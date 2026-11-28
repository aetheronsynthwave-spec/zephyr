package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.mixin.accessor.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AuctionFlipper - while an auction house window is open (DonutSMP's /ah), marks the listings
 * priced well under what the same item has been listed for, with the likely margin.
 *
 * <p>It only reads the window and draws on it: it never clicks, buys or sells. Prices are
 * compared per item against the median of what you have browsed this session, so page through
 * a search first. Servers can change how listings are written; see {@link PriceText}.
 */
public final class AuctionFlipper extends Module {

    private final NumberSetting discount = num("discount", "Mark if below median by %", 5, 90, 5, 30);
    private final NumberSetting minProfit = num("minProfit", "Min margin (thousands)", 0, 10000, 5, 10);
    private final NumberSetting samples = num("samples", "Listings needed to judge", 3, 30, 1, 5);
    private final NumberSetting tax = num("tax", "Selling fee %", 0, 20, 0.5, 0);

    private static final int HISTORY = 120;
    /** Unit prices seen this session, per item and enchantments; one entry per distinct listing. */
    private final Map<String, Deque<Double>> history = new HashMap<>();
    private final Map<String, java.util.Set<String>> seen = new HashMap<>();

    private record Mark(int x, int y, String label) {
    }

    public AuctionFlipper() {
        super("AuctionFlipper", ModuleCategory.DONUT, 0);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?> handled && isAuction(screen)) {
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> draw(context, handled));
            }
        });
    }

    private static boolean isAuction(Screen screen) {
        String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("auction") || title.startsWith("ah ") || title.equals("ah");
    }

    @Override
    protected void onDisable() {
        history.clear();
        seen.clear();
    }

    private void draw(DrawContext context, HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isEnabled() || client.player == null) {
            return;
        }
        HandledScreenAccessor at = (HandledScreenAccessor) screen;
        List<Mark> marks = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == client.player.getInventory() || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            List<String> lore = lore(stack);
            double price = PriceText.price(lore);
            if (price <= 0) {
                continue;
            }
            String key = Registries.ITEM.getId(stack.getItem()) + "|" + stack.getEnchantments().toString();
            double unit = price / Math.max(1, stack.getCount());
            Deque<Double> prices = history.computeIfAbsent(key, k -> new ArrayDeque<>());
            // The same listing redrawn every frame counts once.
            if (seen.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(price + "x" + stack.getCount() + lore)) {
                prices.addLast(unit);
                if (prices.size() > HISTORY) {
                    prices.removeFirst();
                }
            }
            if (prices.size() < samples.asInt()) {
                continue;
            }
            double median = PriceText.median(new ArrayList<>(prices));
            double margin = median * stack.getCount() * (1.0 - tax.get() / 100.0) - price;
            if (unit <= median * (1.0 - discount.get() / 100.0) && margin >= minProfit.get() * 1000.0) {
                marks.add(new Mark(at.zephyr$x() + slot.x, at.zephyr$y() + slot.y, "+" + PriceText.shorten(margin)));
            }
        }
        for (Mark mark : marks) {
            context.fill(mark.x, mark.y, mark.x + 16, mark.y + 16, 0x5530E070);
            context.fill(mark.x - 1, mark.y - 1, mark.x + 17, mark.y, 0xFF30E070);
            context.fill(mark.x - 1, mark.y + 16, mark.x + 17, mark.y + 17, 0xFF30E070);
            context.fill(mark.x - 1, mark.y, mark.x, mark.y + 16, 0xFF30E070);
            context.fill(mark.x + 16, mark.y, mark.x + 17, mark.y + 16, 0xFF30E070);
        }
        // Labels after every box, so a neighbour's highlight does not cover them.
        for (Mark mark : marks) {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(mark.x, mark.y - 4);
            context.getMatrices().scale(0.6f, 0.6f);
            context.drawText(client.textRenderer, mark.label, 0, 0, 0xFF7CFFA8, true);
            context.getMatrices().popMatrix();
        }
        context.drawText(client.textRenderer, "Zephyr: " + marks.size() + " bargain" + (marks.size() == 1 ? "" : "s"),
                at.zephyr$x(), at.zephyr$y() - 11, 0xFFFFFFFF, true);
    }

    private static List<String> lore(ItemStack stack) {
        var component = stack.get(DataComponentTypes.LORE);
        if (component == null) {
            return List.of();
        }
        return component.lines().stream().map(Text::getString).toList();
    }
}
