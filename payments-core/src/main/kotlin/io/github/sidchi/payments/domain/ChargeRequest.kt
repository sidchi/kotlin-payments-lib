package io.github.sidchi.payments.domain

/**
 * Client-supplied key that makes a charge safe to retry: the same key must never move money twice.
 *
 * Keys are opaque to the library. A good key is derived from the business event, e.g.
 * `"sub_123:renewal:2026-12"`, so a crashed-and-restarted billing job produces the same key again.
 */
@JvmInline
public value class IdempotencyKey(
    /** The key itself: 1–255 characters, not blank. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "Idempotency key must not be blank" }
        require(value.length <= MAX_LENGTH) { "Idempotency key must be at most $MAX_LENGTH characters" }
    }

    /** Returns the raw key. */
    override fun toString(): String = value

    /** Limits. */
    public companion object {
        /** Maximum key length, matching common processor limits. */
        public const val MAX_LENGTH: Int = 255
    }
}

/**
 * Who started the charge. Card networks treat the two differently: merchant-initiated recurring charges are exempt
 * from strong customer authentication but are subject to stricter retry limits, while customer-initiated charges can
 * be challenged (3-D Secure) because the customer is present.
 */
public enum class ChargeInitiator {
    /** The customer is present and initiated the payment (checkout, manual "pay now"). */
    CUSTOMER,

    /** The merchant charges a stored credential under a prior agreement (subscription renewal, retry, dunning). */
    MERCHANT,
}

/**
 * A request to charge [amount] to [paymentMethod].
 *
 * The request is a value: retrying means re-sending the *same* request with the *same* [idempotencyKey].
 */
public data class ChargeRequest(
    /** Deduplicates retries of this charge. */
    public val idempotencyKey: IdempotencyKey,
    /** Amount to charge; must be positive (refunds are a separate operation). */
    public val amount: Money,
    /** Tokenized card or wallet to charge. */
    public val paymentMethod: PaymentMethod,
    /** Merchant's customer identifier. */
    public val customerId: String,
    /** Whether the customer or the merchant initiated the charge. Defaults to [ChargeInitiator.MERCHANT]. */
    public val initiator: ChargeInitiator = ChargeInitiator.MERCHANT,
    /** Statement descriptor or internal description, if any. */
    public val description: String? = null,
    /** Free-form key/value pairs passed through to the processor (e.g. subscription and invoice ids). */
    public val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(amount.isPositive) { "Charge amount must be positive, was $amount" }
        require(customerId.isNotBlank()) { "customerId must not be blank" }
    }
}
