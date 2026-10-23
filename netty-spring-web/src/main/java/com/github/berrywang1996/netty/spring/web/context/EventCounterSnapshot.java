package com.github.berrywang1996.netty.spring.web.context;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only event counters carrying the identity of their shared source.
 *
 * <p>Snapshots with the same non-null source object represent the same complete
 * counter set and are counted once during aggregation. Identity comparison is
 * used, not {@link Object#equals(Object)}. A null source leaves the snapshot
 * independent, like a plain map returned by a custom resolver.
 *
 * @since V2.0.0
 */
public final class EventCounterSnapshot extends AbstractMap<String, Object> {

    private final Object source;
    private final Map<String, Object> counters;

    /**
     * @param source the recorder or other shared counter source, or null for independent counters
     * @param counters the counter values to copy (nested values must not be subsequently mutated)
     */
    public EventCounterSnapshot(Object source, Map<String, Object> counters) {
        this.source = source;
        this.counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    /** @return the source identity used to avoid counting shared counters more than once */
    public Object getSource() {
        return source;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return counters.entrySet();
    }
}
