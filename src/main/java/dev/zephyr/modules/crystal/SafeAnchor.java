package dev.zephyr.modules.crystal;

/**
 * SafeAnchor - AnchorMacro that always puts a glowstone shield (obsidian if you have no
 * glowstone) on the floor between you and the charged anchor before detonating it with
 * your sword, exactly like the hand routine: anchor, glowstone charge, glowstone shield,
 * sword click.
 */
public final class SafeAnchor extends AnchorMacro {

    public SafeAnchor() {
        super("SafeAnchor");
    }

    @Override
    protected boolean protectivePlacement() {
        return true;
    }
}
