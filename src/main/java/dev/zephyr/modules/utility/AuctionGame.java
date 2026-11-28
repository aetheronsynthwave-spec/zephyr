package dev.zephyr.modules.utility;

/** What the automatic routines can see and do. Implemented by {@code AuctionClient}. */
public interface AuctionGame {

    /** An open server window: its id changes whenever the server opens another one. */
    record Window(int id, String title, java.util.List<AuctionDeals.SlotView> slots) {
        public AuctionDeals.SlotView at(int slot) {
            return slots.stream().filter(s -> s.slot() == slot).findFirst().orElse(null);
        }

        public AuctionBuyer.ScreenView view() {
            return new AuctionBuyer.ScreenView(title, slots);
        }
    }

    record Item(String id, String name, String key) {
    }

    enum Press { PICKUP, QUICK_MOVE, SWAP, QUICK_CRAFT }

    /** The open server window, or null. Your own inventory screen is not one. */
    Window window();

    /** True when no screen at all is open. */
    boolean free();

    /** Main inventory and hotbar by your own screen handler's slot ids (9-44). */
    AuctionSeller.Inventory inventory();

    /** A slot of your own screen handler (0 crafting result, 1-4 grid), or null if empty. */
    AuctionDeals.SlotView own(int slot);

    /** Number of items on the mouse cursor. */
    int cursor();

    void command(String command);

    /** The command sent within the last two seconds, or null. */
    String recentCommand();

    void click(int slot, int button, Press press);

    void select(int hotbar);

    /** Closes a server window or your inventory screen; leaves other screens alone. */
    void close();

    void openInventory();

    boolean inventoryOpen();

    /** The plain item with this id, or null if there is no such item. */
    Item item(String id);
}
