package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class ChargeSpec : FunSpec({
    val key = IdempotencyKey("sub_123:renewal:2026-12")
    val card = TokenizedCard("tok_synthetic_mc_4444", CardBrand.MASTERCARD, "4444", CardExpiry(1, 2029))

    fun request(amount: Money) = ChargeRequest(
        idempotencyKey = key,
        amount = amount,
        paymentMethod = card,
        customerId = "cus_synthetic_1",
    )

    context("IdempotencyKey") {
        test("rejects blank and over-long keys") {
            shouldThrow<IllegalArgumentException> { IdempotencyKey("  ") }
            shouldThrow<IllegalArgumentException> { IdempotencyKey("k".repeat(IdempotencyKey.MAX_LENGTH + 1)) }
            IdempotencyKey("k".repeat(IdempotencyKey.MAX_LENGTH)).value.length shouldBe IdempotencyKey.MAX_LENGTH
        }
    }

    context("ChargeRequest") {
        test("defaults to a merchant-initiated charge") {
            request(Money.of("9.99", Currency.USD)).initiator shouldBe ChargeInitiator.MERCHANT
        }

        test("amount must be positive") {
            shouldThrow<IllegalArgumentException> { request(Money.zero(Currency.USD)) }
                .message shouldContain "positive"
            shouldThrow<IllegalArgumentException> { request(Money.of("-1.00", Currency.USD)) }
        }

        test("settles in the presentment currency unless told otherwise") {
            val plain = request(Money.of("9.99", Currency.EUR))
            plain.presentmentCurrency shouldBe Currency.EUR
            plain.settlementCurrency shouldBe Currency.EUR
            plain.requiresFx shouldBe false

            val crossBorder = plain.copy(settlementCurrency = Currency.USD)
            crossBorder.presentmentCurrency shouldBe Currency.EUR
            crossBorder.requiresFx shouldBe true
        }

        test("customerId must not be blank") {
            shouldThrow<IllegalArgumentException> { request(Money.of("1.00", Currency.USD)).copy(customerId = "") }
        }
    }

    context("ChargeResult") {
        test("is sealed, so callers handle every outcome") {
            val results: List<ChargeResult> = listOf(
                ChargeResult.Approved(key, "ch_1", Money.of("9.99", Currency.USD), authorizationCode = "A1B2C3"),
                ChargeResult.Declined(key, Iso8583DeclineCodes.toDecline("51")),
                ChargeResult.Failed(key, FailureReason.TIMEOUT),
            )
            val summary = results.map { result ->
                when (result) {
                    is ChargeResult.Approved -> "approved ${result.amount}"
                    is ChargeResult.Declined -> "declined ${result.decline.reason}"
                    is ChargeResult.Failed -> "failed ${result.reason}"
                }
            }
            summary shouldBe listOf("approved USD 9.99", "declined InsufficientFunds", "failed TIMEOUT")
        }

        test("an approval without FX settles the authorized amount") {
            val approved = ChargeResult.Approved(key, "ch_1", Money.of("9.99", Currency.USD))
            approved.settlementConversion shouldBe null
            approved.settlementAmount shouldBe Money.of("9.99", Currency.USD)
        }

        test("an approval with FX settles the converted amount, which must come from the authorized amount") {
            val eurUsd = ExchangeRate(Currency.EUR, Currency.USD, BigDecimal("1.0842"), Instant.EPOCH, "mock")
            val presented = Money.of("9.99", Currency.EUR)
            val fx = eurUsd.convert(presented, RoundingMode.HALF_EVEN)

            ChargeResult.Approved(key, "ch_1", presented, settlementConversion = fx)
                .settlementAmount shouldBe Money.of("10.83", Currency.USD) // 10.831158

            shouldThrow<IllegalArgumentException> {
                ChargeResult.Approved(key, "ch_1", Money.of("5.00", Currency.EUR), settlementConversion = fx)
            }.message shouldContain "must equal the authorized amount"
        }

        test("timeouts and network errors leave the outcome unknown") {
            FailureReason.TIMEOUT.outcomeKnown shouldBe false
            FailureReason.NETWORK_ERROR.outcomeKnown shouldBe false
            FailureReason.RATE_LIMITED.outcomeKnown shouldBe true
            FailureReason.INVALID_REQUEST.isTransient shouldBe false
            FailureReason.PROCESSOR_UNAVAILABLE.isTransient shouldBe true
        }
    }
})
