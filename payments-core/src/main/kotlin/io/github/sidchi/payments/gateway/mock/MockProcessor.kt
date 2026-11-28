package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.fx.CurrencyConverter
import io.github.sidchi.payments.fx.ExchangeRateUnavailableException
import io.github.sidchi.payments.fx.StaleExchangeRateException
import io.github.sidchi.payments.gateway.GatewayCapabilities
import io.github.sidchi.payments.gateway.PaymentGateway
import io.github.sidchi.payments.gateway.ProcessorId
import io.github.sidchi.payments.idempotency.RequestFingerprint
import java.math.RoundingMode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * An in-process fake payment processor with configurable latency, failure rates and decline codes.
 *
 * It behaves like a real processor in the ways that matter to retry and fallback logic:
 *
 * - **Processor-side idempotency.** Results are recorded per [IdempotencyKey]. Re-sending the same request returns the
 *   recorded result instead of charging again; re-using a key with different parameters is `INVALID_REQUEST`.
 * - **Lost responses.** A timeout or dropped connection may happen *after* the charge was created
 *   ([MockProcessorConfig.processedWhenResponseLost]). [lookup] then finds it, and a same-key retry replays it.
 * - **Its own dialect.** Declines carry the processor's raw codes, normalized by [dialect].
 * - **Reproducibility.** All randomness comes from one [seed]; a single-threaded run with the same seed, config and
 *   requests produces the same results.
 *
 * Infrastructure faults are drawn on every call, including retries of a recorded key, just as a real retry can hit
 * a 503. Thread-safe.
 *
 * @param converter converts presentment to settlement currency for requests that [ChargeRequest.requiresFx]; without
 *   one, such requests fail with `INVALID_REQUEST`.
 * @param sleeper waits out simulated latency; tests pass [Sleeper.NONE] or a recorder.
 */
public class MockProcessor(
    override val processorId: ProcessorId,
    /** Raw-code dialect and identifier formats. */
    public val dialect: ProcessorDialect,
    /** Latency, failure and decline behavior. */
    public val config: MockProcessorConfig = MockProcessorConfig(),
    private val converter: CurrencyConverter? = null,
    private val sleeper: Sleeper = Sleeper.REAL,
    /** Seed for every random draw this processor makes. */
    public val seed: Long = DEFAULT_SEED,
) : PaymentGateway {
    override val capabilities: GatewayCapabilities get() = config.capabilities

    private val random = Random(seed)
    private val randomLock = Any()
    private val ledger = ConcurrentHashMap<IdempotencyKey, LedgerEntry>()
    private val chargeSequence = AtomicLong()
    private val callCounter = AtomicLong()
    private val planner = Planner(config, WeightedChoice(config.declineCodes.ifEmpty { dialect.defaultDeclineMix }))

    /** Number of [charge] calls received, including rejected ones. */
    public val calls: Long get() = callCounter.get()

    /** Number of distinct idempotency keys the processor holds a result for (approvals and declines). */
    public val recordedCharges: Int get() = ledger.size

    override fun charge(request: ChargeRequest): ChargeResult {
        callCounter.incrementAndGet()
        val rejection = config.capabilities.unsupportedReason(request)
            ?: "settlement in ${request.settlementCurrency} requires FX, which is not configured"
                .takeIf { request.requiresFx && converter == null }
        return if (rejection != null) {
            ChargeResult.Failed(request.idempotencyKey, FailureReason.INVALID_REQUEST, "$processorId: $rejection")
        } else {
            execute(request, plan(request))
        }
    }

    override fun lookup(idempotencyKey: IdempotencyKey): ChargeResult? = ledger[idempotencyKey]?.result

    private fun plan(request: ChargeRequest): Plan = draw { planner.plan(request, it) }

    private fun execute(request: ChargeRequest, plan: Plan): ChargeResult {
        sleeper.sleep(plan.wait)
        val key = request.idempotencyKey
        return when (plan) {
            is Plan.Rejected -> ChargeResult.Failed(key, plan.reason, "$processorId: simulated ${plan.reason}")
            is Plan.Answered -> record(request, plan.decision)
            is Plan.Lost -> {
                plan.processed?.let { record(request, it) }
                ChargeResult.Failed(key, plan.reason, "$processorId: simulated ${plan.reason} after ${plan.wait}")
            }
        }
    }

    /** Returns the result already recorded for the key, or creates, records and returns a new one. */
    private fun record(request: ChargeRequest, decision: Decision): ChargeResult {
        val fingerprint = RequestFingerprint.of(request)
        val existing = ledger[request.idempotencyKey]
        return if (existing != null) {
            existing.replayFor(request, fingerprint, processorId)
        } else {
            when (val created = create(request, decision)) {
                // Not processed: the processor could not convert, so nothing is recorded.
                is ChargeResult.Failed -> created
                else -> ledger.putIfAbsent(request.idempotencyKey, LedgerEntry(fingerprint, created))
                    ?.replayFor(request, fingerprint, processorId)
                    ?: created
            }
        }
    }

    private fun create(request: ChargeRequest, decision: Decision): ChargeResult {
        val chargeId = dialect.chargeIdPrefix + chargeSequence.incrementAndGet().toString().padStart(ID_DIGITS, '0')
        return when (decision) {
            is Decision.Decline ->
                ChargeResult.Declined(request.idempotencyKey, dialect.toDecline(decision.rawCode), chargeId)
            Decision.Approve -> approve(request, chargeId)
        }
    }

    private fun approve(request: ChargeRequest, chargeId: String): ChargeResult = try {
        val conversion = converter
            ?.takeIf { request.requiresFx }
            ?.convert(request.amount, request.settlementCurrency, RoundingMode.HALF_EVEN)
        ChargeResult.Approved(
            idempotencyKey = request.idempotencyKey,
            chargeId = chargeId,
            amount = request.amount,
            authorizationCode = draw { dialect.authorizationCode(it) },
            settlementConversion = conversion,
        )
    } catch (e: StaleExchangeRateException) {
        fxFailure(request, e)
    } catch (e: ExchangeRateUnavailableException) {
        fxFailure(request, e)
    }

    private fun fxFailure(request: ChargeRequest, cause: RuntimeException): ChargeResult.Failed =
        ChargeResult.Failed(
            request.idempotencyKey,
            FailureReason.PROCESSOR_UNAVAILABLE,
            "$processorId: ${cause.message}",
        )

    private inline fun <T> draw(block: (Random) -> T): T = synchronized(randomLock) { block(random) }

    /** Defaults. */
    public companion object {
        /** Seed used when none is given, so unseeded mocks are still reproducible. */
        public const val DEFAULT_SEED: Long = 42L

        private const val ID_DIGITS = 10
    }
}

/** Decides how a call plays out. Not thread-safe: the processor calls it under its random lock. */
private class Planner(private val config: MockProcessorConfig, private val declineMix: WeightedChoice) {
    fun plan(request: ChargeRequest, random: Random): Plan =
        when (val scenario = MockTokens.scenarioFor(request.paymentMethod)) {
            null -> randomPlan(random)
            else -> scriptedPlan(scenario, random)
        }

    private fun randomPlan(random: Random): Plan {
        val latency = config.latency.sample(random)
        val faults = config.faults
        val roll = random.nextDouble()
        return when {
            roll < faults.unavailable -> Plan.Rejected(FailureReason.PROCESSOR_UNAVAILABLE, latency)
            roll < faults.unavailable + faults.rateLimited -> Plan.Rejected(FailureReason.RATE_LIMITED, latency)
            roll < faults.total -> lost(FailureReason.NETWORK_ERROR, latency, randomDecision(random), random)
            latency > config.timeout -> lost(FailureReason.TIMEOUT, config.timeout, randomDecision(random), random)
            else -> Plan.Answered(randomDecision(random), latency)
        }
    }

    private fun scriptedPlan(scenario: MockTokens.Scenario, random: Random): Plan {
        // Scripted outcomes ignore configured faults, but still take (at most timeout-bounded) latency.
        val latency = minOf(config.latency.sample(random), config.timeout)
        return when (scenario) {
            MockTokens.Scenario.Approve -> Plan.Answered(Decision.Approve, latency)
            is MockTokens.Scenario.Decline -> Plan.Answered(Decision.Decline(scenario.rawCode), latency)
            is MockTokens.Scenario.Timeout ->
                Plan.Lost(FailureReason.TIMEOUT, config.timeout, Decision.Approve.takeIf { scenario.processed })
            MockTokens.Scenario.NetworkError -> Plan.Lost(FailureReason.NETWORK_ERROR, latency, processed = null)
            MockTokens.Scenario.Unavailable -> Plan.Rejected(FailureReason.PROCESSOR_UNAVAILABLE, latency)
            MockTokens.Scenario.RateLimited -> Plan.Rejected(FailureReason.RATE_LIMITED, latency)
        }
    }

    private fun lost(reason: FailureReason, wait: Duration, decision: Decision, random: Random): Plan.Lost =
        Plan.Lost(reason, wait, decision.takeIf { random.nextDouble() < config.processedWhenResponseLost })

    private fun randomDecision(random: Random): Decision =
        if (random.nextDouble() < config.declineRate) Decision.Decline(declineMix.pick(random)) else Decision.Approve
}

/** What the processor decided, before we know whether the client hears about it. */
private sealed interface Decision {
    data object Approve : Decision

    data class Decline(val rawCode: String) : Decision
}

/** How one call will play out. [wait] is how long the client waits before it sees the outcome. */
private sealed interface Plan {
    val wait: Duration

    /** Refused before processing (503, 429). */
    data class Rejected(val reason: FailureReason, override val wait: Duration) : Plan

    /** Processed and answered in time. */
    data class Answered(val decision: Decision, override val wait: Duration) : Plan

    /** The client never got an answer; [processed] is what the processor did anyway, or `null` if nothing. */
    data class Lost(val reason: FailureReason, override val wait: Duration, val processed: Decision?) : Plan
}

private class LedgerEntry(val fingerprint: RequestFingerprint, val result: ChargeResult) {
    fun replayFor(request: ChargeRequest, presented: RequestFingerprint, processorId: ProcessorId): ChargeResult =
        if (presented == fingerprint) {
            result
        } else {
            ChargeResult.Failed(
                request.idempotencyKey,
                FailureReason.INVALID_REQUEST,
                "$processorId: idempotency key '${request.idempotencyKey}' was reused with different parameters",
            )
        }
}

/** Picks keys with probability proportional to their weights. */
private class WeightedChoice(weights: Map<String, Int>) {
    private val keys = weights.keys.toList()
    private val cumulative = weights.values.runningReduce(Int::plus)

    init {
        require(keys.isNotEmpty()) { "At least one decline code is required" }
    }

    fun pick(random: Random): String {
        val roll = random.nextInt(cumulative.last())
        return keys[cumulative.indexOfFirst { roll < it }]
    }
}
