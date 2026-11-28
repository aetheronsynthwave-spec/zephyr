package dev.zephyr.modules.utility;

import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.inventory.InventoryManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** {@link AuctionGame} on the running client. Use only while a player exists. */
public final class AuctionClient implements AuctionGame {

    private boolean clicking;

    /** True while a click of ours is being sent, so it is not taken for yours. */
    public boolean clicking() {
        return clicking;
    }

    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    /** The open server window's screen, or null. */
    public static HandledScreen<?> serverScreen() {
        MinecraftClient c = client();
        return c.player != null && c.currentScreen instanceof HandledScreen<?> h
                && !(h instanceof InventoryScreen) && !(h instanceof CreativeInventoryScreen)
                && h.getScreenHandler() == c.player.currentScreenHandler ? h : null;
    }

    @Override
    public Window window() {
        HandledScreen<?> screen = serverScreen();
        if (screen == null) {
            return null;
        }
        return new Window(screen.getScreenHandler().syncId, screen.getTitle().getString(),
                slots(client(), screen));
    }

    /** The window's own slots (never your inventory), filled ones only. */
    public static List<AuctionDeals.SlotView> slots(MinecraftClient client, HandledScreen<?> screen) {
        List<AuctionDeals.SlotView> out = new ArrayList<>();
        var slots = screen.getScreenHandler().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.inventory != client.player.getInventory() && slot.hasStack()) {
                // The position in the handler's list is the id a click addresses.
                out.add(view(i, slot.getStack()));
            }
        }
        return out;
    }

    private static AuctionDeals.SlotView view(int slot, ItemStack stack) {
        var lore = stack.get(DataComponentTypes.LORE);
        List<String> lines = lore == null ? List.of() : lore.lines().stream().map(Text::getString).toList();
        return new AuctionDeals.SlotView(slot, Registries.ITEM.getId(stack.getItem()).toString(), key(stack),
                stack.getName().getString(), stack.getCount(), lines,
                stack.get(DataComponentTypes.CONTAINER) != null);
    }

    /** What makes two stacks the same thing to sell. */
    private static String key(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getName().getString() + "|"
                + stack.getEnchantments() + "|" + stack.get(DataComponentTypes.POTION_CONTENTS);
    }

    @Override
    public boolean free() {
        return client().currentScreen == null;
    }

    @Override
    public AuctionSeller.Inventory inventory() {
        var player = client().player;
        var slots = player.playerScreenHandler.slots;
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
        return new AuctionSeller.Inventory(stacks, empty, player.getInventory().getSelectedSlot());
    }

    @Override
    public AuctionDeals.SlotView own(int slot) {
        var slots = client().player.playerScreenHandler.slots;
        if (slot < 0 || slot >= slots.size() || slots.get(slot).getStack().isEmpty()) {
            return null;
        }
        return view(slot, slots.get(slot).getStack());
    }

    @Override
    public int cursor() {
        ItemStack stack = client().player.currentScreenHandler.getCursorStack();
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    @Override
    public void command(String command) {
        client().player.networkHandler.sendChatCommand(command);
    }

    @Override
    public void click(int slot, int button, Press press) {
        MinecraftClient c = client();
        SlotActionType type = switch (press) {
            case PICKUP -> SlotActionType.PICKUP;
            case QUICK_MOVE -> SlotActionType.QUICK_MOVE;
            case SWAP -> SlotActionType.SWAP;
            case QUICK_CRAFT -> SlotActionType.QUICK_CRAFT;
        };
        clicking = true;
        try {
            InventoryManager.clickSlot(c, slot, button, type);
        } finally {
            clicking = false;
        }
    }

    @Override
    public void select(int hotbar) {
        SwapManager.select(client(), hotbar);
    }

    @Override
    public void close() {
        MinecraftClient c = client();
        if (c.player == null) {
            return;
        }
        // Closing also returns anything on the cursor or in the crafting grid.
        if (c.currentScreen instanceof HandledScreen<?> || c.currentScreen == null && cursor() > 0) {
            c.player.closeHandledScreen();
        }
    }

    @Override
    public void openInventory() {
        client().setScreen(new InventoryScreen(client().player));
    }

    @Override
    public boolean inventoryOpen() {
        return client().currentScreen instanceof InventoryScreen;
    }

    @Override
    public Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            return null;
        }
        net.minecraft.item.Item found = Registries.ITEM.get(identifier);
        ItemStack stack = new ItemStack(found);
        return stack.isEmpty() ? null : new Item(id, stack.getName().getString(), key(stack));
    }
}
