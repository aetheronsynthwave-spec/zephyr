package dev.zephyr.modules.utility;

/** The settings one automatic routine runs with. Free of game classes. */
public final class AuctionSetup {
    public boolean askFirst = true;
    /** Profit required over cost, as a fraction. */
    public double minGain = 0.10;
    /** Selling fee as a fraction. */
    public double fee = 0.0;
    /** Dollars listed under the lowest competitor. */
    public double undercut = 1;
    /** Decimals the server keeps in "1.1K" (at least 1, from a DonutSMP screenshot dated 2026-11-27). */
    public int priceDecimals = 1;
    /** Highest price to pay for one listing; 0 means no cap (only allowed when asking first). */
    public double maxPrice = 0;
    /** Search again after listing and report whether yours is the cheapest. */
    public boolean verifyListing = true;
    /** Slots below this index are listings. */
    public int listingSlots = 45;
    /** Next page button, from a DonutSMP screenshot dated 2026-11-27. */
    public int nextSlot = 53;
    public String nextItem = "minecraft:arrow";
    public int browsePages = 3;
    /** True only if you have seen that search results are sorted cheapest first: reads page 1. */
    public boolean cheapestFirst = false;
    /** A price check that cannot reach the last page within this many does not price. */
    public int checkPages = 10;
    public int settleTicks = 10;
    public int windowTicks = 60;
    public int commandGapTicks = 20;
    public int promptTicks = 200;
    public int restTicks = 200;
    public int recheckTicks = 2400;
    public int confirmDelayTicks = 4;
    public int replyTicks = 160;
    public boolean confirmPrice = true;
    public boolean oneClick = false;
    public int maxHeld = 5;
}
