package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.CardExpiry
import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.TokenizedCard
import io.github.sidchi.payments.gateway.PaymentGateway
import io.github.sidchi.payments.idempotency.IdempotencyLease
import io.github.sidchi.payments.idempotency.IdempotentChargeExecutor
import io.github.sidchi.payments.idempotency.IdempotentOutcome
import io.github.sidchi.payments.idempotency.InMemoryIdempotencyStore
import io.github.sidchi.payments.idempotency.Persistence
import io.github.sidchi.payments.idempotency.testing.MutableClock
import io.github.sidchi.payments.idempotency.testing.SampleCharges
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/**
 * End to end: the client-side [IdempotentChargeExecutor] and the processor-side idempotency of a [MockProcessor]
 * together turn "the response was lost but the card was charged" into exactly one charge.
 */
class GatewayIdempotencySpec : FunSpec({
    test("a lost response is resolved by lookup on takeover, then replayed from the store") {
        val clock = MutableClock()
        val lease = Duration.ofSeconds(30)
        val executor = IdempotentChargeExecutor(InMemoryIdempotencyStore(clock), lease)
        val gateway: PaymentGateway = MockProcessors.meridian(sleeper = Sleeper.NONE)
        val request = SampleCharges.request().copy(
            paymentMethod = TokenizedCard(
                MockTokens.TIMEOUT_AFTER_PROCESSING,
                CardBrand.VISA,
                "4242",
                CardExpiry(month = 12, year = 2028),
            ),
        )
        var processorCalls = 0
        val charge = { r: ChargeRequest, l: IdempotencyLease ->
            // On a takeover the first attempt may have reached the processor: ask before re-sending.
            val recorded = if (l.isTakeover) gateway.lookup(r.idempotencyKey) else null
            recorded ?: gateway.charge(r).also { processorCalls++ }
        }

        // 1. The processor charges the card, but the client times out: the key is held for recovery.
        val first = executor.execute(request, charge).shouldBeInstanceOf<IdempotentOutcome.Executed>()
        first.result.shouldBeInstanceOf<ChargeResult.Failed>()
        first.persistence shouldBe Persistence.HELD_FOR_RECOVERY

        // 2. After the lease expires, a retry takes over and finds the existing charge without re-sending.
        clock.advance(lease.plusSeconds(1))
        val second = executor.execute(request, charge).shouldBeInstanceOf<IdempotentOutcome.Executed>()
        second.attempt shouldBe 2
        second.persistence shouldBe Persistence.STORED
        val approved = second.result.shouldBeInstanceOf<ChargeResult.Approved>()

        // 3. Every later retry replays the stored approval.
        executor.execute(request, charge) shouldBe IdempotentOutcome.Replayed(approved)
        processorCalls shouldBe 1
    }
})
