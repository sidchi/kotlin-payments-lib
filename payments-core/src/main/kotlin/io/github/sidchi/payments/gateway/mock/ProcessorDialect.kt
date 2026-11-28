package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.Decline
import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.Iso8583DeclineCodes
import kotlin.random.Random

/**
 * The wire-level personality of a mock processor: the raw decline codes it speaks and what its identifiers look
 * like. Mapping raw codes to [Decline]s is the adapter's job in a real integration, so each dialect owns that mapping
 * and the rest of the library only ever sees normalized [DeclineReason]s.
 */
public interface ProcessorDialect {
    /** Prefix of charge ids, e.g. `"mer_ch_"`. */
    public val chargeIdPrefix: String

    /** Raw decline codes and their relative weights when a config does not specify its own mix. */
    public val defaultDeclineMix: Map<String, Int>

    /** Normalizes a raw decline [code] (and optional processor [message]) into a [Decline] that keeps the raw code. */
    public fun toDecline(code: String, message: String? = null): Decline

    /** An issuer authorization code for an approval. */
    public fun authorizationCode(random: Random): String
}

/**
 * Meridian: a fictional acquirer that passes issuer responses through as **ISO 8583** two-character response codes
 * (`05`, `51`, `54`, ...) and returns six-character alphanumeric authorization codes, like most card-present-era
 * acquirers. Decline mapping delegates to [Iso8583DeclineCodes].
 */
public object MeridianDialect : ProcessorDialect {
    override val chargeIdPrefix: String = "mer_ch_"

    /** A plausible-looking mix for recurring card-not-present traffic: mostly soft declines. Synthetic, not data. */
    override val defaultDeclineMix: Map<String, Int> = mapOf(
        "05" to 35, // do not honor
        "51" to 30, // insufficient funds
        "54" to 10, // expired card
        "61" to 5, // exceeds withdrawal limit
        "91" to 5, // issuer unavailable
        "14" to 4, // invalid card number
        "59" to 4, // suspected fraud
        "41" to 3, // lost card
        "43" to 2, // stolen card
        "R1" to 2, // revocation of authorization
    )

    override fun toDecline(code: String, message: String?): Decline = Iso8583DeclineCodes.toDecline(code, message)

    override fun authorizationCode(random: Random): String = randomCode(random, AUTH_CODE_LENGTH, ALPHANUMERIC)

    private const val AUTH_CODE_LENGTH = 6
}

/**
 * Tessera: a fictional REST-style processor that reports declines as **snake_case strings** (`insufficient_funds`,
 * `card_declined`, ...) in the style of modern payment APIs, and returns numeric authorization codes. Codes outside
 * its table normalize to [DeclineReason.Unknown], which fails closed as a hard decline.
 */
public object TesseraDialect : ProcessorDialect {
    override val chargeIdPrefix: String = "tes_ch_"

    /** The code table, public so tests and docs can enumerate it. */
    public val codes: Map<String, DeclineReason> = mapOf(
        "card_declined" to DeclineReason.DoNotHonor,
        "do_not_honor" to DeclineReason.DoNotHonor,
        "generic_decline" to DeclineReason.DoNotHonor,
        "insufficient_funds" to DeclineReason.InsufficientFunds,
        "card_velocity_exceeded" to DeclineReason.ExceedsLimit,
        "withdrawal_count_limit_exceeded" to DeclineReason.ExceedsLimit,
        "issuer_not_available" to DeclineReason.IssuerUnavailable,
        "try_again_later" to DeclineReason.IssuerUnavailable,
        "processing_error" to DeclineReason.ProcessingError,
        "authentication_required" to DeclineReason.AuthenticationRequired,
        "expired_card" to DeclineReason.ExpiredCard,
        "incorrect_number" to DeclineReason.InvalidCardNumber,
        "invalid_account" to DeclineReason.AccountClosed,
        "lost_card" to DeclineReason.LostCard,
        "stolen_card" to DeclineReason.StolenCard,
        "pickup_card" to DeclineReason.PickUpCard,
        "fraudulent" to DeclineReason.SuspectedFraud,
        "transaction_not_allowed" to DeclineReason.TransactionNotPermitted,
        "revocation_of_authorization" to DeclineReason.RevokedAuthorization,
        "stop_payment_order" to DeclineReason.RevokedAuthorization,
    )

    /** A plausible-looking mix for recurring card-not-present traffic. Synthetic, not data. */
    override val defaultDeclineMix: Map<String, Int> = mapOf(
        "card_declined" to 30,
        "insufficient_funds" to 30,
        "expired_card" to 10,
        "authentication_required" to 6,
        "card_velocity_exceeded" to 5,
        "try_again_later" to 5,
        "incorrect_number" to 4,
        "fraudulent" to 4,
        "lost_card" to 3,
        "stolen_card" to 2,
        "revocation_of_authorization" to 1,
    )

    override fun toDecline(code: String, message: String?): Decline {
        val normalized = code.trim().lowercase()
        return Decline(reason = codes[normalized] ?: DeclineReason.Unknown, rawCode = normalized, message = message)
    }

    override fun authorizationCode(random: Random): String = randomCode(random, AUTH_CODE_LENGTH, DIGITS)

    private const val AUTH_CODE_LENGTH = 6
}

private const val ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
private const val DIGITS = "0123456789"

private fun randomCode(random: Random, length: Int, alphabet: String): String =
    buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
