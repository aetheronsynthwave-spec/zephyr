package dev.zephyr.modules.utility;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Crafts a recipe in your own 2x2 grid, one click per few ticks: puts a stack on the
 * cursor, spreads it over the recipe's cells, takes the result, and repeats until there is
 * not enough left. Slot ids are vanilla's: 0 result, 1-4 grid. Free of game classes.
 */
public final class CraftTask implements AuctionRoutine.Task<CraftTask.Result> {

    /** {@code made} is how many finished items appeared. */
    public record Result(int made, String problem) {
    }

    private static final int GAP = 3;
    private static final int OUTSIDE = -999;

    private final AuctionGame game;
    private final CraftRecipe recipe;
    private final Deque<Runnable> clicks = new ArrayDeque<>();
    private int step;
    private int before = -1;
    private long next;
    private long waiting = -1;
    private boolean taken;
    private int gridLeft = Integer.MAX_VALUE;
    private int stalls;
    private String problem;

    public CraftTask(AuctionGame game, CraftRecipe recipe) {
        this.game = game;
        this.recipe = recipe;
    }

    @Override
    public Result tick(long now) {
        if (before < 0) {
            before = count(recipe.sell());
            if (game.inventory().stacks().size() > 32) {
                return new Result(0, "crafting needs 4 empty inventory slots");
            }
        }
        if (now < next) {
            return null;
        }
        next = now + GAP;
        if (!game.inventoryOpen()) {
            if (taken || step > 0 || !clicks.isEmpty()) {
                return finish("the inventory was closed while crafting");
            }
            if (game.free()) {
                game.openInventory();
            } else {
                game.close();
            }
            return null;
        }
        if (!clicks.isEmpty()) {
            clicks.poll().run();
            return null;
        }
        CraftRecipe.Step s = recipe.steps.get(step);
        if (waiting >= 0) {
            return result(s, now);
        }
        for (int cell = 1; cell <= 4; cell++) {
            if (game.own(cell) != null) {
                return finish("the crafting grid is not empty");
            }
        }
        if (game.cursor() > 0) {
            return finish("an item is stuck on the cursor");
        }
        AuctionSeller.InvStack source = source(s);
        if (source == null) {
            if (++step >= recipe.steps.size()) {
                return finish(null);
            }
            return null;
        }
        place(s, source);
        waiting = now;
        return null;
    }

    /** The largest stack of the ingredient that fills every cell at least once. */
    private AuctionSeller.InvStack source(CraftRecipe.Step s) {
        AuctionSeller.InvStack best = null;
        for (AuctionSeller.InvStack stack : game.inventory().stacks()) {
            if (stack.key().equals(key(s.input)) && stack.count() >= s.cells.size()
                    && (best == null || stack.count() > best.count())) {
                best = stack;
            }
        }
        return best;
    }

    private void place(CraftRecipe.Step s, AuctionSeller.InvStack source) {
        int from = source.slot();
        press(from, 0, AuctionGame.Press.PICKUP);
        if (s.cells.size() == 1) {
            press(1 + s.cells.get(0), 0, AuctionGame.Press.PICKUP);
        } else {
            // Vanilla left-drag: start, each cell, end. The stack is shared out evenly.
            press(OUTSIDE, 0, AuctionGame.Press.QUICK_CRAFT);
            for (int cell : s.cells) {
                press(1 + cell, 1, AuctionGame.Press.QUICK_CRAFT);
            }
            press(OUTSIDE, 2, AuctionGame.Press.QUICK_CRAFT);
            if (source.count() % s.cells.size() != 0) {
                press(from, 0, AuctionGame.Press.PICKUP); // the remainder goes back
            }
        }
    }

    private Result result(CraftRecipe.Step s, long now) {
        AuctionDeals.SlotView out = game.own(0);
        if (!taken) {
            if (out != null && out.itemId().equals(s.output)) {
                game.click(0, 0, AuctionGame.Press.QUICK_MOVE);
                taken = true;
                waiting = now;
            } else if (now - waiting > 40) {
                return finish("no " + s.output + " appeared in the crafting result");
            }
            return null;
        }
        int left = 0;
        for (int cell = 1; cell <= 4; cell++) {
            AuctionDeals.SlotView in = game.own(cell);
            left += in == null ? 0 : in.count();
        }
        if (left == 0) {
            waiting = -1;
            taken = false;
            gridLeft = Integer.MAX_VALUE;
            stalls = 0;
            return null;
        }
        if (now - waiting < 20) {
            return null;
        }
        // Still ingredients in the grid: take the result again, as long as that uses them up.
        stalls = left < gridLeft ? 0 : stalls + 1;
        gridLeft = left;
        if (stalls >= 2) {
            return finish("no room for the crafted items");
        }
        if (out != null && out.itemId().equals(s.output)) {
            game.click(0, 0, AuctionGame.Press.QUICK_MOVE);
        }
        waiting = now;
        return null;
    }

    private Result finish(String why) {
        problem = why;
        int made = Math.max(0, count(recipe.sell()) - before);
        game.close(); // returns anything left in the grid or on the cursor
        return new Result(made, problem);
    }

    private void press(int slot, int button, AuctionGame.Press press) {
        clicks.add(() -> game.click(slot, button, press));
    }

    private String key(String id) {
        AuctionGame.Item item = game.item(id);
        return item == null ? "" : item.key();
    }

    private int count(String id) {
        String key = key(id);
        return game.inventory().stacks().stream().filter(s -> s.key().equals(key))
                .mapToInt(AuctionSeller.InvStack::count).sum();
    }
}
