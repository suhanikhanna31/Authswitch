package com.authswitch.demo;

import com.authswitch.core.AuthorizationSwitch;
import com.authswitch.issuer.SimulatedIssuer;
import com.authswitch.iso.Iso8583Codec;
import com.authswitch.model.PanUtil;
import com.authswitch.observer.MetricsCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tiny HTTP front end (JDK built-in server, no dependencies) so the switch can be driven from the web page.
 * Everything is simulated: no real card data is ever accepted. Endpoints:
 *   GET  /health
 *   GET  /api/state
 *   POST /api/simulate?bank=A|B|both&count=1..500
 *   POST /api/duplicates?bank=A|B
 *   POST /api/issuers/{BANK_A|BANK_B}/mode?mode=HEALTHY|SLOW|DOWN
 *   POST /api/reset
 */
public final class ApiServer {
    private static final Iso8583Codec CODEC = new Iso8583Codec();
    private static final int MAX_BATCH = 500;

    private final SimulatedIssuer bankA = new SimulatedIssuer("BANK_A", 5_000_000, 3);
    private final SimulatedIssuer bankB = new SimulatedIssuer("BANK_B", 5_000_000, 3);
    private final MetricsCollector metrics = new MetricsCollector();
    private final AuthorizationSwitch sw;
    private final List<String> cardsA = cards("510000", 100);
    private final List<String> cardsB = cards("222100", 100);
    private final ExecutorService clients = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "sim-client");
        t.setDaemon(true);
        return t;
    });

    private ApiServer() {
        bankA.setSlowLatencyMs(1_000);
        bankB.setSlowLatencyMs(1_000);
        sw = AuthorizationSwitch.builder()
                .route(510000, 559999, bankA)
                .route(222100, 272099, bankB)
                .listener(metrics)
                .issuerTimeoutMs(150)
                .breaker(5, 5_000)          // stays OPEN for 5s so the state is visible on the page
                .standInLimits(100_000, 250_000)
                .build();
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        ApiServer app = new ApiServer();
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.createContext("/", app::route);
        http.start();
        System.out.println("AuthSwitch API listening on :" + port);
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange ex) throws IOException {
        // Open CORS: acceptable here because the API is a simulation with no secrets or real data.
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        try {
            if (method.equals("OPTIONS")) {
                send(ex, 204, "");
            } else if (method.equals("GET") && path.equals("/health")) {
                send(ex, 200, "{\"status\":\"ok\"}");
            } else if (method.equals("GET") && path.equals("/api/state")) {
                send(ex, 200, stateJson());
            } else if (method.equals("POST") && path.equals("/api/simulate")) {
                send(ex, 200, simulate(q.getOrDefault("bank", "both"), clamp(q.get("count"), 1, MAX_BATCH, 100)));
            } else if (method.equals("POST") && path.equals("/api/duplicates")) {
                send(ex, 200, duplicateStorm(q.getOrDefault("bank", "B")));
            } else if (method.equals("POST") && path.matches("/api/issuers/BANK_[AB]/mode")) {
                setMode(path.split("/")[3], q.get("mode"));
                send(ex, 200, stateJson());
            } else if (method.equals("POST") && path.equals("/api/reset")) {
                bankA.setMode(SimulatedIssuer.Mode.HEALTHY);
                bankB.setMode(SimulatedIssuer.Mode.HEALTHY);
                bankA.resetBalances();
                bankB.resetBalances();
                send(ex, 200, stateJson());
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"internal error\"}");
        }
    }

    // ------------------------------------------------------------------ handlers

    private String simulate(String bank, int count) throws Exception {
        if (!List.of("A", "B", "both").contains(bank)) throw new IllegalArgumentException("bank must be A, B or both");
        Random rnd = new Random();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<String> pool = bank.equals("A") ? cardsA : bank.equals("B") ? cardsB : (i % 2 == 0 ? cardsA : cardsB);
            String wire = Demo.request(pool.get(rnd.nextInt(pool.size())), 500 + rnd.nextInt(60_000), "WEB" + String.format("%05d", i));
            futures.add(clients.submit(() -> sw.process(wire).get()));
        }
        int approvedIssuer = 0, approvedStandIn = 0, declinedFunds = 0, declinedUnavailable = 0, other = 0;
        for (Future<String> f : futures) {
            var resp = CODEC.decode(f.get());
            String code = resp.field(39).orElse("??");
            boolean standIn = resp.field(38).orElse("").startsWith("S");
            if (code.equals("00")) { if (standIn) approvedStandIn++; else approvedIssuer++; }
            else if (code.equals("51")) declinedFunds++;
            else if (code.equals("91")) declinedUnavailable++;
            else other++;
        }
        return "{\"sent\":" + count + ",\"approvedByIssuer\":" + approvedIssuer + ",\"approvedByStandIn\":" + approvedStandIn
                + ",\"declinedInsufficientFunds\":" + declinedFunds + ",\"declinedIssuerUnavailable\":" + declinedUnavailable
                + ",\"other\":" + other + "}";
    }

    private String duplicateStorm(String bank) throws Exception {
        SimulatedIssuer issuer = bank.equals("A") ? bankA : bank.equals("B") ? bankB : null;
        if (issuer == null) throw new IllegalArgumentException("bank must be A or B");
        String pan = (bank.equals("A") ? cardsA : cardsB).get(0);
        String wire = Demo.request(pan, 12_345, "STORM001");
        long callsBefore = issuer.authCalls();
        long dupBefore = sw.duplicatesSuppressed();
        int threads = 64;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(clients.submit(() -> { go.await(); return sw.process(wire).get(); }));
        }
        go.countDown();
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (Future<String> f : fs) distinct.add(f.get());
        return "{\"sent\":" + threads + ",\"issuerCalls\":" + (issuer.authCalls() - callsBefore)
                + ",\"duplicatesSuppressed\":" + (sw.duplicatesSuppressed() - dupBefore)
                + ",\"identicalAnswers\":" + (distinct.size() == 1) + "}";
    }

    private void setMode(String issuerId, String mode) {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        SimulatedIssuer.Mode m;
        try {
            m = SimulatedIssuer.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be HEALTHY, SLOW or DOWN");
        }
        (issuerId.equals("BANK_A") ? bankA : bankB).setMode(m);
    }

    private String stateJson() {
        StringBuilder sb = new StringBuilder("{\"issuers\":[");
        sb.append(issuerJson(bankA)).append(',').append(issuerJson(bankB)).append("],");
        sb.append("\"duplicatesSuppressed\":").append(sw.duplicatesSuppressed()).append(',');
        sb.append("\"advicesReplayed\":").append(sw.standIn().advicesReplayed()).append(',');
        sb.append("\"transactions\":").append(metrics.total()).append(',');
        sb.append("\"avgLatencyMs\":").append(String.format(java.util.Locale.ROOT, "%.2f", metrics.avgLatencyMs())).append(',');
        sb.append("\"counters\":{");
        Map<String, Long> snap = new TreeMap<>(metrics.snapshot());
        boolean first = true;
        for (Map.Entry<String, Long> e : snap.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append("}}").toString();
    }

    private String issuerJson(SimulatedIssuer i) {
        return "{\"id\":\"" + i.id() + "\",\"mode\":\"" + i.mode() + "\",\"breaker\":\"" + sw.breakerState(i.id())
                + "\",\"pendingAdvices\":" + sw.standIn().pendingAdvices(i.id()) + ",\"authCalls\":" + i.authCalls()
                + ",\"advicesReceived\":" + i.advicesReceived() + "}";
    }

    // ------------------------------------------------------------------ utilities

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new TreeMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int clamp(String s, int lo, int hi, int dflt) {
        if (s == null) return dflt;
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("count must be a number");
        }
    }

    private static List<String> cards(String bin6, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(PanUtil.withCheckDigit(bin6 + String.format("%09d", i + 1)));
        return out;
    }

}
