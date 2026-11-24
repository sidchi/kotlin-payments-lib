package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

class PaymentMethodSpec : FunSpec({
    // Synthetic tokens only; nothing in this repo is a real card.
    val card = TokenizedCard(
        token = "tok_synthetic_visa_4242",
        brand = CardBrand.VISA,
        last4 = "4242",
        expiry = CardExpiry(12, 2027),
    )

    context("CardExpiry") {
        test("a card is valid through the last day of its expiry month") {
            card.expiry.isExpiredOn(LocalDate.of(2027, 12, 31)) shouldBe false
            card.expiry.isExpiredOn(LocalDate.of(2028, 1, 1)) shouldBe true
            card.expiry.isExpiredOn(LocalDate.of(2026, 6, 15)) shouldBe false
        }

        test("parses and prints MM/YY") {
            CardExpiry.parse("03/28") shouldBe CardExpiry(3, 2028)
            CardExpiry(3, 2028).toString() shouldBe "03/28"
        }

        test("rejects invalid months and malformed text") {
            shouldThrow<IllegalArgumentException> { CardExpiry(13, 2027) }
            shouldThrow<IllegalArgumentException> { CardExpiry(0, 2027) }
            shouldThrow<IllegalArgumentException> { CardExpiry.parse("3/28") }
            shouldThrow<IllegalArgumentException> { CardExpiry.parse("13/28") }
        }
    }

    context("tokens") {
        test("raw card numbers are rejected as tokens") {
            shouldThrow<IllegalArgumentException> { card.copy(token = "4111111111111111") }
                .message shouldContain "raw card number"
            shouldThrow<IllegalArgumentException> { card.copy(token = "4111 1111 1111 1111") }
            shouldThrow<IllegalArgumentException> {
                Wallet(provider = WalletProvider.APPLE_PAY, token = "5555-5555-5555-4444")
            }
        }

        test("blank tokens are rejected") {
            shouldThrow<IllegalArgumentException> { card.copy(token = " ") }
        }

        test("last4 must be four digits") {
            shouldThrow<IllegalArgumentException> { card.copy(last4 = "42") }
            shouldThrow<IllegalArgumentException> { card.copy(last4 = "abcd") }
        }

        test("toString redacts the token") {
            card.toString() shouldNotContain "tok_synthetic_visa_4242"
            card.toString() shouldContain "****4242"
            val wallet = Wallet(WalletProvider.GOOGLE_PAY, "gpay_synthetic_token_9876", CardBrand.MASTERCARD, "9876")
            wallet.toString() shouldNotContain "gpay_synthetic_token"
        }
    }

    test("PaymentMethod is sealed, so a when over it is exhaustive") {
        val methods: List<PaymentMethod> = listOf(card, Wallet(WalletProvider.PAYPAL, "ba_synthetic_1"))
        val labels = methods.map { method ->
            when (method) {
                is TokenizedCard -> "${method.brand} ${method.last4}"
                is Wallet -> method.provider.name
            }
        }
        labels shouldBe listOf("VISA 4242", "PAYPAL")
    }
})
