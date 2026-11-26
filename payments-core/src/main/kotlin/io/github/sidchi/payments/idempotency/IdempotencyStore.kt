package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.IdempotencyKey
import java.time.Duration
import java.time.Instant

/**
 * Durable memory of which charges have been attempted, so a retried request never moves money twice.
 *
 * The protocol is a lease per key:
 *
 * 1. [tryAcquire] — atomically claim the key. Exactly one concurrent caller gets [AcquireResult.Acquired]; the others
 *    see [AcquireResult.InProgress], the stored result, or a fingerprint mismatch.
 * 2. Call the processor (forwarding the same idempotency key).
 * 3. [complete] with a final result so later retries replay it, or [release] when the processor provably did nothing
 *    (e.g. HTTP 503 before processing) so the next retry starts fresh.
 *
 * When the outcome is unknown (timeout) the holder does neither and lets the lease expire; the next caller then takes
 * over with [IdempotencyLease.isTakeover] set. Expiry doubles as a back-off before re-asking the processor.
 *
 * Implementations must make [tryAcquire], [complete] and [release] individually atomic and linearizable per key.
 * [IdempotentChargeExecutor] drives this protocol for you.
 */
public interface IdempotencyStore {
    /**
     * Claims [key] for a request with [fingerprint], with a lease that lasts [leaseDuration] from now.
     *
     * [leaseDuration] must comfortably exceed the processor call's timeout; otherwise a slow-but-alive holder can be
     * taken over while its request is still in flight.
     */
    public fun tryAcquire(key: IdempotencyKey, fingerprint: RequestFingerprint, leaseDuration: Duration): AcquireResult

    /**
     * Records [result] as the final answer for the lease's key and ends the lease.
     *
     * Succeeds even if the lease has expired, provided nobody else has taken the key over: the work did finish.
     *
     * @throws LeaseLostException if another caller now holds the key, or it was released or completed.
     */
    public fun complete(lease: IdempotencyLease, result: ChargeResult)

    /**
     * Forgets the key so the next request with it starts from scratch. Only call this when the processor certainly
     * did not create a charge.
     *
     * @return `false` if the lease was no longer current (nothing was changed).
     */
    public fun release(lease: IdempotencyLease): Boolean

    /** Current record for [key], or `null` if the key is unknown. For diagnostics and tests. */
    public fun find(key: IdempotencyKey): IdempotencyRecord?

    /**
     * Deletes completed records last updated before [cutoff] and returns how many were removed. In-progress records
     * are never purged. Retention should exceed the longest window in which clients retry (often 24 hours to 30 days).
     */
    public fun purgeCompletedBefore(cutoff: Instant): Int
}
