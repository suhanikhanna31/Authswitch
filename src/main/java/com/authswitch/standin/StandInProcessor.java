package com.authswitch.standin;

import com.authswitch.issuer.Issuer;
import com.authswitch.issuer.IssuerException;
import com.authswitch.model.AuthRequest;
import com.authswitch.model.IssuerDecision;
import com.authswitch.model.ResponseCode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stand-in processing (STIP): when an issuer is unreachable the network approves small transactions
 * on the issuer's behalf, within strict limits, and later sends the issuer an advice for each one.
 *
 * Limits: a per-transaction cap and a cumulative per-card cap. The cumulative check-and-add is a single
 * atomic ConcurrentHashMap.compute, so concurrent requests on one card can never exceed the cap.
 */
public final class StandInProcessor {
    public record Advice(AuthRequest request, String standInAuthId) {}

    private final long perTxnLimitMinor;
    private final long cumulativeLimitMinor;
    private final ConcurrentHashMap<String, Long> exposureByPan = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Advice>> pendingAdvices = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final LongAdder replayed = new LongAdder();

    public StandInProcessor(long perTxnLimitMinor, long cumulativeLimitMinor) {
        this.perTxnLimitMinor = perTxnLimitMinor;
        this.cumulativeLimitMinor = cumulativeLimitMinor;
    }

    public IssuerDecision decide(AuthRequest r, String issuerId) {
        if (r.amountMinor() > perTxnLimitMinor) {
            return new IssuerDecision(ResponseCode.ISSUER_UNAVAILABLE, "000000");
        }
        AtomicBoolean within = new AtomicBoolean();
        exposureByPan.compute(r.pan(), (pan, current) -> {
            long cur = current == null ? 0L : current;
            if (cur + r.amountMinor() <= cumulativeLimitMinor) {
                within.set(true);
                return cur + r.amountMinor();
            }
            return cur;
        });
        if (!within.get()) {
            return new IssuerDecision(ResponseCode.ISSUER_UNAVAILABLE, "000000");
        }
        String authId = String.format("S%05d", seq.incrementAndGet() % 100_000);
        pendingAdvices.computeIfAbsent(issuerId, k -> new ConcurrentLinkedQueue<>()).add(new Advice(r, authId));
        return new IssuerDecision(ResponseCode.APPROVED, authId);
    }

    /** Delivers queued advices to a recovered issuer. Stops at the first failure and re-queues it. */
    public int replay(String issuerId, Issuer issuer) {
        ConcurrentLinkedQueue<Advice> queue = pendingAdvices.get(issuerId);
        if (queue == null) return 0;
        int delivered = 0;
        Advice a;
        while ((a = queue.poll()) != null) {
            try {
                issuer.advise(a.request(), a.standInAuthId());
                delivered++;
                replayed.increment();
            } catch (IssuerException e) {
                queue.add(a); // order within the queue is not guaranteed to be preserved; advices are commutative
                break;
            }
        }
        if (queue.isEmpty()) exposureByPan.clear(); // issuer is caught up: reset stand-in exposure
        return delivered;
    }

    public int pendingAdvices(String issuerId) {
        ConcurrentLinkedQueue<Advice> q = pendingAdvices.get(issuerId);
        return q == null ? 0 : q.size();
    }

    public long advicesReplayed() {
        return replayed.sum();
    }
}
