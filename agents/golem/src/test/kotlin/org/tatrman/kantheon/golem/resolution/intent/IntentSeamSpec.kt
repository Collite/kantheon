package org.tatrman.kantheon.golem.resolution.intent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.golem.resolution.ladder.lattice
import org.tatrman.kantheon.golem.resolution.ladder.mention
import org.tatrman.kantheon.golem.resolution.ladder.span
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.TargetClass

// RV-P5.3 T1(a) — operator annotations are evidence, and evidence AUGMENTS.
//
// The three cases that matter are the three priors: a DATA_QUERY prior (ops confirm), an
// investigation prior (ops are recorded and refused the power to overturn), and NO prior
// (ops decide). The middle one is the whole content of "augment, never replace" — a test
// suite that only proved the vacuum case would pass a classifier that overturns everything.

/** An `op:` binding. Kept local: [org.tatrman.kantheon.golem.resolution.ladder.binding]
 *  builds MODEL_OBJECT ones, and the target class is exactly what is under test here. */
private fun opMention(
    id: String,
    text: String,
    op: String,
    start: Int = 0,
): Mention =
    Mention
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setLemma(text)
        .addBindings(
            Binding
                .newBuilder()
                .setRef(op)
                .setTargetClass(TargetClass.TARGET_CLASS_OPERATOR)
                .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT)
                .setInClassScore(1.0),
        ).build()

private fun themis(kind: Themis.IntentKind): Themis.Resolution =
    Themis.Resolution
        .newBuilder()
        .setIntentKind(kind)
        // The FUNCTION-inference confidence (DecideHitlOrEmitNode:184) — deliberately set to
        // something low, to pin that this seam does NOT read it as an intent confidence.
        .setConfidence(0.31)
        .build()

class IntentSeamSpec :
    StringSpec({

        // ------------------------------------------------- (a) evidence IN, at the vacuum

        "no themis verdict + an operator annotation ⇒ DATA_QUERY, sourced to the evidence" {
            val intent =
                classifyTurnIntent(
                    prior = null,
                    lattice = lattice(mentions = listOf(opMention("m1", "Zobraz", "op:show"))),
                )

            intent.intentClass shouldBe IntentClass.DATA_QUERY
            intent.kind shouldBe Themis.IntentKind.PROCEDURAL
            intent.source shouldBe IntentSource.OPERATOR_EVIDENCE
            intent.operators shouldContainExactly listOf("op:show")
            intent.isDataQuery shouldBe true
        }

        "an UNSPECIFIED prior is the same vacuum as no prior at all" {
            // `GolemRequest.resolved_intent` defaults to an empty message rather than being
            // absent, so the unset case arrives as UNSPECIFIED and must not be read as a verdict.
            val intent =
                classifyTurnIntent(
                    prior = Themis.Resolution.getDefaultInstance(),
                    lattice = lattice(mentions = listOf(opMention("m1", "vývoj", "op:trend"))),
                )

            intent.intentClass shouldBe IntentClass.DATA_QUERY
            intent.source shouldBe IntentSource.OPERATOR_EVIDENCE
        }

        "no themis verdict and no operator evidence ⇒ UNKNOWN, and the fast path stays shut" {
            val intent = classifyTurnIntent(prior = null, lattice = lattice())

            intent.intentClass shouldBe IntentClass.UNKNOWN
            intent.source shouldBe IntentSource.NONE
            intent.isDataQuery shouldBe false
        }

        // ------------------------------------------- augment, NEVER replace (the fence)

        "an RCA prior survives operator evidence — augmenting is not overturning" {
            // "Proč klesl vývoj tržeb?" — `op:trend` is genuinely bound AND the question asks
            // why. Letting the operator win would route a causal question into a fast path
            // that cannot answer it; H4 says refuse honestly instead.
            val intent =
                classifyTurnIntent(
                    prior = themis(Themis.IntentKind.RCA),
                    lattice = lattice(mentions = listOf(opMention("m1", "vývoj", "op:trend"))),
                )

            intent.kind shouldBe Themis.IntentKind.RCA
            intent.intentClass shouldBe IntentClass.INVESTIGATION
            intent.source shouldBe IntentSource.THEMIS
            intent.isDataQuery shouldBe false
            // Recorded even though it did not win — the refusal renders what it saw.
            intent.operators shouldContainExactly listOf("op:trend")
            intent.rationale shouldContain "does not overturn"
        }

        "FORECAST and SIMULATION are investigations too, operators notwithstanding" {
            listOf(Themis.IntentKind.FORECAST, Themis.IntentKind.SIMULATION).forEach { kind ->
                val intent =
                    classifyTurnIntent(
                        prior = themis(kind),
                        lattice = lattice(mentions = listOf(opMention("m1", "porovnej", "op:compare"))),
                    )
                intent.intentClass shouldBe IntentClass.INVESTIGATION
                intent.kind shouldBe kind
            }
        }

        "a PROCEDURAL prior stays sourced to THEMIS — operators confirm, they do not re-decide" {
            val intent =
                classifyTurnIntent(
                    prior = themis(Themis.IntentKind.PROCEDURAL),
                    lattice = lattice(mentions = listOf(opMention("m1", "Zobraz", "op:show"))),
                )

            intent.intentClass shouldBe IntentClass.DATA_QUERY
            intent.source shouldBe IntentSource.THEMIS
            intent.rationale shouldContain "confirmed by"
        }

        // ------------------------------------------------------ what counts as an operator

        "only OPERATOR-class bindings count — a model object is not an action" {
            val intent =
                classifyTurnIntent(
                    prior = null,
                    lattice =
                        lattice(
                            mentions =
                                listOf(
                                    mention("m1", "tržby", refs = listOf("md.measure.revenue")),
                                    mention("m2", "prodejen", start = 12, refs = listOf("md.dimension.Store")),
                                ),
                        ),
                )

            // Three MODEL_OBJECT bindings and not one action ⇒ still a vacuum.
            intent.operators shouldBe emptyList()
            intent.intentClass shouldBe IntentClass.UNKNOWN
        }

        "operators come back in span order, deduplicated" {
            val intent =
                classifyTurnIntent(
                    prior = null,
                    lattice =
                        lattice(
                            mentions =
                                listOf(
                                    opMention("m3", "porovnej", "op:compare", start = 40),
                                    opMention("m1", "Ukaž", "op:show", start = 0),
                                    opMention("m2", "vývoj", "op:trend", start = 10),
                                    opMention("m4", "ukaž", "op:show", start = 60),
                                ),
                        ),
                )

            // H5's shape. Span order is the order the user said them in, which is the order
            // `compose` applies them in — so a wrong order here is a wrong ANSWER later.
            intent.operators shouldContainExactly listOf("op:show", "op:trend", "op:compare")
        }

        "a null lattice is a vacuum, not a crash — the core is off by default (P5.1)" {
            val intent = classifyTurnIntent(prior = themis(Themis.IntentKind.PROCEDURAL), lattice = null)

            intent.intentClass shouldBe IntentClass.DATA_QUERY
            intent.operators shouldBe emptyList()
            operatorRefs(null) shouldBe emptyList()
        }
    })
