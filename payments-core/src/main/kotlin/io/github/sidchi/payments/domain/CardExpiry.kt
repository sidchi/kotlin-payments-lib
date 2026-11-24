package io.github.sidchi.payments.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * A card's expiry month and year.
 *
 * Cards are valid through the **last day** of the expiry month, so `12/2027` is still chargeable on 2027-12-31 and
 * expired on 2028-01-01. Expiry is a common hard decline and the main thing Account Updater refreshes.
 */
public data class CardExpiry(
    /** Expiry month, 1–12. */
    public val month: Int,
    /** Four-digit expiry year. */
    public val year: Int,
) {
    init {
        require(month in MONTHS) { "Expiry month must be 1-12, was $month" }
        require(year in YEARS) { "Expiry year must be four digits in 2000-2099, was $year" }
    }

    /** The expiry as a [YearMonth]. */
    public val yearMonth: YearMonth get() = YearMonth.of(year, month)

    /** True when the card can no longer be charged on [date] (i.e. [date] is after the expiry month). */
    public fun isExpiredOn(date: LocalDate): Boolean = YearMonth.from(date).isAfter(yearMonth)

    /** The `MM/YY` form printed on cards, e.g. `03/28`. */
    override fun toString(): String = "%02d/%02d".format(month, year % YEARS_PER_CENTURY)

    /** Parsing helpers. */
    public companion object {
        private val MM_YY = Regex("""(\d{2})/(\d{2})""")
        private val MONTHS = 1..12
        private val YEARS = 2000..2099
        private const val YEARS_PER_CENTURY = 100

        /**
         * Parses the `MM/YY` form used on cards and in Account Updater files, assuming the 2000s.
         *
         * @throws IllegalArgumentException on malformed input or an invalid month.
         */
        public fun parse(text: String): CardExpiry {
            val match = requireNotNull(MM_YY.matchEntire(text.trim())) { "Expected MM/YY, was '$text'" }
            val (month, year) = match.destructured
            return CardExpiry(month.toInt(), YEARS.first + year.toInt())
        }
    }
}
