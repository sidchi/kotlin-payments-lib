package io.github.sidchi.payments.domain

/**
 * An ISO 4217 currency and the number of digits in its minor unit.
 *
 * The minor-unit exponent is what makes money arithmetic safe: `USD 1.00` is 100 minor units, `JPY 100` is 100 minor
 * units, and `KWD 1.000` is 1,000. Amounts are always stored as an integer count of minor units (see [Money]).
 *
 * This registry holds a handful of common currencies; the full ISO 4217 table and exchange rates arrive with the
 * multi-currency roadmap item. Instances are interned, so obtain them with [of] or the named constants.
 */
public class Currency private constructor(
    /** Three-letter upper-case ISO 4217 alphabetic code, e.g. `USD`. */
    public val code: String,
    /** Number of decimal digits in the minor unit, e.g. 2 for USD, 0 for JPY, 3 for KWD. */
    public val minorUnits: Int,
) {
    /** Currencies are equal when their ISO codes are equal. */
    override fun equals(other: Any?): Boolean = other is Currency && other.code == code

    /** Hash of the ISO code, consistent with [equals]. */
    override fun hashCode(): Int = code.hashCode()

    /** The ISO code, e.g. `USD`. */
    override fun toString(): String = code

    /** Named constants and lookup by ISO code. */
    public companion object {
        /** US dollar, 2 minor-unit digits. */
        public val USD: Currency = Currency("USD", 2)

        /** Euro, 2 minor-unit digits. */
        public val EUR: Currency = Currency("EUR", 2)

        /** Pound sterling, 2 minor-unit digits. */
        public val GBP: Currency = Currency("GBP", 2)

        /** Indian rupee, 2 minor-unit digits. */
        public val INR: Currency = Currency("INR", 2)

        /** Japanese yen, no minor unit. */
        public val JPY: Currency = Currency("JPY", 0)

        /** Kuwaiti dinar, 3 minor-unit digits. */
        public val KWD: Currency = Currency("KWD", 3)

        private val registry: Map<String, Currency> =
            listOf(USD, EUR, GBP, INR, JPY, KWD).associateBy { it.code }

        /** Every currency in the registry; handy for property-based tests. */
        public val all: Collection<Currency> get() = registry.values

        /**
         * Looks up a currency by its ISO 4217 code (case-insensitive).
         *
         * @throws IllegalArgumentException if the code is not in the registry.
         */
        public fun of(code: String): Currency {
            val normalized = code.trim().uppercase()
            return requireNotNull(registry[normalized]) { "Unsupported currency code: '$code'" }
        }
    }
}
