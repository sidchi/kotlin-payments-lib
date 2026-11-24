package io.github.sidchi.payments.fx

import io.github.sidchi.payments.domain.Currency
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.RoundingMode

class RateSnapshotSpec : FunSpec({
    val snapshot = SampleRates.eurSnapshot()

    test("rates out of the base are returned exactly as published") {
        val eurUsd = snapshot.rate(Currency.EUR, Currency.USD)
        eurUsd.rate shouldBe BigDecimal("1.0842")
        eurUsd.asOf shouldBe SampleRates.ASOF
        eurUsd.source shouldBe "mock"
    }

    test("cross rates are derived through the base") {
        // 162.35 JPY per EUR / 1.0842 USD per EUR = 149.7417... JPY per USD
        snapshot.rate(Currency.USD, Currency.JPY).rate.setScale(4, RoundingMode.HALF_EVEN) shouldBe
            BigDecimal("149.7417")
    }

    test("the base is always present at exactly 1") {
        (Currency.EUR in snapshot) shouldBe true
        snapshot.ratesPerBase.getValue(Currency.EUR) shouldBeEqualComparingTo BigDecimal.ONE
        snapshot.rate(Currency.EUR, Currency.EUR).rate shouldBeEqualComparingTo BigDecimal.ONE
    }

    test("missing currencies raise ExchangeRateUnavailableException") {
        val e = shouldThrow<ExchangeRateUnavailableException> { snapshot.rate(Currency.USD, Currency.CLF) }
        e.from shouldBe Currency.USD
        e.to shouldBe Currency.CLF
    }

    test("rejects non-positive rates, a base rate other than 1 and a blank source") {
        shouldThrow<IllegalArgumentException> {
            RateSnapshot(Currency.EUR, mapOf(Currency.USD to BigDecimal.ZERO), SampleRates.ASOF, "mock")
        }
        shouldThrow<IllegalArgumentException> {
            RateSnapshot(Currency.EUR, mapOf(Currency.EUR to BigDecimal("1.01")), SampleRates.ASOF, "mock")
        }
        shouldThrow<IllegalArgumentException> { RateSnapshot(Currency.EUR, emptyMap(), SampleRates.ASOF, " ") }
    }

    test("property: A -> B -> A multiplies back to 1, so the snapshot has no built-in arbitrage") {
        val currencies = Arb.element(snapshot.currencies)
        checkAll(currencies, currencies) { a, b ->
            val roundTrip = snapshot.rate(a, b).rate * snapshot.rate(b, a).rate
            (roundTrip - BigDecimal.ONE).abs() shouldBeLessThanOrEqualTo BigDecimal("1e-30")
        }
    }
})
