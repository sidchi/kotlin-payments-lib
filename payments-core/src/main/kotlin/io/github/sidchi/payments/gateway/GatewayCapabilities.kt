package io.github.sidchi.payments.gateway

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.ChargeRequest
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.TokenizedCard
import io.github.sidchi.payments.domain.Wallet
import io.github.sidchi.payments.domain.WalletProvider

/**
 * What a processor connection can accept. Real connections differ here more than anywhere else: one acquirer settles
 * only USD and EUR, another takes JPY but not Amex, only some support wallets. A router uses [supports] to skip
 * connections that would reject a request outright, so a fallback is never wasted on a guaranteed `INVALID_REQUEST`.
 */
public data class GatewayCapabilities(
    /** Currencies the customer can be charged in. */
    public val presentmentCurrencies: Set<Currency>,
    /** Currencies the connection can pay the merchant out in. */
    public val settlementCurrencies: Set<Currency>,
    /** Card networks accepted for [TokenizedCard] payment methods. */
    public val cardBrands: Set<CardBrand>,
    /** Wallets accepted for [Wallet] payment methods. */
    public val wallets: Set<WalletProvider>,
) {
    init {
        require(presentmentCurrencies.isNotEmpty()) { "A gateway must accept at least one presentment currency" }
        require(settlementCurrencies.isNotEmpty()) { "A gateway must settle in at least one currency" }
    }

    /** True when the connection can attempt [request]. */
    public fun supports(request: ChargeRequest): Boolean = unsupportedReason(request) == null

    /** Why the connection cannot attempt [request], or `null` when it can. */
    public fun unsupportedReason(request: ChargeRequest): String? {
        val method = request.paymentMethod
        return when {
            request.presentmentCurrency !in presentmentCurrencies ->
                "presentment currency ${request.presentmentCurrency} is not supported"
            request.settlementCurrency !in settlementCurrencies ->
                "settlement currency ${request.settlementCurrency} is not supported"
            method is TokenizedCard && method.brand !in cardBrands -> "card brand ${method.brand} is not supported"
            method is Wallet && method.provider !in wallets -> "wallet ${method.provider} is not supported"
            else -> null
        }
    }

    /** Factories. */
    public companion object {
        /** Accepts every currency, card brand and wallet the library knows about. Handy for tests. */
        public fun all(): GatewayCapabilities = GatewayCapabilities(
            presentmentCurrencies = Currency.all.toSet(),
            settlementCurrencies = Currency.all.toSet(),
            cardBrands = CardBrand.entries.toSet(),
            wallets = WalletProvider.entries.toSet(),
        )
    }
}
