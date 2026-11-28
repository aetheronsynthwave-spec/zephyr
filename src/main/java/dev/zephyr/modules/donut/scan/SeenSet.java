package dev.zephyr.modules.donut.scan;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A bounded set that keeps what was found earlier. An entry leaves only after two scans in
 * a row miss it while {@code near} says it can be checked; the oldest go first when full.
 */
public final class SeenSet<K> {

    private final int limit;
    private final Map<K, Integer> misses = new LinkedHashMap<>();

    public SeenSet(int limit) {
        this.limit = limit;
    }

    public void update(Collection<K> found, Predicate<K> near) {
        Set<K> now = Set.copyOf(found);
        Iterator<Map.Entry<K, Integer>> old = misses.entrySet().iterator();
        while (old.hasNext()) {
            Map.Entry<K, Integer> entry = old.next();
            if (now.contains(entry.getKey())) {
                entry.setValue(0);
            } else if (near.test(entry.getKey())) {
                entry.setValue(entry.getValue() + 1);
                if (entry.getValue() >= 2) {
                    old.remove();
                }
            }
        }
        for (K key : found) {
            misses.putIfAbsent(key, 0);
        }
        Iterator<K> first = misses.keySet().iterator();
        while (misses.size() > limit && first.hasNext()) {
            first.next();
            first.remove();
        }
    }

    public Set<K> all() {
        return Collections.unmodifiableSet(misses.keySet());
    }

    public void clear() {
        misses.clear();
    }
}
