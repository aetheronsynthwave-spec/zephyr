package dev.zephyr.modules.utility;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * An automatic job made of tasks run one after another: {@link #await} runs a task each
 * tick until it has a result, then continues with it. With nothing awaited, {@link #cycle}
 * decides what to do next. Free of game classes.
 */
public abstract class AuctionRoutine {

    /** @return the result once finished, null while working. */
    public interface Task<T> {
        T tick(long now);
    }

    private interface Step {
        boolean run(long now);
    }

    protected final AuctionGame game;
    protected final AuctionHost host;
    protected final Supplier<AuctionSetup> setup;
    /** Ledger entry id -> tick before which its sale is not tried again. */
    private final Map<Long, Long> retryAt = new HashMap<>();
    private Step step;
    private long restUntil;
    private long moneyAt = Long.MIN_VALUE / 2;
    private BuyTask buying;
    protected String status = "starting";

    protected AuctionRoutine(AuctionGame game, AuctionHost host, Supplier<AuctionSetup> setup) {
        this.game = game;
        this.host = host;
        this.setup = setup;
    }

    public final void tick(long now) {
        if (step == null) {
            if (now >= restUntil) {
                cycle(now);
            }
            return;
        }
        Step running = step;
        if (running.run(now) && step == running) {
            step = null;
        }
    }

    protected abstract void cycle(long now);

    public boolean idle() {
        return step == null;
    }

    protected <T> void await(Task<T> task, BiConsumer<T, Long> then) {
        step = now -> {
            T result = task.tick(now);
            if (result == null) {
                return false;
            }
            then.accept(result, now);
            return true;
        };
    }

    protected void rest(long now, int ticks, String why) {
        restUntil = now + ticks;
        status = why;
    }

    /** Drops everything in progress and closes what the routine opened. */
    public void stop() {
        step = null;
        buying = null;
        game.close();
    }

    public String status() {
        return buying != null && !buying.status().isEmpty() ? buying.status() : status;
    }

    /** Starts reading your money when a balance command is set up and it is a minute old. */
    protected boolean readMoney(long start) {
        if (host.balanceCommand().isEmpty() || start - moneyAt < 1200) {
            return false;
        }
        moneyAt = start;
        int before = host.balanceReads();
        status = "reading your money";
        game.close();
        boolean[] sent = {false};
        await(now -> {
            if (!sent[0]) {
                if (game.free()) {
                    game.command(host.balanceCommand());
                    sent[0] = true;
                }
                return null;
            }
            return host.balanceReads() != before || now - start > 100 ? Boolean.TRUE : null;
        }, (done, now) -> {
        });
        return true;
    }

    // ------------------------------------------------------------------
    // Buying
    // ------------------------------------------------------------------

    protected void buy(MarketScan.Market market, MarketScan.Offer offer, String owner,
                       BiConsumer<BuyTask.Result, Long> then) {
        buying = new BuyTask(game, host, setup.get(), market.item(), offer, owner);
        await(buying, (result, now) -> {
            buying = null;
            status = result.message();
            host.say(result.message());
            then.accept(result, now);
        });
    }

    /** The question you are being asked, or null. */
    public String question() {
        return buying == null ? null : buying.question();
    }

    /** The slot the question is about, or -1. */
    public int questionSlot() {
        return buying == null ? -1 : buying.slot();
    }

    public void answer(boolean yes) {
        if (buying != null) {
            buying.answer(yes);
        }
    }

    public boolean awaitingManualBuy() {
        return buying != null && buying.awaitingManualConfirm();
    }

    public void bought(AuctionLedger.Purchase purchase) {
        if (buying != null) {
            buying.bought(purchase);
        }
    }

    // ------------------------------------------------------------------
    // Selling
    // ------------------------------------------------------------------

    protected boolean due(AuctionLedger.Entry e, long now) {
        return now >= retryAt.getOrDefault(e.id, 0L);
    }

    /** Checks the market, then lists the entry as the cheapest if that still makes the profit. */
    protected void sell(AuctionLedger.Entry entry, BiConsumer<SellTask.Result, Long> then) {
        status = "checking the market for " + entry.name;
        await(new SellTask(game, host, setup.get(), entry), (result, now) -> {
            status = result.message();
            host.say(result.message());
            if (!result.listed()) {
                retryAt.put(entry.id, now + setup.get().recheckTicks);
            }
            then.accept(result, now);
        });
    }
}
