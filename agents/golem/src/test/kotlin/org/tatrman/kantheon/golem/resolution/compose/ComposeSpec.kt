package org.tatrman.kantheon.golem.resolution.compose

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.golem.resolution.ladder.span
import org.tatrman.kantheon.golem.resolution.operatorRefs
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.kantheon.golem.resolution.skills.SkillLayer
import org.tatrman.kantheon.golem.resolution.skills.body
import org.tatrman.kantheon.golem.resolution.skills.fixtureJson
import org.tatrman.kantheon.golem.resolution.skills.layer
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.Grounding
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P5.3 T2/T3 — `composeStructuredQuestion`, and the five composition-precedence cases
 * P4.3 T2 settled for golem-py, re-run against the Kotlin sibling over the same fixture.
 */

private fun bind(
    ref: String,
    targetClass: TargetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
): Binding =
    Binding
        .newBuilder()
        .setRef(ref)
        .setTargetClass(targetClass)
        .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT)
        .setInClassScore(1.0)
        .build()

private fun m(
    id: String,
    text: String,
    start: Int,
    roles: List<FrameRole> = emptyList(),
    refs: List<String> = emptyList(),
    ops: List<String> = emptyList(),
): Mention =
    Mention
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setLemma(text)
        .addAllFrameRoles(roles)
        .addAllBindings(refs.map { bind(it) })
        .addAllBindings(ops.map { bind(it, TargetClass.TARGET_CLASS_OPERATOR) })
        .build()

private fun grounded(
    id: String,
    text: String,
    start: Int,
    normalized: String,
    ref: String = "ground:chrono",
): ValueFinding =
    ValueFinding
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setKind(ValueKind.VALUE_KIND_GROUNDED)
        .setGrounding(
            Grounding
                .newBuilder()
                .setKernel("chrono")
                .setKind("DATE")
                .setNormalizedValue(normalized)
                .setRef(ref),
        ).build()

private fun literal(
    id: String,
    text: String,
    start: Int,
    attributeRef: String,
    memberRef: String,
    anchor: String = "",
): ValueFinding =
    ValueFinding
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setKind(ValueKind.VALUE_KIND_LITERAL)
        .setAnchorMentionId(anchor)
        .addAttributions(
            Attribution
                .newBuilder()
                .setAttributeRef(attributeRef)
                .setBinding(bind(memberRef, TargetClass.TARGET_CLASS_MEMBER)),
        ).build()

private fun state(
    mentions: List<Mention> = emptyList(),
    values: List<ValueFinding> = emptyList(),
): ResolutionState =
    ResolutionState
        .newBuilder()
        .addAllMentions(mentions)
        .addAllValues(values)
        .build()

private fun stdlib(): LayeredSkillLibrary = LayeredSkillLibrary(listOf(SkillLayer.fromJson(fixtureJson())))

/** *"Zobraz tržby podle prodejen"* — H1's shape, and the measure-as-subject case. */
private fun h1(): ResolutionState =
    state(
        mentions =
            listOf(
                m("m1", "Zobraz", 0, ops = listOf("op:show")),
                m(
                    "m2",
                    "tržby",
                    7,
                    // BOTH roles on one mention — 32 of 137 corpus mentions do this.
                    roles = listOf(FrameRole.FRAME_ROLE_SUBJECT, FrameRole.FRAME_ROLE_MEASURE),
                    refs = listOf("md.measure.revenue"),
                ),
                m(
                    "m3",
                    "prodejen",
                    19,
                    roles = listOf(FrameRole.FRAME_ROLE_GROUPING),
                    refs = listOf("md.dimension.Store"),
                ),
            ),
    )

class ComposeSpec :
    StringSpec({

        "measure-as-subject: one mention contributes to EVERY role it carries" {
            val q = composeStructuredQuestion(h1(), stdlib())

            // Neither slot loses. A partition would drop one, and WHICH one it dropped would
            // depend on emission order — the P0.2 finding, in one assertion.
            q.subjects shouldContainExactly listOf("md.measure.revenue")
            q.measures shouldContainExactly listOf("md.measure.revenue")
            q.groupings shouldContainExactly listOf("md.dimension.Store")
            q.isAnswerable shouldBe true
        }

        "members belong to filters, and the filter is (attribute, member)" {
            val s =
                state(
                    mentions =
                        listOf(
                            m(
                                "m1",
                                "náklady",
                                0,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.cost"),
                            ),
                            m(
                                "m2",
                                "účtu",
                                12,
                                roles = listOf(FrameRole.FRAME_ROLE_FILTER),
                                refs = listOf("er.entity.Account"),
                            ),
                        ),
                    values =
                        listOf(
                            literal("v1", "501001", 17, "er.entity.Account.code", "er.entity.Account.code#220", "m2"),
                        ),
                )
            val q = composeStructuredQuestion(s, LayeredSkillLibrary.EMPTY)

            q.filters shouldContainExactly
                listOf(
                    Filter(
                        ref = "er.entity.Account.code",
                        memberRef = "er.entity.Account.code#220",
                        literal = "501001",
                        anchorMentionId = "m2",
                    ),
                )
            // The FILTER-role mention is NOT emitted twice: a value pinned it, and the pinned
            // form is the more specific statement.
            q.filters.size shouldBe 1
        }

        "an unpinned FILTER-role mention is still a restriction on scope" {
            val s =
                state(
                    mentions =
                        listOf(
                            m(
                                "m1",
                                "náklady",
                                0,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.cost"),
                            ),
                            m(
                                "m2",
                                "střediska",
                                12,
                                roles = listOf(FrameRole.FRAME_ROLE_FILTER),
                                refs = listOf("er.entity.CostCentre"),
                            ),
                        ),
                )
            composeStructuredQuestion(s, LayeredSkillLibrary.EMPTY).filters shouldContainExactly
                listOf(Filter(ref = "er.entity.CostCentre", anchorMentionId = "m2"))
        }

        "a grounded DATE becomes the time grain" {
            val s = state(mentions = h1().mentionsList, values = listOf(grounded("v1", "za 12 měsíců", 30, "P12M")))
            val grain = composeStructuredQuestion(s, stdlib()).timeGrain
            grain.shouldNotBeNull()
            grain.normalizedValue shouldBe "P12M"
            grain.ref shouldBe "ground:chrono"
        }

        // ---------------------------------------------- the five precedence cases (P4.3 T2)

        "(a) retrieval directives MERGE — both operators are honoured" {
            val s =
                state(
                    mentions =
                        listOf(
                            m("m1", "Ukaž", 0, ops = listOf("op:show")),
                            m("m2", "vývoj", 5, ops = listOf("op:trend")),
                            m(
                                "m3",
                                "tržeb",
                                11,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.revenue"),
                            ),
                        ),
                    values = listOf(grounded("v1", "po měsících", 20, "P1M")),
                )
            val q = composeStructuredQuestion(s, stdlib())

            q.operators shouldContainExactly listOf("op:show", "op:trend")
            q.retrievalDirectives.size shouldBe 2
            q.retrievalDirectives[0] shouldContain "no reshaping"
            q.retrievalDirectives[1] shouldContain "finest time grain"
        }

        "(b) formatting ACCUMULATES in op order — the last entry is the one to prefer" {
            // §6 says "last-op-wins per directive key" and a prose body has no key to win on,
            // so nothing is dropped and the ORDER carries the precedence. H5's live case.
            val s =
                state(
                    mentions =
                        listOf(
                            m("m1", "Ukaž", 0, ops = listOf("op:show")),
                            m("m2", "vývoj", 5, ops = listOf("op:trend")),
                            m(
                                "m3",
                                "tržeb",
                                11,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.revenue"),
                            ),
                        ),
                    values = listOf(grounded("v1", "po měsících", 20, "P1M")),
                )
            val q = composeStructuredQuestion(s, stdlib())

            q.formattingDirectives.keys.toList() shouldContainExactly listOf("op:show", "op:trend")
            q.formattingDirectives.getValue("op:show") shouldContain "table by default"
            q.formattingDirectives.getValue("op:trend") shouldContain "line chart"
        }

        "(c) an op with NO BODY refuses — we do not know what the word meant" {
            val s = state(mentions = listOf(m("m1", "zkoumej", 0, ops = listOf("op:investigate")), h1().getMentions(1)))
            shouldThrow<ComposeRefused> { composeStructuredQuestion(s, stdlib()) }
                .message!! shouldContain "op:investigate"
        }

        "(d) an op that cannot APPLY is dropped with a note — we know exactly what it meant" {
            // `op:trend` with no time grain. Different from (c): the answer still happens, and
            // it says why the trend is missing.
            val s =
                state(
                    mentions =
                        listOf(
                            m("m1", "Ukaž", 0, ops = listOf("op:show")),
                            m("m2", "vývoj", 5, ops = listOf("op:trend")),
                            m(
                                "m3",
                                "tržeb",
                                11,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.revenue"),
                            ),
                        ),
                )
            val q = composeStructuredQuestion(s, stdlib())

            q.operators shouldContainExactly listOf("op:show")
            q.inapplicableOperators shouldContainExactly listOf("op:trend requires time-grain")
            // …and the dropped op's directives go with it.
            q.retrievalDirectives.size shouldBe 1
            q.formattingDirectives.keys shouldContainExactly listOf("op:show")
        }

        "(e) estate beats stdlib at compose, not just at load" {
            val estate = layer(body("op:show", "Retrieval: ESTATE — only the top 5.\n\nFormatting: cards."))
            val library =
                LayeredSkillLibrary(
                    listOf(estate, SkillLayer.fromJson(fixtureJson())),
                )
            val q = composeStructuredQuestion(h1(), library)

            q.retrievalDirectives shouldContainExactly listOf("ESTATE — only the top 5.")
            q.formattingDirectives.getValue("op:show") shouldBe "cards."
        }

        // ------------------------------------------------- the golem-py review fix, carried

        "⛑ an UNKNOWN requirement refuses rather than being waved through" {
            // golem-py's if/elif chain let two of six stdlib operators skip applicability
            // entirely, silently. "We cannot evaluate this condition" is the same class of
            // ignorance as an operator with no body, and takes the same direction.
            val weird = layer(body("op:weird", "Retrieval: x.\n\nApplicability: `moon-phase` — must be waxing."))
            val s = state(mentions = listOf(m("m1", "divně", 0, ops = listOf("op:weird")), h1().getMentions(1)))

            shouldThrow<ComposeRefused> { composeStructuredQuestion(s, LayeredSkillLibrary(listOf(weird))) }
                .message!! shouldContain "moon-phase"
        }

        "all four known requirements are evaluated, and each can fail" {
            REQUIREMENT_CHECKS.keys.sorted() shouldContainExactly
                listOf("order-measure", "parent-context", "time-grain", "two-series")

            val empty = StructuredQuestion()
            REQUIREMENT_CHECKS.forEach { (name, check) ->
                check(empty) shouldBe false // every one of them fails on nothing-resolved
                if (name == "order-measure") check(StructuredQuestion(measures = listOf("a"))) shouldBe true
                if (name == "two-series") check(StructuredQuestion(measures = listOf("a", "b"))) shouldBe true
                if (name == "parent-context") check(StructuredQuestion(groupings = listOf("g"))) shouldBe true
                if (name == "time-grain") check(StructuredQuestion(timeGrain = TimeGrain("r", "P1M"))) shouldBe true
            }
            // `order-measure` is the subtle one: BOTH 0 and ≥2 measures fail it.
            REQUIREMENT_CHECKS.getValue("order-measure")(StructuredQuestion(measures = listOf("a", "b"))) shouldBe false
        }

        // ------------------------------------------------------- the divergence from golem-py

        "⚑ an operator-less DATA_QUERY composes here, where golem-py would refuse" {
            // The two shells disagree on ONE predicate, by design: golem-py has no intent
            // classifier so its compose has to carry the "can this Golem do this" decision;
            // here the intent seam carries it, and *"Zobraz tržby podle prodejen"* with no
            // action word is an ordinary question rather than a refusal.
            val s =
                state(
                    mentions =
                        listOf(
                            m(
                                "m1",
                                "tržby",
                                0,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.revenue"),
                            ),
                            m(
                                "m2",
                                "prodejen",
                                12,
                                roles = listOf(FrameRole.FRAME_ROLE_GROUPING),
                                refs = listOf("md.dimension.Store"),
                            ),
                        ),
                )
            val q = composeStructuredQuestion(s, stdlib())

            q.operators shouldBe emptyList()
            q.measures shouldContainExactly listOf("md.measure.revenue")
            q.isAnswerable shouldBe true
        }

        "nothing to select is still a refusal, whatever else resolved" {
            val s = state(mentions = listOf(m("m1", "Zobraz", 0, ops = listOf("op:show"))))
            shouldThrow<ComposeRefused> { composeStructuredQuestion(s, stdlib()) }
                .message!! shouldContain "no measure and no subject"
        }

        "operators come back in span order — the order the user said them in" {
            val s =
                state(
                    mentions =
                        listOf(
                            m("m9", "porovnej", 40, ops = listOf("op:compare")),
                            m("m1", "Ukaž", 0, ops = listOf("op:show")),
                            m(
                                "m2",
                                "tržby",
                                6,
                                roles = listOf(FrameRole.FRAME_ROLE_MEASURE),
                                refs = listOf("md.measure.revenue"),
                            ),
                        ),
                )
            operatorRefs(s) shouldContainExactly listOf("op:show", "op:compare")
        }
    })
