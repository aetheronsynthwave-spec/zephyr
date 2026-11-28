package dev.zephyr.modules.utility;

import java.util.List;

/**
 * Sells one bought stack: puts it in the hotbar, selects it, sends {@code /ah sell
 * <price>}, confirms in the window that opens, and counts the item as listed only when it
 * has left the inventory. One action per tick. Free of game classes.
 */
public final class AuctionSeller {

    /** A main inventory or hotbar slot; {@code hotbar} is -1 outside the hotbar. */
    public record InvStack(int slot, int hotbar, String name, String key, int count) {
    }

    public record Inventory(List<InvStack> stacks, List<Integer> emptyHotbar, int selected) {
    }

    public record Job(long entryId, String name, String key, int count, long price) {
    }

    public record Result(long entryId, boolean listed, String message) {
    }

    public static final class Options {
        /** Hotbar index used when no hotbar slot is empty. */
        public int sellSlot = 8;
        public boolean checkItem = true;
        public int stepTicks = 4;
        public int confirmDelayTicks = 4;
        public int timeoutTicks = 200;
        public int verifyTicks = 40;
    }

    public enum Kind { SWAP, SELECT, COMMAND, CLICK }

    public record Act(Kind kind, int slot, int hotbar, String command) {
    }

    private enum State { IDLE, MOVE, SELECT, AWAIT_CONFIRM, VERIFY }

    private State state = State.IDLE;
    private Job job;
    private long since;
    private long confirmSeen;
    private int homeSlot;
    private int before;
    private boolean restore;
    private Result result;
    private String status = "";

    /** A sale is in progress. */
    public boolean busy() {
        return state != State.IDLE;
    }

    /** The hotbar slot you held before the last sale still has to be selected again. */
    public boolean restoring() {
        return state == State.IDLE && restore;
    }

    public Job job() {
        return job;
    }

    public String status() {
        return status;
    }

    /** True while the confirm window is yours to click (button not learned). */
    public boolean awaitingConfirm() {
        return state == State.AWAIT_CONFIRM;
    }

    public void start(Job next, long now) {
        job = next;
        homeSlot = -1;
        confirmSeen = -1;
        restore = false;
        enter(State.MOVE, now);
        status = "selling " + next.name();
    }

    /** The finished job's outcome, once. */
    public Result poll() {
        Result r = result;
        result = null;
        return r;
    }

    public void abort(String why) {
        if (state != State.IDLE) {
            result = new Result(job.entryId(), false, why);
        }
        state = State.IDLE;
        restore = false;
    }

    /** True shortly after the confirm click, while the item is expected to leave. */
    public boolean verifying() {
        return state == State.VERIFY;
    }

    /** You clicked in the sell window yourself. A later click restarts the check. */
    public void manualConfirm(long now) {
        if (state == State.AWAIT_CONFIRM || state == State.VERIFY) {
            enter(State.VERIFY, now);
        }
    }

    /**
     * @param screen the open container window, or null
     * @param free   true when no screen of any kind is open
     */
    public Act tick(Inventory inv, AuctionBuyer.ScreenView screen, boolean free, long now, Options o,
                    AuctionBuyer.Button button) {
        if (state == State.IDLE) {
            if (restore && free) {
                if (homeSlot >= 0 && inv.selected() != homeSlot) {
                    return new Act(Kind.SELECT, -1, homeSlot, null);
                }
                restore = false;
            }
            return null;
        }
        return switch (state) {
            case MOVE -> move(inv, free, now, o);
            case SELECT -> select(inv, free, now, o);
            case AWAIT_CONFIRM -> confirm(screen, now, o, button);
            case VERIFY -> {
                if (now - since >= o.verifyTicks) {
                    boolean gone = total(inv) <= before - job.count();
                    finish(gone, gone ? "listed " + job.name() + " for " + PriceText.shorten(job.price())
                            : job.name() + " is still in your inventory: not listed");
                }
                yield null;
            }
            default -> null;
        };
    }

    private Act move(Inventory inv, boolean free, long now, Options o) {
        if (now - since > o.timeoutTicks) {
            return finish(false, free ? "could not move the item" : "close all windows to sell");
        }
        if (!free || now - since < o.stepTicks) {
            return null;
        }
        InvStack found = find(inv);
        if (found == null) {
            // A just-bought item can take a moment to arrive.
            return now - since < o.verifyTicks ? null
                    : finish(false, "no stack of exactly " + job.count() + " " + job.name() + " in your inventory");
        }
        if (homeSlot < 0) {
            homeSlot = inv.selected();
        }
        enter(State.SELECT, now);
        if (found.hotbar() >= 0) {
            return null;
        }
        int target = inv.emptyHotbar().isEmpty() ? o.sellSlot : inv.emptyHotbar().get(0);
        return new Act(Kind.SWAP, found.slot(), target, null);
    }

    private Act select(Inventory inv, boolean free, long now, Options o) {
        if (now - since > o.timeoutTicks) {
            return finish(false, "could not hold the item");
        }
        if (!free || now - since < o.stepTicks) {
            return null;
        }
        InvStack found = find(inv);
        if (found == null || found.hotbar() < 0) {
            return finish(false, "the item did not reach your hotbar");
        }
        if (inv.selected() != found.hotbar()) {
            restore = true;
            return new Act(Kind.SELECT, -1, found.hotbar(), null);
        }
        before = total(inv);
        enter(State.AWAIT_CONFIRM, now);
        return new Act(Kind.COMMAND, -1, -1, "ah sell " + job.price());
    }

    private Act confirm(AuctionBuyer.ScreenView screen, long now, Options o, AuctionBuyer.Button button) {
        if (now - since > o.timeoutTicks) {
            return finish(false, button.set() ? "no sell confirm window matched the learned button"
                    : "no click in the sell window");
        }
        if (!button.set()) {
            status = "click confirm in the sell window yourself (it will be remembered)";
            return null;
        }
        if (screen == null || !button.on(screen)) {
            confirmSeen = -1;
            return null;
        }
        if (confirmSeen < 0) {
            confirmSeen = now;
        }
        if (now - confirmSeen < o.confirmDelayTicks) {
            return null;
        }
        boolean shown = false;
        for (AuctionDeals.SlotView s : screen.slots()) {
            PriceText.Amount price = PriceText.read(s.lore());
            // An abbreviated price ("1.1K") is only compared as closely as it is shown.
            if (price != null && Math.abs(price.value() - job.price()) > Math.max(1, price.step())) {
                return finish(false, "sell window shows " + PriceText.shorten(price.value()) + ", not confirmed");
            }
            shown |= s.slot() != button.slot && s.name().equalsIgnoreCase(job.name()) && s.count() == job.count();
        }
        if (o.checkItem && !shown) {
            return finish(false, "sell window does not show the item (see sellCheckItem), not confirmed");
        }
        enter(State.VERIFY, now);
        return new Act(Kind.CLICK, button.slot, -1, null);
    }

    /** Only a stack of exactly the bought size: a merged stack would sell more than bought. */
    private InvStack find(Inventory inv) {
        InvStack best = null;
        for (InvStack s : inv.stacks()) {
            if (same(s) && s.count() == job.count()
                    && (best == null || best.hotbar() < 0 && s.hotbar() >= 0
                    || s.hotbar() == inv.selected())) {
                best = s;
            }
        }
        return best;
    }

    private boolean same(InvStack s) {
        return job.key().isEmpty() ? s.name().equalsIgnoreCase(job.name()) : s.key().equals(job.key());
    }

    private int total(Inventory inv) {
        return inv.stacks().stream().filter(this::same).mapToInt(InvStack::count).sum();
    }

    private Act finish(boolean listed, String message) {
        result = new Result(job.entryId(), listed, message);
        status = message;
        state = State.IDLE;
        return null;
    }

    private void enter(State next, long now) {
        state = next;
        since = now;
    }
}
