package dev.zephyr;

import dev.zephyr.command.ZephyrCommand;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.modules.combat.AutoMace;
import dev.zephyr.modules.combat.BreachSwap;
import dev.zephyr.modules.combat.LegitAura;
import dev.zephyr.modules.combat.LungeMacro;
import dev.zephyr.modules.crystal.AnchorMacro;
import dev.zephyr.modules.crystal.AutoCrystal;
import dev.zephyr.modules.crystal.SafeAnchor;
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
import dev.zephyr.modules.render.WorldRenderParticipant;
import dev.zephyr.modules.survival.AutoLog;
import dev.zephyr.modules.survival.AutoTotem;
import dev.zephyr.modules.survival.FastXP;
import dev.zephyr.modules.survival.PearlCatch;
import dev.zephyr.core.timing.RandomController;
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

        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            for (Module m : modules.all()) {
                if (m.isEnabled() && m instanceof WorldRenderParticipant participant) {
                    participant.onWorldRender(ctx);
                }
            }
        });

        // 1.21.11 fabric api: HUD callback passes a RenderTickCounter, not a float delta.
        HudRenderCallback.EVENT.register((ctx, tickCounter) ->
                modules.hudRender(MinecraftClient.getInstance(), ctx, tickCounter.getTickProgress(false)));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RotationManager.invalidate());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            RotationManager.invalidate();
            config.save();
        });
    }

    private void registerAll() {
        // Combat
        modules.register(new AutoMace());
        modules.register(new BreachSwap());
        modules.register(new LungeMacro());
        modules.register(new LegitAura());
        // Crystal & anchor
        modules.register(new AutoCrystal());
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
        modules.register(new Freelook());
        // Render & HUD
        modules.register(new Esp());
        modules.register(new CrystalEsp());
        modules.register(new NameTags());
        modules.register(new DamageNumbers());
        modules.register(new TargetHud());
        modules.register(new FancyOutlines());
        modules.register(new ActiveHud());
    }

    private void onClientTick(MinecraftClient client) {
        RenderTick.advance();
        pollKeybinds(client);
        modules.tick(client);
        RotationManager.tick(client);
    }

    private void pollKeybinds(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        for (Module m : modules.all()) {
            int bind = m.bind().get();
            if (bind == GLFW.GLFW_KEY_UNKNOWN) {
                continue;
            }
            boolean pressed = GLFW.glfwGetKey(window, bind) == GLFW.GLFW_PRESS;
            boolean previous = keyEdge.getOrDefault(bind, false);
            keyEdge.put(bind, pressed);
            if (pressed && !previous) {
                modules.handleKey(bind, GLFW.GLFW_PRESS);
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
