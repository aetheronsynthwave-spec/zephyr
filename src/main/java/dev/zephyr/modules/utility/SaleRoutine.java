package dev.zephyr.modules.utility;

import java.util.Queue;
import java.util.function.Supplier;

/** Sells the entries you picked (or autoSell queued) while the auto loop is off. */
public final class SaleRoutine extends AuctionRoutine {

    private final Queue<Long> queue;

    public SaleRoutine(AuctionGame game, AuctionHost host, Supplier<AuctionSetup> setup, Queue<Long> queue) {
        super(game, host, setup);
        this.queue = queue;
    }

    @Override
    protected void cycle(long now) {
        Long id = queue.poll();
        AuctionLedger.Entry e = id == null ? null : host.ledger().byId(id);
        if (e != null && (e.status == AuctionLedger.Status.BOUGHT
                || e.status == AuctionLedger.Status.UNCONFIRMED)) {
            sell(e, (result, at) -> {
            });
        }
    }
}
