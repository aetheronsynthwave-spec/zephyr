package dev.zephyr;

import dev.zephyr.command.ZephyrCommand;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.modules.combat.AutoMace;
import dev.zephyr.modules.combat.BreachSwap;
import dev.zephyr.modules.combat.JumpReset;
import dev.zephyr.modules.combat.LegitAura;
import dev.zephyr.modules.combat.LungeMacro;
import dev.zephyr.modules.combat.STap;
import dev.zephyr.modules.crystal.AnchorMacro;
import dev.zephyr.modules.crystal.AutoCrystal;
import dev.zephyr.modules.crystal.AutoHitCrystal;
import dev.zephyr.modules.crystal.SafeAnchor;
import dev.zephyr.modules.gui.ClickGui;
import dev.zephyr.modules.hud.ActiveHud;
import dev.zephyr.modules.movement.Freelook;
import dev.zephyr.modules.movement.NoDelay;
import dev.zephyr.modules.movement.NoPush;
import dev.zephyr.modules.render.CrystalEsp;
import dev.zephyr.modules.render.DamageNumbers;
import dev.zephyr.modules.render.Esp;
import dev.zephyr.modules.render.FancyOutlines;
import dev.zephyr.modules.render.NameTags;
import dev.zephyr.modules.render.TargetHud;
import dev.zephyr.modules.render.Tracers;
import dev.zephyr.modules.render.WorldRenderParticipant;
import dev.zephyr.modules.survival.AutoLog;
import dev.zephyr.modules.survival.AutoTotem;
import dev.zephyr.modules.survival.FastXP;
import dev.zephyr.modules.survival.PearlCatch;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.modules.render.Fullbright;
import dev.zephyr.modules.render.BlockEsp;
import dev.zephyr.modules.render.LightDebug;
import dev.zephyr.modules.movement.Freecam;
import dev.zephyr.modules.movement.Fly;
import dev.zephyr.modules.utility.StaffDetector;
import dev.zephyr.modules.utility.SoundDetector;
import dev.zephyr.modules.combat.AimAssist;
import dev.zephyr.modules.combat.TriggerBot;
import dev.zephyr.modules.combat.ShieldBreaker;
import dev.zephyr.modules.movement.Sprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Zephyr entrypoint. Wiring only - all behavior lives in the modules and core managers.
 *
 * <p>Tick stages (see {@link dev.zephyr.core.timing.TickPhase} for the vanilla order):
 * <ul>
 *   <li>{@code START_CLIENT_TICK}: tick id / action window open, keybind dispatch</li>
 *   <li>pre-movement (before {@code ClientWorld#tickEntities}): swap-backs, module tick
 *       dispatch, rotation advancement - every slot packet and attack is sent here</li>
 *   <li>{@code END_CLIENT_TICK}: action window closed, render cache bump, test harness,
 *       profiler report - nothing here sends gameplay packets</li>
 * </ul>
 */
public final class ZephyrClient implements ClientModInitializer {

    private static ModuleManager modules;
    private static ZephyrConfig config;
    /** GLFW rising-edge state for module keybinds. */
    private static final Map<Integer, Boolean> keyEdge = new HashMap<>();

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        modules.setInputGuard(() -> MinecraftClient.getInstance().player == null);

        registerAll();
        config = new ZephyrConfig(modules);
        config.load();
        ZephyrCommand.register();

        ClientTickEvents.START_CLIENT_TICK.register(ZephyrClient::onClientTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(ZephyrClient::onClientTickEnd);
        dev.zephyr.ZephyrSelfTest.init();
        dev.zephyr.ZephyrGameTest.init();
        dev.zephyr.ElytraBotTest.init();

        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            // One camera-position cache invalidation per frame: render modules used to
            // walk gameRenderer->camera->entity->getEyePos() (a fresh Vec3d) on every
            // call from every module.
            long t0 = System.nanoTime();
            RotationManager.onFrame(MinecraftClient.getInstance());
            dev.zephyr.modules.render.RenderUtil.beginFrame();
            for (Module m : modules.all()) {
                if (m.isEnabled() && m instanceof WorldRenderParticipant participant) {
                    participant.onWorldRender(ctx);
                }
            }
            dev.zephyr.core.ZephyrProfiler.addWorldRender(System.nanoTime() - t0);
        });

        // 1.21.11 fabric api: HUD callback passes a RenderTickCounter, not a float delta.
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            if (MinecraftClient.getInstance().currentScreen instanceof dev.zephyr.gui.HudEditorScreen) {
                return; // the editor renders the HUDs itself (no double draw / double animation)
            }
            long t0 = System.nanoTime();
            modules.hudRender(MinecraftClient.getInstance(), ctx, tickCounter.getTickProgress(false));
            dev.zephyr.core.ZephyrProfiler.addHudRender(System.nanoTime() - t0);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            RotationManager.invalidate();
            dev.zephyr.core.combat.SwapManager.reset();
            // The world-scoped hooks were dead: modules overriding them (PearlCatch,
            // ElytraMaceBot, SmartMaceBot) kept stale state - e.g. a returnSlot from the
            // previous world being restored in the new one.
            for (Module m : modules.all()) {
                m.onWorldJoin(client);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            RotationManager.invalidate();
            dev.zephyr.core.combat.SwapManager.reset();
            for (Module m : modules.all()) {
                m.onWorldLeave(client);
            }
            config.save();
        });
    }

    private void registerAll() {
        // GUI
        modules.register(new ClickGui());
        // Combat
        modules.register(new AutoMace());
        modules.register(new dev.zephyr.modules.combat.ElytraMaceBot());
        modules.register(new dev.zephyr.modules.combat.SmartMaceBot());
        modules.register(new BreachSwap());
        modules.register(new LungeMacro());
        modules.register(new LegitAura());
        modules.register(new STap());
        modules.register(new JumpReset());
        modules.register(new AimAssist());
        modules.register(new TriggerBot());
        modules.register(new ShieldBreaker());
        // Crystal & anchor
        // First among the crystal modules: it decides which of them runs this tick.
        modules.register(new dev.zephyr.modules.crystal.AutoCrystalPvP());
        modules.register(new AutoCrystal());
        modules.register(new AutoHitCrystal());
        modules.register(new AnchorMacro());
        modules.register(new SafeAnchor());
        // Survival
        modules.register(new AutoTotem());
        modules.register(new FastXP());
        modules.register(new PearlCatch());
        modules.register(new AutoLog());
        modules.register(new dev.zephyr.modules.survival.AutoRespawn());
        modules.register(new dev.zephyr.modules.survival.DurabilityAlert());
        // Movement
        modules.register(new NoDelay());
        modules.register(new NoPush());
        modules.register(new Sprint());
        modules.register(new Freelook());
        modules.register(new dev.zephyr.modules.movement.WalkInventory());
        // Utility (ported from Donut Client)
        modules.register(new StaffDetector());
        modules.register(new SoundDetector());
        modules.register(new dev.zephyr.modules.utility.AutoTool());
        modules.register(new dev.zephyr.modules.utility.Zoom());
        modules.register(new dev.zephyr.modules.utility.Waypoints());
        modules.register(new Freecam());
        modules.register(new Fly());
        var auctionFlipper = new dev.zephyr.modules.utility.AuctionFlipper();
        modules.register(auctionFlipper);
        modules.register(new dev.zephyr.modules.utility.CraftAutomate(auctionFlipper));
        modules.register(new dev.zephyr.modules.donut.AmethystFinder());
        modules.register(new dev.zephyr.modules.donut.StorageESP());
        // Render & HUD
        modules.register(new Fullbright());
        modules.register(new dev.zephyr.modules.render.XRay());
        modules.register(new dev.zephyr.modules.render.CustomSky());
        modules.register(new dev.zephyr.modules.render.HandChams());
        modules.register(new dev.zephyr.modules.render.Cosmetics());
        modules.register(new BlockEsp());
        modules.register(new LightDebug());
        modules.register(new dev.zephyr.modules.render.BaseFinder());
        modules.register(new dev.zephyr.modules.hud.ProgressBar());
        modules.register(new dev.zephyr.modules.hud.HotbarPrivacy());
        modules.register(new dev.zephyr.modules.hud.InfoHud());
        modules.register(new dev.zephyr.modules.hud.ArmorHud());
        modules.register(new dev.zephyr.modules.hud.Keystrokes());
        modules.register(new dev.zephyr.modules.hud.SpotifyLyrics());
        modules.register(new dev.zephyr.modules.hud.SessionHud());
        modules.register(new dev.zephyr.modules.utility.NameHider());
        modules.register(new Esp());
        modules.register(new Tracers());
        modules.register(new CrystalEsp());
        modules.register(new NameTags());
        modules.register(new DamageNumbers());
        modules.register(new TargetHud());
        modules.register(new FancyOutlines());
        modules.register(new ActiveHud());
    }

    /** Tick HEAD: open this tick's action window, then dispatch binds (toggles / triggers only). */
    private static void onClientTickStart(MinecraftClient client) {
        dev.zephyr.core.timing.TickPhase.beginTick();
        pollKeybinds(client);
    }

    /**
     * Pre-movement stage, called from MixinMinecraftClient right before
     * {@code ClientWorld#tickEntities}. Order matters:
     * <ol>
     *   <li>due swap-backs first, so they use this tick's single slot change before any
     *       module can ask for a different slot (no same-tick bounce)</li>
     *   <li>modules: target selection, slot selection, attacks, interactions</li>
     *   <li>rotation advancement last, so the rotation computed now rides this tick's
     *       movement packet (sent moments later by {@code ClientPlayerEntity#tick})</li>
     * </ol>
     */
    public static void onPreMovementTick(MinecraftClient client) {
        if (modules == null) {
            return;
        }
        dev.zephyr.core.timing.TickPhase.enterPreMovement();
        long t0 = System.nanoTime();
        // Per-tick caches: exposure memo table keys off this tick.
        dev.zephyr.core.combat.DamageCalculator.advanceTick();
        dev.zephyr.core.combat.SwapManager.tick(client);
        RotationManager.settle(client);
        modules.tick(client);
        RotationManager.tick(client);
        dev.zephyr.core.ZephyrProfiler.addTick(System.nanoTime() - t0);
    }

    /** Tick RETURN: after the movement and tick-end packets. Nothing here emits gameplay packets. */
    private static void onClientTickEnd(MinecraftClient client) {
        dev.zephyr.core.timing.TickPhase.endTick();
        RenderTick.advance();
        dev.zephyr.ZephyrSelfTest.tick(client);
        dev.zephyr.ZephyrGameTest.tick(client);
        dev.zephyr.ElytraBotTest.tick(client);
        // Development harnesses: left out of the release jar (build.gradle), so each is only
        // referenced when its system property asks for it.
        if (System.getProperty("zephyr.hct") != null) {
            dev.zephyr.HitCrystalTest.tick(client);
        }
        if (System.getProperty("zephyr.duel") != null) {
            dev.zephyr.DuelTest.tick(client);
        }
        if (Boolean.getBoolean("zephyr.pbt")) {
            dev.zephyr.PracticeBotTest.tick(client);
        }
        dev.zephyr.core.ZephyrProfiler.chatReport(client);
    }

    /**
     * Dispatches every key / mouse press recorded since the last tick (see BindInput).
     * Event-based, so even a tap shorter than one tick always fires, and keys shared by
     * several modules fire all of them exactly once.
     */
    private static void pollKeybinds(MinecraftClient client) {
        int code;
        while ((code = dev.zephyr.core.input.BindInput.poll()) != -1) {
            boolean screenOpen = client.currentScreen != null;
            if (client.currentScreen instanceof dev.zephyr.gui.ClickGuiScreen gs && gs.isCapturing()) {
                continue; // this press is being bound in the menu, not a trigger
            }
            ClickGui gui = modules.get(ClickGui.class);
            if (gui != null && gui.bind().get() == code) {
                // The GUI key works in both directions, but never while typing in chat etc.
                if (!screenOpen || client.currentScreen instanceof dev.zephyr.gui.ClickGuiScreen) {
                    gui.openOrClose(client);
                }
                continue;
            }
            if (!screenOpen) {
                modules.handleKey(code, GLFW.GLFW_PRESS);
            }
        }
    }

    public static ModuleManager modules() {
        return modules;
    }

    public static ZephyrConfig config() {
        return config;
    }

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    /** Render-cache tick source (separate from gameplay ticks). */
    private static final class RenderTick {
        private static int tick;

        static void advance() {
            tick++;
            dev.zephyr.modules.render.RenderUtil.advanceTick();
        }
    }
}

