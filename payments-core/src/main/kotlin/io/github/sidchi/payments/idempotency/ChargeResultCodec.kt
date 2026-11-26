package io.github.sidchi.payments.idempotency

import io.github.sidchi.payments.domain.ChargeResult
import io.github.sidchi.payments.domain.Conversion
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.Decline
import io.github.sidchi.payments.domain.DeclineReason
import io.github.sidchi.payments.domain.ExchangeRate
import io.github.sidchi.payments.domain.FailureReason
import io.github.sidchi.payments.domain.IdempotencyKey
import io.github.sidchi.payments.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Versioned, flat string-map encoding of [ChargeResult] for durable stores.
 *
 * A flat `Map<String, String>` maps directly onto a Postgres `jsonb` object (so operators can query
 * `result->>'type'`), a DynamoDB item or a Redis hash, without pulling a JSON library into the core module. Absent
 * optional fields are omitted rather than stored as nulls. Amounts are minor units, rates are `BigDecimal.toString()`
 * (exact, scale-preserving), instants are ISO-8601, and enums and decline reasons are stored by stable name.
 *
 * Changing the meaning of a field requires a new [VERSION]; [decode] rejects versions it does not know.
 */
public object ChargeResultCodec {
    /** Current encoding version, stored under the `v` field. */
    public const val VERSION: String = "1"

    /** Encodes [result]. */
    public fun encode(result: ChargeResult): Map<String, String> = buildMap {
        put(F_VERSION, VERSION)
        put(F_KEY, result.idempotencyKey.value)
        when (result) {
            is ChargeResult.Approved -> {
                put(F_TYPE, ResultType.APPROVED.name)
                put(F_CHARGE_ID, result.chargeId)
                putMoney("amount", result.amount)
                result.authorizationCode?.let { put(F_AUTH_CODE, it) }
                result.settlementConversion?.let { putConversion(it) }
            }
            is ChargeResult.Declined -> {
                put(F_TYPE, ResultType.DECLINED.name)
                put(F_DECLINE_REASON, reasonName(result.decline.reason))
                result.decline.rawCode?.let { put(F_DECLINE_CODE, it) }
                result.decline.message?.let { put(F_DECLINE_MESSAGE, it) }
                result.chargeId?.let { put(F_CHARGE_ID, it) }
            }
            is ChargeResult.Failed -> {
                put(F_TYPE, ResultType.FAILED.name)
                put(F_FAILURE_REASON, result.reason.name)
                result.message?.let { put(F_FAILURE_MESSAGE, it) }
            }
        }
    }

    /**
     * Decodes a map written by [encode].
     *
     * @throws IllegalArgumentException if the map is from an unknown version or is missing or has invalid fields.
     */
    public fun decode(fields: Map<String, String>): ChargeResult {
        val version = fields.required(F_VERSION)
        require(version == VERSION) { "Unsupported charge result encoding version '$version'" }
        val key = IdempotencyKey(fields.required(F_KEY))
        return when (enumOf<ResultType>(fields.required(F_TYPE))) {
            ResultType.APPROVED -> {
                val amount = fields.money("amount")
                ChargeResult.Approved(
                    idempotencyKey = key,
                    chargeId = fields.required(F_CHARGE_ID),
                    amount = amount,
                    authorizationCode = fields[F_AUTH_CODE],
                    settlementConversion = if (F_FX_RATE in fields) fields.conversion(amount) else null,
                )
            }
            ResultType.DECLINED -> ChargeResult.Declined(
                idempotencyKey = key,
                decline = Decline(
                    reason = reasonOf(fields.required(F_DECLINE_REASON)),
                    rawCode = fields[F_DECLINE_CODE],
                    message = fields[F_DECLINE_MESSAGE],
                ),
                chargeId = fields[F_CHARGE_ID],
            )
            ResultType.FAILED -> ChargeResult.Failed(
                idempotencyKey = key,
                reason = enumOf<FailureReason>(fields.required(F_FAILURE_REASON)),
                message = fields[F_FAILURE_MESSAGE],
            )
        }
    }

    /**
     * Stable storage name of a decline reason. The `when` is exhaustive over the sealed hierarchy, so adding a reason
     * without naming it here does not compile.
     */
    @Suppress("CyclomaticComplexMethod") // One flat branch per reason; splitting it would hide the exhaustiveness.
    internal fun reasonName(reason: DeclineReason): String = when (reason) {
        DeclineReason.InsufficientFunds -> "insufficient_funds"
        DeclineReason.DoNotHonor -> "do_not_honor"
        DeclineReason.ExceedsLimit -> "exceeds_limit"
        DeclineReason.IssuerUnavailable -> "issuer_unavailable"
        DeclineReason.ProcessingError -> "processing_error"
        DeclineReason.AuthenticationRequired -> "authentication_required"
        DeclineReason.ExpiredCard -> "expired_card"
        DeclineReason.InvalidCardNumber -> "invalid_card_number"
        DeclineReason.AccountClosed -> "account_closed"
        DeclineReason.LostCard -> "lost_card"
        DeclineReason.StolenCard -> "stolen_card"
        DeclineReason.PickUpCard -> "pick_up_card"
        DeclineReason.SuspectedFraud -> "suspected_fraud"
        DeclineReason.TransactionNotPermitted -> "transaction_not_permitted"
        DeclineReason.RevokedAuthorization -> "revoked_authorization"
        DeclineReason.Unknown -> "unknown"
    }

    private val reasonsByName: Map<String, DeclineReason> by lazy {
        listOf(
            DeclineReason.InsufficientFunds, DeclineReason.DoNotHonor, DeclineReason.ExceedsLimit,
            DeclineReason.IssuerUnavailable, DeclineReason.ProcessingError, DeclineReason.AuthenticationRequired,
            DeclineReason.ExpiredCard, DeclineReason.InvalidCardNumber, DeclineReason.AccountClosed,
            DeclineReason.LostCard, DeclineReason.StolenCard, DeclineReason.PickUpCard, DeclineReason.SuspectedFraud,
            DeclineReason.TransactionNotPermitted, DeclineReason.RevokedAuthorization, DeclineReason.Unknown,
        ).associateBy(::reasonName)
    }

    private fun reasonOf(name: String): DeclineReason =
        requireNotNull(reasonsByName[name]) { "Unknown decline reason '$name'" }

    private fun MutableMap<String, String>.putMoney(prefix: String, money: Money) {
        put("$prefix.minor", money.amountMinor.toString())
        put("$prefix.currency", money.currency.code)
    }

    private fun MutableMap<String, String>.putConversion(fx: Conversion) {
        putMoney("fx.converted", fx.converted)
        put(F_FX_RATE, fx.rate.rate.toString())
        put(F_FX_FROM, fx.rate.from.code)
        put(F_FX_TO, fx.rate.to.code)
        put(F_FX_AS_OF, fx.rate.asOf.toString())
        put(F_FX_SOURCE, fx.rate.source)
        put(F_FX_ROUNDING, fx.roundingMode.name)
    }

    private fun Map<String, String>.required(field: String): String =
        requireNotNull(this[field]) { "Missing field '$field'" }

    private fun Map<String, String>.money(prefix: String): Money {
        val minor = required("$prefix.minor")
        return Money.ofMinor(
            requireNotNull(minor.toLongOrNull()) { "Field '$prefix.minor' is not a whole number: '$minor'" },
            Currency.of(required("$prefix.currency")),
        )
    }

    /** The conversion's source is the approved amount itself, so it is not stored twice. */
    private fun Map<String, String>.conversion(source: Money): Conversion = Conversion(
        source = source,
        converted = money("fx.converted"),
        rate = ExchangeRate(
            from = Currency.of(required(F_FX_FROM)),
            to = Currency.of(required(F_FX_TO)),
            rate = BigDecimal(required(F_FX_RATE)),
            asOf = Instant.parse(required(F_FX_AS_OF)),
            source = required(F_FX_SOURCE),
        ),
        roundingMode = enumOf<RoundingMode>(required(F_FX_ROUNDING)),
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        requireNotNull(enumValues<E>().firstOrNull { it.name == name }) {
            "Unknown ${E::class.java.simpleName} '$name'"
        }

    private const val F_VERSION = "v"
    private const val F_KEY = "idempotencyKey"
    private const val F_TYPE = "type"
    private const val F_CHARGE_ID = "chargeId"
    private const val F_AUTH_CODE = "authorizationCode"
    private const val F_DECLINE_REASON = "decline.reason"
    private const val F_DECLINE_CODE = "decline.rawCode"
    private const val F_DECLINE_MESSAGE = "decline.message"
    private const val F_FAILURE_REASON = "failure.reason"
    private const val F_FAILURE_MESSAGE = "failure.message"
    private const val F_FX_RATE = "fx.rate"
    private const val F_FX_FROM = "fx.from"
    private const val F_FX_TO = "fx.to"
    private const val F_FX_AS_OF = "fx.asOf"
    private const val F_FX_SOURCE = "fx.source"
    private const val F_FX_ROUNDING = "fx.roundingMode"

    /** Stored under `type`; the names are part of the encoding. */
    private enum class ResultType { APPROVED, DECLINED, FAILED }
}
