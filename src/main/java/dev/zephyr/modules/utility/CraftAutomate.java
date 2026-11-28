package dev.zephyr.modules.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CraftAutomate - buys an ingredient on /ah (by default spruce logs), crafts it in your own
 * 2x2 grid (planks, then crafting tables) and sells the results one at a time, each just
 * under the cheapest listing found with {@code /ah <item>}. It buys only when that current
 * price pays for the current cheapest ingredient plus your minimum profit. Recipes are in
 * config/zephyr/craft.json. Budget, fee, buttons and the bought / sold list are shared with
 * AuctionFlipper (see {@link CraftRoutine}).
 */
public final class CraftAutomate extends Module {

    /** Contents of craft.json. */
    private static final class Saved {
        List<CraftRecipe> recipes = new ArrayList<>(List.of(CraftRecipe.craftingTable()));
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Path.of("config", "zephyr", "craft.json");

    private final BooleanSetting askFirst = bool("askFirst", "Ask before buying", true);
    private final NumberSetting maxPrice = num("maxPrice", "Max price (k, 0 = any)", 0, 1_000_000, 1, 0);
    private final NumberSetting minGain = num("minGain", "Min profit %", 0, 500, 1, 10);

    private final AuctionFlipper flipper;
    private List<CraftRecipe> recipes = List.of();

    public CraftAutomate(AuctionFlipper flipper) {
        super("CraftAutomate", ModuleCategory.DONUT, 0);
        this.flipper = flipper;
        reload();
        flipper.attach(this);
    }

    boolean askFirst() {
        return askFirst.get();
    }

    double maxPrice() {
        return maxPrice.get();
    }

    double minGain() {
        return minGain.get();
    }

    List<CraftRecipe> recipes() {
        return recipes;
    }

    /** A file that cannot be read leaves no recipes and is not overwritten. */
    void reload() {
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, GSON.toJson(new Saved()), StandardCharsets.UTF_8);
            }
            Saved read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Saved.class);
            if (read == null || read.recipes == null || read.recipes.stream().anyMatch(r -> r == null)) {
                throw new IllegalStateException("missing recipes");
            }
            recipes = read.recipes;
        } catch (IOException | RuntimeException e) {
            recipes = List.of();
            System.err.println("[Zephyr] " + FILE + " not loaded: " + e);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        flipper.run(client);
    }

    @Override
    protected void onDisable() {
        flipper.craftStopped("CraftAutomate switched off");
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        flipper.craftStopped("left the world");
    }
}
