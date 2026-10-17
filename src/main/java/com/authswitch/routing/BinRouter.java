package com.authswitch.routing;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Maps the 6-digit BIN of a PAN to its issuer route in O(log n) using a TreeMap of non-overlapping
 * ranges (floorEntry). Immutable once built, so lookups need no locking.
 */
public final class BinRouter {
    private final TreeMap<Integer, Route> byLow = new TreeMap<>();

    public BinRouter(Collection<Route> routes) {
        for (Route r : routes) {
            if (r.binLow() > r.binHigh()) throw new IllegalArgumentException("Inverted BIN range");
            Map.Entry<Integer, Route> below = byLow.floorEntry(r.binHigh());
            if (below != null && below.getValue().binHigh() >= r.binLow()) {
                throw new IllegalArgumentException("Overlapping BIN ranges for " + r.issuer().id());
            }
            Map.Entry<Integer, Route> above = byLow.ceilingEntry(r.binLow());
            if (above != null && above.getKey() <= r.binHigh()) {
                throw new IllegalArgumentException("Overlapping BIN ranges for " + r.issuer().id());
            }
            byLow.put(r.binLow(), r);
        }
    }

    public Optional<Route> find(int bin6) {
        Map.Entry<Integer, Route> e = byLow.floorEntry(bin6);
        if (e == null || e.getValue().binHigh() < bin6) return Optional.empty();
        return Optional.of(e.getValue());
    }

    public Collection<Route> routes() {
        return byLow.values();
    }
}
