package dev.zephyr.modules.utility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The auction state both modules share: ledger, session limits, money, learned buttons,
 * chat replies, and which automatic routine has the screen. Free of game classes.
 */
public final class AuctionCore implements AuctionHost {

    /** Persistence, time and messages. */
    public interface Env {
        long millis();

        long tickId();

        void say(String message);

        void saveLedger();

        void saveButtons();
    }

    /** Spending rules from the settings. Money in server currency units. */
    public static final class Limits {
        public boolean allowed = true;
        public double budget;
        public int maxBuys = 10;
        public double keepMoney;
    }

    private static final long REPLY_MILLIS = 8_000;

    public final AuctionLedger ledger = new AuctionLedger();
    public final AuctionBuyer buyer = new AuctionBuyer();
    public final AuctionSeller seller = new AuctionSeller();
    private final AuctionDeals deals = new AuctionDeals();

    public AuctionBuyer.Button confirm = new AuctionBuyer.Button();
    public AuctionBuyer.Button sellConfirm = new AuctionBuyer.Button();
    public Pattern boughtPattern;
    public Pattern soldPattern;
    public Pattern balancePattern;
    public String balanceCommand = "";
    private double balance = -1;
    private int balanceReads;

    private final AuctionGame game;
    private final Env env;
    private final Supplier<Limits> limits;
    private final Supplier<AuctionSetup> flipSetup;
    private final Supplier<AuctionSetup> craftSetup;
    private final Supplier<AuctionSeller.Options> sellOptions;
    private final Supplier<AuctionDeals.Filter> filter;
    private final Supplier<AuctionDeals.Rules> rules;
    private final Supplier<List<CraftRecipe>> recipes;

    private final Queue<Long> saleQueue = new ArrayDeque<>();
    private FlipRoutine flip;
    private CraftRoutine craft;
    private SaleRoutine sales;
    private AuctionRoutine current;
    private int turn;
    private String reported = "";
    /** The window a listing was last clicked in. */
    private int listingWindow = -1;
    private String listingTitle = "";
    /** The sell button you clicked, kept until the item is seen to leave. */
    private AuctionBuyer.Button sellCandidate;
    private int sellWindow = -1;

    public AuctionCore(AuctionGame game, Env env, Supplier<Limits> limits, Supplier<AuctionSetup> flipSetup,
                       Supplier<AuctionSetup> craftSetup, Supplier<AuctionSeller.Options> sellOptions,
                       Supplier<AuctionDeals.Filter> filter, Supplier<AuctionDeals.Rules> rules,
                       Supplier<List<CraftRecipe>> recipes) {
        this.game = game;
        this.env = env;
        this.limits = limits;
        this.flipSetup = flipSetup;
        this.craftSetup = craftSetup;
        this.sellOptions = sellOptions;
        this.filter = filter;
        this.rules = rules;
        this.recipes = recipes;
        fresh();
    }

    private void fresh() {
        flip = new FlipRoutine(game, this, flipSetup);
        craft = new CraftRoutine(game, this, craftSetup, recipes);
        sales = new SaleRoutine(game, this, flipSetup, saleQueue);
        current = null;
    }

    // ------------------------------------------------------------------
    // Routines
    // ------------------------------------------------------------------

    /** Runs one tick of whichever routine has the screen; routines take turns when idle. */
    public void tick(long now, boolean flipOn, boolean craftOn) {
        if (working() && (current == flip && !flipOn || current == craft && !craftOn)) {
            halt("switched off");
        }
        List<AuctionRoutine> active = new ArrayList<>();
        if (!saleQueue.isEmpty() || current == sales && !sales.idle()) {
            active.add(sales);
        }
        if (flipOn) {
            active.add(flip);
        }
        if (craftOn) {
            active.add(craft);
        }
        if (active.isEmpty()) {
            current = null;
            return;
        }
        if (current == null || current.idle()) {
            current = active.get(Math.floorMod(turn++, active.size()));
        }
        current.tick(now);
        // Say why a routine is resting, once per reason.
        if (current.idle() && !current.quiet() && !current.status().equals(reported)) {
            reported = current.status();
            if (!reported.equals("starting")) {
                env.say(reported);
            }
        }
    }

    /** True while a routine is in the middle of something. */
    public boolean working() {
        return current != null && !current.idle();
    }

    public String status() {
        return current == null ? "" : current.status();
    }

    public String question() {
        return current == null ? null : current.question();
    }

    public int questionSlot() {
        return current == null ? -1 : current.questionSlot();
    }

    public void answer(boolean yes) {
        if (current != null) {
            current.answer(yes);
        }
    }

    public void queueSale(long id) {
        if (!saleQueue.contains(id)) {
            saleQueue.add(id);
        }
    }

    public void unqueueSale(long id) {
        saleQueue.remove(id);
    }

    /** Stops everything in progress; nothing half-done is counted as listed. */
    public void stop(String why) {
        if (working()) {
            halt(why);
        }
        saleQueue.clear();
        buyer.abort();
        fresh();
    }

    private void halt(String why) {
        current.stop();
        seller.abort(why);
        seller.poll();
        sellCandidate = null;
        for (AuctionLedger.Entry e : ledger.entries()) {
            if (e.status == AuctionLedger.Status.SELLING) {
                e.status = AuctionLedger.Status.BOUGHT;
            }
        }
        env.saveLedger();
        fresh();
    }

    // ------------------------------------------------------------------
    // Chat and your clicks
    // ------------------------------------------------------------------

    /** A server message. Returns the purchase it confirmed or recorded, if it was one. */
    public AuctionLedger.Purchase chat(String line) {
        rejected(line);
        AuctionChat.Hit money = AuctionChat.match(balancePattern, line);
        if (money != null) {
            balance = money.price();
            balanceReads++;
        }
        AuctionChat.Hit bought = AuctionChat.match(boughtPattern, line);
        if (bought != null) {
            AuctionLedger.Purchase purchase = ledger.bought(bought.item(), bought.count(), bought.price(),
                    bought.high(), env.millis());
            confirmed(purchase, bought.price());
            env.saveLedger();
            return purchase;
        }
        AuctionChat.Hit sold = AuctionChat.match(soldPattern, line);
        if (sold != null) {
            ledger.sold(sold.item(), sold.count(), sold.price(), env.millis());
            if (balance >= 0) {
                balance += sold.price();
            }
            env.saveLedger();
        }
        return null;
    }

    /** Names the command of ours that the server refused, if one was just sent. */
    private void rejected(String line) {
        // Wording from a DonutSMP chat screenshot dated 2026-11-27.
        if (line == null || !line.replaceAll("§.", "").trim().startsWith("This command does not exist")) {
            return;
        }
        String sent = game.recentCommand();
        if (sent == null) {
            return; // not ours: nothing was sent just before it
        }
        if (!balanceCommand.isEmpty() && sent.equals(balanceCommand)) {
            balanceCommand = "";
            env.say("the server does not know /" + sent + ": money reading is off until you fix "
                    + "balanceCommand in auction.json and reload");
        } else {
            env.say("the server said \"This command does not exist\" right after /" + sent);
        }
    }

    private void confirmed(AuctionLedger.Purchase purchase, double paid) {
        if (balance >= 0) {
            balance = Math.max(0, balance - paid);
        }
        AuctionLedger.Expected x = purchase.expected();
        if (x == null) {
            return; // bought by hand
        }
        if (x.sent) {
            buyer.adjust(x.price, paid);
        } else {
            // You confirmed it yourself, and the reply proves which button bought.
            if (current != null && current.awaitingManualBuy()) {
                buyer.count(paid);
            } else if (buyer.awaitingManualConfirm()) {
                buyer.manualBought(paid, env.tickId());
            } else {
                buyer.adjust(0, paid); // still counts against the budget
            }
            if (x.tag instanceof AuctionBuyer.Button button) {
                confirm = button;
                env.saveButtons();
                env.say("remembered the confirm button: slot " + button.slot + " in \"" + button.title + "\"");
            }
        }
        if (current != null) {
            current.bought(purchase);
        }
    }

    /** You clicked a filled slot of a server window. */
    public void clicked(int window, AuctionBuyer.Button button) {
        boolean buying = buyer.awaitingManualConfirm() || current != null && current.awaitingManualBuy();
        if (buying && window != listingWindow && !AuctionBuyer.Button.normalize(button.title)
                .equals(AuctionBuyer.Button.normalize(listingTitle))) {
            AuctionLedger.Expected x = ledger.latestExpected();
            if (x != null && !x.sent) {
                x.tag = button; // kept only if the chat reply proves this click bought
                x.deadline = env.millis() + REPLY_MILLIS;
            }
        } else if (!sellConfirm.set() && (seller.awaitingConfirm() || seller.verifying() && window == sellWindow)) {
            sellWindow = window;
            sellCandidate = button; // kept only if the item then leaves your inventory
            seller.manualConfirm(env.tickId());
        }
    }

    /** Money you have according to the last balance line; -1 when unknown. */
    public double balance() {
        return balance;
    }

    // ------------------------------------------------------------------
    // AuctionHost
    // ------------------------------------------------------------------

    @Override
    public AuctionLedger ledger() {
        return ledger;
    }

    @Override
    public long millis() {
        return env.millis();
    }

    @Override
    public AuctionBuyer.Button confirmButton() {
        return confirm;
    }

    @Override
    public AuctionBuyer.Button sellButton() {
        return sellConfirm;
    }

    @Override
    public AuctionSeller seller() {
        return seller;
    }

    @Override
    public AuctionSeller.Options sellOptions() {
        return sellOptions.get();
    }

    @Override
    public String limit(AuctionSetup setup, double price) {
        Limits l = limits.get();
        if (!l.allowed) {
            return "buying is switched off (allowBuying, or a broken auction.json)";
        }
        if (!setup.askFirst && (setup.maxPrice <= 0 || l.budget <= 0)) {
            return "buying without asking needs a max price and a budget";
        }
        if (setup.maxPrice > 0 && price > setup.maxPrice) {
            return "$" + PriceText.shorten(price) + " is over your max price";
        }
        if (l.budget > 0 && buyer.committed() + price > l.budget) {
            return "$" + PriceText.shorten(price) + " would go over your budget";
        }
        if (buyer.buys() >= l.maxBuys) {
            return "max buys for this session reached (/zephyr ah reset)";
        }
        if (balance >= 0 && price > balance - l.keepMoney) {
            return "not enough money for $" + PriceText.shorten(price) + " (you have $"
                    + PriceText.shorten(balance) + ")";
        }
        return null;
    }

    @Override
    public AuctionLedger.Expected expect(AuctionDeals.SlotView item, double price, String owner, boolean sent,
                                         int ticks) {
        AuctionLedger.Expected x = new AuctionLedger.Expected(item.name(), item.key(), item.count(), price, owner,
                sent, env.millis() + ticks * 50L);
        ledger.expect(x);
        return x;
    }

    @Override
    public void sent(double price) {
        buyer.count(price);
    }

    @Override
    public void listingClicked(AuctionGame.Window window) {
        listingClicked(window.id(), window.title());
    }

    public void listingClicked(int window, String title) {
        listingWindow = window;
        listingTitle = title;
    }

    public int listingWindow() {
        return listingWindow;
    }

    @Override
    public List<AuctionDeals.Deal> candidates(List<AuctionDeals.SlotView> slots, long now) {
        return deals.evaluate(slots, filter.get(), rules.get(), now);
    }

    @Override
    public String balanceCommand() {
        return balancePattern == null ? "" : balanceCommand;
    }

    @Override
    public int balanceReads() {
        return balanceReads;
    }

    @Override
    public void say(String message) {
        reported = message;
        env.say(message);
    }

    @Override
    public void saleEnded(boolean listed) {
        if (listed && sellCandidate != null) {
            sellConfirm = sellCandidate;
            env.saveButtons();
            env.say("remembered the sell confirm button: slot " + sellCandidate.slot);
        }
        sellCandidate = null;
        env.saveLedger();
    }

    @Override
    public void save() {
        env.saveLedger();
    }

    public void resetSession() {
        buyer.resetSession();
        deals.clear();
    }
}
