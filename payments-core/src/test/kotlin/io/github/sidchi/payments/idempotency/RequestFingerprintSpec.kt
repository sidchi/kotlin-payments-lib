package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.CardExpiry
import io.github.sidchi.payments.domain.ChargeInitiator
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.domain.Wallet
import io.github.sidchi.payments.domain.WalletProvider
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class RequestFingerprintSpec : FunSpec({
    val base = SampleCharges.request()
    val fingerprint = RequestFingerprint.of(base)

    test("is a stable 64-character hex SHA-256") {
        fingerprint.value shouldMatch Regex("[0-9a-f]{64}")
        RequestFingerprint.of(base.copy()) shouldBe fingerprint
    }

    test("ignores metadata insertion order") {
        val reordered = base.copy(metadata = base.metadata.entries.reversed().associate { it.toPair() })
        RequestFingerprint.of(reordered) shouldBe fingerprint
    }

    test("ignores display-only card data that Account Updater may refresh") {
        val refreshed = base.copy(paymentMethod = SampleCharges.card.copy(expiry = CardExpiry(1, 2031), last4 = "1881"))
        RequestFingerprint.of(refreshed) shouldBe fingerprint
    }

    context("changes when anything that decides the money movement changes") {
        mapOf(
            "amount" to base.copy(amount = Money.of("19.98", Currency.USD)),
            "currency" to base.copy(amount = Money.of("19.99", Currency.EUR), settlementCurrency = Currency.USD),
            "settlement currency" to base.copy(settlementCurrency = Currency.EUR),
            "card token" to base.copy(paymentMethod = SampleCharges.card.copy(token = "tok_synthetic_other")),
            "method kind" to base.copy(paymentMethod = Wallet(WalletProvider.APPLE_PAY, SampleCharges.card.token)),
            "customer" to base.copy(customerId = "cus_synthetic_2"),
            "initiator" to base.copy(initiator = ChargeInitiator.CUSTOMER),
            "description" to base.copy(description = "Renewal"),
            "metadata value" to base.copy(metadata = base.metadata + ("invoice" to "in_synthetic_2")),
        ).forEach { (field, changed) ->
            test(field) { RequestFingerprint.of(changed) shouldNotBe fingerprint }
        }
    }

    test("absent and empty descriptions differ") {
        RequestFingerprint.of(base.copy(description = "")) shouldNotBe RequestFingerprint.of(base.copy(description = null))
    }

    test("field boundaries can't be shifted to forge a collision") {
        val a = base.copy(metadata = mapOf("ab" to "c"))
        val b = base.copy(metadata = mapOf("a" to "bc"))
        RequestFingerprint.of(a) shouldNotBe RequestFingerprint.of(b)
    }

    test("rejects malformed digests") {
        shouldThrow<IllegalArgumentException> { RequestFingerprint("not-a-hash") }
        shouldThrow<IllegalArgumentException> { RequestFingerprint("A".repeat(64)) }
    }
})
