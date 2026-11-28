# kotlin-payments-lib

[![CI](https://github.com/sidchi/kotlin-payments-lib/actions/workflows/ci.yml/badge.svg)](https://github.com/sidchi/kotlin-payments-lib/actions/workflows/ci.yml)

A Kotlin library for **resilient recurring payments**: idempotent charges, decline-aware retries, fallback across
processors, multi-currency money, and an Account Updater simulator. Gateways are mocked; there are no real processor
credentials or proprietary code, and every card number in this repo is synthetic.

> **Status:** core domain model, multi-currency conversion, idempotent charge execution (in-memory and Postgres
> stores), and the gateway abstraction with two mock processors are in place. The decline-aware retry engine is next.
> See [ROADMAP.md](ROADMAP.md).

## Why

Subscription businesses lose a surprising share of revenue to *involuntary churn*: renewals that fail for reasons
that have nothing to do with the customer wanting to leave — an expired card, a processor timeout, a soft decline
that would have succeeded an hour later. Recovering those charges safely needs a few things to be right at once:

- **Idempotency** — a retried request must never charge the customer twice.
- **Decline-aware retry** — retry a `do_not_honor` with backoff, but never retry a `stolen_card`.
- **Processor fallback** — route eligible failures to a secondary processor, with a circuit breaker per processor.
- **Fresh credentials** — apply Account Updater results (new PAN/expiry, closed account) before the next attempt.
- **Money that can't lie** — currency-aware minor units (JPY 0, USD 2, KWD 3) and explicit rounding.

This library packages those pieces behind small, testable interfaces.

## Planned architecture

```
              ChargeRequest (idempotency key, money, payment method)
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │  IdempotencyStore   │──► replay stored ChargeResult
                        └──────────┬──────────┘
                                   ▼
                        ┌─────────────────────┐
                        │    RetryEngine      │  decline taxonomy → retry / stop
                        └──────────┬──────────┘
                                   ▼
                        ┌─────────────────────┐
                        │  ProcessorRouter    │  primary → fallback, circuit breaker each
                        └─────┬──────────┬────┘
                              ▼          ▼
                        Gateway A    Gateway B      (mock processors)
                                   ▲
                 AccountUpdater ───┘  refreshes PAN/expiry before attempts
```

## Domain model

Everything lives in `io.github.sidchi.payments.domain` and is immutable.

| Type | What it models |
|------|----------------|
| `Money`, `Currency` | Exact amounts as a `Long` of minor units (JPY 0, USD 2, KWD 3, CLF 4 digits). No implicit rounding, no silent overflow, no cross-currency arithmetic. `allocate()` splits an amount by ratios without losing a minor unit. `Currency` is a curated ISO 4217 table (alphabetic + numeric codes) verified against the JDK in tests. |
| `ExchangeRate`, `Conversion` | A directional, timestamped, sourced rate; `convert(money, roundingMode)` returns a `Conversion` recording the input, output, rate and rounding mode, so every FX result is auditable. |
| `PaymentMethod` | Sealed: `TokenizedCard` (brand, last4, `CardExpiry`) or `Wallet` (Apple Pay, Google Pay, PayPal). Tokens only: anything that looks like a raw PAN is rejected and `toString` redacts tokens. |
| `ChargeRequest` | Idempotency key, positive **presentment** amount, payment method, customer, `ChargeInitiator` (customer- vs. merchant-initiated, which changes SCA and retry rules), and an optional **settlement** currency. |
| `ChargeResult` | Sealed: `Approved` (with an optional settlement `Conversion`), `Declined(Decline)`, or `Failed(FailureReason)`. `FailureReason.outcomeKnown` separates "definitely not charged" from "maybe charged" (timeouts), which must be retried with the same key. |
| `DeclineReason` | Sealed **soft vs. hard** taxonomy (`DeclineReason.Soft` / `DeclineReason.Hard`), each reason carrying a default `RecoveryAction`: retry later, authenticate the customer, update the payment method, or stop. |
| `Iso8583DeclineCodes` | Maps common ISO 8583 response codes (`05`, `51`, `54`, `1A`, `R0`, ...) to reasons. Unknown codes fail closed as hard declines. |

```kotlin
val request = ChargeRequest(
    idempotencyKey = IdempotencyKey("sub_123:renewal:2026-12"),
    amount = Money.of("19.99", Currency.USD),
    paymentMethod = TokenizedCard("tok_synthetic_visa", CardBrand.VISA, "4242", CardExpiry.parse("12/27")),
    customerId = "cus_123",
)

when (val result = gateway.charge(request)) {          // any PaymentGateway, e.g. a MockProcessor
    is ChargeResult.Approved -> markPaid(result.chargeId)
    is ChargeResult.Declined -> when (result.decline.recoveryAction) {
        RecoveryAction.RETRY_LATER -> scheduleRetry(request)            // same idempotency key
        RecoveryAction.AUTHENTICATE_CUSTOMER -> emailCustomerToConfirm()
        RecoveryAction.UPDATE_PAYMENT_METHOD -> requestAccountUpdate(request.paymentMethod)
        RecoveryAction.DO_NOT_RETRY -> stopDunning()
    }
    is ChargeResult.Failed ->
        if (result.reason.outcomeKnown) scheduleRetry(request) else reconcileThenRetry(request)
}
```

## Multi-currency

Exchange-rate plumbing lives in `io.github.sidchi.payments.fx`:

| Type | Role |
|------|------|
| `RateSnapshot` | A consistent set of rates published at one instant, quoted against one base currency. Cross rates are derived through the base, so `A -> B -> A` multiplies back to 1 and a snapshot can't contain arbitrage. |
| `ExchangeRateProvider` | `fun interface` returning the newest snapshot. All conversions in a batch should use one snapshot. |
| `InMemoryExchangeRateProvider` | Thread-safe mock/source for tests and simulations; refuses to publish a snapshot older than the current one. |
| `CurrencyConverter` | Converts with a required `RoundingMode` and refuses rates older than `maxRateAge` (`StaleExchangeRateException`). Same-currency conversion never touches the provider. |

```kotlin
val converter = CurrencyConverter(provider, maxRateAge = Duration.ofHours(26))

val request = ChargeRequest(
    idempotencyKey = IdempotencyKey("sub_123:renewal:2026-12"),
    amount = Money.of("12.00", Currency.GBP),          // presented to the customer
    paymentMethod = wallet,
    customerId = "cus_123",
    settlementCurrency = Currency.USD,                 // paid out to the merchant
)

val fx = converter.convert(request.amount, request.settlementCurrency, RoundingMode.HALF_EVEN)
val approved = ChargeResult.Approved(request.idempotencyKey, chargeId, request.amount, settlementConversion = fx)
approved.settlementAmount   // USD amount, with the rate, source and timestamp kept in `fx`
```

Design notes:

- **Rounding is always explicit.** `HALF_EVEN` for neutral reporting, `DOWN` for a customer quote you must honor,
  `UP` when collecting enough to cover a settlement amount. A default would hide a business decision.
- **ISO exponents, not processor exponents.** A few processors treat ISO two-decimal currencies (e.g. HUF, TWD) as
  zero-decimal on the wire; that belongs in the gateway adapter, not in `Currency`.
- **Stale rates fail loudly.** Converting on yesterday's rate is how a merchant silently eats an FX move; the caller
  decides whether to fail, settle in presentment currency, or alert.

Property-based tests (Kotest) check that conversion is monotonic, symmetric around zero, within half a minor unit of
the exact product, and that floor/ceiling bracket half-even by at most one minor unit, across every currency pair.
All rates in tests are synthetic.

## Idempotency

`io.github.sidchi.payments.idempotency` makes "charge at most once" a property of the library rather than of every
caller. A store grants a **lease** per idempotency key; only the lease holder may call the processor.

| Type | Role |
|------|------|
| `IdempotencyStore` | `tryAcquire` / `complete` / `release` / `find` / `purgeCompletedBefore`. Exactly one concurrent caller acquires a key; the rest see `InProgress`, the stored result, or a `FingerprintMismatch`. |
| `RequestFingerprint` | SHA-256 of what decides the money movement (amount, currencies, token, customer, initiator, description, metadata). A key reused for a *different* request is a client bug and is rejected, not replayed. Display-only card data is excluded so an Account Updater expiry refresh doesn't break a retry. |
| `IdempotentChargeExecutor` | Drives the protocol around a `charge` function and decides what each result means for retries (table below). |
| `InMemoryIdempotencyStore` | Lock-free per key via `ConcurrentHashMap.compute`; for tests and single-instance services. |
| `PostgresIdempotencyStore` | In `payments-idempotency-postgres`. One `INSERT ... ON CONFLICT DO UPDATE ... WHERE` per acquire; results stored as flat, queryable `jsonb` via `ChargeResultCodec`. |

| Result | Store action | A retry with the same key… |
|---|---|---|
| `Approved`, `Declined` | complete | replays the stored result |
| `Failed(INVALID_REQUEST)` | complete | replays it; the same request fails the same way |
| `Failed`, outcome known (503, 429) | release | calls the processor again immediately |
| `Failed`, outcome unknown (timeout), or an exception | leave the lease to expire | waits, then **takes over** (`lease.isTakeover`) |

```kotlin
val executor = IdempotentChargeExecutor(PostgresIdempotencyStore(dataSource), leaseDuration = Duration.ofSeconds(60))

when (val outcome = executor.execute(request) { req, lease -> gateway.charge(req) }) {   // forwards req.idempotencyKey
    is IdempotentOutcome.Executed -> respond(outcome.result)
    is IdempotentOutcome.Replayed -> respond(outcome.result)              // retry of a finished charge
    is IdempotentOutcome.InProgress -> conflict(retryAfter = outcome.retryAfter)   // HTTP 409
    is IdempotentOutcome.KeyReused -> unprocessable("Idempotency key reused")      // HTTP 422
}
```

Design notes:

- **No transaction spans the processor call.** Holding a row lock (or an open transaction) across a network call to a
  processor ties database connections to the processor's latency and turns a processor brown-out into a pool
  exhaustion. A lease is a plain row with an expiry, written and read in single auto-committed statements.
- **Unknown outcomes wait out the lease instead of releasing it.** After a timeout the charge may exist at the
  processor. Releasing would let an immediate retry race the processor; waiting for expiry gives it time to settle, and
  the takeover re-sends the *same* key so the processor's own idempotency turns it into a lookup.
- **Lease length must exceed the processor timeout.** Otherwise a slow-but-alive holder can be taken over mid-call. If
  that happens anyway, the late holder's write fails (`LeaseLostException`, surfaced as `Persistence.LEASE_LOST`) and
  the processor-side key still prevents a double charge.
- **App clock, not database `now()`.** Lease arithmetic uses the injected `Clock`, which keeps both stores identical and
  testable; hosts need NTP-synchronized clocks, and lease durations should dwarf any skew.
- **One contract, every store.** `idempotencyStoreContract` (in `payments-core` test fixtures) runs the same suite,
  including a 16-thread race over 25 keys, against the in-memory store and a real Postgres.

## Gateways and mock processors

`io.github.sidchi.payments.gateway` is the adapter boundary. Everything above it (retry engine, router, dunning)
sees only normalized `ChargeResult`s; everything processor-specific lives below it.

| Type | Role |
|------|------|
| `PaymentGateway` | One processor connection: `charge(request)` makes **one** attempt and never throws for processor outcomes; `lookup(key)` asks what happened to an earlier attempt without charging. |
| `ProcessorId` | Slug naming a *connection* (`meridian-us`), the key for future per-processor retry budgets and circuit breakers. |
| `GatewayCapabilities` | Presentment/settlement currencies, card brands and wallets a connection accepts; `supports(request)` lets a router skip guaranteed rejections. |
| `MockProcessor` | Seeded, thread-safe fake processor (below). |
| `ProcessorDialect` | A processor's raw decline codes and id formats, and their mapping to the `DeclineReason` taxonomy. |
| `MockTokens` | Magic tokens that force an outcome: `APPROVE`, `decline("51")`, `TIMEOUT`, `TIMEOUT_AFTER_PROCESSING`, `NETWORK_ERROR`, `UNAVAILABLE`, `RATE_LIMITED`. |

Two deliberately different fictional processors come ready-made in `MockProcessors`:

| | Meridian | Tessera |
|---|---|---|
| Decline codes | ISO 8583 (`51`, `05`, `54`) | snake_case (`insufficient_funds`, `card_declined`) |
| Cards / wallets | Visa, Mastercard, Amex, Discover / — | Visa, Mastercard / Apple Pay, Google Pay |
| Presentment / settlement | USD, EUR, GBP / USD, EUR, GBP | + JPY, INR, KWD / USD, EUR |

```kotlin
val flaky = MockProcessors.tessera(
    config = MockProcessorConfig(
        capabilities = MockProcessors.TESSERA_CAPABILITIES,
        latency = LatencyModel.Spiky(
            base = LatencyModel.Uniform(Duration.ofMillis(80), Duration.ofMillis(250)),
            spike = LatencyModel.Fixed(Duration.ofSeconds(15)),     // long tail -> timeouts
            spikeProbability = 0.02,
        ),
        timeout = Duration.ofSeconds(5),
        faults = FaultRates(unavailable = 0.03, rateLimited = 0.01, networkError = 0.005),
        declineRate = 0.12,
        declineCodes = mapOf("insufficient_funds" to 5, "card_declined" to 3, "expired_card" to 1),
        processedWhenResponseLost = 0.5,                           // a lost response may still have charged
    ),
    seed = 2026,
)
```

Each call draws, in order: an infrastructure fault (503/429 before processing, or a dropped connection), then a
decision (decline with a code from the mix, or approve), then latency (over the timeout means `Failed(TIMEOUT)` after
waiting exactly the timeout). Design notes:

- **Processor-side idempotency is simulated, not assumed.** The mock records results per key: a same-key retry replays
  the original charge, a key reused with different parameters is `INVALID_REQUEST`. Paired with
  `IdempotentChargeExecutor`, a takeover calls `lookup` first, so "timed out but charged" ends as exactly one charge
  (see `GatewayIdempotencySpec`).
- **Timeouts are emergent.** They come from the latency model's tail crossing the client deadline, not from a separate
  "timeout rate", so tightening a timeout in a test changes the failure rate the way it would in production.
- **Raw codes stay in the processor's dialect.** Configs and magic tokens use the processor's own codes, and the
  dialect owns the mapping. A code from the wrong dialect (`"51"` sent to Tessera) normalizes to `Unknown`, which fails
  closed as a hard decline — the safe default for a code an adapter has never seen.
- **Synchronous for now.** `charge` blocks for the simulated latency through an injectable `Sleeper`, so tests run in
  microseconds and assert exact waits. Suspend APIs arrive with the coroutines roadmap item.
- **Reproducible.** One seed drives every draw; the same seed, config and requests give the same results.

## Modules

| Module          | Purpose                                                        |
|-----------------|----------------------------------------------------------------|
| `payments-core` | Domain model, FX, idempotency, gateway abstraction and mock processors; retry and fallback next (pure Kotlin, JVM, no runtime deps) |
| `payments-idempotency-postgres` | `PostgresIdempotencyStore` + schema migration (`db/migration/V1__create_payment_idempotency_keys.sql`); plain JDBC, bring your own driver and pool |

A sample Ktor service arrives with a later roadmap item.

## Build

Requires JDK 21 (bytecode targets Java 17). The Gradle wrapper downloads everything else.

```bash
./gradlew build          # compile, detekt (incl. ktlint rules), unit tests
./gradlew test           # tests only
./gradlew detekt         # static analysis only; HTML report in payments-core/build/reports/detekt/
```

The Postgres store's integration tests run when `PAYMENTS_TEST_POSTGRES_URL` points at a disposable database (CI
provides one as a service container; without it they're skipped locally and fail on CI):

```bash
docker run --rm -d -p 5432:5432 -e POSTGRES_PASSWORD=payments -e POSTGRES_USER=payments \
  -e POSTGRES_DB=payments_test postgres:16-alpine
PAYMENTS_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/payments_test?user=payments&password=payments' \
  ./gradlew :payments-idempotency-postgres:test
```

## Tooling

- **Kotlin 2.1** with [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)
  — every public declaration states its visibility and type, so the library's API surface is deliberate.
- **Gradle (Kotlin DSL)** with a version catalog in `gradle/libs.versions.toml`.
- **JUnit 5 + Kotest** on the JUnit Platform: Kotest for specs and property-based tests, JUnit where it reads better.
- **detekt** with the `detekt-formatting` (ktlint) rule set; config in `config/detekt/detekt.yml`, zero issues allowed.
- **GitHub Actions** runs `./gradlew build` on every push and pull request.

## Roadmap

See [ROADMAP.md](ROADMAP.md). Each item ships as one reviewed pull request.
