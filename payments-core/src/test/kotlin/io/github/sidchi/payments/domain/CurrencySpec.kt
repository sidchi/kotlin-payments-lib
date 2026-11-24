package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class CurrencySpec : FunSpec({
    test("minor-unit exponents follow ISO 4217") {
        Currency.USD.minorUnits shouldBe 2
        Currency.JPY.minorUnits shouldBe 0
        Currency.KWD.minorUnits shouldBe 3
        Currency.CLF.minorUnits shouldBe 4
    }

    test("every registry entry agrees with the JDK's ISO 4217 data") {
        Currency.all.forAll { currency ->
            val jdk = java.util.Currency.getInstance(currency.code)
            currency.minorUnits shouldBe jdk.defaultFractionDigits
            currency.numericCode shouldBe jdk.numericCode
        }
    }

    test("alphabetic and numeric codes are unique") {
        Currency.all.map { it.code }.shouldBeUnique()
        Currency.all.map { it.numericCode }.shouldBeUnique()
    }

    test("lookup by numeric code, as carried in ISO 8583 field 49") {
        Currency.ofNumeric(840) shouldBeSameInstanceAs Currency.USD
        Currency.ofNumeric("036") shouldBe Currency.of("AUD")
        Currency.of("AUD").numericCodeAsString shouldBe "036"
        shouldThrow<IllegalArgumentException> { Currency.ofNumeric(999) }
        shouldThrow<IllegalArgumentException> { Currency.ofNumeric("84O") }
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
