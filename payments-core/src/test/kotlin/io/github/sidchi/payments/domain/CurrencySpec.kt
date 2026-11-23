package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class CurrencySpec : FunSpec({
    test("minor-unit exponents follow ISO 4217") {
        Currency.USD.minorUnits shouldBe 2
        Currency.JPY.minorUnits shouldBe 0
        Currency.KWD.minorUnits shouldBe 3
    }

    test("lookup is case-insensitive and returns the interned instance") {
        Currency.of("usd") shouldBeSameInstanceAs Currency.USD
        Currency.of(" EUR ") shouldBeSameInstanceAs Currency.EUR
    }

    test("unknown codes are rejected") {
        shouldThrow<IllegalArgumentException> { Currency.of("XYZ") }
    }

    test("equality and toString use the ISO code") {
        Currency.of("GBP") shouldBe Currency.GBP
        Currency.GBP.toString() shouldBe "GBP"
    }
})
