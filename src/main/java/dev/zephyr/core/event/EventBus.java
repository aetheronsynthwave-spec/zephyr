package dev.zephyr.core.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal, allocation-free event bus. Listener lists are copy-on-write so firing
 * never allocates and never races with subscription changes from other threads.
 */
public final class EventBus<E> {

    private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<E> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<E> listener) {
        listeners.remove(listener);
    }

    public void fire(E event) {
        List<Consumer<E>> ls = listeners;
        for (int i = 0; i < ls.size(); i++) {
            ls.get(i).accept(event);
        }
    }

    public void clear() {
        listeners.clear();
    }
}
