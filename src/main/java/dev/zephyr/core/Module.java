package dev.zephyr.core;

import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.KeySetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.settings.Setting;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class of every Zephyr subsystem. Lifecycle:
 * <ul>
 *   <li>{@link #onEnable}/{@link #onDisable} - world-attached state setup/teardown</li>
 *   <li>{@link #onTick} - once per client tick, in the pre-movement stage (after vanilla
 *       input handling, before this tick's movement packet), only while enabled</li>
 *   <li>{@link #hudLabel} - plain-text suffix shown by the active-module HUD</li>
 * </ul>
 * Subclasses register {@link Setting}s via {@link #s(Setting)} so the config
 * serializer and the barebones command access discover them automatically.
 */
public abstract class Module {

    protected final String name;
    private final ModuleCategory category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final KeySetting bind;
    private boolean enabled;
    private int disableFlash; // HUD feedback ticks when toggled by key

    protected Module(String name, ModuleCategory category, int defaultKey) {
        this.name = name;
        this.category = category;
        this.bind = new KeySetting("bind", "Bind", defaultKey);
        this.settings.add(this.bind);
        if (Profiles.has(name)) {
            this.profile = new dev.zephyr.core.settings.ModeSetting("profile", "Profile", Profiles.MODES, "CUSTOM");
            this.settings.add(this.profile);
            this.profile.onChange(this::applyProfile);
        } else {
            this.profile = null;
        }
    }

    /** CUSTOM / SMART / LEGIT for combat modules (null for the rest). */
    private final dev.zephyr.core.settings.ModeSetting profile;

    /** SMART profile: the module should use its adaptive behaviour. */
    public boolean smart() {
        return profile != null && profile.is("SMART");
    }

    /** LEGIT profile: the module should prefer the most human-looking behaviour. */
    public boolean legit() {
        return profile != null && profile.is("LEGIT");
    }

    public boolean hasProfile() {
        return profile != null;
    }

    /** Writes the preset values of {@code mode} into this module's settings (and selects it). */
    @SuppressWarnings("unchecked")
    public void applyProfile(String mode) {
        if (profile == null) {
            return;
        }
        if (!profile.is(mode)) {
            profile.loadRaw(mode);
        }
        java.util.Map<String, Object> preset = Profiles.preset(name, mode);
        for (Setting<?> st : settings) {
            Object v = preset.get(st.id());
            if (v == null) {
                continue;
            }
            if (st instanceof NumberSetting n && v instanceof Number num) {
                n.set(num.doubleValue());
            } else if (st instanceof BooleanSetting b && v instanceof Boolean bv) {
                b.set(bv);
            } else if (st instanceof dev.zephyr.core.settings.ModeSetting m && v instanceof String sv) {
                m.set(sv);
            }
        }
    }

    /** Visible to subclasses for HUD label composition. */
    protected final String moduleName() {
        return name;
    }

    /** Registers a setting and returns it for chaining. */
    protected <T extends Setting<?>> T s(T setting) {
        this.settings.add(setting);
        return setting;
    }

    protected BooleanSetting bool(String id, String label, boolean def) {
        return s(new BooleanSetting(id, label, def));
    }

    protected NumberSetting num(String id, String label, double min, double max, double step, double def) {
        return s(new NumberSetting(id, label, min, max, step, def));
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    public void onTick(MinecraftClient client) {
    }

    public void onHudRender(net.minecraft.client.gui.DrawContext ctx, float tickDelta) {
    }

    public void onWorldJoin(MinecraftClient client) {
    }

    public void onWorldLeave(MinecraftClient client) {
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean value) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        this.disableFlash = value ? 0 : 30;
        try {
            if (value) {
                // Modules that drive the same hotbar/aim (e.g. AutoHitCrystal + AnchorMacro +
                // SafeAnchor) fight each other when on together - the item flickers and
                // nothing completes. Turning one on turns the rest of its group off.
                String group = exclusiveGroup();
                if (group != null && dev.zephyr.ZephyrClient.modules() != null) {
                    for (Module other : dev.zephyr.ZephyrClient.modules().all()) {
                        if (other != this && other.isEnabled() && group.equals(other.exclusiveGroup())) {
                            other.setEnabled(false);
                        }
                    }
                }
                onEnable();
            } else {
                onDisable();
            }
        } catch (Throwable e) {
            // A module must never crash the tick loop; failures degrade to disable,
            // but never silently.
            this.enabled = false;
            ModuleManager.recordFault(this, value ? "onEnable" : "onDisable", e);
        }
    }

    /** Modules sharing a non-null group are mutually exclusive (see setEnabled). */
    protected String exclusiveGroup() {
        return null;
    }

    public void handleKeyPress(int keyCode) {
        if (keyCode == bind.get()) {
            toggle();
        }
    }

    public boolean keyMatches(int keyCode) {
        return bind.get() == keyCode;
    }

    /** One-line HUD description, e.g. "AutoCrystal · DoubleTap · 19.6cps". */
    public String hudLabel() {
        return name;
    }

    // ------------------------------------------------------------------
    // Per-tick HUD label cache
    // ------------------------------------------------------------------

    private static int labelTick;
    private String cachedLabel;
    private int cachedLabelAt = -1;

    /** Bumped once per client tick by the module dispatcher. */
    public static void advanceLabelTick() {
        labelTick++;
    }

    /**
     * Per-tick cached {@link #hudLabel()}. The HUD renders at frame rate (250/s) while
     * labels only change per tick (20/s) - rebuilding strings and Text objects per
     * frame was measurable allocator churn, so HUD renderers read this instead.
     */
    public String hudLabelCached() {
        if (cachedLabel == null || cachedLabelAt != labelTick) {
            cachedLabel = hudLabel();
            cachedLabelAt = labelTick;
        }
        return cachedLabel;
    }

    public String name() {
        return name;
    }

    /**
     * Short, player-facing explanation of what the module does (ClickGui tooltip, search,
     * {@code /zephyr info}). Defaults to the shared {@link ModuleDescriptions} table.
     */
    public String description() {
        return ModuleDescriptions.of(name);
    }

    public ModuleCategory category() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int disableFlashTicks() {
        return disableFlash;
    }

    public void flashDecay() {
        if (disableFlash > 0) {
            disableFlash--;
        }
    }

    public KeySetting bind() {
        return bind;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public Setting<?> setting(String id) {
        for (Setting<?> s : settings) {
            if (s.id().equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }
}
