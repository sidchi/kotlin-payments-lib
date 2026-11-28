package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.CardExpiry
import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.domain.TokenizedCard
import io.github.sidchi.payments.fx.CurrencyConverter
import io.github.sidchi.payments.fx.InMemoryExchangeRateProvider
import io.github.sidchi.payments.fx.RateSnapshot
import io.github.sidchi.payments.gateway.ProcessorId
import io.github.sidchi.payments.idempotency.testing.MutableClock
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Records simulated waits instead of sleeping. */
private class RecordingSleeper : Sleeper {
    val waits: MutableList<Duration> = Collections.synchronizedList(mutableListOf())

    override fun sleep(duration: Duration) {
        waits += duration
    }
}

private fun withToken(token: String, key: String = "sub_synthetic_1:renewal:2026-12"): ChargeRequest =
    SampleCharges.request(key).copy(
        paymentMethod = TokenizedCard(token, CardBrand.VISA, "4242", CardExpiry(month = 12, year = 2028)),
    )

private fun requests(count: Int): List<ChargeRequest> =
    (1..count).map { SampleCharges.request(key = "sub_synthetic_$it:renewal:2026-12") }

class MockProcessorSpec : FunSpec({
    val latency = Duration.ofMillis(120)
    val timeout = Duration.ofSeconds(3)
    val healthy = MockProcessorConfig(latency = LatencyModel.Fixed(latency), timeout = timeout)

    fun meridian(config: MockProcessorConfig = healthy, sleeper: Sleeper = Sleeper.NONE, seed: Long = 1) =
        MockProcessors.meridian(config = config, sleeper = sleeper, seed = seed)

    context("happy path") {
        test("approves by default, after the configured latency, with a dialect-shaped charge id") {
            val sleeper = RecordingSleeper()
            val gateway = meridian(sleeper = sleeper)
            val request = SampleCharges.request()

            val approved = gateway.charge(request).shouldBeInstanceOf<ChargeResult.Approved>()

            approved.idempotencyKey shouldBe request.idempotencyKey
            approved.amount shouldBe request.amount
            approved.chargeId shouldBe "mer_ch_0000000001"
            approved.authorizationCode.shouldNotBeNull().length shouldBe 6
            approved.settlementConversion.shouldBeNull()
            sleeper.waits shouldContainExactly listOf(latency)
            gateway.calls shouldBe 1
            gateway.recordedCharges shouldBe 1
        }

        test("the two mock processors are distinct connections") {
            val meridian = MockProcessors.meridian(sleeper = Sleeper.NONE)
            val tessera = MockProcessors.tessera(sleeper = Sleeper.NONE)
            meridian.processorId shouldBe ProcessorId("meridian")
            tessera.processorId shouldBe ProcessorId("tessera")
            meridian.capabilities shouldBe MockProcessors.MERIDIAN_CAPABILITIES
            tessera.capabilities shouldBe MockProcessors.TESSERA_CAPABILITIES
            tessera.charge(SampleCharges.request()).shouldBeInstanceOf<ChargeResult.Approved>()
                .chargeId shouldStartWith "tes_ch_"
        }
    }

    context("processor-side idempotency") {
        test("re-sending the same request replays the recorded result without a new charge") {
            val gateway = meridian()
            val request = SampleCharges.request()
            val first = gateway.charge(request)
            repeat(3) { gateway.charge(request) shouldBe first }
            gateway.calls shouldBe 4
            gateway.recordedCharges shouldBe 1
            gateway.lookup(request.idempotencyKey) shouldBe first
        }

        test("a declined key keeps declining with the same decline on retry") {
            val gateway = meridian()
            val request = withToken(MockTokens.decline("51"))
            val first = gateway.charge(request).shouldBeInstanceOf<ChargeResult.Declined>()
            gateway.charge(request) shouldBe first
        }

        test("reusing a key with different parameters is an invalid request, and the original stands") {
            val gateway = meridian()
            val original = SampleCharges.request(amount = "19.99")
            val approved = gateway.charge(original)

            val reused = gateway.charge(SampleCharges.request(amount = "29.99"))

            reused.shouldBeInstanceOf<ChargeResult.Failed>().reason shouldBe FailureReason.INVALID_REQUEST
            reused.message.shouldNotBeNull() shouldContain "reused with different parameters"
            gateway.lookup(original.idempotencyKey) shouldBe approved
        }

        test("lookup of an unknown key is null") {
            meridian().lookup(IdempotencyKey("never-sent")).shouldBeNull()
        }

        test("concurrent sends of one key create exactly one charge") {
            val gateway = meridian(config = healthy.copy(latency = LatencyModel.Fixed(Duration.ofMillis(5))))
            val request = SampleCharges.request()
            val threads = 16
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<ChargeResult>())
            try {
                repeat(threads) {
                    pool.execute {
                        start.await()
                        results += gateway.charge(request)
                    }
                }
                start.countDown()
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
            }

            results shouldHaveSize threads
            results.toSet() shouldHaveSize 1
            gateway.recordedCharges shouldBe 1
        }
    }

    context("magic tokens force outcomes") {
        test("decline codes are given and returned in the processor's own dialect") {
            val meridian = meridian()
            val tessera = MockProcessors.tessera(sleeper = Sleeper.NONE)

            val iso = meridian.charge(withToken(MockTokens.decline("54"))).shouldBeInstanceOf<ChargeResult.Declined>()
            iso.decline.reason shouldBe DeclineReason.ExpiredCard
            iso.decline.rawCode shouldBe "54"
            iso.chargeId.shouldNotBeNull() shouldStartWith "mer_ch_"

            val rest = tessera.charge(withToken(MockTokens.decline("expired_card")))
                .shouldBeInstanceOf<ChargeResult.Declined>()
            rest.decline.reason shouldBe DeclineReason.ExpiredCard
            rest.decline.rawCode shouldBe "expired_card"
        }

        test("a code from the wrong dialect is an unknown, hard decline") {
            val declined = MockProcessors.tessera(sleeper = Sleeper.NONE).charge(withToken(MockTokens.decline("51")))
            declined.shouldBeInstanceOf<ChargeResult.Declined>().decline.reason shouldBe DeclineReason.Unknown
        }

        test("approve overrides a 100% decline rate and any fault rate") {
            val hostile = healthy.copy(declineRate = 1.0, faults = FaultRates(unavailable = 1.0))
            meridian(config = hostile).charge(withToken(MockTokens.APPROVE))
                .shouldBeInstanceOf<ChargeResult.Approved>()
        }

        test("503 and 429 are rejected before processing, so nothing is recorded") {
            val gateway = meridian()
            mapOf(
                MockTokens.UNAVAILABLE to FailureReason.PROCESSOR_UNAVAILABLE,
                MockTokens.RATE_LIMITED to FailureReason.RATE_LIMITED,
            ).forEach { (token, reason) ->
                val failed = gateway.charge(withToken(token)).shouldBeInstanceOf<ChargeResult.Failed>()
                failed.reason shouldBe reason
                failed.reason.outcomeKnown shouldBe true
            }
            gateway.recordedCharges shouldBe 0
        }

        test("a timeout waits exactly the timeout and, unprocessed, leaves no record") {
            val sleeper = RecordingSleeper()
            val gateway = meridian(sleeper = sleeper)
            val request = withToken(MockTokens.TIMEOUT)

            val failed = gateway.charge(request).shouldBeInstanceOf<ChargeResult.Failed>()

            failed.reason shouldBe FailureReason.TIMEOUT
            failed.reason.outcomeKnown shouldBe false
            sleeper.waits shouldContainExactly listOf(timeout)
            gateway.lookup(request.idempotencyKey).shouldBeNull()
        }

        test("a timeout after processing is found by lookup and replayed by a same-key retry") {
            val gateway = meridian()
            val request = withToken(MockTokens.TIMEOUT_AFTER_PROCESSING)

            gateway.charge(request).shouldBeInstanceOf<ChargeResult.Failed>().reason shouldBe FailureReason.TIMEOUT
            val recorded = gateway.lookup(request.idempotencyKey).shouldBeInstanceOf<ChargeResult.Approved>()

            // A same-key retry times out again (the token forces it), but finds the charge and creates no second one.
            gateway.charge(request).shouldBeInstanceOf<ChargeResult.Failed>()
            gateway.recordedCharges shouldBe 1
            gateway.lookup(request.idempotencyKey) shouldBe recorded
        }

        test("a dropped connection reports an unknown outcome") {
            val failed = meridian().charge(
                withToken(MockTokens.NETWORK_ERROR),
            ).shouldBeInstanceOf<ChargeResult.Failed>()
            failed.reason shouldBe FailureReason.NETWORK_ERROR
            failed.reason.outcomeKnown shouldBe false
        }
    }

    context("configured rates") {
        val n = 10_000

        test("fault rates are honored statistically, and only unprocessed faults are absent from the ledger") {
            val config = healthy.copy(
                faults = FaultRates(unavailable = 0.10, rateLimited = 0.05, networkError = 0.05),
                processedWhenResponseLost = 0.0,
            )
            val gateway = meridian(config = config, seed = 11)

            val results = requests(n).map(gateway::charge)

            val byReason = results.filterIsInstance<ChargeResult.Failed>().groupingBy { it.reason }.eachCount()
            byReason.getValue(FailureReason.PROCESSOR_UNAVAILABLE).toDouble() / n shouldBe (0.10 plusOrMinus 0.015)
            byReason.getValue(FailureReason.RATE_LIMITED).toDouble() / n shouldBe (0.05 plusOrMinus 0.01)
            byReason.getValue(FailureReason.NETWORK_ERROR).toDouble() / n shouldBe (0.05 plusOrMinus 0.01)
            gateway.recordedCharges shouldBe results.count { it is ChargeResult.Approved }
        }

        test("decline rate and code mix are honored") {
            val config = healthy.copy(declineRate = 0.3, declineCodes = mapOf("51" to 3, "05" to 1))
            val results = requests(n).map(meridian(config = config, seed = 5)::charge)

            val declines = results.filterIsInstance<ChargeResult.Declined>()
            declines.size.toDouble() / n shouldBe (0.3 plusOrMinus 0.02)
            val byCode = declines.groupingBy { it.decline.rawCode }.eachCount()
            byCode.keys shouldBe setOf("51", "05")
            byCode.getValue("51").toDouble() / declines.size shouldBe (0.75 plusOrMinus 0.03)
        }

        test("without an explicit mix, declines are drawn from the dialect's default mix") {
            val results = requests(2_000).map(meridian(config = healthy.copy(declineRate = 1.0))::charge)
            val codes = results.filterIsInstance<ChargeResult.Declined>().map { it.decline.rawCode }.toSet()
            MeridianDialect.defaultDeclineMix.shouldContainKeys(*codes.toTypedArray())
            codes.size shouldBe MeridianDialect.defaultDeclineMix.size
        }

        test("long-tail latency produces timeouts at the spike rate, and each waits only the timeout") {
            val sleeper = RecordingSleeper()
            val config = healthy.copy(
                latency = LatencyModel.Spiky(
                    base = LatencyModel.Fixed(latency),
                    spike = LatencyModel.Fixed(Duration.ofSeconds(30)),
                    spikeProbability = 0.02,
                ),
                processedWhenResponseLost = 1.0,
            )
            val gateway = meridian(config = config, sleeper = sleeper, seed = 3)
            val all = requests(n)

            val results = all.map(gateway::charge)

            val timeouts = results.filterIsInstance<ChargeResult.Failed>()
            timeouts.all { it.reason == FailureReason.TIMEOUT } shouldBe true
            timeouts.size.toDouble() / n shouldBe (0.02 plusOrMinus 0.005)
            sleeper.waits.max() shouldBe timeout
            // processedWhenResponseLost = 1.0: every timed-out charge exists at the processor.
            gateway.recordedCharges shouldBe n
            timeouts.forEach { gateway.lookup(it.idempotencyKey).shouldBeInstanceOf<ChargeResult.Approved>() }
        }

        test("a retry after a processed timeout replays the original charge instead of creating a second one") {
            val config = healthy.copy(
                latency = LatencyModel.Spiky(
                    LatencyModel.Fixed(latency),
                    LatencyModel.Fixed(timeout.multipliedBy(2)),
                    0.5,
                ),
                processedWhenResponseLost = 1.0,
            )
            val gateway = meridian(config = config, seed = 9)
            val request = SampleCharges.request()

            var result = gateway.charge(request)
            while (result is ChargeResult.Failed) result = gateway.charge(request)

            result.shouldBeInstanceOf<ChargeResult.Approved>()
            gateway.recordedCharges shouldBe 1
            gateway.lookup(request.idempotencyKey) shouldBe result
        }

        test("the same seed reproduces the same results") {
            val config = healthy.copy(declineRate = 0.2, faults = FaultRates(unavailable = 0.1, networkError = 0.1))
            val batch = requests(500)
            batch.map(meridian(config = config, seed = 77)::charge) shouldBe
                batch.map(meridian(config = config, seed = 77)::charge)
        }
    }

    context("capabilities and FX") {
        test("an unsupported request is rejected without being processed or waiting") {
            val sleeper = RecordingSleeper()
            val gateway =
                meridian(config = healthy.copy(capabilities = MockProcessors.MERIDIAN_CAPABILITIES), sleeper = sleeper)
            val jpy = SampleCharges.request().copy(amount = Money.of("2500", Currency.JPY))

            val failed = gateway.charge(jpy).shouldBeInstanceOf<ChargeResult.Failed>()

            failed.reason shouldBe FailureReason.INVALID_REQUEST
            failed.message.shouldNotBeNull() shouldContain "presentment currency JPY"
            sleeper.waits shouldBe emptyList()
            gateway.recordedCharges shouldBe 0
        }

        val clock = MutableClock(Instant.parse("2026-11-26T03:00:00Z"))
        val rates = InMemoryExchangeRateProvider(
            RateSnapshot(
                base = Currency.EUR,
                rates = mapOf(Currency.USD to BigDecimal("1.0842")), // synthetic sample rate
                asOf = Instant.parse("2026-11-26T00:00:00Z"),
                source = "mock",
            ),
        )
        val eurSettledInUsd = SampleCharges.request().copy(
            amount = Money.of("10.00", Currency.EUR),
            settlementCurrency = Currency.USD,
        )

        test("a cross-currency charge settles through the configured converter") {
            val converter = CurrencyConverter(rates, maxRateAge = Duration.ofHours(26), clock = clock)
            val gateway = MockProcessors.meridian(config = healthy, converter = converter, sleeper = Sleeper.NONE)

            val approved = gateway.charge(eurSettledInUsd).shouldBeInstanceOf<ChargeResult.Approved>()

            approved.amount shouldBe Money.of("10.00", Currency.EUR)
            approved.settlementAmount shouldBe Money.of("10.84", Currency.USD)
        }

        test("a cross-currency charge without a converter is an invalid request") {
            val failed = meridian().charge(eurSettledInUsd).shouldBeInstanceOf<ChargeResult.Failed>()
            failed.reason shouldBe FailureReason.INVALID_REQUEST
            failed.message.shouldNotBeNull() shouldContain "requires FX"
        }

        test("stale rates fail the charge as transient and record nothing") {
            val converter = CurrencyConverter(rates, maxRateAge = Duration.ofHours(1), clock = clock)
            val gateway = MockProcessors.meridian(config = healthy, converter = converter, sleeper = Sleeper.NONE)

            val failed = gateway.charge(eurSettledInUsd).shouldBeInstanceOf<ChargeResult.Failed>()

            failed.reason shouldBe FailureReason.PROCESSOR_UNAVAILABLE
            gateway.recordedCharges shouldBe 0
        }
    }

    test("config validation rejects impossible settings") {
        listOf(
            { healthy.copy(timeout = Duration.ZERO) },
            { healthy.copy(declineRate = 1.1) },
            { healthy.copy(processedWhenResponseLost = -0.1) },
            { healthy.copy(declineCodes = mapOf("51" to 0)) },
            { FaultRates(unavailable = 0.6, rateLimited = 0.6) },
            { FaultRates(networkError = -0.1) },
        ).forEach { build ->
            shouldThrow<IllegalArgumentException> { build() }
        }
    }
})
