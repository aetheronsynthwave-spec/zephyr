package dev.zephyr.modules.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.KeySetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.mixin.accessor.HandledScreenAccessor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AuctionFlipper - while an auction house window is open (DonutSMP's /ah), finds listings
 * priced under what the same item has been listed for and buys the best one that fits your
 * limits (see {@link AuctionBuyer}). Button positions are learned from your own clicks and
 * stored with the item rules in config/zephyr/auction.json. Money settings are in thousands.
 * Servers can change how listings are written; see {@link PriceText}.
 */
public final class AuctionFlipper extends Module {

    /** Contents of the JSON file. */
    private static final class Saved {
        String auctionTitle = "";
        AuctionBuyer.Button confirm = new AuctionBuyer.Button();
        AuctionBuyer.Button refresh = new AuctionBuyer.Button();
        AuctionDeals.Rules rules = new AuctionDeals.Rules();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = Path.of("config", "zephyr");
    private static final Path FILE = DIR.resolve("auction.json");

    private final BooleanSetting autoBuy = bool("autoBuy", "Buy the best deal", true);
    private final NumberSetting maxPrice = num("maxPrice", "Max price (thousands, 0 = unset)", 0, 1_000_000, 1, 0);
    private final NumberSetting budget = num("budget", "Session budget (thousands, 0 = unset)", 0, 10_000_000, 1, 0);
    private final NumberSetting maxBuys = num("maxBuys", "Max buys per session", 1, 500, 1, 10);
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
    private final BooleanSetting highlight = bool("highlight", "Highlight deals", true);

    private final KeySetting dumpKey = s(new KeySetting("dumpKey", "Dump open window", 0));

    private final AuctionBuyer buyer = new AuctionBuyer();
    private Saved saved = new Saved();
    /** The JSON file exists but cannot be used: no buying, and it is not overwritten. */
    private boolean broken;
    private AuctionBuyer.Kind learning;
    /** While learning confirm: the listing you clicked first (window id, title, item). */
    private int learnFrom = -1;
    private String learnTitle = "";
    private String learnKey = "";
    private String reported = "";

    public AuctionFlipper() {
        super("AuctionFlipper", ModuleCategory.DONUT, 0);
        reload();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?> handled) {
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> draw(context, handled));
            }
        });
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
        buyer.abort(); // totals stay: toggling must not reset the budget
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        buyer.abort();
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            buyer.abort();
            return;
        }
        HandledScreen<?> screen = client.currentScreen instanceof HandledScreen<?> h
                && h.getScreenHandler() == client.player.currentScreenHandler ? h : null;
        // Teaching a button is your click; the buyer stays out of the way meanwhile.
        if (learning != null || !TickPhase.actionWindowOpen()
                || screen != null && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            return; // decided before the buyer runs, so it never counts a click that is not sent
        }
        AuctionBuyer.Click click = buyer.tick(screen == null ? null : view(client, screen), TickPhase.tickId(),
                options(), filter(), saved.rules, saved.confirm, saved.refresh);
        if (click != null && screen != null) {
            InventoryManager.clickSlot(client, click.slot(), 0, SlotActionType.PICKUP);
        }
        if (!buyer.status().equals(reported)) {
            reported = buyer.status();
            if (reported.startsWith("sent buy") || reported.contains("not confirmed")
                    || reported.startsWith("confirm ")) {
                say(reported);
            }
        }
    }

    private AuctionBuyer.Options options() {
        AuctionBuyer.Options o = new AuctionBuyer.Options();
        o.autoBuy = autoBuy.get() && !broken;
        o.oneClick = oneClick.get();
        o.confirmPrice = confirmPrice.get();
        o.refresh = refresh.get();
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
        return f;
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
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            String name = stack.getName().getString();
            String key = id + "|" + name + "|" + stack.getEnchantments() + "|"
                    + stack.get(DataComponentTypes.POTION_CONTENTS);
            // The position in the handler's list is the id a click addresses.
            out.add(new AuctionDeals.SlotView(i, id, key, name, stack.getCount(), lore(stack),
                    stack.get(DataComponentTypes.CONTAINER) != null));
        }
        return new AuctionBuyer.ScreenView(screen.getTitle().getString(), out);
    }

    // ------------------------------------------------------------------
    // Commands: learn, dump, reload, reset
    // ------------------------------------------------------------------

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

    /** From the mixin, before any slot click is sent. */
    public void onSlotClick(int syncId, int slotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (learning == null || client.player == null
                || !(client.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler().syncId != syncId) {
            return;
        }
        var slots = screen.getScreenHandler().slots;
        if (slotId < 0 || slotId >= slots.size() || !slots.get(slotId).hasStack()
                || slots.get(slotId).inventory == client.player.getInventory()) {
            return;
        }
        String title = screen.getTitle().getString();
        if (learning == AuctionBuyer.Kind.CONFIRM) {
            List<AuctionDeals.SlotView> shown = view(client, screen).slots();
            AuctionDeals.SlotView clicked = shown.stream().filter(v -> v.slot() == slotId).findFirst().orElse(null);
            if (clicked == null) {
                return;
            }
            // The confirm window is a second window, with its own title, that shows the
            // listed item next to the button. Page buttons and other clicks are ignored.
            boolean isConfirm = learnFrom != -1 && learnFrom != syncId && !title.equals(learnTitle)
                    && !clicked.key().equals(learnKey)
                    && shown.stream().anyMatch(v -> v.slot() != slotId && v.key().equals(learnKey));
            if (!isConfirm) {
                if (PriceText.price(clicked.lore()) > 0) {
                    learnFrom = syncId;
                    learnTitle = title;
                    learnKey = clicked.key();
                }
                return;
            }
        }
        AuctionBuyer.Button button = new AuctionBuyer.Button();
        button.title = title;
        button.slot = slotId;
        button.itemId = Registries.ITEM.getId(slots.get(slotId).getStack().getItem()).toString();
        if (learning == AuctionBuyer.Kind.CONFIRM) {
            saved.confirm = button;
        } else {
            saved.refresh = button;
        }
        say("learned " + learning.name().toLowerCase(Locale.ROOT) + ": slot " + slotId + " (" + button.itemId
                + ") in \"" + button.title + "\"");
        learning = null;
        save();
    }

    public void cancelLearning() {
        learning = null;
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

    /** From the keyboard mixin: module binds are ignored while a window is open, this is not. */
    public void onKey(int keyCode) {
        if (keyCode > 0 && keyCode == dumpKey.get()
                && MinecraftClient.getInstance().currentScreen instanceof HandledScreen<?>) {
            dump();
        }
    }

    public void reload() {
        broken = false;
        try {
            if (!Files.exists(FILE)) {
                save();
                return;
            }
            Saved read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Saved.class);
            if (read == null || read.confirm == null || read.refresh == null || read.rules == null
                    || read.auctionTitle == null || read.confirm.title == null || read.confirm.itemId == null
                    || read.refresh.title == null || read.refresh.itemId == null) {
                throw new IllegalStateException("missing fields");
            }
            saved = read;
        } catch (IOException | RuntimeException e) {
            saved = new Saved();
            broken = true; // no buying without your blacklist and price caps
            System.err.println("[Zephyr] " + FILE + " not loaded, buying is blocked: " + e);
        }
    }

    private void save() {
        if (broken) {
            return;
        }
        try {
            Files.createDirectories(DIR);
            Files.writeString(FILE, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Zephyr] could not save " + FILE + ": " + e);
        }
    }

    public void resetSession() {
        buyer.resetSession();
        say("session totals and price history cleared.");
    }

    public String summary() {
        return (broken ? "CANNOT READ " + FILE + " - buying blocked. " : "") + buyer.buys() + " buys sent, " + PriceText.shorten(buyer.spent()) + " spent, confirm "
                + (saved.confirm.set() ? "slot " + saved.confirm.slot : "unset") + ", refresh "
                + (saved.refresh.set() ? "slot " + saved.refresh.slot : "unset") + " - " + buyer.status();
    }

    private static void say(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Zephyr] AuctionFlipper: " + message), false);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + buyer.buys() + " buys " + PriceText.shorten(buyer.spent())
                + (learning != null ? " · learning" : "");
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private void draw(DrawContext context, HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isEnabled() || !highlight.get() || client.player == null
                || !AuctionBuyer.isAuctionTitle(screen.getTitle().getString(), saved.auctionTitle)
                || saved.confirm.set() && saved.confirm.title.equals(screen.getTitle().getString())) {
            return;
        }
        HandledScreenAccessor at = (HandledScreenAccessor) screen;
        var slots = screen.getScreenHandler().slots;
        List<AuctionDeals.Deal> marks = buyer.marks();
        // Worst first, so the best deal's label and gold box end up on top.
        for (int i = marks.size() - 1; i >= 0; i--) {
            AuctionDeals.Deal deal = marks.get(i);
            if (deal.view().slot() >= slots.size()) {
                continue;
            }
            Slot slot = slots.get(deal.view().slot());
            int x = at.zephyr$x() + slot.x;
            int y = at.zephyr$y() + slot.y;
            int color = i == 0 ? 0xFFFFC830 : 0xFF30E070;
            context.fill(x, y, x + 16, y + 16, color & 0x55FFFFFF);
            context.fill(x - 1, y - 1, x + 17, y, color);
            context.fill(x - 1, y + 16, x + 17, y + 17, color);
            context.fill(x - 1, y, x, y + 16, color);
            context.fill(x + 16, y, x + 17, y + 16, color);
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(x, y - 4);
            context.getMatrices().scale(0.6f, 0.6f);
            context.drawText(client.textRenderer, "+" + PriceText.shorten(deal.profit()), 0, 0, color, true);
            context.getMatrices().popMatrix();
        }
        context.drawText(client.textRenderer, "Zephyr: " + buyer.status(), at.zephyr$x(), at.zephyr$y() - 11,
                0xFFFFFFFF, true);
    }

    private static List<String> lore(ItemStack stack) {
        var component = stack.get(DataComponentTypes.LORE);
        if (component == null) {
            return List.of();
        }
        return component.lines().stream().map(Text::getString).toList();
    }
}
