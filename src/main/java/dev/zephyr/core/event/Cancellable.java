package dev.zephyr.core.event;

/**
 * Cancellation contract for hook events fired from mixins.
 */
public interface Cancellable {

    void setCancelled(boolean cancelled);

    boolean isCancelled();
}
