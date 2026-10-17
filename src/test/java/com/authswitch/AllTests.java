package com.authswitch;

import com.authswitch.core.AuthorizationSwitch;
import com.authswitch.issuer.SimulatedIssuer;
import com.authswitch.iso.Iso8583Codec;
import com.authswitch.iso.Iso8583Message;
import com.authswitch.iso.MessageFormatException;
import com.authswitch.model.AuthRequest;
import com.authswitch.model.IssuerDecision;
import com.authswitch.model.PanUtil;
import com.authswitch.model.ResponseCode;
import com.authswitch.resilience.CircuitBreaker;
import com.authswitch.routing.BinRouter;
import com.authswitch.routing.Route;
import com.authswitch.standin.StandInProcessor;
import com.authswitch.validation.ValidationHandler;
import com.authswitch.validation.Validators;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free test runner (no JUnit needed): run with `make test`. Exits non-zero on any failure. */
public final class AllTests {
    private static final Iso8583Codec CODEC = new Iso8583Codec();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private static final String VISA_LIKE_TEST_PAN = PanUtil.withCheckDigit("555555555555444"); // 5555555555554444
    private static int passed, failed;
    private static final AtomicLong SEQ = new AtomicLong(1);

    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL  " + name + " -> " + t);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    public static void main(String[] args) {
        System.out.println("Codec");
        test("round-trips a message", () -> {
            Iso8583Message m = Iso8583Message.builder("0100").field(2, VISA_LIKE_TEST_PAN).field(4, "000000010000").field(41, "TERM0001").build();
            Iso8583Message back = CODEC.decode(CODEC.encode(m));
            eq(m.fields(), back.fields(), "fields");
        });
        test("bitmap sets the right bits", () -> {
            String wire = CODEC.encode(Iso8583Message.builder("0100").field(2, "4111111111111111").field(3, "000000").build());
            eq("6000000000000000", wire.substring(4, 20), "bitmap"); // bit 1 is the secondary-bitmap flag; DE2 -> bit 2, DE3 -> bit 3
        });
        test("rejects truncated, trailing and non-numeric data", () -> {
            String good = CODEC.encode(Iso8583Message.builder("0100").field(2, "4111111111111111").field(3, "000000").build());
            expectFormatError(good.substring(0, good.length() - 1));
            expectFormatError(good + "9");
            expectFormatError(good.replace("4111111111111111", "41111111111111AB"));
            expectFormatError("short");
        });

        System.out.println("Validation chain");
        test("Luhn, amount, expiry and processing code", () -> {
            ValidationHandler chain = Validators.standardChain(FIXED, 1_000_000);
            eq(java.util.Optional.empty(), chain.validate(req(VISA_LIKE_TEST_PAN, 100, "3012", "000000")), "valid");
            eq(ResponseCode.INVALID_CARD, chain.validate(req("5555555555554445", 100, "3012", "000000")).get(), "luhn");
            eq(ResponseCode.INVALID_AMOUNT, chain.validate(req(VISA_LIKE_TEST_PAN, 0, "3012", "000000")).get(), "zero");
            eq(ResponseCode.INVALID_AMOUNT, chain.validate(req(VISA_LIKE_TEST_PAN, 2_000_000, "3012", "000000")).get(), "too big");
            eq(ResponseCode.EXPIRED_CARD, chain.validate(req(VISA_LIKE_TEST_PAN, 100, "2609", "000000")).get(), "expired");
            eq(java.util.Optional.empty(), chain.validate(req(VISA_LIKE_TEST_PAN, 100, "2610", "000000")), "valid through end of month");
            eq(ResponseCode.INVALID_TRANSACTION, chain.validate(req(VISA_LIKE_TEST_PAN, 100, "3012", "200000")).get(), "refund unsupported");
        });

        System.out.println("BIN router");
        test("range lookup and overlap detection", () -> {
            SimulatedIssuer a = new SimulatedIssuer("A", 0, 0), b = new SimulatedIssuer("B", 0, 0);
            BinRouter r = new BinRouter(List.of(new Route(510000, 559999, a, null), new Route(222100, 272099, b, null)));
            eq("A", r.find(540123).get().issuer().id(), "A");
            eq("B", r.find(222100).get().issuer().id(), "B low edge");
            eq("B", r.find(272099).get().issuer().id(), "B high edge");
            check(r.find(400000).isEmpty(), "unknown BIN");
            check(r.find(272100).isEmpty(), "just above range");
            boolean threw = false;
            try {
                new BinRouter(List.of(new Route(100000, 200000, a, null), new Route(150000, 250000, b, null)));
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "overlap must be rejected");
        });

        System.out.println("Circuit breaker");
        test("closed -> open -> half-open -> closed, single probe", () -> {
            AtomicLong now = new AtomicLong(0);
            List<CircuitBreaker.State> seen = new ArrayList<>();
            CircuitBreaker cb = new CircuitBreaker(3, 1000, now::get, seen::add);
            for (int i = 0; i < 2; i++) { check(cb.tryAcquire(), "closed admits"); cb.recordFailure(); }
            eq(CircuitBreaker.State.CLOSED, cb.state(), "below threshold");
            cb.tryAcquire(); cb.recordFailure();
            eq(CircuitBreaker.State.OPEN, cb.state(), "tripped");
            check(!cb.tryAcquire(), "open rejects");
            now.set(1000);
            check(cb.tryAcquire(), "first caller after cool-down is the probe");
            eq(CircuitBreaker.State.HALF_OPEN, cb.state(), "half-open");
            check(!cb.tryAcquire(), "only one probe at a time");
            cb.recordSuccess();
            eq(CircuitBreaker.State.CLOSED, cb.state(), "recovered");
            eq(List.of(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED), seen, "transitions");
        });
        test("failed probe re-opens the breaker", () -> {
            AtomicLong now = new AtomicLong(0);
            CircuitBreaker cb = new CircuitBreaker(1, 1000, now::get, s -> {});
            cb.tryAcquire(); cb.recordFailure();
            now.set(1000);
            check(cb.tryAcquire(), "probe");
            cb.recordFailure();
            eq(CircuitBreaker.State.OPEN, cb.state(), "reopened");
            check(!cb.tryAcquire(), "cool-down restarted");
        });

        System.out.println("Stand-in processing");
        test("cumulative cap holds under 100 concurrent threads", () -> {
            StandInProcessor sip = new StandInProcessor(50_000, 250_000);
            AuthRequest r = req(VISA_LIKE_TEST_PAN, 10_000, "3012", "000000");
            ExecutorService pool = Executors.newFixedThreadPool(100);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<IssuerDecision>> fs = new ArrayList<>();
            for (int i = 0; i < 100; i++) fs.add(pool.submit(() -> { go.await(); return sip.decide(r, "X"); }));
            go.countDown();
            int approved = 0;
            for (Future<IssuerDecision> f : fs) if (f.get().code() == ResponseCode.APPROVED) approved++;
            pool.shutdown();
            eq(25, approved, "exactly 250000/10000 approvals");
            eq(25, sip.pendingAdvices("X"), "one advice per approval");
        });
        test("per-transaction cap declines with 91", () -> {
            StandInProcessor sip = new StandInProcessor(50_000, 250_000);
            eq(ResponseCode.ISSUER_UNAVAILABLE, sip.decide(req(VISA_LIKE_TEST_PAN, 50_001, "3012", "000000"), "X").code(), "over cap");
        });

        System.out.println("Authorization switch (end to end)");
        test("approves, then declines on insufficient funds", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 10_000, 0);
            try (AuthorizationSwitch sw = base(bank).build()) {
                eq("00", code(sw.process(wire(VISA_LIKE_TEST_PAN, 6_000)).get()), "first");
                eq("51", code(sw.process(wire(VISA_LIKE_TEST_PAN, 6_000)).get()), "second");
            }
        });
        test("concurrent spending never exceeds the balance", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 100_000, 1);
            try (AuthorizationSwitch sw = base(bank).workerThreads(16).queueCapacity(500).build()) {
                List<String> wires = new ArrayList<>();
                for (int i = 0; i < 200; i++) wires.add(wire(VISA_LIKE_TEST_PAN, 1_000));
                ExecutorService pool = Executors.newFixedThreadPool(32);
                List<Future<String>> fs = new ArrayList<>();
                for (String w : wires) fs.add(pool.submit(() -> sw.process(w).get()));
                int approved = 0;
                for (Future<String> f : fs) if (code(f.get()).equals("00")) approved++;
                pool.shutdown();
                eq(100, approved, "100 x 1000 = balance");
                eq(0L, bank.balanceOf(VISA_LIKE_TEST_PAN), "final balance");
            }
        });
        test("duplicate in-flight requests reach the issuer once", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 1_000_000, 50);
            try (AuthorizationSwitch sw = base(bank).build()) {
                String w = wire(VISA_LIKE_TEST_PAN, 1_000);
                ExecutorService pool = Executors.newFixedThreadPool(50);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> fs = new ArrayList<>();
                for (int i = 0; i < 50; i++) fs.add(pool.submit(() -> { go.await(); return sw.process(w).get(); }));
                go.countDown();
                java.util.Set<String> distinct = new java.util.HashSet<>();
                for (Future<String> f : fs) distinct.add(f.get());
                pool.shutdown();
                eq(1L, bank.authCalls(), "issuer calls");
                eq(1, distinct.size(), "identical answers");
                eq(49L, sw.duplicatesSuppressed(), "duplicates");
            }
        });
        test("slow issuer times out and stand-in approves", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 1_000_000, 0);
            bank.setMode(SimulatedIssuer.Mode.SLOW);
            bank.setSlowLatencyMs(2_000);
            try (AuthorizationSwitch sw = base(bank).issuerTimeoutMs(50).build()) {
                long t0 = System.nanoTime();
                String res = sw.process(wire(VISA_LIKE_TEST_PAN, 1_000)).get(1, TimeUnit.SECONDS);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                eq("00", code(res), "stand-in approval");
                check(res.contains("S0000"), "stand-in auth id");
                check(ms < 500, "answered near the timeout, not after 2s: " + ms + "ms");
            }
        });
        test("outage trips the breaker, recovery replays advices", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 1_000_000, 0);
            bank.setMode(SimulatedIssuer.Mode.DOWN);
            try (AuthorizationSwitch sw = base(bank).clock(Clock.systemUTC()).breaker(3, 200).build()) { // real clock: cool-down must elapse
                for (int i = 0; i < 6; i++) sw.process(wire(VISA_LIKE_TEST_PAN, 1_000)).get();
                eq(CircuitBreaker.State.OPEN, sw.breakerState("BANK"), "open");
                long callsWhileOpen = bank.authCalls();
                sw.process(wire(VISA_LIKE_TEST_PAN, 1_000)).get();
                eq(callsWhileOpen, bank.authCalls(), "open breaker must not call the issuer");
                bank.setMode(SimulatedIssuer.Mode.HEALTHY);
                Thread.sleep(250);
                eq("00", code(sw.process(wire(VISA_LIKE_TEST_PAN, 1_000)).get()), "probe");
                eq(CircuitBreaker.State.CLOSED, sw.breakerState("BANK"), "closed");
                for (int i = 0; i < 50 && sw.standIn().pendingAdvices("BANK") > 0; i++) Thread.sleep(20);
                eq(0, sw.standIn().pendingAdvices("BANK"), "advices drained");
                eq(7L, bank.advicesReceived(), "7 stand-in approvals replayed");
            }
        });
        test("saturated pool sheds load to stand-in instead of queueing forever", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 1_000_000, 300);
            try (AuthorizationSwitch sw = base(bank).workerThreads(1).queueCapacity(1).issuerTimeoutMs(2_000).build()) {
                List<java.util.concurrent.CompletableFuture<String>> fs = new ArrayList<>();
                for (int i = 0; i < 6; i++) fs.add(sw.process(wire(VISA_LIKE_TEST_PAN, 100)));
                int standIn = 0;
                for (var f : fs) if (f.get().contains("S0000")) standIn++;
                check(standIn >= 3, "expected shed requests to be answered by stand-in, got " + standIn);
            }
        });
        test("bad inputs get the right codes and no PAN in toString", () -> {
            SimulatedIssuer bank = new SimulatedIssuer("BANK", 1_000_000, 0);
            try (AuthorizationSwitch sw = base(bank).build()) {
                eq("30", code(sw.process("garbage").get()), "format");
                eq("14", code(sw.process(wire("5555555555554445", 100)).get()), "luhn");
                eq("15", code(sw.process(wire(PanUtil.withCheckDigit("400000000000000"), 100)).get()), "unknown bin");
                eq(0L, bank.authCalls(), "issuer untouched by invalid traffic");
            }
            check(!Iso8583Message.builder("0100").field(2, VISA_LIKE_TEST_PAN).build().toString().contains(VISA_LIKE_TEST_PAN), "PAN leaked");
        });

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    private static AuthorizationSwitch.Builder base(SimulatedIssuer bank) {
        return AuthorizationSwitch.builder().clock(FIXED).route(510000, 559999, bank).issuerTimeoutMs(500);
    }

    private static AuthRequest req(String pan, long amount, String expiry, String procCode) {
        return new AuthRequest(pan, procCode, amount, "1018120000", "000001", expiry, "000000000001", "TERM0001", "356");
    }

    private static String wire(String pan, long amount) throws MessageFormatException {
        long n = SEQ.getAndIncrement();
        return CODEC.encode(Iso8583Message.builder("0100").field(2, pan).field(3, "000000")
                .field(4, String.format("%012d", amount)).field(7, "1018120000").field(11, String.format("%06d", n))
                .field(14, "3012").field(37, String.format("%012d", n)).field(41, "TERM0001").field(49, "356").build());
    }

    private static String code(String responseWire) throws MessageFormatException {
        return CODEC.decode(responseWire).field(39).orElse("??");
    }

    private static void expectFormatError(String wire) {
        try {
            CODEC.decode(wire);
        } catch (MessageFormatException expected) {
            return;
        }
        throw new AssertionError("expected MessageFormatException for: " + wire);
    }
}
