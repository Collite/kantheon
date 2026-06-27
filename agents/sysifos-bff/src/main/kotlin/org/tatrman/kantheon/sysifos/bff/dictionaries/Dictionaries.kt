package org.tatrman.kantheon.sysifos.bff.dictionaries

import kotlinx.serialization.Serializable
import org.tatrman.kantheon.midas.v1.AssetKind
import org.tatrman.kantheon.midas.v1.TransactionKind
import java.util.concurrent.atomic.AtomicReference

/** A broker for the import UI (contracts §5). v1 ships a static registry stub. */
@Serializable
data class BrokerEntry(
    val brokerId: String,
    val displayName: String,
)

/** A code + cs/en labels — currencies, transaction-kinds, asset-kinds. */
@Serializable
data class LabelledEntry(
    val code: String,
    val cs: String,
    val en: String,
)

/**
 * The four dictionary surfaces (contracts §5), cached at the BFF (§3.7). The
 * tx-kind / asset-kind lists derive from `midas/v1` enums + a cs/en label map;
 * the `TX_CASH_*` legs appear read-only (cash legs are derived, not user-entered).
 * Brokers are a static stub in v1; currencies a static ISO-4217 subset.
 */
object Dictionaries {
    val brokers: List<BrokerEntry> =
        listOf(
            BrokerEntry("fio", "Fio banka"),
            BrokerEntry("degiro", "DEGIRO"),
            BrokerEntry("ibkr", "Interactive Brokers"),
        )

    // ISO-4217 subset covering the locales Sysifos serves + common majors.
    val currencies: List<LabelledEntry> =
        listOf(
            cur("CZK", "Česká koruna", "Czech koruna"),
            cur("EUR", "Euro", "Euro"),
            cur("USD", "Americký dolar", "US dollar"),
            cur("GBP", "Britská libra", "British pound"),
            cur("CHF", "Švýcarský frank", "Swiss franc"),
            cur("PLN", "Polský zlotý", "Polish złoty"),
            cur("JPY", "Japonský jen", "Japanese yen"),
            cur("CAD", "Kanadský dolar", "Canadian dollar"),
            cur("AUD", "Australský dolar", "Australian dollar"),
            cur("HUF", "Maďarský forint", "Hungarian forint"),
            cur("SEK", "Švédská koruna", "Swedish krona"),
            cur("NOK", "Norská koruna", "Norwegian krone"),
            cur("DKK", "Dánská koruna", "Danish krone"),
            cur("CNY", "Čínský jüan", "Chinese yuan"),
        )

    private val txKindLabels: Map<TransactionKind, Pair<String, String>> =
        mapOf(
            TransactionKind.TX_BUY to ("Nákup" to "Buy"),
            TransactionKind.TX_SELL to ("Prodej" to "Sell"),
            TransactionKind.TX_DIVIDEND to ("Dividenda" to "Dividend"),
            TransactionKind.TX_INTEREST to ("Úrok" to "Interest"),
            TransactionKind.TX_FEE to ("Poplatek" to "Fee"),
            TransactionKind.TX_TAX to ("Daň" to "Tax"),
            TransactionKind.TX_TRANSFER_IN to ("Převod dovnitř" to "Transfer in"),
            TransactionKind.TX_TRANSFER_OUT to ("Převod ven" to "Transfer out"),
            TransactionKind.TX_ADJUSTMENT to ("Korekce" to "Adjustment"),
            TransactionKind.TX_CASH_CREDIT to ("Hotovost — připsání" to "Cash credit"),
            TransactionKind.TX_CASH_DEBIT to ("Hotovost — odepsání" to "Cash debit"),
        )

    private val assetKindLabels: Map<AssetKind, Pair<String, String>> =
        mapOf(
            AssetKind.ASSET_STOCK to ("Akcie" to "Stock"),
            AssetKind.ASSET_ETF to ("ETF" to "ETF"),
            AssetKind.ASSET_BOND to ("Dluhopis" to "Bond"),
            AssetKind.ASSET_FUND to ("Fond" to "Fund"),
            AssetKind.ASSET_CASH to ("Hotovost" to "Cash"),
        )

    val transactionKinds: List<LabelledEntry> =
        TransactionKind
            .entries
            .filter { it != TransactionKind.UNRECOGNIZED }
            .map { k ->
                val (cs, en) = txKindLabels[k] ?: (k.name to k.name)
                LabelledEntry(k.name, cs, en)
            }

    val assetKinds: List<LabelledEntry> =
        AssetKind
            .entries
            .filter { it != AssetKind.UNRECOGNIZED }
            .map { k ->
                val (cs, en) = assetKindLabels[k] ?: (k.name to k.name)
                LabelledEntry(k.name, cs, en)
            }

    private fun cur(
        code: String,
        cs: String,
        en: String,
    ) = LabelledEntry(code, cs, en)
}

/**
 * A value cached for [ttlMs]. `get()` recomputes via [supplier] once the entry
 * is stale (TTL 10 min for dictionaries, contracts §3.7) — a coarse but
 * sufficient cache for static-ish reference data.
 */
class Cached<T>(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val supplier: () -> T,
) {
    private data class Box<T>(
        val value: T,
        val computedAt: Long,
    )

    private val ref = AtomicReference<Box<T>?>(null)

    fun get(): T {
        val cur = ref.get()
        if (cur != null && now() - cur.computedAt < ttlMs) return cur.value
        val fresh = Box(supplier(), now())
        ref.set(fresh)
        return fresh.value
    }

    /** Whether the current value is fresh (test/observability hook). */
    fun isFresh(): Boolean = ref.get()?.let { now() - it.computedAt < ttlMs } ?: false
}
