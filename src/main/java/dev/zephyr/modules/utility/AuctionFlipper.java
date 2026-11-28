package dev.zephyr.modules.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.KeySetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.mixin.accessor.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AuctionFlipper - on DonutSMP's /ah it finds listings priced under what the same item has
 * been listed for, asks you, buys the best one (see {@link AuctionBuyer}), keeps a list of
 * what was bought and sold (see {@link AuctionLedger}) and relists bought stacks with
 * {@code /ah sell} (see {@link AuctionSeller}). Confirm buttons are remembered from your
 * own clicks and stored with the item rules in config/zephyr/auction.json. Money settings
 * are in thousands.
 */
public final class AuctionFlipper extends Module {

    /** Contents of auction.json. */
    private static final class Saved {
        String auctionTitle = "";
        AuctionBuyer.Button confirm = new AuctionBuyer.Button();
        AuctionBuyer.Button refresh = new AuctionBuyer.Button();
        AuctionBuyer.Button sellConfirm = new AuctionBuyer.Button();
        /** From a DonutSMP chat screenshot dated 2026-11-27. */
        String boughtMessage = "You bought {count} {item} for $ {price}";
        /** Unset: no sale message has been seen yet. */
        String soldMessage = "";
        AuctionDeals.Rules rules = new AuctionDeals.Rules();
    }

    /** Contents of auction-ledger.json. */
    private static final class Book {
        List<AuctionLedger.Entry> entries = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = Path.of("config", "zephyr");
    private static final Path FILE = DIR.resolve("auction.json");
    private static final Path LEDGER_FILE = DIR.resolve("auction-ledger.json");
    private static final Path CHAT_LOG = DIR.resolve("auction-chat.log");
    private static final long REPLY_MILLIS = 8_000;

    // Basic settings (see SettingLayout); everything else is in the Advanced section.
    private final BooleanSetting askFirst = bool("askFirst", "Ask before buying", true);
    private final NumberSetting maxPrice = num("maxPrice", "Max price (k, 0 = any)", 0, 1_000_000, 1, 0);
    private final NumberSetting budget = num("budget", "Budget (k, 0 = any)", 0, 10_000_000, 1, 0);
    private final BooleanSetting autoSell = bool("autoSell", "Sell after buying", false);

    private final BooleanSetting autoBuy = bool("autoBuy", "Allow buying", true);
    private final NumberSetting maxBuys = num("maxBuys", "Max buys per session", 1, 500, 1, 10);
    private final KeySetting buyKey = s(new KeySetting("buyKey", "Yes, buy", 89));
    private final KeySetting skipKey = s(new KeySetting("skipKey", "No, skip", 78));
    private final KeySetting ledgerKey = s(new KeySetting("ledgerKey", "Open bought / sold", 0));
    private final NumberSetting promptTime = num("promptTime", "Question timeout (seconds)", 3, 60, 1, 10);
    private final ModeSetting rank = s(new ModeSetting("rank", "Best deal means",
            names(AuctionDeals.Rank.values()), AuctionDeals.Rank.PROFIT.name()));
    private final ModeSetting reference = s(new ModeSetting("reference", "Resale price from",
            names(AuctionDeals.Reference.values()), AuctionDeals.Reference.MEDIAN.name()));
    private final NumberSetting discount = num("discount", "Min % below reference", 0, 95, 1, 30);
    private final NumberSetting minProfit = num("minProfit", "Min margin (thousands)", 0, 10000, 5, 10);
    private final NumberSetting minRoi = num("minRoi", "Min return on price %", 0, 1000, 5, 0);
    private final NumberSetting minPrice = num("minPrice", "Min price (thousands)", 0, 100000, 1, 0);
    private final NumberSetting samples = num("samples", "Other listings needed", 1, 60, 1, 5);
    private final NumberSetting tax = num("tax", "Selling fee %", 0, 20, 0.5, 0);
    private final NumberSetting memory = num("memory", "Price memory (minutes)", 1, 240, 1, 30);
    private final NumberSetting listingSlots = num("listingSlots", "Listing slots (0 = all)", 0, 54, 9, 45);
    private final BooleanSetting skipContainers = bool("skipContainers", "Skip filled containers", true);
    private final BooleanSetting whitelistOnly = bool("whitelistOnly", "Only whitelisted items", false);
    private final BooleanSetting oneClick = bool("oneClick", "Server buys on first click", false);
    private final BooleanSetting confirmPrice = bool("confirmPrice", "Confirm window must show price", true);
    private final BooleanSetting refresh = bool("refresh", "Press learned refresh button", false);
    private final NumberSetting settle = num("settle", "Page settle ticks", 0, 40, 1, 4);
    private final NumberSetting buyDelay = num("buyDelay", "Extra delay before buy (ticks)", 0, 100, 1, 0);
    private final NumberSetting confirmDelay = num("confirmDelay", "Delay before confirm (ticks)", 0, 40, 1, 2);
    private final NumberSetting timeout = num("timeout", "Confirm timeout (ticks)", 10, 200, 5, 60);
    private final NumberSetting cooldown = num("cooldown", "Pause between buys (ticks)", 1, 600, 1, 20);
    private final NumberSetting retry = num("retry", "Retry same listing after (seconds)", 5, 3600, 5, 60);
    private final NumberSetting refreshEvery = num("refreshEvery", "Refresh interval (ticks)", 10, 1200, 5, 40);
    private final NumberSetting undercut = num("undercut", "Sell under reference %", 0, 50, 1, 2);
    private final NumberSetting sellMargin = num("sellMargin", "Min gain over cost %", 0, 500, 1, 5);
    private final NumberSetting sellSlot = num("sellSlot", "Hotbar slot for selling", 1, 9, 1, 9);
    private final BooleanSetting sellCheckItem = bool("sellCheckItem", "Sell window must show item", true);
    private final BooleanSetting highlight = bool("highlight", "Highlight deals", true);
    private final BooleanSetting logChat = bool("logChat", "Log chat lines with $", false);
    private final KeySetting dumpKey = s(new KeySetting("dumpKey", "Dump open window", 0));

    private final AuctionBuyer buyer = new AuctionBuyer();
    private final AuctionSeller seller = new AuctionSeller();
    private final AuctionLedger ledger = new AuctionLedger();
    private final Deque<Long> sellQueue = new ArrayDeque<>();
    private Saved saved = new Saved();
    private Pattern boughtPattern;
    private Pattern soldPattern;
    /** The JSON file exists but cannot be used: no buying or selling, and it is not overwritten. */
    private boolean broken;
    private boolean ledgerBroken;
    private AuctionBuyer.Kind learning;
    /** While learning confirm: the listing you clicked first (window id, title, item). */
    private int learnFrom = -1;
    private String learnTitle = "";
    private String learnKey = "";
    /** The window the flipper last clicked a listing in. */
    private int listingWindow = -1;
    private String listingTitle = "";
    /** The sell button you clicked, kept until the item is seen to leave. */
    private AuctionBuyer.Button sellCandidate;
    private int sellWindow = -1;
    private boolean ownClick;
    private boolean openRequested;
    private String reported = "";

    public AuctionFlipper() {
        super("AuctionFlipper", ModuleCategory.DONUT, 0);
        reload();
        loadLedger();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?> handled) {
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> draw(context, handled));
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChat(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> v : values) {
            out.add(v.name());
        }
        return out;
    }

    @Override
    protected void onDisable() {
        stop("AuctionFlipper switched off"); // totals stay: toggling must not reset the budget
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        stop("left the world");
    }

    private void stop(String why) {
        buyer.abort();
        seller.abort(why);
        finishSale();
        sellQueue.clear();
        for (AuctionLedger.Entry e : ledger.entries()) {
            if (e.status == AuctionLedger.Status.SELLING) {
                e.status = AuctionLedger.Status.BOUGHT;
            }
        }
        saveLedger();
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            buyer.abort();
            return;
        }
        HandledScreen<?> screen = client.currentScreen instanceof HandledScreen<?> h
                && h.getScreenHandler() == client.player.currentScreenHandler ? h : null;
        // Teaching a button is your click; the flipper stays out of the way meanwhile.
        if (learning != null || !TickPhase.actionWindowOpen()
                || screen != null && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            return; // decided before anything runs, so nothing counts a click that is not sent
        }
        long now = TickPhase.tickId();
        AuctionBuyer.ScreenView view = screen == null ? null : view(client, screen);
        boolean free = client.currentScreen == null;
        if (!seller.busy() && !sellQueue.isEmpty() && free && buyer.opening() == null) {
            startSale(sellQueue.poll(), now);
        }
        if (seller.busy() || seller.restoring() && free) {
            buyer.abort();
            act(client, seller.tick(inventory(client), view, free, now, sellOptions(), saved.sellConfirm));
            finishSale();
        } else {
            AuctionBuyer.Click click = buyer.tick(view, now, options(), filter(), saved.rules, saved.confirm,
                    saved.refresh);
            if (click != null && screen != null) {
                if (click.kind() == AuctionBuyer.Kind.LISTING) {
                    listingWindow = screen.getScreenHandler().syncId;
                    listingTitle = view.title();
                    AuctionDeals.Deal opening = buyer.opening();
                    if (opening != null && !saved.confirm.set()) {
                        ledger.expect(expectation(opening, false, promptTime.asInt() * 1000L + REPLY_MILLIS));
                    }
                }
                if (click.buys() != null) {
                    ledger.expect(expectation(click.buys(), true, REPLY_MILLIS));
                }
                click(client, click.slot(), SlotActionType.PICKUP, 0);
            }
        }
        report();
    }

    private void click(MinecraftClient client, int slot, SlotActionType type, int button) {
        ownClick = true;
        try {
            InventoryManager.clickSlot(client, slot, button, type);
        } finally {
            ownClick = false;
        }
    }

    private void act(MinecraftClient client, AuctionSeller.Act act) {
        if (act == null) {
            return;
        }
        switch (act.kind()) {
            case SWAP -> click(client, act.slot(), SlotActionType.SWAP, act.hotbar());
            case SELECT -> SwapManager.select(client, act.hotbar());
            case COMMAND -> client.player.networkHandler.sendChatCommand(act.command());
            case CLICK -> click(client, act.slot(), SlotActionType.PICKUP, 0);
        }
    }

    /** Runs whether or not the module is on: chat replies and the ledger screen. */
    private void endTick(MinecraftClient client) {
        if (ledger.expire(System.currentTimeMillis())) {
            say("no chat reply for a buy: listed as unconfirmed.");
            saveLedger();
        }
        if (openRequested) {
            openRequested = false;
            client.setScreen(new AuctionLedgerScreen(this));
        }
    }

    private AuctionLedger.Expected expectation(AuctionDeals.Deal d, boolean sent, long millis) {
        double listAt = Math.floor(d.reference() * d.view().count() * (1.0 - undercut.get() / 100.0));
        return new AuctionLedger.Expected(d.view().name(), d.view().key(), d.view().count(), d.price(), listAt, sent,
                System.currentTimeMillis() + millis);
    }

    private void report() {
        String status = buyer.status();
        if (!status.equals(reported)) {
            reported = status;
            if (status.startsWith("sent buy") || status.contains("not confirmed") || status.startsWith("click confirm")
                    || status.startsWith("confirm button")) {
                say(status);
            }
        }
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private void onChat(String line) {
        long now = System.currentTimeMillis();
        if (logChat.get() && line.contains("$")) {
            try {
                Files.createDirectories(DIR);
                Files.writeString(CHAT_LOG, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[Zephyr] chat log: " + e);
            }
        }
        AuctionChat.Hit bought = AuctionChat.match(boughtPattern, line);
        if (bought != null) {
            AuctionLedger.Purchase purchase = ledger.bought(bought.item(), bought.count(), bought.price(), now);
            AuctionLedger.Expected x = purchase.expected();
            if (x != null) {
                if (x.sent) {
                    buyer.adjust(x.price, bought.price());
                } else {
                    // You confirmed it yourself, and the reply proves which button bought.
                    if (buyer.awaitingManualConfirm()) {
                        buyer.manualBought(bought.price(), TickPhase.tickId());
                    } else {
                        buyer.adjust(0, bought.price()); // still counts against the budget
                    }
                    if (x.tag instanceof AuctionBuyer.Button button) {
                        saved.confirm = button;
                        save();
                        say("remembered the confirm button: slot " + button.slot + " in \"" + button.title + "\"");
                    }
                }
                if (autoSell.get() && isEnabled()) {
                    requestSale(purchase.entry().id, false);
                }
            }
            saveLedger();
            return;
        }
        AuctionChat.Hit sold = AuctionChat.match(soldPattern, line);
        if (sold != null) {
            ledger.sold(sold.item(), sold.count(), sold.price(), now);
            saveLedger();
        }
    }

    // ------------------------------------------------------------------
    // Selling
    // ------------------------------------------------------------------

    public AuctionLedger ledger() {
        return ledger;
    }

    public boolean soldMessageSet() {
        return soldPattern != null;
    }

    /** The lowest price a stack may be listed at, from what it cost. */
    public double floorPrice(AuctionLedger.Entry e) {
        // The selling fee comes out of the sale, so the floor is raised to cover it.
        return e.paid > 0 ? Math.ceil(e.paid * (1.0 + sellMargin.get() / 100.0) / (1.0 - tax.get() / 100.0)) : 1;
    }

    /** Marks a sale by hand, for when no sold message is set. */
    public void markSold(long id) {
        AuctionLedger.Entry e = ledger.byId(id);
        if (e != null && e.status == AuctionLedger.Status.LISTED) {
            e.status = AuctionLedger.Status.SOLD;
            e.soldFor = e.asked;
            saveLedger();
        }
    }

    public void removeEntry(long id) {
        sellQueue.remove(id);
        AuctionSeller.Job job = seller.job();
        if (!(seller.busy() && job.entryId() == id)) {
            ledger.remove(id);
            saveLedger();
        }
    }

    /**
     * Queues a bought stack for sale. {@code chosen} means you picked the price on the
     * ledger screen; an automatic sale never goes under {@link #floorPrice}.
     *
     * @return why it was not queued, or null
     */
    public String requestSale(long id, boolean chosen) {
        AuctionLedger.Entry e = ledger.byId(id);
        String why = null;
        if (broken) {
            why = "fix " + FILE + " first";
        } else if (!isEnabled()) {
            why = "switch AuctionFlipper on to sell";
        } else if (e == null || e.status == AuctionLedger.Status.SOLD || e.status == AuctionLedger.Status.LISTED
                || e.status == AuctionLedger.Status.SELLING) {
            why = "that entry cannot be sold";
        } else if (e.listAt < 1) {
            why = e.name + " has no sale price: set one on the bought / sold screen";
        } else if (!chosen && e.listAt < floorPrice(e)) {
            why = e.name + ": suggested " + PriceText.shorten(e.listAt) + " is under your minimum gain, not listed";
        } else if (!sellQueue.contains(id)) {
            sellQueue.add(id);
        }
        if (why != null && !chosen) {
            say(why);
        }
        return why;
    }

    private void startSale(long id, long now) {
        AuctionLedger.Entry e = ledger.byId(id);
        if (e == null || e.listAt < 1) {
            return;
        }
        e.status = AuctionLedger.Status.SELLING;
        e.asked = Math.floor(e.listAt);
        sellCandidate = null;
        seller.start(new AuctionSeller.Job(e.id, e.name, e.key, e.count, (long) e.asked), now);
        say("selling " + e.count + "x " + e.name + " for " + (long) e.asked);
    }

    private void finishSale() {
        AuctionSeller.Result result = seller.poll();
        if (result == null) {
            return;
        }
        AuctionLedger.Entry e = ledger.byId(result.entryId());
        if (e != null) {
            e.status = result.listed() ? AuctionLedger.Status.LISTED : AuctionLedger.Status.BOUGHT;
        }
        if (result.listed() && sellCandidate != null) {
            saved.sellConfirm = sellCandidate;
            save();
            say("remembered the sell confirm button: slot " + sellCandidate.slot);
        }
        sellCandidate = null;
        say(result.message());
        saveLedger();
    }

    private AuctionSeller.Options sellOptions() {
        AuctionSeller.Options o = new AuctionSeller.Options();
        o.sellSlot = sellSlot.asInt() - 1;
        o.checkItem = sellCheckItem.get();
        return o;
    }

    /** Main inventory and hotbar, by the ids of the player's own screen handler (9-44). */
    private static AuctionSeller.Inventory inventory(MinecraftClient client) {
        var slots = client.player.playerScreenHandler.slots;
        List<AuctionSeller.InvStack> stacks = new ArrayList<>();
        List<Integer> empty = new ArrayList<>();
        for (int i = 9; i < 45 && i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getStack();
            int hotbar = i >= 36 ? i - 36 : -1;
            if (stack.isEmpty()) {
                if (hotbar >= 0) {
                    empty.add(hotbar);
                }
                continue;
            }
            stacks.add(new AuctionSeller.InvStack(i, hotbar, stack.getName().getString(), key(stack),
                    stack.getCount()));
        }
        return new AuctionSeller.Inventory(stacks, empty, client.player.getInventory().getSelectedSlot());
    }

    // ------------------------------------------------------------------
    // Settings snapshots
    // ------------------------------------------------------------------

    private AuctionBuyer.Options options() {
        AuctionBuyer.Options o = new AuctionBuyer.Options();
        o.autoBuy = autoBuy.get() && !broken;
        o.askFirst = askFirst.get();
        o.oneClick = oneClick.get();
        o.confirmPrice = confirmPrice.get();
        o.refresh = refresh.get();
        o.awaitingReply = ledger.awaiting();
        o.maxPrice = maxPrice.get() * 1000.0;
        o.budget = budget.get() * 1000.0;
        o.maxBuys = maxBuys.asInt();
        o.settleTicks = settle.asInt();
        o.buyDelayTicks = buyDelay.asInt();
        o.confirmDelayTicks = confirmDelay.asInt();
        o.timeoutTicks = timeout.asInt();
        o.cooldownTicks = cooldown.asInt();
        o.retryTicks = retry.asInt() * 20;
        o.refreshTicks = refreshEvery.asInt();
        o.promptTicks = promptTime.asInt() * 20;
        o.auctionTitle = saved.auctionTitle;
        return o;
    }

    private AuctionDeals.Filter filter() {
        AuctionDeals.Filter f = new AuctionDeals.Filter();
        f.rank = AuctionDeals.Rank.valueOf(rank.get());
        f.reference = AuctionDeals.Reference.valueOf(reference.get());
        f.minDiscount = discount.get() / 100.0;
        f.minProfit = minProfit.get() * 1000.0;
        f.minRoi = minRoi.get() / 100.0;
        f.minPrice = minPrice.get() * 1000.0;
        f.samples = samples.asInt();
        f.fee = tax.get() / 100.0;
        f.historyTicks = memory.asInt() * 60L * 20L;
        f.skipContainers = skipContainers.get();
        f.whitelistOnly = whitelistOnly.get();
        f.listingSlots = listingSlots.asInt();
        return f;
    }

    private static String key(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getName().getString() + "|"
                + stack.getEnchantments() + "|" + stack.get(DataComponentTypes.POTION_CONTENTS);
    }

    /** The window's own slots (never your inventory), filled ones only. */
    private static AuctionBuyer.ScreenView view(MinecraftClient client, HandledScreen<?> screen) {
        List<AuctionDeals.SlotView> out = new ArrayList<>();
        var slots = screen.getScreenHandler().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.inventory == client.player.getInventory() || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            // The position in the handler's list is the id a click addresses.
            out.add(new AuctionDeals.SlotView(i, Registries.ITEM.getId(stack.getItem()).toString(), key(stack),
                    stack.getName().getString(), stack.getCount(), lore(stack),
                    stack.get(DataComponentTypes.CONTAINER) != null));
        }
        return new AuctionBuyer.ScreenView(screen.getTitle().getString(), out);
    }

    // ------------------------------------------------------------------
    // Your clicks and keys
    // ------------------------------------------------------------------

    /** From the mixin, before any slot click is sent. */
    public void onSlotClick(int syncId, int slotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (ownClick || client.player == null || !(client.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler().syncId != syncId) {
            return;
        }
        var slots = screen.getScreenHandler().slots;
        if (slotId < 0 || slotId >= slots.size() || !slots.get(slotId).hasStack()
                || slots.get(slotId).inventory == client.player.getInventory()) {
            return;
        }
        AuctionBuyer.Button button = new AuctionBuyer.Button();
        button.title = screen.getTitle().getString();
        button.slot = slotId;
        button.itemId = Registries.ITEM.getId(slots.get(slotId).getStack().getItem()).toString();
        if (learning != null) {
            learnClick(client, screen, syncId, button);
        } else if (buyer.awaitingManualConfirm() && syncId != listingWindow
                && !AuctionBuyer.Button.normalize(button.title)
                .equals(AuctionBuyer.Button.normalize(listingTitle))) {
            AuctionLedger.Expected x = ledger.latestExpected();
            if (x != null && !x.sent) {
                x.tag = button; // kept only if the chat reply proves this click bought
                x.deadline = System.currentTimeMillis() + REPLY_MILLIS;
            }
        } else if (!saved.sellConfirm.set() && (seller.awaitingConfirm()
                || seller.verifying() && syncId == sellWindow)) {
            sellWindow = syncId;
            sellCandidate = button; // kept only if the item then leaves your inventory
            seller.manualConfirm(TickPhase.tickId());
        }
    }

    private void learnClick(MinecraftClient client, HandledScreen<?> screen, int syncId, AuctionBuyer.Button button) {
        if (learning == AuctionBuyer.Kind.CONFIRM) {
            List<AuctionDeals.SlotView> shown = view(client, screen).slots();
            AuctionDeals.SlotView clicked = shown.stream().filter(v -> v.slot() == button.slot).findFirst()
                    .orElse(null);
            if (clicked == null) {
                return;
            }
            // The confirm window is a second window, with its own title, that shows the
            // listed item next to the button. Page buttons and other clicks are ignored.
            boolean isConfirm = learnFrom != -1 && learnFrom != syncId
                    && !AuctionBuyer.Button.normalize(button.title).equals(AuctionBuyer.Button.normalize(learnTitle))
                    && !clicked.key().equals(learnKey)
                    && shown.stream().anyMatch(v -> v.slot() != button.slot && v.key().equals(learnKey));
            if (!isConfirm) {
                if (PriceText.price(clicked.lore()) > 0) {
                    learnFrom = syncId;
                    learnTitle = button.title;
                    learnKey = clicked.key();
                }
                return;
            }
            saved.confirm = button;
        } else {
            saved.refresh = button;
        }
        say("learned " + learning.name().toLowerCase(Locale.ROOT) + ": slot " + button.slot + " (" + button.itemId
                + ") in \"" + button.title + "\"");
        learning = null;
        save();
    }

    /** From the keyboard mixin: module binds are ignored while a window is open, these are not. */
    public void onKey(int keyCode) {
        if (keyCode <= 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof HandledScreen<?>) {
            if (keyCode == dumpKey.get()) {
                dump();
            } else if (keyCode == buyKey.get()) {
                buyer.answer(true);
            } else if (keyCode == skipKey.get()) {
                buyer.answer(false);
            }
        } else if (client.currentScreen == null && client.player != null && keyCode == ledgerKey.get()) {
            openLedger();
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /** Opens on the next tick: a command's chat screen closes after the command runs. */
    public void openLedger() {
        openRequested = true;
    }

    public void learn(AuctionBuyer.Kind kind) {
        if (broken) {
            say("fix " + FILE + " and run /zephyr ah reload first.");
            return;
        }
        learning = kind;
        learnFrom = -1;
        buyer.abort();
        say(kind == AuctionBuyer.Kind.CONFIRM
                ? "open the auction house, click a listing, then click confirm. That second click really buys."
                : "open the auction house and click its refresh button.");
    }

    public void cancelLearning() {
        learning = null;
    }

    /** Clears all remembered buttons. */
    public void forget() {
        if (broken) {
            say("fix " + FILE + " and run /zephyr ah reload first.");
            return;
        }
        saved.confirm = new AuctionBuyer.Button();
        saved.refresh = new AuctionBuyer.Button();
        saved.sellConfirm = new AuctionBuyer.Button();
        buyer.abort();
        save();
        say("forgot the confirm, sell and refresh buttons.");
    }

    /** Writes the open window (title, slots, lore) to a file. */
    public void dump() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            say("no window open. Bind dumpKey, open the window and press it.");
            return;
        }
        AuctionBuyer.ScreenView v = view(client, screen);
        StringBuilder out = new StringBuilder("title: " + v.title() + "\n");
        for (AuctionDeals.SlotView s : v.slots()) {
            out.append(s.slot()).append("  ").append(s.itemId()).append(" x").append(s.count()).append("  ")
                    .append(s.name()).append("  price=").append(PriceText.price(s.lore())).append('\n');
            for (String line : s.lore()) {
                out.append("      ").append(line).append('\n');
            }
        }
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve("auction-dump-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            say("wrote " + file);
        } catch (IOException e) {
            say("could not write the dump: " + e.getMessage());
        }
    }

    public void reload() {
        broken = false;
        try {
            if (!Files.exists(FILE)) {
                saved = new Saved();
                save();
            } else {
                Saved read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Saved.class);
                if (read == null || read.rules == null || read.auctionTitle == null || read.boughtMessage == null
                        || read.soldMessage == null || Arrays.asList(read.confirm, read.refresh, read.sellConfirm)
                        .stream().anyMatch(b -> b == null || b.title == null || b.itemId == null)) {
                    throw new IllegalStateException("missing fields");
                }
                saved = read;
            }
        } catch (IOException | RuntimeException e) {
            saved = new Saved();
            broken = true; // no buying without your blacklist and price caps
            System.err.println("[Zephyr] " + FILE + " not loaded, buying and selling are blocked: " + e);
        }
        boughtPattern = AuctionChat.compile(saved.boughtMessage);
        soldPattern = AuctionChat.compile(saved.soldMessage);
    }

    private void save() {
        if (broken) {
            return;
        }
        write(FILE, GSON.toJson(saved));
    }

    private void loadLedger() {
        if (!Files.exists(LEDGER_FILE)) {
            return;
        }
        try {
            Book book = GSON.fromJson(Files.readString(LEDGER_FILE, StandardCharsets.UTF_8), Book.class);
            ledger.load(book.entries);
        } catch (IOException | RuntimeException e) {
            ledgerBroken = true; // keep the file for you to repair; this session is not saved
            System.err.println("[Zephyr] " + LEDGER_FILE + " not loaded and will not be overwritten: " + e);
        }
    }

    private void saveLedger() {
        if (ledgerBroken) {
            return;
        }
        Book book = new Book();
        book.entries = ledger.entries();
        write(LEDGER_FILE, GSON.toJson(book));
    }

    /** Writes beside the file and then moves over it, so a crash cannot leave half a file. */
    private static void write(Path file, String text) {
        try {
            Files.createDirectories(DIR);
            Path next = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(next, text, StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Zephyr] could not save " + file + ": " + e);
        }
    }

    public void resetSession() {
        buyer.resetSession();
        say("session buy count, budget use and price history cleared. The bought / sold list stays.");
    }

    public String summary() {
        return (broken ? "CANNOT READ " + FILE + " - blocked. " : "")
                + (ledgerBroken ? "CANNOT READ " + LEDGER_FILE + " - not saving it. " : "")
                + buyer.buys() + " buys, " + PriceText.shorten(buyer.committed()) + " of budget used, confirm "
                + describe(saved.confirm) + ", sell " + describe(saved.sellConfirm) + ", refresh "
                + describe(saved.refresh) + ", sold message " + (soldPattern == null ? "unset" : "set") + " - "
                + (seller.busy() ? seller.status() : buyer.status());
    }

    private static String describe(AuctionBuyer.Button b) {
        return b.set() ? "slot " + b.slot : "unset";
    }

    static void say(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Zephyr] AuctionFlipper: " + message), false);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + buyer.buys() + " buys " + PriceText.shorten(buyer.committed())
                + (learning != null ? " · learning" : "");
    }

    // ------------------------------------------------------------------
    // Drawing over the auction window
    // ------------------------------------------------------------------

    private void draw(DrawContext context, HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isEnabled() || client.player == null
                || !AuctionBuyer.isAuctionTitle(screen.getTitle().getString(), saved.auctionTitle)
                || saved.confirm.set() && AuctionBuyer.Button.normalize(saved.confirm.title)
                .equals(AuctionBuyer.Button.normalize(screen.getTitle().getString()))) {
            return;
        }
        HandledScreenAccessor at = (HandledScreenAccessor) screen;
        var slots = screen.getScreenHandler().slots;
        AuctionDeals.Deal asked = buyer.question();
        List<AuctionDeals.Deal> marks = buyer.marks();
        if (highlight.get()) {
            // Worst first, so the best deal's label and gold box end up on top.
            for (int i = marks.size() - 1; i >= 0; i--) {
                AuctionDeals.Deal deal = marks.get(i);
                if (deal.view().slot() >= slots.size()) {
                    continue;
                }
                Slot slot = slots.get(deal.view().slot());
                int color = i == 0 ? 0xFFFFC830 : 0xFF30E070;
                box(context, at.zephyr$x() + slot.x, at.zephyr$y() + slot.y, color);
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(at.zephyr$x() + slot.x, at.zephyr$y() + slot.y - 4);
                context.getMatrices().scale(0.6f, 0.6f);
                context.drawText(client.textRenderer, "+" + PriceText.shorten(deal.profit()), 0, 0, color, true);
                context.getMatrices().popMatrix();
            }
        }
        if (asked == null) {
            context.drawText(client.textRenderer, "Zephyr: " + buyer.status(), at.zephyr$x(), at.zephyr$y() - 11,
                    0xFFFFFFFF, true);
            return;
        }
        if (asked.view().slot() < slots.size()) {
            Slot slot = slots.get(asked.view().slot());
            box(context, at.zephyr$x() + slot.x, at.zephyr$y() + slot.y, 0xFF40C8FF);
        }
        String first = "Buy " + asked.view().count() + "x " + asked.view().name() + " for $"
                + PriceText.shorten(asked.price()) + "?  (est. +" + PriceText.shorten(asked.profit()) + ")";
        String second = "[" + buyKey.display() + "] buy    [" + skipKey.display() + "] skip";
        int width = Math.max(client.textRenderer.getWidth(first), client.textRenderer.getWidth(second)) + 10;
        int x = at.zephyr$x();
        int y = Math.max(2, at.zephyr$y() - 28);
        context.fill(x, y, x + width, y + 26, 0xF0101018);
        context.fill(x, y, x + 2, y + 26, 0xFF40C8FF);
        context.drawText(client.textRenderer, first, x + 6, y + 4, 0xFFFFFFFF, true);
        context.drawText(client.textRenderer, second, x + 6, y + 15, 0xFF40C8FF, true);
    }

    private static void box(DrawContext context, int x, int y, int color) {
        context.fill(x, y, x + 16, y + 16, color & 0x55FFFFFF);
        context.fill(x - 1, y - 1, x + 17, y, color);
        context.fill(x - 1, y + 16, x + 17, y + 17, color);
        context.fill(x - 1, y, x, y + 16, color);
        context.fill(x + 16, y, x + 17, y + 16, color);
    }

    private static List<String> lore(ItemStack stack) {
        var component = stack.get(DataComponentTypes.LORE);
        if (component == null) {
            return List.of();
        }
        return component.lines().stream().map(Text::getString).toList();
    }
}
