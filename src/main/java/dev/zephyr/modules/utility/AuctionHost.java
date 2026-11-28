package dev.zephyr.modules.utility;

import java.util.List;

/** What a routine needs from the AuctionFlipper that runs it. */
public interface AuctionHost {

    AuctionLedger ledger();

    /** Epoch milliseconds, as used in the ledger. */
    long millis();

    AuctionBuyer.Button confirmButton();

    AuctionBuyer.Button sellButton();

    AuctionSeller seller();

    AuctionSeller.Options sellOptions();

    /** Why a purchase of up to {@code price} is not allowed now, or null when it is. */
    String limit(AuctionSetup setup, double price);

    /** Registers a purchase that the server's chat line has to confirm. */
    AuctionLedger.Expected expect(AuctionDeals.SlotView item, double price, String owner, boolean sent, int ticks);

    /** A buy click was sent: counts it against the session limits. */
    void sent(double price);

    /** A listing was clicked in this window; your confirm click comes in a later one. */
    void listingClicked(AuctionGame.Window window);

    /** Promising listings on this page, best first. */
    List<AuctionDeals.Deal> candidates(List<AuctionDeals.SlotView> slots, long now);

    /** The command that makes the server print your balance, or "" when none is set up. */
    String balanceCommand();

    /** Changes every time a balance line is read. */
    int balanceReads();

    void say(String message);

    /** A sale attempt finished; the ledger changed. */
    void saleEnded(boolean listed);

    /** The ledger changed. */
    void save();
}
