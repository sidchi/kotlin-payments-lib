package io.github.sidchi.payments.domain

import java.math.BigDecimal
import kotlin.math.sign

/**
 * An exact amount of a single [Currency], stored as a signed count of minor units.
 *
 * Design choices:
 * - **Integer minor units, not `BigDecimal` or `Double`.** Processors, ledgers and card networks all speak minor
 *   units, and a `Long` can't carry a hidden fraction of a cent. `BigDecimal` is used only at the edges ([of],
 *   [toDecimal]).
 * - **No implicit rounding.** [of] rejects amounts with more precision than the currency allows; rounding is a
 *   business decision (per line vs. per invoice) and belongs to the caller, with an explicit rounding mode.
 * - **No silent overflow.** Arithmetic uses `Math.*Exact` and throws [ArithmeticException] instead of wrapping.
 * - **No cross-currency arithmetic.** Adding USD to EUR throws; conversion is an explicit, rate-stamped operation.
 *
 * Negative amounts are allowed (refunds, credits, proration adjustments); a charge enforces positivity itself.
 */
public data class Money(
    /** Signed amount in minor units of [currency], e.g. 1234 for `USD 12.34`. */
    public val amountMinor: Long,
    /** Currency of this amount. */
    public val currency: Currency,
) : Comparable<Money> {
    /** True when the amount is exactly zero. */
    public val isZero: Boolean get() = amountMinor == 0L

    /** True when the amount is strictly greater than zero. */
    public val isPositive: Boolean get() = amountMinor > 0L

    /** True when the amount is strictly less than zero. */
    public val isNegative: Boolean get() = amountMinor < 0L

    /** Sum of two amounts in the same currency. */
    public operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    /** Difference of two amounts in the same currency. */
    public operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(amountMinor, other.amountMinor), currency)
    }

    /** This amount multiplied by an integer quantity, e.g. a per-seat price times seats. */
    public operator fun times(quantity: Long): Money = Money(Math.multiplyExact(amountMinor, quantity), currency)

    /** The same amount with the opposite sign, e.g. to turn a charge into a refund. */
    public operator fun unaryMinus(): Money = Money(Math.negateExact(amountMinor), currency)

    /** Orders amounts of the same currency; comparing different currencies is a programming error and throws. */
    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amountMinor.compareTo(other.amountMinor)
    }

    /**
     * Splits this amount into parts proportional to [ratios] without creating or losing a single minor unit.
     *
     * Each part is the truncated proportional share; the leftover minor units are then handed out one at a time,
     * in order, to parts with a non-zero ratio. The parts always sum exactly to this amount. Typical uses: splitting
     * a discount across invoice lines, or a proration credit across billing periods.
     *
     * @param ratios non-negative weights with a positive sum, e.g. `[1, 1, 1]` for an even three-way split.
     */
    public fun allocate(ratios: List<Int>): List<Money> {
        require(ratios.isNotEmpty()) { "ratios must not be empty" }
        require(ratios.all { it >= 0 }) { "ratios must be non-negative: $ratios" }
        val total = ratios.sumOf { it.toLong() }
        require(total > 0) { "ratios must have a positive sum: $ratios" }

        // Integer division truncates toward zero, so the remainder carries the sign of the amount.
        val shares = ratios.map { Math.multiplyExact(amountMinor, it.toLong()) / total }.toLongArray()
        var remainder = amountMinor - shares.sum()
        val step = remainder.sign.toLong()
        for (i in shares.indices) {
            if (remainder == 0L) break
            if (ratios[i] > 0) {
                shares[i] += step
                remainder -= step
            }
        }
        return shares.map { Money(it, currency) }
    }

    /** The amount in major units with the currency's exact scale, e.g. `12.34` for 1234 USD minor units. */
    public fun toDecimal(): BigDecimal = BigDecimal.valueOf(amountMinor, currency.minorUnits)

    /** Human-readable form such as `USD 12.34`, `JPY 500` or `KWD 1.250`. */
    override fun toString(): String = "${currency.code} ${toDecimal().toPlainString()}"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
    }

    /** Factory functions. */
    public companion object {
        /** An amount given directly in minor units, e.g. `ofMinor(1234, USD)` is `USD 12.34`. */
        public fun ofMinor(amountMinor: Long, currency: Currency): Money = Money(amountMinor, currency)

        /** Zero in the given currency. */
        public fun zero(currency: Currency): Money = Money(0L, currency)

        /**
         * An amount given in major units, e.g. `of(BigDecimal("12.34"), USD)`.
         *
         * @throws IllegalArgumentException if [amount] has more decimal places than the currency's minor unit
         *   (`12.345 USD`, `1.5 JPY`). Round explicitly before calling.
         * @throws ArithmeticException if the amount does not fit in a `Long` of minor units.
         */
        public fun of(amount: BigDecimal, currency: Currency): Money {
            require(amount.stripTrailingZeros().scale() <= currency.minorUnits) {
                "$amount has more precision than $currency allows (${currency.minorUnits} decimal places)"
            }
            return Money(amount.movePointRight(currency.minorUnits).longValueExact(), currency)
        }

        /** Convenience overload of [of] taking a decimal string such as `"12.34"`. */
        public fun of(amount: String, currency: Currency): Money = of(BigDecimal(amount), currency)
    }
}
