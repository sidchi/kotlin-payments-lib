package io.github.sidchi.payments.domain

/**
 * Maps common ISO 8583 field-39 response codes (plus a few widely documented Visa codes) to [DeclineReason].
 *
 * Processors expose these codes in their APIs, but meanings vary slightly by network and issuer, so this table is a
 * reasonable default rather than an authority; gateway adapters can override it. Anything unmapped becomes
 * [DeclineReason.Unknown], which fails closed.
 */
public object Iso8583DeclineCodes {
    /** Response codes that mean "approved" and therefore can't be mapped to a decline. */
    public val approvalCodes: Set<String> = setOf("00", "08", "10", "11", "85")

    private val reasons: Map<String, DeclineReason> = mapOf(
        "04" to DeclineReason.PickUpCard,
        "05" to DeclineReason.DoNotHonor,
        "07" to DeclineReason.PickUpCard,
        "14" to DeclineReason.InvalidCardNumber,
        "19" to DeclineReason.ProcessingError,
        "33" to DeclineReason.ExpiredCard,
        "41" to DeclineReason.LostCard,
        "43" to DeclineReason.StolenCard,
        "46" to DeclineReason.AccountClosed,
        "51" to DeclineReason.InsufficientFunds,
        "54" to DeclineReason.ExpiredCard,
        "57" to DeclineReason.TransactionNotPermitted,
        "58" to DeclineReason.TransactionNotPermitted,
        "59" to DeclineReason.SuspectedFraud,
        "61" to DeclineReason.ExceedsLimit,
        "62" to DeclineReason.TransactionNotPermitted,
        "65" to DeclineReason.ExceedsLimit,
        "91" to DeclineReason.IssuerUnavailable,
        "96" to DeclineReason.ProcessingError,
        "1A" to DeclineReason.AuthenticationRequired,
        "R0" to DeclineReason.RevokedAuthorization,
        "R1" to DeclineReason.RevokedAuthorization,
        "R3" to DeclineReason.RevokedAuthorization,
    )

    /** Every code this table maps explicitly. */
    public val knownCodes: Set<String> get() = reasons.keys

    /**
     * The normalized reason for [code] (case-insensitive, surrounding whitespace ignored).
     *
     * @throws IllegalArgumentException if [code] is an approval code — an approval is not a decline.
     */
    public fun reasonFor(code: String): DeclineReason {
        val normalized = code.trim().uppercase()
        require(normalized !in approvalCodes) { "Response code '$code' is an approval, not a decline" }
        return reasons[normalized] ?: DeclineReason.Unknown
    }

    /** A [Decline] for [code], keeping the raw code and [message] for audit. */
    public fun toDecline(code: String, message: String? = null): Decline =
        Decline(reason = reasonFor(code), rawCode = code.trim(), message = message)
}
