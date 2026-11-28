package dev.zephyr.modules.crystal;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.PearlThrow;
import dev.zephyr.core.crystal.CrystalSolver;
import dev.zephyr.core.input.InputOverride;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.settings.Setting;
import dev.zephyr.core.target.TargetLock;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.modules.survival.AutoTotem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AutoCrystalPvP - the crystal modules run as one bot.
 *
 * <p>It chooses which of them is on from what is happening, and does the footwork none of them
 * do: AutoHitCrystal for hit-and-crystal combos up close, AnchorMacro taking turns with it
 * (SafeAnchor instead when your health is low), AutoCrystal when the target is out of sword
 * range, and AutoTotem throughout. It walks in, holds its distance and circles. When a hit
 * flings the target sideways (or they get far away) it throws a flat pearl to land just under
 * them, never a lob, and places obsidian and crystals as it arrives. Turning it off puts every
 * module back as it was.
 */
public final class AutoCrystalPvP extends Module {

    private static final int PRIORITY = -1;

    private final NumberSetting range = num("range", "Engage range", 6, 48, 1, 24);
    private final BooleanSetting move = bool("move", "Move on its own", true);
    private final NumberSetting keep = num("keep", "Preferred distance", 1.5, 4.5, 0.1, 2.6);
    private final BooleanSetting strafe = bool("strafe", "Circle the target", true);
    private final BooleanSetting anchors = bool("anchors", "Use respawn anchors", true);
    private final NumberSetting safeBelow = num("safeBelow", "SafeAnchor below hp", 0, 20, 1, 10);
    private final BooleanSetting rangeCrystal = bool("rangeCrystal", "AutoCrystal out of reach", true);
    private final NumberSetting reachSwitch = num("reachSwitch", "Out of reach beyond", 3.0, 6.0, 0.1, 4.2);
    private final BooleanSetting pearlChase = bool("pearlChase", "Pearl after far targets", true);
    private final NumberSetting pearlFrom = num("pearlFrom", "Pearl when farther than", 8, 30, 1, 12);
    /** After a hit or blast, how far sideways they must be flung before the pearl goes. */
    private final NumberSetting chaseBeyond = num("chaseBeyond", "Chase after a hit beyond", 3.0, 10.0, 0.5, 4.0);

    private final TargetLock lock = new TargetLock();
    /** What each module's on/off was before the bot took over. */
    private final Map<Module, Boolean> before = new LinkedHashMap<>();
    private int ticks;
    private int sinceSwitch = 99;
    private int strafeSide = 1;
    private int strafeFor;
    private int sincePearl = 999;
    private int sinceHit = 999;
    private int lastHits;
    private int lastBlasts;
    private int inFlight;
    private int chases;
    private int arrivals;
    private float lastPitch;
    private boolean pearlSeen;
    private String refused = "";
    private Vec3d lastSelf;
    private LivingEntity tracked;
    private Vec3d trackedAt;
    /** The target's movement over the last tick (a remote player's velocity field is unreliable). */
    private Vec3d targetStep = Vec3d.ZERO;
    private String doing = "waiting";

    public AutoCrystalPvP() {
        super("AutoCrystalPvP", ModuleCategory.CRYSTAL, 0);
    }

    @Override
    protected void onEnable() {
        before.clear();
        for (Class<? extends Module> type : java.util.List.of(AutoHitCrystal.class, AnchorMacro.class,
                SafeAnchor.class, AutoCrystal.class, AutoTotem.class)) {
            Module module = module(type);
            if (module != null) {
                before.put(module, module.isEnabled());
            }
        }
        sinceSwitch = 99;
        inFlight = 0;
        sinceHit = 999;
        lastSelf = null;
        tracked = null;
        doing = "waiting";
    }

    @Override
    protected void onDisable() {
        // Off first, then back on, so modules that exclude each other cannot undo the restore.
        before.keySet().forEach(m -> m.setEnabled(false));
        before.forEach((m, was) -> {
            if (was) {
                m.setEnabled(true);
            }
        });
        before.clear();
        lock.clear();
        RotationManager.clear(PRIORITY);
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        lock.clear();
        // So the settings saved on disconnect are the player's own, not the bot's choice.
        before.keySet().forEach(m -> m.setEnabled(false));
        before.forEach((m, was) -> {
            if (was) {
                m.setEnabled(true);
            }
        });
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null || !self.isAlive() || client.currentScreen != null) {
            return;
        }
        ticks++;
        sinceSwitch++;
        sincePearl++;
        sinceHit++;
        totem();
        LivingEntity target = lock.update(client, range.get(), false);
        if (target == null) {
            doing = "waiting";
            arm(null, null);
            return;
        }
        double distance = self.distanceTo(target);
        double across = Math.hypot(target.getX() - self.getX(), target.getZ() - self.getZ());
        track(target);
        watchHits();

        // A jump of more than a couple of blocks in one tick is the pearl arriving.
        if (inFlight > 0) {
            // Either sign will do: the pearl vanishing, or us being somewhere else all at once.
            boolean landed = pearlLanded(client, self);
            boolean arrived = landed || lastSelf != null && self.getEntityPos().squaredDistanceTo(lastSelf) > 6.25;
            inFlight = arrived ? 0 : inFlight - 1;
            if (arrived) {
                arrivals++;
                AutoHitCrystal hitter = module(AutoHitCrystal.class);
                if (hitter != null) {
                    hitter.strikeNow(); // obsidian and crystals straight away
                }
            }
        }
        lastSelf = self.getEntityPos();

        boolean chasing = inFlight > 0 || wantsChase(target, across);

        // Which fighters are on. Switched at most once a second so they can finish a step.
        boolean far = !chasing && rangeCrystal.get() && distance > reachSwitch.get() && distance <= 6.5
                && sinceHit > 30;
        Class<? extends Module> crystal = far ? AutoCrystal.class : AutoHitCrystal.class;
        Class<? extends Module> anchor = null;
        if (!far && !chasing && anchors.get() && hasAnchorKit(self)) {
            anchor = self.getHealth() + self.getAbsorptionAmount() <= safeBelow.get()
                    ? SafeAnchor.class : AnchorMacro.class;
        }
        arm(crystal, anchor);
        doing = (far ? "range" : "combo") + (anchor == SafeAnchor.class ? " + safe anchor"
                : anchor == AnchorMacro.class ? " + anchor" : "");

        // Always, at the lowest priority: any module aiming at something wins. Without this it
        // could be left looking at a block with the target just out of reach, and never walk.
        face(self, target);
        if (inFlight > 0 || chasing && throwUnder(client, self, target)) {
            doing = "pearl chase";
            return; // stand still until it lands
        }
        if (move.get()) {
            footwork(self, target, distance);
        }
    }

    private void track(LivingEntity target) {
        targetStep = target == tracked && trackedAt != null ? target.getEntityPos().subtract(trackedAt) : Vec3d.ZERO;
        tracked = target;
        trackedAt = target.getEntityPos();
    }

    /** Notes our own sword hits and detonations: the start of a chance to chase. */
    private void watchHits() {
        AutoHitCrystal hitter = module(AutoHitCrystal.class);
        if (hitter == null) {
            return;
        }
        int hits = hitter.hitCounts()[0];
        int blasts = hitter.detonatedCount();
        if (hits > lastHits || blasts > lastBlasts) {
            sinceHit = 0;
        }
        lastHits = hits;
        lastBlasts = blasts;
    }

    private void footwork(ClientPlayerEntity self, LivingEntity target, double distance) {
        Vec3d eye = self.getEyePos();
        double error = Math.abs(MathUtil.angleDelta(self.getYaw(), MathUtil.yawTo(eye, MathUtil.aimPoint(target))));
        if (error > 70.0) {
            return; // looking at a block to click it: walking now would drift sideways
        }
        if (distance > keep.get() + 0.3) {
            InputOverride.requestForward(true);
        } else if (distance < keep.get() - 0.6) {
            InputOverride.requestMove(true, false, false);
        }
        if (strafe.get() && distance < keep.get() + 1.5) {
            if (--strafeFor <= 0) {
                strafeSide = -strafeSide;
                strafeFor = 12 + (int) (Math.random() * 18);
            }
            InputOverride.requestMove(false, strafeSide > 0, strafeSide < 0);
        }
        if (self.horizontalCollision) {
            strafeSide = -strafeSide;
            if (self.isOnGround() && !InputOverride.jumpedLastTick()) {
                InputOverride.requestJump();
            }
        }
    }

    /**
     * Whether to go after them with a pearl now: a hit or a blast just sent them flying
     * sideways out of reach, or they are simply a long way off.
     */
    private boolean wantsChase(LivingEntity target, double across) {
        if (!pearlChase.get() || sincePearl < 25) {
            return false;
        }
        boolean flung = sinceHit <= 30 && (!target.isOnGround() || targetStep.horizontalLength() > 0.25);
        return flung && across > chaseBeyond.get() || across > pearlFrom.get();
    }

    /** A flat throw that comes down on the ground just under them. Never upward. */
    private boolean throwUnder(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        int slot = InventoryManager.findHotbarSlot(self, ItemUtil::isPearl);
        refused = slot == -1 ? "no pearl" : "cooldown or low health";
        // A pearl costs 5 health on landing: not with so little left and nothing to catch us.
        if (slot == -1 || ItemUtil.coolingDown(self, self.getInventory().getStack(slot))
                || self.getHealth() + self.getAbsorptionAmount() <= 6.0f && !ItemUtil.isTotem(self.getOffHandStack())) {
            return false;
        }
        Vec3d carried = self.isOnGround() ? new Vec3d(self.getVelocity().x, 0.0, self.getVelocity().z)
                : self.getVelocity();
        PearlThrow.Throw plan = aimUnder(client, self, target, carried);
        if (plan == null) {
            refused = "no clear flat throw";
            return false;
        }
        int home = InventoryManager.selectedHotbar(self);
        if (CombatActions.interactItemFromSlot(client, slot, ItemUtil::isPearl, plan.yaw(), plan.pitch())
                == ActionResult.FAIL) {
            refused = "the game refused the use";
            return false;
        }
        InventoryManager.selectHotbar(client, home);
        sincePearl = 0;
        refused = "";
        pearlSeen = false;
        inFlight = plan.ticks() + 12;
        lastPitch = plan.pitch();
        chases++;
        return true;
    }

    /**
     * Under them, a block short to leave room for the obsidian between us; if blocks are in the
     * way of that (often our own), a little to the side, right under, or just past.
     */
    private PearlThrow.Throw aimUnder(MinecraftClient client, ClientPlayerEntity self, LivingEntity target,
                                      Vec3d carried) {
        double floor = CrystalSolver.floorUnder(client.world, target);
        java.util.function.BiPredicate<Vec3d, Vec3d> clear = (from, to) -> blockFree(client, self, from, to)
                && blockFree(client, self, from.add(0.0, -0.15, 0.0), to.add(0.0, -0.15, 0.0));
        // {blocks short of the point under them, blocks to the side}
        double[][] choices = {{1.0, 0.0}, {1.0, 1.0}, {1.0, -1.0}, {0.0, 0.0}, {-1.0, 0.0}, {2.0, 0.0}};
        for (double[] choice : choices) {
            PearlThrow.Throw plan = null;
            int lead = 6;
            // Twice: the flight time decides how far they move on, which changes the flight time.
            for (int pass = 0; pass < 2; pass++) {
                // Air drag takes 9 % of a flung player's speed each tick, so they stop short.
                double travel = (1.0 - Math.pow(0.91, lead)) / 0.09;
                Vec3d there = target.getEntityPos().add(targetStep.x * travel, 0.0, targetStep.z * travel);
                Vec3d back = new Vec3d(self.getX() - there.x, 0.0, self.getZ() - there.z).normalize();
                Vec3d spot = there.add(back.multiply(choice[0])).add(-back.z * choice[1], 0.0, back.x * choice[1]);
                plan = PearlThrow.solve(self.getEyePos(), carried, new Vec3d(spot.x, floor, spot.z), 0.9, clear);
                if (plan == null) {
                    break;
                }
                lead = Math.min(plan.ticks(), 15);
            }
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static boolean blockFree(MinecraftClient client, ClientPlayerEntity self, Vec3d from, Vec3d to) {
        if (client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, self)).getType() != HitResult.Type.MISS) {
            return false;
        }
        // A pearl bursts against an end crystal just as it does against a block.
        return client.world.getEntitiesByClass(EndCrystalEntity.class, new Box(from, to).expand(1.0),
                crystal -> crystal.getBoundingBox().expand(0.2).raycast(from, to).isPresent()).isEmpty();
    }

    /** True once our pearl has been seen in the air and is gone again. */
    private boolean pearlLanded(MinecraftClient client, ClientPlayerEntity self) {
        boolean flying = !client.world.getEntitiesByClass(EnderPearlEntity.class,
                self.getBoundingBox().expand(48.0), e -> e.getOwner() == self && !e.isRemoved()).isEmpty();
        boolean landed = pearlSeen && !flying;
        pearlSeen = flying;
        return landed;
    }

    /** Why the last wanted chase threw nothing, empty if it threw. */
    public String lastRefusal() {
        return refused + " (ticks since a hit " + sinceHit + ")";
    }

    /** Pearls thrown, arrivals seen, and the last throw's pitch (positive is downwards). */
    public float[] chaseCounts() {
        return new float[]{chases, arrivals, lastPitch};
    }

    private void face(ClientPlayerEntity self, LivingEntity target) {
        Vec3d eye = self.getEyePos();
        Vec3d aim = MathUtil.aimPoint(target);
        RotationManager.request(RotationRequest.to(MathUtil.yawTo(eye, aim),
                        MathHelper.clamp(MathUtil.pitchTo(eye, aim), -60.0, 60.0))
                .speed(25, 60)
                .silent(false)
                .priority(PRIORITY)
                .validWhile(this::isEnabled)
                .expiresAfter(2)
                .build());
    }

    /** Turns on the chosen crystal and anchor modules and off the others the bot manages. */
    private void arm(Class<? extends Module> crystal, Class<? extends Module> anchor) {
        boolean changes = isOn(AutoHitCrystal.class) != (crystal == AutoHitCrystal.class)
                || isOn(AutoCrystal.class) != (crystal == AutoCrystal.class)
                || isOn(AnchorMacro.class) != (anchor == AnchorMacro.class)
                || isOn(SafeAnchor.class) != (anchor == SafeAnchor.class);
        if (!changes || crystal != null && sinceSwitch < 20) {
            return;
        }
        sinceSwitch = 0;
        for (Class<? extends Module> type : java.util.List.of(AutoCrystal.class, AutoHitCrystal.class,
                AnchorMacro.class, SafeAnchor.class)) {
            if (type != crystal && type != anchor) {
                set(type, false);
            }
        }
        if (crystal != null) {
            set(crystal, true);
        }
        if (anchor != null) {
            set(anchor, true);
        }
    }

    private void totem() {
        AutoTotem totem = module(AutoTotem.class);
        if (totem != null && !totem.isEnabled()) {
            for (Setting<?> setting : totem.settings()) {
                if (setting instanceof ModeSetting mode && setting.id().equals("mode")) {
                    mode.set("HOVER");
                }
            }
            totem.setEnabled(true);
        }
    }

    private static boolean hasAnchorKit(ClientPlayerEntity self) {
        return InventoryManager.findHotbarSlot(self, ItemUtil::isRespawnAnchor) != -1
                && InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone) != -1;
    }

    private static <T extends Module> T module(Class<T> type) {
        return ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(type);
    }

    private static boolean isOn(Class<? extends Module> type) {
        Module module = module(type);
        return module != null && module.isEnabled();
    }

    private static void set(Class<? extends Module> type, boolean on) {
        Module module = module(type);
        if (module != null) {
            module.setEnabled(on);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + doing;
    }
}
