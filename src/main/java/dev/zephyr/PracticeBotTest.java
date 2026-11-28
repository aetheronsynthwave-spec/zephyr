package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.Setting;
import dev.zephyr.modules.crystal.AutoHitCrystal;
import dev.zephyr.modules.survival.AutoTotem;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.LeverBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * AutoHitCrystal + AutoTotem (hover) against Theobald's Practice Bot (crystal, hard), armed only
 * by -Dzephyr.pbt=1 in a single-player copy of the map. The harness stands in for the player's
 * legs (face, walk, get out of snags; it never eats); every attack, placement and
 * totem restock is the modules'. Ends when the bot has popped {@code lead} more times than we have.
 */
public final class PracticeBotTest {
    private static final boolean ARMED = Boolean.getBoolean("zephyr.pbt");
    private static final Path OUT = Path.of(System.getProperty("zephyr.pbt.dir", "."));
    private static final int LEAD = Integer.getInteger("zephyr.pbt.lead", 5);
    private static final int MAX_FIGHT_TICKS = Integer.getInteger("zephyr.pbt.ticks", 12000);
    private static final boolean TRACE = Boolean.getBoolean("zephyr.pbt.trace");
    private static final String BOT = "TheobaldTheBot";
    /** Stand-in footwork: space (just outside the bot's reach), rush (hold W), reset (rush + jump reset). */
    private static final String STYLE = System.getProperty("zephyr.pbt.style", "space");
    /** AutoHitCrystal setting overrides for comparisons, e.g. "freeOpen=false,maxSelf=8". */
    private static final boolean BENCH = Boolean.getBoolean("zephyr.pbt.bench");
    private static final String LEDGE = System.getProperty("zephyr.pbt.ledge", "");
    /** Another module to run beside AutoHitCrystal, e.g. SafeAnchor. */
    private static final String WITH = System.getProperty("zephyr.pbt.with", "");
    /** "grass": a grass over stone floor that explosions can crater, instead of netherite. */
    private static final boolean GRASS = "grass".equals(System.getProperty("zephyr.pbt.terrain"));
    private static boolean terrainBuilt;
    private static int buildTicks;
    private static volatile int lastHurtTick;
    private static int lastRepairTick;
    private static int repairs;
    private static int sunkTicks;
    // Server thread only: floor blocks found missing, summed over every rebuild.
    private static int blownGrass, blownStone;
    private static int higherTicks;
    private static int onObsidianTicks;
    private static final String SET = System.getProperty("zephyr.pbt.set", "");
    /** Hold-W styles stop pushing inside this horizontal distance. */
    private static final double HUG = Double.parseDouble(System.getProperty("zephyr.pbt.hug", "1.3"));
    private static final BlockPos RESIST_LEVER = new BlockPos(11, 15, 13);

    private static int boot;
    private static int setup;
    private static int fightTicks;
    private static int roundTicks;
    private static int round;
    private static int quit = -1;
    private static int stuck;
    private static int strafeTicks;

    // Written on the server thread, read on the client thread.
    private static volatile boolean botPresent;
    private static volatile boolean botFighting;
    private static volatile boolean meFighting;
    private static volatile boolean resisted;
    private static volatile int botPops, myPops, botDeaths, myDeaths;
    private static volatile float botHp, myHp;

    private static ServerPlayerEntity botRef, meRef;
    private static int botStat, meStat;
    private static boolean botWasAlive = true, meWasAlive = true;
    private static int loggedBotPops, loggedMyPops, loggedBotDeaths, loggedMyDeaths;
    private static int obby0, det0;
    private static final StringBuilder report = new StringBuilder();

    /** Damage and pops by "victim <- source by attacker", so losses can be explained. */
    private static final java.util.Map<String, float[]> damage = new java.util.concurrent.ConcurrentSkipListMap<>();
    private static volatile String lastOnUs = "";
    private static volatile String lastOnBot = "";
    private static boolean listening;
    private static String stageName = "";
    private static final java.util.Map<String, Integer> stageTicks = new java.util.TreeMap<>();

    private PracticeBotTest() {
    }

    public static void tick(MinecraftClient client) {
        if (!ARMED) {
            return;
        }
        try {
            client.options.pauseOnLostFocus = false;
            if (!listening) {
                listening = true;
                ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
                    if (!(entity instanceof ServerPlayerEntity player)) {
                        return;
                    }
                    boolean bot = player.getGameProfile().name().equals(BOT);
                    lastHurtTick = fightTicks;
                    var by = source.getAttacker();
                    String who = by == null ? "nobody" : by.getName().getString().equals(BOT) ? "bot" : "us";
                    String key = (bot ? "bot" : "us") + " <- " + source.getName() + " by " + who;
                    float[] total = damage.computeIfAbsent(key, k -> new float[2]);
                    total[0] += taken;
                    total[1]++;
                    String detail = String.format(Locale.ROOT, "%s %.1f", key, taken);
                    var at = source.getPosition();
                    if (at != null) {
                        detail += String.format(Locale.ROOT, " (from us dx=%.1f dy=%.1f)",
                                Math.hypot(at.x - player.getX(), at.z - player.getZ()), at.y - player.getY());
                    }
                    if (bot) {
                        lastOnBot = detail;
                    } else {
                        lastOnUs = detail;
                    }
                });
            }
            if (client.currentScreen instanceof GameMenuScreen) {
                client.setScreen(null);
            }
            run(client);
            // Nothing decorative while testing: no explosion or totem particles, and no
            // totem animation over the view.
            if (client.world != null) {
                client.particleManager.clearParticles();
                client.gameRenderer.showFloatingItem(net.minecraft.item.ItemStack.EMPTY);
            }
        } catch (Throwable e) {
            log("harness error: " + e);
            e.printStackTrace();
            client.scheduleStop();
        }
    }

    private static void log(String s) {
        System.out.println("[PBT] " + s);
        report.append(s).append('\n');
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("report.txt"), report.toString());
        } catch (Exception e) {
            System.out.println("[PBT] write failed: " + e);
        }
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommandManager()
                .parseAndExecute(server.getCommandSource().withSilent(), command));
    }

    private static void sample(MinecraftServer server, String myName) {
        server.execute(() -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(BOT);
            ServerPlayerEntity me = server.getPlayerManager().getPlayer(myName);
            botPresent = bot != null;
            botFighting = bot != null && !bot.isCreative() && bot.isAlive();
            meFighting = me != null && !me.isCreative() && me.isAlive()
                    && me.interactionManager.getGameMode().isSurvivalLike()
                    && me.getY() > 40;
            if (bot != null) {
                int stat = bot.getStatHandler().getStat(Stats.USED.getOrCreateStat(Items.TOTEM_OF_UNDYING));
                if (bot == botRef && stat > botStat) {
                    botPops += stat - botStat;
                }
                if (bot == botRef && botWasAlive && !bot.isAlive()) {
                    botDeaths++;
                }
                botRef = bot;
                botStat = stat;
                botWasAlive = bot.isAlive();
                botHp = bot.getHealth() + bot.getAbsorptionAmount();
                if (lastBotHp >= 0 && botHp < lastBotHp) {
                    botLost += lastBotHp - botHp;
                }
                lastBotHp = botHp;
            }
            if (me != null) {
                int stat = me.getStatHandler().getStat(Stats.USED.getOrCreateStat(Items.TOTEM_OF_UNDYING));
                if (me == meRef && stat > meStat) {
                    myPops += stat - meStat;
                }
                if (meWasAlive && !me.isAlive()) {
                    myDeaths++;
                }
                meRef = me;
                meStat = stat;
                meWasAlive = me.isAlive();
                myHp = me.getHealth() + me.getAbsorptionAmount();
                resisted = me.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.RESISTANCE)
                        && me.getStatusEffect(net.minecraft.entity.effect.StatusEffects.RESISTANCE)
                        .getAmplifier() >= 2;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void override(Module m) {
        for (String pair : SET.split(",")) {
            String[] kv = pair.split("=");
            for (Setting<?> s : m.settings()) {
                if (kv.length == 2 && s.id().equals(kv[0])) {
                    if (s.get() instanceof Boolean) {
                        ((Setting<Boolean>) s).loadRaw(Boolean.parseBoolean(kv[1]));
                    } else if (s.get() instanceof Double) {
                        ((Setting<Double>) s).loadRaw(Double.parseDouble(kv[1]));
                    }
                    log("setting " + kv[0] + " = " + s.get());
                }
            }
        }
    }

    private static boolean overridden;

    private static void modules(boolean crystalOn) {
        if (!overridden) {
            overridden = true;
            override(ZephyrClient.modules().byName("AutoHitCrystal"));
            StringBuilder in = new StringBuilder("settings in use:");
            for (Setting<?> s : ZephyrClient.modules().byName("AutoHitCrystal").settings()) {
                in.append(' ').append(s.id()).append('=').append(s.get());
            }
            log(in.toString());
        }
        for (Module m : ZephyrClient.modules().all()) {
            boolean want = m instanceof AutoTotem || crystalOn && (m instanceof AutoHitCrystal || m.name().equals(WITH));
            if (m.isEnabled() != want) {
                if (m instanceof AutoTotem) {
                    for (Setting<?> s : m.settings()) {
                        if (s instanceof ModeSetting mode && s.id().equals("mode")) {
                            mode.set("HOVER");
                        }
                    }
                }
                m.setEnabled(want);
            }
        }
    }

    private static void release(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.useKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private static void run(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        MinecraftServer server = client.getServer();
        if (self == null || client.world == null || server == null) {
            return;
        }
        String me = self.getGameProfile().name();
        if (quit >= 0) {
            if (++quit > 40) {
                client.scheduleStop();
            }
            return;
        }
        if (++boot < 100) {
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.ui")) {
            uiTour(client, boot - 100);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.buildmap")) {
            buildMap(client, server);
            return;
        }
        sample(server, me);
        if (!self.isAlive()) {
            self.requestRespawn();
            client.setScreen(null);
            return;
        }
        report();
        if (botPops - myPops >= LEAD || fightTicks >= MAX_FIGHT_TICKS) {
            summary(botPops - myPops >= LEAD ? "GOAL REACHED" : "TIME LIMIT");
            craters(server);
            scoreboard(client, server, me);
            modules(false);
            release(client);
            quit = 0;
            return;
        }
        if (System.getProperty("zephyr.pbt.smart") != null && round > 0) {
            smartRun(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.features") && round > 0 && self.isAlive()) {
            featureRun(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.chase") && round > 0 && self.isAlive()) {
            chaseRun(client, server, self);
            return;
        }
        if (!botFighting || !meFighting) {
            if (roundTicks > 0) {
                log("round " + round + " over after " + roundTicks + " ticks");
                roundTicks = 0;
                setup = 0;
            }
            setupRound(client, server, me);
            return;
        }
        fight(client, server, self);
    }

    /** Puts both players into a crystal / hard round with the requested hotbar. */
    private static void setupRound(MinecraftClient client, MinecraftServer server, String me) {
        modules(false);
        release(client);
        int s = setup++;
        if (s == 20 && !botPresent) {
            log("spawning bot");
            cmd(server, "function practicebot:spawnbot");
        } else if (s == 40) {
            server.execute(() -> {
                var world = server.getOverworld();
                var state = world.getBlockState(RESIST_LEVER);
                if (state.getBlock() instanceof LeverBlock lever && state.get(LeverBlock.POWERED)) {
                    lever.togglePower(state, world, RESIST_LEVER, null);
                }
                log("resistance lever now " + world.getBlockState(RESIST_LEVER));
            });
        } else if (s == 60) {
            cmd(server, "scoreboard players set " + me + " kit 1");
            // The map's crystal button, whole chain (command blocks at -1 12..7 12). Without
            // "remove naked" the map keeps us in adventure, where nothing can be placed.
            cmd(server, "function practicebot:setcrystal");
            if (!GRASS) {
                cmd(server, "fill -192 60 208 208 60 -192 minecraft:netherite_block replace minecraft:grass_block");
            } else if (!terrainBuilt) {
                terrainBuilt = true;
                buildTerrain(server);
            }
            cmd(server, "tag " + BOT + " remove naked");
            cmd(server, "gamerule minecraft:mob_griefing false");
            cmd(server, "scoreboard players set " + BOT + " difficulty 3");
            cmd(server, "function practicebot:changedifficulty");
        } else if (s == 90) {
            // The map's own start chain (command blocks at 4 13..1 15), in the same order.
            cmd(server, "tp " + BOT + " 8.50 61.00 -16.5 0 0");
            cmd(server, "function practicebot:setgamemode");
            cmd(server, "tp " + me + " 8.5 61 8.5 180 0");
            cmd(server, "effect give " + BOT + " minecraft:regeneration 2 255 true");
            cmd(server, "scoreboard players set " + BOT + " combo 0");
            cmd(server, "player " + BOT + " stop");
            cmd(server, "fill 8 61 3 8 62 2 air");
        } else if (s == 110) {
            self(client).getInventory().setSelectedSlot(0);
        } else if (s == 120) {
            cmd(server, "enchant " + me + " minecraft:knockback 1");
            String[] hotbar = {null, "ender_pearl 16", "golden_apple 64", "glowstone 64",
                    "shield[unbreakable={}]", "totem_of_undying", "obsidian 64", "end_crystal 64",
                    "respawn_anchor 64"};
            for (int i = 1; i < hotbar.length; i++) {
                cmd(server, "item replace entity " + me + " hotbar." + i + " with minecraft:" + hotbar[i]);
            }
            cmd(server, "effect give " + me + " minecraft:instant_health 1 5 true");
        } else if (s > 400) {
            log("setup stalled: botPresent=" + botPresent + " botFighting=" + botFighting
                    + " meFighting=" + meFighting + " pos=" + self(client).getBlockPos());
            setup = 0;
        }
    }

    private static ClientPlayerEntity self(MinecraftClient client) {
        return client.player;
    }

    private static void fight(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        AutoHitCrystal crystal = (AutoHitCrystal) ZephyrClient.modules().byName("AutoHitCrystal");
        // Finish the loadout first: the start chain flips both players to survival early.
        if (setup > 0 && setup < 130) {
            setupRound(client, server, self.getGameProfile().name());
            return;
        }
        if (roundTicks++ == 0) {
            round++;
            setup = 0;
            roundTicks = 1;
            StringBuilder inv = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                inv.append(self.getInventory().getStack(i)).append(" | ");
            }
            log("round " + round + " starts: hotbar " + inv + "offhand " + self.getOffHandStack()
                    + " chest " + self.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
            server.execute(() -> {
                var board = server.getScoreboard();
                var objective = board.getNullableObjective("difficulty");
                var score = board.getScore(net.minecraft.scoreboard.ScoreHolder.fromName(BOT), objective);
                log("bot difficulty score " + (score == null ? "unset" : score.getScore()) + " (3 = hard), style "
                        + STYLE + ", overrides '" + SET + "'");
            });
            log("gamemode " + client.interactionManager.getCurrentGameMode() + " effects " + self.getStatusEffects() + " resist block "
                    + server.getOverworld().getBlockState(new BlockPos(11, 15, 18))
                    + " enchants " + self.getInventory().getStack(0).getEnchantments());
            obby0 = crystal.obsidianPlacedCount();
            det0 = crystal.detonatedCount();
        }
        if (roundTicks > 40 && !client.interactionManager.getCurrentGameMode().isSurvivalLike()
                || roundTicks > 40 && !self.getAbilities().allowModifyWorld) {
            log("ABORT: we cannot place blocks (mode " + client.interactionManager.getCurrentGameMode() + ")");
            modules(false);
            release(client);
            quit = 0;
            return;
        }
        if (BENCH) {
            bench(client, server, self);
            return;
        }
        if (System.getProperty("zephyr.pbt.smart") != null) {
            smartRun(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.features")) {
            featureRun(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.chase")) {
            chaseRun(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.speed")) {
            speedCheck(client, server, self);
            return;
        }
        if (Boolean.getBoolean("zephyr.pbt.guard")) {
            guardCheck(client, server, self);
            return;
        }
        if (!LEDGE.isEmpty()) {
            ledgeCheck(client, server, self);
            return;
        }
        fightTicks++;
        resync(server, self.getGameProfile().name());
        // Anchors dig pits the stand-in cannot climb (it has no pearl logic), which would end the
        // fight early. Rebuild the floor and lift both out once either has been two or more
        // blocks down for 5 s, or nobody has been hurt for 8 s.
        if (GRASS) {
            boolean sunk = client.world.getPlayers().stream().anyMatch(p -> p.getY() < 59.5);
            sunkTicks = sunk ? sunkTicks + 1 : 0;
            if (sunkTicks > 100 || fightTicks - Math.max(lastHurtTick, lastRepairTick) > 160) {
                repairFloor(client, server, self);
                sunkTicks = 0;
            }
        }
        if (resisted && roundTicks > 40) {
            log("ABORT: resistance is ON for us, these pops would not count");
            modules(false);
            release(client);
            quit = 0;
            return;
        }
        AbstractClientPlayerEntity bot = client.world.getPlayers().stream()
                .filter(p -> p.getGameProfile().name().equals(BOT)).findFirst().orElse(null);
        double dist = bot == null ? 99 : self.distanceTo(bot);
        modules(true);
        if (bot == null) {
            release(client);
            return;
        }
        String[] parts = crystal.hudLabel().split(" · ");
        String stage = parts.length > 1 ? parts[1] : "?";
        stageName = stage;
        stageTicks.merge(stage, 1, Integer::sum);
        double dx = bot.getX() - self.getX(), dz = bot.getZ() - self.getZ();
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(want - self.getYaw());
        boolean melee = stage.equals("melee");
        if (melee && (dist > 2.8 || Math.abs(delta) > 60f)) {
            self.setYaw(self.getYaw() + Math.max(-20f, Math.min(20f, delta)));
            self.setPitch(self.getPitch() + Math.max(-8f, Math.min(8f, 8f - self.getPitch())));
        }
        // Footwork. The bot only starts its crystals after hitting us from within 2.5 blocks,
        // so stand just outside that, step in (sprinting) when the sword is charged, and
        // never jump: being higher than the opponent is the losing side.
        double flat = Math.hypot(dx, dz);
        boolean charged = self.getAttackCooldownProgress(0.5f) >= 0.9f && bot.hurtTime <= 1;
        boolean facing = Math.abs(delta) < 50f;
        boolean forward = melee && facing && (charged ? flat > 2.75 : flat > 3.8);
        boolean back = !forward && flat < (charged && melee ? 2.65 : 3.5);
        if (back && Math.abs(delta) > 30f && melee) {
            self.setYaw(self.getYaw() + Math.max(-20f, Math.min(20f, delta)));
        }
        client.options.forwardKey.setPressed(forward);
        client.options.sprintKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        // Circle out of snags (obsidian, walls) instead of hopping onto them.
        stuck = self.horizontalCollision ? stuck + 1 : 0;
        if (self.horizontalCollision) {
            strafeTicks = 12;
        }
        if (strafeTicks > 0) {
            strafeTicks--;
        }
        boolean left = strafeTicks > 0 && (round + fightTicks / 200) % 2 == 0;
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(strafeTicks > 0 && !left);
        client.options.jumpKey.setPressed(stuck > 30);
        if (!STYLE.equals("space")) {
            // Keep W held into the bot: less knockback, and the crystal it aims three blocks
            // ahead of itself lands behind us.
            boolean w = flat > HUG && Math.abs(delta) < 60f;
            if (Math.abs(delta) > 25f && melee) {
                self.setYaw(self.getYaw() + Math.max(-20f, Math.min(20f, delta)));
            }
            client.options.forwardKey.setPressed(w);
            client.options.sprintKey.setPressed(w);
            client.options.backKey.setPressed(false);
            if (STYLE.equals("reset")) {
                client.options.jumpKey.setPressed(stuck > 30 || self.hurtTime >= 9 && self.isOnGround());
            }
        }

        if (self.getY() - bot.getY() > 0.4) {
            higherTicks++;
        }
        if (self.isOnGround() && self.getY() > 61.5 && client.world.getBlockState(
                BlockPos.ofFloored(self.getX(), self.getY() - 0.5, self.getZ())).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
            onObsidianTicks++;
        }
        if (TRACE && fightTicks % 4 == 0) {
            log(String.format(Locale.ROOT,
                    "t=%d %s d=%.2f dy=%.2f slot=%d yaw=%.0f pitch=%.0f me=%.1f bot=%.1f det=%d obby=%d",
                    fightTicks, stage, dist, bot.getY() - self.getY(), self.getInventory().getSelectedSlot(),
                    self.getYaw(), self.getPitch(), self.getHealth() + self.getAbsorptionAmount(), botHp, crystal.detonatedCount() - det0,
                    crystal.obsidianPlacedCount() - obby0));
        }
        if (fightTicks % 600 == 0) {
            summary("progress");
            scoreboard(client, server, self.getGameProfile().name());
        }
    }

    private static final java.util.Set<String> ledgeSeen = new java.util.LinkedHashSet<>();
    private static int ledgeTicks;

    /**
     * Staged scene for the ledge rule (-Dzephyr.pbt.ledge=obsidian|plain): the bot is parked
     * on a one block ledge, we stand below, and every obsidian block and crystal is listed.
     */
    private static void ledgeCheck(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        String me = self.getGameProfile().name();
        int s = ledgeTicks++;
        release(client);
        if (s == 0) {
            modules(false);
            cmd(server, "scoreboard players set " + BOT + " difficulty 0");
            cmd(server, "fill 14 61 14 26 66 26 minecraft:air");
            cmd(server, "fill 14 60 14 26 60 26 minecraft:netherite_block");
            cmd(server, "fill 18 61 23 22 61 25 minecraft:netherite_block");
            if (LEDGE.equals("obsidian")) {
                cmd(server, "setblock 19 61 23 minecraft:obsidian");
            }
        }
        if (s < 40 || s % 10 == 0) {
            cmd(server, "player " + BOT + " stop");
            cmd(server, "tp " + BOT + " 20.5 62 23.4 180 0");
            cmd(server, "effect give " + BOT + " minecraft:resistance 5 3 true");
        }
        if (s == 20) {
            cmd(server, "tp " + me + " 20.5 61 20.6 0 10");
        }
        if (s < 40) {
            return;
        }
        modules(true);
        for (BlockPos p : BlockPos.iterate(15, 61, 17, 25, 64, 26)) {
            if (client.world.getBlockState(p).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                ledgeSeen.add("obsidian at " + p.toShortString());
            }
        }
        for (var c : client.world.getEntitiesByClass(net.minecraft.entity.decoration.EndCrystalEntity.class,
                new net.minecraft.util.math.Box(14, 60, 14, 27, 67, 27), e -> true)) {
            ledgeSeen.add("crystal on " + c.getBlockPos().down().toShortString());
        }
        if (s == 200) {
            log("ledge=" + LEDGE + " (ledge top y=62, our feet y=61): " + ledgeSeen);
            modules(false);
            quit = 0;
        }
    }

    private static int benchTicks;

    /**
     * Times the spot search on fixed scenes (-Dzephyr.pbt.bench=true) and prints what it
     * picks, so a faster search can be shown to choose the same blocks.
     */
    private static void bench(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        String me = self.getGameProfile().name();
        int s = benchTicks++;
        release(client);
        modules(false);
        String[] scenes = {"flat", "obsidian", "ledge"};
        int scene = s / 80;
        int step = s % 80;
        if (scene >= scenes.length) {
            quit = 0;
            return;
        }
        if (step == 0) {
            cmd(server, "scoreboard players set " + BOT + " difficulty 0");
            cmd(server, "fill 14 61 14 26 66 26 minecraft:air");
            cmd(server, "fill 14 60 14 26 60 26 minecraft:netherite_block");
            if (scenes[scene].equals("obsidian")) {
                for (String at : new String[]{"19 61 22", "22 61 23", "21 61 24", "18 61 21", "20 61 25"}) {
                    cmd(server, "setblock " + at + " minecraft:obsidian");
                }
            } else if (scenes[scene].equals("ledge")) {
                cmd(server, "fill 18 61 23 22 61 25 minecraft:netherite_block");
            }
        }
        if (step < 60) {
            cmd(server, "player " + BOT + " stop");
            cmd(server, "tp " + BOT + (scenes[scene].equals("ledge") ? " 20.5 62 23.4" : " 20.5 61 23.0") + " 180 0");
            cmd(server, "tp " + me + " 20.5 61 20.6 0 10");
            return;
        }
        if (step != 70) {
            return;
        }
        var bot = client.world.getPlayers().stream()
                .filter(p -> p.getGameProfile().name().equals(BOT)).findFirst().orElse(null);
        if (bot == null) {
            log("bench: no bot");
            return;
        }
        double reach = Math.min(4.0, self.getEntityInteractionRange()) - 0.15;
        java.util.function.Predicate<BlockPos> breakable = cell -> dev.zephyr.core.world.HitGeometry.withinReach(
                self.getEyePos(), new net.minecraft.util.math.Box(cell.getX() - 0.5, cell.getY(), cell.getZ() - 0.5,
                        cell.getX() + 1.5, cell.getY() + 2.0, cell.getZ() + 1.5), reach);
        for (boolean newOnly : new boolean[]{true, false}) {
            // Picks are shown with loose limits so every scene has one; times use the real ones.
            var best = dev.zephyr.core.crystal.CrystalSolver.findOptimalSpot(client.world, self, bot, 4.5, 0.5,
                    20.0, true, newOnly, breakable);
            double micros = Double.MAX_VALUE;
            for (int round = 0; round < 12; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    dev.zephyr.core.combat.DamageCalculator.advanceTick();
                    dev.zephyr.core.crystal.CrystalSolver.findOptimalSpot(client.world, self, bot, 4.5, 3.0,
                            13.0, true, newOnly, breakable);
                }
                micros = Math.min(micros, (System.nanoTime() - start) / 100.0 / 1000.0);
            }
            log(String.format(Locale.ROOT, "bench %s newOnly=%s: %.0f us per search, best=%s", scenes[scene], newOnly,
                    micros, best == null ? "none" : String.format(Locale.ROOT, "%s value %.3f",
                            best.spot().support.toShortString(), best.value())));
        }
    }

    /** Opens every Zephyr screen in turn and screenshots it (-Dzephyr.pbt.ui=true). */
    private static void uiTour(MinecraftClient client, int s) {
        String[] hud = {"HUD", "ArmorHUD", "InfoHUD", "Keystrokes", "SessionHUD", "ProgressBar"};
        if (s == 1) {
            for (String n : hud) {
                Module m = ZephyrClient.modules().byName(n);
                if (m != null) {
                    m.setEnabled(true);
                }
            }
            ZephyrClient.modules().byName("AutoHitCrystal").setEnabled(true);
            ZephyrClient.modules().byName("AutoTotem").setEnabled(true);
            dev.zephyr.gui.ClickGuiScreen.setRenderScale(0.4f);
            client.setScreen(new dev.zephyr.gui.ClickGuiScreen());
        } else if (s == 50) {
            shot(client, "ui-menu.png");
        } else if (s == 60 && client.currentScreen != null) {
            // Right-click AutoHitCrystal's row to show an open settings list.
            dev.zephyr.gui.ClickGuiScreen.expandForTest("AutoHitCrystal");
        } else if (s == 100) {
            shot(client, "ui-settings.png");
        } else if (s == 110) {
            client.setScreen(new dev.zephyr.gui.ConfigScreen(null));
        } else if (s == 140) {
            shot(client, "ui-configs.png");
        } else if (s == 150) {
            client.setScreen(new dev.zephyr.gui.HudEditorScreen());
        } else if (s == 190) {
            shot(client, "ui-hud.png");
        } else if (s == 230) {
            client.scheduleStop();
        }
    }

    private static void probe(MinecraftServer server, String when) {
        server.execute(() -> {
            StringBuilder column = new StringBuilder("floor " + when + " at 12,12 from y=61 down:");
            var world = server.getOverworld();
            for (int y = 61; y >= 50; y--) {
                var block = world.getBlockState(new BlockPos(12, y, 12)).getBlock();
                column.append(' ').append(y).append('=')
                        .append(net.minecraft.registry.Registries.BLOCK.getId(block).getPath());
            }
            log(column.toString());
        });
    }

    /**
     * The map's floor is one grass layer over nothing, so explosions would open holes to the
     * void. Bedrock and eight layers of stone go underneath, into air only: the map keeps a
     * command block column under the middle of the arena.
     */
    private static void buildTerrain(MinecraftServer server) {
        probe(server, "before");
        cmd(server, "fill -64 51 -80 80 51 64 minecraft:bedrock replace minecraft:air");
        for (int y = 52; y <= 59; y++) {
            cmd(server, "fill -64 " + y + " -80 80 " + y + " 64 minecraft:stone replace minecraft:air");
        }
        cmd(server, "fill -64 60 -80 80 60 64 minecraft:grass_block replace minecraft:netherite_block");
        // Craters would otherwise fill the arena with dropped items.
        cmd(server, "gamerule minecraft:block_drops false");
        probe(server, "after");
    }

    /**
     * -Dzephyr.pbt.buildmap=true: builds the floor into the loaded copy, empties the command
     * block that turns grass into netherite, and quits so the world can be saved and shared.
     */
    private static void buildMap(MinecraftClient client, MinecraftServer server) {
        int s = buildTicks++;
        if (s == 0) {
            buildTerrain(server);
        } else if (s == 40) {
            server.execute(() -> {
                var world = server.getOverworld();
                int emptied = 0;
                for (BlockPos p : BlockPos.iterate(-32, 0, -32, 48, 61, 48)) {
                    if (world.getBlockEntity(p) instanceof net.minecraft.block.entity.CommandBlockBlockEntity block
                            && block.getCommandExecutor().getCommand().contains("netherite_block replace")) {
                        block.getCommandExecutor().setCommand("gamerule minecraft:block_drops false");
                        block.markDirty();
                        emptied++;
                    }
                }
                log("netherite floor command blocks replaced: " + emptied);
            });
        } else if (s == 100) {
            client.scheduleStop();
        }
    }

    /** Adds up the floor blocks the explosions removed. Runs on the server thread. */
    private static void countBlown(MinecraftServer server) {
        var world = server.getOverworld();
        for (BlockPos p : BlockPos.iterate(-24, 52, -40, 40, 60, 32)) {
            if (world.getBlockState(p).isAir()) {
                if (p.getY() == 60) {
                    blownGrass++;
                } else {
                    blownStone++;
                }
            }
        }
    }

    private static void repairFloor(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        lastRepairTick = fightTicks;
        repairs++;
        server.execute(() -> countBlown(server));
        for (var player : client.world.getPlayers()) {
            if (player.getY() < 61) {
                cmd(server, String.format(Locale.ROOT, "tp %s %.2f 61 %.2f", player.getGameProfile().name(),
                        player.getX(), player.getZ()));
            }
        }
        for (int y = 52; y <= 59; y++) {
            cmd(server, "fill -64 " + y + " -80 80 " + y + " 64 minecraft:stone replace minecraft:air");
        }
        cmd(server, "fill -64 60 -80 80 60 64 minecraft:grass_block replace minecraft:air");
        log("t=" + fightTicks + " stalled at our y=" + String.format(Locale.ROOT, "%.1f", self.getY())
                + ": floor rebuilt");
    }

    private static void craters(MinecraftServer server) {
        if (!GRASS) {
            return;
        }
        server.execute(() -> {
            countBlown(server);
            log("  floor blown out: " + blownGrass + " grass blocks, " + blownStone + " stone blocks below, "
                    + repairs + " rebuilds");
        });
    }

    private static void chaseRun(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        release(client);
        resync(server, self.getGameProfile().name());
        if (ChaseCheck.tick(client, server, self, PracticeBotTest::log, command -> cmd(server, command))) {
            quit = 0;
        }
    }

    private static void featureRun(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        release(client);
        resync(server, self.getGameProfile().name());
        if (FeatureTour.tick(client, server, self, PracticeBotTest::log, command -> cmd(server, command),
                name -> shot(client, name), () -> botLost)) {
            damage.forEach((key, v) -> log(String.format(Locale.ROOT, "  %s: %.0f dmg in %.0f hits", key, v[0], v[1])));
            quit = 0;
        }
    }

    private static void smartRun(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        release(client);
        resync(server, self.getGameProfile().name());
        for (Module m : ZephyrClient.modules().all()) {
            if (m.isEnabled() && !m.name().equals("SmartMaceBot")) {
                m.setEnabled(false);
            }
        }
        boolean finished = SmartMaceArena.tick(client, server, self,
                "train".equals(System.getProperty("zephyr.pbt.smart")), PracticeBotTest::log,
                command -> cmd(server, command), () -> botLost, () -> total("us <- ", ""));
        if (finished) {
            damage.forEach((key, v) -> log(String.format(Locale.ROOT, "  %s: %.0f dmg in %.0f hits", key, v[0], v[1])));
            quit = 0;
        }
    }

    private static double total(String prefix, String suffix) {
        return damage.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getKey().endsWith(suffix))
                .mapToDouble(e -> e.getValue()[0]).sum();
    }

    /** Health the bot has lost, summed from samples: the damage event misses melee hits on it. */
    private static volatile double botLost;
    private static float lastBotHp = -1;

    private static int resyncTicks;

    /**
     * The map refills the kit on the server in the same tick an item is used, so nothing
     * changes from the server's point of view and the client is never told: its own count
     * runs down to zero. Resend the inventory twice a second.
     */
    private static void resync(MinecraftServer server, String me) {
        if (++resyncTicks % 10 == 0) {
            server.execute(() -> {
                var player = server.getPlayerManager().getPlayer(me);
                if (player != null) {
                    player.currentScreenHandler.syncState();
                }
            });
        }
    }

    private static int speedTicks;
    private static int[] speedBase;

    /**
     * Fixed scene (-Dzephyr.pbt.speed=true): both fighters held 2.4 blocks apart and kept
     * alive, so the only variable is how fast the module works. Counts 600 ticks (30 s).
     */
    private static void speedCheck(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        String me = self.getGameProfile().name();
        AutoHitCrystal m = (AutoHitCrystal) ZephyrClient.modules().byName("AutoHitCrystal");
        int s = speedTicks++;
        release(client);
        if (s == 0) {
            modules(false);
            cmd(server, "scoreboard players set " + BOT + " difficulty 0");
            cmd(server, "fill 12 61 12 28 66 30 minecraft:air");
            cmd(server, "fill 12 60 12 28 60 30 minecraft:netherite_block");
            cmd(server, "tp " + me + " 20.5 61 20.6 0 10");
            // Held in place by attributes: the server drops a player's clicks while a
            // teleport is pending, so the player cannot be teleported every tick.
            for (String who : new String[]{BOT, me}) {
                cmd(server, "attribute " + who + " minecraft:knockback_resistance base set 1");
                cmd(server, "attribute " + who + " minecraft:explosion_knockback_resistance base set 1");
                // Hard to kill without resistance, which would hide blasts under the minimum damage.
                cmd(server, "attribute " + who + " minecraft:max_health base set 1000");
                cmd(server, "effect clear " + who + " minecraft:resistance");
            }
        }
        cmd(server, "player " + BOT + " stop");
        if (s % 5 == 0) {
            cmd(server, "tp " + BOT + " 20.5 61 23.0 180 0");
        }
        for (String who : new String[]{BOT, me}) {
            cmd(server, "effect give " + who + " minecraft:instant_health 1 8 true");
        }
        if (s % 40 == 0) {
            cmd(server, "fill 12 61 12 28 63 30 minecraft:air replace minecraft:obsidian");
            // Crystals left standing would block the next blocks from being placed.
            cmd(server, "kill @e[type=minecraft:end_crystal]");
            cmd(server, "item replace entity " + me + " hotbar.6 with minecraft:obsidian 64");
            cmd(server, "item replace entity " + me + " hotbar.7 with minecraft:end_crystal 64");
        }
        if (s < 40) {
            return;
        }
        modules(true);
        resync(server, me);
        if (s == 60) {
            speedBase = new int[]{m.detonatedCount(), m.obsidianPlacedCount(), m.cycles(), m.hitCounts()[0]};
        }
        if (s % 100 == 99) {
            log(String.format(Locale.ROOT, "speed t=%d stage=%s me=%s hp=%.1f bot=%s det=%d obby=%d", s, m.hudLabel(),
                    self.getBlockPos().toShortString(), self.getHealth(), client.world.getPlayers().stream()
                            .filter(p -> p != self).map(p -> p.getBlockPos().toShortString()).findFirst().orElse("-"),
                    m.detonatedCount(), m.obsidianPlacedCount()));
        }
        if (s == 660) {
            log(String.format(Locale.ROOT, "speed over 30 s: %d crystals detonated (%.1f/s), %d obsidian, %d cycles, "
                            + "%d swings, %.1f ticks from opening to first blast",
                    m.detonatedCount() - speedBase[0], (m.detonatedCount() - speedBase[0]) / 30.0,
                    m.obsidianPlacedCount() - speedBase[1], m.cycles() - speedBase[2],
                    m.hitCounts()[0] - speedBase[3], m.ticksToFirstBlast()));
            modules(false);
            quit = 0;
        }
    }

    private static int guardTicks;
    private static double guardTop;

    /**
     * W and jump held into a one block obsidian step, three times: guard off, guard on with
     * the bot level with us, guard on with the bot well above us. Prints how high we got.
     */
    private static void guardCheck(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self) {
        String me = self.getGameProfile().name();
        String[] cases = {"guard off, bot level", "guard on, bot level", "guard on, bot level, sprinting",
                "guard on, bot 3 above"};
        int s = guardTicks % 100;
        int c = guardTicks / 100;
        guardTicks++;
        release(client);
        if (c >= cases.length) {
            modules(false);
            quit = 0;
            return;
        }
        double botY = c == 3 ? 64 : 61;
        if (s == 0) {
            modules(false);
            guardTop = 0;
            cmd(server, "scoreboard players set " + BOT + " difficulty 0");
            cmd(server, "fill 14 61 14 26 66 28 minecraft:air");
            cmd(server, "fill 14 60 14 26 60 28 minecraft:netherite_block");
            cmd(server, "fill 18 61 22 22 61 22 minecraft:obsidian");
            if (c == 3) {
                cmd(server, "fill 20 63 25 20 63 25 minecraft:netherite_block");
            }
            cmd(server, "clear " + me + " minecraft:end_crystal");
            cmd(server, "clear " + me + " minecraft:obsidian");
        }
        cmd(server, "player " + BOT + " stop");
        cmd(server, "tp " + BOT + " 20.5 " + botY + " 25.5 180 0");
        cmd(server, "effect give " + BOT + " minecraft:resistance 5 4 true");
        if (s < 30) {
            // The sprinting case starts further back, to reach the block at full speed.
            cmd(server, "tp " + me + (c == 2 ? " 20.5 61 16.4" : " 20.5 61 20.4") + " 0 0");
            return;
        }
        for (Setting<?> setting : ZephyrClient.modules().byName("AutoHitCrystal").settings()) {
            if (setting.id().equals("offObsidian")) {
                @SuppressWarnings("unchecked")
                Setting<Boolean> on = (Setting<Boolean>) setting;
                on.loadRaw(c != 0);
            }
        }
        modules(true);
        self.setYaw(0f);
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(true);
        client.options.sprintKey.setPressed(c == 2);
        if (s >= 32 && self.isOnGround()) {
            guardTop = Math.max(guardTop, self.getY());
        }
        if (s == 99) {
            log(String.format(Locale.ROOT, "%s: highest footing y=%.2f (floor 61, obsidian top 62), z=%.1f",
                    cases[c], guardTop, self.getZ()));
        }
    }

    private static void report() {
        if (botPops != loggedBotPops || myPops != loggedMyPops
                || botDeaths != loggedBotDeaths || myDeaths != loggedMyDeaths) {
            loggedBotPops = botPops;
            loggedMyPops = myPops;
            loggedBotDeaths = botDeaths;
            loggedMyDeaths = myDeaths;
            log(String.format(Locale.ROOT, "t=%d bot pops=%d deaths=%d | our pops=%d deaths=%d | hp us=%.1f bot=%.1f",
                    fightTicks, botPops, botDeaths, myPops, myDeaths, myHp, botHp)
                    + " | stage=" + stageName + " | last us: " + lastOnUs + " | last bot: " + lastOnBot);
        }
    }

    private static void shot(MinecraftClient client, String name) {
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, name,
                client.getFramebuffer(), 1, message -> log(message.getString()));
    }

    /** The map's own "pops" sidebar, logged and screenshotted, to check the counts against. */
    private static void scoreboard(MinecraftClient client, MinecraftServer server, String me) {
        server.execute(() -> {
            var board = server.getScoreboard();
            var objective = board.getNullableObjective("pops");
            var mine = board.getScore(net.minecraft.scoreboard.ScoreHolder.fromName(me), objective);
            var theirs = board.getScore(net.minecraft.scoreboard.ScoreHolder.fromName(BOT), objective);
            log("map scoreboard at tick " + fightTicks + ": " + me + "=" + (mine == null ? 0 : mine.getScore())
                    + " " + BOT + "=" + (theirs == null ? 0 : theirs.getScore())
                    + " | harness count: us=" + myPops + " bot=" + botPops);
        });
        shot(client, System.getProperty("zephyr.pbt.label", "run") + "-" + fightTicks + ".png");
    }

    private static void summary(String title) {
        AutoHitCrystal m = (AutoHitCrystal) ZephyrClient.modules().byName("AutoHitCrystal");
        int[] hits = m.hitCounts();
        log(String.format(Locale.ROOT,
                "%s: ticks=%d rounds=%d | bot pops=%d deaths=%d | our pops=%d deaths=%d | swings=%d sprint=%d kb=%d det=%d obby=%d",
                title, fightTicks, round, botPops, botDeaths, myPops, myDeaths, hits[0], hits[1], hits[2],
                m.detonatedCount(), m.obsidianPlacedCount()));
        log("  stage ticks " + stageTicks);
        log(String.format(Locale.ROOT, "  we were higher than the bot %.0f%% of ticks, standing on obsidian %.0f%%",
                100.0 * higherTicks / Math.max(1, fightTicks), 100.0 * onObsidianTicks / Math.max(1, fightTicks)));
        log("  cycles " + m.cycles() + ", obsidian placed " + m.obsidianPlacedCount());
        log("  clean hits followed by obsidian: " + m.builtHits() + " of " + m.cleanHits() + " (" + hits[0] + " swings)");
        damage.forEach((key, v) -> log(String.format(Locale.ROOT, "  %s: %.0f dmg in %.0f hits", key, v[0], v[1])));
    }
}
