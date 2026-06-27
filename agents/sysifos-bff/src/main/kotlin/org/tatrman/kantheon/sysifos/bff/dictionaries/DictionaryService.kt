package org.tatrman.kantheon.sysifos.bff.dictionaries

/**
 * The four dictionary surfaces (contracts §3.7/§5), each behind a [Cached] with
 * the contract's 10-minute TTL. Reference data is static-ish in v1, so the cache
 * is coarse; the indirection keeps the route handlers trivial and lets later
 * stages swap the broker source (loader registry) without touching them.
 */
class DictionaryService(
    ttlMs: Long = DEFAULT_TTL_MS,
    now: () -> Long = System::currentTimeMillis,
) {
    val brokers = Cached(ttlMs, now) { Dictionaries.brokers }
    val currencies = Cached(ttlMs, now) { Dictionaries.currencies }
    val transactionKinds = Cached(ttlMs, now) { Dictionaries.transactionKinds }
    val assetKinds = Cached(ttlMs, now) { Dictionaries.assetKinds }

    companion object {
        const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000
    }
}
