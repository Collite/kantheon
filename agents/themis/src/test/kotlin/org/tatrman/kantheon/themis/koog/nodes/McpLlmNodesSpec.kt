package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.3 T4 spec — focused coverage of the pure helpers under
 * filterRelevantSpans / fuzzyMatchSpans. The full Resolver integration suite
 * covers end-to-end behaviour; this spec gives fast, descriptive failure
 * messages for the algebraic pieces.
 */
class McpLlmNodesSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // buildFilterPrompt — eval-gate parity (prompt text MUST NOT drift)
        // -------------------------------------------------------------------------

        "buildFilterPrompt — includes candidate span text, pos, dep on each line" {
            val spans =
                listOf(
                    domainSpan(coveredText = "invoices", pos = "NOUN", depRelation = "obj"),
                    domainSpan(coveredText = "ACME", pos = "PROPN", depRelation = "nmod"),
                )
            val out = buildFilterPrompt(spans, entityTypes = emptyList(), locale = "cs")
            out shouldContain "[0] 'invoices' (pos=NOUN, dep=obj)"
            out shouldContain "[1] 'ACME' (pos=PROPN, dep=nmod)"
        }

        "buildFilterPrompt — embeds entityTypeRef + description + fuzzyMatcher per type" {
            val out =
                buildFilterPrompt(
                    spans = emptyList(),
                    entityTypes =
                        listOf(
                            entityType("customerId", "Customer identifier", "customers"),
                        ),
                    locale = "cs",
                )
            out shouldContain "- customerId: Customer identifier (fuzzyMatcher=customers)"
        }

        "buildFilterPrompt — preserves the byte-for-byte template (header phrases)" {
            // If this test fails, the prompt has drifted from ai-platform Resolver. The
            // Stage 2.4 eval gate will catch any regression; failing here gives faster feedback.
            val out = buildFilterPrompt(emptyList(), emptyList(), "cs")
            out shouldContain "domain relevance filter for a Czech/English ERP question resolver"
            out shouldContain "Return ONLY a JSON array"
            out shouldContain
                """Example: [{"index":0,"entityTypes":["customerId"]}, {"index":2,"entityTypes":["invoiceId","orderId"]}]"""
        }

        // -------------------------------------------------------------------------
        // parseFilterResponse — silent fallback semantics
        // -------------------------------------------------------------------------

        "parseFilterResponse — happy path filters by validRefs and carries entityTypeCandidates" {
            val spans =
                listOf(
                    domainSpan(coveredText = "invoices"),
                    domainSpan(coveredText = "yesterday"),
                    domainSpan(coveredText = "ACME"),
                )
            val entityTypes = listOf(entityType("customerId"), entityType("invoiceId"))
            val response = """[{"index":0,"entityTypes":["invoiceId"]},{"index":2,"entityTypes":["customerId"]}]"""

            val filtered = parseFilterResponse(response, spans, entityTypes)
            filtered shouldHaveSize 2
            filtered[0].coveredText shouldBe "invoices"
            filtered[0].entityTypeCandidates shouldBe listOf("invoiceId")
            filtered[1].coveredText shouldBe "ACME"
            filtered[1].entityTypeCandidates shouldBe listOf("customerId")
        }

        "parseFilterResponse — strips ```json fences" {
            val spans = listOf(domainSpan(coveredText = "invoices"))
            val entityTypes = listOf(entityType("invoiceId"))
            val response = """```json
[{"index":0,"entityTypes":["invoiceId"]}]
```"""
            parseFilterResponse(response, spans, entityTypes) shouldHaveSize 1
        }

        "parseFilterResponse — silent fallback on malformed JSON returns original spans" {
            val spans = listOf(domainSpan(coveredText = "invoices"))
            val entityTypes = listOf(entityType("invoiceId"))
            val response = """[{"index":0,"entityTypes":["customerId"]""" // missing closing brackets

            val filtered = parseFilterResponse(response, spans, entityTypes)
            // ai-platform Resolver semantics: parse failure → no filter applied.
            // Stage 2.4 eval gate validates parity; behavior change is a separate decision.
            filtered shouldBe spans
        }

        "parseFilterResponse — entries with no validRefs are dropped" {
            val spans = listOf(domainSpan(coveredText = "yesterday"))
            val entityTypes = listOf(entityType("invoiceId"))
            val response = """[{"index":0,"entityTypes":["dateRange"]}]"""
            parseFilterResponse(response, spans, entityTypes) shouldHaveSize 0
        }

        "parseFilterResponse — out-of-bounds index drops the entry" {
            val spans = listOf(domainSpan(coveredText = "invoices"))
            val entityTypes = listOf(entityType("invoiceId"))
            val response = """[{"index":99,"entityTypes":["invoiceId"]}]"""
            parseFilterResponse(response, spans, entityTypes) shouldHaveSize 0
        }

        // -------------------------------------------------------------------------
        // callFilterLlm — silent fallback on Result.failure
        // -------------------------------------------------------------------------

        "callFilterLlm — gateway success surfaces verbatim" {
            val llm = mockk<LlmGatewayClient>()
            coEvery {
                llm.complete(
                    prompt = "test prompt",
                    systemPrompt = "",
                    model = "haiku",
                    temperature = 0.0,
                )
            } returns Result.success("""[{"index":0,"entityTypes":["x"]}]""")

            val out = callFilterLlm(llm, "test prompt")
            out shouldNotContain "fail"
            out shouldContain "entityTypes"
        }

        "callFilterLlm — gateway failure returns \"[]\" (preserves Resolver no-filter semantics)" {
            val llm = mockk<LlmGatewayClient>()
            coEvery {
                llm.complete(any(), any(), any(), any(), any())
            } returns Result.failure(RuntimeException("gateway down"))

            callFilterLlm(llm, "test prompt") shouldBe "[]"
        }

        // -------------------------------------------------------------------------
        // namespaceFor — fuzzy namespace resolution fallback ladder
        // -------------------------------------------------------------------------

        "namespaceFor — empty entityTypeCandidates → null (caller skips the span)" {
            val span = domainSpan(coveredText = "noise", entityTypeCandidates = emptyList())
            namespaceFor(span, listOf(entityType("invoiceId", fuzzyMatcherNamespace = "invoices"))) shouldBe null
        }

        "namespaceFor — direct match uses the candidate's EntityTypeSpec namespace" {
            val span = domainSpan(coveredText = "ACME", entityTypeCandidates = listOf("customerId"))
            val entityTypes =
                listOf(
                    entityType("invoiceId", fuzzyMatcherNamespace = "invoices"),
                    entityType("customerId", fuzzyMatcherNamespace = "customers"),
                )
            namespaceFor(span, entityTypes) shouldBe "customers"
        }

        "namespaceFor — unknown candidate falls back to first entityType's namespace" {
            val span = domainSpan(coveredText = "ACME", entityTypeCandidates = listOf("unknownTypeRef"))
            val entityTypes =
                listOf(
                    entityType("invoiceId", fuzzyMatcherNamespace = "invoices"),
                    entityType("customerId", fuzzyMatcherNamespace = "customers"),
                )
            namespaceFor(span, entityTypes) shouldBe "invoices"
        }

        "namespaceFor — no entityTypes at all → null" {
            val span = domainSpan(coveredText = "ACME", entityTypeCandidates = listOf("anything"))
            namespaceFor(span, emptyList()) shouldBe null
        }
    })

private fun domainSpan(
    coveredText: String,
    pos: String = "NOUN",
    depRelation: String = "obj",
    entityTypeCandidates: List<String> = emptyList(),
): DomainSpan =
    DomainSpan(
        charStart = 0,
        charEnd = coveredText.length,
        coveredText = coveredText,
        pos = pos,
        depHead = 0,
        depRelation = depRelation,
        entityTypeCandidates = entityTypeCandidates,
    )

private fun entityType(
    entityTypeRef: String,
    description: String = "$entityTypeRef description",
    fuzzyMatcherNamespace: String = "",
): Themis.EntityTypeSpec =
    Themis.EntityTypeSpec
        .newBuilder()
        .setEntityTypeRef(entityTypeRef)
        .setDescription(description)
        .setFuzzyMatcherNamespace(fuzzyMatcherNamespace)
        .build()
