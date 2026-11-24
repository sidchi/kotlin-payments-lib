package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.Currency
import java.time.Duration
import java.time.Instant

/** No rate is available to convert [from] into [to]. */
public class ExchangeRateUnavailableException(
    /** Currency being sold. */
    public val from: Currency,
    /** Currency being bought. */
    public val to: Currency,
    /** Rate source that was asked. */
    public val source: String,
) : RuntimeException("No $from->$to rate available from '$source'")

/**
 * The newest available rates are older than the converter's freshness limit.
 *
 * Converting on a stale rate is how a merchant silently eats an FX move, so the converter refuses instead of
 * guessing; callers decide whether to fail the charge, settle in the presentment currency, or alert.
 */
public class StaleExchangeRateException(
    /** Publication time of the newest snapshot. */
    public val asOf: Instant,
    /** How old that snapshot was when the conversion was attempted. */
    public val age: Duration,
    /** The configured freshness limit. */
    public val maxAge: Duration,
) : RuntimeException("Exchange rates as of $asOf are $age old, older than the allowed $maxAge")
