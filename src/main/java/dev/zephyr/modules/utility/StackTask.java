package dev.zephyr.modules.utility;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Makes your inventory hold a stack of exactly {@code count} of an item, so a sale lists
 * what was bought and no more: the server merges equal items, so it splits a larger stack
 * or joins smaller ones. One click per few ticks, with no screen open. The result is ""
 * when the stack is there, else why not. Free of game classes.
 */
public final class StackTask implements AuctionRoutine.Task<String> {

    private static final int GAP = 3;
    /** More rounds than any join and split needs; a server that undoes clicks ends here. */
    private static final int ROUNDS = 40;

    private final AuctionGame game;
    private final String key;
    private final String name;
    private final int count;
    private final Deque<Runnable> clicks = new ArrayDeque<>();
    private long start = -1;
    private long next;
    private int rounds;

    public StackTask(AuctionGame game, String key, String name, int count) {
        this.game = game;
        this.key = key;
        this.name = name;
        this.count = count;
    }

    @Override
    public String tick(long now) {
        if (start < 0) {
            start = now;
        }
        if (now < next) {
            return null;
        }
        if (!game.free()) {
            if (!clicks.isEmpty() || game.cursor() > 0) {
                game.close(); // returns the cursor
                clicks.clear();
                return "a window opened while arranging " + name;
            }
            game.close();
            return now - start > 200 ? "close all windows to sell " + name : null;
        }
        next = now + GAP;
        if (!clicks.isEmpty()) {
            clicks.poll().run();
            return null;
        }
        if (game.cursor() > 0) {
            game.close();
            return "an item was left on the cursor while arranging " + name;
        }
        List<AuctionSeller.InvStack> mine = game.inventory().stacks().stream()
                .filter(s -> s.key().equals(key))
                .sorted(Comparator.comparingInt(AuctionSeller.InvStack::count).reversed()).toList();
        if (mine.stream().anyMatch(s -> s.count() == count)) {
            return "";
        }
        if (mine.stream().mapToInt(AuctionSeller.InvStack::count).sum() < count) {
            // A just-bought item can take a moment to arrive.
            return now - start < 40 ? null : "fewer than " + count + " " + name + " in your inventory";
        }
        if (++rounds > ROUNDS) {
            return "could not make a stack of " + count + " " + name;
        }
        AuctionSeller.InvStack big = mine.get(0);
        if (big.count() > count) {
            split(big);
            return clicks.isEmpty() ? "free an inventory slot to split off " + count + " " + name : null;
        }
        if (mine.size() < 2) {
            return "could not make a stack of " + count + " " + name;
        }
        AuctionSeller.InvStack other = mine.get(1);
        press(other.slot(), 0);
        press(big.slot(), 0);
        press(other.slot(), 0); // whatever did not fit goes back
        return null;
    }

    /** Picks the stack up and right-clicks single items into an empty slot. */
    private void split(AuctionSeller.InvStack from) {
        int empty = emptySlot();
        if (empty < 0) {
            return;
        }
        // Move whichever is fewer: the amount wanted, or the amount left over.
        int singles = Math.min(count, from.count() - count);
        press(from.slot(), 0);
        for (int i = 0; i < singles; i++) {
            press(empty, 1);
        }
        press(from.slot(), 0);
    }

    private int emptySlot() {
        AuctionSeller.Inventory inv = game.inventory();
        // Hotbar first (36-44), then the rest of the inventory (9-35).
        for (int slot = 44; slot >= 9; slot--) {
            int at = slot;
            if (inv.stacks().stream().noneMatch(i -> i.slot() == at)) {
                return slot;
            }
        }
        return -1;
    }

    private void press(int slot, int button) {
        clicks.add(() -> game.click(slot, button, AuctionGame.Press.PICKUP));
    }
}
