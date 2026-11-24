package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.Currency
import java.math.BigDecimal
import java.time.Instant

/** Synthetic sample rates for tests. Plausible-looking, but not market data. */
internal object SampleRates {
    val ASOF: Instant = Instant.parse("2026-11-24T00:00:00Z")

    fun eurSnapshot(asOf: Instant = ASOF): RateSnapshot = RateSnapshot(
        base = Currency.EUR,
        rates = mapOf(
            Currency.USD to BigDecimal("1.0842"),
            Currency.GBP to BigDecimal("0.8567"),
            Currency.JPY to BigDecimal("162.35"),
            Currency.INR to BigDecimal("90.12"),
            Currency.KWD to BigDecimal("0.3331"),
        ),
        asOf = asOf,
        source = "mock",
    )
}
