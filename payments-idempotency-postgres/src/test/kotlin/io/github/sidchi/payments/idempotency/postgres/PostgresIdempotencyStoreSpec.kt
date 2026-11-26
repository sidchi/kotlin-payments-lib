package io.github.sidchi.payments.idempotency.postgres

import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.idempotency.AcquireResult
import io.github.sidchi.payments.idempotency.RequestFingerprint
import io.github.sidchi.payments.idempotency.testing.MutableClock
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.github.sidchi.payments.idempotency.testing.idempotencyStoreContract
import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.postgresql.ds.PGSimpleDataSource
import java.time.Duration
import javax.sql.DataSource
import kotlin.reflect.KClass

/**
 * Runs the shared store contract against a real Postgres.
 *
 * Point `PAYMENTS_TEST_POSTGRES_URL` at a disposable database, e.g.
 * `jdbc:postgresql://localhost:5432/payments_test?user=postgres&password=postgres`. Without it the spec is skipped
 * locally; on CI (`CI=true`) a missing URL fails the build instead, so the suite can't silently go green.
 */
@EnabledIf(PostgresConfigured::class)
class PostgresIdempotencyStoreSpec : FunSpec({
    val dataSource: DataSource = PGSimpleDataSource().apply { setURL(PostgresConfigured.url()) }
    PostgresIdempotencySchema.create(dataSource)
    PostgresIdempotencySchema.create(dataSource) // the DDL is idempotent

    fun truncate() = dataSource.connection.use { c ->
        c.createStatement().use { it.execute("TRUNCATE payment_idempotency_keys") }
    }

    include(
        idempotencyStoreContract { clock ->
            truncate()
            PostgresIdempotencyStore(dataSource, clock)
        },
    )

    test("results are queryable as flat jsonb") {
        truncate()
        val store = PostgresIdempotencyStore(dataSource, MutableClock())
        val key = IdempotencyKey("jsonb-check")
        val fingerprint = RequestFingerprint.of(SampleCharges.request(key.value))
        val lease = store.tryAcquire(key, fingerprint, Duration.ofSeconds(30))
            .shouldBeInstanceOf<AcquireResult.Acquired>().lease
        store.complete(lease, SampleCharges.declined(key))

        val reason = dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT result->>'type', result->>'decline.reason' FROM payment_idempotency_keys " +
                    "WHERE idempotency_key = ?",
            ).use { st ->
                st.setString(1, key.value)
                st.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) to rs.getString(2)
                }
            }
        }
        reason shouldBe ("DECLINED" to "insufficient_funds")
    }

    test("the table rejects rows in an impossible state") {
        truncate()
        val inserted = runCatching {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO payment_idempotency_keys (idempotency_key, fingerprint, state, attempts, " +
                            "created_at, updated_at) VALUES ('k', 'f', 'IN_PROGRESS', 1, now(), now())",
                    )
                }
            }
        }
        inserted.isFailure shouldBe true
    }
})

/** Enables the Postgres spec when a database URL is configured, and fails loudly on CI when it isn't. */
class PostgresConfigured : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean {
        val configured = System.getenv(URL_ENV) != null
        check(configured || System.getenv("CI") != "true") { "$URL_ENV must be set on CI" }
        return configured
    }

    companion object {
        private const val URL_ENV = "PAYMENTS_TEST_POSTGRES_URL"

        fun url(): String = requireNotNull(System.getenv(URL_ENV)) { "$URL_ENV is not set" }
    }
}
