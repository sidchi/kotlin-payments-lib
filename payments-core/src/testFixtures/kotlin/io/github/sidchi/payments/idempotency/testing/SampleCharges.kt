package io.github.sidchi.payments.idempotency.testing

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.CardExpiry
import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.Decline
import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.ExchangeRate
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.domain.Iso8583DeclineCodes
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.domain.TokenizedCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Synthetic charges and results for idempotency tests. Tokens and ids are fake. */
public object SampleCharges {
    /** A synthetic tokenized Visa. */
    public val card: TokenizedCard =
        TokenizedCard("tok_synthetic_visa_4242", CardBrand.VISA, "4242", CardExpiry(month = 12, year = 2028))

    /** A USD 19.99 renewal for `cus_synthetic_1` under [key]. */
    public fun request(key: String = "sub_synthetic_1:renewal:2026-12", amount: String = "19.99"): ChargeRequest =
        ChargeRequest(
            idempotencyKey = IdempotencyKey(key),
            amount = Money.of(amount, Currency.USD),
            paymentMethod = card,
            customerId = "cus_synthetic_1",
            metadata = mapOf("subscription" to "sub_synthetic_1", "invoice" to "in_synthetic_1"),
        )

    /** An approval with a USD -> EUR settlement conversion, exercising every optional field. */
    public fun approved(key: IdempotencyKey): ChargeResult.Approved {
        val amount = Money.of("19.99", Currency.USD)
        val rate = ExchangeRate(
            from = Currency.USD,
            to = Currency.EUR,
            rate = BigDecimal("0.92230"), // trailing zero: scale must survive storage
            asOf = Instant.parse("2026-11-26T00:00:00Z"),
            source = "mock",
        )
        return ChargeResult.Approved(
            idempotencyKey = key,
            chargeId = "ch_synthetic_1",
            amount = amount,
            authorizationCode = "A1B2C3",
            settlementConversion = rate.convert(amount, RoundingMode.HALF_EVEN),
        )
    }

    /** A soft "insufficient funds" decline with its raw ISO 8583 code. */
    public fun declined(key: IdempotencyKey): ChargeResult.Declined =
        ChargeResult.Declined(key, Iso8583DeclineCodes.toDecline("51"), chargeId = "ch_synthetic_2")

    /** A hard decline with no optional fields. */
    public fun hardDeclined(key: IdempotencyKey): ChargeResult.Declined =
        ChargeResult.Declined(key, Decline(DeclineReason.StolenCard))

    /** A failure of the given [reason]. */
    public fun failed(key: IdempotencyKey, reason: FailureReason): ChargeResult.Failed =
        ChargeResult.Failed(key, reason, message = "synthetic ${reason.name.lowercase()} with \"quotes\" and ünïcode")
}
