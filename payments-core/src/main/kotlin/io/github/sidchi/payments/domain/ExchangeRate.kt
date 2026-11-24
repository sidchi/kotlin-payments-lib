package io.github.sidchi.payments.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/**
 * A directional, timestamped exchange rate: one unit of [from] buys [rate] units of [to].
 *
 * Rates are `BigDecimal` and live only at the conversion boundary; the amounts on either side stay exact [Money].
 * The timestamp and [source] travel with every [Conversion] so a settled charge can always answer "which rate, from
 * where, as of when" — the first question in any FX reconciliation or dispute.
 *
 * Equality is `data class` equality, so `1.10` and `1.100` are different rates; compare [rate] with `compareTo` when
 * you mean numeric equality.
 */
public data class ExchangeRate(
    /** Currency being sold. */
    public val from: Currency,
    /** Currency being bought. */
    public val to: Currency,
    /** Units of [to] per one unit of [from], in major units. Must be positive. */
    public val rate: BigDecimal,
    /** When the source published this rate. */
    public val asOf: Instant,
    /** Where the rate came from, e.g. `"ecb-reference"` or `"mock"`. */
    public val source: String,
) {
    init {
        require(rate.signum() > 0) { "Exchange rate must be positive, was $rate" }
        require(from != to || rate.compareTo(BigDecimal.ONE) == 0) {
            "A $from->$to rate must be exactly 1, was $rate"
        }
        require(source.isNotBlank()) { "Exchange rate source must not be blank" }
    }

    /**
     * Converts [money] (which must be in [from]) into [to], rounding to [to]'s minor unit with [roundingMode].
     *
     * The rounding mode is deliberately a required argument. Which way a fraction of a cent goes is a business
     * decision — `HALF_EVEN` for neutral reporting, `DOWN` when quoting a customer a price you must honor, `UP` when
     * computing how much to collect to cover a settlement amount — and a default would hide it.
     *
     * @throws ArithmeticException if [roundingMode] is [RoundingMode.UNNECESSARY] and the result is inexact, or if
     *   the result does not fit in a `Long` of minor units.
     */
    public fun convert(money: Money, roundingMode: RoundingMode): Conversion {
        require(money.currency == from) { "Cannot convert ${money.currency} with a $from->$to rate" }
        val exact = money.toDecimal().multiply(rate)
        val converted = Money.of(exact.setScale(to.minorUnits, roundingMode), to)
        return Conversion(source = money, converted = converted, rate = this, roundingMode = roundingMode)
    }

    /** The reverse rate (`to -> from`), computed with 34 significant digits ([MathContext.DECIMAL128]). */
    public fun inverse(): ExchangeRate =
        copy(from = to, to = from, rate = BigDecimal.ONE.divide(rate, MathContext.DECIMAL128))

    /** Factories. */
    public companion object {
        /** The trivial rate of 1 between a currency and itself. */
        public fun identity(currency: Currency, asOf: Instant): ExchangeRate =
            ExchangeRate(currency, currency, BigDecimal.ONE, asOf, IDENTITY_SOURCE)

        /** [source] used by [identity] rates. */
        public const val IDENTITY_SOURCE: String = "identity"
    }
}

/**
 * The full record of one currency conversion: what went in, what came out, and exactly how.
 *
 * Keeping the rate and rounding mode next to the result (rather than just the converted [Money]) is what makes a
 * conversion auditable and reproducible later.
 */
public data class Conversion(
    /** Amount before conversion, in [ExchangeRate.from]. */
    public val source: Money,
    /** Amount after conversion and rounding, in [ExchangeRate.to]. */
    public val converted: Money,
    /** Rate applied. */
    public val rate: ExchangeRate,
    /** How the unrounded result was rounded to [converted]'s minor unit. */
    public val roundingMode: RoundingMode,
) {
    init {
        require(source.currency == rate.from) { "Source $source does not match rate ${rate.from}->${rate.to}" }
        require(converted.currency == rate.to) { "Result $converted does not match rate ${rate.from}->${rate.to}" }
    }
}
