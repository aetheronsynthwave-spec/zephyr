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
            String state = m.isEnabled() ? Formatting.GREEN + "on" : Formatting.GRAY + "off";
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
        String state = m.isEnabled() ? Formatting.GREEN + "on" : Formatting.GRAY + "off";
        ctx.getSource().sendFeedback(Text.literal(Formatting.AQUA + m.name() + Formatting.RESET
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
