package io.github.sidchi.payments.gateway

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.IdempotencyKey

/**
 * Stable identifier of a payment processor connection, e.g. `"meridian-us"` or `"tessera-eu"`.
 *
 * Retry policies, circuit breakers and metrics are keyed by this id, so it names a *connection* (processor plus
 * merchant account or region), not just a vendor.
 */
@JvmInline
public value class ProcessorId(
    /** The id: lower-case letters, digits and dashes, 1–64 characters. */
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Processor id must match ${PATTERN.pattern}, was '$value'" }
    }

    /** Returns the raw id. */
    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("""[a-z0-9][a-z0-9-]{0,63}""")
    }
}

/**
 * One processor connection, seen through the library's normalized domain model.
 *
 * Implementations are adapters: they translate a [ChargeRequest] into the processor's wire format and map the
 * processor's response — approval, raw decline code, HTTP error, timeout — back into exactly one [ChargeResult].
 * Everything above this interface (retry engine, processor router, dunning) reasons only about normalized results.
 *
 * ### Contract
 *
 * - **Never throw for processor outcomes.** Declines are [ChargeResult.Declined]; transport and processor errors are
 *   [ChargeResult.Failed] with a [io.github.sidchi.payments.domain.FailureReason] that says whether the outcome is
 *   known. Exceptions are reserved for programming errors.
 * - **Forward the idempotency key.** [charge] must pass [ChargeRequest.idempotencyKey] to the processor so that
 *   re-sending a request whose first attempt timed out resolves to the original charge instead of a second one.
 * - **Reject what you can't do.** A request outside [capabilities] returns `Failed(INVALID_REQUEST)` without
 *   contacting the processor; routers should check [GatewayCapabilities.supports] first.
 * - **Be thread-safe.** One instance serves concurrent charges.
 */
public interface PaymentGateway {
    /** Which processor connection this is. */
    public val processorId: ProcessorId

    /** Currencies, card brands and wallets this connection accepts. */
    public val capabilities: GatewayCapabilities

    /** Attempts [request] once. Retrying, backoff and fallback are the caller's job. */
    public fun charge(request: ChargeRequest): ChargeResult

    /**
     * Asks the processor what happened to an earlier request with [idempotencyKey], without creating a charge.
     *
     * Returns the processor's recorded result, or `null` when it has no record of the key — meaning the earlier
     * attempt never reached it and re-sending is safe. Use it after a [ChargeResult.Failed] whose outcome is unknown,
     * or on an idempotency-lease takeover.
     */
    public fun lookup(idempotencyKey: IdempotencyKey): ChargeResult?
}
