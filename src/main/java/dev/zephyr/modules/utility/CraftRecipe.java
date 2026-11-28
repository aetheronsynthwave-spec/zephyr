package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.List;

/** Buy one item, craft it in the inventory grid in steps, sell the result one at a time. */
public final class CraftRecipe {

    /** One ingredient placed in some of the four grid cells (0-3). */
    public static final class Step {
        public String input = "";
        public List<Integer> cells = new ArrayList<>();
        public String output = "";
        public int gives = 1;

        public Step() {
        }

        public Step(String input, List<Integer> cells, String output, int gives) {
            this.input = input;
            this.cells = new ArrayList<>(cells);
            this.output = output;
            this.gives = gives;
        }
    }

    public boolean enabled = true;
    public List<Step> steps = new ArrayList<>();

    public String buy() {
        return steps.get(0).input;
    }

    public String sell() {
        return steps.get(steps.size() - 1).output;
    }

    /** How many finished items {@code count} bought items turn into. */
    public int yield(int count) {
        long n = count;
        for (Step s : steps) {
            n = n / s.cells.size() * s.gives;
        }
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    public boolean valid() {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s == null || s.input == null || s.output == null || s.cells == null || s.cells.isEmpty()
                    || s.gives < 1 || s.cells.stream().anyMatch(c -> c == null || c < 0 || c > 3)
                    || s.cells.stream().distinct().count() != s.cells.size()
                    || i > 0 && !s.input.equals(steps.get(i - 1).output)) {
                return false;
            }
        }
        return true;
    }

    /** Spruce log -> 4 spruce planks -> crafting table (vanilla recipes). */
    public static CraftRecipe craftingTable() {
        CraftRecipe r = new CraftRecipe();
        r.steps.add(new Step("minecraft:spruce_log", List.of(0), "minecraft:spruce_planks", 4));
        r.steps.add(new Step("minecraft:spruce_planks", List.of(0, 1, 2, 3), "minecraft:crafting_table", 1));
        return r;
    }
}
