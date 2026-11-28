package io.github.sidchi.payments.gateway.mock

import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * How long a mock processor takes to answer. Sampled once per call from the processor's seeded [Random], so a run is
 * reproducible.
 *
 * Real processor latency is not a bell curve: most calls are fast and a few are very slow (issuer stand-in, network
 * hiccups). [Spiky] models that long tail, and it is the tail — not the median — that trips timeouts and circuit
 * breakers.
 */
public sealed interface LatencyModel {
    /** Draws one latency. */
    public fun sample(random: Random): Duration

    /** Always [latency]. */
    public data class Fixed(
        /** The latency; must not be negative. */
        public val latency: Duration,
    ) : LatencyModel {
        init {
            require(!latency.isNegative) { "Latency must not be negative, was $latency" }
        }

        override fun sample(random: Random): Duration = latency
    }

    /** Uniformly distributed between [min] and [max], inclusive. */
    public data class Uniform(
        /** Shortest latency; must not be negative. */
        public val min: Duration,
        /** Longest latency; must be at least [min]. */
        public val max: Duration,
    ) : LatencyModel {
        init {
            require(!min.isNegative) { "Minimum latency must not be negative, was $min" }
            require(max >= min) { "Maximum latency $max must be at least the minimum $min" }
        }

        override fun sample(random: Random): Duration =
            Duration.ofNanos(random.nextLong(min.toNanos(), max.toNanos() + 1))
    }

    /** Mostly [base], but with probability [spikeProbability] a draw from [spike] instead: a long-tail model. */
    public data class Spiky(
        /** Latency of a normal call. */
        public val base: LatencyModel,
        /** Latency of a slow call. */
        public val spike: LatencyModel,
        /** Share of calls that are slow, in `[0, 1]`. */
        public val spikeProbability: Double,
    ) : LatencyModel {
        init {
            require(spikeProbability in 0.0..1.0) { "Spike probability must be in [0, 1], was $spikeProbability" }
        }

        override fun sample(random: Random): Duration =
            if (random.nextDouble() < spikeProbability) spike.sample(random) else base.sample(random)
    }
}

/**
 * Waits out simulated latency. Production code uses [REAL]; tests pass a recorder so a "3 second timeout" test takes
 * microseconds and can assert exactly how long the gateway would have waited.
 */
public fun interface Sleeper {
    /** Blocks the calling thread for [duration]. */
    public fun sleep(duration: Duration)

    /** Implementations. */
    public companion object {
        /** Really sleeps the calling thread. */
        public val REAL: Sleeper = Sleeper { duration ->
            if (!duration.isNegative && !duration.isZero) TimeUnit.NANOSECONDS.sleep(duration.toNanos())
        }

        /** Returns immediately. */
        public val NONE: Sleeper = Sleeper { }
    }
}
