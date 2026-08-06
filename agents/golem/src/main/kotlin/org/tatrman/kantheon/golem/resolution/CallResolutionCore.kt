package org.tatrman.kantheon.golem.resolution

import org.slf4j.LoggerFactory
import org.tatrman.nlp.v1.EngineVersion
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.CallResolutionCore")

// RV-P5.1 — the one node that replaces the six-node resolution chain (RV-11).
//
// What "the collapse" means at P5, per Bora's ruling (A) 2026-08-06: the six nodes it
// replaces — detectLangAndParse · extractUniversal · proposeDomainSpans ·
// filterRelevantSpans · fuzzyMatchSpans · jointInference — live in `agents/themis`, which
// runs unchanged until RV-P6 retires the service whole. So nothing is deleted here. This is
// the Golem's own path, dark until P5.3's fast path gives it an exit, and the Themis nodes
// are marked superseded rather than removed.
//
// The node spends NO LLM budget: the core's round-0 pass is deterministic, and the two rungs
// that do spend are P5.2's.

/**
 * The S-1 / S-4 provenance a lattice carries, lifted out of the proto so the conversation
 * can hold it as a value.
 *
 * ⚑ **kantheon has nowhere else to put this** (P5.1 T1 recon §5): `common/v1`'s
 * `ViewProvenance` and `BlockProvenance` carry neither the RV-39 layer tuple nor an
 * evidence class, so T2(d)'s "preserved onto whatever kantheon's provenance surface is"
 * had no surface to land on. It is preserved **in conversation state** here, which is all
 * P5.1 needs — this phase emits no envelope. The additive `common/v1` delta belongs to
 * **P5.3 T3**, which is where the answer envelope and its `provenance` per contracts §6
 * are actually built. Recorded rather than quietly skipped.
 *
 * Per-binding S-4 (algorithm, score, snapshot hash, proposing rung) is deliberately NOT
 * copied up: it rides inside each `Binding.producer` in the lattice, and duplicating it
 * would create two places for it to disagree.
 */
data class ResolutionProvenance(
    /** RV-39 layer tuple — "" when no compiled lexicon is loaded, which is itself the signal. */
    val lexiconArtifactHash: String,
    val memberIndexVersions: Map<String, String>,
    /** ABSENT until RV-P6 — the absence is the contract, so this stays nullable, not "". */
    val overlayVersion: String?,
    /** S-1 engine identity, one entry per NLP op that ran. Never empty from a live core. */
    val engineVersions: List<EngineVersion>,
) {
    companion object {
        fun from(lattice: ResolutionState): ResolutionProvenance =
            ResolutionProvenance(
                lexiconArtifactHash = lattice.lexiconVersions.lexiconArtifactHash,
                memberIndexVersions = lattice.lexiconVersions.memberIndexVersionsMap.toMap(),
                overlayVersion =
                    if (lattice.lexiconVersions.hasOverlayVersion()) lattice.lexiconVersions.overlayVersion else null,
                engineVersions = lattice.parse.usedList.toList(),
            )
    }
}

/**
 * The turn's degrade posture when the core door failed. A turn carrying this has no
 * lattice and must not pretend otherwise — it is the honest "I could not understand the
 * question because the thing that understands questions is down", distinct in kind from
 * `RefusalWithGaps`, which is "I understood you and cannot help".
 */
data class CoreDegrade(
    val code: String,
    val message: String,
)

/**
 * What `callResolutionCore` produces. Kept as its own type rather than four loose fields so
 * that the node's contract is one value: **either** a lattice with its provenance, **or** a
 * degrade. Never both, never neither.
 */
data class CoreCallResult(
    val lattice: ResolutionState? = null,
    val provenance: ResolutionProvenance? = null,
    val capabilities: Capabilities? = null,
    val degrade: CoreDegrade? = null,
)

/**
 * The pure body of the `callResolutionCore` node (repo idiom: a pure step function the Koog
 * node wraps, so the graph and a direct unit call exercise the same code — the Stage 2.1
 * spike pattern themis and golem both follow).
 *
 * One rpc. No LLM. A door failure becomes a [CoreDegrade], never an exception: an exception
 * out of a Koog node aborts the whole strategy.
 */
suspend fun callResolutionCoreStep(
    question: String,
    conversationId: String,
    locale: String,
    referenceDatetime: String,
    tenant: String,
    callerSubject: String,
    client: ResolutionCoreClient,
): CoreCallResult {
    val request =
        ResolveRequest
            .newBuilder()
            .setConversationId(conversationId)
            .setFresh(FreshQuestion.newBuilder().setText(question).setLocale(locale))
            .setContext(
                ResolveContext
                    .newBuilder()
                    .setReferenceDatetime(referenceDatetime)
                    .setTenant(tenant),
            ).setCallerSubject(callerSubject)
            .build()
    // No per-request `registry` override: the snapshot channel feeds it (RS-24, one channel).

    val response =
        try {
            client.resolve(request)
        } catch (e: ResolutionCoreException) {
            log.warn("resolve.bind failed ({}: {}) — the turn degrades", e.code, e.message)
            return CoreCallResult(degrade = CoreDegrade(e.code, e.message ?: "resolve failed"))
        }

    // A response with no lattice is a door that answered without doing the job. Treat it as a
    // degrade rather than an empty lattice: empty reads downstream as "understood you, found
    // nothing", which is a different and dishonest answer. This is reachable in practice —
    // `resolution_state` is field 7, additive, so a pre-RV-P2 server answers without it.
    if (!response.hasResolutionState()) {
        log.warn("resolve.bind returned no resolution_state — pre-RV core behind the door?")
        return CoreCallResult(
            degrade = CoreDegrade("NO_LATTICE", "the resolver answered without a resolution_state"),
        )
    }

    val lattice = response.resolutionState
    return CoreCallResult(
        lattice = lattice,
        provenance = ResolutionProvenance.from(lattice),
        capabilities = response.capabilities,
    )
}
