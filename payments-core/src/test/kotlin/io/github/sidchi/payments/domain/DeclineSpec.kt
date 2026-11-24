package io.github.sidchi.payments.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DeclineSpec : FunSpec({
    context("ISO 8583 mapping") {
        val cases = listOf(
            Triple("05", DeclineReason.DoNotHonor, RecoveryAction.RETRY_LATER),
            Triple("51", DeclineReason.InsufficientFunds, RecoveryAction.RETRY_LATER),
            Triple("61", DeclineReason.ExceedsLimit, RecoveryAction.RETRY_LATER),
            Triple("91", DeclineReason.IssuerUnavailable, RecoveryAction.RETRY_LATER),
            Triple("1A", DeclineReason.AuthenticationRequired, RecoveryAction.AUTHENTICATE_CUSTOMER),
            Triple("54", DeclineReason.ExpiredCard, RecoveryAction.UPDATE_PAYMENT_METHOD),
            Triple("14", DeclineReason.InvalidCardNumber, RecoveryAction.UPDATE_PAYMENT_METHOD),
            Triple("43", DeclineReason.StolenCard, RecoveryAction.UPDATE_PAYMENT_METHOD),
            Triple("59", DeclineReason.SuspectedFraud, RecoveryAction.DO_NOT_RETRY),
            Triple("R0", DeclineReason.RevokedAuthorization, RecoveryAction.DO_NOT_RETRY),
        )
        cases.forEach { (code, reason, action) ->
            test("$code maps to $reason ($action)") {
                val decline = Iso8583DeclineCodes.toDecline(code)
                decline.reason shouldBe reason
                decline.recoveryAction shouldBe action
                decline.rawCode shouldBe code
            }
        }
    }

    test("soft and hard classification") {
        Iso8583DeclineCodes.toDecline("51").isSoft shouldBe true
        Iso8583DeclineCodes.toDecline("54").isHard shouldBe true
        DeclineReason.DoNotHonor.shouldBeInstanceOf<DeclineReason.Soft>()
        DeclineReason.LostCard.shouldBeInstanceOf<DeclineReason.Hard>()
    }

    test("only soft declines recommend retrying the same credential") {
        Iso8583DeclineCodes.knownCodes.map(Iso8583DeclineCodes::reasonFor).forEach { reason ->
            val retriesSameCard = reason.recoveryAction == RecoveryAction.RETRY_LATER
            retriesSameCard shouldBe (reason is DeclineReason.Soft && reason != DeclineReason.AuthenticationRequired)
        }
    }

    test("unmapped codes fail closed") {
        val decline = Iso8583DeclineCodes.toDecline("ZZ", message = "something new")
        decline.reason shouldBe DeclineReason.Unknown
        decline.isHard shouldBe true
        decline.recoveryAction shouldBe RecoveryAction.DO_NOT_RETRY
        decline.message shouldBe "something new"
    }

    test("codes are normalized") {
        Iso8583DeclineCodes.reasonFor(" r1 ") shouldBe DeclineReason.RevokedAuthorization
    }

    test("approval codes are not declines") {
        shouldThrow<IllegalArgumentException> { Iso8583DeclineCodes.reasonFor("00") }
    }

    test("the taxonomy is sealed, so a when over it is exhaustive without else") {
        fun bucket(reason: DeclineReason): String = when (reason) {
            is DeclineReason.Soft -> "soft"
            is DeclineReason.Hard -> "hard"
        }
        bucket(DeclineReason.ProcessingError) shouldBe "soft"
        bucket(DeclineReason.Unknown) shouldBe "hard"
    }
})
