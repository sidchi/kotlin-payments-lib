package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

// All rates in this file are synthetic sample values, not market data.
class ExchangeRateSpec : FunSpec({
    val asOf = Instant.parse("2026-11-24T00:00:00Z")
    fun rate(from: Currency, to: Currency, rate: String) = ExchangeRate(from, to, BigDecimal(rate), asOf, "mock")

    context("convert") {
        val usdJpy = rate(Currency.USD, Currency.JPY, "151.237")

        test("rounds to the target currency's minor unit with the given mode") {
            val ten = Money.of("10.00", Currency.USD) // 1512.37 JPY before rounding
            usdJpy.convert(ten, RoundingMode.HALF_EVEN).converted shouldBe Money.of("1512", Currency.JPY)
            usdJpy.convert(ten, RoundingMode.DOWN).converted shouldBe Money.of("1512", Currency.JPY)
            usdJpy.convert(ten, RoundingMode.UP).converted shouldBe Money.of("1513", Currency.JPY)
        }

        test("handles three- and four-decimal currencies") {
            val eurKwd = rate(Currency.EUR, Currency.KWD, "0.3331")
            eurKwd.convert(Money.of("19.99", Currency.EUR), RoundingMode.HALF_EVEN).converted shouldBe
                Money.of("6.659", Currency.KWD) // 6.658669
            val usdClf = rate(Currency.USD, Currency.CLF, "0.02612345")
            usdClf.convert(Money.of("100.00", Currency.USD), RoundingMode.HALF_EVEN).converted shouldBe
                Money.of("2.6123", Currency.CLF) // 2.612345
        }

        test("records the source amount, rate and rounding mode") {
            val conversion = usdJpy.convert(Money.of("1.00", Currency.USD), RoundingMode.HALF_UP)
            conversion.source shouldBe Money.of("1.00", Currency.USD)
            conversion.rate shouldBe usdJpy
            conversion.roundingMode shouldBe RoundingMode.HALF_UP
        }

        test("UNNECESSARY demands an exact result") {
            shouldThrow<ArithmeticException> {
                usdJpy.convert(Money.of("10.00", Currency.USD), RoundingMode.UNNECESSARY)
            }
            usdJpy.convert(Money.of("1000.00", Currency.USD), RoundingMode.UNNECESSARY).converted shouldBe
                Money.of("151237", Currency.JPY)
        }

        test("rejects money in the wrong currency") {
            shouldThrow<IllegalArgumentException> { usdJpy.convert(Money.of("1.00", Currency.EUR), RoundingMode.DOWN) }
                .message shouldContain "EUR"
        }
    }

    context("validation") {
        test("rate must be positive") {
            shouldThrow<IllegalArgumentException> { rate(Currency.USD, Currency.EUR, "0") }
            shouldThrow<IllegalArgumentException> { rate(Currency.USD, Currency.EUR, "-0.92") }
        }

        test("a currency converts to itself only at exactly 1") {
            shouldThrow<IllegalArgumentException> { rate(Currency.USD, Currency.USD, "1.01") }
            rate(Currency.USD, Currency.USD, "1.000").rate shouldBeEqualComparingTo BigDecimal.ONE
            ExchangeRate.identity(Currency.JPY, asOf).source shouldBe ExchangeRate.IDENTITY_SOURCE
        }
    }

    test("inverse swaps the pair and multiplies back to 1") {
        val eurUsd = rate(Currency.EUR, Currency.USD, "1.0842")
        val usdEur = eurUsd.inverse()
        usdEur.from shouldBe Currency.USD
        usdEur.to shouldBe Currency.EUR
        usdEur.asOf shouldBe asOf
        (eurUsd.rate * usdEur.rate - BigDecimal.ONE).abs() shouldBeLessThanOrEqualTo BigDecimal("1e-30")
    }

    context("properties") {
        // Rates from 0.000001 to 100 and amounts up to 10^12 minor units stay well inside a Long of minor units
        // even for JPY -> CLF (0 -> 4 decimals).
        val rates = Arb.long(1L..100_000_000L).map { BigDecimal.valueOf(it, 6) }
        val amounts = Arb.long(-1_000_000_000_000L..1_000_000_000_000L)
        val pairs = Arb.bind(Arb.element(Currency.all), Arb.element(Currency.all)) { a, b -> a to b }
            .filter { (a, b) -> a != b }

        test("floor <= half-even <= ceiling, and they differ by at most one minor unit") {
            checkAll(amounts, pairs, rates) { minor, (from, to), r ->
                val fx = ExchangeRate(from, to, r, asOf, "mock")
                val money = Money.ofMinor(minor, from)
                val floor = fx.convert(money, RoundingMode.FLOOR).converted
                val halfEven = fx.convert(money, RoundingMode.HALF_EVEN).converted
                val ceiling = fx.convert(money, RoundingMode.CEILING).converted
                floor shouldBeLessThanOrEqualTo halfEven
                halfEven shouldBeLessThanOrEqualTo ceiling
                (ceiling.amountMinor - floor.amountMinor) shouldBeLessThanOrEqualTo 1L
            }
        }

        test("half-even is within half a minor unit of the exact product") {
            checkAll(amounts, pairs, rates) { minor, (from, to), r ->
                val money = Money.ofMinor(minor, from)
                val converted = ExchangeRate(from, to, r, asOf, "mock").convert(money, RoundingMode.HALF_EVEN)
                val exact = money.toDecimal() * r
                val halfMinorUnit = BigDecimal.valueOf(5, to.minorUnits + 1)
                (converted.converted.toDecimal() - exact).abs() shouldBeLessThanOrEqualTo halfMinorUnit
            }
        }

        test("conversion is monotonic and symmetric around zero") {
            checkAll(amounts, amounts, pairs, rates) { a, b, (from, to), r ->
                val fx = ExchangeRate(from, to, r, asOf, "mock")
                val (lo, hi) = if (a <= b) a to b else b to a
                fun convert(minor: Long) = fx.convert(Money.ofMinor(minor, from), RoundingMode.HALF_EVEN).converted
                convert(lo) shouldBeLessThanOrEqualTo convert(hi)
                convert(-a) shouldBe -convert(a)
            }
        }

        test("identity conversion is exact under every rounding mode") {
            checkAll(amounts, Arb.element(Currency.all)) { minor, currency ->
                val money = Money.ofMinor(minor, currency)
                ExchangeRate.identity(currency, asOf).convert(money, RoundingMode.UNNECESSARY).converted shouldBe money
            }
        }
    }
})
