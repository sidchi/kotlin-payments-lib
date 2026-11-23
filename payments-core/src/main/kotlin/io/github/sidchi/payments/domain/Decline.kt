package io.github.sidchi.payments.domain

/**
 * What a billing system should do next after a decline.
 */
public enum class RecoveryAction {
    /** Retry the same credential later; the cause is usually temporary (funds, issuer outage, velocity limits). */
    RETRY_LATER,

    /** Bring the customer on-session to authenticate (3-D Secure / SCA), then retry as a customer-initiated charge. */
    AUTHENTICATE_CUSTOMER,

    /** Don't retry this credential; get a new one via Account Updater or by asking the customer to update it. */
    UPDATE_PAYMENT_METHOD,

    /** Stop. Retrying risks network fines or chargebacks (fraud, revoked authorization, unknown codes). */
    DO_NOT_RETRY,
}

/**
 * Normalized reason for a decline, split into a sealed **soft** vs. **hard** taxonomy.
 *
 * - [Soft] declines are temporary; the same credential may succeed later.
 * - [Hard] declines mean this credential will not succeed as-is. Retrying them wastes attempts, and card networks
 *   penalize merchants that repeatedly retry hard declines.
 *
 * Because both levels are sealed, `when (reason)` is exhaustive and the compiler flags every call site when a new
 * reason is added. Each reason also carries a default [recoveryAction] for dunning logic.
 */
public sealed interface DeclineReason {
    /** Default next step for this reason. */
    public val recoveryAction: RecoveryAction

    /** A temporary decline; retrying the same credential later can succeed. */
    public sealed interface Soft : DeclineReason

    /** A permanent decline for this credential. */
    public sealed interface Hard : DeclineReason

    /** Not enough available funds or credit. */
    public data object InsufficientFunds : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY_LATER
    }

    /** Generic issuer refusal with no stated reason ("do not honor"). Usually soft. */
    public data object DoNotHonor : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY_LATER
    }

    /** Amount or frequency limit exceeded on the card. */
    public data object ExceedsLimit : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY_LATER
    }

    /** Issuer or switch unreachable. */
    public data object IssuerUnavailable : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY_LATER
    }

    /** Transient processing error at the issuer or network. */
    public data object ProcessingError : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY_LATER
    }

    /** Issuer requires strong customer authentication (SCA soft decline). */
    public data object AuthenticationRequired : Soft {
        override val recoveryAction: RecoveryAction = RecoveryAction.AUTHENTICATE_CUSTOMER
    }

    /** Card is past its expiry date. Account Updater can often supply the new expiry. */
    public data object ExpiredCard : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Card number not recognized by the issuer. */
    public data object InvalidCardNumber : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Account closed by the cardholder or issuer. */
    public data object AccountClosed : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Card reported lost; a replacement card may exist. */
    public data object LostCard : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Card reported stolen; a replacement card may exist. */
    public data object StolenCard : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Issuer asked the merchant to retain the card ("pick up card"). */
    public data object PickUpCard : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.UPDATE_PAYMENT_METHOD
    }

    /** Issuer suspects fraud. Do not retry. */
    public data object SuspectedFraud : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.DO_NOT_RETRY
    }

    /** Card or cardholder may not make this type of transaction. */
    public data object TransactionNotPermitted : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.DO_NOT_RETRY
    }

    /**
     * Cardholder revoked the recurring authorization or placed a stop-payment order. Retrying invites chargebacks.
     */
    public data object RevokedAuthorization : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.DO_NOT_RETRY
    }

    /**
     * Code not recognized by the mapper. Classified as hard so unknown codes fail closed: the cost of a missed
     * recovery is lower than the cost of hammering a card the issuer has blocked.
     */
    public data object Unknown : Hard {
        override val recoveryAction: RecoveryAction = RecoveryAction.DO_NOT_RETRY
    }
}

/**
 * A classified decline: the normalized [reason] plus the processor's raw code and message for audit and debugging.
 */
public data class Decline(
    /** Normalized reason. */
    public val reason: DeclineReason,
    /** Raw response code from the processor or network, e.g. `"51"`. */
    public val rawCode: String? = null,
    /** Raw response message, for logs only. */
    public val message: String? = null,
) {
    /** True for temporary declines. */
    public val isSoft: Boolean get() = reason is DeclineReason.Soft

    /** True for permanent declines on this credential. */
    public val isHard: Boolean get() = reason is DeclineReason.Hard

    /** Default next step; shorthand for `reason.recoveryAction`. */
    public val recoveryAction: RecoveryAction get() = reason.recoveryAction
}
