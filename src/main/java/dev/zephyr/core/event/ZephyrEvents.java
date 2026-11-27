package dev.zephyr.core.event;

import net.minecraft.network.packet.Packet;

/**
 * The single registry of mixin-fired hooks. Mixins call {@code fire}; modules subscribe
 * through their constructors. Keeping every hook centralized makes the mixin surface
 * tiny and each subsystem trivially detachable.
 */
public final class ZephyrEvents {

    private ZephyrEvents() {
    }

    /** Fired before any outbound play packet. Cancellable. */
    public static final EventBus<PacketSend> PACKET_SEND = new EventBus<>();

    /** Fired at ClientPlayerEntity#tickMovement HEAD, before vanilla movement. Cancellable. */
    public static final EventBus<PlayerMovement> PLAYER_MOVEMENT = new EventBus<>();

    /** Mouse look deltas (cursor dx/dy) after freelook interception. Not cancellable (deltas already consumed). */
    public static final EventBus<LookDelta> LOOK_DELTA = new EventBus<>();

    /** Fired when the local player lands on the ground after a fall (mace timing hook). */
    public static final EventBus<GroundLand> GROUND_LAND = new EventBus<>();

    public static final class PacketSend implements Cancellable {
        public final Packet<?> packet;
        private boolean cancelled;

        public PacketSend(Packet<?> packet) {
            this.packet = packet;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static final class PlayerMovement implements Cancellable {
        public final net.minecraft.client.network.ClientPlayerEntity player;
        private boolean cancelled;

        public PlayerMovement(net.minecraft.client.network.ClientPlayerEntity player) {
            this.player = player;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static final class LookDelta {
        public final double dx;
        public final double dy;

        public LookDelta(double dx, double dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    public static final class GroundLand {
        public final net.minecraft.client.network.ClientPlayerEntity player;
        public final float fallDistance;

        public GroundLand(net.minecraft.client.network.ClientPlayerEntity player, float fallDistance) {
            this.player = player;
            this.fallDistance = fallDistance;
        }
    }
}
