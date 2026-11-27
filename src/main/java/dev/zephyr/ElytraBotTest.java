package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game scenario test for ElytraMaceBot (dev only, -Dzephyr.elytratest=1).
 * Builds a stone arena, spawns a real server-side husk as the practice dummy so mace
 * damage is real, and logs every bot state change, hit and bit of self damage.
 */
public final class ElytraBotTest {

    private static final String[] SCENARIOS = System.getProperty("zephyr.scen", "still,walk,strafe,dodge").split(",");
    private static final int SCENARIO_TICKS = 20 * Integer.getInteger("zephyr.secs", 40) + 60;
    private static final int REPS = Math.max(1, Integer.getInteger("zephyr.reps", 1));
    private static final int FLOOR = 150;
    private static final String WORLD = "ZephyrTest";

    private static boolean enabled;
    private static int t = -1;
    private static int scenario;
    private static int rep;
    private static int st;
    private static int idleTicks;
    private static boolean bootstrapped;

    /** Per-scenario-name totals across all repetitions, for the aggregate report lines. */
    private static final class Agg {
        int runs;
        int hits;
        int deaths;
        double selfDmg;
        double secSum;
        int secRuns;
    }

    private static final Map<String, Agg> AGGS = new LinkedHashMap<>();
    private static String lastState = "";
    private static final List<String> report = new ArrayList<>();

    private static volatile int serverHits;
    private static volatile int serverLast;
    private static int seenHits;
    private static volatile int shieldDownUntil;
    private static String scenarioName = "";
    private static double peakY;
    private static volatile float serverDamage;
    private static volatile float serverBest;
    private static volatile int serverFirst = -1;
    private static float lastSelfHealth = 20;
    private static float selfDamage;
    private static int deaths;
    private static int disabledAt;
    private static boolean wasDead;
    private static boolean wasCommitted;
    private static volatile boolean side;

    private ElytraBotTest() {
    }

    public static void init() {
        enabled = "1".equals(System.getProperty("zephyr.elytratest"));
        if (enabled) {
            System.out.println("[EBT] armed");
        }
    }

    /**
     * Creates the ZephyrTest superflat survival world (cheats on) when quickPlay could not
     * find it, so a fresh machine can run unattended. Waits ~100 ticks first so a world that
     * quickPlay is still loading is never raced.
     */
    private static void bootstrap(MinecraftClient client) {
        if (bootstrapped || client.world != null || client.getServer() != null) {
            return;
        }
        if (++idleTicks < 100) {
            return;
        }
        bootstrapped = true;
        try {
            if (client.getLevelStorage().levelExists(WORLD)) {
                // quickPlay did not get us in (screen: ...): open the existing world directly.
                System.out.println("[EBT] world " + WORLD + " exists, opening it (screen was "
                        + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getName()) + ")");
                client.createIntegratedServerLoader().start(WORLD, () -> client.setScreen(new TitleScreen()));
                return;
            }
            LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.EASY, true,
                    new GameRules(DataConfiguration.SAFE_MODE.enabledFeatures()), DataConfiguration.SAFE_MODE);
            GeneratorOptions opts = new GeneratorOptions(GeneratorOptions.getRandomSeed(), true, false);
            client.createIntegratedServerLoader().createAndStart(WORLD, info, opts,
                    lookup -> lookup.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                            .createDimensionsRegistryHolder(),
                    new TitleScreen());
            System.out.println("[EBT] created world " + WORLD + " (superflat, cheats on)");
        } catch (Exception e) {
            System.out.println("[EBT] world creation failed: " + e);
        }
    }

    private static void aggregate(String name, double secPerHit) {
        Agg a = AGGS.computeIfAbsent(name, k -> new Agg());
        a.runs++;
        a.hits += serverHits;
        a.deaths += deaths;
        a.selfDmg += selfDamage;
        if (secPerHit >= 0) {
            a.secSum += secPerHit;
            a.secRuns++;
        }
    }

    private static void writeReport() {
        try {
            Files.write(Path.of("ebt-report.txt"), report, StandardCharsets.UTF_8);
            System.out.println("[EBT] wrote ebt-report.txt");
        } catch (IOException e) {
            System.out.println("[EBT] could not write ebt-report.txt: " + e);
        }
    }

    private static void cmd(MinecraftClient client, String command) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> server.getCommandManager().parseAndExecute(
                server.getCommandSource().withSilent(), "execute as @a at @s run " + command));
    }

    private static Module bot() {
        return ZephyrClient.modules().byName("ElytraMaceBot");
    }

    public static void tick(MinecraftClient client) {
        if (!enabled) {
            return;
        }
        if (client.world == null && client.getServer() == null) {
            bootstrap(client);
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) {
            return;
        }
        t++;
        if (client.currentScreen != null && !(client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen)) {
            client.setScreen(null);
        }
        if (t < 60) {
            return;
        }
        if (t == 60) {
            for (Module m : ZephyrClient.modules().all()) {
                if (m.isEnabled() && !m.name().equals("ActiveHud")) {
                    m.setEnabled(false);
                }
            }
            cmd(client, "gamemode spectator @s");
            cmd(client, "difficulty easy");
            cmd(client, "gamerule spawn_mobs false");
            cmd(client, "time set day");
            cmd(client, "weather clear");
            cmd(client, "tp @s 0.5 170 0.5");
            return;
        }
        if (t == 200) {
            cmd(client, "fill -64 " + FLOOR + " -64 63 " + FLOOR + " 63 minecraft:stone");
            return;
        }
        if (t < 240) {
            return;
        }
        scenarioTick(client, p);
    }

    private static void setup(MinecraftClient client) {
        cmd(client, "kill @e[type=!player]");
        cmd(client, "gamemode survival @s");
        cmd(client, "tp @s 0.5 " + (FLOOR + 1) + " 0.5 0 0");
        cmd(client, "clear @s");
        cmd(client, "effect clear @s");
        cmd(client, "effect give @s minecraft:instant_health 2 5 true");
        cmd(client, "effect give @s minecraft:saturation 5 5 true");
        cmd(client, "item replace entity @s armor.chest with minecraft:elytra[unbreakable={}]");
        cmd(client, "item replace entity @s hotbar.0 with minecraft:netherite_sword");
        cmd(client, "item replace entity @s hotbar.1 with minecraft:mace[enchantments={\"minecraft:wind_burst\":1},unbreakable={}]");
        cmd(client, "item replace entity @s hotbar.2 with minecraft:firework_rocket[fireworks={flight_duration:1}] 64");
        cmd(client, "item replace entity @s inventory.0 with minecraft:firework_rocket[fireworks={flight_duration:1}] 64");
        cmd(client, "item replace entity @s hotbar.3 with minecraft:netherite_chestplate[unbreakable={}]");
        cmd(client, "item replace entity @s hotbar.4 with minecraft:wind_charge 64");
        cmd(client, "item replace entity @s hotbar.5 with " + (scenarioName.contains("noaxe")
                ? "minecraft:air" : "minecraft:netherite_axe[unbreakable={}]"));
        cmd(client, "summon minecraft:husk 0.5 " + (FLOOR + 1) + " 24.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
                + "attributes:[{id:\"minecraft:max_health\",base:1024.0d}],Health:1024.0f,Tags:[\"ebt\"]}");
    }

    private static void scenarioTick(MinecraftClient client, ClientPlayerEntity p) {
        if (scenario >= SCENARIOS.length) {
            if (rep + 1 < REPS) {
                rep++;
                scenario = 0;
                return;
            }
            if (st++ == 0) {
                System.out.println("[EBT] ===== REPORT =====");
                AGGS.forEach((n, a) -> report.add(String.format(
                        "AGG %s: runs=%d totalHits=%d meanSecPerHit=%.2f totalSelfDmg=%.1f deaths=%d",
                        n, a.runs, a.hits, a.secRuns == 0 ? -1.0 : a.secSum / a.secRuns, a.selfDmg, a.deaths)));
                report.forEach(s -> System.out.println("[EBT] " + s));
                writeReport();
                System.out.println("[EBT] done - exiting");
                client.scheduleStop();
            }
            return;
        }
        String name = SCENARIOS[scenario];
        Module bot = bot();
        if (st == 0) {
            cmd(client, "gamemode spectator @s");
            cmd(client, "tp @s 0.5 " + (FLOOR + 1) + " 0.5 0 0");
            selfDamage = 0;
            serverHits = 0;
            shieldDownUntil = 0;
            serverDamage = serverBest = 0;
            serverFirst = -1;
            deaths = 0;
            disabledAt = -1;
            lastState = "";
        }
        if (st == 20) {
            scenarioName = name;
            setup(client);
        }
        if (st > 60 && p.isDead() && !wasDead) {
            deaths++;
            System.out.println("[EBT] " + name + " t=" + st + " PLAYER DIED");
            p.requestRespawn();
        }
        wasDead = p.isDead();
        // One respawn request can get lost (the death screen was not up yet): keep asking, or every
        // later scenario runs with a dead player and the bot sits idle.
        if (p.isDead() && st % 10 == 0) {
            p.requestRespawn();
        }
        // Start-position variants. Teleported just before the bot is enabled (not at setup), so
        // the player is still in the air when the bot starts and does not land first.
        if (st == 59 && name.startsWith("chain")) { // 6 above and 1 beside the target
            cmd(client, "tp @s 0.5 " + (FLOOR + 7) + " 23.5 0 0");
        }
        if (st == 59 && name.startsWith("offset")) { // 8 blocks horizontally away at FLOOR+9
            cmd(client, "tp @s 0.5 " + (FLOOR + 9) + " 16.5 0 0");
        }
        if (st == 60) {
            ModuleManager.FAULTS.remove("ElytraMaceBot");
            p.getInventory().setSelectedSlot(0);
            lastSelfHealth = p.getHealth();
            if (name.contains("/")) { // "still/1.0": scenario with a diveAt value
                String[] v = name.split("/");
                ((dev.zephyr.core.settings.NumberSetting) bot.setting("rocketAt")).set(Double.parseDouble(v[1]));
                if (v.length > 2) {
                    ((dev.zephyr.core.settings.NumberSetting) bot.setting("dropAhead")).set(Double.parseDouble(v[2]));
                }
            }
            // "chain" itself contains "ai", so it is stripped before the aiMode check.
            ((dev.zephyr.core.settings.BooleanSetting) bot.setting("aiMode")).set(name.replace("chain", "").contains("ai"));
            // "climb" in the name: the old behaviour (always climb before diving) as a baseline.
            ((dev.zephyr.core.settings.NumberSetting) bot.setting("rocketDiveFrom")).set(name.contains("climb") ? 0.0 : 2.5);
            bot.setEnabled(true);
            System.out.println("[EBT] >> " + name + " enabled=" + bot.isEnabled());
        }
        // Server-side: drive the dummy and count damage there (no cross-thread sampling).
        MinecraftServer server = client.getServer();
        int now = st;
        boolean committed = bot.hudLabel().contains("strike");
        boolean jumpNow = committed && !wasCommitted;
        wasCommitted = committed;
        server.execute(() -> {
            ServerWorld w = server.getOverworld();
            for (Entity e : w.iterateEntities()) {
                if (e instanceof HuskEntity h && h.getCommandTags().contains("ebt") && h.isAlive()) {
                    float dmg = 1024f - h.getHealth();
                    if (dmg > 0.5f && now > 60) {
                        serverHits++;
                        serverLast = now - 60;
                        serverDamage += dmg;
                        serverBest = Math.max(serverBest, dmg);
                        if (serverFirst < 0) {
                            serverFirst = now - 60;
                        }
                        System.out.printf("[EBT] %s t=%d HIT %.1f dmg%n", name, now, dmg);
                    }
                    h.setHealth(1024f);
                    if (name.startsWith("shield")) {
                        // Mobs have no shield cooldown, so mimic a player's: an attacker holding
                        // an axe nearby lowers the shield for five seconds.
                        var sp = server.getPlayerManager().getPlayerList().get(0);
                        if (now == 40 && h.getOffHandStack().isEmpty()) {
                            h.setStackInHand(net.minecraft.util.Hand.OFF_HAND,
                                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.SHIELD));
                        }
                        if (sp.getMainHandStack().isOf(net.minecraft.item.Items.NETHERITE_AXE) && sp.distanceTo(h) < 6) {
                            shieldDownUntil = now + 100;
                        }
                        if (now > 60 && now >= shieldDownUntil && !h.isUsingItem()) {
                            h.setCurrentHand(net.minecraft.util.Hand.OFF_HAND);
                        } else if (now < shieldDownUntil && h.isUsingItem()) {
                            h.stopUsingItem();
                        }
                        h.setHeadYaw(180f);
                        h.setBodyYaw(180f);
                    }
                    h.setFireTicks(0);
                    if (name.startsWith("dodge")) { // unfair on purpose: leaps aside as the bot commits
                        if (jumpNow) {
                            side = !side;
                            h.requestTeleport(side ? 6.5 : 0.5, FLOOR + 1, 24.5);
                        }
                    } else if (now > 60 && !name.startsWith("still") && !name.startsWith("shield")
                            && !name.startsWith("chain") && !name.startsWith("offset")) {
                        double k = (now - 60);
                        double x, z;
                        if (name.startsWith("walk") || name.startsWith("fast")) { // circle r=10 at walking speed (0.21 b/t)
                            double ang = k * (name.startsWith("fast") ? 0.045 : 0.021); // 0.45 or 0.21 b/t
                            x = 0.5 + Math.sin(ang) * 10;
                            z = 14.5 + Math.cos(ang) * 10;
                        } else { // strafe: reverse every 1.5 s at sprint speed (0.28 b/t)
                            double ph = (k % 60) / 30.0;
                            x = 0.5 + (ph < 1 ? ph : 2 - ph) * 8.4 - 4.2;
                            z = 24.5;
                        }
                        h.requestTeleport(x, FLOOR + 1, z);
                    }
                }
            }
        });
        if (st > 60) {
            peakY = Math.max(peakY, p.getY() - FLOOR - 1);
            if (serverHits != seenHits) {
                System.out.printf("[EBT] %s t=%d peak=%.1f blocks%n", name, st, peakY);
                seenHits = serverHits;
                peakY = 0;
            }
            float hp = p.getHealth();
            if (hp < lastSelfHealth - 0.1f) {
                selfDamage += lastSelfHealth - hp;
                System.out.printf("[EBT] %s t=%d SELF -%.1f (state %s)%n", name, st, lastSelfHealth - hp, lastState);
            }
            lastSelfHealth = hp;
            if (hp < 14f && !p.isDead()) { // keep the run going; damage is already counted
                cmd(client, "effect give @s minecraft:instant_health 1 5 true");
            }
            String label = bot.hudLabel();
            String[] parts = label.split(" · ");
            String state = parts.length > 1 ? parts[1] : label;
            if (!state.equals(lastState)) {
                LivingEntity tg = dev.zephyr.core.target.TargetManager.currentTarget();
                System.out.printf("[EBT] %s t=%d %s pos=(%.1f,%.1f,%.1f) v=%.2f vy=%.2f fall=%.1f dist=%.1f%n", name, st, state,
                        p.getX(), p.getY(), p.getZ(), p.getVelocity().length(), p.getVelocity().y, p.fallDistance,
                        tg == null ? -1 : p.distanceTo(tg));
                lastState = state;
            }
            if (!bot.isEnabled() && disabledAt < 0) {
                disabledAt = st - 60;
                System.out.println("[EBT] " + name + " bot disabled itself at t=" + st
                        + " fault=" + ModuleManager.FAULTS.get("ElytraMaceBot"));
            }
        }
        if (++st >= SCENARIO_TICKS) {
            int rockets = 0;
            for (int i = 0; i < 36; i++) {
                if (p.getInventory().getStack(i).isOf(net.minecraft.item.Items.FIREWORK_ROCKET)) {
                    rockets += p.getInventory().getStack(i).getCount();
                }
            }
            String tag = "rep " + (rep + 1) + "/" + REPS + " " + name;
            double secPerHit = serverHits < 2 ? -1.0 : (serverLast - serverFirst) / 20.0 / (serverHits - 1);
            aggregate(name, secPerHit);
            report.add(String.format("%s: seconds per hit=%.2f", tag, secPerHit));
            System.out.println("[EBT] " + report.get(report.size() - 1));
            report.add(String.format("%s: hits=%d totalDmg=%.0f best=%.1f firstHit=%.1fs selfDmg=%.1f deaths=%d rocketsUsed=%d disabledAt=%.1fs",
                    tag + " [" + bot.hudLabel() + "]", serverHits, serverDamage, serverBest, serverFirst / 20.0, selfDamage, deaths, 128 - rockets,
                    disabledAt / 20.0));
            System.out.println("[EBT] " + report.get(report.size() - 1));
            bot.setEnabled(false);
            scenario++;
            st = 0;
        }
    }
}
