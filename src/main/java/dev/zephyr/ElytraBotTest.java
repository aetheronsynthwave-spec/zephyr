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

import java.util.ArrayList;
import java.util.List;

/**
 * In-game scenario test for ElytraMaceBot (dev only, -Dzephyr.elytratest=1).
 * Builds a stone arena, spawns a real server-side husk as the practice dummy so mace
 * damage is real, and logs every bot state change, hit and bit of self damage.
 */
public final class ElytraBotTest {

    private static final String[] SCENARIOS = System.getProperty("zephyr.scen", "still,walk,strafe,dodge").split(",");
    private static final int SCENARIO_TICKS = 20 * Integer.getInteger("zephyr.secs", 40) + 60;
    private static final int FLOOR = 150;

    private static boolean enabled;
    private static int t = -1;
    private static int scenario;
    private static int st;
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
            if (st++ == 0) {
                System.out.println("[EBT] ===== REPORT =====");
                report.forEach(s -> System.out.println("[EBT] " + s));
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
            ((dev.zephyr.core.settings.BooleanSetting) bot.setting("aiMode")).set(name.contains("ai"));
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
                    } else if (now > 60 && !name.startsWith("still") && !name.startsWith("shield")) {
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
            report.add(String.format("%s: seconds per hit=%.2f", name,
                    serverHits < 2 ? -1.0 : (serverLast - serverFirst) / 20.0 / (serverHits - 1)));
            System.out.println("[EBT] " + report.get(report.size() - 1));
            report.add(String.format("%s: hits=%d totalDmg=%.0f best=%.1f firstHit=%.1fs selfDmg=%.1f deaths=%d rocketsUsed=%d disabledAt=%.1fs",
                    name + " [" + bot.hudLabel() + "]", serverHits, serverDamage, serverBest, serverFirst / 20.0, selfDamage, deaths, 128 - rockets,
                    disabledAt / 20.0));
            System.out.println("[EBT] " + report.get(report.size() - 1));
            bot.setEnabled(false);
            scenario++;
            st = 0;
        }
    }
}
