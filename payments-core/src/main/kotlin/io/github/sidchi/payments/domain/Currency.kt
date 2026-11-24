package io.github.sidchi.payments.domain

/**
 * An ISO 4217 currency: alphabetic code, numeric code, and the number of digits in its minor unit.
 *
 * The minor-unit exponent is what makes money arithmetic safe: `USD 1.00` is 100 minor units, `JPY 100` is 100 minor
 * units, and `KWD 1.000` is 1,000. Amounts are always stored as an integer count of minor units (see [Money]).
 *
 * The registry is a curated table of commonly settled currencies, kept as reviewable data in this file rather than
 * read from `java.util.Currency` at runtime: the JDK table also contains withdrawn currencies (DEM, FRF, ...) and
 * changes with the JDK version, while a payments library wants a stable, explicit list. A unit test cross-checks
 * every entry against the JDK so typos can't slip in.
 *
 * Note that ISO 4217 is the *accounting* exponent. Some processors use a different one on the wire for a few
 * currencies (e.g. treating ISK or HUF as zero-decimal); that mapping belongs in the gateway adapter, not here.
 *
 * Instances are interned, so obtain them with [of], [ofNumeric] or the named constants.
 */
public class Currency private constructor(
    /** Three-letter upper-case ISO 4217 alphabetic code, e.g. `USD`. */
    public val code: String,
    /** ISO 4217 numeric code, e.g. 840 for USD; this is what ISO 8583 messages carry in field 49. */
    public val numericCode: Int,
    /** Number of decimal digits in the minor unit, e.g. 2 for USD, 0 for JPY, 3 for KWD. */
    public val minorUnits: Int,
) {
    /** The numeric code zero-padded to three digits, e.g. `036` for AUD. */
    public val numericCodeAsString: String get() = numericCode.toString().padStart(NUMERIC_CODE_LENGTH, '0')

    /** Currencies are equal when their ISO codes are equal. */
    override fun equals(other: Any?): Boolean = other is Currency && other.code == code

    /** Hash of the ISO code, consistent with [equals]. */
    override fun hashCode(): Int = code.hashCode()

    /** The ISO code, e.g. `USD`. */
    override fun toString(): String = code

    /** Named constants and lookup by ISO code. */
    public companion object {
        private const val NUMERIC_CODE_LENGTH = 3

        // code, numeric code, minor units. Sorted by code; one row per currency.
        @Suppress("LongMethod", "MagicNumber") // the table is data, not logic
        private fun table(): List<Currency> = listOf(
            Currency("AED", 784, 2),
            Currency("ARS", 32, 2),
            Currency("AUD", 36, 2),
            Currency("BDT", 50, 2),
            Currency("BHD", 48, 3),
            Currency("BRL", 986, 2),
            Currency("CAD", 124, 2),
            Currency("CHF", 756, 2),
            Currency("CLF", 990, 4),
            Currency("CLP", 152, 0),
            Currency("CNY", 156, 2),
            Currency("COP", 170, 2),
            Currency("CZK", 203, 2),
            Currency("DKK", 208, 2),
            Currency("EGP", 818, 2),
            Currency("EUR", 978, 2),
            Currency("GBP", 826, 2),
            Currency("HKD", 344, 2),
            Currency("HUF", 348, 2),
            Currency("IDR", 360, 2),
            Currency("ILS", 376, 2),
            Currency("INR", 356, 2),
            Currency("IQD", 368, 3),
            Currency("ISK", 352, 0),
            Currency("JOD", 400, 3),
            Currency("JPY", 392, 0),
            Currency("KES", 404, 2),
            Currency("KRW", 410, 0),
            Currency("KWD", 414, 3),
            Currency("LYD", 434, 3),
            Currency("MXN", 484, 2),
            Currency("MYR", 458, 2),
            Currency("NGN", 566, 2),
            Currency("NOK", 578, 2),
            Currency("NZD", 554, 2),
            Currency("OMR", 512, 3),
            Currency("PEN", 604, 2),
            Currency("PHP", 608, 2),
            Currency("PKR", 586, 2),
            Currency("PLN", 985, 2),
            Currency("PYG", 600, 0),
            Currency("SAR", 682, 2),
            Currency("SEK", 752, 2),
            Currency("SGD", 702, 2),
            Currency("THB", 764, 2),
            Currency("TND", 788, 3),
            Currency("TRY", 949, 2),
            Currency("TWD", 901, 2),
            Currency("UGX", 800, 0),
            Currency("USD", 840, 2),
            Currency("VND", 704, 0),
            Currency("XAF", 950, 0),
            Currency("XOF", 952, 0),
            Currency("ZAR", 710, 2),
        )

        // Declared before the constants below so they can be looked up from it.
        private val registry: Map<String, Currency> = table().associateBy { it.code }
        private val byNumeric: Map<Int, Currency> = registry.values.associateBy { it.numericCode }

        /** US dollar, 2 minor-unit digits. */
        public val USD: Currency = registry.getValue("USD")

        /** Euro, 2 minor-unit digits. */
        public val EUR: Currency = registry.getValue("EUR")

        /** Pound sterling, 2 minor-unit digits. */
        public val GBP: Currency = registry.getValue("GBP")

        /** Indian rupee, 2 minor-unit digits. */
        public val INR: Currency = registry.getValue("INR")

        /** Japanese yen, no minor unit. */
        public val JPY: Currency = registry.getValue("JPY")

        /** Kuwaiti dinar, 3 minor-unit digits. */
        public val KWD: Currency = registry.getValue("KWD")

        /** Chilean unidad de fomento, 4 minor-unit digits: the edge case that breaks "everything has 2 decimals". */
        public val CLF: Currency = registry.getValue("CLF")

        /** Every currency in the registry; handy for property-based tests. */
        public val all: Collection<Currency> get() = registry.values

        /**
         * Looks up a currency by its ISO 4217 alphabetic code (case-insensitive).
         *
         * @throws IllegalArgumentException if the code is not in the registry.
         */
        public fun of(code: String): Currency {
            val normalized = code.trim().uppercase()
            return requireNotNull(registry[normalized]) { "Unsupported currency code: '$code'" }
        }

        /**
         * Looks up a currency by its ISO 4217 numeric code, e.g. `ofNumeric(840)` or `ofNumeric("036")` as found in
         * ISO 8583 field 49.
         *
         * @throws IllegalArgumentException if the code is not in the registry.
         */
        public fun ofNumeric(numericCode: Int): Currency =
            requireNotNull(byNumeric[numericCode]) { "Unsupported numeric currency code: $numericCode" }

        /** String overload of [ofNumeric] accepting zero-padded codes such as `"036"`. */
        public fun ofNumeric(numericCode: String): Currency {
            val parsed = requireNotNull(numericCode.trim().toIntOrNull()) {
                "Numeric currency code must be digits: '$numericCode'"
            }
            return ofNumeric(parsed)
        }
    }
}
