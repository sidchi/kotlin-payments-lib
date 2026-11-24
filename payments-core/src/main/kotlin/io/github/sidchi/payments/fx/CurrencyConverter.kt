package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.Conversion
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.ExchangeRate
import io.github.sidchi.payments.domain.Money
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration

/**
 * Converts [Money] between currencies using an [ExchangeRateProvider], refusing to use rates older than [maxRateAge].
 *
 * Same-currency conversions short-circuit to an identity rate and never touch the provider, so a single-currency
 * merchant pays nothing for the abstraction and is unaffected by a rate-feed outage.
 */
public class CurrencyConverter(
    private val provider: ExchangeRateProvider,
    /** Oldest snapshot the converter will use; e.g. 26 hours for a daily reference feed. */
    public val maxRateAge: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!maxRateAge.isNegative && !maxRateAge.isZero) { "maxRateAge must be positive, was $maxRateAge" }
    }

    /**
     * Converts [money] into [target], rounding to [target]'s minor unit with [roundingMode].
     *
     * Snapshots stamped slightly in the future (clock skew between the feed and this host) are treated as fresh.
     *
     * @throws StaleExchangeRateException if the newest snapshot is older than [maxRateAge].
     * @throws ExchangeRateUnavailableException if the snapshot has no rate for the pair.
     */
    public fun convert(money: Money, target: Currency, roundingMode: RoundingMode): Conversion {
        if (money.currency == target) {
            return ExchangeRate.identity(target, clock.instant()).convert(money, roundingMode)
        }
        return freshSnapshot().rate(money.currency, target).convert(money, roundingMode)
    }

    /**
     * The current rate for [from] -> [to], subject to the same freshness check as [convert]. Useful for quoting a
     * rate to a customer before charging.
     */
    public fun rate(from: Currency, to: Currency): ExchangeRate =
        if (from == to) ExchangeRate.identity(from, clock.instant()) else freshSnapshot().rate(from, to)

    private fun freshSnapshot(): RateSnapshot {
        val snapshot = provider.currentSnapshot()
        val age = Duration.between(snapshot.asOf, clock.instant())
        if (age > maxRateAge) throw StaleExchangeRateException(snapshot.asOf, age, maxRateAge)
        return snapshot
    }
}
