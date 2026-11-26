package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.IdempotencyKey
import java.time.Instant

/**
 * Exclusive, time-limited right to execute the charge for [key].
 *
 * Only the holder of a live lease may call the processor. If the holder crashes, the lease expires at [expiresAt] and
 * the next caller takes over with [attempt] incremented. Because the crashed attempt may already have reached the
 * processor, a takeover ([isTakeover]) must send the **same** idempotency key to the processor so it deduplicates.
 */
public data class IdempotencyLease(
    /** Key this lease covers. */
    public val key: IdempotencyKey,
    /** Fingerprint of the request that acquired the key. */
    public val fingerprint: RequestFingerprint,
    /** Random token identifying this holder; completing or releasing requires it. */
    public val token: String,
    /** When other callers may take the key over. */
    public val expiresAt: Instant,
    /** 1 for the first attempt on this key, 2+ after taking over an expired lease. */
    public val attempt: Int,
) {
    init {
        require(token.isNotBlank()) { "Lease token must not be blank" }
        require(attempt >= 1) { "Attempt must be at least 1, was $attempt" }
    }

    /** True when an earlier holder's lease expired; that attempt may or may not have reached the processor. */
    public val isTakeover: Boolean get() = attempt > 1
}

/** What a store knows about one idempotency key. */
public sealed interface IdempotencyRecord {
    /** The key. */
    public val key: IdempotencyKey

    /** Fingerprint of the request first seen with this key. */
    public val fingerprint: RequestFingerprint

    /** Number of leases granted on this key so far. */
    public val attempts: Int

    /**
     * What a caller presenting [presented] at [now] gets when this record already exists, or `null` when the caller
     * may take the key over (same fingerprint, in progress, lease expired). Store implementations share this so they
     * agree exactly on precedence: fingerprint mismatch, then completed, then live lease.
     */
    public fun acquireResultFor(presented: RequestFingerprint, now: Instant): AcquireResult? = when {
        fingerprint != presented -> AcquireResult.FingerprintMismatch(fingerprint, presented)
        this is Completed -> AcquireResult.AlreadyCompleted(result)
        this is InProgress && leaseExpiresAt.isAfter(now) -> AcquireResult.InProgress(leaseExpiresAt)
        else -> null
    }

    /** A worker holds (or held, if [leaseExpiresAt] has passed) a lease and has not recorded a result. */
    public data class InProgress(
        override val key: IdempotencyKey,
        override val fingerprint: RequestFingerprint,
        override val attempts: Int,
        /** When the current lease expires. */
        public val leaseExpiresAt: Instant,
    ) : IdempotencyRecord

    /** The charge reached a final result; retries replay [result]. */
    public data class Completed(
        override val key: IdempotencyKey,
        override val fingerprint: RequestFingerprint,
        override val attempts: Int,
        /** The stored result. */
        public val result: ChargeResult,
        /** When [result] was recorded. */
        public val completedAt: Instant,
    ) : IdempotencyRecord
}

/** Outcome of [IdempotencyStore.tryAcquire]. */
public sealed interface AcquireResult {
    /** The caller now holds [lease] and should execute the charge. */
    public data class Acquired(
        /** The lease to pass to [IdempotencyStore.complete] or [IdempotencyStore.release]. */
        public val lease: IdempotencyLease,
    ) : AcquireResult

    /** The key already has a final result; return it instead of charging again. */
    public data class AlreadyCompleted(
        /** The stored result. */
        public val result: ChargeResult,
    ) : AcquireResult

    /** Another caller holds a live lease. Retry after [leaseExpiresAt] (an HTTP API would answer 409). */
    public data class InProgress(
        /** When the current lease expires. */
        public val leaseExpiresAt: Instant,
    ) : AcquireResult

    /**
     * The key was first used with a different request. This is a client bug; an HTTP API would answer 422.
     */
    public data class FingerprintMismatch(
        /** Fingerprint stored with the key. */
        public val stored: RequestFingerprint,
        /** Fingerprint of the rejected request. */
        public val presented: RequestFingerprint,
    ) : AcquireResult
}

/**
 * Thrown by [IdempotencyStore.complete] when the caller's lease is no longer current: it expired and another caller
 * took the key over, or the key was released or completed under a different lease.
 */
public class LeaseLostException(
    /** The lease that is no longer current. */
    public val lease: IdempotencyLease,
) : IllegalStateException("Lease ${lease.attempt} on idempotency key '${lease.key}' is no longer current")
