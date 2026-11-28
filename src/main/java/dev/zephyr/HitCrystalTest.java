package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.modules.crystal.AutoHitCrystal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
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
 * Two-instance AutoHitCrystal test, armed only by -Dzephyr.hct=host|victim.
 *
 * <p>The host creates a flat world, opens it to LAN (offline mode) and runs AutoHitCrystal as
 * "Hunter". A second dev client joins as "Victim", a real server-side player, so damage,
 * knockback and hurt time are the game's own. Each trial resets the arena and both kits, then
 * records what the server saw. Never use outside a private test run.
 */
public final class HitCrystalTest {
    private static final String ROLE = System.getProperty("zephyr.hct", "");
    private static final Path DIR = Path.of(System.getProperty("zephyr.hct.dir", "."));
    private static final int PORT = 25590;
    private static final int TRIAL_TICKS = 200;
    private static final boolean TRACE = Boolean.getBoolean("zephyr.hct.trace");

    private static boolean creating;
    private static boolean lanOpen;
    private static int ticks;
    private static int trial = -1;
    private static int t;
    private static int quitTicks;
    private static final List<String[]> trials = new ArrayList<>();
    private static final List<String> report = new ArrayList<>();

    // Written on the server thread, read on the client thread.
    private static volatile float victimHp = 20f;
    private static volatile float hunterHp = 20f;
    private static volatile String lastSource = "";
    private static volatile boolean victimDead;

    private static float prevVictim, prevHunter, dmgMelee, dmgBlast, selfDmg;
    private static int[] hits0;
    private static int melee, blasts, det0, obs0, firstHit, firstDet, deathAt;
    private static final java.util.Set<Integer> crystalIds = new java.util.HashSet<>();
    private static final Map<String, Integer> stageTicks = new TreeMap<>();

    private HitCrystalTest() {
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
            if (ROLE.equals("host")) {
                host(client);
            } else {
                victim(client);
            }
        } catch (Throwable e) {
            log("harness error: " + e);
            e.printStackTrace();
            write("control.txt", "quit");
            client.scheduleStop();
        }
    }

    private static void log(String s) {
        System.out.println("[HCT] " + s);
    }

    private static void write(String name, String text) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(name), text);
        } catch (Exception e) {
            log("write failed: " + e);
        }
    }

    private static String read(String name) {
        try {
            return Files.readString(DIR.resolve(name)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void quietModules() {
        for (Module m : ZephyrClient.modules().all()) {
            if (m.isEnabled() && !(m instanceof AutoHitCrystal)) {
                m.setEnabled(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Victim
    // ------------------------------------------------------------------

    private static void victim(MinecraftClient client) {
        String mode = read("control.txt");
        if (mode.equals("quit")) {
            client.scheduleStop();
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return;
        }
        quietModules();
        if (!p.isAlive()) {
            p.requestRespawn();
            client.setScreen(null);
            return;
        }
        AbstractClientPlayerEntity hunter = client.world.getPlayers().stream()
                .filter(o -> o != p).findFirst().orElse(null);
        boolean forward = false, back = false, left = false, right = false, jump = false;
        if (hunter != null) {
            double dx = hunter.getX() - p.getX(), dz = hunter.getZ() - p.getZ();
            p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            p.setPitch(5f);
            double d = Math.hypot(dx, dz);
            int phase = (p.age / 14) % 2;
            switch (mode) {
                case "approach" -> forward = d > 2.2;
                case "strafe" -> {
                    left = phase == 0;
                    right = phase == 1;
                    forward = d > 2.8;
                }
                case "retreat" -> back = d < 6.0 && (p.age % 40) < 24;
                case "jump" -> {
                    jump = true;
                    forward = d > 2.5;
                }
                default -> {
                }
            }
        }
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);
        client.options.jumpKey.setPressed(jump);
    }

    // ------------------------------------------------------------------
    // Host
    // ------------------------------------------------------------------

    private static void host(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            if (!creating && client.currentScreen instanceof TitleScreen title) {
                creating = true;
                String name = "hct-" + System.currentTimeMillis();
                LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.NORMAL, true,
                        new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(name, info,
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
            lanOpen = client.getServer().openToLan(GameMode.SURVIVAL, true, PORT);
            log("LAN ready=" + lanOpen);
            write("control.txt", "still");
            write("ready.txt", "1");
            for (String rule : new String[]{"natural_health_regeneration false", "spawn_mobs false",
                    "immediate_respawn true", "advance_time false", "send_command_feedback false",
                    "entity_drops false", "block_drops false"}) {
                cmd(server, "gamerule minecraft:" + rule);
            }
            cmd(server, "time set day");
            ticks = 0;
            plan();
            return;
        }
        quietModules();
        sample(server);
        ClientPlayerEntity self = client.player;
        AbstractClientPlayerEntity enemy = client.world.getPlayers().stream()
                .filter(o -> o != self).findFirst().orElse(null);
        if (trial < 0) {
            if (enemy == null || ++ticks < 60) {
                return;
            }
            trial = 0;
            t = 0;
        }
        if (trial >= trials.size()) {
            if (quitTicks == 0) {
                log("===== REPORT =====");
                report.forEach(HitCrystalTest::log);
                write("report.txt", String.join("\n", report) + "\n");
                write("control.txt", "quit");
            }
            if (++quitTicks > 60) {
                client.scheduleStop();
            }
            return;
        }
        run(client, server, self, enemy);
    }

    private static void plan() {
        String[] floors = System.getProperty("zephyr.hct.floors", "grass_block,obsidian").split(",");
        String[] modes = System.getProperty("zephyr.hct.modes", "still,approach,strafe,retreat,jump").split(",");
        int reps = Integer.getInteger("zephyr.hct.reps", 2);
        String[] kits = System.getProperty("zephyr.hct.kits", "hotbar").split(",");
        for (String kit : kits) {
        for (String f : floors) {
            for (String m : modes) {
                for (int r = 0; r < reps; r++) {
                    trials.add(new String[]{f, m, String.valueOf(r), kit});
                }
            }
        }
        }
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommandManager()
                .parseAndExecute(server.getCommandSource().withSilent(), command));
    }

    private static void sample(MinecraftServer server) {
        server.execute(() -> {
            ServerPlayerEntity v = server.getPlayerManager().getPlayer("Victim");
            ServerPlayerEntity h = server.getPlayerManager().getPlayer("Hunter");
            if (v != null) {
                victimHp = v.getHealth() + v.getAbsorptionAmount();
                victimDead = !v.isAlive();
                var src = v.getRecentDamageSource();
                lastSource = src == null ? "" : src.getName();
            }
            if (h != null) {
                hunterHp = h.getHealth() + h.getAbsorptionAmount();
            }
        });
    }

    private static void reset(MinecraftServer server, String floor) {
        cmd(server, "fill -14 -61 -14 14 -61 14 minecraft:bedrock");
        cmd(server, "fill -14 -60 -14 14 -58 14 minecraft:" + floor);
        cmd(server, "fill -14 -57 -14 14 -50 14 minecraft:air");
        cmd(server, "kill @e[type=!minecraft:player]");
        for (String who : new String[]{"Hunter", "Victim"}) {
            cmd(server, "gamemode survival " + who);
            cmd(server, "clear " + who);
            cmd(server, "effect clear " + who);
            cmd(server, "effect give " + who + " minecraft:instant_health 1 5 true");
            cmd(server, "effect give " + who + " minecraft:saturation 2 5 true");
            for (String[] a : new String[][]{{"head", "helmet"}, {"chest", "chestplate"},
                    {"legs", "leggings"}, {"feet", "boots"}}) {
                String ench = a[0].equals("legs") ? "blast_protection" : "protection";
                cmd(server, "item replace entity " + who + " armor." + a[0] + " with minecraft:netherite_" + a[1]
                        + "[enchantments={\"minecraft:" + ench + "\":4},unbreakable={}]");
            }
        }
        cmd(server, "item replace entity Hunter hotbar.0 with minecraft:netherite_sword[enchantments={\"minecraft:knockback\":1}]");
        cmd(server, "item replace entity Hunter hotbar.1 with minecraft:obsidian 64");
        cmd(server, "item replace entity Hunter hotbar.2 with minecraft:end_crystal 64");
        cmd(server, "tp Hunter 0.5 -57 0.5 0 5");
        cmd(server, "tp Victim 0.5 -57 3.0 180 5");
    }

    private static void run(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self,
                            AbstractClientPlayerEntity enemy) {
        String[] tr = trials.get(trial);
        AutoHitCrystal m = (AutoHitCrystal) ZephyrClient.modules().byName("AutoHitCrystal");
        t++;
        if (t == 1) {
            m.setEnabled(false);
            write("control.txt", "still");
            client.options.forwardKey.setPressed(false);
            reset(server, tr[0]);
            return;
        }
        if (t == 20) {
            // Kit variants, applied over the standard hotbar kit.
            if (tr[3].equals("inventory")) {
                cmd(server, "clear Hunter minecraft:obsidian");
                cmd(server, "clear Hunter minecraft:end_crystal");
                cmd(server, "item replace entity Hunter inventory.0 with minecraft:obsidian 64");
                cmd(server, "item replace entity Hunter inventory.1 with minecraft:end_crystal 64");
            } else if (tr[3].equals("fewcrystals")) {
                cmd(server, "item replace entity Hunter hotbar.2 with minecraft:end_crystal 2");
            } else if (tr[3].endsWith("tanky")) {
                // Survives the whole trial, so the crystal rate can be measured.
                cmd(server, "effect give Victim minecraft:resistance 30 3 true");
            } else if (tr[3].equals("lowhp")) {
                cmd(server, "damage Hunter 15 minecraft:generic_kill");
            }
        }
        if (t < 40) {
            self.getInventory().setSelectedSlot(0);
            return;
        }
        if (t == 40) {
            write("control.txt", tr[1]);
            self.setYaw(0f);
            self.setPitch(5f);
            prevVictim = victimHp;
            if (victimHp < 19.9f || hunterHp < 4.9f) {
                log("WARNING trial starts at victim hp " + victimHp + " hunter hp " + hunterHp);
            }
            prevHunter = hunterHp;
            dmgMelee = dmgBlast = selfDmg = 0;
            melee = blasts = 0;
            firstHit = firstDet = deathAt = -1;
            hits0 = m.hitCounts();
            det0 = m.detonatedCount();
            obs0 = m.obsidianPlacedCount();
            crystalIds.clear();
            stageTicks.clear();
            m.setEnabled(true);
            return;
        }
        int age = t - 40;
        float v = victimHp, h = hunterHp;
        if (v < prevVictim - 0.01f) {
            float d = prevVictim - v;
            if (lastSource.contains("explosion")) {
                dmgBlast += d;
                blasts++;
            } else {
                dmgMelee += d;
                melee++;
                if (firstHit < 0) {
                    firstHit = age;
                }
            }
        }
        if (h < prevHunter - 0.01f) {
            selfDmg += prevHunter - h;
        }
        prevVictim = v;
        prevHunter = h;
        if (firstDet < 0 && m.detonatedCount() > det0) {
            firstDet = age;
        }
        for (EndCrystalEntity c : client.world.getEntitiesByClass(EndCrystalEntity.class,
                new Box(self.getBlockPos()).expand(12), e -> true)) {
            crystalIds.add(c.getId());
        }
        String label = m.hudLabel();
        String[] parts = label.split(" · ");
        String stage = parts.length > 1 ? parts[1] : "?";
        stageTicks.merge(stage, 1, Integer::sum);
        if (TRACE && enemy != null) {
            log(String.format(Locale.ROOT,
                    "t=%d %s d=%.2f slot=%d yaw=%.0f pitch=%.0f cd=%.2f hurt=%d ehp=%.1f me=%.1f crystals=%d det=%d enemy=(%.1f,%.1f,%.1f)",
                    age, stage, self.distanceTo(enemy), self.getInventory().getSelectedSlot(), self.getYaw(),
                    self.getPitch(), self.getAttackCooldownProgress(0.5f), enemy.hurtTime, v, h, crystalIds.size(),
                    m.detonatedCount() - det0, enemy.getX(), enemy.getY(), enemy.getZ()));
        }
        // Stand in for the player's legs: close the gap between combos, never during one.
        boolean chase = false;
        if (enemy != null && self.distanceTo(enemy) > 2.8 && stage.equals("melee")) {
            double dx = enemy.getX() - self.getX(), dz = enemy.getZ() - self.getZ();
            float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float delta = net.minecraft.util.math.MathHelper.wrapDegrees(want - self.getYaw());
            self.setYaw(self.getYaw() + Math.max(-15f, Math.min(15f, delta)));
            chase = Math.abs(delta) < 40f;
        }
        if (tr[3].startsWith("holdw") && enemy != null) {
            chase = self.distanceTo(enemy) > 1.5; // the player simply keeps W held
        }
        client.options.forwardKey.setPressed(chase);
        client.options.sprintKey.setPressed(chase);

        if (victimDead && deathAt < 0) {
            deathAt = age;
        }
        boolean suicided = !self.isAlive();
        if (age >= TRIAL_TICKS || deathAt >= 0 || suicided) {
            int placed = crystalIds.size();
            String line = String.format(Locale.ROOT,
                    "%s %s #%s %s | kill=%s dmg melee=%.1f(%d) blast=%.1f(%d) self=%.1f | crystals=%d det=%d obby=%d | firstHit=%d firstDet=%d | stages=%s",
                    tr[0], tr[1], tr[2], tr[3], deathAt >= 0 ? deathAt + "t" : "no", dmgMelee, melee, dmgBlast, blasts,
                    selfDmg, placed, m.detonatedCount() - det0, m.obsidianPlacedCount() - obs0, firstHit, firstDet,
                    stageTicks) + " | endSlot=" + self.getInventory().getSelectedSlot()
                    + " hunterHp=" + String.format(Locale.ROOT, "%.1f", h) + (suicided ? " HUNTER DIED" : "") + " swings=" + (m.hitCounts()[0] - hits0[0])
                    + " sprintHits=" + (m.hitCounts()[1] - hits0[1]) + " kbSwordHits=" + (m.hitCounts()[2] - hits0[2]);
            report.add(line);
            log(line);
            m.setEnabled(false);
            client.options.forwardKey.setPressed(false);
            trial++;
            t = 0;
        }
    }
}
