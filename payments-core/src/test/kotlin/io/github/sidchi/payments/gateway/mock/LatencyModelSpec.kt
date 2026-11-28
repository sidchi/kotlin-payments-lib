package io.github.sidchi.payments.gateway.mock

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration
import kotlin.random.Random

class LatencyModelSpec : FunSpec({
    test("fixed latency is constant") {
        val model = LatencyModel.Fixed(Duration.ofMillis(80))
        repeat(10) { model.sample(Random(it)) shouldBe Duration.ofMillis(80) }
    }

    test("uniform latency stays within its inclusive bounds for any seed") {
        val model = LatencyModel.Uniform(Duration.ofMillis(50), Duration.ofMillis(250))
        checkAll(Arb.long()) { seed ->
            val random = Random(seed)
            repeat(20) {
                val sample = model.sample(random)
                (sample >= model.min && sample <= model.max) shouldBe true
            }
        }
    }

    test("a degenerate uniform range returns its single value") {
        LatencyModel.Uniform(Duration.ofMillis(5), Duration.ofMillis(5)).sample(Random(1)) shouldBe Duration.ofMillis(5)
    }

    test("spiky latency takes the spike branch at roughly the configured rate") {
        val fast = Duration.ofMillis(100)
        val slow = Duration.ofSeconds(12)
        val model = LatencyModel.Spiky(LatencyModel.Fixed(fast), LatencyModel.Fixed(slow), spikeProbability = 0.05)
        val random = Random(7)
        val draws = 20_000
        val spikes = (1..draws).count { model.sample(random) == slow }
        spikes.toDouble() / draws shouldBe (0.05 plusOrMinus 0.01)
    }

    test("invalid parameters are rejected") {
        shouldThrow<IllegalArgumentException> { LatencyModel.Fixed(Duration.ofMillis(-1)) }
        shouldThrow<IllegalArgumentException> { LatencyModel.Uniform(Duration.ofMillis(-1), Duration.ofMillis(1)) }
        shouldThrow<IllegalArgumentException> { LatencyModel.Uniform(Duration.ofMillis(9), Duration.ofMillis(1)) }
        shouldThrow<IllegalArgumentException> {
            LatencyModel.Spiky(LatencyModel.Fixed(Duration.ZERO), LatencyModel.Fixed(Duration.ZERO), 1.5)
        }
    }

    test("Sleeper.REAL tolerates zero and negative durations") {
        Sleeper.REAL.sleep(Duration.ZERO)
        Sleeper.REAL.sleep(Duration.ofMillis(-5))
        Sleeper.REAL.sleep(Duration.ofMillis(1))
    }
})
