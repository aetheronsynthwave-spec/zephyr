package dev.zephyr.core;

/**
 * Logical grouping of modules. Order of constants drives the plain-text HUD ordering.
 */
public enum ModuleCategory {
    COMBAT("Combat"),
    CRYSTAL("Crystal"),
    SURVIVAL("Survival"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    GUI("Gui");

    private final String label;

    ModuleCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
