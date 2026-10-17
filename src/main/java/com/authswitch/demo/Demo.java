package com.authswitch.demo;

import com.authswitch.core.AuthorizationSwitch;
import com.authswitch.issuer.SimulatedIssuer;
import com.authswitch.iso.Iso8583Codec;
import com.authswitch.iso.Iso8583Message;
import com.authswitch.iso.MessageFormatException;
import com.authswitch.model.PanUtil;
import com.authswitch.observer.AuditLogger;
import com.authswitch.observer.MetricsCollector;
import com.authswitch.observer.TransactionEvent;
import com.authswitch.resilience.CircuitBreaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end scenario: healthy traffic, an issuer outage (circuit opens, stand-in approves within limits),
 * a retransmission storm (idempotency), then recovery (half-open probe, advice replay).
 */
public final class Demo {
    private static final Iso8583Codec CODEC = new Iso8583Codec();
    private static final AtomicLong SEQ = new AtomicLong(1);

    public static void main(String[] args) throws Exception {
        SimulatedIssuer bankA = new SimulatedIssuer("BANK_A", 1_000_000, 3); // BINs 510000-559999
        SimulatedIssuer bankB = new SimulatedIssuer("BANK_B", 1_000_000, 3); // BINs 222100-272099
        MetricsCollector metrics = new MetricsCollector();

        try (AuthorizationSwitch sw = AuthorizationSwitch.builder()
                .route(510000, 559999, bankA)
                .route(222100, 272099, bankB)
                .listener(metrics)
                .listener(new AuditLogger(System.out, e -> e.path() == TransactionEvent.Path.STAND_IN && e.amountMinor() % 97 == 0))
                .issuerTimeoutMs(100)
                .breaker(5, 600)
                .standInLimits(100_000, 250_000)
                .build()) {

            List<String> cardsA = cards("510000", 100);
            List<String> cardsB = cards("222100", 100);

            banner("Phase 1: healthy traffic, 2000 authorizations across 16 client threads, both issuers up");
            blast(sw, cardsA, cardsB, 2000, 16);
            System.out.print(metrics.summary());

            banner("Phase 2: BANK_A goes down. BANK_B must be unaffected");
            MetricsCollector phase2 = new MetricsCollector();
            sw.addListener(phase2);
            bankA.setMode(SimulatedIssuer.Mode.DOWN);
            blast(sw, cardsA, cardsB, 600, 16);
            System.out.print(phase2.summary());
            System.out.printf("  BANK_A breaker=%s   BANK_B breaker=%s   pending advices for BANK_A=%d%n",
                    sw.breakerState("BANK_A"), sw.breakerState("BANK_B"), sw.standIn().pendingAdvices("BANK_A"));

            banner("Phase 3: retransmission storm, the same message sent by 64 threads at once");
            long before = bankB.authCalls();
            String same = request(cardsB.get(0), 12_345, "TERM0001");
            List<String> answers = fire(sw, same, 64);
            boolean identical = answers.stream().distinct().count() == 1;
            System.out.printf("  64 identical requests -> issuer called %d time(s), %d duplicates suppressed, identical answers: %s%n",
                    bankB.authCalls() - before, sw.duplicatesSuppressed(), identical);

            banner("Phase 4: BANK_A recovers; a half-open probe closes the breaker and advices are replayed");
            bankA.setMode(SimulatedIssuer.Mode.HEALTHY);
            long advicesQueued = sw.standIn().pendingAdvices("BANK_A");
            Thread.sleep(700); // > breaker open duration
            sw.process(request(cardsA.get(1), 5_000, "TERM0002")).get();
            Thread.sleep(200); // let the async replay finish
            System.out.printf("  BANK_A breaker=%s   advices queued=%d   replayed to issuer=%d   still pending=%d%n",
                    sw.breakerState("BANK_A"), advicesQueued, bankA.advicesReceived(), sw.standIn().pendingAdvices("BANK_A"));

            banner("Phase 5: bad input is rejected safely");
            System.out.println("  garbage message      -> DE39=" + responseCode(sw.process("not-an-iso-message").get()) + " (format error)");
            System.out.println("  Luhn-invalid PAN     -> DE39=" + responseCode(sw.process(request("5100000000000000", 100, "TERM0003")).get()) + " (invalid card)");
            System.out.println("  unknown BIN          -> DE39=" + responseCode(sw.process(request(PanUtil.withCheckDigit("400000000000000"), 100, "TERM0004")).get()) + " (no such issuer)");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void blast(AuthorizationSwitch sw, List<String> cardsA, List<String> cardsB, int n, int threads) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(threads);
        Random rnd = new Random(42);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> pool = i % 2 == 0 ? cardsA : cardsB;
            String wire = request(pool.get(rnd.nextInt(pool.size())), 500 + rnd.nextInt(150_000), "TERM" + (i % 50 + 1000));
            futures.add(clients.submit(() -> sw.process(wire).get()));
        }
        for (Future<String> f : futures) f.get();
        clients.shutdown();
    }

    private static List<String> fire(AuthorizationSwitch sw, String wire, int threads) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(clients.submit(() -> { go.await(); return sw.process(wire).get(); }));
        }
        go.countDown();
        List<String> out = new ArrayList<>();
        for (Future<String> f : fs) out.add(f.get());
        clients.shutdown();
        return out;
    }

    private static List<String> cards(String bin6, int count) {
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) cards.add(PanUtil.withCheckDigit(bin6 + String.format("%09d", i + 1)));
        return cards;
    }

    /** Builds a 0100 purchase request. Each call gets a fresh STAN/RRN, i.e. a distinct transaction. */
    static String request(String pan, long amountMinor, String terminal) throws MessageFormatException {
        long n = SEQ.getAndIncrement();
        return CODEC.encode(Iso8583Message.builder("0100")
                .field(2, pan).field(3, "000000").field(4, String.format("%012d", amountMinor))
                .field(7, "1018120000").field(11, String.format("%06d", n % 1_000_000))
                .field(14, "3012").field(37, String.format("%012d", n)).field(41, terminal).field(49, "356")
                .build());
    }

    private static String responseCode(String wire) throws MessageFormatException {
        return CODEC.decode(wire).field(39).orElse("??");
    }

    private static void banner(String s) {
        System.out.println("\n== " + s);
    }

    private Demo() {}
}
