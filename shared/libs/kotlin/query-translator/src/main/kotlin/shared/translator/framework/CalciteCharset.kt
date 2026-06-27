package shared.translator.framework

/**
 * One-time promotion of Calcite's default string-literal charset to UTF-8.
 *
 * Calcite resolves the charset used for `CHAR`/`VARCHAR` literals from
 * `CalciteSystemProperty.DEFAULT_CHARSET`, which defaults to **ISO-8859-1** (Latin-1) and is
 * frozen the first time `org.apache.calcite.util.Util` is class-loaded
 * (`Util.DEFAULT_CHARSET = Charset.forName(CalciteSystemProperty.DEFAULT_CHARSET.value())`).
 * Latin-1 cannot encode the Czech letters outside it — `š č ř ž ů ě ť ď ň` — so a literal like
 * `'Poštovné'` fails SQL→Rel conversion with *"Failed to encode 'Poštovné' in character set
 * 'ISO-8859-1'"*, which reaches callers as an opaque parse error. (Latin-1-representable accents
 * like `é á í` convert fine, which is what made the bug look intermittent.)
 *
 * The fix is to set `calcite.default.charset` (and the matching national charset + collation) to
 * UTF-8 **before any Calcite class loads**. Because the value is frozen at `Util`'s static init,
 * a late `System.setProperty` is a no-op — so [ensureUtf8] must be invoked at the top of every
 * public Calcite entry point in this library (parse / unparse / schema-detect). The first call
 * wins; the rest are cheap no-ops. An explicit `-Dcalcite.default.charset=…` JVM flag still takes
 * precedence (we only set the keys when absent), so operators can override per deployment.
 *
 * Production also sets the JVM flag in the deployment manifest as belt-and-suspenders, but this
 * in-code hook keeps the library correct for consumers that don't (e.g. a local
 * `just deploy-kt translator` build, or other services embedding the lib).
 */
object CalciteCharset {
    @Volatile
    private var applied = false

    /** Idempotently promote Calcite's default charset/collation to UTF-8 if not already set. */
    fun ensureUtf8() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            setIfAbsent("calcite.default.charset", "UTF-8")
            setIfAbsent("calcite.default.nationalcharset", "UTF-8")
            setIfAbsent("calcite.default.collation.name", "UTF-8\$en_US")
            applied = true
        }
    }

    private fun setIfAbsent(
        key: String,
        value: String,
    ) {
        if (System.getProperty(key) == null) System.setProperty(key, value)
    }
}
