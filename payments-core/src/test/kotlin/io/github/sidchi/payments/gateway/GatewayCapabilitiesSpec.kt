package io.github.sidchi.payments.gateway

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.domain.Wallet
import io.github.sidchi.payments.domain.WalletProvider
import io.github.sidchi.payments.gateway.mock.MockProcessors
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GatewayCapabilitiesSpec : FunSpec({
    val meridian = MockProcessors.MERIDIAN_CAPABILITIES
    val tessera = MockProcessors.TESSERA_CAPABILITIES
    val usdVisa = SampleCharges.request()

    test("a USD Visa renewal is supported by both mock processors") {
        meridian.supports(usdVisa) shouldBe true
        tessera.supports(usdVisa) shouldBe true
        meridian.unsupportedReason(usdVisa) shouldBe null
    }

    test("presentment currency is checked") {
        val jpy = usdVisa.copy(amount = Money.of("2500", Currency.JPY), settlementCurrency = Currency.USD)
        meridian.unsupportedReason(jpy)!! shouldContain "presentment currency JPY"
        tessera.supports(jpy) shouldBe true
    }

    test("settlement currency is checked separately from presentment") {
        val settleGbp = usdVisa.copy(settlementCurrency = Currency.GBP)
        meridian.supports(settleGbp) shouldBe true
        tessera.unsupportedReason(settleGbp)!! shouldContain "settlement currency GBP"
    }

    test("card brand is checked for tokenized cards") {
        val amex = usdVisa.copy(paymentMethod = SampleCharges.card.copy(brand = CardBrand.AMEX))
        meridian.supports(amex) shouldBe true
        tessera.unsupportedReason(amex)!! shouldContain "card brand AMEX"
    }

    test("wallet provider is checked for wallets") {
        val applePay = usdVisa.copy(paymentMethod = Wallet(WalletProvider.APPLE_PAY, "tok_synthetic_applepay_1"))
        meridian.unsupportedReason(applePay)!! shouldContain "wallet APPLE_PAY"
        tessera.supports(applePay) shouldBe true
    }

    test("all() accepts everything the library models") {
        val all = GatewayCapabilities.all()
        all.cardBrands shouldBe CardBrand.entries.toSet()
        all.wallets shouldBe WalletProvider.entries.toSet()
        all.presentmentCurrencies shouldBe Currency.all.toSet()
    }

    test("a gateway must accept and settle at least one currency") {
        shouldThrow<IllegalArgumentException> { meridian.copy(presentmentCurrencies = emptySet()) }
        shouldThrow<IllegalArgumentException> { meridian.copy(settlementCurrencies = emptySet()) }
    }

    test("processor ids are lower-case slugs") {
        ProcessorId("meridian-us-1").toString() shouldBe "meridian-us-1"
        listOf("", "Meridian", "-leading-dash", "has space", "x".repeat(65)).forEach {
            shouldThrow<IllegalArgumentException> { ProcessorId(it) }
        }
    }
})
