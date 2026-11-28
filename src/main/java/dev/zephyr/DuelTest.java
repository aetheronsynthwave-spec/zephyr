package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Two game clients over LAN: AutoCrystalPvP (host, "Crystal") against ElytraMaceBot (guest,
 * "Mace"). Dev only, -Dzephyr.duel=host|guest; run with run-duel.sh. Nobody touches a key:
 * the harness hands out kits, starts the bots and keeps score.
 */
public final class DuelTest {

    private static final String ROLE = System.getProperty("zephyr.duel", "");
    private static final Path DIR = Path.of(System.getProperty("zephyr.duel.dir", "."));
    private static final int PORT = 25591;
    private static final int ROUNDS = Integer.getInteger("zephyr.duel.rounds", 6);
    private static final int ROUND_TICKS = Integer.getInteger("zephyr.duel.ticks", 1800);
    private static final String CRYSTAL = "Crystal";
    private static final String MACE = "Mace";

    private static boolean creating;
    private static boolean lanOpen;
    private static int ticks;
    private static int round;
    private static int t = -200;
    private static int quitTicks;
    private static int restarts;
    private static boolean wasOn;
    private static final List<String> report = new ArrayList<>();
    private static final Map<String, Integer> maceStates = new TreeMap<>();
    private static final Map<String, Integer> crystalStates = new TreeMap<>();

    /** Per fighter, written on the server thread. */
    private static final class Score {
        volatile int pops;
        volatile int deaths;
        volatile double lost;
        float health = -1;
        int popStat = -1;
        boolean alive = true;
    }

    private static final Score crystal = new Score();
    private static final Score mace = new Score();
    private static int[] start;

    private DuelTest() {
    }

    public static void tick(MinecraftClient client) {
        if (ROLE.isEmpty()) {
            return;
        }
        try {
            client.options.pauseOnLostFocus = false;
            if (client.currentScreen instanceof GameMenuScreen) {
                client.setScreen(null);
            }
            if (client.world != null) {
                client.particleManager.clearParticles();
                client.gameRenderer.showFloatingItem(net.minecraft.item.ItemStack.EMPTY);
            }
            if (ROLE.equals("host")) {
                host(client);
            } else {
                guest(client);
            }
        } catch (Throwable e) {
            log("harness error: " + e);
            e.printStackTrace();
            write("control.txt", "quit");
            client.scheduleStop();
        }
    }

    private static void log(String s) {
        System.out.println("[DUEL] " + s);
        report.add(s);
    }

    private static void write(String name, String text) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(name), text);
        } catch (Exception e) {
            System.out.println("[DUEL] write failed: " + e);
        }
    }

    private static String read(String name) {
        try {
            return Files.readString(DIR.resolve(name)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void off() {
        // The bot first: it switches its own modules back when it stops.
        Module bot = ZephyrClient.modules().byName("AutoCrystalPvP");
        bot.setEnabled(false);
        for (Module m : ZephyrClient.modules().all()) {
            if (m.isEnabled()) {
                m.setEnabled(false);
            }
        }
    }

    private static void on(String name) {
        Module module = ZephyrClient.modules().byName(name);
        if (!module.isEnabled()) {
            module.setEnabled(true);
        }
    }

    private static void totem() {
        Module totem = ZephyrClient.modules().byName("AutoTotem");
        if (!totem.isEnabled()) {
            for (Setting<?> s : totem.settings()) {
                if (s instanceof ModeSetting mode && s.id().equals("mode")) {
                    mode.set("HOVER");
                }
            }
            totem.setEnabled(true);
        }
    }

    private static void guest(MinecraftClient client) {
        String control = read("control.txt");
        if (control.equals("quit")) {
            client.scheduleStop();
            return;
        }
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            return;
        }
        if (!self.isAlive()) {
            off();
            self.requestRespawn();
            client.setScreen(null);
            return;
        }
        if (!control.equals("fight")) {
            off();
            return;
        }
        totem();
        Module bot = ZephyrClient.modules().byName("ElytraMaceBot");
        if (wasOn && !bot.isEnabled()) {
            StringBuilder held = new StringBuilder("[DUEL] mace bot stopped itself; chest="
                    + self.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST) + " offhand=" + self.getOffHandStack());
            for (int i = 0; i < 36; i++) {
                if (!self.getInventory().getStack(i).isEmpty()) {
                    held.append(' ').append(i).append(':').append(self.getInventory().getStack(i));
                }
            }
            System.out.println(held);
        }
        wasOn = bot.isEnabled();
        if (!bot.isEnabled()) {
            // It stops itself when it cannot go on; count that and start it again.
            if (++ticks % 40 == 1) {
                bot.setEnabled(true);
                write("restarts.txt", String.valueOf(++restarts));
            }
        }
        write("mace-state.txt", bot.hudLabel());
    }

    private static void host(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            if (!creating && client.currentScreen instanceof TitleScreen title) {
                creating = true;
                LevelInfo info = new LevelInfo("duel-" + System.currentTimeMillis(), GameMode.SURVIVAL, false,
                        Difficulty.NORMAL, true, new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES),
                        DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(info.getLevelName(), info,
                        GeneratorOptions.createTestWorld(), WorldPresets::createTestOptions, title);
            }
            return;
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        if (!lanOpen) {
            if (++ticks < 60) {
                return;
            }
            server.setOnlineMode(false);
            lanOpen = server.openToLan(GameMode.SURVIVAL, true, PORT);
            log("LAN ready=" + lanOpen);
            write("control.txt", "wait");
            write("ready.txt", "1");
            for (String rule : new String[]{"natural_health_regeneration false", "spawn_mobs false",
                    "immediate_respawn true", "advance_time false", "send_command_feedback false",
                    "block_drops false"}) {
                cmd(server, "gamerule minecraft:" + rule);
            }
            cmd(server, "time set day");
            ticks = 0;
            return;
        }
        ClientPlayerEntity self = client.player;
        var other = client.world.getPlayers().stream().filter(p -> p != self).findFirst().orElse(null);
        sample(server);
        if (quitTicks > 0) {
            if (++quitTicks > 60) {
                client.scheduleStop();
            }
            return;
        }
        // Asked of the server: after a death they respawn too far off for us to see them.
        if (round == 0 && t < 0 && other == null || server.getPlayerManager().getPlayer(MACE) == null) {
            return;
        }
        if (!self.isAlive()) {
            self.requestRespawn();
            client.setScreen(null);
        }
        t++;
        if (t < -100) {
            return;
        }
        if (t == -100) {
            off();
            write("control.txt", "wait");
            arena(server);
            return;
        }
        if (t == -60) {
            kits(server);
            return;
        }
        if (t == -20) {
            cmd(server, "effect give @a minecraft:instant_health 1 5 true");
            return;
        }
        if (t < 0) {
            return;
        }
        if (t == 0) {
            round++;
            start = new int[]{crystal.pops, crystal.deaths, mace.pops, mace.deaths,
                    (int) crystal.lost, (int) mace.lost};
            write("control.txt", "fight");
        }
        totem();
        on("AutoCrystalPvP");
        if (t % 5 == 0) {
            crystalStates.merge(ZephyrClient.modules().byName("AutoCrystalPvP").hudLabel().replace("AutoCrystalPvP · ", ""),
                    1, Integer::sum);
            // Read once: the other client rewrites the file all the time.
            String[] parts = read("mace-state.txt").split(" · ");
            maceStates.merge(parts.length > 1 ? parts[1] : "?", 1, Integer::sum);
        }
        if (t == 100 || t == 400) {
            net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory,
                    "duel-r" + round + "-t" + t + ".png", client.getFramebuffer(), 1, message -> {
                    });
        }
        boolean died = crystal.deaths > start[1] || mace.deaths > start[3];
        if (died || t >= ROUND_TICKS) {
            String winner = crystal.deaths > start[1] && mace.deaths > start[3] ? "both died"
                    : crystal.deaths > start[1] ? "Mace wins" : mace.deaths > start[3] ? "Crystal wins" : "time";
            log(String.format(Locale.ROOT,
                    "round %d: %s after %.0fs | totems popped: Crystal %d, Mace %d", round, winner, t / 20.0,
                    crystal.pops - start[0], mace.pops - start[2]));
            off();
            write("control.txt", "wait");
            if (round >= ROUNDS) {
                log(String.format(Locale.ROOT, "TOTAL over %d rounds: Crystal popped %d totems and died %d times | Mace popped %d and died %d times",
                        round, crystal.pops, crystal.deaths, mace.pops, mace.deaths));
                log("Crystal bot time (x5 ticks): " + crystalStates);
                log("Mace bot time (x5 ticks): " + maceStates + ", restarts " + read("restarts.txt"));
                write("report.txt", String.join("\n", report) + "\n");
                write("control.txt", "quit");
                quitTicks = 1;
            }
            t = -160;
        }
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommandManager()
                .parseAndExecute(server.getCommandSource().withSilent(), command));
    }

    private static void sample(MinecraftServer server) {
        server.execute(() -> {
            score(server.getPlayerManager().getPlayer(CRYSTAL), crystal);
            score(server.getPlayerManager().getPlayer(MACE), mace);
        });
    }

    private static void score(ServerPlayerEntity player, Score score) {
        if (player == null) {
            return;
        }
        boolean fighting = t > 0 && read("control.txt").equals("fight");
        int stat = player.getStatHandler().getStat(Stats.USED.getOrCreateStat(Items.TOTEM_OF_UNDYING));
        float health = player.isAlive() ? player.getHealth() + player.getAbsorptionAmount() : 0;
        if (fighting) {
            if (score.popStat >= 0 && stat > score.popStat) {
                score.pops += stat - score.popStat;
            }
            if (score.health >= 0 && health < score.health) {
                score.lost += score.health - health;
            }
            if (score.alive && !player.isAlive()) {
                score.deaths++;
            }
        }
        score.popStat = stat;
        score.health = health;
        score.alive = player.isAlive();
    }

    /** A 48 x 48 floor of grass on stone on the superflat world, open sky above. */
    private static void arena(MinecraftServer server) {
        cmd(server, "fill -24 -61 -24 24 -61 24 minecraft:bedrock");
        cmd(server, "fill -24 -60 -24 24 -59 24 minecraft:stone");
        cmd(server, "fill -24 -58 -24 24 -58 24 minecraft:grass_block");
        for (int y = -57; y <= -44; y++) {
            cmd(server, "fill -24 " + y + " -24 24 " + y + " 24 minecraft:air");
        }
        cmd(server, "kill @e[type=!minecraft:player]");
        cmd(server, "tp " + CRYSTAL + " 0.5 -57 -6.5 0 0");
        cmd(server, "tp " + MACE + " 0.5 -57 6.5 180 0");
    }

    private static void kits(MinecraftServer server) {
        for (String who : new String[]{CRYSTAL, MACE}) {
            cmd(server, "gamemode survival " + who);
            cmd(server, "clear " + who);
            cmd(server, "effect clear " + who);
            cmd(server, "effect give " + who + " minecraft:saturation 5 5 true");
            for (String[] a : new String[][]{{"head", "helmet"}, {"chest", "chestplate"},
                    {"legs", "leggings"}, {"feet", "boots"}}) {
                String enchant = a[0].equals("legs") ? "blast_protection" : "protection";
                cmd(server, "item replace entity " + who + " armor." + a[0] + " with minecraft:netherite_" + a[1]
                        + "[enchantments={\"minecraft:" + enchant + "\":4},unbreakable={}]");
            }
            cmd(server, "item replace entity " + who + " weapon.offhand with minecraft:totem_of_undying");
            // The same six spare totems each, in the main inventory.
            for (int i = 0; i < 6; i++) {
                cmd(server, "item replace entity " + who + " inventory." + i + " with minecraft:totem_of_undying");
            }
        }
        String[] crystalBar = {"netherite_sword[enchantments={\"minecraft:knockback\":1}]", "ender_pearl 16",
                "golden_apple 64", "glowstone 64", "shield", "totem_of_undying", "obsidian 64", "end_crystal 64",
                "respawn_anchor 64"};
        String[] maceBar = {"mace[enchantments={\"minecraft:density\":5,\"minecraft:wind_burst\":1}]",
                "netherite_axe", "netherite_sword", "firework_rocket 64", "wind_charge 64",
                "netherite_chestplate[enchantments={\"minecraft:protection\":4}]", "totem_of_undying"};
        for (int i = 0; i < crystalBar.length; i++) {
            cmd(server, "item replace entity " + CRYSTAL + " hotbar." + i + " with minecraft:" + crystalBar[i]);
        }
        for (int i = 0; i < maceBar.length; i++) {
            cmd(server, "item replace entity " + MACE + " hotbar." + i + " with minecraft:" + maceBar[i]);
        }
        cmd(server, "item replace entity " + MACE + " armor.chest with minecraft:elytra[unbreakable={}]");
    }
}
