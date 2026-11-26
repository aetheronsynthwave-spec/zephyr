package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Fly - vanilla flight controls (double-tap jump). Ported from Donut Client (MIT):
 * authority is granted ONLY on a hosted (integrated server) world. On a remote server this
 * module refuses to enable, because the server would reject it and nothing is spoofed.
 */
public final class Fly extends Module {

    private final NumberSetting flySpeed = num("speed", "Fly speed", 0.02, 0.5, 0.01, 0.05);

    private boolean prevAllow, prevFlying;
    private float prevSpeed;
    private boolean applied;

    public Fly() {
        super("Fly", ModuleCategory.MOVEMENT, 0);
    }

    private static ServerPlayerEntity serverPlayer(MinecraftClient mc) {
        var server = mc.getServer();
        return server == null || mc.player == null ? null : server.getPlayerManager().getPlayer(mc.player.getUuid());
    }

    @Override
    protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            throw new IllegalStateException("no player");
        }
        if (mc.getServer() == null) {
            mc.player.sendMessage(net.minecraft.text.Text.literal(
                    "[Zephyr] Fly only works on a hosted (singleplayer/LAN) world."), true);
            throw new IllegalStateException("remote server");
        }
        PlayerAbilities a = mc.player.getAbilities();
        prevAllow = a.allowFlying;
        prevFlying = a.flying;
        prevSpeed = a.getFlySpeed();
        applied = true;
        apply(mc);
    }

    private void apply(MinecraftClient mc) {
        PlayerAbilities a = mc.player.getAbilities();
        a.allowFlying = true;
        a.setFlySpeed(flySpeed.get().floatValue());
        var server = mc.getServer();
        if (server != null) {
            float sp0 = flySpeed.get().floatValue();
            server.execute(() -> {
                ServerPlayerEntity sp = serverPlayer(mc);
                if (sp != null) {
                    sp.getAbilities().allowFlying = true;
                    sp.getAbilities().setFlySpeed(sp0);
                    sp.sendAbilitiesUpdate();
                }
            });
        }
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (applied && mc.player != null && !mc.player.getAbilities().allowFlying) {
            apply(mc);
        }
    }

    @Override
    protected void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!applied || mc.player == null) {
            applied = false;
            return;
        }
        applied = false;
        boolean creativeLike = mc.player.isCreative() || mc.player.isSpectator();
        PlayerAbilities a = mc.player.getAbilities();
        a.allowFlying = prevAllow || creativeLike;
        a.flying = (prevFlying && a.allowFlying);
        a.setFlySpeed(prevSpeed);
        var server = mc.getServer();
        if (server != null) {
            boolean allow = a.allowFlying, flying = a.flying;
            float ps = prevSpeed;
            server.execute(() -> {
                ServerPlayerEntity sp = serverPlayer(mc);
                if (sp != null) {
                    sp.getAbilities().allowFlying = allow;
                    sp.getAbilities().flying = flying;
                    sp.getAbilities().setFlySpeed(ps);
                    sp.sendAbilitiesUpdate();
                }
            });
        }
    }
}
