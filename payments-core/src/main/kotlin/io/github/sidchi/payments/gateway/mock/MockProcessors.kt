package io.github.sidchi.payments.gateway.mock

import io.github.sidchi.payments.domain.CardBrand
import io.github.sidchi.payments.domain.Currency
import io.github.sidchi.payments.domain.WalletProvider
import io.github.sidchi.payments.fx.CurrencyConverter
import io.github.sidchi.payments.gateway.GatewayCapabilities
import io.github.sidchi.payments.gateway.ProcessorId

/**
 * Two ready-made, deliberately different mock processors, so retry and fallback logic is exercised against more than
 * one dialect and capability set. Both processors are fictional.
 *
 * | | Meridian | Tessera |
 * |---|---|---|
 * | Decline codes | ISO 8583 (`51`, `05`) | snake_case (`insufficient_funds`) |
 * | Cards | Visa, Mastercard, Amex, Discover | Visa, Mastercard |
 * | Wallets | — | Apple Pay, Google Pay |
 * | Presentment | USD, EUR, GBP | USD, EUR, GBP, JPY, INR, KWD |
 * | Settlement | USD, EUR, GBP | USD, EUR |
 *
 * A Tessera-only JPY charge, or an Amex card that only Meridian takes, are the cases a capability-aware router must
 * handle.
 */
public object MockProcessors {
    /** What [meridian] accepts by default. */
    public val MERIDIAN_CAPABILITIES: GatewayCapabilities = GatewayCapabilities(
        presentmentCurrencies = setOf(Currency.USD, Currency.EUR, Currency.GBP),
        settlementCurrencies = setOf(Currency.USD, Currency.EUR, Currency.GBP),
        cardBrands = setOf(CardBrand.VISA, CardBrand.MASTERCARD, CardBrand.AMEX, CardBrand.DISCOVER),
        wallets = emptySet(),
    )

    /** What [tessera] accepts by default. */
    public val TESSERA_CAPABILITIES: GatewayCapabilities = GatewayCapabilities(
        presentmentCurrencies = setOf(
            Currency.USD,
            Currency.EUR,
            Currency.GBP,
            Currency.JPY,
            Currency.INR,
            Currency.KWD,
        ),
        settlementCurrencies = setOf(Currency.USD, Currency.EUR),
        cardBrands = setOf(CardBrand.VISA, CardBrand.MASTERCARD),
        wallets = setOf(WalletProvider.APPLE_PAY, WalletProvider.GOOGLE_PAY),
    )

    /** A Meridian connection (ISO 8583 decline codes). */
    public fun meridian(
        config: MockProcessorConfig = MockProcessorConfig(capabilities = MERIDIAN_CAPABILITIES),
        processorId: ProcessorId = ProcessorId("meridian"),
        converter: CurrencyConverter? = null,
        sleeper: Sleeper = Sleeper.REAL,
        seed: Long = MockProcessor.DEFAULT_SEED,
    ): MockProcessor = MockProcessor(processorId, MeridianDialect, config, converter, sleeper, seed)

    /** A Tessera connection (snake_case decline codes). */
    public fun tessera(
        config: MockProcessorConfig = MockProcessorConfig(capabilities = TESSERA_CAPABILITIES),
        processorId: ProcessorId = ProcessorId("tessera"),
        converter: CurrencyConverter? = null,
        sleeper: Sleeper = Sleeper.REAL,
        seed: Long = MockProcessor.DEFAULT_SEED,
    ): MockProcessor = MockProcessor(processorId, TesseraDialect, config, converter, sleeper, seed)
}
