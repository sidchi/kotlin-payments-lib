package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.gateway.GatewayCapabilities
import java.time.Duration

/**
 * Behavior of a [MockProcessor]: how fast it answers, how often it fails, and how it declines.
 *
 * Each call is resolved in this order, one random draw per step:
 *
 * 1. **Fault** ([faults]): the processor answers 503 or 429 before processing, or the connection drops.
 * 2. **Decision**: decline with probability [declineRate] (raw code drawn from [declineCodes]), otherwise approve.
 * 3. **Latency** ([latency]): if the sampled latency exceeds [timeout], the client gives up with `Failed(TIMEOUT)`.
 *
 * Timeouts and dropped connections are the interesting cases: the processor may have created the charge anyway. With
 * probability [processedWhenResponseLost] it did, and a later [MockProcessor.lookup] or same-key retry returns that
 * charge — which is exactly why retries must reuse the idempotency key.
 */
public data class MockProcessorConfig(
    /** What the connection accepts; requests outside it fail with `INVALID_REQUEST` without "reaching" the mock. */
    public val capabilities: GatewayCapabilities = GatewayCapabilities.all(),
    /** Time the processor takes to answer. */
    public val latency: LatencyModel = LatencyModel.Fixed(Duration.ofMillis(DEFAULT_LATENCY_MILLIS)),
    /** Client-side deadline; a slower answer becomes `Failed(TIMEOUT)` after waiting exactly this long. */
    public val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** Infrastructure failure rates. */
    public val faults: FaultRates = FaultRates.NONE,
    /** Share of processed charges that are declined, in `[0, 1]`. */
    public val declineRate: Double = 0.0,
    /**
     * Relative weights of raw decline codes, **in the processor's own dialect** (e.g. `"51"` for an ISO 8583
     * processor, `"insufficient_funds"` for a REST-style one). Empty means the dialect's
     * [ProcessorDialect.defaultDeclineMix].
     */
    public val declineCodes: Map<String, Int> = emptyMap(),
    /** Probability, in `[0, 1]`, that a timed-out or dropped request was nonetheless processed. */
    public val processedWhenResponseLost: Double = DEFAULT_PROCESSED_WHEN_LOST,
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "Timeout must be positive, was $timeout" }
        require(declineRate in 0.0..1.0) { "Decline rate must be in [0, 1], was $declineRate" }
        require(processedWhenResponseLost in 0.0..1.0) {
            "processedWhenResponseLost must be in [0, 1], was $processedWhenResponseLost"
        }
        require(declineCodes.values.all { it > 0 }) { "Decline code weights must be positive: $declineCodes" }
    }

    /** Defaults. */
    public companion object {
        /** Default latency of a healthy processor. */
        public const val DEFAULT_LATENCY_MILLIS: Long = 120

        /** Default client timeout. */
        public const val DEFAULT_TIMEOUT_SECONDS: Long = 10

        /** By default a lost response is a coin flip: the caller genuinely cannot know. */
        public const val DEFAULT_PROCESSED_WHEN_LOST: Double = 0.5
    }
}

/**
 * Probabilities of infrastructure failures per call, each in `[0, 1]` and summing to at most 1.
 *
 * [unavailable] and [rateLimited] are rejected before processing (outcome known, safe to retry at once);
 * [networkError] drops the connection after the request was sent (outcome unknown).
 */
public data class FaultRates(
    /** HTTP 503 before processing. */
    public val unavailable: Double = 0.0,
    /** HTTP 429 before processing. */
    public val rateLimited: Double = 0.0,
    /** Connection reset after the request was sent. */
    public val networkError: Double = 0.0,
) {
    init {
        listOf(unavailable, rateLimited, networkError).forEach {
            require(it in 0.0..1.0) { "Fault rates must be in [0, 1]: $this" }
        }
        require(total <= 1.0) { "Fault rates must sum to at most 1: $this" }
    }

    /** Combined probability of any fault. */
    public val total: Double get() = unavailable + rateLimited + networkError

    /** Constants. */
    public companion object {
        /** A healthy processor. */
        public val NONE: FaultRates = FaultRates()
    }
}
