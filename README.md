# AuthSwitch

A small, dependency-free **payment authorization switch** in plain Java 17: it accepts ISO 8583 card
authorization requests, routes them to the right issuer by BIN, and keeps answering when an issuer is
slow or down, using **stand-in processing (STIP)** with later **advice replay**.

It models the part of the card network that sits between a merchant's acquirer and a cardholder's bank:
every authorization must get an answer within a tight time budget, exactly once, even while one issuer is
having an outage. Core Java, concurrency, and classic design patterns only: no frameworks, no libraries.

```
make test    # 16 tests, no JUnit needed
make run     # 5-phase demo: load, outage, retransmission storm, recovery, bad input
```
Requires JDK 17+.

## The problem it solves

| Real-world issue | How the switch handles it |
|---|---|
| An issuer host is slow or down | Hard per-call timeout, per-issuer **circuit breaker**, then **stand-in** approval within limits |
| One bad issuer must not hurt others | A breaker per issuer; healthy issuers keep running (see demo phase 2) |
| Acquirers retransmit on timeout | **Idempotency**: concurrent duplicates share one in-flight result; issuer is called once |
| Traffic spikes | Bounded worker pool and queue; overflow is shed to stand-in rather than growing memory |
| Stand-in approvals must reach the issuer | Queued as **advices** and replayed when the breaker closes |
| Card data is sensitive | PAN masked everywhere (`510000******0255`); `toString` never prints field values |

## Architecture

```
 wire (0100) ─► Iso8583Codec ─► ValidationHandler chain ─► idempotency store ─► BinRouter
                  (format 30)    processing code → Luhn          (dup? share        │
                                 → amount → expiry               in-flight answer)  ▼
                                 (12/14/13/54)                           per-issuer CircuitBreaker
                                                                      closed │              │ open / half-open busy
                                                                             ▼              ▼
                                            bounded worker pool ──► Issuer (timeout)   StandInProcessor ──► advice queue
                                                    │ ok / decline       │ fail/timeout/rejected  ▲               │
                                                    └────────────────────┴────────────────────────┘      replay on CLOSED
                                                                       ▼
                                          0110 response (DE38 auth id, DE39 code) ─► observers (metrics, audit)
```

## Patterns and concurrency, and where to find them

| Technique | Where | Why |
|---|---|---|
| **Builder** | `Iso8583Message.Builder`, `AuthorizationSwitch.Builder` | Immutable messages; readable switch configuration |
| **Chain of Responsibility** | `validation/ValidationHandler`, `Validators` | Add or reorder rules without touching the switch |
| **Strategy** | `issuer/Issuer` (`SimulatedIssuer`) | Swap issuer implementations; tests use simulations |
| **Observer** | `observer/TransactionListener`, `MetricsCollector`, `AuditLogger` | Metrics/audit decoupled from the hot path; a faulty listener cannot change a result |
| **State machine** | `resilience/CircuitBreaker` | CLOSED → OPEN → HALF_OPEN with a single probe |
| `ThreadPoolExecutor` + bounded queue + `AbortPolicy` | `AuthorizationSwitch` | Explicit back-pressure |
| `CompletableFuture` + `orTimeout` | `AuthorizationSwitch.dispatch` | Non-blocking API, timeout, and worker-thread cancellation on timeout |
| `ConcurrentHashMap.putIfAbsent` | idempotency store | Atomic "first caller does the work, others share the future" |
| `ConcurrentHashMap.compute` | `StandInProcessor` | Atomic check-and-add keeps the cumulative cap exact |
| CAS loop on `AtomicLong` | `SimulatedIssuer.authorize` | Balance can never be overspent |
| `LongAdder` / `LongAccumulator` | `MetricsCollector` | Low-contention counters |
| Injectable clock | `CircuitBreaker`, `Validators` | State transitions tested without sleeping |

## Sample run (`make run`)

```
== Phase 1: healthy traffic, 2000 authorizations across 16 client threads
  transactions=2000  avgLatency=4.78ms  maxLatency=71.25ms
  code.00 APPROVED 1899   code.51 INSUFFICIENT_FUNDS 101   path.ISSUER 2000

== Phase 2: BANK_A goes down. BANK_B must be unaffected
  path.ISSUER 300 (BANK_B)   path.STAND_IN 300 (BANK_A)
  BANK_A breaker=OPEN   BANK_B breaker=CLOSED   pending advices for BANK_A=194

== Phase 3: retransmission storm, the same message sent by 64 threads at once
  64 identical requests -> issuer called 1 time(s), 63 duplicates suppressed, identical answers: true

== Phase 4: BANK_A recovers; a half-open probe closes the breaker and advices are replayed
  BANK_A breaker=CLOSED   advices queued=194   replayed to issuer=194   still pending=0
```

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
  demo/         Demo
src/test/java/com/authswitch/AllTests.java
```

## Design decisions worth discussing

- **A decline is a healthy answer.** Issuer response `51` calls `recordSuccess()`; only timeouts, exceptions
  and pool rejection count against the breaker.
- **The switch always answers.** The returned future carries a 0110 even for infrastructure failures;
  the stand-in path decides approve (`00`) or decline (`91`).
- **Rejected submissions release the half-open probe.** If the pool rejects the probe request, the breaker is
  told it failed; otherwise it would wait forever for a probe result.
- **Idempotency key = terminal + STAN + RRN + transmission time**, the same fields acquirers keep stable on retry.
  Completed entries are purged after a TTL by a housekeeping thread.
- **Timeouts cancel the worker** (`Future.cancel(true)`) so a hung issuer call does not pin a pool thread.

## Deliberately simplified

This is a learning/portfolio project, not a certified implementation.

- ASCII ISO 8583 subset (11 data elements, primary bitmap only), not Mastercard's binary/EBCDIC formats, and no
  MAC, PIN, EMV or 3-D Secure fields.
- The issuer is simulated in memory; no network I/O, TLS, or HSMs.
- Idempotency state and advice queues are in memory, so they would not survive a restart or span nodes. A
  production version would persist both (and replay advices in order).
- Stand-in limits are static; real STIP parameters come from the issuer, by BIN and merchant category.
- Tests use a small built-in runner instead of JUnit so the project builds with only a JDK. Porting `AllTests`
  to JUnit 5 would be mechanical.

## Possible extensions

Reversals (0400) and settlement files, a socket front end using NIO (`AsynchronousServerSocketChannel`), a
`pom.xml`/Gradle build with JUnit 5, JMH benchmarks for the router and codec.
