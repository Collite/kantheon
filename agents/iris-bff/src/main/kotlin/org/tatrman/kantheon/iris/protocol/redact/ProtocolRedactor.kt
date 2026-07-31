package org.tatrman.kantheon.iris.protocol.redact

import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.protocol.v1.ProtocolDocument

/**
 * The redaction seam (contracts §8). A protocol carries prompts, SQL, RLS
 * predicates and raw service logs — the highest-value payload the estate can
 * assemble in one place — so redaction is a layer of its own rather than a
 * concern smeared across the builders.
 *
 * Implementations must be **pure and total**: same document in, same document
 * out, and never an exception. A redactor that threw would fail open in the worst
 * possible way — the caller's error path would be reached with the unredacted
 * document already built.
 */
fun interface ProtocolRedactor {
    fun redact(
        doc: ProtocolDocument,
        profile: ProtocolProfile,
    ): ProtocolDocument
}

/**
 * The redaction chain. **[FloorRedactor] always runs first and cannot be removed**
 * — that is enforced by construction, not by convention: there is no way to build
 * a chain without it, and [custom] impls are appended after the built-ins rather
 * than replacing them.
 *
 * Order matters and is deliberate:
 *  1. **Floor** — secrets, tokens, connection strings, cross-user rows. Always.
 *  2. **Config** — profile-driven digesting and literal masking.
 *  3. **Custom** — estate-specific rules, last, and unable to undo either.
 *
 * A custom redactor CAN only see already-floor-scrubbed content, so a buggy or
 * hostile one cannot reveal what the floor removed.
 */
class RedactionChain private constructor(
    private val stages: List<ProtocolRedactor>,
) : ProtocolRedactor {
    override fun redact(
        doc: ProtocolDocument,
        profile: ProtocolProfile,
    ): ProtocolDocument = stages.fold(doc) { acc, stage -> stage.redact(acc, profile) }

    companion object {
        /** The shipped chain: floor, then config, then anything the estate added. */
        fun standard(custom: List<ProtocolRedactor> = emptyList()): RedactionChain =
            RedactionChain(listOf(FloorRedactor, ConfigRedactor) + custom)
    }
}
