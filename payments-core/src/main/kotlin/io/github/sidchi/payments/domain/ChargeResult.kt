package io.github.sidchi.payments.domain

/**
 * Outcome of a single charge attempt. Exactly one of three things happened:
 *
 * - [Approved] — the issuer authorized the charge.
 * - [Declined] — the issuer (or processor risk engine) answered "no", with a classified [Decline].
 * - [Failed] — no issuer answer was obtained. Depending on [FailureReason.outcomeKnown] the charge may or may not
 *   have gone through, which is exactly the case idempotency keys exist for.
 */
public sealed interface ChargeResult {
    /** Key of the request this result answers. */
    public val idempotencyKey: IdempotencyKey

    /** The charge was authorized. */
    public data class Approved(
        override val idempotencyKey: IdempotencyKey,
        /** Processor's identifier for the charge, used for captures, refunds and disputes. */
        public val chargeId: String,
        /** Amount authorized. */
        public val amount: Money,
        /** Issuer authorization code, when the processor returns one. */
        public val authorizationCode: String? = null,
    ) : ChargeResult

    /** The issuer or processor refused the charge. */
    public data class Declined(
        override val idempotencyKey: IdempotencyKey,
        /** Normalized decline with the processor's raw code. */
        public val decline: Decline,
        /** Processor's identifier for the declined attempt, if any. */
        public val chargeId: String? = null,
    ) : ChargeResult

    /** The attempt failed before an issuer decision was received. */
    public data class Failed(
        override val idempotencyKey: IdempotencyKey,
        /** What went wrong. */
        public val reason: FailureReason,
        /** Diagnostic detail; never shown to customers. */
        public val message: String? = null,
    ) : ChargeResult
}

/**
 * Why a charge attempt produced no issuer decision.
 *
 * [outcomeKnown] is the property that matters operationally: when it is `false` (timeouts, dropped connections) the
 * charge may have succeeded at the processor, so it must be retried with the **same** idempotency key or reconciled —
 * never re-sent under a new key.
 */
public enum class FailureReason(
    /** True when the processor definitely did not create a charge. */
    public val outcomeKnown: Boolean,
) {
    /** No response within the deadline; the charge may or may not exist. */
    TIMEOUT(outcomeKnown = false),

    /** Connection dropped mid-request; the charge may or may not exist. */
    NETWORK_ERROR(outcomeKnown = false),

    /** Processor rejected the request as unavailable (e.g. HTTP 503) before processing it. */
    PROCESSOR_UNAVAILABLE(outcomeKnown = true),

    /** Processor throttled the request (e.g. HTTP 429) before processing it. */
    RATE_LIMITED(outcomeKnown = true),

    /** The request was malformed or failed validation; retrying unchanged will not help. */
    INVALID_REQUEST(outcomeKnown = true),
    ;

    /** True when sending the same request again could plausibly succeed. */
    public val isTransient: Boolean get() = this != INVALID_REQUEST
}
