package com.authswitch.core;

import com.authswitch.issuer.Issuer;
import com.authswitch.iso.Iso8583Codec;
import com.authswitch.iso.Iso8583Message;
import com.authswitch.iso.MessageFormatException;
import com.authswitch.model.AuthRequest;
import com.authswitch.model.IssuerDecision;
import com.authswitch.model.PanUtil;
import com.authswitch.model.ResponseCode;
import com.authswitch.observer.TransactionEvent;
import com.authswitch.observer.TransactionEvent.Path;
import com.authswitch.observer.TransactionListener;
import com.authswitch.resilience.CircuitBreaker;
import com.authswitch.routing.BinRouter;
import com.authswitch.routing.Route;
import com.authswitch.standin.StandInProcessor;
import com.authswitch.validation.ValidationHandler;
import com.authswitch.validation.Validators;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * A miniature payment authorization switch. For each 0100 request it:
 * <ol>
 *   <li>decodes the ISO 8583 message and validates it (Chain of Responsibility),</li>
 *   <li>de-duplicates retransmissions so one transaction hits the issuer at most once (idempotency),</li>
 *   <li>routes by BIN to the owning issuer and calls it on a bounded worker pool with a hard timeout,</li>
 *   <li>guards each issuer with its own circuit breaker, and</li>
 *   <li>falls back to stand-in processing when the issuer is slow, failing or the pool is saturated.</li>
 * </ol>
 * The returned future always completes with a 0110 wire message; it never completes exceptionally for
 * business or infrastructure failures, because a switch must always answer.
 */
public final class AuthorizationSwitch implements AutoCloseable {

    private final Iso8583Codec codec = new Iso8583Codec();
    private final BinRouter router;
    private final ValidationHandler validation;
    private final ExecutorService issuerPool;
    private final ScheduledExecutorService housekeeping;
    private final long issuerTimeoutMs;
    private final StandInProcessor standIn;
    private final Clock clock;
    private final long idempotencyTtlMs;
    private final List<TransactionListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, Entry> idempotency = new ConcurrentHashMap<>();
    private final LongAdder duplicates = new LongAdder();

    private record Entry(CompletableFuture<String> future, long createdAtMs) {}

    private AuthorizationSwitch(Builder b) {
        this.clock = b.clock;
        this.issuerTimeoutMs = b.issuerTimeoutMs;
        this.idempotencyTtlMs = b.idempotencyTtlMs;
        this.listeners.addAll(b.listeners);
        this.validation = Validators.standardChain(b.clock, b.maxAmountMinor);
        this.standIn = new StandInProcessor(b.standInPerTxnMinor, b.standInCumulativeMinor);

        // Bounded queue + AbortPolicy = explicit back-pressure instead of unbounded memory growth.
        this.issuerPool = new ThreadPoolExecutor(b.workerThreads, b.workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(b.queueCapacity), namedDaemons("issuer-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor hk = new ScheduledThreadPoolExecutor(2, namedDaemons("housekeeping"));
        hk.setRemoveOnCancelPolicy(true);
        this.housekeeping = hk;

        StandInProcessor stip = this.standIn;
        ScheduledExecutorService hkLocal = this.housekeeping;
        List<Route> routes = new ArrayList<>();
        for (Builder.RouteSpec spec : b.routes) {
            Issuer issuer = spec.issuer();
            CircuitBreaker breaker = new CircuitBreaker(b.breakerThreshold, b.breakerOpenMs, b.clock::millis, state -> {
                if (state == CircuitBreaker.State.CLOSED) { // issuer is back: push stand-in advices to it
                    try {
                        hkLocal.execute(() -> stip.replay(issuer.id(), issuer));
                    } catch (RejectedExecutionException ignored) {
                        // switch is shutting down
                    }
                }
            });
            routes.add(new Route(spec.low(), spec.high(), issuer, breaker));
        }
        this.router = new BinRouter(routes);

        long ttl = b.idempotencyTtlMs;
        housekeeping.scheduleAtFixedRate(this::purgeIdempotencyStore, ttl, ttl, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ public API

    public CompletableFuture<String> process(String wire) {
        long start = System.nanoTime();
        Iso8583Message msg;
        AuthRequest req;
        try {
            msg = codec.decode(wire);
            req = AuthRequest.from(msg);
        } catch (MessageFormatException | NumberFormatException e) {
            return CompletableFuture.completedFuture(formatError(start));
        }

        CompletableFuture<String> fresh = new CompletableFuture<>();
        Entry existing = idempotency.putIfAbsent(req.idempotencyKey(), new Entry(fresh, clock.millis()));
        if (existing != null) { // retransmission: share the original (possibly still in-flight) answer
            duplicates.increment();
            return existing.future();
        }
        try {
            run(msg, req, start, fresh);
        } catch (RuntimeException unexpected) {
            complete(fresh, msg, req, ResponseCode.SYSTEM_MALFUNCTION, "000000", "-", Path.ISSUER, start);
        }
        return fresh;
    }

    public void addListener(TransactionListener l) {
        listeners.add(l);
    }

    public long duplicatesSuppressed() {
        return duplicates.sum();
    }

    public int idempotencyStoreSize() {
        return idempotency.size();
    }

    public StandInProcessor standIn() {
        return standIn;
    }

    public CircuitBreaker.State breakerState(String issuerId) {
        for (Route r : router.routes()) {
            if (r.issuer().id().equals(issuerId)) return r.breaker().state();
        }
        throw new IllegalArgumentException("Unknown issuer " + issuerId);
    }

    @Override
    public void close() {
        issuerPool.shutdownNow();
        housekeeping.shutdownNow();
    }

    // ------------------------------------------------------------------ pipeline

    private void run(Iso8583Message msg, AuthRequest req, long start, CompletableFuture<String> out) {
        Optional<ResponseCode> rejected = validation.validate(req);
        if (rejected.isPresent()) {
            complete(out, msg, req, rejected.get(), "000000", "-", Path.VALIDATION, start);
            return;
        }
        Optional<Route> found = router.find(req.bin6());
        if (found.isEmpty()) {
            complete(out, msg, req, ResponseCode.NO_SUCH_ISSUER, "000000", "-", Path.NO_ROUTE, start);
            return;
        }
        Route route = found.get();
        if (!route.breaker().tryAcquire()) { // fail fast: don't even queue work for a known-bad issuer
            completeViaStandIn(out, msg, req, route, start);
            return;
        }
        dispatch(out, msg, req, route, start);
    }

    private void dispatch(CompletableFuture<String> out, Iso8583Message msg, AuthRequest req, Route route, long start) {
        CompletableFuture<IssuerDecision> call = new CompletableFuture<>();
        Future<?> task;
        try {
            task = issuerPool.submit(() -> {
                try {
                    call.complete(route.issuer().authorize(req));
                } catch (Throwable t) {
                    call.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException saturated) {
            route.breaker().recordFailure(); // also releases a half-open probe slot
            completeViaStandIn(out, msg, req, route, start);
            return;
        }

        call.orTimeout(issuerTimeoutMs, TimeUnit.MILLISECONDS).whenComplete((decision, error) -> {
            if (error == null) {
                route.breaker().recordSuccess(); // a decline is still a healthy answer
                complete(out, msg, req, decision.code(), decision.authId(), route.issuer().id(), Path.ISSUER, start);
            } else {
                if (error instanceof TimeoutException) task.cancel(true); // free the worker thread
                route.breaker().recordFailure();
                completeViaStandIn(out, msg, req, route, start);
            }
        });
    }

    private void completeViaStandIn(CompletableFuture<String> out, Iso8583Message msg, AuthRequest req,
                                    Route route, long start) {
        IssuerDecision d = standIn.decide(req, route.issuer().id());
        complete(out, msg, req, d.code(), d.authId(), route.issuer().id(), Path.STAND_IN, start);
    }

    private void complete(CompletableFuture<String> out, Iso8583Message msg, AuthRequest req, ResponseCode code,
                          String authId, String issuerId, Path path, long start) {
        try {
            Iso8583Message response = msg.toBuilder().mti("0110").field(38, authId).field(39, code.code()).build();
            String wire = codec.encode(response);
            publish(new TransactionEvent(PanUtil.mask(req.pan()), req.amountMinor(), code, issuerId, path,
                    (System.nanoTime() - start) / 1_000));
            out.complete(wire);
        } catch (MessageFormatException | RuntimeException e) {
            out.completeExceptionally(e);
        }
    }

    private String formatError(long start) {
        publish(new TransactionEvent("n/a", 0, ResponseCode.FORMAT_ERROR, "-", Path.FORMAT_ERROR,
                (System.nanoTime() - start) / 1_000));
        try {
            return codec.encode(Iso8583Message.builder("0110").field(39, ResponseCode.FORMAT_ERROR.code()).build());
        } catch (MessageFormatException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void publish(TransactionEvent e) {
        for (TransactionListener l : listeners) {
            try {
                l.onCompleted(e);
            } catch (RuntimeException ignored) {
                // a faulty observer must never affect the authorization result
            }
        }
    }

    private void purgeIdempotencyStore() {
        long cutoff = clock.millis() - idempotencyTtlMs;
        idempotency.entrySet().removeIf(en -> en.getValue().future().isDone() && en.getValue().createdAtMs() < cutoff);
    }

    private static ThreadFactory namedDaemons(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ builder

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private record RouteSpec(int low, int high, Issuer issuer) {}

        private final List<RouteSpec> routes = new ArrayList<>();
        private final List<TransactionListener> listeners = new ArrayList<>();
        private Clock clock = Clock.systemUTC();
        private int workerThreads = 32;
        private int queueCapacity = 256;
        private long issuerTimeoutMs = 250;
        private int breakerThreshold = 5;
        private long breakerOpenMs = 2_000;
        private long standInPerTxnMinor = 100_000;
        private long standInCumulativeMinor = 250_000;
        private long maxAmountMinor = 100_000_000;
        private long idempotencyTtlMs = 300_000;

        public Builder route(int binLow, int binHigh, Issuer issuer) { routes.add(new RouteSpec(binLow, binHigh, issuer)); return this; }
        public Builder listener(TransactionListener l) { listeners.add(l); return this; }
        public Builder clock(Clock c) { this.clock = c; return this; }
        public Builder workerThreads(int n) { this.workerThreads = n; return this; }
        public Builder queueCapacity(int n) { this.queueCapacity = n; return this; }
        public Builder issuerTimeoutMs(long ms) { this.issuerTimeoutMs = ms; return this; }
        public Builder breaker(int failureThreshold, long openMs) { this.breakerThreshold = failureThreshold; this.breakerOpenMs = openMs; return this; }
        public Builder standInLimits(long perTxnMinor, long cumulativeMinor) { this.standInPerTxnMinor = perTxnMinor; this.standInCumulativeMinor = cumulativeMinor; return this; }
        public Builder maxAmountMinor(long v) { this.maxAmountMinor = v; return this; }
        public Builder idempotencyTtlMs(long ms) { this.idempotencyTtlMs = ms; return this; }

        public AuthorizationSwitch build() {
            if (routes.isEmpty()) throw new IllegalStateException("At least one route is required");
            return new AuthorizationSwitch(this);
        }
    }
}
