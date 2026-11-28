package dev.zephyr.modules.crystal;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.combat.ExplosiveTurn;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * AnchorMacro - respawn-anchor kill chain: PLACE anchor next to the target, CHARGE it
 * with glowstone, (SafeAnchor: put a glowstone SHIELD between you and it), DETONATE it
 * with your sword.
 *
 * <p>Rewritten after a clip showed the old state machine cycling anchor/glowstone/sword
 * every 0.4 s while nothing was placed or charged on the server (item counts never
 * moved): it advanced on the client's own prediction. This version:
 * <ul>
 *   <li>re-reads the world every tick and decides from what is actually there (an anchor
 *       next to the target is reused - charged if empty, detonated if charged);</li>
 *   <li>only counts a step as done when the block state / item count really changed;</li>
 *   <li>never clicks before the (silent) aim has arrived on the face it clicks;</li>
 *   <li>gives a spot that keeps failing a short ignore window instead of looping;</li>
 *   <li>optionally prints in chat which step failed ("Debug messages").</li>
 * </ul>
 */
public class AnchorMacro extends Module {

    protected final NumberSetting range = num("range", "Range", 2.0, 6.0, 0.1, 4.5);
    protected final NumberSetting maxSelfDamage = num("maxSelf", "Max self dmg (no shield)", 0.0, 20.0, 0.5, 8.0);
    protected final NumberSetting minTargetDamage = num("minDmg", "Min target dmg", 0.0, 20.0, 0.5, 4.0);
    protected final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 10, 180, 5, 50);
    protected final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 10, 180, 5, 90);
    protected final BooleanSetting silent = bool("silent", "Silent rotations", true);
    protected final BooleanSetting debug = bool("debug", "Debug messages", false);
    // Kept for config compatibility (old pacing setting); one action per tick is used now.
    protected final NumberSetting paceMin = num("paceMin", "Stage delay min ms", 0, 50, 1, 0);
    protected final NumberSetting paceMax = num("paceMax", "Stage delay max ms", 0, 80, 1, 0);

    /** Anchor we are working on (placed or adopted). */
    protected BlockPos work;
    private int attempts;
    private int glowBefore = -1;
    private int anchorsBefore = -1;
    private final Map<BlockPos, Long> ignored = new HashMap<>();
    private BlockPos shieldFor;
    private int searchTick;
    private String lastStep = "idle";

    public AnchorMacro() {
        this("AnchorMacro");
    }

    protected AnchorMacro(String name) {
        super(name, ModuleCategory.CRYSTAL, 0);
    }

    /** SafeAnchor returns true: always put a glowstone shield up before detonating. */
    protected boolean protectivePlacement() {
        return false;
    }

    @Override
    protected String exclusiveGroup() {
        return "anchor"; // AutoHitCrystal may be on too: they take turns (ExplosiveTurn)
    }

    @Override
    protected boolean conflicts(Module other) {
        return super.conflicts(other) || other instanceof AutoCrystal;
    }

    /** Switched on and holding what a chain needs, so it is worth offering it a turn. */
    public boolean stocked(ClientPlayerEntity self) {
        return isEnabled() && InventoryManager.findHotbarSlot(self, ItemUtil::isRespawnAnchor) != -1
                && InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone) != -1;
    }

    @Override
    protected void onDisable() {
        work = null;
        attempts = 0;
        shieldFor = null;
        RotationManager.clear(0);
    }

    private void fail(String why) {
        if (debug.get() && MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§7[" + name + "] §c" + why), false);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null || client.interactionManager == null || client.currentScreen != null) {
            return;
        }
        AutoHitCrystal crystals = dev.zephyr.ZephyrClient.modules().get(AutoHitCrystal.class);
        if (crystals != null && crystals.isEnabled() && !ExplosiveTurn.anchorMayAct()) {
            return; // a crystal cycle is running: wait for our turn
        }
        long now = System.currentTimeMillis();
        ignored.values().removeIf(t -> t < now);

        LivingEntity target = TargetManager.acquire(client, new TargetCriteria().range(range.get() + 2.0));
        if (target == null || !target.isAlive()) {
            work = null;
            lastStep = "no target";
            return;
        }

        // 1) Validate / adopt the anchor we work on.
        if (work != null && !world.getBlockState(work).isOf(Blocks.RESPAWN_ANCHOR)) {
            if (lastStep.equals("place") && attempts < 4) {
                // placement not confirmed yet - keep trying the same cell below
            } else {
                work = null;
            }
        }
        if (work == null || lastStep.equals("detonated")) {
            work = findExistingAnchor(world, self, target);
            attempts = 0;
            lastStep = work != null ? "adopt" : "search";
        }

        // 2) Nothing to work on: place a new anchor (spot search every 2nd tick).
        if (work == null || !world.getBlockState(work).isOf(Blocks.RESPAWN_ANCHOR)) {
            BlockPos spot = work;
            if (spot == null) {
                if (searchTick++ % 2 != 0) {
                    return;
                }
                spot = findAnchorSpot(world, self, target);
                if (spot == null) {
                    lastStep = "no spot";
                    return;
                }
            }
            placeAnchor(client, self, world, spot);
            return;
        }

        BlockState st = world.getBlockState(work);
        int charges = st.get(Properties.CHARGES);
        if (charges == 0) {
            charge(client, self);
            return;
        }
        if (protectivePlacement() && !work.equals(shieldFor)) {
            shield(client, self, world);
            return;
        }
        detonate(client, self, world, target);
    }

    // ------------------------------------------------------------------
    // Steps (each does at most one click per tick)
    // ------------------------------------------------------------------

    private void placeAnchor(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, BlockPos spot) {
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isRespawnAnchor);
        if (slot == -1) {
            fail("no respawn anchor on hotbar");
            return;
        }
        Vec3d point = Vec3d.ofBottomCenter(spot); // top face of the block under the spot
        if (!aim(self, point)) {
            return;
        }
        if (lastStep.equals("place") && spot.equals(work)) {
            // previous click: did the anchor count go down / block appear?
            if (++attempts > 4) {
                fail("anchor placement rejected at " + spot.toShortString());
                ignored.put(spot.toImmutable(), System.currentTimeMillis() + 3000);
                work = null;
                return;
            }
        } else {
            attempts = 0;
        }
        if (!InventoryManager.selectHotbar(client, slot)) {
            return; // slot change not possible this tick: place next tick, never with the wrong item
        }
        CombatActions.interactBlock(client, RaycastValidator.placementHit(spot.down()));
        work = spot.toImmutable();
        lastStep = "place";
    }

    private void charge(MinecraftClient client, ClientPlayerEntity self) {
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone);
        if (slot == -1) {
            fail("no glowstone on hotbar");
            return;
        }
        Vec3d face = topFace(work);
        if (!aim(self, face)) {
            return;
        }
        int count = countHotbar(self, ItemUtil::isGlowstone);
        if (lastStep.equals("charge")) {
            if (count < glowBefore) {
                attempts = 0; // server took the glowstone; state update is on its way
            } else if (++attempts > 4) {
                fail("charge rejected (glowstone not consumed)");
                ignored.put(work, System.currentTimeMillis() + 3000);
                work = null;
                return;
            }
        } else {
            attempts = 0;
        }
        if (!InventoryManager.selectHotbar(client, slot)) {
            return;
        }
        glowBefore = count;
        CombatActions.interactBlock(client, new BlockHitResult(face, Direction.UP, work, false));
        lastStep = "charge";
    }

    private void shield(MinecraftClient client, ClientPlayerEntity self, ClientWorld world) {
        BlockPos cell = shieldCell(world, self, work);
        if (cell == null) {
            shieldFor = work; // nowhere to put a shield (already covered / no support): go on
            return;
        }
        if (!world.getBlockState(cell).isAir()) {
            shieldFor = work;
            return;
        }
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone);
        if (slot == -1) {
            slot = InventoryManager.findHotbarSlot(self, ItemUtil::isObsidian);
        }
        BlockHitResult hit = supportHit(world, cell);
        if (slot == -1 || hit == null) {
            fail("cannot place shield");
            shieldFor = work;
            return;
        }
        if (!aim(self, hit.getPos())) {
            return;
        }
        if (!InventoryManager.selectHotbar(client, slot)) {
            return;
        }
        CombatActions.interactBlock(client, hit);
        lastStep = "shield";
        if (!world.getBlockState(cell).isAir() || ++attempts > 3) {
            shieldFor = work;
            attempts = 0;
        }
    }

    private void detonate(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        Vec3d blast = Vec3d.ofCenter(work);
        if (!protectivePlacement() && DamageCalculator.anchorDamage(world, blast, self) > maxSelfDamage.get()) {
            lastStep = "too close";
            return; // would hurt us too much without a shield - wait for spacing
        }
        if (target.getEntityPos().distanceTo(blast) > 4.5) {
            lastStep = "target away";
            return;
        }
        // Detonate with the sword (anything but glowstone works; never a block).
        int slot = InventoryManager.findHotbarSlot(self,
                s -> ItemUtil.id(s).endsWith("_sword") || ItemUtil.id(s).endsWith("_axe"));
        if (slot == -1) {
            slot = InventoryManager.findHotbarSlot(self,
                    s -> !ItemUtil.isGlowstone(s) && !(s.getItem() instanceof net.minecraft.item.BlockItem));
        }
        if (slot == -1) {
            fail("no detonation item (sword) on hotbar");
            return;
        }
        Vec3d face = topFace(work);
        if (!aim(self, face)) {
            return;
        }
        if (!InventoryManager.selectHotbar(client, slot)) {
            return; // detonating with glowstone in hand would charge instead of explode
        }
        CombatActions.interactBlock(client, new BlockHitResult(face, Direction.UP, work, false));
        lastStep = "detonated";
        shieldFor = null;
        ExplosiveTurn.anchorFinished();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Vec3d topFace(BlockPos pos) {
        return Vec3d.ofCenter(pos).add(0, 0.5, 0);
    }

    private boolean aim(ClientPlayerEntity self, Vec3d point) {
        ExplosiveTurn.anchorProgress();
        RotationManager.request(RotationRequest
                .to(MathUtil.yawTo(self.getEyePos(), point), MathUtil.pitchTo(self.getEyePos(), point))
                .speed(rotMin.get(), rotMax.get())
                .jitter(0.3)
                .silent(silent.get())
                .validWhile(this::isEnabled)
                .expiresAfter(6)
                .build());
        return RotationManager.isAimedAt(point, 12.0);
    }

    private static int countHotbar(ClientPlayerEntity self, java.util.function.Predicate<net.minecraft.item.ItemStack> p) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            var s = self.getInventory().getStack(i);
            if (p.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** An anchor already standing within blast reach of the target, in our reach. */
    private BlockPos findExistingAnchor(ClientWorld world, ClientPlayerEntity self, LivingEntity target) {
        BlockPos feet = target.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterateOutwards(feet, 2, 1, 2)) {
            if (!world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR) || ignored.containsKey(p)) {
                continue;
            }
            if (self.getEyePos().distanceTo(Vec3d.ofCenter(p)) > range.get()) {
                continue;
            }
            double d = Vec3d.ofCenter(p).squaredDistanceTo(target.getEntityPos());
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    /** Best empty cell next to the target for a new anchor. */
    protected BlockPos findAnchorSpot(ClientWorld world, ClientPlayerEntity self, LivingEntity target) {
        Vec3d predicted = dev.zephyr.core.crystal.CrystalSolver.predictPos(target, 2);
        BlockPos center = BlockPos.ofFloored(predicted);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : BlockPos.iterateOutwards(center, 2, 1, 2)) {
            if (ignored.containsKey(pos) || !world.getBlockState(pos).isAir()) {
                continue;
            }
            if (!RaycastValidator.canPlaceAnchor(world, pos.down(), self)) {
                continue;
            }
            if (self.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > range.get()) {
                continue;
            }
            Box cell = new Box(pos);
            if (self.getBoundingBox().intersects(cell) || target.getBoundingBox().intersects(cell)) {
                continue; // cannot place a block inside a player
            }
            Vec3d blast = Vec3d.ofCenter(pos);
            double targetDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.ANCHOR_STRENGTH, predicted, target);
            if (targetDmg < minTargetDamage.get()) {
                continue;
            }
            double selfDmg = DamageCalculator.anchorDamage(world, blast, self);
            if (!protectivePlacement() && selfDmg > maxSelfDamage.get()) {
                continue;
            }
            // Between us and the target is where a hand player puts it.
            double score = targetDmg * 2.0 - (protectivePlacement() ? 0 : selfDmg)
                    - self.getEyePos().distanceTo(blast);
            if (score > bestScore) {
                bestScore = score;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    /** The floor cell touching the anchor on our side (the clip's glowstone shield spot). */
    private static BlockPos shieldCell(ClientWorld world, ClientPlayerEntity self, BlockPos anchor) {
        double dx = self.getX() - (anchor.getX() + 0.5);
        double dz = self.getZ() - (anchor.getZ() + 0.5);
        Direction major = Math.abs(dx) >= Math.abs(dz)
                ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction minor = Math.abs(dx) >= Math.abs(dz)
                ? (dz > 0 ? Direction.SOUTH : Direction.NORTH) : (dx > 0 ? Direction.EAST : Direction.WEST);
        for (Direction d : new Direction[]{major, minor}) {
            BlockPos cell = anchor.offset(d);
            if (!world.getBlockState(cell).isAir()) {
                return cell; // already shielded on this side
            }
            if (!self.getBoundingBox().intersects(new Box(cell)) && supportHit(world, cell) != null) {
                return cell;
            }
        }
        return null;
    }

    /** A click on a solid neighbour's face that places a block into {@code cell}. */
    private static BlockHitResult supportHit(ClientWorld world, BlockPos cell) {
        for (Direction dir : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            BlockPos support = cell.offset(dir);
            var st = world.getBlockState(support);
            if (st.isAir() || !st.isSolidBlock(world, support) || st.isOf(Blocks.RESPAWN_ANCHOR)) {
                continue; // clicking an anchor would charge/detonate it instead of placing
            }
            Direction face = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5,
                    face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            return new BlockHitResult(hitVec, face, support, false);
        }
        return null;
    }

    @Override
    public String hudLabel() {
        return name + " · " + lastStep;
    }
}
