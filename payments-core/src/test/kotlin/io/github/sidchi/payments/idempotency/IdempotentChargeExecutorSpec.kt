package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.idempotency.testing.MutableClock
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdempotentChargeExecutorSpec : FunSpec({
    val lease = Duration.ofSeconds(30)
    val request = SampleCharges.request()
    val key = request.idempotencyKey

    class Fixture {
        val clock = MutableClock()
        val store = InMemoryIdempotencyStore(clock)
        val executor = IdempotentChargeExecutor(store, lease)
        val calls = AtomicInteger()

        fun charge(req: ChargeRequest = request, result: (IdempotencyLease) -> ChargeResult): IdempotentOutcome =
            executor.execute(req) { _, l ->
                calls.incrementAndGet()
                result(l)
            }
    }

    test("an approval is stored and every retry replays it without calling the processor") {
        val f = Fixture()
        val approved = SampleCharges.approved(key)
        f.charge { approved } shouldBe IdempotentOutcome.Executed(approved, attempt = 1, Persistence.STORED)
        repeat(3) { f.charge { error("must not be called") } shouldBe IdempotentOutcome.Replayed(approved) }
        f.calls.get() shouldBe 1
    }

    test("declines and invalid requests are final too") {
        listOf(SampleCharges.declined(key), SampleCharges.failed(key, FailureReason.INVALID_REQUEST)).forEach { r ->
            val f = Fixture()
            f.charge { r }.shouldBeInstanceOf<IdempotentOutcome.Executed>().persistence shouldBe Persistence.STORED
            f.charge { error("must not be called") } shouldBe IdempotentOutcome.Replayed(r)
        }
    }

    test("a failure the processor never processed releases the key for an immediate retry") {
        val f = Fixture()
        val unavailable = SampleCharges.failed(key, FailureReason.PROCESSOR_UNAVAILABLE)
        f.charge { unavailable } shouldBe IdempotentOutcome.Executed(unavailable, 1, Persistence.RELEASED)
        val approved = SampleCharges.approved(key)
        f.charge { approved } shouldBe IdempotentOutcome.Executed(approved, 1, Persistence.STORED)
        f.calls.get() shouldBe 2
    }

    test("an unknown outcome holds the key until the lease expires, then the next caller takes over") {
        val f = Fixture()
        val timeout = SampleCharges.failed(key, FailureReason.TIMEOUT)
        f.charge { timeout } shouldBe IdempotentOutcome.Executed(timeout, 1, Persistence.HELD_FOR_RECOVERY)

        val waiting = f.charge { error("must not be called") }.shouldBeInstanceOf<IdempotentOutcome.InProgress>()
        waiting.retryAfter shouldBe MutableClock.DEFAULT_START.plus(lease)

        f.clock.advance(lease)
        var sawTakeover = false
        val approved = SampleCharges.approved(key)
        f.charge { l ->
            sawTakeover = l.isTakeover
            approved
        } shouldBe IdempotentOutcome.Executed(approved, attempt = 2, Persistence.STORED)
        sawTakeover shouldBe true
    }

    test("an exception from the processor call propagates and holds the key for recovery") {
        val f = Fixture()
        shouldThrow<IllegalStateException> { f.charge { error("socket closed") } }
        f.store.find(key).shouldBeInstanceOf<IdempotencyRecord.InProgress>()
        f.charge { error("must not be called") }.shouldBeInstanceOf<IdempotentOutcome.InProgress>()
    }

    test("reusing a key for a different request is refused without charging") {
        val f = Fixture()
        f.charge { SampleCharges.approved(key) }
        val other = SampleCharges.request(amount = "99.00")
        f.charge(other) { error("must not be called") } shouldBe
            IdempotentOutcome.KeyReused(RequestFingerprint.of(request), RequestFingerprint.of(other))
    }

    test("a holder whose lease was taken over still returns its result but writes nothing") {
        val f = Fixture()
        val approved = SampleCharges.approved(key)
        val outcome = f.charge {
            // Simulate a stall longer than the lease, during which another worker takes the key over.
            f.clock.advance(lease)
            f.store.tryAcquire(key, RequestFingerprint.of(request), lease)
                .shouldBeInstanceOf<AcquireResult.Acquired>()
            approved
        }
        outcome shouldBe IdempotentOutcome.Executed(approved, 1, Persistence.LEASE_LOST)
        f.store.find(key).shouldBeInstanceOf<IdempotencyRecord.InProgress>().attempts shouldBe 2
    }

    test("rejects a charge function that answers for a different key") {
        val f = Fixture()
        shouldThrow<IllegalArgumentException> { f.charge { SampleCharges.approved(IdempotencyKey("someone-else")) } }
    }

    test("concurrent duplicate submissions call the processor exactly once") {
        val f = Fixture()
        val approved = SampleCharges.approved(key)
        val threads = 16
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<IdempotentOutcome>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    outcomes += f.charge {
                        Thread.sleep(50) // hold the lease while the others arrive
                        approved
                    }
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES) shouldBe true
        }
        f.calls.get() shouldBe 1
        outcomes.filterIsInstance<IdempotentOutcome.Executed>() shouldContainOnly
            listOf(IdempotentOutcome.Executed(approved, 1, Persistence.STORED))
        outcomes.count { it is IdempotentOutcome.Executed } shouldBe 1
        outcomes.filterNot { it is IdempotentOutcome.Executed }.forEach { outcome ->
            (outcome is IdempotentOutcome.InProgress || outcome == IdempotentOutcome.Replayed(approved)) shouldBe true
        }
    }
})
