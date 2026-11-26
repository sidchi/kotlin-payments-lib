package io.github.sidchi.payments.idempotency.postgres

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.idempotency.AcquireResult
import io.github.sidchi.payments.idempotency.ChargeResultCodec
import io.github.sidchi.payments.idempotency.IdempotencyLease
import io.github.sidchi.payments.idempotency.IdempotencyRecord
import io.github.sidchi.payments.idempotency.IdempotencyStore
import io.github.sidchi.payments.idempotency.LeaseLostException
import io.github.sidchi.payments.idempotency.RequestFingerprint
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

private const val STATE_IN_PROGRESS = "IN_PROGRESS"
private const val STATE_COMPLETED = "COMPLETED"

/**
 * [IdempotencyStore] backed by the `payment_idempotency_keys` table (see [PostgresIdempotencySchema]).
 *
 * Every operation is a single auto-committed statement, so the store works with any pool and never holds a database
 * transaction open across the processor call. Acquisition is one `INSERT ... ON CONFLICT DO UPDATE ... WHERE`:
 * Postgres serializes concurrent upserts on the same key through the row lock, and the `WHERE` re-checks the latest
 * row version, so exactly one caller wins a new or expired key no matter how many processes race.
 *
 * Time comes from [clock], not the database's `now()`, so lease arithmetic is consistent with the caller and testable.
 * Application hosts therefore need reasonably synchronized clocks (NTP); keep [IdempotencyLease.expiresAt] margins far
 * larger than any expected skew. Instants are truncated to microseconds, Postgres's `timestamptz` precision.
 *
 * Results are stored as a flat `jsonb` object written by [ChargeResultCodec], so operators can query them
 * (`WHERE result->>'type' = 'DECLINED'`).
 */
public class PostgresIdempotencyStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : IdempotencyStore {
    override fun tryAcquire(
        key: IdempotencyKey,
        fingerprint: RequestFingerprint,
        leaseDuration: Duration,
    ): AcquireResult {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Lease duration must be positive" }
        dataSource.connection.use { connection ->
            // A loser re-reads the row to say why it lost. If the row changed in between (released, or its lease
            // just expired) the read can't classify it and we race again; each loop makes progress for someone.
            repeat(MAX_ACQUIRE_ROUNDS) {
                val now = now()
                val token = UUID.randomUUID().toString()
                val expiresAt = now.plus(leaseDuration).truncatedTo(ChronoUnit.MICROS)
                val attempt = connection.upsertLease(key, fingerprint, token, expiresAt, now)
                if (attempt != null) {
                    return AcquireResult.Acquired(IdempotencyLease(key, fingerprint, token, expiresAt, attempt))
                }
                val refusal = connection.select(key)?.acquireResultFor(fingerprint, now)
                if (refusal != null) return refusal
            }
        }
        error("Could not acquire or classify idempotency key '$key' after $MAX_ACQUIRE_ROUNDS rounds")
    }

    override fun complete(lease: IdempotencyLease, result: ChargeResult) {
        require(result.idempotencyKey == lease.key) {
            "Result is for key '${result.idempotencyKey}', lease is for '${lease.key}'"
        }
        val encoded = ChargeResultCodec.encode(result).toSortedMap()
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(COMPLETE_SQL).use { st ->
                st.bind(
                    connection.createArrayOf("text", encoded.keys.toTypedArray()),
                    connection.createArrayOf("text", encoded.values.toTypedArray()),
                    now().toTimestamptz(),
                    lease.key.value,
                    lease.token,
                )
                st.executeUpdate()
            }
        }
        if (updated == 0) throw LeaseLostException(lease)
    }

    override fun release(lease: IdempotencyLease): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(RELEASE_SQL).use { st ->
            st.bind(lease.key.value, lease.token)
            st.executeUpdate() > 0
        }
    }

    override fun find(key: IdempotencyKey): IdempotencyRecord? = dataSource.connection.use { it.select(key) }

    override fun purgeCompletedBefore(cutoff: Instant): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(PURGE_SQL).use { st ->
            st.bind(cutoff.toTimestamptz())
            st.executeUpdate()
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    /** Returns the new attempt number if this caller now holds the lease, or `null` if someone else does. */
    private fun Connection.upsertLease(
        key: IdempotencyKey,
        fingerprint: RequestFingerprint,
        token: String,
        expiresAt: Instant,
        now: Instant,
    ): Int? = prepareStatement(ACQUIRE_SQL).use { st ->
        val at = now.toTimestamptz()
        // Parameters: key, fingerprint, lease token, lease expiry, created_at, updated_at.
        st.bind(key.value, fingerprint.value, token, expiresAt.toTimestamptz(), at, at)
        st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
    }

    private fun Connection.select(key: IdempotencyKey): IdempotencyRecord? = prepareStatement(SELECT_SQL).use { st ->
        st.bind(key.value)
        st.executeQuery().use { rs -> if (rs.next()) rs.toRecord(key) else null }
    }

    private companion object {
        const val MAX_ACQUIRE_ROUNDS = 5

        val ACQUIRE_SQL = """
            INSERT INTO payment_idempotency_keys AS k
                (idempotency_key, fingerprint, state, lease_token, lease_expires_at, attempts, created_at, updated_at)
            VALUES (?, ?, '$STATE_IN_PROGRESS', ?, ?, 1, ?, ?)
            ON CONFLICT (idempotency_key) DO UPDATE
                SET lease_token = EXCLUDED.lease_token,
                    lease_expires_at = EXCLUDED.lease_expires_at,
                    attempts = k.attempts + 1,
                    updated_at = EXCLUDED.updated_at
                WHERE k.state = '$STATE_IN_PROGRESS'
                  AND k.fingerprint = EXCLUDED.fingerprint
                  AND k.lease_expires_at <= EXCLUDED.updated_at
            RETURNING attempts
        """.trimIndent()

        val COMPLETE_SQL = """
            UPDATE payment_idempotency_keys
               SET state = '$STATE_COMPLETED',
                   result = jsonb_object(?::text[], ?::text[]),
                   lease_token = NULL,
                   lease_expires_at = NULL,
                   updated_at = ?
             WHERE idempotency_key = ? AND lease_token = ? AND state = '$STATE_IN_PROGRESS'
        """.trimIndent()

        val RELEASE_SQL = """
            DELETE FROM payment_idempotency_keys
             WHERE idempotency_key = ? AND lease_token = ? AND state = '$STATE_IN_PROGRESS'
        """.trimIndent()

        val SELECT_SQL = """
            SELECT fingerprint, state, attempts, lease_expires_at, updated_at,
                   (SELECT array_agg(e.key ORDER BY e.key) FROM jsonb_each_text(result) e) AS result_keys,
                   (SELECT array_agg(e.value ORDER BY e.key) FROM jsonb_each_text(result) e) AS result_values
              FROM payment_idempotency_keys
             WHERE idempotency_key = ?
        """.trimIndent()

        val PURGE_SQL = """
            DELETE FROM payment_idempotency_keys
             WHERE state = '$STATE_COMPLETED' AND updated_at < ?
        """.trimIndent()
    }
}

private fun ResultSet.toRecord(key: IdempotencyKey): IdempotencyRecord {
    val fingerprint = RequestFingerprint(getString("fingerprint"))
    val attempts = getInt("attempts")
    return when (val state = getString("state")) {
        STATE_IN_PROGRESS ->
            IdempotencyRecord.InProgress(key, fingerprint, attempts, getInstant("lease_expires_at"))
        STATE_COMPLETED -> {
            val keys = getStringArray("result_keys")
            val values = getStringArray("result_values")
            check(keys.size == values.size) { "Corrupt result for key '$key'" }
            val result = ChargeResultCodec.decode(keys.zip(values).toMap())
            IdempotencyRecord.Completed(key, fingerprint, attempts, result, getInstant("updated_at"))
        }
        else -> error("Unknown idempotency state '$state' for key '$key'")
    }
}

private fun ResultSet.getInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.getStringArray(column: String): List<String> =
    (getArray(column).array as Array<*>).map { it.toString() }

/** Binds [params] to the statement's placeholders in order; pgjdbc maps each type (text, text[], timestamptz). */
private fun PreparedStatement.bind(vararg params: Any) {
    params.forEachIndexed { i, value -> setObject(i + 1, value) }
}

private fun Instant.toTimestamptz(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
