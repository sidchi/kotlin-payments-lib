package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.IdempotencyKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, process-local [IdempotencyStore] for tests, simulations and single-instance deployments.
 *
 * Every operation runs inside [ConcurrentHashMap.compute], which locks the key's bin, so acquire, complete and release
 * are atomic per key without a global lock. State is lost on restart; use a shared database store (for example the
 * Postgres one) when more than one process can charge the same key.
 */
public class InMemoryIdempotencyStore(
    private val clock: Clock = Clock.systemUTC(),
) : IdempotencyStore {
    private val records = ConcurrentHashMap<IdempotencyKey, Entry>()

    override fun tryAcquire(
        key: IdempotencyKey,
        fingerprint: RequestFingerprint,
        leaseDuration: Duration,
    ): AcquireResult {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Lease duration must be positive" }
        var outcome: AcquireResult? = null
        records.compute(key) { _, existing ->
            val now = clock.instant()
            val refusal = existing?.record?.acquireResultFor(fingerprint, now)
            if (refusal != null) {
                outcome = refusal
                existing
            } else {
                val lease = IdempotencyLease(
                    key = key,
                    fingerprint = fingerprint,
                    token = UUID.randomUUID().toString(),
                    expiresAt = now.plus(leaseDuration),
                    attempt = (existing?.record?.attempts ?: 0) + 1,
                )
                outcome = AcquireResult.Acquired(lease)
                Entry(
                    record = IdempotencyRecord.InProgress(key, fingerprint, lease.attempt, lease.expiresAt),
                    leaseToken = lease.token,
                    updatedAt = now,
                )
            }
        }
        return checkNotNull(outcome)
    }

    override fun complete(lease: IdempotencyLease, result: ChargeResult) {
        require(result.idempotencyKey == lease.key) {
            "Result is for key '${result.idempotencyKey}', lease is for '${lease.key}'"
        }
        var completed = false
        records.computeIfPresent(lease.key) { _, entry ->
            if (entry.holds(lease)) {
                completed = true
                val now = clock.instant()
                val record = IdempotencyRecord.Completed(
                    key = lease.key,
                    fingerprint = lease.fingerprint,
                    attempts = entry.record.attempts,
                    result = result,
                    completedAt = now,
                )
                Entry(record, leaseToken = null, updatedAt = now)
            } else {
                entry
            }
        }
        if (!completed) throw LeaseLostException(lease)
    }

    override fun release(lease: IdempotencyLease): Boolean {
        var released = false
        records.computeIfPresent(lease.key) { _, entry ->
            if (entry.holds(lease)) {
                released = true
                null
            } else {
                entry
            }
        }
        return released
    }

    override fun find(key: IdempotencyKey): IdempotencyRecord? = records[key]?.record

    override fun purgeCompletedBefore(cutoff: Instant): Int {
        var purged = 0
        // Re-check inside computeIfPresent so a record completed concurrently is judged on its latest state.
        records.keys.forEach { key ->
            records.computeIfPresent(key) { _, entry ->
                if (entry.record is IdempotencyRecord.Completed && entry.updatedAt.isBefore(cutoff)) {
                    purged++
                    null
                } else {
                    entry
                }
            }
        }
        return purged
    }

    private class Entry(val record: IdempotencyRecord, val leaseToken: String?, val updatedAt: Instant) {
        fun holds(lease: IdempotencyLease): Boolean =
            record is IdempotencyRecord.InProgress && leaseToken == lease.token
    }
}
