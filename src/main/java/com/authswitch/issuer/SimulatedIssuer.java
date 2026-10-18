package com.authswitch.issuer;

import com.authswitch.model.AuthRequest;
import com.authswitch.model.IssuerDecision;
import com.authswitch.model.ResponseCode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory issuer host with per-card balances, configurable latency and failure injection.
 * Balance debits use a CAS loop, so concurrent authorizations on one card can never overspend.
 */
public final class SimulatedIssuer implements Issuer {
    public enum Mode { HEALTHY, SLOW, DOWN }

    private final String id;
    private final long defaultBalanceMinor;
    private final ConcurrentHashMap<String, AtomicLong> balances = new ConcurrentHashMap<>();
    private final AtomicLong authSeq = new AtomicLong();
    private final LongAdder authCalls = new LongAdder();
    private final LongAdder advicesReceived = new LongAdder();

    private volatile Mode mode = Mode.HEALTHY;
    private volatile long latencyMs;
    private volatile long slowLatencyMs = 1_000;

    public SimulatedIssuer(String id, long defaultBalanceMinor, long latencyMs) {
        this.id = id;
        this.defaultBalanceMinor = defaultBalanceMinor;
        this.latencyMs = latencyMs;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public void resetBalances() {
        balances.clear();
    }

    public void setSlowLatencyMs(long ms) {
        this.slowLatencyMs = ms;
    }

    public long authCalls() {
        return authCalls.sum();
    }

    public long advicesReceived() {
        return advicesReceived.sum();
    }

    public long balanceOf(String pan) {
        AtomicLong b = balances.get(pan);
        return b == null ? defaultBalanceMinor : b.get();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public IssuerDecision authorize(AuthRequest r) throws IssuerException {
        authCalls.increment();
        simulateNetwork();
        AtomicLong balance = balances.computeIfAbsent(r.pan(), k -> new AtomicLong(defaultBalanceMinor));
        while (true) {
            long current = balance.get();
            if (current < r.amountMinor()) {
                return new IssuerDecision(ResponseCode.INSUFFICIENT_FUNDS, "000000");
            }
            if (balance.compareAndSet(current, current - r.amountMinor())) {
                return new IssuerDecision(ResponseCode.APPROVED, String.format("%06d", authSeq.incrementAndGet()));
            }
        }
    }

    @Override
    public void advise(AuthRequest r, String standInAuthId) throws IssuerException {
        if (mode == Mode.DOWN) throw new IssuerException(id + " connection refused");
        balances.computeIfAbsent(r.pan(), k -> new AtomicLong(defaultBalanceMinor)).addAndGet(-r.amountMinor());
        advicesReceived.increment();
    }

    private void simulateNetwork() throws IssuerException {
        Mode m = mode;
        if (m == Mode.DOWN) throw new IssuerException(id + " connection refused");
        long delay = m == Mode.SLOW ? slowLatencyMs : latencyMs;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // timeout cancelled us: restore flag and bail out
                throw new IssuerException(id + " call interrupted");
            }
        }
    }
}
