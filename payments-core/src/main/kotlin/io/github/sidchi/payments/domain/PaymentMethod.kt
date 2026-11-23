package io.github.sidchi.payments.domain

/**
 * A way to pay, always represented by a vault or network **token** — never a raw card number.
 *
 * Keeping PANs out of the domain model keeps this library (and anything that logs its objects) out of PCI DSS scope.
 * As a guardrail, constructors reject tokens that look like a bare 13–19 digit PAN, and `toString` redacts tokens.
 */
public sealed interface PaymentMethod {
    /** Opaque token issued by the vault, processor or wallet. */
    public val token: String
}

/** Card network of a tokenized card. */
public enum class CardBrand {
    /** Visa. */
    VISA,

    /** Mastercard. */
    MASTERCARD,

    /** American Express. */
    AMEX,

    /** Discover. */
    DISCOVER,

    /** Any other network. */
    OTHER,
}

/** Digital wallet that issued a [Wallet] token. */
public enum class WalletProvider {
    /** Apple Pay (device-bound network token). */
    APPLE_PAY,

    /** Google Pay. */
    GOOGLE_PAY,

    /** PayPal billing agreement. */
    PAYPAL,
}

/**
 * A card stored in a vault, referenced by token plus the non-sensitive display data needed for receipts,
 * dunning emails and Account Updater matching.
 */
public data class TokenizedCard(
    override val token: String,
    /** Card network. */
    public val brand: CardBrand,
    /** Last four digits of the card number, for display only. */
    public val last4: String,
    /** Expiry month and year. */
    public val expiry: CardExpiry,
) : PaymentMethod {
    init {
        requireValidToken(token)
        require(LAST4.matches(last4)) { "last4 must be exactly four digits" }
    }

    /** Redacts the token so card objects are safe to log. */
    override fun toString(): String = "TokenizedCard(brand=$brand, last4=$last4, expiry=$expiry, token=${redact(token)})"

    private companion object {
        val LAST4 = Regex("""\d{4}""")
    }
}

/**
 * A wallet payment method (Apple Pay, Google Pay, PayPal), optionally with the display details of the card behind it.
 */
public data class Wallet(
    /** Wallet that issued the token. */
    public val provider: WalletProvider,
    override val token: String,
    /** Brand of the funding card, when the wallet discloses it. */
    public val cardBrand: CardBrand? = null,
    /** Last four digits of the funding card's device or network token, when disclosed. */
    public val last4: String? = null,
) : PaymentMethod {
    init {
        requireValidToken(token)
    }

    /** Redacts the token so wallet objects are safe to log. */
    override fun toString(): String =
        "Wallet(provider=$provider, cardBrand=$cardBrand, last4=$last4, token=${redact(token)})"
}

private val LOOKS_LIKE_PAN = Regex("""\d{13,19}""")

private fun requireValidToken(token: String) {
    require(token.isNotBlank()) { "Payment method token must not be blank" }
    require(!LOOKS_LIKE_PAN.matches(token.filterNot { it == ' ' || it == '-' })) {
        "Payment method token looks like a raw card number; tokenize it before it reaches this library"
    }
}

private fun redact(token: String): String = if (token.length <= 4) "****" else "****" + token.takeLast(4)
