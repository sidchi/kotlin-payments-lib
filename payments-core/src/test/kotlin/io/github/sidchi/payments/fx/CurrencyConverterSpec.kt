package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.domain.Wallet
import io.github.sidchi.payments.domain.WalletProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CurrencyConverterSpec : FunSpec({
    val maxAge = Duration.ofHours(26)
    fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)
    val sampleProvider = ExchangeRateProvider { SampleRates.eurSnapshot() }
    fun converterAt(instant: Instant, provider: ExchangeRateProvider = sampleProvider) =
        CurrencyConverter(provider, maxAge, clockAt(instant))

    test("converts using the provider's current snapshot") {
        val conversion = converterAt(SampleRates.ASOF.plus(Duration.ofHours(1)))
            .convert(Money.of("19.99", Currency.EUR), Currency.USD, RoundingMode.HALF_EVEN)
        conversion.converted shouldBe Money.of("21.67", Currency.USD) // 21.673158
        conversion.rate.asOf shouldBe SampleRates.ASOF
    }

    test("same-currency conversion never consults the provider") {
        val failing = ExchangeRateProvider { error("rate feed is down") }
        val money = Money.of("19.99", Currency.USD)
        converterAt(SampleRates.ASOF, failing).convert(money, Currency.USD, RoundingMode.UNNECESSARY).converted shouldBe
            money
    }

    test("rates exactly at the freshness limit are accepted; older ones are refused") {
        val atLimit = SampleRates.ASOF.plus(maxAge)
        converterAt(atLimit).rate(Currency.EUR, Currency.GBP).rate shouldBe BigDecimal("0.8567")

        val stale = shouldThrow<StaleExchangeRateException> {
            converterAt(atLimit.plusSeconds(1)).convert(Money.of("1.00", Currency.EUR), Currency.GBP, RoundingMode.DOWN)
        }
        stale.asOf shouldBe SampleRates.ASOF
        stale.age shouldBe maxAge.plusSeconds(1)
    }

    test("snapshots stamped slightly in the future (clock skew) are treated as fresh") {
        converterAt(SampleRates.ASOF.minusSeconds(5))
            .convert(Money.of("1.00", Currency.EUR), Currency.JPY, RoundingMode.HALF_EVEN)
            .converted shouldBe Money.of("162", Currency.JPY)
    }

    test("maxRateAge must be positive") {
        shouldThrow<IllegalArgumentException> {
            CurrencyConverter({ SampleRates.eurSnapshot() }, Duration.ZERO)
        }
    }

    test("end to end: present in GBP, settle in USD") {
        val request = ChargeRequest(
            idempotencyKey = IdempotencyKey("sub_synthetic_9:renewal:2026-12"),
            amount = Money.of("12.00", Currency.GBP),
            paymentMethod = Wallet(WalletProvider.APPLE_PAY, "tok_synthetic_applepay_1"),
            customerId = "cus_synthetic_9",
            settlementCurrency = Currency.USD,
        )
        request.requiresFx shouldBe true

        val converter = converterAt(SampleRates.ASOF)
        val fx = converter.convert(request.amount, request.settlementCurrency, RoundingMode.HALF_EVEN)
        val approved = ChargeResult.Approved(request.idempotencyKey, "ch_synthetic_1", request.amount, null, fx)

        // 12.00 GBP * (1.0842 / 0.8567) = 15.1868... USD
        approved.settlementAmount shouldBe Money.of("15.19", Currency.USD)
        approved.amount shouldBe Money.of("12.00", Currency.GBP)
    }
})
