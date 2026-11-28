package dev.zephyr.modules.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
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
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * AuctionFlipper - flips items on DonutSMP's /ah. With Auto on it browses page by page,
 * searches {@code /ah <item>} to check each promising item's whole market, asks you, buys,
 * searches again and relists with {@code /ah sell} just under the cheapest, and only when
 * that makes your minimum profit (see {@link FlipRoutine}). With Auto off it marks deals
 * in the window you open and asks before buying (see {@link AuctionBuyer}). It also runs
 * the routine of {@link CraftAutomate}; both share the list of what was bought and sold.
 * Confirm buttons are remembered from your own clicks and stored with the item rules in
 * config/zephyr/auction.json. Money settings are in thousands.
 */
public final class AuctionFlipper extends Module implements AuctionCore.Env {

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
        /** Unset: the command that prints your money, without the slash. */
        String balanceCommand = "";
        /** Unset: the line it prints, with {price} where the amount is. */
        String balanceMessage = "";
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

    // Basic settings (see SettingLayout); everything else is in the Advanced section.
    private final BooleanSetting auto = bool("auto", "Auto flip", true);
    private final BooleanSetting askFirst = bool("askFirst", "Ask before buying", true);
    private final NumberSetting maxPrice = num("maxPrice", "Max price (k, 0 = any)", 0, 1_000_000, 1, 0);
    private final NumberSetting budget = num("budget", "Budget (k, 0 = any)", 0, 10_000_000, 1, 0);
    private final NumberSetting sellMargin = num("sellMargin", "Min profit %", 0, 500, 1, 10);

    private final BooleanSetting autoBuy = bool("autoBuy", "Allow buying", true);
    private final BooleanSetting autoSell = bool("autoSell", "Sell after buying (Auto off)", true);
    private final NumberSetting maxBuys = num("maxBuys", "Max buys per session", 1, 500, 1, 10);
    private final NumberSetting keepMoney = num("keepMoney", "Money to keep (k)", 0, 10_000_000, 1, 0);
    private final KeySetting buyKey = s(new KeySetting("buyKey", "Yes, buy", 89));
    private final KeySetting skipKey = s(new KeySetting("skipKey", "No, skip", 78));
    private final KeySetting ledgerKey = s(new KeySetting("ledgerKey", "Open bought / sold", 0));
    private final NumberSetting promptTime = num("promptTime", "Question timeout (seconds)", 3, 60, 1, 10);
    private final NumberSetting undercut = num("undercut", "List under the cheapest by $", 1, 100000, 1, 1);
    private final NumberSetting priceDecimals = num("priceDecimals", "Decimals in \"1.1K\" prices", 1, 3, 1, 1);
    private final NumberSetting browsePages = num("browsePages", "Pages to browse", 1, 50, 1, 3);
    private final NumberSetting checkPages = num("checkPages", "Max pages per price check", 1, 50, 1, 10);
    private final BooleanSetting cheapestFirst = bool("cheapestFirst", "Search is sorted cheapest first", false);
    private final NumberSetting restTime = num("restTime", "Auto refresh pause (seconds)", 2, 300, 1, 10);
    private final NumberSetting recheck = num("recheck", "Check an item again after (seconds)", 10, 3600, 10, 120);
    private final NumberSetting commandGap = num("commandGap", "Ticks between commands", 5, 200, 5, 20);
    private final NumberSetting nextSlot = num("nextSlot", "Next page button slot", 0, 89, 1, 53);
    private final NumberSetting maxHeld = num("maxHeld", "Stop buying at unsold items", 1, 36, 1, 5);
    private final BooleanSetting verifyListing = bool("verifyListing", "Search again after listing", true);
    private final ModeSetting rank = s(new ModeSetting("rank", "Best deal means",
            names(AuctionDeals.Rank.values()), AuctionDeals.Rank.PROFIT.name()));
    private final ModeSetting reference = s(new ModeSetting("reference", "Deal reference price",
            names(AuctionDeals.Reference.values()), AuctionDeals.Reference.MEDIAN.name()));
    private final NumberSetting discount = num("discount", "Min % below reference", 0, 95, 1, 30);
    private final NumberSetting minProfit = num("minProfit", "Min margin (thousands)", 0, 10000, 1, 0);
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
    private final NumberSetting settle = num("settle", "Page settle ticks", 0, 40, 1, 10);
    private final NumberSetting buyDelay = num("buyDelay", "Extra delay before buy (ticks)", 0, 100, 1, 0);
    private final NumberSetting confirmDelay = num("confirmDelay", "Delay before confirm (ticks)", 0, 40, 1, 4);
    private final NumberSetting timeout = num("timeout", "Window timeout (ticks)", 10, 200, 5, 60);
    private final NumberSetting cooldown = num("cooldown", "Pause between buys (ticks)", 1, 600, 1, 20);
    private final NumberSetting retry = num("retry", "Retry same listing after (seconds)", 5, 3600, 5, 60);
    private final NumberSetting refreshEvery = num("refreshEvery", "Refresh interval (ticks)", 10, 1200, 5, 40);
    private final NumberSetting sellSlot = num("sellSlot", "Hotbar slot for selling", 1, 9, 1, 9);
    private final BooleanSetting sellCheckItem = bool("sellCheckItem", "Sell window must show item", true);
    private final BooleanSetting highlight = bool("highlight", "Highlight deals", true);
    private final BooleanSetting logChat = bool("logChat", "Log chat lines with $", false);
    private final KeySetting dumpKey = s(new KeySetting("dumpKey", "Dump open window", 0));

    private final AuctionClient game = new AuctionClient();
    private final AuctionCore core;
    private final AuctionBuyer buyer;
    private final AuctionLedger ledger;
    /** Set by {@link CraftAutomate} when it is created. */
    private CraftAutomate crafter;
    private Saved saved = new Saved();
    /** The JSON file exists but cannot be used: no buying or selling, and it is not overwritten. */
    private boolean broken;
    private boolean ledgerBroken;
    private AuctionBuyer.Kind learning;
    /** While learning confirm: the listing you clicked first (window id, title, item). */
    private int learnFrom = -1;
    private String learnTitle = "";
    private String learnKey = "";
    private boolean openRequested;
    private String reported = "";
    private long ranTick = -1;

    public AuctionFlipper() {
        super("AuctionFlipper", ModuleCategory.DONUT, 0);
        core = new AuctionCore(game, this, this::limits, () -> setup(askFirst.get(), sellMargin.get(),
                maxPrice.get()), this::craftSetup, this::sellOptions, this::filter, () -> saved.rules,
                () -> crafter == null ? List.of() : crafter.recipes());
        buyer = core.buyer;
        ledger = core.ledger;
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

    void attach(CraftAutomate module) {
        crafter = module;
    }

    @Override
    protected void onDisable() {
        // Totals stay: toggling must not reset the budget. A running CraftAutomate goes on.
        if (crafter == null || !crafter.isEnabled()) {
            core.stop("AuctionFlipper switched off");
            saveLedger();
        }
    }

    void craftStopped(String why) {
        if (!isEnabled()) {
            core.stop(why);
            saveLedger();
        }
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        core.stop("left the world");
        saveLedger();
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        run(client);
    }

    /** Once per tick, from whichever of the two auction modules is on. */
    void run(MinecraftClient client) {
        long now = TickPhase.tickId();
        if (now == ranTick) {
            return;
        }
        if (client.player == null || client.interactionManager == null) {
            buyer.abort();
            return;
        }
        // Teaching a button is your click; the modules stay out of the way meanwhile.
        if (learning != null || !TickPhase.actionWindowOpen()) {
            return;
        }
        ranTick = now;
        if (broken) {
            if (isEnabled()) {
                assist(client, now); // marks deals; buying stays blocked
            }
            return;
        }
        boolean flipping = isEnabled() && auto.get();
        boolean crafting = crafter != null && crafter.isEnabled();
        core.tick(now, flipping, crafting);
        if (core.working() || flipping || crafting) {
            buyer.abort();
        } else if (isEnabled()) {
            assist(client, now);
        }
    }

    /** Auto off: mark deals in the window you opened, ask, and buy. */
    private void assist(MinecraftClient client, long now) {
        HandledScreen<?> screen = AuctionClient.serverScreen();
        if (screen != null && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            return; // decided before anything runs, so nothing counts a click that is not sent
        }
        AuctionBuyer.ScreenView view = screen == null ? null : view(client, screen);
        AuctionBuyer.Click click = buyer.tick(view, now, options(), filter(), saved.rules, core.confirm,
                saved.refresh);
        if (click != null && screen != null) {
            if (click.kind() == AuctionBuyer.Kind.LISTING) {
                core.listingClicked(screen.getScreenHandler().syncId, view.title());
                AuctionDeals.Deal opening = buyer.opening();
                if (opening != null && !core.confirm.set()) {
                    core.expect(opening.view(), opening.price(), FlipRoutine.OWNER, false,
                            promptTime.asInt() * 20 + 160);
                }
            }
            if (click.buys() != null) {
                core.expect(click.buys().view(), click.buys().price(), FlipRoutine.OWNER, true, 160);
            }
            game.click(click.slot(), 0, AuctionGame.Press.PICKUP);
        }
        String status = buyer.status();
        if (!status.equals(reported)) {
            reported = status;
            if (status.startsWith("sent buy") || status.contains("not confirmed") || status.startsWith("click confirm")
                    || status.startsWith("confirm button")) {
                say(status);
            }
        }
    }

    /** Runs whether or not the module is on: expired buys and the ledger screen. */
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

    private void onChat(String line) {
        if (logChat.get() && line.contains("$")) {
            try {
                Files.createDirectories(DIR);
                Files.writeString(CHAT_LOG, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[Zephyr] chat log: " + e);
            }
        }
        boolean assisting = isEnabled() && !auto.get() && !core.working();
        AuctionLedger.Purchase purchase = core.chat(line);
        // In auto mode the routine sells what it bought itself.
        if (purchase != null && purchase.expected() != null && assisting && autoSell.get()) {
            requestSale(purchase.entry().id);
        }
    }

    // ------------------------------------------------------------------
    // Bought / sold screen
    // ------------------------------------------------------------------

    public AuctionLedger ledger() {
        return ledger;
    }

    public boolean soldMessageSet() {
        return core.soldPattern != null;
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
        core.unqueueSale(id);
        AuctionSeller.Job job = core.seller.job();
        if (!(core.seller.busy() && job.entryId() == id)) {
            ledger.remove(id);
            saveLedger();
        }
    }

    /**
     * Queues a bought stack: the market is checked, then it is listed as the cheapest if
     * that makes the minimum profit.
     *
     * @return why it was not queued, or null
     */
    public String requestSale(long id) {
        AuctionLedger.Entry e = ledger.byId(id);
        if (broken) {
            return "fix " + FILE + " first";
        }
        if (!isEnabled() && !(crafter != null && crafter.isEnabled())) {
            return "switch AuctionFlipper on to sell";
        }
        if (e == null || e.status == AuctionLedger.Status.SOLD || e.status == AuctionLedger.Status.LISTED
                || e.status == AuctionLedger.Status.SELLING) {
            return "that entry cannot be sold";
        }
        core.queueSale(id);
        return null;
    }

    // ------------------------------------------------------------------
    // Settings snapshots
    // ------------------------------------------------------------------

    private AuctionSetup setup(boolean ask, double gainPercent, double maxThousands) {
        AuctionSetup o = new AuctionSetup();
        o.askFirst = ask;
        o.minGain = gainPercent / 100.0;
        o.maxPrice = maxThousands * 1000.0;
        o.fee = tax.get() / 100.0;
        o.undercut = undercut.get();
        o.priceDecimals = priceDecimals.asInt();
        o.listingSlots = listingSlots.asInt() == 0 ? nextSlot.asInt() : listingSlots.asInt();
        o.nextSlot = nextSlot.asInt();
        o.browsePages = browsePages.asInt();
        o.checkPages = checkPages.asInt();
        o.cheapestFirst = cheapestFirst.get();
        o.settleTicks = Math.max(2, settle.asInt());
        o.windowTicks = timeout.asInt();
        o.commandGapTicks = commandGap.asInt();
        o.promptTicks = promptTime.asInt() * 20;
        o.restTicks = restTime.asInt() * 20;
        o.recheckTicks = recheck.asInt() * 20;
        o.confirmDelayTicks = confirmDelay.asInt();
        o.confirmPrice = confirmPrice.get();
        o.oneClick = oneClick.get();
        o.maxHeld = maxHeld.asInt();
        o.verifyListing = verifyListing.get();
        return o;
    }

    private AuctionSetup craftSetup() {
        return crafter == null ? setup(true, sellMargin.get(), maxPrice.get())
                : setup(crafter.askFirst(), crafter.minGain(), crafter.maxPrice());
    }

    private AuctionCore.Limits limits() {
        AuctionCore.Limits l = new AuctionCore.Limits();
        l.allowed = autoBuy.get() && !broken;
        l.budget = budget.get() * 1000.0;
        l.maxBuys = maxBuys.asInt();
        l.keepMoney = keepMoney.get() * 1000.0;
        return l;
    }

    private AuctionSeller.Options sellOptions() {
        AuctionSeller.Options o = new AuctionSeller.Options();
        o.sellSlot = sellSlot.asInt() - 1;
        o.checkItem = sellCheckItem.get();
        return o;
    }

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

    private static AuctionBuyer.ScreenView view(MinecraftClient client, HandledScreen<?> screen) {
        return new AuctionBuyer.ScreenView(screen.getTitle().getString(), AuctionClient.slots(client, screen));
    }

    // ------------------------------------------------------------------
    // Your clicks and keys
    // ------------------------------------------------------------------

    /** From the mixin, before any slot click is sent. */
    public void onSlotClick(int syncId, int slotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (game.clicking() || client.player == null || !(client.currentScreen instanceof HandledScreen<?> screen)
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
        } else {
            core.clicked(syncId, button);
        }
    }

    private void learnClick(MinecraftClient client, HandledScreen<?> screen, int syncId, AuctionBuyer.Button button) {
        if (learning == AuctionBuyer.Kind.CONFIRM) {
            List<AuctionDeals.SlotView> shown = AuctionClient.slots(client, screen);
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
            core.confirm = button;
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
                core.answer(true);
            } else if (keyCode == skipKey.get()) {
                buyer.answer(false);
                core.answer(false);
            } else if (core.working()) {
                // A module's own key switches it off even while its window is open.
                if (isEnabled() && keyMatches(keyCode)) {
                    setEnabled(false);
                }
                if (crafter != null && crafter.isEnabled() && crafter.keyMatches(keyCode)) {
                    crafter.setEnabled(false);
                }
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
        core.stop("learning a button");
        learning = kind;
        learnFrom = -1;
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
        core.confirm = new AuctionBuyer.Button();
        saved.refresh = new AuctionBuyer.Button();
        core.sellConfirm = new AuctionBuyer.Button();
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
        core.stop("reloading auction.json");
        broken = false;
        try {
            if (!Files.exists(FILE)) {
                saved = new Saved();
                save();
            } else {
                Saved read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Saved.class);
                if (read == null || read.rules == null || read.auctionTitle == null || read.boughtMessage == null
                        || read.soldMessage == null || read.balanceCommand == null || read.balanceMessage == null
                        || Arrays.asList(read.confirm, read.refresh, read.sellConfirm)
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
        core.confirm = saved.confirm;
        core.sellConfirm = saved.sellConfirm;
        core.boughtPattern = AuctionChat.compile(saved.boughtMessage);
        core.soldPattern = AuctionChat.compile(saved.soldMessage);
        core.balancePattern = AuctionChat.compileAmount(saved.balanceMessage);
        core.balanceCommand = saved.balanceCommand.replaceFirst("^/", "").trim();
        if (crafter != null) {
            crafter.reload();
        }
    }

    private void save() {
        if (broken) {
            return;
        }
        saved.confirm = core.confirm;
        saved.sellConfirm = core.sellConfirm;
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

    @Override
    public void saveLedger() {
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
        core.resetSession();
        say("session buy count, budget use and price history cleared. The bought / sold list stays.");
    }

    public String summary() {
        return (broken ? "CANNOT READ " + FILE + " - blocked. " : "")
                + (ledgerBroken ? "CANNOT READ " + LEDGER_FILE + " - not saving it. " : "")
                + buyer.buys() + " buys, " + PriceText.shorten(buyer.committed()) + " of budget used, money "
                + (core.balance() < 0 ? "unknown" : "$" + PriceText.shorten(core.balance())) + ", confirm "
                + describe(core.confirm) + ", sell " + describe(core.sellConfirm) + ", sold message "
                + (core.soldPattern == null ? "unset" : "set") + " - "
                + (core.status().isEmpty() ? buyer.status() : core.status());
    }

    private static String describe(AuctionBuyer.Button b) {
        return b.set() ? "slot " + b.slot : "unset";
    }

    // ------------------------------------------------------------------
    // AuctionCore.Env
    // ------------------------------------------------------------------

    @Override
    public long millis() {
        return System.currentTimeMillis();
    }

    @Override
    public long tickId() {
        return TickPhase.tickId();
    }

    @Override
    public void say(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Zephyr] AuctionFlipper: " + message), false);
        }
    }

    @Override
    public void saveButtons() {
        save();
    }

    @Override
    public String hudLabel() {
        return name + " · " + buyer.buys() + " buys " + PriceText.shorten(buyer.committed())
                + (core.balance() >= 0 ? " · $" + PriceText.shorten(core.balance()) : "")
                + (learning != null ? " · learning" : "");
    }

    // ------------------------------------------------------------------
    // Drawing over the auction window
    // ------------------------------------------------------------------

    private void draw(DrawContext context, HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean crafting = crafter != null && crafter.isEnabled();
        if (!isEnabled() && !crafting || client.player == null) {
            return;
        }
        HandledScreenAccessor at = (HandledScreenAccessor) screen;
        var slots = screen.getScreenHandler().slots;
        if (core.working()) {
            String question = core.question();
            if (question == null) {
                context.drawText(client.textRenderer, "Zephyr: " + core.status(), at.zephyr$x(),
                        at.zephyr$y() - 11, 0xFFFFFFFF, true);
            } else {
                ask(context, client, at, slots, core.questionSlot(), question);
            }
            return;
        }
        if (!isEnabled() || auto.get() || crafting) {
            return;
        }
        String title = screen.getTitle().getString();
        if (!AuctionBuyer.isAuctionTitle(title, saved.auctionTitle) || core.confirm.set()
                && AuctionBuyer.Button.normalize(core.confirm.title).equals(AuctionBuyer.Button.normalize(title))) {
            return;
        }
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
        ask(context, client, at, slots, asked.view().slot(), "Buy " + asked.view().count() + "x "
                + asked.view().name() + " for $" + PriceText.shorten(asked.price()) + "?  (est. +"
                + PriceText.shorten(asked.profit()) + ")");
    }

    private void ask(DrawContext context, MinecraftClient client, HandledScreenAccessor at, List<Slot> slots,
                     int index, String first) {
        if (index >= 0 && index < slots.size()) {
            Slot slot = slots.get(index);
            box(context, at.zephyr$x() + slot.x, at.zephyr$y() + slot.y, 0xFF40C8FF);
        }
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
}
