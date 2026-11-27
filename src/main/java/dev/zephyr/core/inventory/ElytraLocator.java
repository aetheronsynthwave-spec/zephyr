package dev.zephyr.core.inventory;

import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Where the elytra is right now: the chest slot, the hotbar or the main inventory. Free of
 * game classes so the search order can be checked on its own.
 */
public final class ElytraLocator {

    private ElytraLocator() {
    }

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_FIRST = 9;
    private static final int MAIN_END = 36;

    public enum Place { WORN, HOTBAR, MAIN, NONE }

    /**
     * @param slot   hotbar index 0..8 for {@code HOTBAR}, player screen-handler slot 9..35 for
     *               {@code MAIN}, otherwise -1
     * @param usable whether that elytra can still glide
     */
    public record Found(Place place, int slot, boolean usable) {
    }

    /**
     * An elytra in the chest slot always wins. Otherwise a usable one (hotbar before main
     * inventory) is preferred over a worn-out one anywhere.
     *
     * @param hotbar stack at hotbar index 0..8
     * @param main   stack at player screen-handler slot 9..35
     */
    public static <S> Found locate(S worn, IntFunction<S> hotbar, IntFunction<S> main,
                                   Predicate<S> elytra, Predicate<S> usable) {
        if (elytra.test(worn)) {
            return new Found(Place.WORN, -1, usable.test(worn));
        }
        Found found = search(hotbar, main, usable, true);
        if (found == null) {
            found = search(hotbar, main, elytra, false);
        }
        return found != null ? found : new Found(Place.NONE, -1, false);
    }

    private static <S> Found search(IntFunction<S> hotbar, IntFunction<S> main, Predicate<S> match,
                                    boolean usable) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (match.test(hotbar.apply(i))) {
                return new Found(Place.HOTBAR, i, usable);
            }
        }
        for (int slot = MAIN_FIRST; slot < MAIN_END; slot++) {
            if (match.test(main.apply(slot))) {
                return new Found(Place.MAIN, slot, usable);
            }
        }
        return null;
    }
}
