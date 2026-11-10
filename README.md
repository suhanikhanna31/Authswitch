# AuthSwitch

A payment **authorization switch** in plain Java 17, with no frameworks and no runtime dependencies.
It accepts ISO 8583 card authorization requests, routes each one to the right issuer by BIN, and keeps
answering when an issuer is slow or down by using **stand-in processing (STIP)**, then **replays advices**
once the issuer recovers.

**[Live demo](https://authswitch-web.vercel.app)** · [Architecture](docs/ARCHITECTURE.md) ·
[API health](https://authswitch-api.onrender.com/health)

> The live demo runs on free hosting and sleeps when idle, so the first load can take up to a minute.
> If you would rather not wait, `make run` below reproduces the whole scenario offline in a few seconds.

## Why this project exists

A switch sits between a merchant's acquirer and a cardholder's bank. Every authorization needs an answer
inside a tight time budget, **exactly once**, even while one issuer is having an outage. AuthSwitch models
that problem end to end and shows how each failure mode is contained.

| Real-world problem | How the switch handles it |
|---|---|
| An issuer host is slow or down | Hard per-call timeout, per-issuer **circuit breaker**, then **stand-in** approval within limits |
| One bad issuer must not hurt others | One breaker per issuer; healthy issuers keep running |
| Acquirers retransmit on timeout | **Idempotency**: concurrent duplicates share one in-flight result, so the issuer is called once |
| Traffic spikes | Bounded worker pool and queue; overflow is shed to stand-in instead of growing memory |
| Stand-in approvals must reach the issuer | Queued as **advices** and replayed when the breaker closes |
| Card data is sensitive | PAN masked everywhere (`510000******0255`); `toString` never prints field values |

## Quick start

Requires **JDK 17+** and `make`. Nothing else is downloaded.

```
make test    # 16 tests, no JUnit needed
make run     # 5-phase console demo: load, outage, retransmission storm, recovery, bad input
make serve   # HTTP API on :8080 for the web control room in web/
```

## Pipeline at a glance

```
0100 wire message
  -> decode (ISO 8583)  -> de-duplicate (idempotency key)
  -> validate (processing code, Luhn, amount, expiry)  -> route by BIN
  -> circuit breaker    -> issuer call on a bounded pool, with a timeout
                           \-- on failure, timeout, open breaker or full pool --> stand-in + advice queue
  -> 0110 response (DE38 authorization id, DE39 response code)  -> observers (metrics, audit)
```

The full design, with sequence and state diagrams, a failure-mode matrix and the concurrency model, is in
**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Reading guide: five minutes in the code

If you only have a few minutes, read these in order:

1. [`core/AuthorizationSwitch.java`](src/main/java/com/authswitch/core/AuthorizationSwitch.java): the pipeline
   (`process` -> `run` -> `dispatch` -> `completeViaStandIn`) and the idempotency store.
2. [`resilience/CircuitBreaker.java`](src/main/java/com/authswitch/resilience/CircuitBreaker.java): a small,
   explicit state machine with a single half-open probe.
3. [`standin/StandInProcessor.java`](src/main/java/com/authswitch/standin/StandInProcessor.java): an atomic
   cumulative cap and the advice queue.
4. [`AllTests.java`](src/test/java/com/authswitch/AllTests.java): each test names the behaviour it protects,
   including concurrency tests (100-thread stand-in cap, 64-thread duplicate storm, concurrent spending).

## Patterns and concurrency techniques

| Technique | Where | Why |
|---|---|---|
| **Builder** | `Iso8583Message.Builder`, `AuthorizationSwitch.Builder` | Immutable messages; readable switch configuration |
| **Chain of Responsibility** | `validation/ValidationHandler`, `Validators` | Add or reorder rules without touching the switch |
| **Strategy** | `issuer/Issuer` (`SimulatedIssuer`) | Swap issuer implementations; tests use simulations |
| **Observer** | `observer/TransactionListener`, `MetricsCollector`, `AuditLogger` | Metrics and audit stay off the hot path; a faulty listener cannot change a result |
| **State machine** | `resilience/CircuitBreaker` | CLOSED -> OPEN -> HALF_OPEN with a single probe |
| `ThreadPoolExecutor` + bounded queue + `AbortPolicy` | `AuthorizationSwitch` | Explicit back-pressure |
| `CompletableFuture` + `orTimeout` | `AuthorizationSwitch.dispatch` | Non-blocking API, timeout, and worker cancellation on timeout |
| `ConcurrentHashMap.putIfAbsent` | idempotency store | Atomic "first caller does the work, others share the future" |
| `ConcurrentHashMap.compute` | `StandInProcessor` | Atomic check-and-add keeps the cumulative cap exact |
| CAS loop on `AtomicLong` | `SimulatedIssuer.authorize` | A balance can never be overspent |
| `LongAdder` / `LongAccumulator` | `MetricsCollector` | Low-contention counters |
| Injectable clock | `CircuitBreaker`, `Validators` | State transitions tested without sleeping |

## Sample run (`make run`)

Latencies vary from run to run; the counts below are deterministic.

```
== Phase 1: healthy traffic, 2000 authorizations across 16 client threads
  transactions=2000  avgLatency=4.78ms  maxLatency=32.60ms
  code.00 APPROVED 1899   code.51 INSUFFICIENT_FUNDS 101   path.ISSUER 2000

== Phase 2: BANK_A goes down. BANK_B must be unaffected
  path.ISSUER 300 (BANK_B)   path.STAND_IN 300 (BANK_A)
  BANK_A breaker=OPEN   BANK_B breaker=CLOSED   pending advices for BANK_A=194

== Phase 3: retransmission storm, the same message sent by 64 threads at once
  64 identical requests -> issuer called 1 time(s), 63 duplicates suppressed, identical answers: true

== Phase 4: BANK_A recovers; a half-open probe closes the breaker and advices are replayed
  BANK_A breaker=CLOSED   advices queued=194   replayed to issuer=194   still pending=0
```

## Live control room and deployment

`web/index.html` is a static page (no build step) that drives the switch through the HTTP API in
`demo/ApiServer.java`. Take a bank down or slow, send 100 transactions, and watch the breaker trip,
stand-in approve within limits, and advices replay. Or send one message 64 times at once and see the issuer
called once.

- **Run locally:** `make serve`, then open `web/index.html` (it defaults to `http://localhost:8080`).
- **Shared and simulated:** everyone on the live page uses the same switch, and all banks and cards are
  simulated. Use **Reset banks** if someone left a bank down. State is in memory, so it resets on restart.
- **Timeout in the demo:** the demo API uses a 150 ms issuer timeout (the library default is 250 ms). Right
  after waking from sleep, a few requests to a healthy bank can exceed it and be answered by stand-in. That is
  the fallback working as designed, not a fault.

<details>
<summary>Deploy your own copy</summary>

1. **Backend on Render (or Railway/Fly.io):** new Web Service from this repo, runtime **Docker** (uses the
   root `Dockerfile`; the platform sets `PORT`), health check path `/health`. The switch is stateful (breakers,
   advice queues), so it needs a long-running process, not serverless. No database is needed.
2. **Frontend on Vercel:** import the repo, set **Root Directory** to `web`, framework preset **Other**, and
   leave the build settings empty.
3. In `web/index.html`, set `DEFAULT_API` to your Render URL (no trailing slash) and redeploy. Visitors can
   also paste an API URL into the page's footer field.

The API takes no real card data and has open CORS so the static page can call it.
</details>

## Troubleshooting

| Symptom | Fix |
|---|---|
| `javac: command not found` or `release 17 not supported` | Install JDK 17 or newer and check `javac -version` |
| `make serve`: `Address already in use` | Another process holds port 8080; run `PORT=9090 make serve` and paste that URL into the page footer |
| Live demo shows "can't reach the server yet" | The free-tier API is waking up; wait about a minute, or use `make run` / `make serve` locally |
| `make` is unavailable (for example on Windows) | Run the `javac` and `java` commands from the `Makefile` directly; they are one line each |

## Project layout

```
src/main/java/com/authswitch/
  iso/          Iso8583Message, Iso8583Codec (bitmap, fixed + LLVAR fields, strict validation)
  model/        AuthRequest, ResponseCode (DE39), IssuerDecision, PanUtil (Luhn, masking)
  validation/   ValidationHandler (chain) + Validators
  routing/      BinRouter (TreeMap range lookup), Route
  issuer/       Issuer interface, SimulatedIssuer (latency + failure injection)
  resilience/   CircuitBreaker
  standin/      StandInProcessor (limits, advices, replay)
  observer/     TransactionListener, MetricsCollector, AuditLogger
  core/         AuthorizationSwitch (orchestration)
  demo/         Demo (console scenario), ApiServer (HTTP API on the JDK's built-in server)
src/test/java/com/authswitch/AllTests.java
docs/ARCHITECTURE.md
web/            index.html, the static control-room page (deploy to Vercel)
Dockerfile      multi-stage build for the API (Render, Railway, Fly.io)
```

## Key design decisions

Short versions; each has its trade-offs written out in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#7-key-design-decisions).

- **A decline is a healthy answer.** Issuer response `51` counts as a success for the breaker; only timeouts,
  exceptions and pool rejection count as failures.
- **The switch always answers.** The returned future carries a 0110 even for infrastructure failures; stand-in
  decides approve (`00`) or decline (`91`).
- **A rejected submission releases the half-open probe**, otherwise the breaker would wait forever for a result.
- **Idempotency key = terminal + STAN + RRN + transmission time**, the fields acquirers keep stable on retry.
- **Timeouts cancel the worker** (`Future.cancel(true)`) so a hung issuer call does not pin a pool thread.

## Deliberately simplified

This is a learning and portfolio project, not a certified implementation.

- ASCII ISO 8583 subset (11 data elements, primary bitmap only), not Mastercard's binary/EBCDIC formats, and no
  MAC, PIN, EMV or 3-D Secure fields.
- The issuer is simulated in memory: no network I/O, TLS or HSMs.
- Idempotency state and advice queues are in memory, so they do not survive a restart or span nodes.
- Advices are replayed when a breaker returns to closed. If a few requests fall back to stand-in without the
  breaker ever opening (for example a one-off timeout), those advices wait for a later outage and recovery.
- Stand-in limits are static; real STIP parameters come from the issuer, by BIN and merchant category.
- Tests use a small built-in runner instead of JUnit so the project builds with only a JDK. Porting `AllTests`
  to JUnit 5 would be mechanical.

A fuller list, with what a production version would change, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#9-known-limitations-and-production-gaps).

## Possible extensions

Reversals (0400) and settlement files, a socket front end using NIO (`AsynchronousServerSocketChannel`), a
`pom.xml`/Gradle build with JUnit 5, and JMH benchmarks for the router and codec.
