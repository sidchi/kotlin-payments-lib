package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.PaymentMethod

/**
 * Magic payment-method tokens that force a specific outcome from any [MockProcessor], regardless of its configured
 * rates — the mock equivalent of a processor's documented test cards. Use them for deterministic tests of retry and
 * fallback logic: "the first processor times out, the second approves".
 *
 * Tokens are plain strings, so they work in both [io.github.sidchi.payments.domain.TokenizedCard] and
 * [io.github.sidchi.payments.domain.Wallet].
 */
public object MockTokens {
    private const val PREFIX = "tok_mock_"
    private const val DECLINE_PREFIX = "${PREFIX}decline_"

    /** Always approves. */
    public const val APPROVE: String = "${PREFIX}approve"

    /** Exceeds the timeout; the processor does **not** create the charge. */
    public const val TIMEOUT: String = "${PREFIX}timeout"

    /** Exceeds the timeout, but the processor **does** create the charge: the case idempotency keys exist for. */
    public const val TIMEOUT_AFTER_PROCESSING: String = "${PREFIX}timeout_processed"

    /** Connection dropped; the processor does not create the charge. */
    public const val NETWORK_ERROR: String = "${PREFIX}network_error"

    /** HTTP 503 before processing. */
    public const val UNAVAILABLE: String = "${PREFIX}unavailable"

    /** HTTP 429 before processing. */
    public const val RATE_LIMITED: String = "${PREFIX}rate_limited"

    /** Declines with [rawCode], given in the processor's own dialect (e.g. `"51"` or `"insufficient_funds"`). */
    public fun decline(rawCode: String): String {
        require(rawCode.isNotBlank()) { "Decline code must not be blank" }
        return DECLINE_PREFIX + rawCode
    }

    /** The scenario [paymentMethod]'s token forces, or `null` for an ordinary token. */
    internal fun scenarioFor(paymentMethod: PaymentMethod): Scenario? {
        val token = paymentMethod.token
        return when {
            !token.startsWith(PREFIX) -> null
            token.startsWith(DECLINE_PREFIX) -> Scenario.Decline(token.removePrefix(DECLINE_PREFIX))
            else -> fixedScenarios[token]
        }
    }

    private val fixedScenarios: Map<String, Scenario> = mapOf(
        APPROVE to Scenario.Approve,
        TIMEOUT to Scenario.Timeout(processed = false),
        TIMEOUT_AFTER_PROCESSING to Scenario.Timeout(processed = true),
        NETWORK_ERROR to Scenario.NetworkError,
        UNAVAILABLE to Scenario.Unavailable,
        RATE_LIMITED to Scenario.RateLimited,
    )

    /** A forced outcome. */
    internal sealed interface Scenario {
        data object Approve : Scenario

        data class Decline(val rawCode: String) : Scenario

        data class Timeout(val processed: Boolean) : Scenario

        data object NetworkError : Scenario

        data object Unavailable : Scenario

        data object RateLimited : Scenario
    }
}
