package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.FailureReason
import java.time.Duration
import java.time.Instant

/**
 * Runs charges through an [IdempotencyStore] so that retries, double-clicks and redelivered jobs charge at most once.
 *
 * What happens to each result decides whether a retry replays it or tries again:
 *
 * | Result | Store action | A retry with the same key… |
 * |---|---|---|
 * | `Approved`, `Declined` | [IdempotencyStore.complete] | replays the stored result |
 * | `Failed(INVALID_REQUEST)` | [IdempotencyStore.complete] | replays it; the same request will fail the same way |
 * | `Failed`, outcome known (503, 429) | [IdempotencyStore.release] | calls the processor again immediately |
 * | `Failed`, outcome unknown (timeout) | lease left to expire | waits for expiry, then takes over |
 * | exception thrown by `charge` | lease left to expire | waits for expiry, then takes over |
 *
 * A takeover means an earlier attempt may already have reached the processor, so `charge` **must** forward
 * [ChargeRequest.idempotencyKey] to the processor; the processor's own idempotency then turns the takeover into a
 * lookup rather than a second charge. Leaving the lease to expire, rather than releasing it, gives the processor time
 * to settle the first attempt before it is asked again.
 */
public class IdempotentChargeExecutor(
    private val store: IdempotencyStore,
    /** How long a caller may hold a key. Must comfortably exceed the processor call's timeout. */
    private val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Lease duration must be positive" }
    }

    /**
     * Executes [charge] for [request] unless the key already has a result or is being worked on.
     *
     * [charge] receives the lease so it can tell a first attempt from a takeover ([IdempotencyLease.isTakeover]),
     * e.g. to look the charge up at the processor before re-sending it.
     */
    public fun execute(
        request: ChargeRequest,
        charge: (ChargeRequest, IdempotencyLease) -> ChargeResult,
    ): IdempotentOutcome {
        val fingerprint = RequestFingerprint.of(request)
        return when (val acquired = store.tryAcquire(request.idempotencyKey, fingerprint, leaseDuration)) {
            is AcquireResult.Acquired -> runCharge(request, acquired.lease, charge)
            is AcquireResult.AlreadyCompleted -> IdempotentOutcome.Replayed(acquired.result)
            is AcquireResult.InProgress -> IdempotentOutcome.InProgress(acquired.leaseExpiresAt)
            is AcquireResult.FingerprintMismatch -> IdempotentOutcome.KeyReused(acquired.stored, fingerprint)
        }
    }

    private fun runCharge(
        request: ChargeRequest,
        lease: IdempotencyLease,
        charge: (ChargeRequest, IdempotencyLease) -> ChargeResult,
    ): IdempotentOutcome.Executed {
        // An exception leaves the lease in place on purpose: we can't know whether the processor saw the request.
        val result = charge(request, lease)
        require(result.idempotencyKey == request.idempotencyKey) {
            "charge returned a result for key '${result.idempotencyKey}', expected '${request.idempotencyKey}'"
        }
        val persistence = persist(lease, result)
        return IdempotentOutcome.Executed(result, lease.attempt, persistence)
    }

    private fun persist(lease: IdempotencyLease, result: ChargeResult): Persistence = when {
        result.isFinal() -> try {
            store.complete(lease, result)
            Persistence.STORED
        } catch (expected: LeaseLostException) {
            // Our lease expired and someone else took over. Their attempt reuses the key at the processor, so it
            // resolves to this same charge; we still return our result to our caller.
            Persistence.LEASE_LOST
        }
        result is ChargeResult.Failed && result.reason.outcomeKnown ->
            if (store.release(lease)) Persistence.RELEASED else Persistence.LEASE_LOST
        else -> Persistence.HELD_FOR_RECOVERY
    }

    private fun ChargeResult.isFinal(): Boolean = when (this) {
        is ChargeResult.Approved, is ChargeResult.Declined -> true
        is ChargeResult.Failed -> reason == FailureReason.INVALID_REQUEST
    }

    /** Defaults. */
    public companion object {
        /** Default lease: long enough for a slow processor call with its own retries, short enough to recover. */
        public val DEFAULT_LEASE_DURATION: Duration = Duration.ofSeconds(60)
    }
}

/** What [IdempotentChargeExecutor.execute] did. */
public sealed interface IdempotentOutcome {
    /** The charge ran in this call. */
    public data class Executed(
        /** Result from the processor. */
        public val result: ChargeResult,
        /** Which attempt on this key this was (2+ means an earlier holder's lease expired). */
        public val attempt: Int,
        /** What was recorded in the store. */
        public val persistence: Persistence,
    ) : IdempotentOutcome

    /** The key already had a final result; the processor was not called. */
    public data class Replayed(
        /** The stored result. */
        public val result: ChargeResult,
    ) : IdempotentOutcome

    /** Another caller is executing this key right now; retry after [retryAfter]. */
    public data class InProgress(
        /** When the other caller's lease expires. */
        public val retryAfter: Instant,
    ) : IdempotentOutcome

    /** The key was already used for a different request; nothing was charged. */
    public data class KeyReused(
        /** Fingerprint stored with the key. */
        public val stored: RequestFingerprint,
        /** Fingerprint of this request. */
        public val presented: RequestFingerprint,
    ) : IdempotentOutcome
}

/** What happened to the idempotency record after a charge ran. */
public enum class Persistence {
    /** The final result was stored; retries replay it. */
    STORED,

    /** The processor certainly did nothing; the key was forgotten so a retry starts fresh. */
    RELEASED,

    /** Outcome unknown; the lease was left to expire so the next caller takes over and reconciles. */
    HELD_FOR_RECOVERY,

    /** The lease had already passed to another caller; nothing was written. */
    LEASE_LOST,
}
