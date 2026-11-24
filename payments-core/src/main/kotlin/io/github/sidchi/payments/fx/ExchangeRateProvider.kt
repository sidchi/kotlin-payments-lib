package io.github.sidchi.payments.fx

import java.util.concurrent.atomic.AtomicReference

/**
 * Source of exchange rates. Implementations wrap a reference-rate feed, a processor's FX quote API, or (in tests and
 * simulations) a fixed table.
 *
 * The contract is deliberately "give me your newest consistent snapshot" rather than "give me one rate": all
 * conversions in a batch (an invoice's lines, a settlement report) should use rates from the same instant.
 */
public fun interface ExchangeRateProvider {
    /** The newest snapshot this provider has. Must not block for long; providers should cache. */
    public fun currentSnapshot(): RateSnapshot
}

/**
 * Thread-safe in-memory provider: holds one snapshot and lets tests or a background refresher [publish] newer ones.
 *
 * Publishing a snapshot older than the current one is rejected, so an out-of-order or replayed feed message can never
 * roll rates back in time.
 */
public class InMemoryExchangeRateProvider(initial: RateSnapshot) : ExchangeRateProvider {
    private val current = AtomicReference(initial)

    override fun currentSnapshot(): RateSnapshot = current.get()

    /**
     * Replaces the current snapshot with [snapshot].
     *
     * @throws IllegalArgumentException if [snapshot] is older than the current snapshot.
     */
    public fun publish(snapshot: RateSnapshot) {
        current.updateAndGet { existing ->
            require(!snapshot.asOf.isBefore(existing.asOf)) {
                "Refusing to publish rates as of ${snapshot.asOf}; current rates are newer (${existing.asOf})"
            }
            snapshot
        }
    }
}
