package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.Decline
import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.domain.Money
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.reflect.KClass

class ChargeResultCodecSpec : FunSpec({
    val key = IdempotencyKey("sub_synthetic_1:renewal:2026-12")

    fun roundTrip(result: ChargeResult): ChargeResult = ChargeResultCodec.decode(ChargeResultCodec.encode(result))

    test("round-trips every sample result exactly, including rate scale") {
        listOf(
            SampleCharges.approved(key),
            SampleCharges.declined(key),
            SampleCharges.hardDeclined(key),
            SampleCharges.failed(key, FailureReason.TIMEOUT),
        ).forEach { roundTrip(it) shouldBe it }
    }

    test("every decline reason in the sealed hierarchy has a stable name and round-trips") {
        val reasons = leafObjects(DeclineReason::class).filterIsInstance<DeclineReason>()
        reasons shouldHaveSize 16
        reasons.map(ChargeResultCodec::reasonName).toSet() shouldHaveSize reasons.size
        reasons.forEach { reason ->
            val declined = ChargeResult.Declined(key, Decline(reason))
            roundTrip(declined) shouldBe declined
        }
    }

    test("every failure reason round-trips") {
        FailureReason.entries.forEach { reason ->
            val failed = ChargeResult.Failed(key, reason)
            roundTrip(failed) shouldBe failed
        }
    }

    test("approvals round-trip for any amount, currency and optional fields") {
        checkAll(
            Arb.long(1L..Long.MAX_VALUE),
            Arb.element(Currency.all.toList()),
            Arb.string(maxSize = 20).orNull(),
        ) { minor, currency, authCode ->
            val approved = ChargeResult.Approved(key, "ch_synthetic", Money.ofMinor(minor, currency), authCode)
            roundTrip(approved) shouldBe approved
        }
    }

    test("the stored shape is flat, readable and stable") {
        ChargeResultCodec.encode(SampleCharges.declined(key)) shouldBe mapOf(
            "v" to "1",
            "idempotencyKey" to key.value,
            "type" to "DECLINED",
            "decline.reason" to "insufficient_funds",
            "decline.rawCode" to "51",
            "chargeId" to "ch_synthetic_2",
        )
    }

    context("rejects data it does not understand") {
        val valid = ChargeResultCodec.encode(SampleCharges.approved(key))
        mapOf(
            "unknown version" to (valid + ("v" to "2")),
            "unknown type" to (valid + ("type" to "REFUNDED")),
            "missing field" to (valid - "chargeId"),
            "non-numeric amount" to (valid + ("amount.minor" to "19.99")),
            "unknown decline reason" to ChargeResultCodec.encode(SampleCharges.declined(key))
                .plus("decline.reason" to "made_up"),
        ).forEach { (case, fields) ->
            test(case) {
                shouldThrow<IllegalArgumentException> { ChargeResultCodec.decode(fields) }
            }
        }

        test("error messages name the problem") {
            shouldThrow<IllegalArgumentException> { ChargeResultCodec.decode(valid - "chargeId") }
                .message shouldContain "chargeId"
        }
    }
})

/** All `object` leaves of a sealed hierarchy, found by reflection so a new reason can't be missed. */
private fun leafObjects(root: KClass<*>): List<Any> =
    root.sealedSubclasses.flatMap { sub ->
        if (sub.isSealed) leafObjects(sub) else listOfNotNull(sub.objectInstance)
    }.distinct()
