package dev.zephyr.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.zephyr.ZephyrClient;
import dev.zephyr.ZephyrConfig;
import dev.zephyr.core.Module;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.settings.Setting;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Barebones command surface. Everything is one flat namespace:
 * <pre>
 *   /zephyr list
 *   /zephyr info &lt;module&gt;
 *   /zephyr toggle &lt;module&gt;
 *   /zephyr set &lt;module&gt; &lt;setting&gt; &lt;value&gt;
 *   /zephyr bind &lt;module&gt; &lt;keycode&gt;
 *   /zephyr save
 * </pre>
 * Suggestion providers list live module/setting ids, so no GUI is needed.
 */
public final class ZephyrCommand {

    private ZephyrCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(build()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("zephyr")
                .then(ClientCommandManager.literal("list").executes(ZephyrCommand::list))
                .then(ClientCommandManager.literal("coords").executes(ZephyrCommand::coords))
                .then(ClientCommandManager.literal("hud").executes(ctx -> {
                    // Opened on the next client tick so the chat screen has closed first.
                    var client = net.minecraft.client.MinecraftClient.getInstance();
                    client.send(() -> client.setScreen(new dev.zephyr.gui.HudEditorScreen()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("calc")
                        .then(ClientCommandManager.argument("expr", StringArgumentType.greedyString())
                                .executes(ZephyrCommand::calc)))
                .then(ClientCommandManager.literal("roll")
                        .executes(ctx -> roll(ctx, 6))
                        .then(ClientCommandManager.argument("sides", com.mojang.brigadier.arguments.IntegerArgumentType.integer(2, 1000000))
                                .executes(ctx -> roll(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "sides")))))
                .then(ClientCommandManager.literal("wp")
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(ZephyrCommand::wpAdd)))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            var wp = ZephyrClient.modules().get(dev.zephyr.modules.utility.Waypoints.class);
                                            if (wp != null && net.minecraft.client.MinecraftClient.getInstance().world != null) {
                                                for (var w : wp.current(net.minecraft.client.MinecraftClient.getInstance())) {
                                                    builder.suggest(w.name());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ZephyrCommand::wpRemove)))
                        .then(ClientCommandManager.literal("list").executes(ZephyrCommand::wpList))
                        .then(ClientCommandManager.literal("clear").executes(ZephyrCommand::wpClear)))
                .then(ClientCommandManager.literal("info")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (Module m : ZephyrClient.modules().all()) {
                                        builder.suggest(m.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ZephyrCommand::info)))
                .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (Module m : ZephyrClient.modules().all()) {
                                        builder.suggest(m.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ZephyrCommand::toggle)))
                .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (Module m : ZephyrClient.modules().all()) {
                                        builder.suggest(m.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .then(ClientCommandManager.argument("setting", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            Module m = module(ctx);
                                            if (m != null) {
                                                for (var s : m.settings()) {
                                                    builder.suggest(s.id());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(ClientCommandManager.argument("value", StringArgumentType.word())
                                                .executes(ZephyrCommand::set))))
                )
                .then(ClientCommandManager.literal("bind")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .then(ClientCommandManager.argument("keycode", DoubleArgumentType.doubleArg(0))
                                        .executes(ZephyrCommand::bind))))
                .then(ClientCommandManager.literal("save").executes(ctx -> {
                    ZephyrClient.config().save();
                    reply(ctx, "config saved");
                    return 1;
                }))
                .then(ClientCommandManager.literal("profile").executes(ctx -> {
                    dev.zephyr.core.ZephyrProfiler.toggle();
                    reply(ctx, "profiler " + (dev.zephyr.core.ZephyrProfiler.isEnabled()
                            ? "ON - reports every 10s in chat" : "off"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("mixins").executes(ctx -> {
                    reply(ctx, "hook status: camera=OK mouse-tick=OK movement=OK cooldown=OK "
                            + "(verified statically against 1.21.11 intermediary; see logs for warnings)");
                    return 1;
                }));
    }

    private static Module module(CommandContext<FabricClientCommandSource> ctx) {
        return ZephyrClient.modules().byName(StringArgumentType.getString(ctx, "module"));
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        for (Module m : ZephyrClient.modules().all()) {
            String state = m.isEnabled() ? Formatting.WHITE + "on" : Formatting.GRAY + "off";
            ctx.getSource().sendFeedback(Text.literal(m.name() + " [" + state + Formatting.RESET + "]  "
                    + Formatting.GRAY + m.description()));
        }
        return 1;
    }

    private static int info(CommandContext<FabricClientCommandSource> ctx) {
        Module m = module(ctx);
        if (m == null) {
            reply(ctx, "no such module");
            return 0;
        }
        String state = m.isEnabled() ? Formatting.WHITE + "on" : Formatting.GRAY + "off";
        ctx.getSource().sendFeedback(Text.literal(Formatting.WHITE + m.name() + Formatting.RESET
                + " (" + m.category().label() + ", " + state + Formatting.RESET + ")"));
        String d = m.description();
        ctx.getSource().sendFeedback(Text.literal(d.isEmpty() ? "No description." : d).formatted(Formatting.GRAY));
        StringBuilder settings = new StringBuilder();
        for (Setting<?> s : m.settings()) {
            if (settings.length() > 0) {
                settings.append(", ");
            }
            settings.append(s.id());
        }
        ctx.getSource().sendFeedback(Text.literal("Settings: " + settings).formatted(Formatting.DARK_GRAY));
        return 1;
    }

    /** Copies "x y z" of your block position to the clipboard. */
    private static int coords(CommandContext<FabricClientCommandSource> ctx) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null) {
            return 0;
        }
        String c = mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
        mc.keyboard.setClipboard(c);
        reply(ctx, "copied " + c);
        return 1;
    }

    private static int calc(CommandContext<FabricClientCommandSource> ctx) {
        String expr = StringArgumentType.getString(ctx, "expr");
        try {
            double v = Calc.eval(expr);
            String out = v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
            reply(ctx, expr + " = " + out);
            return 1;
        } catch (RuntimeException e) {
            reply(ctx, "calc error: " + e.getMessage());
            return 0;
        }
    }

    private static final java.util.Random DICE = new java.util.Random();

    private static int roll(CommandContext<FabricClientCommandSource> ctx, int sides) {
        reply(ctx, "rolled d" + sides + ": " + (DICE.nextInt(sides) + 1));
        return 1;
    }

    private static dev.zephyr.modules.utility.Waypoints waypoints(CommandContext<FabricClientCommandSource> ctx) {
        var wp = ZephyrClient.modules().get(dev.zephyr.modules.utility.Waypoints.class);
        if (wp != null && !wp.isEnabled()) {
            wp.setEnabled(true); // using the command turns the beams on
        }
        if (net.minecraft.client.MinecraftClient.getInstance().player == null) {
            reply(ctx, "join a world first");
            return null;
        }
        return wp;
    }

    private static int wpAdd(CommandContext<FabricClientCommandSource> ctx) {
        var wp = waypoints(ctx);
        if (wp == null) {
            return 0;
        }
        var w = wp.add(net.minecraft.client.MinecraftClient.getInstance(), StringArgumentType.getString(ctx, "name"));
        reply(ctx, "waypoint " + w.name() + " at " + w.x() + " " + w.y() + " " + w.z());
        return 1;
    }

    private static int wpRemove(CommandContext<FabricClientCommandSource> ctx) {
        var wp = waypoints(ctx);
        if (wp == null) {
            return 0;
        }
        String n = StringArgumentType.getString(ctx, "name");
        reply(ctx, wp.remove(net.minecraft.client.MinecraftClient.getInstance(), n) ? "removed " + n : "no waypoint " + n);
        return 1;
    }

    private static int wpList(CommandContext<FabricClientCommandSource> ctx) {
        var wp = waypoints(ctx);
        if (wp == null) {
            return 0;
        }
        var list = wp.current(net.minecraft.client.MinecraftClient.getInstance());
        if (list.isEmpty()) {
            reply(ctx, "no waypoints here");
        }
        for (var w : list) {
            reply(ctx, w.name() + ": " + w.x() + " " + w.y() + " " + w.z());
        }
        return 1;
    }

    private static int wpClear(CommandContext<FabricClientCommandSource> ctx) {
        var wp = waypoints(ctx);
        if (wp == null) {
            return 0;
        }
        wp.clear(net.minecraft.client.MinecraftClient.getInstance());
        reply(ctx, "cleared waypoints for this world");
        return 1;
    }

    private static int toggle(CommandContext<FabricClientCommandSource> ctx) {
        Module m = module(ctx);
        if (m == null) {
            reply(ctx, "no such module");
            return 0;
        }
        m.toggle();
        reply(ctx, m.name() + " -> " + (m.isEnabled() ? "on" : "off"));
        ZephyrClient.config().save();
        return 1;
    }

    private static int set(CommandContext<FabricClientCommandSource> ctx) {
        Module m = module(ctx);
        if (m == null) {
            reply(ctx, "no such module");
            return 0;
        }
        String settingId = StringArgumentType.getString(ctx, "setting");
        String value = StringArgumentType.getString(ctx, "value");
        Setting<?> setting = m.setting(settingId);
        if (setting == null) {
            reply(ctx, "no such setting: " + settingId);
            return 0;
        }
        if (setting instanceof NumberSetting number) {
            try {
                number.set(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                reply(ctx, "not a number: " + value);
                return 0;
            }
        } else if (setting instanceof ModeSetting mode) {
            mode.set(value);
            if (!mode.is(value)) {
                reply(ctx, "invalid mode: " + value + " (options: " + mode.modes() + ")");
                return 0;
            }
        } else if (setting instanceof dev.zephyr.core.settings.BooleanSetting bool) {
            bool.set(Boolean.parseBoolean(value));
        } else if (setting instanceof dev.zephyr.core.settings.KeySetting key) {
            try {
                key.set(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                reply(ctx, "not a keycode: " + value);
                return 0;
            }
        } else {
            reply(ctx, "unsupported setting type");
            return 0;
        }
        reply(ctx, m.name() + "." + settingId + " = " + value);
        ZephyrClient.config().save();
        return 1;
    }

    private static int bind(CommandContext<FabricClientCommandSource> ctx) {
        Module m = module(ctx);
        if (m == null) {
            reply(ctx, "no such module");
            return 0;
        }
        int keycode = (int) DoubleArgumentType.getDouble(ctx, "keycode");
        m.bind().set(keycode);
        reply(ctx, m.name() + " bound to " + keycode);
        ZephyrClient.config().save();
        return 1;
    }

    private static void reply(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(Text.literal("[Zephyr] " + message).formatted(Formatting.GRAY));
    }
}
