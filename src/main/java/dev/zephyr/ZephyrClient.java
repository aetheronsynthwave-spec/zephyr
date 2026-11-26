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
 * <p>Tick responsibilities:
 * <ul>
 *   <li>keybind polling (GLFW edge detection per module bind)</li>
 *   <li>module tick dispatch</li>
 *   <li>rotation manager advancement</li>
 *   <li>render cache tick bump</li>
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

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        dev.zephyr.ZephyrSelfTest.init();
        dev.zephyr.ZephyrGameTest.init();

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
            long t0 = System.nanoTime();
            modules.hudRender(MinecraftClient.getInstance(), ctx, tickCounter.getTickProgress(false));
            dev.zephyr.core.ZephyrProfiler.addHudRender(System.nanoTime() - t0);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RotationManager.invalidate());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            RotationManager.invalidate();
            config.save();
        });
    }

    private void registerAll() {
        // GUI
        modules.register(new ClickGui());
        // Combat
        modules.register(new AutoMace());
        modules.register(new BreachSwap());
        modules.register(new LungeMacro());
        modules.register(new LegitAura());
        modules.register(new STap());
        modules.register(new JumpReset());
        modules.register(new AimAssist());
        modules.register(new TriggerBot());
        modules.register(new ShieldBreaker());
        // Crystal & anchor
        modules.register(new AutoCrystal());
        modules.register(new AutoHitCrystal());
        modules.register(new AnchorMacro());
        modules.register(new SafeAnchor());
        // Survival
        modules.register(new AutoTotem());
        modules.register(new FastXP());
        modules.register(new PearlCatch());
        modules.register(new AutoLog());
        // Movement
        modules.register(new NoDelay());
        modules.register(new NoPush());
        modules.register(new Sprint());
        modules.register(new Freelook());
        // Utility (ported from Donut Client)
        modules.register(new StaffDetector());
        modules.register(new SoundDetector());
        modules.register(new Freecam());
        modules.register(new Fly());
        // Render & HUD
        modules.register(new Fullbright());
        modules.register(new BlockEsp());
        modules.register(new LightDebug());
        modules.register(new dev.zephyr.modules.render.BaseFinder());
        modules.register(new dev.zephyr.modules.hud.ProgressBar());
        modules.register(new dev.zephyr.modules.hud.HotbarPrivacy());
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

    private void onClientTick(MinecraftClient client) {
        RenderTick.advance();
        dev.zephyr.ZephyrSelfTest.tick(client);
        dev.zephyr.ZephyrGameTest.tick(client);
        // Per-tick caches: exposure memo table + render caches all key off this tick.
        dev.zephyr.core.combat.DamageCalculator.advanceTick();
        pollKeybinds(client);
        long t0 = System.nanoTime();
        modules.tick(client);
        RotationManager.tick(client);
        dev.zephyr.core.ZephyrProfiler.addTick(System.nanoTime() - t0);
        dev.zephyr.core.ZephyrProfiler.chatReport(client);
    }

    /**
     * Dispatches every key / mouse press recorded since the last tick (see BindInput).
     * Event-based, so even a tap shorter than one tick always fires, and keys shared by
     * several modules fire all of them exactly once.
     */
    private void pollKeybinds(MinecraftClient client) {
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

