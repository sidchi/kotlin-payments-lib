# Roadmap — kotlin-payments-lib
A Kotlin library for resilient recurring payments: idempotent charges, retry/fallback across processors, multi-currency money, and an Account Updater simulator. Gateways are mocked; no real processor credentials or proprietary code. Each item is one PR. The daily pipeline takes the first unchecked item.
- [x] 1. Scaffold: Kotlin 2.x, Gradle (Kotlin DSL), JUnit 5 + Kotest, detekt/ktlint, GitHub Actions CI, README
- [x] 2. Core domain: Money, PaymentMethod (tokenized card, wallet), ChargeRequest, ChargeResult, sealed decline taxonomy (soft vs. hard declines)
- [x] 3. Multi-currency: ISO 4217 currency metadata (minor units, e.g. JPY 0, USD 2, KWD 3), exchange-rate provider interface with timestamped rate snapshots, conversion with explicit rounding mode, presentment vs. settlement currency on charges, mock rate source with property-based tests
- [x] 4. Idempotency: idempotency-key store interface, in-memory and Postgres implementations, concurrency tests
- [ ] 5. Gateway abstraction + two mock processors with configurable latency, error rates, and decline codes
- [ ] 6. Retry engine: decline-code-aware retry policy, exponential backoff with jitter, max-attempt and time-window limits
- [ ] 7. Processor fallback / cascading: route to secondary processor on eligible failures, circuit breaker per processor
- [ ] 8. Account Updater simulator: batch file in/out (updated PAN/expiry, closed account, contact cardholder) plus a real-time update hook
- [ ] 9. Coroutines + observability: suspend APIs, structured logging, Micrometer metrics, sample Grafana dashboard JSON
- [ ] 10. Load test: Gatling or k6 against a sample Ktor service, documented throughput and p99 results
- [ ] 11. Polish: KDoc site, usage examples, design doc on retry/fallback trade-offs, publish to GitHub Packages, v1.0 release
