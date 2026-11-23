package io.github.sidchi.payments

/**
 * Build metadata for kotlin-payments-lib.
 *
 * The domain model (money, payment methods, charges, declines) lives in [io.github.sidchi.payments.domain].
 */
public object PaymentsLib {
    /** Library version. A test keeps this in sync with `version` in `gradle.properties`. */
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    /** Maven artifact coordinates (`group:name:version`) for this module. */
    public val coordinates: String
        get() = "io.github.sidchi:payments-core:$VERSION"
}
