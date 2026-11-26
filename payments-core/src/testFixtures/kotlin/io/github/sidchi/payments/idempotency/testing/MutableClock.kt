package io.github.sidchi.payments.idempotency.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** A thread-safe [Clock] that tests move by hand. */
public class MutableClock(start: Instant = DEFAULT_START) : Clock() {
    private val current = AtomicReference(start)

    override fun instant(): Instant = current.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    /** Moves the clock forward by [duration]. */
    public fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }

    /** Defaults. */
    public companion object {
        /** Whole-second start instant, so values survive storage at microsecond precision unchanged. */
        public val DEFAULT_START: Instant = Instant.parse("2026-11-26T03:00:00Z")
    }
}
