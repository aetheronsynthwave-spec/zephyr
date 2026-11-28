package dev.zephyr;

import dev.zephyr.core.Module;
import dev.zephyr.core.settings.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * One pass over the newer features in the practice world (-Dzephyr.pbt.features=true, run
 * from {@link PracticeBotTest}): screenshots for what is seen, numbers for what acts.
 */
final class FeatureTour {

    private static final String BOT = "TheobaldTheBot";
    private static int tick;
    private static double dealt0;
    private static boolean jumped;
    private static boolean stunned;

    private FeatureTour() {
    }

    /** @return true when finished */
    static boolean tick(MinecraftClient client, MinecraftServer server, ClientPlayerEntity self, Consumer<String> log,
                        Consumer<String> cmd, Consumer<String> shot, java.util.function.DoubleSupplier dealt) {
        String me = self.getGameProfile().name();
        int s = tick++;
        // "stop" would cancel the punch and the raised shield the later checks ask the bot for.
        if (s < 790 || s > 1100) {
            cmd.accept("player " + BOT + " stop");
        }
        if (s % 20 == 0) {
            cmd.accept("effect give " + me + " minecraft:instant_health 1 5 true");
        }
        if (s == 0) {
            all(false);
            // Difficulty 0, or the bot keeps crystalling through the checks.
            cmd.accept("scoreboard players set " + BOT + " difficulty 0");
            cmd.accept("kill @e[type=minecraft:end_crystal]");
            cmd.accept("fill 4 61 4 36 78 48 minecraft:air");
            cmd.accept("fill 4 60 4 36 60 48 minecraft:grass_block");
            cmd.accept("tp " + BOT + " 20.5 61 40.5 180 0");
            cmd.accept("time set midnight");
            cmd.accept("gamemode survival " + me);
        }

        // Skies: look up and across, each style for three seconds.
        String[] styles = {"AURORA", "NEBULA", "GALAXY", "MONO"};
        for (int i = 0; i < styles.length; i++) {
            int at = 20 + i * 60;
            if (s == at) {
                cmd.accept("tp " + me + " 20.5 61 10.5 0 -32");
                set("CustomSky", "style", styles[i]);
                on("CustomSky", true);
            } else if (s == at + 50) {
                shot.accept("sky-" + styles[i].toLowerCase(Locale.ROOT) + ".png");
            }
        }

        // Lyrics in the world.
        if (s == 270) {
            on("CustomSky", false);
            cmd.accept("time set noon");
            cmd.accept("tp " + me + " 20.5 61 10.5 0 -50");
            set("SpotifyLyrics", "display", "SKY");
            on("SpotifyLyrics", true);
        } else if (s == 320) {
            shot.accept("lyrics-sky.png");
            set("SpotifyLyrics", "display", "3D");
            cmd.accept("tp " + me + " 20.5 61 10.5 0 -8");
        } else if (s == 370) {
            shot.accept("lyrics-3d.png");
            on("SpotifyLyrics", false);
        }

        if (s > 272 && s < 370 && s % 10 == 0) {
            // Repeated: the module's own Spotify poll would otherwise put its status back.
            ((dev.zephyr.modules.hud.SpotifyLyrics) ZephyrClient.modules().byName("SpotifyLyrics")).showSample(
                    "Test Artist - Test Song", "first line of the song", "this is the line being sung now",
                    "and this one is next", "last line");
        }

        // Cosmetics from behind and the front, then the hands.
        if (s == 380) {
            cmd.accept("tp " + me + " 20.5 61 10.5 0 10");
            set("Cosmetics", "hat", true);
            on("Cosmetics", true);
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        } else if (s == 420) {
            shot.accept("cosmetics-back.png");
            client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        } else if (s == 450) {
            shot.accept("cosmetics-front.png");
            client.options.setPerspective(Perspective.FIRST_PERSON);
            on("Cosmetics", false);
            cmd.accept("clear " + me);
            set("HandChams", "green", 90.0);
            on("HandChams", true);
        } else if (s == 490) {
            shot.accept("handchams.png");
            on("HandChams", false);
        }

        // X-ray: ores buried in stone under our feet.
        if (s == 500) {
            cmd.accept("fill 12 50 8 28 59 24 minecraft:stone");
            cmd.accept("fill 18 55 13 19 56 14 minecraft:diamond_ore");
            cmd.accept("fill 22 54 17 23 54 18 minecraft:gold_ore");
            cmd.accept("setblock 16 57 17 minecraft:ancient_debris");
            cmd.accept("setblock 24 57 12 minecraft:chest");
            cmd.accept("tp " + me + " 20.5 68 8.5 0 55");
            cmd.accept("effect give " + me + " minecraft:levitation 6 0 true");
            cmd.accept("effect give " + me + " minecraft:resistance 30 4 true");
        } else if (s == 520) {
            on("XRay", true);
        } else if (s == 580) {
            shot.accept("xray.png");
            on("XRay", false);
            cmd.accept("effect clear " + me);
        } else if (s == 620) {
            shot.accept("xray-off.png");
        }

        // AuctionFlipper: a chest titled like the auction house, with one underpriced stack.
        if (s == 630) {
            cmd.accept("fill 12 61 8 28 70 24 minecraft:air");
            cmd.accept("fill 12 60 8 28 60 24 minecraft:grass_block");
            cmd.accept("setblock 20 61 13 minecraft:chest[facing=north]{CustomName:\"Auction House\"}");
            for (int slot = 0; slot < 8; slot++) {
                int price = slot == 5 ? 20000 : 100000 + slot * 1500;
                cmd.accept("item replace block 20 61 13 container." + slot + " with minecraft:diamond[lore=[\"Price: $"
                        + price + "\"]] 1");
            }
            cmd.accept("tp " + me + " 20.5 61 11.2 0 30");
            on("AuctionFlipper", true);
        } else if (s == 670) {
            client.interactionManager.interactBlock(self, Hand.MAIN_HAND,
                    new BlockHitResult(new Vec3d(20.5, 61.5, 13.0), Direction.NORTH, new BlockPos(20, 61, 13), false));
        } else if (s == 710) {
            log.accept("auction screen: " + (client.currentScreen == null ? "none"
                    : client.currentScreen.getTitle().getString()));
            shot.accept("auction.png");
        } else if (s == 720) {
            self.closeHandledScreen();
            on("AuctionFlipper", false);
            cmd.accept("setblock 20 61 13 minecraft:air");
        }

        // JumpReset: a punch from the bot should make us jump; damage with nobody there should not.
        if (s == 730) {
            client.options.setPerspective(Perspective.FIRST_PERSON);
            on("JumpReset", true);
            set("JumpReset", "chance", 1.0);
            cmd.accept("tp " + me + " 20.5 61 20.5 0 0");
            cmd.accept("tp " + BOT + " 20.5 61 22.4 180 0");
            jumped = false;
        } else if (s == 770) {
            jumped = false;
            cmd.accept("damage " + me + " 2 minecraft:magic");
        } else if (s > 770 && s < 800) {
            jumped |= !self.isOnGround() && self.getVelocity().y > 0.2;
        } else if (s == 800) {
            log.accept("jumpreset: magic damage with no attacker -> jumped=" + jumped + " (want false)");
            jumped = false;
            cmd.accept("player " + BOT + " attack once");
        } else if (s > 800 && s < 830) {
            jumped |= !self.isOnGround() && self.getVelocity().y > 0.2;
        } else if (s == 830) {
            log.accept("jumpreset: punch from the bot -> jumped=" + jumped + " (want true)");
            on("JumpReset", false);
        }

        // AutoMace: drop on a blocking target. The axe must open the shield, then the mace lands.
        if (s == 840) {
            cmd.accept("attribute " + BOT + " minecraft:max_health base set 1000");
            cmd.accept("attribute " + BOT + " minecraft:knockback_resistance base set 1");
            // Main hand: the map keeps putting a totem back in the off hand.
            cmd.accept("item replace entity " + BOT + " weapon.mainhand with minecraft:shield");
            cmd.accept("clear " + me);
            cmd.accept("item replace entity " + me + " hotbar.0 with minecraft:netherite_sword");
            cmd.accept("item replace entity " + me + " hotbar.1 with minecraft:netherite_axe");
            cmd.accept("item replace entity " + me + " hotbar.2 with minecraft:mace[enchantments={\"minecraft:density\":5}]");
            cmd.accept("tp " + BOT + " 20.5 61 22.5 180 0");
            self.getInventory().setSelectedSlot(0);
            on("AutoMace", true);
        }
        if (s > 840 && s < 1100) {
            cmd.accept("player " + BOT + " use continuous");
        }
        if (s == 900 || s == 1000) {
            cmd.accept("effect give " + BOT + " minecraft:instant_health 1 8 true");
            cmd.accept("effect give " + me + " minecraft:resistance 15 4 true");
            cmd.accept("tp " + me + " 20.5 74 21.2 0 90");
            dealt0 = dealt.getAsDouble();
            stunned = false;
        }
        if (s == 899 || s == 925) {
            server.execute(() -> {
                var bot = server.getPlayerManager().getPlayer(BOT);
                var us = server.getPlayerManager().getPlayer(me);
                log.accept("automace server: bot gm=" + bot.interactionManager.getGameMode() + " invulnerable="
                        + bot.getAbilities().invulnerable + "/" + bot.isInvulnerable() + " hp=" + bot.getHealth()
                        + " effects=" + bot.getStatusEffects() + " pos=" + bot.getBlockPos().toShortString() + " offhand="
                        + bot.getOffHandStack().getItem() + " | us gm=" + us.interactionManager.getGameMode() + " pos="
                        + us.getBlockPos().toShortString() + " pvp=" + server.getOverworld().getGameRules()
                        .getValue(net.minecraft.world.rule.GameRules.PVP));
            });
        }
        if ((s == 912 || s == 916 || s == 920 || s == 1012)) {
            var other = client.world.getPlayers().stream().filter(p -> p != self).findFirst().orElse(null);
            log.accept(String.format(Locale.ROOT, "automace t=%d y=%.1f fall=%.1f dist=%.1f held=%s hotbar2=%s enabled=%s botBlocking=%s",
                    s, self.getY(), self.fallDistance, other == null ? -1f : self.distanceTo(other),
                    self.getMainHandStack().getItem(), self.getInventory().getStack(2).getItem(),
                    ZephyrClient.modules().byName("AutoMace").isEnabled(), other != null && other.isBlocking()));
        }
        if ((s > 900 && s < 960 || s > 1000 && s < 1060)) {
            var bot = client.world.getPlayers().stream().filter(p -> p != self).findFirst().orElse(null);
            stunned |= bot != null && bot.isBlocking() && self.distanceTo(bot) < 4.0f;
        }
        if (s == 960 || s == 1060) {
            log.accept(String.format(Locale.ROOT, "automace drop onto a shield: dealt %.0f (blocked while near: %s)",
                    dealt.getAsDouble() - dealt0, stunned));
        }
        if (s == 1100) {
            on("AutoMace", false);
        }

        // DonutSMP finders: things behind a solid wall and under the floor.
        if (s == 1104) {
            cmd.accept("fill 8 61 6 32 70 34 minecraft:air");
            cmd.accept("fill 8 60 6 32 60 34 minecraft:grass_block");
            cmd.accept("setblock 17 61 22 minecraft:chest");
            cmd.accept("setblock 19 61 22 minecraft:barrel");
            cmd.accept("setblock 21 61 22 minecraft:shulker_box");
            cmd.accept("setblock 23 61 22 minecraft:ender_chest");
            cmd.accept("fill 18 50 24 23 55 28 minecraft:stone");
            cmd.accept("fill 19 52 25 22 52 27 minecraft:budding_amethyst");
            cmd.accept("fill 19 53 25 22 53 27 minecraft:amethyst_cluster");
            // A wall between us and all of it.
            cmd.accept("fill 12 61 18 28 66 18 minecraft:stone");
            cmd.accept("tp " + me + " 20.5 61 12.5 0 22");
            client.options.setPerspective(Perspective.FIRST_PERSON);
            on("StorageESP", true);
            on("AmethystFinder", true);
            set("StorageESP", "colors", true);
            set("AmethystFinder", "colors", true);
        } else if (s == 1190) {
            var storage = (dev.zephyr.modules.donut.StorageESP) ZephyrClient.modules().byName("StorageESP");
            var amethyst = (dev.zephyr.modules.donut.AmethystFinder) ZephyrClient.modules().byName("AmethystFinder");
            log.accept("storageesp: " + storage.count() + " containers found (4 placed behind the wall)");
            log.accept("amethystfinder: " + amethyst.blockCount() + " blocks in " + amethyst.geodeCount()
                    + " geode(s) (24 blocks placed under the floor)");
            shot.accept("donut-esp.png");
        } else if (s == 1200) {
            on("StorageESP", false);
            on("AmethystFinder", false);
            shot.accept("donut-esp-off.png");
        }

        // The menu with its new size control.
        if (s == 1210) {
            dev.zephyr.gui.ClickGuiScreen.setRenderScale(0.4f);
            client.setScreen(new dev.zephyr.gui.ClickGuiScreen());
        } else if (s == 1248) {
            log.accept("menu screen before shot: " + (client.currentScreen == null ? "none"
                    : client.currentScreen.getClass().getSimpleName()) + ", perspective " + client.options.getPerspective());
        } else if (s == 1250) {
            shot.accept("menu.png");
        } else if (s == 1260) {
            client.setScreen(null);
        } else if (s > 1280) {
            all(false);
            return true;
        }
        return false;
    }

    private static void on(String name, boolean enabled) {
        Module m = ZephyrClient.modules().byName(name);
        if (m.isEnabled() != enabled) {
            m.setEnabled(enabled);
        }
    }

    private static void all(boolean enabled) {
        for (Module m : ZephyrClient.modules().all()) {
            if (m.isEnabled()) {
                m.setEnabled(enabled);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void set(String module, String id, Object value) {
        for (Setting<?> setting : ZephyrClient.modules().byName(module).settings()) {
            if (setting.id().equals(id)) {
                ((Setting<Object>) setting).loadRaw(value);
                return;
            }
        }
        throw new IllegalStateException(module + " has no setting " + id);
    }
}
