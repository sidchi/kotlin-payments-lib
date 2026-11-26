package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.idempotency.testing.idempotencyStoreContract
import io.kotest.core.spec.style.FunSpec

class InMemoryIdempotencyStoreSpec : FunSpec({
    include(idempotencyStoreContract { clock -> InMemoryIdempotencyStore(clock) })
})
