package org.tatrman.kantheon.golem.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryParameterDef
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.context.PackageContext
import org.tatrman.kantheon.golem.execution.CompileResult
import org.tatrman.kantheon.golem.execution.MiniPlanExecutor
import org.tatrman.kantheon.golem.execution.QueryClient
import org.tatrman.kantheon.golem.execution.QueryResult
import org.tatrman.kantheon.golem.execution.SelectionResolver
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.persistence.GolemTurnRecord
import org.tatrman.kantheon.golem.persistence.GolemTurnStatus
import org.tatrman.kantheon.golem.persistence.InMemoryTurnsRepository
import org.tatrman.kantheon.golem.persistence.TurnsRepository
import org.tatrman.kantheon.golem.plan.PlanComposer
import org.tatrman.kantheon.golem.plan.PlanValidator
import org.tatrman.kantheon.golem.resume.ClarificationSpan
import org.tatrman.kantheon.golem.resume.ResumeCodec
import org.tatrman.kantheon.golem.resume.ResumeOption
import org.tatrman.kantheon.golem.resume.ResumePayload
import org.tatrman.kantheon.golem.resume.ResumeTokenException
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.RowSelection
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayPromptExecutor
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private const val PROMPT = "system: \"x\"\nuser: \"{{ question }}\""

private val fixtureRows =
    buildJsonArray {
        add(
            buildJsonObject {
                put("KOD_STR", "DF01")
                put("ZUSTATEK", 12500.5)
            },
        )
    }

/** A QueryClient that records calls and returns fixture rows. */
private class RecordingQueryClient : QueryClient {
    val params = mutableListOf<String>()

    override suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult {
        params += paramsJson
        return QueryResult(fixtureRows, emptyList(), fixtureRows.size.toLong(), truncated = false)
    }

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult = CompileResult("SELECT 1", true)
}

private fun promptStore(): PromptStore =
    PromptStore(
        shemDir = Path.of("/nonexistent-golem-shem"),
        locale = "cs",
        fallback = { mapOf("intent" to PROMPT) },
    ).also { it.refresh() }

private suspend fun deps(
    replyJson: String,
    queryClient: QueryClient,
    turns: TurnsRepository,
): GolemGraphDeps {
    val gateway = mockk<LlmGatewayClient>()
    coEvery { gateway.complete(any(), any(), any(), any(), any()) } returns Result.success(replyJson)
    val promptExecutor = LlmGatewayPromptExecutor(gateway)
    return GolemGraphDeps(
        composer = PlanComposer(promptExecutor, promptStore()),
        validator = PlanValidator(),
        miniPlanExecutor = MiniPlanExecutor(queryClient),
        promptExecutor = promptExecutor,
        selectionResolver = SelectionResolver(turns),
    )
}

private fun model(): ModelSnapshot {
    val q =
        ModelBundleQuery
            .newBuilder()
            .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName("stred"))
            .setSourceText("SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska}")
            .addParameters(QueryParameterDef.newBuilder().setName("nazev_strediska").setType("varchar"))
            .build()
    return ModelSnapshot.from(ModelBundle.newBuilder().addPatternQueries(q).build())
}

private fun packageContextWith(model: ModelSnapshot): PackageContext =
    mockk<PackageContext> {
        every { currentOrNull() } returns model
    }

private fun request(
    id: String = UUID.randomUUID().toString(),
    question: String = "kolik?",
    selection: RowSelection? = null,
): GolemRequest {
    val b =
        GolemRequest
            .newBuilder()
            .setId(id)
            .setGolemId("golem-erp")
            .setQuestion(question)
    selection?.let { b.contextBuilder.selection = it }
    return b.build()
}

private fun caller(): AdmittedCaller =
    AdmittedCaller(userId = "alice", tenantId = "default", roles = setOf("erp"), bearer = "obo-tok")

private const val FREE_SQL_JSON =
    """{"source":"FREE_SQL","confidence":0.97,"nodes":[
       {"node_id":"q1","query":{"source":"SELECT 1","source_language":"sql","params_json":"{}","compile_first":true}},
       {"node_id":"r1","render":{"input_node_ids":["q1"]}}]}"""

private const val LOW_CONF_JSON =
    """{"source":"FREE_SQL","confidence":0.40,"nodes":[
       {"node_id":"q1","query":{"source":"SELECT 1","source_language":"sql","params_json":"{}","compile_first":true}}]}"""

private const val PATTERN_JSON =
    """{"source":"PATTERN","confidence":0.95,"nodes":[
       {"node_id":"q1","query":{"pattern_id":"stred","source_language":"sql","params_json":"{\"nazev_strediska\":\"DF ADNAK\"}"}},
       {"node_id":"r1","render":{"input_node_ids":["q1"]}}]}"""

// A drill that leaves the param unfilled — the row-detail selection must fill it.
private const val DRILL_SELECT_JSON =
    """{"source":"DRILL","confidence":0.95,"nodes":[
       {"node_id":"q1","query":{"pattern_id":"stred","source_language":"sql","params_json":"{}"}},
       {"node_id":"r1","render":{"input_node_ids":["q1"]}}]}"""

// A pattern plan that omits the required param — the executor asks via param_fill.
private const val PARAM_FILL_JSON =
    """{"source":"PATTERN","confidence":0.95,"nodes":[
       {"node_id":"q1","query":{"pattern_id":"stred","source_language":"sql","params_json":"{}"}},
       {"node_id":"r1","render":{"input_node_ids":["q1"]}}]}"""

class AnswerServiceSpec :
    StringSpec({

        "a FREE_SQL turn answers DONE, renders a table, echoes ids, and persists one row" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val service = AnswerService(deps(FREE_SQL_JSON, RecordingQueryClient(), turns), null, turns)
                val req = request()
                val resp = service.answer(req, caller())

                resp.status shouldBe Status.STATUS_DONE
                resp.requestId shouldBe req.id
                resp.golemId shouldBe "golem-erp"
                resp.envelopesList shouldHaveSize 1
                // The persisted row mirrors the response.
                val row = turns.findByRequestId(UUID.fromString(req.id))
                row.shouldNotBeNull()
                row.status shouldBe GolemTurnStatus.DONE
            }
        }

        "a low-confidence plan clarifies and persists a CLARIFICATION row" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val service = AnswerService(deps(LOW_CONF_JSON, RecordingQueryClient(), turns), null, turns)
                val req = request()
                val resp = service.answer(req, caller())

                resp.status shouldBe Status.STATUS_CLARIFICATION
                resp.envelopesList shouldHaveSize 1
                turns.findByRequestId(UUID.fromString(req.id))!!.status shouldBe GolemTurnStatus.CLARIFICATION
            }
        }

        "a parametrized PATTERN turn forwards the typed {name:{value,type}} map to the query edge" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val client = RecordingQueryClient()
                val service = AnswerService(deps(PATTERN_JSON, client, turns), packageContextWith(model()), turns)
                val resp = service.answer(request(), caller())

                resp.status shouldBe Status.STATUS_DONE
                // The rail turned the raw arg into the typed envelope before the call.
                client.params.single() shouldContain "\"value\":\"DF ADNAK\""
                client.params.single() shouldContain "\"type\":\"varchar\""
                // S3.1: the format enricher attached chips + current_view end-to-end (PD-4 echo).
                val env = resp.envelopesList.single()
                env.chipsList.any { it.prompt.display == "Detail střediska" } shouldBe true
                env.currentView.totalRows shouldBe 1
            }
        }

        "an unbound required param emits a param_fill clarification, then resume binds + answers" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val client = RecordingQueryClient()
                val service = AnswerService(deps(PARAM_FILL_JSON, client, turns), packageContextWith(model()), turns)

                // Turn 1: the required param is missing → ask the user.
                val first = service.answer(request(id = "00000000-0000-0000-0000-0000000000aa"), caller())
                first.status shouldBe Status.STATUS_CLARIFICATION
                val pc = first.envelopesList.single().pendingClarification
                pc.kind shouldBe "param_fill"
                first.envelopesList.single().errorCode shouldBe "PARAM_FILL_CLARIFICATION"
                pc.optionsList.single().id shouldBe "nazev_strediska"
                client.params.size shouldBe 0 // nothing forwarded yet

                // Turn 2: resume with the answer → it binds + executes.
                val resumed =
                    service.resume(
                        token = pc.resumeToken,
                        caller = caller(),
                        freeTextAnswer = "DF ADNAK",
                        selectedOptionId = null,
                    )
                resumed.status shouldBe Status.STATUS_DONE
                client.params.single() shouldContain "\"value\":\"DF ADNAK\""
            }
        }

        "an entity_choice resume splices the chosen option into the question and re-runs (Δ3 fallback)" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val service = AnswerService(deps(FREE_SQL_JSON, RecordingQueryClient(), turns), null, turns)
                // Mint an entity_choice token (no resolver token → the splice fallback path).
                val codec = ResumeCodec("dev-secret-change-in-production")
                val turnId = "00000000-0000-0000-0000-0000000000bb"
                val token =
                    codec.encode(
                        ResumePayload(
                            threadId = "s1",
                            turnId = turnId,
                            kind = "entity_choice",
                            userText = "tržby za DF",
                            options = listOf(ResumeOption(id = "opt1", display = "Kaufland ČR")),
                            clarificationSpan = ClarificationSpan(charStart = 10, charEnd = 12, coveredText = "DF"),
                            userId = "alice",
                            tenantId = "default",
                        ),
                    )
                val resumed = service.resume(token, caller(), freeTextAnswer = null, selectedOptionId = "opt1")
                resumed.status shouldBe Status.STATUS_DONE
                // The re-run turn's question had "DF" replaced by the chosen option.
                turns.findByRequestId(UUID.fromString(turnId))!!.question shouldBe "tržby za Kaufland ČR"
            }
        }

        "a resume token minted for another caller is rejected (B1 — no cross-tenant replay)" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val service = AnswerService(deps(FREE_SQL_JSON, RecordingQueryClient(), turns), null, turns)
                val codec = ResumeCodec("dev-secret-change-in-production")
                // Minted for bob/tenant-b — a valid, well-signed token, just not this caller's.
                val token =
                    codec.encode(
                        ResumePayload(
                            threadId = "s1",
                            turnId = "00000000-0000-0000-0000-0000000000cc",
                            kind = "entity_choice",
                            userText = "tržby za DF",
                            options = listOf(ResumeOption(id = "opt1", display = "Kaufland ČR")),
                            userId = "bob",
                            tenantId = "tenant-b",
                        ),
                    )
                val ex =
                    shouldThrow<ResumeTokenException> {
                        // alice/default replays bob's token → reject.
                        service.resume(token, caller(), freeTextAnswer = null, selectedOptionId = "opt1")
                    }
                ex.message shouldContain "different caller"
                // Nothing was executed or persisted under the attacker's identity.
                turns.findByRequestId(UUID.fromString("00000000-0000-0000-0000-0000000000cc")).shouldBeNull()
            }
        }

        "an entity_choice resume rejects a selected_option_id that matches no option (H1)" {
            runTest {
                val turns = InMemoryTurnsRepository()
                val service = AnswerService(deps(FREE_SQL_JSON, RecordingQueryClient(), turns), null, turns)
                val codec = ResumeCodec("dev-secret-change-in-production")
                val token =
                    codec.encode(
                        ResumePayload(
                            threadId = "s1",
                            turnId = "00000000-0000-0000-0000-0000000000dd",
                            kind = "entity_choice",
                            userText = "tržby za DF",
                            options = listOf(ResumeOption(id = "opt1", display = "Kaufland ČR")),
                            userId = "alice",
                            tenantId = "default",
                        ),
                    )
                shouldThrow<ResumeTokenException> {
                    service.resume(token, caller(), freeTextAnswer = null, selectedOptionId = "does-not-exist")
                }
            }
        }

        "a row-detail selection binds an unfilled pattern param from the selected row" {
            runTest {
                val turns = InMemoryTurnsRepository()
                // Seed a prior turn whose bubble 'b1' displayed a row with the NAZEV_STREDISKA
                // column (matches the pattern param 'nazev_strediska' case-insensitively).
                turns.insert(priorTurnWithRow("b1", mapOf("NAZEV_STREDISKA" to "DF ADNAK")))
                val client = RecordingQueryClient()
                val service =
                    AnswerService(deps(DRILL_SELECT_JSON, client, turns), packageContextWith(model()), turns)

                val sel =
                    RowSelection
                        .newBuilder()
                        .setBubbleId("b1")
                        .addRowIndices(0)
                        .build()
                val resp = service.answer(request(selection = sel), caller())

                resp.status shouldBe Status.STATUS_DONE
                // The plan left nazev_strediska unfilled; the selection's NAZEV_STR column fills it
                // (case-insensitive), and the rail then types it.
                client.params.single() shouldContain "\"value\":\"DF ADNAK\""
            }
        }
    })

private fun priorTurnWithRow(
    bubbleId: String,
    row: Map<String, String>,
): GolemTurnRecord {
    val rowsJson =
        buildJsonArray {
            add(buildJsonObject { row.forEach { (k, v) -> put(k, v) } })
        }.toString()
    val envelopesJson =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("bubbleId", bubbleId)
                    put("contentJson", rowsJson)
                },
            )
        }.toString()
    return GolemTurnRecord(
        id = UUID.randomUUID(),
        requestId = UUID.randomUUID(),
        golemId = "golem-erp",
        userId = "alice",
        tenantId = "default",
        question = "q",
        resolvedIntentJson = "{}",
        planJson = "{}",
        envelopesJson = envelopesJson,
        currentViewJson = buildJsonObject { put("bubbleId", bubbleId) }.toString(),
        status = GolemTurnStatus.DONE,
        createdAt = Instant.now(),
    )
}
