package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.RecoveryAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import kotlin.random.Random

class ProcessorDialectSpec : FunSpec({
    context("Tessera snake_case codes") {
        listOf(
            Triple("insufficient_funds", DeclineReason.InsufficientFunds, RecoveryAction.RETRY_LATER),
            Triple("card_declined", DeclineReason.DoNotHonor, RecoveryAction.RETRY_LATER),
            Triple("try_again_later", DeclineReason.IssuerUnavailable, RecoveryAction.RETRY_LATER),
            Triple(
                "authentication_required",
                DeclineReason.AuthenticationRequired,
                RecoveryAction.AUTHENTICATE_CUSTOMER,
            ),
            Triple("expired_card", DeclineReason.ExpiredCard, RecoveryAction.UPDATE_PAYMENT_METHOD),
            Triple("stolen_card", DeclineReason.StolenCard, RecoveryAction.UPDATE_PAYMENT_METHOD),
            Triple("fraudulent", DeclineReason.SuspectedFraud, RecoveryAction.DO_NOT_RETRY),
            Triple("stop_payment_order", DeclineReason.RevokedAuthorization, RecoveryAction.DO_NOT_RETRY),
        ).forEach { (code, reason, action) ->
            test("$code -> $reason ($action)") {
                val decline = TesseraDialect.toDecline(code, "issuer said no")
                decline.reason shouldBe reason
                decline.recoveryAction shouldBe action
                decline.rawCode shouldBe code
                decline.message shouldBe "issuer said no"
            }
        }

        test("codes are normalized for case and whitespace") {
            TesseraDialect.toDecline("  Insufficient_Funds ").let {
                it.reason shouldBe DeclineReason.InsufficientFunds
                it.rawCode shouldBe "insufficient_funds"
            }
        }

        test("an unknown code fails closed as a hard decline") {
            val decline = TesseraDialect.toDecline("brand_new_code")
            decline.reason shouldBe DeclineReason.Unknown
            decline.isHard shouldBe true
            decline.rawCode shouldBe "brand_new_code"
        }
    }

    context("Meridian ISO 8583 codes") {
        test("delegates to the ISO 8583 table and keeps the raw code") {
            val decline = MeridianDialect.toDecline("51")
            decline.reason shouldBe DeclineReason.InsufficientFunds
            decline.rawCode shouldBe "51"
        }
    }

    context("every dialect") {
        listOf(MeridianDialect, TesseraDialect).forEach { dialect ->
            val name = dialect::class.simpleName

            test("$name: every code in the default mix maps to a known reason") {
                val reasons = dialect.defaultDeclineMix.keys.map { dialect.toDecline(it).reason }
                reasons shouldNotContain DeclineReason.Unknown
            }

            test("$name: the default mix is dominated by soft declines, as recurring traffic is") {
                val total = dialect.defaultDeclineMix.values.sum()
                val soft = dialect.defaultDeclineMix.filterKeys { dialect.toDecline(it).isSoft }.values.sum()
                (soft * 2 > total) shouldBe true
            }

            test("$name: authorization codes are six characters and seed-deterministic") {
                val a = dialect.authorizationCode(Random(3))
                a shouldMatch Regex("[A-Z0-9]{6}")
                dialect.authorizationCode(Random(3)) shouldBe a
                dialect.chargeIdPrefix shouldEndWith "_ch_"
            }
        }
    }
})
