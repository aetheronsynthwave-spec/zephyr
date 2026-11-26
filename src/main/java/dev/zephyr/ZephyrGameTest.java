package dev.zephyr;

import com.mojang.authlib.GameProfile;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * IN-GAME TEST HARNESS - activated by -Dzephyr.gametest=1 only.
 *
 * Joins a singleplayer world (quickPlay), then for every module: resets the player's kit,
 * frames a client-side fake enemy player, enables the module, drives a module-specific
 * scenario and samples the player each tick (rotation, per-tick rotation step = smoothness,
 * hotbar slot, swings, offhand, item counts, crystals, velocity). Optional filter:
 * -Dzephyr.gametest.only=Name1,Name2
 */
public final class ZephyrGameTest {
    private static boolean enabled;
    private static int ticksInWorld;
    private static int phase;
    private static int phaseTicks;
    private static boolean setupDone;
    private static OtherClientPlayerEntity fake;
    private static List<Module> queue;
    private static final List<String> report = new ArrayList<>();
    private static int shotCount;
    private static boolean autoLogPending;

    private static final int TICKS_PER_MODULE = 90;
    private static final int ENABLE_AT = 6;

    private static float yaw0, pitch0, lastYaw, lastPitch;
    private static float maxYawDelta, maxPitchDelta, maxStep;
    private static final java.util.Set<Integer> crystalIds = new java.util.HashSet<>();
    private static int swings;
    private static boolean lastSwing;
    private static final Set<Integer> slots = new LinkedHashSet<>();
    private static boolean sawOffhandTotem;
    private static double maxVelY;
    private static int crystalsSeen;
    private static int itemCount0;
    private static int xpBottles0;
    private static boolean sawTarget;

    public static void init() {
        enabled = "1".equals(System.getProperty("zephyr.gametest"));
        if (enabled) {
            System.out.println("[GAMETEST] armed");
        }
    }

    public static void tick(MinecraftClient client) {
        if (!enabled) {
            return;
        }
        if (client.player == null || client.world == null) {
            if (autoLogPending) {
                String l = "OK AutoLog  (disconnected on low health)";
                report.add(l);
                System.out.println("[GAMETEST] " + l);
                autoLogPending = false;
                finish(client);
            }
            ticksInWorld = 0;
            return;
        }
        ticksInWorld++;
        if (ticksInWorld < 100) {
            return;
        }
        try {
            if (!setupDone) {
                if (!client.player.isOnGround()) {
                    return;
                }
                setup(client);
                setupDone = true;
                return;
            }
            run(client);
        } catch (Throwable t) {
            System.out.println("[GAMETEST] harness error: " + t);
            t.printStackTrace();
            finish(client);
        }
    }

    private static void cmd(MinecraftClient client, String command) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerCommandSource src = server.getCommandSource().withSilent();
            server.getCommandManager().parseAndExecute(src, "execute as @a at @s run " + command);
        });
    }

    private static void giveKit(MinecraftClient client) {
        cmd(client, "clear @s");
        cmd(client, "give @s minecraft:netherite_sword");
        cmd(client, "give @s minecraft:mace");
        cmd(client, "give @s minecraft:obsidian 64");
        cmd(client, "give @s minecraft:end_crystal 64");
        cmd(client, "give @s minecraft:respawn_anchor 16");
        cmd(client, "give @s minecraft:glowstone 16");
        cmd(client, "give @s minecraft:ender_pearl 16");
        cmd(client, "give @s minecraft:wind_charge 16");
        cmd(client, "give @s minecraft:experience_bottle 64");
        cmd(client, "give @s minecraft:totem_of_undying 4");
        cmd(client, "give @s minecraft:netherite_spear");
        // Realistic crystal-PvP armour: the self-damage gates are judged against it.
        for (String piece : new String[]{"head:helmet", "chest:chestplate", "legs:leggings", "feet:boots"}) {
            String[] s = piece.split(":");
            cmd(client, "item replace entity @s armor." + s[0] + " with minecraft:netherite_" + s[1]
                    + "[enchantments={\"minecraft:" + (s[0].equals("legs") ? "blast_protection" : "protection")
                    + "\":4}]");
        }
        cmd(client, "effect clear @s");
        cmd(client, "kill @e[type=minecraft:end_crystal,distance=..30]");
        cmd(client, "kill @e[type=minecraft:item,distance=..30]");
        cmd(client, "experience set @s 0 levels");
        cmd(client, "gamemode survival @s");
    }

    private static void setup(MinecraftClient client) {
        System.out.println("[GAMETEST] setup; player=" + client.player.getName().getString());
        cmd(client, "time set day");
        cmd(client, "weather clear");
        GameProfile gp = new GameProfile(UUID.nameUUIDFromBytes("zephyr-test".getBytes()), "TestEnemy");
        fake = new OtherClientPlayerEntity(client.world, gp);
        Vec3d pos = client.player.getEntityPos();
        fake.refreshPositionAndAngles(pos.x, pos.y, pos.z + 3.0, 180f, 0f);
        fake.setHealth(20f);
        client.world.addEntity(fake);
        queue = new ArrayList<>(ZephyrClient.modules().all());
        String only = System.getProperty("zephyr.gametest.only");
        if (only != null && !only.isBlank()) {
            List<String> names = List.of(only.toLowerCase().split(","));
            queue.removeIf(m -> !names.contains(m.name().toLowerCase()));
        }
        Module log = ZephyrClient.modules().byName("AutoLog");
        if (queue.remove(log)) {
            queue.add(log);
        }
        phase = -1;
        phaseTicks = 0;
        giveKit(client);
        shot(client, "world");
    }

    private static int count(ClientPlayerEntity p, Item item) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static void run(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) {
            client.setScreen(null); // a pause menu freezes the integrated server mid-test
        }
        if (fake.isRemoved()) {
            client.world.addEntity(fake);
        }
        phaseTicks++;
        if (phase == -1) {
            if (phaseTicks > 40) {
                phase = 0;
                phaseTicks = 0;
            }
            return;
        }
        if (phase >= queue.size()) {
            finish(client);
            return;
        }
        Module m = queue.get(phase);
        String n = m.name();
        boolean isGui = n.equalsIgnoreCase("ClickGui");

        if (phaseTicks == 1) {
            m.setEnabled(false);
            giveKit(client);
            boolean fragile = n.equals("JumpReset") || n.equals("AutoLog") || n.equals("AutoTotem");
            if (!fragile) {
                cmd(client, "effect give @s minecraft:resistance 100 4 true");
                cmd(client, "effect give @s minecraft:saturation 100 4 true");
            }
            cmd(client, "attribute @s minecraft:max_health base set 20");
            cmd(client, "effect give @s minecraft:regeneration 1 0 true");
            if (n.contains("Crystal") || n.contains("Anchor")) {
                cmd(client, "fill ~-2 ~-1 ~-1 ~4 ~-1 ~6 minecraft:obsidian");
            }
            ModuleManager.FAULTS.remove(n);
            Vec3d pp = p.getEntityPos();
            boolean mace = n.equals("AutoMace") || n.equals("BreachSwap");
            if (mace) {
                cmd(client, "tp @s ~ ~14 ~");
            }
            if (n.equals("BreachSwap")) { // BreachSwap acts on AutoMace's strike requests
                cmd(client, "item replace entity @s hotbar.7 with minecraft:mace[enchantments={\"minecraft:breach\":4}]");
            }
            if (n.equals("ShieldBreaker")) {
                cmd(client, "item replace entity @s hotbar.7 with minecraft:netherite_axe");
            }
            double fx = mace || n.equals("TriggerBot") || n.equals("ShieldBreaker") ? 0.0 : 0.8, fz = mace ? 0.6 : 2.2;
            fake.refreshPositionAndAngles(pp.x + fx, pp.y, pp.z + fz, 180f, 0f);
            fake.setHealth(20f);
            fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, n.equals("ShieldBreaker")
                    ? new net.minecraft.item.ItemStack(Items.SHIELD) : net.minecraft.item.ItemStack.EMPTY);
            setFakeUsingOffhand(n.equals("ShieldBreaker")); // enemy raises the shield
            p.getInventory().setSelectedSlot(0); // start every module holding the sword
            p.setYaw(0f);
            p.setPitch(5f);
            yaw0 = lastYaw = 0f;
            pitch0 = lastPitch = 5f;
            maxYawDelta = maxPitchDelta = maxStep = 0;
            swings = 0;
            lastSwing = false;
            slots.clear();
            sawOffhandTotem = false;
            maxVelY = 0;
            crystalsSeen = 0;
            crystalIds.clear();
            sawTarget = false;
            return;
        }
        if (phaseTicks == ENABLE_AT) {
            if (!isGui) {
                m.setEnabled(true);
            }
            if (n.equals("BreachSwap")) {
                ZephyrClient.modules().byName("AutoMace").setEnabled(true);
            }
            itemCount0 = count(p, Items.ENDER_PEARL) + count(p, Items.WIND_CHARGE);
            xpBottles0 = count(p, Items.EXPERIENCE_BOTTLE);
            System.out.println("[GAMETEST] >> " + n + " enabled=" + m.isEnabled() + " bind=" + m.bind().get());
            if (n.equals("PearlCatch") || n.equals("LungeMacro")) {
                int key = m.bind().get();
                if (key > 0) {
                    ZephyrClient.modules().handleKey(key, GLFW.GLFW_PRESS);
                }
            }
        }
        if (isGui && m instanceof dev.zephyr.modules.gui.ClickGui gui) {
            // Menu-scale check: default size, then 0.2 smaller (the Ctrl+wheel path), then restore.
            if (phaseTicks == ENABLE_AT) {
                gui.openOrClose(client);
            } else if (phaseTicks == ENABLE_AT + 15) {
                shot(client, "gui-default");
                gui.adjustScale(-0.2);
            } else if (phaseTicks == ENABLE_AT + 30) {
                shot(client, "gui-smaller");
                gui.adjustScale(0.2);
            } else if (phaseTicks == ENABLE_AT + 40) {
                gui.openOrClose(client);
            }
        }
        if (n.equals("AimAssist")) { // it only assists while the attack button is held
            client.options.attackKey.setPressed(phaseTicks >= ENABLE_AT && phaseTicks < TICKS_PER_MODULE - 2);
        }
        if (phaseTicks == ENABLE_AT + 8 && n.equals("AutoTotem")) {
            cmd(client, "damage @s 13 minecraft:generic");
        }
        if (phaseTicks >= ENABLE_AT + 12 && phaseTicks % 15 == 0 && (n.equals("STap") || n.equals("LegitAura") && false)) {
            client.interactionManager.attackEntity(p, fake);
            p.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
        if (phaseTicks == ENABLE_AT + 20) {
            if (n.equals("JumpReset")) {
                cmd(client, "damage @s 2 minecraft:generic");
            }
            if (n.equals("JumpReset") || n.equals("DamageNumbers") || n.equals("TargetHUD") || n.equals("STap")) {
                fake.setHealth(fake.getHealth() - 5f);
            }
        }
        if (phaseTicks == ENABLE_AT + 25 && n.equals("AutoLog")) {
            cmd(client, "effect clear @s minecraft:resistance");
            cmd(client, "damage @s 18 minecraft:generic");
            autoLogPending = true;
        }

        if (phaseTicks >= ENABLE_AT) {
            float y = p.getYaw(), pt = p.getPitch();
            maxYawDelta = Math.max(maxYawDelta, Math.abs(wrap(y - yaw0)));
            maxPitchDelta = Math.max(maxPitchDelta, Math.abs(pt - pitch0));
            float stepNow = Math.max(Math.abs(wrap(y - lastYaw)), Math.abs(pt - lastPitch));
            if (stepNow > 25f) {
                System.out.println("[GAMETEST] bigstep " + n + " t=" + phaseTicks + " yaw " + lastYaw + "->" + y
                        + " pitch " + lastPitch + "->" + pt + " pos=" + p.getEntityPos());
            }
            maxStep = Math.max(maxStep, stepNow);
            lastYaw = y;
            lastPitch = pt;
            if (p.handSwinging && !lastSwing) {
                swings++;
            }
            lastSwing = p.handSwinging;
            slots.add(p.getInventory().getSelectedSlot());
            if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                sawOffhandTotem = true;
            }
            maxVelY = Math.max(maxVelY, p.getVelocity().y);
            for (EndCrystalEntity ec : client.world.getEntitiesByClass(EndCrystalEntity.class,
                    new Box(p.getBlockPos()).expand(12), e -> true)) {
                crystalIds.add(ec.getId());
            }
            crystalsSeen = crystalIds.size();
            if (dev.zephyr.core.target.TargetManager.currentTarget() != null) {
                sawTarget = true;
            }
        }
        if (phaseTicks == TICKS_PER_MODULE - 5 && m.isEnabled()) {
            shot(client, "mod-" + n);
        }
        if (phaseTicks >= TICKS_PER_MODULE) {
            String fault = ModuleManager.FAULTS.get(n);
            boolean alive = m.isEnabled() || isGui || (n.equals("AutoLog") && autoLogPending);
            int pearlsNow = count(p, Items.ENDER_PEARL) + count(p, Items.WIND_CHARGE);
            int xpNow = count(p, Items.EXPERIENCE_BOTTLE);
            String probe = String.format("yaw+-%.0f pitch+-%.0f maxStep=%.1f swings=%d slots=%s offhandTotem=%s maxVelY=%.2f crystals=%d target=%s pearls/wind %d->%d xpBottles %d->%d xpLevel=%d",
                    maxYawDelta, maxPitchDelta, maxStep, swings, slots, sawOffhandTotem, maxVelY, crystalsSeen,
                    sawTarget, itemCount0, pearlsNow, xpBottles0, xpNow, p.experienceLevel);
            String verdict = fault != null ? "FAIL  <- " + fault
                    : !alive ? "FAIL  <- self-disabled / failed to enable" : "OK";
            String line = verdict + " " + n + "  [" + probe + "]";
            report.add(line);
            System.out.println("[GAMETEST] " + line);
            m.setEnabled(false);
            if (n.equals("BreachSwap")) {
                ZephyrClient.modules().byName("AutoMace").setEnabled(false);
            }
            phase++;
            phaseTicks = 0;
        }
    }

    /**
     * Client-side "is using item" lives in the synced LIVING_FLAGS byte (setCurrentHand only
     * sets it on the server), so the fake enemy's shield is raised through that flag. Dev
     * runtime only (yarn names), which is the only place this harness runs.
     */
    @SuppressWarnings("unchecked")
    private static void setFakeUsingOffhand(boolean using) {
        try {
            java.lang.reflect.Field f = net.minecraft.entity.LivingEntity.class.getDeclaredField("LIVING_FLAGS");
            f.setAccessible(true);
            var td = (net.minecraft.entity.data.TrackedData<Byte>) f.get(null);
            fake.getDataTracker().set(td, (byte) (using ? 0x1 | 0x2 : 0));
        } catch (Throwable t) {
            System.out.println("[GAMETEST] could not raise fake shield: " + t);
        }
    }

    private static float wrap(float a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private static void finish(MinecraftClient client) {
        System.out.println("[GAMETEST] ===== REPORT =====");
        for (String l : report) {
            System.out.println("[GAMETEST] " + l);
        }
        try {
            File dir = new File("gametest-out");
            dir.mkdirs();
            java.nio.file.Files.write(new File(dir, "report.txt").toPath(), report);
        } catch (Exception ignored) {
        }
        System.out.println("[GAMETEST] done - exiting");
        enabled = false;
        client.scheduleStop();
    }

    private static void shot(MinecraftClient client, String name) {
        try {
            net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), img -> {
                try {
                    File dir = new File("gametest-out");
                    dir.mkdirs();
                    img.writeTo(new File(dir, (shotCount++) + "-" + name + ".png"));
                } catch (Exception e) {
                    System.out.println("[GAMETEST] shot write failed: " + e);
                }
            });
        } catch (Throwable t) {
            System.out.println("[GAMETEST] shot failed: " + t);
        }
    }
}
