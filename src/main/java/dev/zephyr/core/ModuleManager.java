package dev.zephyr.core;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.settings.Setting;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registry + tick dispatcher. Every module executes inside a per-module try/catch so a
 * faulting subsystem degrades to "disabled" instead of taking the client tick loop down.
 */
public final class ModuleManager {

    private final List<Module> modules = new ArrayList<>();
    /**
     * Type -> module lookup cache. {@link #get(Class)} is called from per-frame and
     * per-entity mixin hooks (FOV, look deltas, pushes, knockback); a linear isInstance
     * scan over ~50 modules there was pure overhead. Modules are only registered at init.
     */
    private final java.util.Map<Class<?>, Module> byType = new java.util.HashMap<>();
    private Supplier<Boolean> inputGuard = () -> false;

    /** moduleName -> last fault description (read by the self-test harness). */
    public static final java.util.Map<String, String> FAULTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordFault(Module m, String phase, Throwable e) {
        String msg = phase + ": " + e;
        FAULTS.put(m.name(), msg);
        System.err.println("[Zephyr] module " + m.name() + " faulted in " + msg);
        e.printStackTrace();
    }

    public void register(Module module) {
        modules.add(module);
        byType.clear();
    }

    public void forEach(Consumer<Module> action) {
        for (Module m : modules) {
            action.accept(m);
        }
    }

    public <T extends Module> T get(Class<T> type) {
        Module cached = byType.get(type);
        if (cached == null && !byType.containsKey(type)) {
            for (Module m : modules) {
                if (type.isInstance(m)) {
                    cached = m;
                    break;
                }
            }
            byType.put(type, cached); // null cached too: a missing type is not re-scanned
        }
        return type.cast(cached);
    }

    public Module byName(String name) {
        for (Module m : modules) {
            if (m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return null;
    }

    public List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    /** Modules with an open world are blocked from toggling while a screen/lock is active. */
    public void setInputGuard(Supplier<Boolean> guard) {
        this.inputGuard = guard;
    }

    public boolean inputBlocked() {
        return inputGuard.get();
    }

    public void handleKey(int keyCode, int action) {
        if (action != GLFW.GLFW_PRESS || inputBlocked()) {
            return;
        }
        for (Module m : modules) {
            m.handleKeyPress(keyCode);
        }
    }

    public void tick(MinecraftClient client) {
        Module.advanceLabelTick();
        if (client.player == null || client.world == null) {
            return;
        }
        for (Module m : modules) {
            if (m.isEnabled()) {
                try {
                    m.onTick(client);
                } catch (Throwable e) {
                    // Degrade to disabled, but SAY SO: a silently dead module looks
                    // exactly like "nothing works". Logged once per failure.
                    recordFault(m, "onTick", e);
                    m.setEnabled(false);
                }
            }
            m.flashDecay();
        }
    }

    public void hudRender(MinecraftClient client, net.minecraft.client.gui.DrawContext ctx, float tickDelta) {
        for (Module m : modules) {
            if (m.isEnabled()) {
                try {
                    m.onHudRender(ctx, tickDelta);
                } catch (Throwable e) {
                    recordFault(m, "onHudRender", e);
                    m.setEnabled(false);
                }
            }
        }
    }

    /** Serializes all settings of all modules (used by ZephyrConfig). */
    public void writeSettings(java.util.Map<String, java.util.Map<String, Object>> out) {
        for (Module m : modules) {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("enabled", m.isEnabled());
            for (Setting<?> s : m.settings()) {
                data.put(s.id(), s.serialize());
            }
            out.put(m.name(), data);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /** True while the config file is being applied (change listeners can ignore it). */
    public static volatile boolean loadingConfig;

    public void readSettings(java.util.Map<String, java.util.Map<String, Object>> in) {
        loadingConfig = true;
        try {
            readSettingsInner(in);
        } finally {
            loadingConfig = false;
        }
    }

    private void readSettingsInner(java.util.Map<String, java.util.Map<String, Object>> in) {
        for (Module m : modules) {
            java.util.Map<String, Object> data = in.get(m.name());
            if (data == null) {
                continue;
            }
            for (Setting<?> s : m.settings()) {
                Object raw = data.get(s.id());
                if (raw != null) {
                    // Direct cast path for JSON primitives (Gson yields Double for numbers).
                    if (s instanceof dev.zephyr.core.settings.NumberSetting ns && raw instanceof Number n) {
                        ns.set(n.doubleValue());
                    } else if (s instanceof dev.zephyr.core.settings.BooleanSetting bs && raw instanceof Boolean b) {
                        bs.set(b);
                    } else if (s instanceof dev.zephyr.core.settings.ModeSetting ms) {
                        ms.set(String.valueOf(raw));
                    } else if (s instanceof dev.zephyr.core.settings.KeySetting ks && raw instanceof Number n) {
                        ks.set(n.intValue());
                    } else {
                        ((Setting) s).loadRaw(raw);
                    }
                }
            }
            Object en = data.get("enabled");
            if (en instanceof Boolean b && b && clientSafe()) {
                m.setEnabled(true);
            }
        }
    }

    private boolean clientSafe() {
        return ZephyrClient.mc() != null;
    }
}
