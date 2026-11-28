package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.modules.crystal.AutoCrystalPvP;
import dev.zephyr.modules.crystal.AutoHitCrystal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Staged pearl chase (-Dzephyr.pbt.chase=true, run from {@link PracticeBotTest}): we trade a
 * few blows with the stationary bot, then it is flung 6, 9 and 12 blocks away into the air.
 */
final class ChaseCheck {

    private static final String BOT = "TheobaldTheBot";
    private static final int[] FLINGS = {6, 9, 12, 6, 9, 12};
    private static final int PHASE = 300;
    private static final int FLING = 120;
    private static int tick;
    private static int flungAt = -1;
    private static int seen;
    private static int closeStart;
    private static float[] before;
    private static int obsidianBefore;
    private static int blastsBefore;
    private static double botZ;
    private static String arrival = "never arrived";

    private ChaseCheck() {
    }

    /** @return true when finished */
    static boolean tick(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self, Consumer<String> log,
                        Consumer<String> cmd) {
        String me = self.getGameProfile().name();
        int phase = tick / PHASE;
        int s = tick % PHASE;
        tick++;
        Module bot = ZephyrClient.modules().byName("AutoCrystalPvP");
        if (phase >= FLINGS.length) {
            bot.setEnabled(false);
            return true;
        }
        AutoCrystalPvP pvp = (AutoCrystalPvP) bot;
        AutoHitCrystal hitter = (AutoHitCrystal) ZephyrClient.modules().byName("AutoHitCrystal");
        cmd.accept("player " + BOT + " stop");
        if (s == 0) {
            bot.setEnabled(false);
            cmd.accept("scoreboard players set " + BOT + " difficulty 0");
            cmd.accept("kill @e[type=!player]");
            cmd.accept("fill 4 61 4 36 74 52 minecraft:air");
            cmd.accept("fill 4 60 4 36 60 52 minecraft:netherite_block");
            cmd.accept("tp " + me + " 20.5 61 14.5 0 0");
            for (String who : new String[]{BOT, me}) {
                cmd.accept("attribute " + who + " minecraft:max_health base set 1000");
                cmd.accept("effect clear " + who + " minecraft:resistance");
                cmd.accept("attribute " + who + " minecraft:explosion_knockback_resistance base set 1");
                cmd.accept("attribute " + who + " minecraft:knockback_resistance base set 1");
            }
            cmd.accept("item replace entity " + me + " hotbar.1 with minecraft:ender_pearl 16");
            botZ = 17.0;
            arrival = "never arrived";
        }
        if (s % 20 == 0) {
            cmd.accept("effect give " + BOT + " minecraft:instant_health 1 8 true");
            cmd.accept("effect give " + me + " minecraft:instant_health 1 8 true");
        }
        // Close by on the ground first, then flung: held three blocks up, farther away.
        // The second three run with the anchor modules left out, to compare.
        for (var setting : bot.settings()) {
            if (setting.id().equals("anchors")) {
                @SuppressWarnings("unchecked")
                var anchors = (dev.zephyr.core.settings.Setting<Boolean>) setting;
                anchors.loadRaw(phase < 3);
            }
        }
        // Flung the moment after one of our hits or blasts, as it happens in a fight.
        int done = hitter.hitCounts()[0] + hitter.detonatedCount();
        if (s == 0) {
            flungAt = -1;
        }
        if (s == 40) {
            closeStart = done;
        }
        if (s >= FLING && flungAt < 0 && (done > seen || s >= FLING + 60)) {
            flungAt = s;
            log.accept(String.format(Locale.ROOT, "chase anchors=%s: close fight made %d hits+blasts in %d ticks%s",
                    phase < 3, done - closeStart, s - 40, done > seen ? "" : " (flung without a fresh one)"));
        }
        seen = done;
        if (flungAt < 0) {
            if (s % 5 == 0) {
                cmd.accept("tp " + BOT + " 20.5 61 17.0 180 0");
            }
        } else {
            botZ = Math.min(14.5 + FLINGS[phase], botZ + 0.8);
            cmd.accept(String.format(Locale.ROOT, "tp %s 20.5 64 %.2f 180 0", BOT, botZ));
        }
        if (s == 20) {
            bot.setEnabled(true);
        }
        if (s == flungAt) {
            before = pvp.chaseCounts();
            obsidianBefore = hitter.obsidianPlacedCount();
            blastsBefore = hitter.detonatedCount();
        }
        if (flungAt >= 0 && s > flungAt && before != null && pvp.chaseCounts()[1] > before[1] && arrival.startsWith("never")) {
            arrival = String.format(Locale.ROOT,
                    "arrived %d ticks after the fling at z=%.1f y=%.1f (they are over z=%.1f), obsidian then %d, blasts %d",
                    s - flungAt, self.getZ(), self.getY(), botZ, hitter.obsidianPlacedCount() - obsidianBefore,
                    hitter.detonatedCount() - blastsBefore);
        }
        if (s == PHASE - 1) {
            float[] now = pvp.chaseCounts();
            log.accept(String.format(Locale.ROOT,
                    "chase fling %d blocks: %d pearls, last pitch %.1f (positive = down) | %s | by the end obsidian %d, blasts %d | %s",
                    FLINGS[phase], (int) (now[0] - before[0]), now[2], arrival,
                    hitter.obsidianPlacedCount() - obsidianBefore, hitter.detonatedCount() - blastsBefore,
                    pvp.lastRefusal()));
        }
        return false;
    }
}
