# kotlin-payments-lib

[![CI](https://github.com/sidchi/kotlin-payments-lib/actions/workflows/ci.yml/badge.svg)](https://github.com/sidchi/kotlin-payments-lib/actions/workflows/ci.yml)

A Kotlin library for **resilient recurring payments**: idempotent charges, decline-aware retries, fallback across
processors, multi-currency money, and an Account Updater simulator. Gateways are mocked; there are no real processor
credentials or proprietary code, and every card number in this repo is synthetic.

> **Status:** core domain model in place (money, payment methods, charges, decline taxonomy). Multi-currency
> conversion is next. See [ROADMAP.md](ROADMAP.md).

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
| `Money`, `Currency` | Exact amounts as a `Long` of minor units (JPY 0, USD 2, KWD 3 digits). No implicit rounding, no silent overflow, no cross-currency arithmetic. `allocate()` splits an amount by ratios without losing a minor unit. |
| `PaymentMethod` | Sealed: `TokenizedCard` (brand, last4, `CardExpiry`) or `Wallet` (Apple Pay, Google Pay, PayPal). Tokens only: anything that looks like a raw PAN is rejected and `toString` redacts tokens. |
| `ChargeRequest` | Idempotency key, positive amount, payment method, customer, and `ChargeInitiator` (customer- vs. merchant-initiated, which changes SCA and retry rules). |
| `ChargeResult` | Sealed: `Approved`, `Declined(Decline)`, or `Failed(FailureReason)`. `FailureReason.outcomeKnown` separates "definitely not charged" from "maybe charged" (timeouts), which must be retried with the same key. |
| `DeclineReason` | Sealed **soft vs. hard** taxonomy (`DeclineReason.Soft` / `DeclineReason.Hard`), each reason carrying a default `RecoveryAction`: retry later, authenticate the customer, update the payment method, or stop. |
| `Iso8583DeclineCodes` | Maps common ISO 8583 response codes (`05`, `51`, `54`, `1A`, `R0`, ...) to reasons. Unknown codes fail closed as hard declines. |

```kotlin
val request = ChargeRequest(
    idempotencyKey = IdempotencyKey("sub_123:renewal:2026-12"),
    amount = Money.of("19.99", Currency.USD),
    paymentMethod = TokenizedCard("tok_synthetic_visa", CardBrand.VISA, "4242", CardExpiry.parse("12/27")),
    customerId = "cus_123",
)

when (val result = gateway.charge(request)) {          // gateway arrives in roadmap item 5
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

## Modules

| Module          | Purpose                                                        |
|-----------------|----------------------------------------------------------------|
| `payments-core` | Domain model, retry and fallback primitives (pure Kotlin, JVM) |

More modules (Postgres idempotency store, sample Ktor service) arrive with later roadmap items.

## Build

Requires JDK 21 (bytecode targets Java 17). The Gradle wrapper downloads everything else.

```bash
./gradlew build          # compile, detekt (incl. ktlint rules), unit tests
./gradlew test           # tests only
./gradlew detekt         # static analysis only; HTML report in payments-core/build/reports/detekt/
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
