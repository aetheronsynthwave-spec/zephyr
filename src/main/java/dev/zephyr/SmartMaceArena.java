package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.core.settings.Setting;
import dev.zephyr.modules.combat.SmartMaceBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Scenarios and parameter training for SmartMaceBot, run from {@link PracticeBotTest}
 * (-Dzephyr.pbt.smart=check or train). The practice bot stands in as a target that does not
 * fight back; it is kept alive with a large health pool so every hit can be measured.
 *
 * <p>Training is a one-setting-at-a-time search: change one value, replay all scenarios, keep
 * the change only if the score rises. Score = damage dealt minus 1.5 x damage we took.
 */
final class SmartMaceArena {

    private static final String BOT = "TheobaldTheBot";
    private static final String[] SCENES = {"level", "pillar", "far", "shield", "circle"};
    private static final int SCENE_TICKS = Integer.getInteger("zephyr.pbt.sceneticks", 500);
    private static final int SETTLE = 40;

    private record Knob(String id, double[] values) {
    }

    private static final Knob[] KNOBS = {
            new Knob("wantHeight", new double[]{2.5, 3.0, 4.0, 6.0, 8.0}),
            new Knob("minFall", new double[]{1.5, 2.0, 3.0, 4.5}),
            new Knob("launchRange", new double[]{3, 4, 5, 7, 9}),
            new Knob("windGap", new double[]{12, 20, 30, 45}),
            new Knob("pearlEvery", new double[]{60, 100, 160, 300}),
            new Knob("pearlGap", new double[]{2, 3, 4, 6}),
            new Knob("glideFrom", new double[]{2.5, 3.5, 5.0}),
            new Knob("aimTol", new double[]{6, 10, 16, 24}),
            new Knob("rotMax", new double[]{40, 70, 120, 180}),
    };

    private static final List<String> done = new ArrayList<>();
    private static final Map<String, Double> best = new LinkedHashMap<>();
    private static Map<String, Double> trial = new LinkedHashMap<>();
    private static final Random RANDOM = new Random(7);
    private static double bestScore = Double.NEGATIVE_INFINITY;
    private static int scene;
    private static int tick;
    private static int round;
    private static double score;
    private static double dealt0, taken0;
    private static int[] counts0;
    private static double peak;

    private SmartMaceArena() {
    }

    /** @return true when finished */
    static boolean tick(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self, boolean train,
                        java.util.function.Consumer<String> log, java.util.function.Consumer<String> cmd,
                        java.util.function.DoubleSupplier dealt, java.util.function.DoubleSupplier taken) {
        SmartMaceBot bot = (SmartMaceBot) ZephyrClient.modules().byName("SmartMaceBot");
        String me = self.getGameProfile().name();
        int s = tick++;
        if (s == 0) {
            bot.setEnabled(false);
            if (round == 0 && scene == 0) {
                for (Knob k : KNOBS) {
                    best.put(k.id(), value(bot, k.id()));
                }
                trial = new LinkedHashMap<>(best);
            }
            if (scene == 0) {
                score = 0;
                apply(bot, trial);
            }
            arena(cmd, me, SCENES[scene]);
        }
        if (!SCENES[scene].equals("shield")) {
            cmd.accept("player " + BOT + " stop");
        } else {
            cmd.accept("player " + BOT + " use continuous");
        }
        if (SCENES[scene].equals("circle")) {
            double a = s * 0.05;
            cmd.accept(String.format(Locale.ROOT, "tp %s %.2f 61 %.2f %.0f 0", BOT, 20.5 + 4 * Math.sin(a),
                    30.5 + 4 * Math.cos(a), 180.0));
        }
        for (String who : new String[]{BOT, me}) {
            if (s % 20 == 0) {
                cmd.accept("effect give " + who + " minecraft:instant_health 1 8 true");
            }
        }
        if (s % 40 == 0) {
            cmd.accept("effect give " + me + " minecraft:saturation 1 5 true");
        }
        if (s < SETTLE) {
            if (s == 20) {
                loadout(cmd, me);
            }
            return false;
        }
        if (s == SETTLE) {
            dealt0 = dealt.getAsDouble();
            taken0 = taken.getAsDouble();
            counts0 = bot.counts();
            peak = 0;
            bot.setEnabled(true);
        }
        peak = Math.max(peak, self.getY() - 61);
        if (!train && s % 100 == 0) {
            log.accept("  t=" + s + " " + bot.hudLabel() + " y=" + String.format(Locale.ROOT, "%.1f", self.getY())
                    + " chest=" + self.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem()
                    + " slot=" + self.getInventory().getSelectedSlot() + " pos=" + self.getBlockPos().toShortString()
                    + " others=" + client.world.getPlayers().stream().filter(p -> p != self)
                    .map(p -> p.getBlockPos().toShortString() + " d=" + (int) self.distanceTo(p) + " blocking=" + p.isBlocking())
                    .toList());
        }
        if (s < SETTLE + SCENE_TICKS) {
            return false;
        }
        bot.setEnabled(false);
        int[] c = bot.counts();
        double hit = dealt.getAsDouble() - dealt0;
        double hurt = taken.getAsDouble() - taken0;
        score += hit - 1.5 * hurt;
        if (!train) {
            log.accept(String.format(Locale.ROOT,
                    "smart %s: dealt %.0f, took %.0f in %d s | smashes %d, wind launches %d, pearl catches %d, "
                            + "stun slams %d, glides %d, fall saves %d | highest %.1f above floor | hits %s",
                    SCENES[scene], hit, hurt, SCENE_TICKS / 20, c[0] - counts0[0], c[1] - counts0[1],
                    c[2] - counts0[2], c[3] - counts0[3], c[4] - counts0[4], c[5] - counts0[5], peak,
                    "see damage table"));
        }
        tick = 0;
        if (++scene < SCENES.length) {
            return false;
        }
        scene = 0;
        if (!train) {
            log.accept(String.format(Locale.ROOT, "smart score %.0f with %s", score, best));
            return true;
        }
        return nextTrial(bot, log);
    }

    private static boolean nextTrial(SmartMaceBot bot, java.util.function.Consumer<String> log) {
        boolean better = score > bestScore;
        log.accept(String.format(Locale.ROOT, "train %d: score %.0f%s with %s", round, score,
                better ? " (kept)" : "", trial));
        if (better) {
            bestScore = score;
            best.clear();
            best.putAll(trial);
        }
        if (++round > Integer.getInteger("zephyr.pbt.rounds", 24)) {
            log.accept(String.format(Locale.ROOT, "train best %.0f: %s", bestScore, best));
            apply(bot, best);
            return true;
        }
        // One setting, one new value.
        for (int attempt = 0; attempt < 100; attempt++) {
            Knob knob = KNOBS[(round + attempt) % KNOBS.length];
            double v = knob.values()[RANDOM.nextInt(knob.values().length)];
            if (v != best.get(knob.id())) {
                trial = new LinkedHashMap<>(best);
                trial.put(knob.id(), v);
                String key = trial.toString();
                if (!done.contains(key)) {
                    done.add(key);
                    break;
                }
            }
        }
        return false;
    }

    private static double value(Module m, String id) {
        for (Setting<?> s : m.settings()) {
            if (s.id().equals(id)) {
                return (Double) s.get();
            }
        }
        throw new IllegalStateException("no setting " + id);
    }

    @SuppressWarnings("unchecked")
    private static void apply(Module m, Map<String, Double> values) {
        for (Setting<?> s : m.settings()) {
            Double v = values.get(s.id());
            if (v != null) {
                ((Setting<Double>) s).loadRaw(v);
            }
        }
    }

    private static void arena(java.util.function.Consumer<String> cmd, String me, String scene) {
        cmd.accept("scoreboard players set " + BOT + " difficulty 0");
        cmd.accept("fill 4 61 8 36 78 52 minecraft:air");
        cmd.accept("fill 4 79 8 36 92 52 minecraft:air");
        cmd.accept("fill 4 60 8 36 60 52 minecraft:netherite_block");
        cmd.accept("kill @e[type=!player]");
        double botY = 61;
        double botZ = 28.5;
        if (scene.equals("pillar")) {
            cmd.accept("fill 19 61 27 21 65 29 minecraft:netherite_block");
            botY = 66;
        } else if (scene.equals("far")) {
            botZ = 38.5;
        } else if (scene.equals("shield")) {
            botZ = 26.5;
            cmd.accept("item replace entity " + BOT + " weapon.mainhand with minecraft:shield");
        }
        cmd.accept("tp " + BOT + " 20.5 " + botY + " " + botZ + " 180 0");
        cmd.accept("tp " + me + " 20.5 61 22.5 0 0");
        for (String who : new String[]{BOT, me}) {
            cmd.accept("attribute " + who + " minecraft:max_health base set 1000");
            cmd.accept("effect clear " + who + " minecraft:resistance");
        }
        cmd.accept("attribute " + BOT + " minecraft:knockback_resistance base set 1");
        cmd.accept("gamemode survival " + me);
    }

    private static void loadout(java.util.function.Consumer<String> cmd, String me) {
        cmd.accept("clear " + me);
        String[] hotbar = {
                "mace[enchantments={\"minecraft:density\":5,\"minecraft:wind_burst\":1}]",
                "netherite_axe", "netherite_sword", "wind_charge 64", "ender_pearl 16", "elytra", "totem_of_undying"};
        for (int i = 0; i < hotbar.length; i++) {
            cmd.accept("item replace entity " + me + " hotbar." + i + " with minecraft:" + hotbar[i]);
        }
        cmd.accept("item replace entity " + me + " armor.chest with minecraft:netherite_chestplate");
        cmd.accept("item replace entity " + me + " weapon.offhand with minecraft:totem_of_undying");
    }
}
