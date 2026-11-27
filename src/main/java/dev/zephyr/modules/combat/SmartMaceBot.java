package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.input.InputOverride;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * SmartMaceBot - autonomous mace/melee bot without elytra flight.
 *
 * <p>Every tick it builds an immutable {@link Situation} (distance, line of sight, height
 * advantage, fall velocity, fall distance, hurt time, cooldown, shield state, available
 * weapons) and {@link #decide} maps it to one {@link Intent}. Executors then act through the
 * existing managers only:
 * <ul>
 *   <li><b>STRIKE</b> - airborne, descending, enough banked fall, target inside reach:
 *       {@link CombatActions#attributeSwapAttack} with the best mace for THIS hit (density /
 *       breach / wind-burst scoring against the target's armour).</li>
 *   <li><b>LAUNCH</b> - no height advantage: a multi-tick sequence (select wind charge, jump,
 *       throw it at the feet at the jump apex with a silent pitch-90 interact, restore the slot)
 *       that turns a flat fight into a smash drop. Gated on health, headroom, cooldown and range.</li>
 *   <li><b>TRACK_AIR</b> - mid-air: keep the view on the target, air-steer toward it, wait for the
 *       descent (Wind Burst relaunches land here too, so the mace chain continues by itself).</li>
 *   <li><b>MELEE</b> - grounded, in reach, no better option: charged sword/axe (or mace) hit.</li>
 *   <li><b>APPROACH</b> - walk/sprint to the target with edge and obstacle checks.</li>
 * </ul>
 * Rotation priority 4, visible rotations, one attack per tick, one slot change per tick; the
 * module stands down while gliding (that is ElytraMaceBot's job) and while a shield-break is due.
 */
public final class SmartMaceBot extends Module {

    private static final int PRIORITY = 4;

    private final NumberSetting range = num("range", "Target range", 6, 40, 1, 20);
    private final NumberSetting reach = num("reach", "Strike reach", 2.5, 3.0, 0.05, 2.95);
    private final NumberSetting minFall = num("minFall", "Min fall distance to smash", 1.5, 15, 0.5, 3.0);
    private final NumberSetting maxHurt = num("hurtTime", "Max target hurt time", 0, 10, 1, 3);
    private final NumberSetting meleeCooldown = num("meleeCooldown", "Melee min charge", 0.5, 1.0, 0.05, 0.92);
    private final NumberSetting wantHeight = num("wantHeight", "Wanted height over target", 2, 12, 0.5, 4.0);
    private final NumberSetting launchRange = num("launchRange", "Wind charge launch range", 3, 24, 1, 14);
    private final NumberSetting windGap = num("windGap", "Min ticks between launches", 10, 100, 1, 30);
    private final NumberSetting minHealth = num("minHealth", "Min health to launch", 2, 20, 1, 8);
    private final NumberSetting breachArmor = num("breachArmor", "Breach mace from armour", 0, 20, 1, 10);
    private final NumberSetting rotSpeedMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 15);
    private final NumberSetting rotSpeedMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 40);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.4);
    private final NumberSetting aimTol = num("aimTol", "Strike aim tolerance deg", 2, 30, 1, 10);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 2);
    private final BooleanSetting useWind = bool("windCharge", "Wind charge launch", true);
    private final BooleanSetting preferWindBurst = bool("preferWindBurst", "Prefer Wind Burst mace", true);
    private final BooleanSetting approach = bool("approach", "Walk toward target", true);
    private final BooleanSetting meleeFallback = bool("melee", "Melee when no smash", true);
    private final BooleanSetting airControl = bool("airControl", "Steer in the air", true);
    private final BooleanSetting respectShield = bool("respectShield", "Hold strike into raised shield", true);

    private enum Intent { IDLE, STRIKE, LAUNCH, TRACK_AIR, MELEE, APPROACH, HOLD }

    private enum Launch { NONE, SELECT, JUMP, THROW, SETTLE }

    /** Immutable per-tick snapshot the decision function reads. */
    private record Situation(LivingEntity target, double reachDist, double horiz, double heightAdv,
                             boolean los, boolean airborne, boolean rising, boolean descending,
                             double fall, boolean shield, int armor, boolean maceAvailable,
                             boolean windAvailable, boolean meleeAvailable, double cooldown,
                             boolean headroom) {
    }

    private Intent intent = Intent.IDLE;
    private Launch launch = Launch.NONE;
    private int launchTicks;
    private int settleTicks;
    private int launchHome = -1;
    private int sinceWind = 999;
    private boolean slamPending;
    private int strikes;
    private int launches;
    private long actTick = Long.MIN_VALUE;

    public SmartMaceBot() {
        super("SmartMaceBot", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected String exclusiveGroup() {
        return "mace";
    }

    /** CombatCoordinator hook: a drop/launch/strike on the current target is being set up. */
    public boolean slamPending() {
        return slamPending;
    }

    @Override
    protected void onDisable() {
        MinecraftClient client = MinecraftClient.getInstance();
        endLaunch(client);
        launch = Launch.NONE; // disabled: no further retries, whatever the slot restore did
        launchHome = -1;
        slamPending = false;
        intent = Intent.IDLE;
        RotationManager.clear(PRIORITY);
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        launch = Launch.NONE;
        launchHome = -1;
        slamPending = false;
        intent = Intent.IDLE;
    }

    // ------------------------------------------------------------------
    // Tick: sense -> decide -> act
    // ------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null || client.interactionManager == null || !self.isAlive()) {
            return;
        }
        sinceWind++;
        slamPending = false;

        if (self.isGliding() || client.currentScreen != null) {
            intent = Intent.IDLE; // elytra flight belongs to ElytraMaceBot / the player
            abandonLaunch(client);
            return;
        }
        if (findMace(self, 0.0, 0) == -1) {
            self.sendMessage(Text.literal("[Zephyr] SmartMaceBot: no mace in the hotbar."), true);
            setEnabled(false);
            return;
        }
        LivingEntity target = TargetManager.acquire(client, new TargetCriteria().range(range.get()));
        TargetManager.cleanup();
        if (target == null) {
            intent = Intent.IDLE;
            abandonLaunch(client);
            return;
        }

        Situation s = sense(client, self, world, target);
        intent = decide(self, s);
        slamPending = (intent == Intent.STRIKE || intent == Intent.TRACK_AIR || intent == Intent.LAUNCH)
                && s.maceAvailable();

        switch (intent) {
            case STRIKE -> executeStrike(client, self, s);
            case LAUNCH -> executeLaunch(client, self, s);
            case TRACK_AIR -> executeTrackAir(self, s);
            case MELEE -> executeMelee(client, self, s);
            case APPROACH -> executeApproach(self, world, s);
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Sense
    // ------------------------------------------------------------------

    private Situation sense(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity t) {
        Vec3d eye = self.getEyePos();
        Vec3d aim = MathUtil.aimPoint(t);
        double reachDist = Math.sqrt(t.getBoundingBox().squaredMagnitude(eye));
        double vy = self.getVelocity().y;
        boolean airborne = !self.isOnGround() && !self.isTouchingWater() && !self.isInLava()
                && !self.isClimbing() && !self.hasVehicle();
        boolean los = RaycastValidator.canSee(eye, aim, self);
        boolean headroom = RaycastValidator.canSee(eye, eye.add(0.0, 9.0, 0.0), self);
        return new Situation(t, reachDist, Math.hypot(t.getX() - self.getX(), t.getZ() - self.getZ()),
                self.getY() - t.getY(), los, airborne, vy > 0.05, vy < -0.1, self.fallDistance,
                CombatCoordinator.shieldBlocks(t, self), t.getArmor(), findMace(self, 0.0, 0) != -1,
                InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge) != -1,
                meleeSlot(self) != -1, ItemUtil.attackCooldownProgress(self), headroom);
    }

    // ------------------------------------------------------------------
    // Decide (pure: Situation in, Intent out)
    // ------------------------------------------------------------------

    private Intent decide(ClientPlayerEntity self, Situation s) {
        if (CombatCoordinator.shieldBreakerWants(MinecraftClient.getInstance(), s.target())) {
            return Intent.HOLD; // ShieldBreaker opens the guard first (stun slam)
        }
        double r = reach.get();
        if (s.airborne()) {
            if (launch != Launch.NONE) {
                return Intent.LAUNCH; // finish the throw while still on the way up
            }
            return canSmash(s) ? Intent.STRIKE : Intent.TRACK_AIR;
        }
        // Grounded. A launch already under way keeps priority (no flicker between intents).
        if (launch != Launch.NONE) {
            return Intent.LAUNCH;
        }
        boolean inReach = s.reachDist() <= r;
        boolean enoughHeight = s.heightAdv() >= wantHeight.get();
        if (!enoughHeight && canLaunch(self, s) && launchBeatsMelee(self, s, inReach)) {
            return Intent.LAUNCH;
        }
        if (inReach && meleeFallback.get() && s.meleeAvailable() && s.los()) {
            return Intent.MELEE;
        }
        if (approach.get() && s.reachDist() > r * 0.8) {
            return Intent.APPROACH;
        }
        return Intent.HOLD;
    }

    /** All gates for a smash strike this tick (the executor adds the aim/LOS convergence). */
    private boolean canSmash(Situation s) {
        return s.descending() && s.fall() >= minFall.get() && s.reachDist() <= reach.get()
                && s.heightAdv() > -0.5 && s.los()
                && s.target().hurtTime <= maxHurt.get().intValue()
                && !(respectShield.get() && s.shield());
    }

    private boolean canLaunch(ClientPlayerEntity self, Situation s) {
        return useWind.get() && s.windAvailable() && s.headroom() && sinceWind >= windGap.get()
                && self.getHealth() + self.getAbsorptionAmount() >= minHealth.get()
                && s.horiz() <= launchRange.get() && s.heightAdv() < 2.0
                && !ItemUtil.coolingDown(self, windStack(self));
    }

    /**
     * Compares the expected damage of a wind-charge drop (smash bonus at a typical 7-block fall,
     * discounted for the ~1.5 s it takes and for target movement) against hitting now with the
     * best melee weapon. Fixed weapon values: axe 9, sword 7, mace 6 (base damage, 1.21.x).
     */
    private boolean launchBeatsMelee(ClientPlayerEntity self, Situation s, boolean inReach) {
        if (!inReach || !s.meleeAvailable() || !meleeFallback.get()) {
            return true; // nothing to hit with right now: launching is the only way to deal damage
        }
        double meleeNow = meleeDamage(self.getInventory().getStack(meleeSlot(self))) * Math.min(1.0, s.cooldown() + 0.25);
        double drop = (6.0 + smashBonus(7.0)) * 0.6;
        return drop > meleeNow * 1.5;
    }

    /** Vanilla mace smash bonus: 4/block up to 3, 2/block up to 8, 1/block beyond. */
    private static double smashBonus(double fall) {
        if (fall <= 1.5) {
            return 0.0;
        }
        return fall <= 3.0 ? 4.0 * fall : fall <= 8.0 ? 12.0 + 2.0 * (fall - 3.0) : 22.0 + (fall - 8.0);
    }

    private static double meleeDamage(ItemStack s) {
        String id = ItemUtil.id(s);
        return id.endsWith("_axe") ? 9.0 : id.endsWith("_sword") ? 7.0 : 6.0;
    }

    // ------------------------------------------------------------------
    // Execute: STRIKE
    // ------------------------------------------------------------------

    private void executeStrike(MinecraftClient client, ClientPlayerEntity self, Situation s) {
        LivingEntity t = s.target();
        Vec3d eye = self.getEyePos();
        Vec3d aim = MathUtil.bestAimPoint(self, t, RotationManager.currentYaw(), self.getPitch());
        steer(MathUtil.yawTo(eye, aim), MathUtil.pitchTo(eye, aim));
        if (acted() || !RotationManager.isAimedAt(aim, aimTol.get())) {
            return;
        }
        int slot = findMace(self, s.fall(), s.armor());
        if (slot != -1 && CombatActions.attributeSwapAttack(client, slot, t, backTicks.get().intValue())) {
            noteAct();
            strikes++;
        }
    }

    /**
     * Mace choice for this hit. Score = base 6 + smash bonus + density (0.5 per block per level),
     * reduced by the share of damage the target's armour absorbs (4 % per point, max 80 %) except
     * for what Breach (15 % per level) pierces, plus a flat bonus for Wind Burst (the relaunch
     * keeps the chain going). Breach is only considered at or above the armour threshold.
     */
    private int findMace(ClientPlayerEntity self, double fall, int armor) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 9; i++) {
            ItemStack st = self.getInventory().getStack(i);
            if (!ItemUtil.isMace(st)) {
                continue;
            }
            int density = ItemUtil.enchantLevel(st, "density");
            int breach = armor >= breachArmor.get() ? ItemUtil.enchantLevel(st, "breach") : 0;
            int wind = ItemUtil.enchantLevel(st, "wind_burst");
            double base = 6.0 + smashBonus(fall) + 0.5 * density * Math.max(0.0, fall);
            double absorbed = Math.min(0.8, armor * 0.04) * (1.0 - Math.min(1.0, 0.15 * breach));
            double score = base * (1.0 - absorbed) + (wind > 0 && preferWindBurst.get() ? 3.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Execute: LAUNCH (wind charge drop sequence)
    // ------------------------------------------------------------------

    private void executeLaunch(MinecraftClient client, ClientPlayerEntity self, Situation s) {
        if (launch == Launch.NONE) {
            launch = Launch.SELECT;
            launchTicks = 0;
            settleTicks = 0;
            launchHome = SwapManager.homeSlot(self);
        }
        launchTicks++;
        faceTarget(self, s.target());
        int wind = InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge);
        if (launchTicks > 40 || (wind == -1 && launch != Launch.SETTLE)) {
            endLaunch(client);
            return;
        }
        switch (launch) {
            case SELECT -> {
                if (self.getInventory().getSelectedSlot() == wind || InventoryManager.selectHotbar(client, wind)) {
                    launch = Launch.JUMP;
                }
            }
            case JUMP -> {
                if (!self.isOnGround()) {
                    launch = Launch.THROW;
                } else if (self.getInventory().getSelectedSlot() != wind) {
                    launch = Launch.SELECT;
                } else {
                    InputOverride.requestJump();
                }
            }
            case THROW -> {
                if (!ItemUtil.isWindCharge(self.getMainHandStack())) {
                    launch = Launch.SELECT;
                } else if (self.getVelocity().y <= 0.12 && !acted()
                        && CombatActions.interactItemAt(client, self.getYaw(), 90.0f).isAccepted()) {
                    noteAct();
                    sinceWind = 0;
                    launches++;
                    launch = Launch.SETTLE;
                    settleTicks = 0;
                } else if (self.isOnGround()) {
                    launch = Launch.JUMP; // landed before the throw: jump again
                }
            }
            case SETTLE -> {
                if (++settleTicks >= 3) {
                    endLaunch(client);
                }
            }
            default -> { }
        }
    }

    /** Restores the pre-launch hotbar slot (retried each tick) and clears the sequence. */
    private void endLaunch(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self != null && launchHome != -1 && self.getInventory().getSelectedSlot() != launchHome
                && !InventoryManager.selectHotbar(client, launchHome) && launchTicks < 60) {
            launchTicks++;
            return; // slot budget used this tick: retry next tick, sequence stays alive
        }
        launch = Launch.NONE;
        launchHome = -1;
        launchTicks = 0;
    }

    /** Cancels a sequence that no longer applies (gliding, no target, screen open). */
    private void abandonLaunch(MinecraftClient client) {
        if (launch != Launch.NONE) {
            endLaunch(client);
        }
    }

    private ItemStack windStack(ClientPlayerEntity self) {
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge);
        return slot == -1 ? ItemStack.EMPTY : self.getInventory().getStack(slot);
    }

    // ------------------------------------------------------------------
    // Execute: TRACK_AIR / MELEE / APPROACH
    // ------------------------------------------------------------------

    private void executeTrackAir(ClientPlayerEntity self, Situation s) {
        faceTarget(self, s.target());
        if (!airControl.get() || s.horiz() < 1.6) {
            return;
        }
        Vec3d eye = self.getEyePos();
        double yawErr = Math.abs(MathUtil.angleDelta(self.getYaw(), MathUtil.yawTo(eye, MathUtil.aimPoint(s.target()))));
        // Air control is only 2 % of ground control: push forward only while facing the target.
        if (yawErr < 35.0 && (s.descending() || s.heightAdv() > 1.0)) {
            InputOverride.requestForward(false);
        }
    }

    private void executeMelee(MinecraftClient client, ClientPlayerEntity self, Situation s) {
        LivingEntity t = s.target();
        Vec3d eye = self.getEyePos();
        Vec3d aim = MathUtil.bestAimPoint(self, t, RotationManager.currentYaw(), self.getPitch());
        steer(MathUtil.yawTo(eye, aim), MathUtil.pitchTo(eye, aim));
        if (acted() || s.shield() || t.hurtTime > 5) {
            return;
        }
        int slot = meleeSlot(self);
        if (slot == -1) {
            return;
        }
        // A slot change resets the swing charge, so select the weapon first and hit once charged.
        if (self.getInventory().getSelectedSlot() != slot) {
            if (SwapManager.select(client, slot)) {
                noteAct();
            }
            return;
        }
        if (ItemUtil.attackCooldownProgress(self) < meleeCooldown.get()
                || !RotationManager.isAimedAt(aim, aimTol.get())) {
            return;
        }
        if (CombatActions.attackEntity(client, t)) {
            noteAct();
        }
    }

    private void executeApproach(ClientPlayerEntity self, ClientWorld world, Situation s) {
        faceTarget(self, s.target());
        Vec3d eye = self.getEyePos();
        double yawErr = Math.abs(MathUtil.angleDelta(self.getYaw(), MathUtil.yawTo(eye, MathUtil.aimPoint(s.target()))));
        if (yawErr > 40.0) {
            return; // turn first, then walk: no sideways drift
        }
        boolean dropOk = s.heightAdv() >= 3.0; // deliberately stepping off an edge toward a lower target
        if (!dropOk && !groundAhead(world, self)) {
            return;
        }
        InputOverride.requestForward(true);
        if (self.horizontalCollision && self.isOnGround() && !InputOverride.jumpedLastTick()) {
            InputOverride.requestJump(); // step/auto-jump over a one-block obstacle
        }
    }

    /** Solid, non-lava ground within 4 blocks below the spot 1.5 blocks ahead. */
    private boolean groundAhead(ClientWorld world, ClientPlayerEntity self) {
        Vec3d ahead = self.getEntityPos().add(Vec3d.fromPolar(0.0f, self.getYaw()).multiply(1.5));
        BlockPos start = BlockPos.ofFloored(ahead);
        for (int i = 0; i <= 4; i++) {
            BlockPos p = start.down(i);
            BlockState st = world.getBlockState(p);
            if (st.getFluidState().isIn(FluidTags.LAVA)) {
                return false;
            }
            if (!st.getCollisionShape(world, p).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Best hotbar melee weapon: axe, then sword, then any mace; -1 if none. */
    private int meleeSlot(ClientPlayerEntity self) {
        int best = -1;
        double bestDmg = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = self.getInventory().getStack(i);
            String id = ItemUtil.id(st);
            if (id.endsWith("_axe") || id.endsWith("_sword") || ItemUtil.isMace(st)) {
                double d = meleeDamage(st);
                if (d > bestDmg) {
                    bestDmg = d;
                    best = i;
                }
            }
        }
        return best;
    }

    private void faceTarget(ClientPlayerEntity self, LivingEntity t) {
        Vec3d eye = self.getEyePos();
        Vec3d aim = MathUtil.aimPoint(t);
        steer(MathUtil.yawTo(eye, aim), MathUtil.pitchTo(eye, aim));
    }

    private void steer(double yaw, double pitch) {
        RotationManager.request(RotationRequest.to(yaw, MathHelper.clamp(pitch, -90.0, 90.0))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false)
                .priority(PRIORITY)
                .validWhile(this::isEnabled)
                .expiresAfter(3)
                .build());
    }

    private boolean acted() {
        return actTick == TickPhase.tickId();
    }

    private void noteAct() {
        actTick = TickPhase.tickId();
    }

    @Override
    public String hudLabel() {
        return name + " · " + intent.name().toLowerCase() + " · " + strikes + (launches > 0 ? "/" + launches : "");
    }
}
