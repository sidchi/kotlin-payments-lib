package io.github.sidchi.payments.idempotency.testing

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.idempotency.AcquireResult
import io.github.sidchi.payments.idempotency.IdempotencyLease
import io.github.sidchi.payments.idempotency.IdempotencyRecord
import io.github.sidchi.payments.idempotency.IdempotencyStore
import io.github.sidchi.payments.idempotency.LeaseLostException
import io.github.sidchi.payments.idempotency.RequestFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Behaviour every [IdempotencyStore] must have. Include it from a spec with a factory that returns an **empty**
 * store driven by the given clock:
 *
 * ```kotlin
 * class MyStoreSpec : FunSpec({ include(idempotencyStoreContract { clock -> MyStore(clock) }) })
 * ```
 */
public fun idempotencyStoreContract(newStore: (MutableClock) -> IdempotencyStore): TestFactory = funSpec {
    val lease = Duration.ofSeconds(30)
    val key = IdempotencyKey("sub_synthetic_1:renewal:2026-12")
    val fingerprint = RequestFingerprint.of(SampleCharges.request(key.value))
    val otherFingerprint = RequestFingerprint.of(SampleCharges.request(key.value, amount = "29.99"))

    fun IdempotencyStore.acquire(): IdempotencyLease =
        tryAcquire(key, fingerprint, lease).shouldBeInstanceOf<AcquireResult.Acquired>().lease

    test("first caller acquires a lease on a new key") {
        val clock = MutableClock()
        val store = newStore(clock)
        val acquired = store.acquire()
        acquired.attempt shouldBe 1
        acquired.isTakeover shouldBe false
        acquired.expiresAt shouldBe clock.instant().plus(lease)
        store.find(key) shouldBe IdempotencyRecord.InProgress(key, fingerprint, 1, acquired.expiresAt)
    }

    test("a live lease makes other callers wait") {
        val clock = MutableClock()
        val store = newStore(clock)
        val held = store.acquire()
        clock.advance(Duration.ofSeconds(29))
        store.tryAcquire(key, fingerprint, lease) shouldBe AcquireResult.InProgress(held.expiresAt)
    }

    test("a different request under the same key is rejected, in progress or completed") {
        val clock = MutableClock()
        val store = newStore(clock)
        val held = store.acquire()
        val mismatch = AcquireResult.FingerprintMismatch(fingerprint, otherFingerprint)
        store.tryAcquire(key, otherFingerprint, lease) shouldBe mismatch
        store.complete(held, SampleCharges.approved(key))
        store.tryAcquire(key, otherFingerprint, lease) shouldBe mismatch
        clock.advance(Duration.ofHours(1))
        store.tryAcquire(key, otherFingerprint, lease) shouldBe mismatch
    }

    context("completed results replay exactly") {
        val results: Map<String, ChargeResult> = mapOf(
            "approved with settlement FX" to SampleCharges.approved(key),
            "soft decline with raw code" to SampleCharges.declined(key),
            "hard decline without optional fields" to SampleCharges.hardDeclined(key),
            "invalid-request failure" to SampleCharges.failed(key, FailureReason.INVALID_REQUEST),
        )
        results.forEach { (name, result) ->
            test(name) {
                val clock = MutableClock()
                val store = newStore(clock)
                val held = store.acquire()
                clock.advance(Duration.ofSeconds(2))
                store.complete(held, result)
                store.tryAcquire(key, fingerprint, lease) shouldBe AcquireResult.AlreadyCompleted(result)
                store.find(key) shouldBe IdempotencyRecord.Completed(key, fingerprint, 1, result, clock.instant())
            }
        }
    }

    test("an expired lease is taken over and the old holder can no longer write") {
        val clock = MutableClock()
        val store = newStore(clock)
        val first = store.acquire()
        clock.advance(lease)
        val second = store.acquire()
        second.attempt shouldBe 2
        second.isTakeover shouldBe true
        shouldThrow<LeaseLostException> { store.complete(first, SampleCharges.approved(key)) }
        store.release(first) shouldBe false
        store.complete(second, SampleCharges.approved(key))
        store.find(key).shouldBeInstanceOf<IdempotencyRecord.Completed>().attempts shouldBe 2
    }

    test("a holder may still complete after expiry if nobody took over") {
        val clock = MutableClock()
        val store = newStore(clock)
        val held = store.acquire()
        clock.advance(lease.multipliedBy(2))
        store.complete(held, SampleCharges.declined(key))
        store.tryAcquire(key, fingerprint, lease) shouldBe AcquireResult.AlreadyCompleted(SampleCharges.declined(key))
    }

    test("release forgets the key so the next caller starts fresh") {
        val clock = MutableClock()
        val store = newStore(clock)
        val held = store.acquire()
        store.release(held) shouldBe true
        store.find(key).shouldBeNull()
        store.release(held) shouldBe false
        store.acquire().attempt shouldBe 1
    }

    test("a lease completes at most once") {
        val clock = MutableClock()
        val store = newStore(clock)
        val held = store.acquire()
        store.complete(held, SampleCharges.approved(key))
        shouldThrow<LeaseLostException> { store.complete(held, SampleCharges.declined(key)) }
        store.release(held) shouldBe false
        store.find(key).shouldBeInstanceOf<IdempotencyRecord.Completed>().result shouldBe SampleCharges.approved(key)
    }

    test("purge removes only completed records older than the cutoff") {
        val clock = MutableClock()
        val store = newStore(clock)
        fun keyed(name: String) = IdempotencyKey(name).let { it to RequestFingerprint.of(SampleCharges.request(name)) }
        val (old, oldFp) = keyed("old")
        val (recent, recentFp) = keyed("recent")
        val (inFlight, inFlightFp) = keyed("in-flight")

        val oldLease = store.tryAcquire(old, oldFp, lease).shouldBeInstanceOf<AcquireResult.Acquired>().lease
        store.complete(oldLease, SampleCharges.declined(old))
        store.tryAcquire(inFlight, inFlightFp, lease).shouldBeInstanceOf<AcquireResult.Acquired>()
        clock.advance(Duration.ofDays(2))
        val cutoff = clock.instant()
        val recentLease = store.tryAcquire(recent, recentFp, lease).shouldBeInstanceOf<AcquireResult.Acquired>().lease
        store.complete(recentLease, SampleCharges.declined(recent))

        store.purgeCompletedBefore(cutoff) shouldBe 1
        store.find(old).shouldBeNull()
        store.find(recent).shouldBeInstanceOf<IdempotencyRecord.Completed>()
        store.find(inFlight).shouldBeInstanceOf<IdempotencyRecord.InProgress>()
    }

    test("exactly one of many concurrent callers acquires each key") {
        val store = newStore(MutableClock())
        val threads = 16
        val keys = (1..25).map { IdempotencyKey("concurrent:$it") }
        val winners = ConcurrentHashMap<IdempotencyKey, MutableList<Int>>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { thread ->
                pool.execute {
                    start.await()
                    // Every thread walks every key, in a thread-specific order, to maximize contention.
                    keys.shuffled(Random(thread)).forEach { k ->
                        val fp = RequestFingerprint.of(SampleCharges.request(k.value))
                        if (store.tryAcquire(k, fp, lease) is AcquireResult.Acquired) {
                            winners.computeIfAbsent(k) { Collections.synchronizedList(mutableListOf()) }
                                .add(thread)
                        }
                    }
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES) shouldBe true
        }
        winners.keys shouldHaveSize keys.size
        winners.values.forEach { it shouldHaveSize 1 }
    }
}
