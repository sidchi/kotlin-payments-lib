package io.github.sidchi.payments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch

/** Kotest spec: proves the Kotest engine is wired up. */
class PaymentsLibSpec : FunSpec({
    test("VERSION is semantic-version shaped") {
        PaymentsLib.VERSION shouldMatch Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""")
    }

    test("coordinates are group:artifact:version") {
        val parts = PaymentsLib.coordinates.split(":")
        parts.size shouldBe 3
        parts[0] shouldBe "io.github.sidchi"
        parts[1] shouldBe "payments-core"
        PaymentsLib.coordinates shouldEndWith PaymentsLib.VERSION
    }
})
