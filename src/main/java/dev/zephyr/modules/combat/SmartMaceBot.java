package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.combat.MaceChoice;
import dev.zephyr.core.combat.PearlIntercept;
import dev.zephyr.core.inventory.ElytraLocator;
import dev.zephyr.core.inventory.GearSlots;
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
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * SmartMaceBot - autonomous mace/melee bot that fights from above, without rockets.
 *
 * <p>Height is what it plays for: below the target it does not trade hits, it gets back up
 * (wind charge, or a pearl caught by a wind charge for a much higher drop); above it, it stays
 * up and drops onto them. A raised shield is opened with an axe on the way down (stun slam).
 * With an elytra in the inventory it glides across a gap, takes the elytra off over the target
 * and smashes. A missed fall is broken with a wind charge at the feet.
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
    private final NumberSetting launchRange = num("launchRange", "Wind charge launch range", 3, 12, 1, 9);
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
    /** Never trade hits from below while there is a way to get above. */
    private final BooleanSetting highGround = bool("highGround", "Fight for high ground", true);
    private final NumberSetting belowBy = num("belowBy", "Counts as below (blocks)", 0.2, 3.0, 0.1, 0.5);
    private final BooleanSetting usePearl = bool("pearlCatch", "Pearl catch", true);
    private final NumberSetting pearlGap = num("pearlGap", "Pearl to wind charge ticks", 1, 8, 1, 2);
    private final NumberSetting pearlRange = num("pearlRange", "Pearl catch range", 3, 24, 1, 12);
    private final NumberSetting pearlEvery = num("pearlEvery", "Ticks between pearl catches", 30, 400, 10, 100);
    private final NumberSetting pearlHealth = num("pearlHealth", "Min health to pearl", 6, 20, 1, 12);
    private final BooleanSetting stunSlam = bool("stunSlam", "Stun slam (axe, then mace)", true);
    private final BooleanSetting useElytra = bool("elytra", "Glide with elytra (no rockets)", true);
    private final NumberSetting glideFrom = num("glideFrom", "Glide if farther than", 2.0, 10.0, 0.5, 3.5);
    private final BooleanSetting fallSave = bool("fallSave", "Wind charge fall save", true);
    private final dev.zephyr.core.target.TargetLock lock = new dev.zephyr.core.target.TargetLock();

    private enum Intent { IDLE, STRIKE, LAUNCH, PEARL, GLIDE, TRACK_AIR, MELEE, APPROACH, HOLD }

    private enum Pearl { NONE, THROW, CATCH }

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
    private Pearl pearl = Pearl.NONE;
    private int pearlTicks;
    private int sincePearl = 999;
    private int pearlHome = -1;
    private int pearlCatches;
    private int stunSlams;
    private int fallSaves;
    private int glides;
    private boolean gliding;
    private int glideTicks;
    /** We put the elytra on, so we take it off again; one the player wears is left alone. */
    private boolean elytraOurs;

    public SmartMaceBot() {
        super("SmartMaceBot", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected String exclusiveGroup() {
        return "mace";
    }

    /** Strikes, wind launches, pearl catches, stun slams, glides, fall saves. */
    public int[] counts() {
        return new int[]{strikes, launches, pearlCatches, stunSlams, glides, fallSaves};
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
        pearl = Pearl.NONE;
        gliding = false;
        slamPending = false;
        intent = Intent.IDLE;
        lock.clear();
        RotationManager.clear(PRIORITY);
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        launch = Launch.NONE;
        launchHome = -1;
        pearl = Pearl.NONE;
        gliding = false;
        elytraOurs = false;
        slamPending = false;
        intent = Intent.IDLE;
        lock.clear();
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
        sincePearl++;
        slamPending = false;

        // A glide we did not start belongs to ElytraMaceBot or the player.
        if (self.isGliding() && !gliding || client.currentScreen != null) {
            intent = Intent.IDLE; // elytra flight belongs to ElytraMaceBot / the player
            abandonLaunch(client);
            return;
        }
        if (findMace(self, 0.0, 0) == -1) {
            self.sendMessage(Text.literal("[Zephyr] SmartMaceBot: no mace in the hotbar."), true);
            setEnabled(false);
            return;
        }
        // Own lock (see TargetLock): other modules no longer flip this bot's target.
        LivingEntity target = lock.update(client, range.get(), false);
        if (target == null) {
            intent = Intent.IDLE;
            abandonLaunch(client);
            return;
        }

        Situation s = sense(client, self, world, target);
        // The glide is over once we are down or the elytra is back off.
        if (gliding && (self.isOnGround() || self.isTouchingWater())) {
            gliding = false;
        }
        if (!self.isOnGround() && fallSave.get() && breakFall(client, self, world, s)) {
            return;
        }
        // Put the chestplate back once we are not flying.
        if (!gliding && self.isOnGround() && elytraOurs) {
            if (!GearSlots.wearingElytra(self)) {
                elytraOurs = false;
            } else if (swapChest(client, self)) {
                return;
            }
        }
        intent = decide(self, s);
        slamPending = (intent == Intent.STRIKE || intent == Intent.TRACK_AIR || intent == Intent.LAUNCH
                || intent == Intent.PEARL || intent == Intent.GLIDE) && s.maceAvailable();

        switch (intent) {
            case STRIKE -> executeStrike(client, self, s);
            case PEARL -> executePearl(client, self, s);
            case GLIDE -> executeGlide(client, self, s);
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
        if (!stunSlam.get() && CombatCoordinator.shieldBreakerWants(MinecraftClient.getInstance(), s.target())) {
            return Intent.HOLD; // ShieldBreaker opens the guard first (stun slam)
        }
        if (pearl != Pearl.NONE) {
            return Intent.PEARL;
        }
        double r = reach.get();
        if (s.airborne()) {
            if (launch != Launch.NONE) {
                return Intent.LAUNCH; // finish the throw while still on the way up
            }
            if (gliding) {
                return Intent.GLIDE;
            }
            if (canSmash(s)) {
                return Intent.STRIKE;
            }
            return wantsGlide(self, s) ? Intent.GLIDE : Intent.TRACK_AIR;
        }
        // Grounded. A launch already under way keeps priority (no flicker between intents).
        if (launch != Launch.NONE) {
            return Intent.LAUNCH;
        }
        boolean inReach = s.reachDist() <= r;
        boolean enoughHeight = s.heightAdv() >= wantHeight.get();
        boolean below = highGround.get() && s.heightAdv() < -belowBy.get();
        // A pearl catch goes far higher than a wind charge and covers ground: used when they
        // are above us, or too far for a wind charge to bring us over them.
        if (!enoughHeight && canPearl(self, s) && (below || s.horiz() > launchRange.get())) {
            return Intent.PEARL;
        }
        // A wind charge is a vertical boost and air control is ~2% of ground control, so the
        // descent can only close a few blocks. Launching from far away (the old default was
        // 14) made every descent sail past the target and the bot just bounced up and down
        // without ever landing the smash - launch from smash range, walk the rest.
        if (!enoughHeight && s.horiz() <= launchRange.get() && canLaunch(self, s)
                && (below || launchBeatsMelee(self, s, inReach))) {
            return Intent.LAUNCH;
        }
        if (highGround.get() && s.heightAdv() >= minFall.get() && s.horiz() > 1.0) {
            return Intent.APPROACH; // we hold the height: walk off the edge and smash on the way down
        }
        // From below, hold back while a way up is only a cooldown away.
        if (below && wayUpSoon(self, s)) {
            return s.horiz() > launchRange.get() ? Intent.APPROACH : Intent.HOLD;
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
        // Strike a hair inside the client-side reach: the server sees us a round trip behind,
        // and a hit sent at exactly the 3.0 interaction limit was regularly dropped as out
        // of range, which is why the smash used to whiff right at the target.
        double r = Math.max(2.4, reach.get() - 0.2);
        return s.descending() && s.fall() >= minFall.get() && s.reachDist() <= r
                && s.heightAdv() > -0.5 && s.los()
                && s.target().hurtTime <= maxHurt.get().intValue()
                && !(respectShield.get() && s.shield() && !canStun());
    }

    /** An axe is on the bar to open a shield with. */
    private boolean canStun() {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        return stunSlam.get() && self != null && axeSlot(self) != -1;
    }

    private static int axeSlot(ClientPlayerEntity self) {
        return MaceChoice.axeSlot(self);
    }

    private boolean canLaunch(ClientPlayerEntity self, Situation s) {
        return useWind.get() && s.windAvailable() && s.headroom() && sinceWind >= windGap.get()
                && self.getHealth() + self.getAbsorptionAmount() >= minHealth.get()
                && s.horiz() <= launchRange.get() && s.heightAdv() < 2.0
                && !ItemUtil.coolingDown(self, windStack(self));
    }

    /** Room, items, health and cooldowns for a pearl catch. */
    private boolean canPearl(ClientPlayerEntity self, Situation s) {
        if (!usePearl.get() || sincePearl < pearlEvery.get() || !s.windAvailable() || !self.isOnGround()
                || self.getHealth() + self.getAbsorptionAmount() < pearlHealth.get()
                || s.horiz() > pearlRange.get()) {
            return false;
        }
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isPearl);
        if (slot == -1 || ItemUtil.coolingDown(self, self.getInventory().getStack(slot))
                || ItemUtil.coolingDown(self, windStack(self))) {
            return false;
        }
        Vec3d eye = self.getEyePos();
        return RaycastValidator.canSee(eye, eye.add(0.0, 18.0, 0.0), self);
    }

    /** A wind charge or pearl will be usable within a second or so. */
    private boolean wayUpSoon(ClientPlayerEntity self, Situation s) {
        boolean wind = useWind.get() && s.windAvailable() && s.headroom()
                && self.getHealth() + self.getAbsorptionAmount() >= minHealth.get()
                && sinceWind >= windGap.get() - 20;
        boolean pearls = usePearl.get() && s.windAvailable() && sincePearl >= pearlEvery.get() - 20
                && self.getHealth() + self.getAbsorptionAmount() >= pearlHealth.get()
                && InventoryManager.findHotbarSlot(self, ItemUtil::isPearl) != -1;
        return wind || pearls;
    }

    // ------------------------------------------------------------------
    // Execute: PEARL (throw it up, catch it with a wind charge)
    // ------------------------------------------------------------------

    private void executePearl(MinecraftClient client, ClientPlayerEntity self, Situation s) {
        if (pearl == Pearl.NONE) {
            pearl = Pearl.THROW;
            pearlTicks = 0;
            pearlHome = SwapManager.homeSlot(self);
        }
        pearlTicks++;
        faceTarget(self, s.target());
        if (pearlTicks > 30) {
            endPearl(client);
            return;
        }
        if (acted()) {
            return;
        }
        Vec3d to = s.target().getEntityPos().subtract(self.getEntityPos());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        if (pearl == Pearl.THROW) {
            int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isPearl);
            if (slot == -1) {
                endPearl(client);
                return;
            }
            // Lean the throw so the catch, about 14 blocks up, ends over the target. Capped: a
            // missed catch lets the pearl fly on, and a steep lean would drop us out of the fight.
            float lean = (float) Math.min(20.0, Math.toDegrees(Math.atan2(s.horiz(), 14.0)));
            if (CombatActions.interactItemFromSlot(client, slot, ItemUtil::isPearl, yaw, -(90.0f - lean))
                    != ActionResult.FAIL) {
                noteAct();
                sincePearl = 0;
                pearl = Pearl.CATCH;
                pearlTicks = 0;
            }
            return;
        }
        if (pearlTicks < pearlGap.get().intValue()) {
            return;
        }
        EnderPearlEntity mine = ownPearl(client, self);
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge);
        if (mine == null || slot == -1) {
            return;
        }
        float[] aim = PearlIntercept.solve(self, mine, CombatCoordinator.pingTicks(client));
        if (aim != null && CombatActions.interactItemFromSlot(client, slot, ItemUtil::isWindCharge, aim[0], aim[1])
                != ActionResult.FAIL) {
            noteAct();
            sinceWind = 0;
            pearlCatches++;
            pearl = Pearl.NONE;
            restoreSlot(client, pearlHome);
        }
    }

    private void endPearl(MinecraftClient client) {
        pearl = Pearl.NONE;
        restoreSlot(client, pearlHome);
    }

    private static void restoreSlot(MinecraftClient client, int slot) {
        if (SwapManager.isValidSlot(slot)) {
            InventoryManager.selectHotbar(client, slot); // refused means this tick's change is used
        }
    }

    private static EnderPearlEntity ownPearl(MinecraftClient client, ClientPlayerEntity self) {
        EnderPearlEntity best = null;
        for (EnderPearlEntity e : client.world.getEntitiesByClass(EnderPearlEntity.class,
                self.getBoundingBox().expand(96.0), x -> x.getOwner() == self && !x.isRemoved())) {
            if (best == null || e.age < best.age) {
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Execute: GLIDE (elytra without rockets: cross the gap, take it off, smash)
    // ------------------------------------------------------------------

    private boolean wantsGlide(ClientPlayerEntity self, Situation s) {
        return useElytra.get() && s.heightAdv() >= 6.0 && s.horiz() > glideFrom.get()
                && self.getVelocity().y < 0.0
                && (GearSlots.wearingUsableElytra(self) || GearSlots.locateElytra(self).usable())
                && (GearSlots.wearingElytra(self)
                ? InventoryManager.findHotbarSlot(self, GearSlots::isChestplate) != -1 : true);
    }

    private void executeGlide(MinecraftClient client, ClientPlayerEntity self, Situation s) {
        if (!gliding) {
            gliding = true;
            glideTicks = 0;
            glides++;
        }
        glideTicks++;
        Vec3d eye = self.getEyePos();
        Vec3d over = s.target().getEntityPos().add(0.0, s.target().getHeight() + 2.0, 0.0);
        boolean arrived = s.horiz() <= 1.6 || s.heightAdv() < minFall.get() + 2.5 || glideTicks > 100;
        if (arrived) {
            if (GearSlots.wearingElytra(self)) {
                swapChest(client, self); // elytra off: the fall from here is the smash
            } else {
                gliding = false;
            }
            faceTarget(self, s.target());
            return;
        }
        // Nose at the point over their head, never so steep that the glide turns into a fall.
        double pitch = MathHelper.clamp(MathUtil.pitchTo(eye, over), -5.0, 40.0);
        steer(MathUtil.yawTo(eye, over), pitch);
        if (!GearSlots.wearingElytra(self)) {
            ElytraLocator.Found at = GearSlots.locateElytra(self);
            if (!acted()) {
                boolean sent = switch (at.place()) {
                    case HOTBAR -> GearSlots.swapWorn(client, at.slot());
                    case MAIN -> GearSlots.stageToHotbar(client, GearSlots::isUsableElytra, meleeSlot(self),
                            axeSlot(self), InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge),
                            InventoryManager.findHotbarSlot(self, ItemUtil::isPearl),
                            findMace(self, 0.0, 0));
                    case WORN, NONE -> false;
                };
                if (sent) {
                    noteAct();
                    elytraOurs |= at.place() == ElytraLocator.Place.HOTBAR;
                }
            }
            return;
        }
        // A glide starts from a fresh jump press in the air.
        if (!self.isGliding() && !InputOverride.jumpedLastTick()) {
            InputOverride.requestJump();
        }
    }

    /** Exchanges elytra and chestplate; true when the click was sent. */
    private boolean swapChest(MinecraftClient client, ClientPlayerEntity self) {
        int slot = GearSlots.counterpartHotbarSlot(self);
        if (acted() || slot == -1 || !GearSlots.swapWorn(client, slot)) {
            return false;
        }
        noteAct();
        return true;
    }

    // ------------------------------------------------------------------
    // Fall save
    // ------------------------------------------------------------------

    /** A long fall that is not going to hit anyone: a wind charge at the feet just above ground. */
    private boolean breakFall(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, Situation s) {
        double vy = self.getVelocity().y;
        if (vy > -0.6 || self.fallDistance < 5.0 || self.isGliding() || s.reachDist() <= reach.get() + 1.0
                || acted()) {
            return false;
        }
        double within = Math.max(2.5, -vy * 2.5);
        if (world.isSpaceEmpty(self, self.getBoundingBox().stretch(0.0, -within, 0.0))) {
            return false;
        }
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isWindCharge);
        if (slot == -1 || CombatActions.interactItemFromSlot(client, slot, ItemUtil::isWindCharge,
                self.getYaw(), 90.0f) == ActionResult.FAIL) {
            return false;
        }
        noteAct();
        sinceWind = 0;
        fallSaves++;
        return true;
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
        double drop = (6.0 + MaceChoice.smashBonus(7.0)) * 0.6;
        return drop > meleeNow * 1.5;
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
        // A qualifying fall is fast, and the aim point slews 15-25 deg/tick over the last
        // blocks - holding the hit for a 10-degree convergence regularly flew the whole
        // reach window past the target and the bot boosted itself back up for another
        // launch. The server only needs reach + line of sight for a melee hit, so while a
        // qualifying smash drop is under way the tolerance widens instead of blocking; the
        // aim request above keeps the visible rotations looking human either way.
        boolean fastFall = s.descending() && s.fall() >= minFall.get();
        double tol = fastFall ? Math.max(aimTol.get() * 2.5, 30.0) : aimTol.get();
        if (acted() || !RotationManager.isAimedAt(aim, tol)) {
            return;
        }
        // Stun slam: the axe opens the shield this tick, the mace lands on a following one.
        if (stunSlam.get() && CombatCoordinator.shieldBlocks(t, self)) {
            int axe = axeSlot(self);
            if (axe != -1 && CombatActions.attributeSwapAttack(client, axe, t, 1)) {
                noteAct();
                stunSlams++;
                CombatCoordinator.noteShieldBroken(t);
            }
            return;
        }
        int slot = findMace(self, s.fall(), s.armor());
        if (slot != -1 && CombatActions.attributeSwapAttack(client, slot, t, backTicks.get().intValue())) {
            noteAct();
            strikes++;
        }
    }

    private int findMace(ClientPlayerEntity self, double fall, int armor) {
        return MaceChoice.bestSlot(self, fall, armor, breachArmor.get(), preferWindBurst.get());
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
                } else if (self.getVelocity().y < -0.30) {
                    // Past the apex by more than a couple of ticks: throwing now would burst
                    // at our feet while we are already descending onto the target and boost
                    // us back up right before the smash - finish the drop instead.
                    endLaunch(client);
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
        if (acted() || s.shield() && !canStun() || t.hurtTime > 5 && !s.shield()) {
            return;
        }
        int slot = s.shield() ? axeSlot(self) : meleeSlot(self);
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
            if (s.shield()) {
                stunSlams++;
                CombatCoordinator.noteShieldBroken(t);
            }
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
