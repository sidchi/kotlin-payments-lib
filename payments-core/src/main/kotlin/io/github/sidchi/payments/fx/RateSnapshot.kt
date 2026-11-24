package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.ExchangeRate
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * A consistent set of exchange rates published at one instant, quoted against a single [base] currency — the shape
 * of most reference-rate feeds (e.g. a daily table of "units per 1 EUR").
 *
 * Quoting everything against one base keeps the snapshot free of arbitrage: any cross rate `A -> B` is derived as
 * `perBase[B] / perBase[A]`, so `A -> B -> A` always multiplies back to 1 (to 34 significant digits). Rates out of
 * the base are returned exactly as published, without a division.
 *
 * Every [ExchangeRate] handed out carries this snapshot's [asOf] and [source].
 */
public class RateSnapshot(
    /** Currency all rates are quoted against. */
    public val base: Currency,
    rates: Map<Currency, BigDecimal>,
    /** When the source published these rates. */
    public val asOf: Instant,
    /** Where the rates came from, e.g. `"mock"`. */
    public val source: String,
) {
    /** Units of each currency per one unit of [base]; always contains `base -> 1`. */
    public val ratesPerBase: Map<Currency, BigDecimal> = buildMap {
        putAll(rates)
        put(base, BigDecimal.ONE)
    }

    init {
        require(source.isNotBlank()) { "Snapshot source must not be blank" }
        rates[base]?.let {
            require(it.compareTo(BigDecimal.ONE) == 0) { "Rate for the base currency $base must be 1, was $it" }
        }
        rates.forEach { (currency, rate) ->
            require(rate.signum() > 0) { "Rate for $currency must be positive, was $rate" }
        }
    }

    /** Currencies this snapshot can convert between. */
    public val currencies: Set<Currency> get() = ratesPerBase.keys

    /** True when this snapshot has a rate for [currency]. */
    public operator fun contains(currency: Currency): Boolean = currency in ratesPerBase

    /**
     * The rate for converting [from] into [to].
     *
     * @throws ExchangeRateUnavailableException if either currency is missing from the snapshot.
     */
    public fun rate(from: Currency, to: Currency): ExchangeRate {
        if (from == to) return ExchangeRate.identity(from, asOf)
        val fromPerBase = ratesPerBase[from] ?: throw ExchangeRateUnavailableException(from, to, source)
        val toPerBase = ratesPerBase[to] ?: throw ExchangeRateUnavailableException(from, to, source)
        val cross = if (from == base) toPerBase else toPerBase.divide(fromPerBase, MathContext.DECIMAL128)
        return ExchangeRate(from, to, cross, asOf, source)
    }

    /** Summary such as `RateSnapshot(base=EUR, currencies=12, asOf=..., source=mock)`. */
    override fun toString(): String =
        "RateSnapshot(base=$base, currencies=${ratesPerBase.size}, asOf=$asOf, source=$source)"
}
