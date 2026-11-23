package io.github.sidchi.payments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** JUnit 5 test: proves the Jupiter engine runs alongside Kotest on the JUnit Platform. */
class PaymentsLibVersionTest {
    @Test
    fun `VERSION matches the version declared in gradle properties`() {
        val buildVersion = System.getProperty("payments.version")
        assertNotNull(buildVersion, "Gradle should pass payments.version to the test JVM")
        assertEquals(buildVersion, PaymentsLib.VERSION)
    }
}
