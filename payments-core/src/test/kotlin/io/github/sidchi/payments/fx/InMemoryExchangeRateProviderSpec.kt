package io.github.sidchi.payments.fx

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InMemoryExchangeRateProviderSpec : FunSpec({
    test("publishes newer snapshots") {
        val provider = InMemoryExchangeRateProvider(SampleRates.eurSnapshot())
        val newer = SampleRates.eurSnapshot(SampleRates.ASOF.plus(Duration.ofHours(1)))
        provider.publish(newer)
        provider.currentSnapshot() shouldBeSameInstanceAs newer
    }

    test("refuses to roll rates back in time") {
        val provider = InMemoryExchangeRateProvider(SampleRates.eurSnapshot())
        val current = provider.currentSnapshot()
        shouldThrow<IllegalArgumentException> {
            provider.publish(SampleRates.eurSnapshot(SampleRates.ASOF.minusSeconds(1)))
        }
        provider.currentSnapshot() shouldBeSameInstanceAs current
    }

    test("concurrent publishers converge on the newest snapshot") {
        val provider = InMemoryExchangeRateProvider(SampleRates.eurSnapshot())
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    repeat(perThread) { i ->
                        // Interleave minutes across threads so every thread races on "newer" and "older" publishes.
                        val asOf = SampleRates.ASOF.plus(Duration.ofMinutes((i * threads + t).toLong()))
                        runCatching { provider.publish(SampleRates.eurSnapshot(asOf)) }
                    }
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true
        }
        val newest = SampleRates.ASOF.plus(Duration.ofMinutes((threads * perThread - 1).toLong()))
        provider.currentSnapshot().asOf shouldBe newest
    }
})
