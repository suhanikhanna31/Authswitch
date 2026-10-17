package com.authswitch.observer;

import java.io.PrintStream;
import java.util.function.Predicate;

/** Writes one masked line per (filtered) transaction. Raw PANs never reach the log. */
public final class AuditLogger implements TransactionListener {
    private final PrintStream out;
    private final Predicate<TransactionEvent> filter;

    public AuditLogger(PrintStream out, Predicate<TransactionEvent> filter) {
        this.out = out;
        this.filter = filter;
    }

    @Override
    public void onCompleted(TransactionEvent e) {
        if (!filter.test(e)) return;
        out.printf("  [audit] pan=%s amount=%d code=%s path=%s issuer=%s latency=%dus%n",
                e.maskedPan(), e.amountMinor(), e.code().code(), e.path(), e.issuerId(), e.latencyMicros());
    }
}
