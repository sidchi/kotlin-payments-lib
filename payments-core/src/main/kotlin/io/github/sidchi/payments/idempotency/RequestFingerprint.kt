package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.TokenizedCard
import io.github.sidchi.payments.domain.Wallet
import java.security.MessageDigest

/**
 * SHA-256 digest of the parts of a [ChargeRequest] that decide *what money moves*.
 *
 * An idempotency key says "this is the same charge as before"; the fingerprint checks that claim. Reusing a key for a
 * different amount, card or customer is a client bug (a key derived from the wrong field, a copy-pasted constant), and
 * silently replaying the old result would hide it. Stores reject such a request with
 * [AcquireResult.FingerprintMismatch] instead.
 *
 * Included: amount and currency, settlement currency, payment-method kind and token, customer, initiator, description
 * and metadata. Excluded: display-only card data (last4, expiry), because Account Updater can refresh an expiry
 * between a crashed attempt and its retry without making it a different charge.
 */
@JvmInline
public value class RequestFingerprint(
    /** Lower-case hex SHA-256, 64 characters. */
    public val value: String,
) {
    init {
        require(HEX_SHA256.matches(value)) { "Fingerprint must be 64 lower-case hex characters" }
    }

    /** Returns the hex digest. */
    override fun toString(): String = value

    /** Factories. */
    public companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")

        /** Version tag hashed into every fingerprint, so a future change to the canonical form can't collide. */
        private const val CANONICAL_FORM_VERSION = "charge-fingerprint/v1"

        /** Fingerprints [request]. Equal requests (ignoring display-only card data) give equal fingerprints. */
        public fun of(request: ChargeRequest): RequestFingerprint {
            val method = when (val pm = request.paymentMethod) {
                is TokenizedCard -> "card"
                is Wallet -> "wallet:${pm.provider.name}"
            }
            val fields = buildList {
                add(CANONICAL_FORM_VERSION)
                add(request.amount.amountMinor.toString())
                add(request.amount.currency.code)
                add(request.settlementCurrency.code)
                add(method)
                add(request.paymentMethod.token)
                add(request.customerId)
                add(request.initiator.name)
                // "-" for absent, "+text" for present, so null and "" fingerprint differently.
                add(request.description?.let { "+$it" } ?: "-")
                request.metadata.toSortedMap().forEach { (k, v) ->
                    add(k)
                    add(v)
                }
            }
            return RequestFingerprint(sha256Hex(canonical(fields)))
        }

        /** Length-prefixes every field so no two field lists share an encoding (`["ab","c"]` vs `["a","bc"]`). */
        private fun canonical(fields: List<String>): String =
            fields.joinToString(separator = "") { "${it.length}:$it;" }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
