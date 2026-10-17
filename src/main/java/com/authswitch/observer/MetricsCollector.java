package com.authswitch.observer;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free counters (LongAdder) so metrics never become a contention point on the hot path. */
public final class MetricsCollector implements TransactionListener {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();
    private final LongAdder latencySum = new LongAdder();
    private final LongAccumulator latencyMax = new LongAccumulator(Math::max, 0);

    @Override
    public void onCompleted(TransactionEvent e) {
        total.increment();
        latencySum.add(e.latencyMicros());
        latencyMax.accumulate(e.latencyMicros());
        counters.computeIfAbsent("path." + e.path(), k -> new LongAdder()).increment();
        counters.computeIfAbsent("code." + e.code().code() + " " + e.code(), k -> new LongAdder()).increment();
    }

    public long count(String key) {
        LongAdder a = counters.get(key);
        return a == null ? 0 : a.sum();
    }

    public long total() {
        return total.sum();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        long n = total.sum();
        sb.append(String.format("  transactions=%d  avgLatency=%.2fms  maxLatency=%.2fms%n",
                n, n == 0 ? 0.0 : latencySum.sum() / 1000.0 / n, latencyMax.get() / 1000.0));
        Map<String, Long> sorted = new TreeMap<>();
        counters.forEach((k, v) -> sorted.put(k, v.sum()));
        sorted.forEach((k, v) -> sb.append(String.format("  %-34s %d%n", k, v)));
        return sb.toString();
    }
}
