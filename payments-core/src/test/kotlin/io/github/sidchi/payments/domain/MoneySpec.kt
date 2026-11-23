package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlin.math.abs

class MoneySpec : FunSpec({
    val usd = Currency.USD

    context("construction") {
        test("of() converts major units using the currency's minor-unit exponent") {
            Money.of("12.34", usd).amountMinor shouldBe 1234
            Money.of("500", Currency.JPY).amountMinor shouldBe 500
            Money.of("1.25", Currency.KWD).amountMinor shouldBe 1250
        }

        test("of() accepts trailing zeros beyond the exponent") {
            Money.of("12.3400", usd) shouldBe Money.ofMinor(1234, usd)
        }

        test("of() refuses to round implicitly") {
            shouldThrow<IllegalArgumentException> { Money.of("12.345", usd) }
                .message shouldContain "more precision"
            shouldThrow<IllegalArgumentException> { Money.of("1.5", Currency.JPY) }
        }

        test("of() rejects amounts that overflow a Long of minor units") {
            shouldThrow<ArithmeticException> { Money.of(BigDecimal("1e30"), usd) }
        }

        test("toDecimal() round-trips with of()") {
            checkAll(Arb.long(-1_000_000_000_000L..1_000_000_000_000L), Arb.element(Currency.all)) { minor, ccy ->
                val money = Money.ofMinor(minor, ccy)
                Money.of(money.toDecimal(), ccy) shouldBe money
            }
        }
    }

    context("arithmetic") {
        test("plus, minus, times and negation") {
            val a = Money.of("10.00", usd)
            val b = Money.of("2.50", usd)
            (a + b) shouldBe Money.of("12.50", usd)
            (a - b) shouldBe Money.of("7.50", usd)
            (b * 3) shouldBe Money.of("7.50", usd)
            (-a) shouldBe Money.of("-10.00", usd)
        }

        test("mixing currencies is rejected") {
            val error = shouldThrow<IllegalArgumentException> {
                Money.of("1.00", usd) + Money.of("1.00", Currency.EUR)
            }
            error.message shouldContain "Currency mismatch"
            shouldThrow<IllegalArgumentException> { Money.of("1.00", usd) < Money.of("1.00", Currency.GBP) }
        }

        test("overflow throws instead of wrapping") {
            shouldThrow<ArithmeticException> { Money.ofMinor(Long.MAX_VALUE, usd) + Money.ofMinor(1, usd) }
            shouldThrow<ArithmeticException> { Money.ofMinor(Long.MAX_VALUE / 2 + 1, usd) * 2 }
            shouldThrow<ArithmeticException> { -Money.ofMinor(Long.MIN_VALUE, usd) }
        }

        test("ordering and sign predicates") {
            Money.of("1.00", usd) shouldBeLessThan Money.of("1.01", usd)
            Money.zero(usd).isZero shouldBe true
            Money.of("0.01", usd).isPositive shouldBe true
            Money.of("-0.01", usd).isNegative shouldBe true
        }
    }

    context("allocate") {
        test("splits evenly and gives leftover minor units to the first parts") {
            Money.of("100.00", usd).allocate(listOf(1, 1, 1)).map { it.amountMinor }
                .shouldContainExactly(3334, 3333, 3333)
        }

        test("respects ratios") {
            Money.of("0.05", usd).allocate(listOf(3, 7)).map { it.amountMinor }
                .shouldContainExactly(2, 3)
        }

        test("never gives leftovers to zero-weight parts") {
            Money.ofMinor(5, usd).allocate(listOf(0, 1, 1)).map { it.amountMinor }
                .shouldContainExactly(0, 3, 2)
        }

        test("negative amounts allocate symmetrically") {
            Money.ofMinor(-100_00, usd).allocate(listOf(1, 1, 1)).map { it.amountMinor }
                .shouldContainExactly(-3334, -3333, -3333)
        }

        test("rejects invalid ratios") {
            shouldThrow<IllegalArgumentException> { Money.ofMinor(100, usd).allocate(emptyList()) }
            shouldThrow<IllegalArgumentException> { Money.ofMinor(100, usd).allocate(listOf(0, 0)) }
            shouldThrow<IllegalArgumentException> { Money.ofMinor(100, usd).allocate(listOf(2, -1)) }
        }

        test("property: parts sum to the original and each is within one minor unit of its exact share") {
            val ratiosArb = Arb.list(Arb.int(0..1_000), 1..12).filter { r -> r.sum() > 0 }
            checkAll(Arb.long(-1_000_000_000L..1_000_000_000L), ratiosArb) { minor, ratios ->
                val parts = Money.ofMinor(minor, usd).allocate(ratios)

                parts.size shouldBe ratios.size
                parts.sumOf { it.amountMinor } shouldBe minor
                val total = ratios.sum().toDouble()
                parts.zip(ratios).forEach { (part, ratio) ->
                    val exact = minor * (ratio / total)
                    (abs(part.amountMinor - exact) < 1.0 + 1e-6) shouldBe true
                    if (ratio == 0) part.isZero shouldBe true
                }
            }
        }
    }

    context("formatting") {
        test("toString uses the ISO code and the currency's exact scale") {
            Money.ofMinor(1234, usd).toString() shouldBe "USD 12.34"
            Money.ofMinor(500, Currency.JPY).toString() shouldBe "JPY 500"
            Money.ofMinor(1250, Currency.KWD).toString() shouldBe "KWD 1.250"
            Money.ofMinor(-5, usd).toString() shouldBe "USD -0.05"
        }
    }
})
