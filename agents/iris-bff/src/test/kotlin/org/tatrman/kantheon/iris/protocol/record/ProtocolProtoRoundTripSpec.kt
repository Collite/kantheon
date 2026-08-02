package org.tatrman.kantheon.iris.protocol.record

import com.google.protobuf.InvalidProtocolBufferException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.protocol.v1.ErrorItem
import org.tatrman.kantheon.protocol.v1.ErrorsSection
import org.tatrman.kantheon.protocol.v1.ExecutionSection
import org.tatrman.kantheon.protocol.v1.HeaderSection
import org.tatrman.kantheon.protocol.v1.HintTiming
import org.tatrman.kantheon.protocol.v1.LlmCall
import org.tatrman.kantheon.protocol.v1.LlmCallsSection
import org.tatrman.kantheon.protocol.v1.LlmMessage
import org.tatrman.kantheon.protocol.v1.PlanSection
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolHints
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.ProtocolTurn
import org.tatrman.kantheon.protocol.v1.QuerySection
import org.tatrman.kantheon.protocol.v1.ReceiptsSection
import org.tatrman.kantheon.protocol.v1.RecordCaptures
import org.tatrman.kantheon.protocol.v1.RecordPointers
import org.tatrman.kantheon.protocol.v1.ResolutionSection
import org.tatrman.kantheon.protocol.v1.Scope
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.SecuritySection
import org.tatrman.kantheon.protocol.v1.ServiceLogGroup
import org.tatrman.kantheon.protocol.v1.ServiceLogsSection
import org.tatrman.kantheon.protocol.v1.SessionHeader
import org.tatrman.kantheon.protocol.v1.SourceReceipt
import org.tatrman.kantheon.protocol.v1.SqlSection
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * `protocol/v1` wire-shape guard (contracts §1). Proto is the source of truth
 * even where the wire is REST JSON (kantheon-architecture §4), so the binary
 * round-trip is the contract test: bytes → parse → equal, for the full spine.
 *
 * The unknown-field cases pin the two forward-compatibility promises the arc
 * leans on — PT-25 hints ride additively on `golem/v1`, and a reader built
 * against an older descriptor must preserve, not drop, what it cannot name.
 */
class ProtocolProtoRoundTripSpec :
    StringSpec({

        "ProtocolDocument with full spine round-trips bytes -> parse -> equal" {
            val doc =
                ProtocolDocument
                    .newBuilder()
                    .setProtocolId("11111111-1111-1111-1111-111111111111")
                    .setSessionId("22222222-2222-2222-2222-222222222222")
                    .setScope(Scope.newBuilder().setWholeSession(true))
                    .setHeader(
                        SessionHeader
                            .newBuilder()
                            .setTitle("Protocol — Q3 margin walk — whole session")
                            .setUserId("maya")
                            .setTenantId("hartland")
                            .addAgentIds("golem-finance")
                            .addAgentIds("themis")
                            .setSessionCreatedAt("2026-07-30T09:00:00+02:00")
                            .setTurnCountInScope(2)
                            .setTurnCountTotal(2),
                    ).addTurns(
                        ProtocolTurn
                            .newBuilder()
                            .setTurnId("33333333-3333-3333-3333-333333333333")
                            .setSeq(1)
                            .addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.header")
                                    .setStatus(SectionStatus.SECTION_OK)
                                    .setAppliedVerbosity(Verbosity.VERBOSITY_FULL)
                                    .setHeader(
                                        HeaderSection
                                            .newBuilder()
                                            .setQuestion("How did margin move in Q3?")
                                            .setAgentId("golem-finance")
                                            .setRoutingOutcome("routed")
                                            .setStatus("done")
                                            .setOrigin("user")
                                            .setStartedAt("2026-07-30T09:00:01+02:00")
                                            .setDurationMs(2_140),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.resolution")
                                    .setStatus(SectionStatus.SECTION_OK)
                                    .setAppliedVerbosity(Verbosity.VERBOSITY_FULL)
                                    .setResolution(
                                        ResolutionSection
                                            .newBuilder()
                                            .setFunctionId("margin_by_period")
                                            .setArgsJson("""{"period":"Q3"}""")
                                            .setConfidence(0.94)
                                            .setRationale("layer-2 lexical hit")
                                            .setLayerHit(2),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.llm-calls")
                                    .setStatus(SectionStatus.SECTION_OK)
                                    .setAppliedVerbosity(Verbosity.VERBOSITY_SUMMARY)
                                    .setLlmCalls(
                                        LlmCallsSection
                                            .newBuilder()
                                            .addCalls(
                                                LlmCall
                                                    .newBuilder()
                                                    .setCallRef("gw-771")
                                                    .setPurpose("resolve.classify")
                                                    .setRequestedModel("claude-opus-5")
                                                    .setServedModel("claude-opus-5")
                                                    .setServedProvider("azure")
                                                    .setTokensPrompt(1_204)
                                                    .setTokensCompletion(88)
                                                    .setDurationMs(910)
                                                    .setTtfbMs(310)
                                                    .setCostUsd(0.0123)
                                                    .setStatus("SUCCESS")
                                                    .addMessages(
                                                        LlmMessage
                                                            .newBuilder()
                                                            .setRole("system")
                                                            .setContent("<digested>")
                                                            .setContentRedacted(true),
                                                    ),
                                            ).setUnattributableCount(1),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.query")
                                    .setQuery(
                                        QuerySection
                                            .newBuilder()
                                            .setEntityQuery("margin by period")
                                            .setQueryKind("aggregate"),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.plan")
                                    .setPlan(
                                        PlanSection
                                            .newBuilder()
                                            .setRelPlanText(
                                                "LogicalAggregate(...)",
                                            ).setReconstructed(true),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.sql")
                                    .setTruncated(true)
                                    .setSql(
                                        SqlSection
                                            .newBuilder()
                                            .setSql("SELECT 1")
                                            .setDialect("postgres")
                                            .setEngineLabel("pg-hartland")
                                            .setLiteralsMasked(true),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.security")
                                    .setSecurity(SecuritySection.newBuilder().setPolicySource("perun")),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.execution")
                                    .setExecution(
                                        ExecutionSection
                                            .newBuilder()
                                            .setDispatchTarget("pg-hartland")
                                            .setWorker("worker-postgres")
                                            .setRowCount(72)
                                            .setDurationMs(180),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.service-logs")
                                    .setStatus(SectionStatus.SECTION_DEGRADED)
                                    .setServiceLogs(
                                        ServiceLogsSection
                                            .newBuilder()
                                            .addGroups(
                                                ServiceLogGroup
                                                    .newBuilder()
                                                    .setServiceName("resolver-agent")
                                                    .setDroppedByCap(12),
                                            ),
                                    ),
                            ).addSections(
                                Section
                                    .newBuilder()
                                    .setKey("protocol.section.errors")
                                    .setStatus(SectionStatus.SECTION_OFF)
                                    .setErrors(
                                        ErrorsSection
                                            .newBuilder()
                                            .addItems(
                                                ErrorItem
                                                    .newBuilder()
                                                    .setSource(
                                                        "loki",
                                                    ).setCode("504")
                                                    .setMessage("timeout"),
                                            ),
                                    ),
                            ),
                    ).setReceipts(
                        ReceiptsSection
                            .newBuilder()
                            .setProfileName("default")
                            .setGeneratedBy("hartland/assembler-1.0")
                            .addSources(
                                SourceReceipt
                                    .newBuilder()
                                    .setSource("records")
                                    .setStatus("ok")
                                    .setDetail("2 rows"),
                            ),
                    ).setSchemaVersion(SchemaVersion.CURRENT)
                    .setGeneratedAt("2026-07-30T09:05:00+02:00")
                    .build()

            val parsed = ProtocolDocument.parseFrom(doc.toByteArray())

            parsed shouldBe doc
            parsed.turnsList
                .single()
                .sectionsList
                .map { it.key } shouldContainExactly
                listOf(
                    "protocol.section.header",
                    "protocol.section.resolution",
                    "protocol.section.llm-calls",
                    "protocol.section.query",
                    "protocol.section.plan",
                    "protocol.section.sql",
                    "protocol.section.security",
                    "protocol.section.execution",
                    "protocol.section.service-logs",
                    "protocol.section.errors",
                )
        }

        "ProtocolRecord with pointers, captures and hints round-trips" {
            val record =
                ProtocolRecord
                    .newBuilder()
                    .setTurnId("44444444-4444-4444-4444-444444444444")
                    .setPointers(
                        RecordPointers
                            .newBuilder()
                            .setTraceId("0af7651916cd43dd8448eb211c80319c")
                            .setCorrelationId("corr-9")
                            .setGatewayTurnRef("turn-9")
                            .addPlanIds("plan-1")
                            .addLlmCallRefs("gw-771")
                            .setSqlInline("SELECT 1")
                            .setLogWindowFrom("2026-07-30T09:00:00+02:00")
                            .setLogWindowTo("2026-07-30T09:00:05+02:00")
                            .setHints(ProtocolHints.newBuilder().addPlanIds("plan-1").setSqlRef("sql-1")),
                    ).setCaptures(
                        RecordCaptures
                            .newBuilder()
                            .setResolveResponse(
                                com.google.protobuf.ByteString
                                    .copyFromUtf8("f2-bytes"),
                            ).setSecurityApplied(
                                com.google.protobuf.ByteString
                                    .copyFromUtf8("f7-bytes"),
                            ),
                    ).setSchemaVersion(SchemaVersion.CURRENT)
                    .build()

            ProtocolRecord.parseFrom(record.toByteArray()) shouldBe record
        }

        "ProtocolHints round-trips and survives being read as unknown fields by an older reader" {
            val hints =
                ProtocolHints
                    .newBuilder()
                    .addPlanIds("plan-1")
                    .addPlanIds("plan-2")
                    .addLlmCallRefs("gw-1")
                    .setSqlRef("sql-ref-1")
                    .setSqlInline("SELECT 2")
                    .addTimings(HintTiming.newBuilder().setStep("resolve").setDurationMs(120))
                    .addTimings(HintTiming.newBuilder().setStep("execute").setDurationMs(880))
                    .build()

            ProtocolHints.parseFrom(hints.toByteArray()) shouldBe hints

            // Hints never travel alone — they ride as a field of a container
            // (RecordPointers.hints = 10, ConversationalResponse.protocol_hints = 12).
            // So an "older reader" is a consumer whose descriptor has no such field
            // NUMBER. Proto3 keeps what it cannot name, and the block survives the
            // parse/reserialize hop intact — the promise contracts §4 rests on.
            val carrier =
                RecordPointers
                    .newBuilder()
                    .setTraceId("trace-1")
                    .setCorrelationId("corr-1")
                    .setHints(hints)
                    .build()

            // QuerySection declares fields 1-2 only, both singular strings — the same
            // shape RecordPointers has there. That match matters: a stand-in whose
            // field 1 is SINGULAR where the writer's is REPEATED parses it as a known
            // field and keeps only the last entry, losing the rest with no error.
            // Which is the concrete reason contracts §4 forbids renumbering: reusing a
            // number at a different cardinality corrupts old readers silently.
            val older = QuerySection.parseFrom(carrier.toByteArray())
            older.entityQuery shouldBe "trace-1"
            older.unknownFields.asMap().keys shouldContain 10

            RecordPointers.parseFrom(older.toByteArray()).hints shouldBe hints
        }

        "Scope oneof: exactly one of last_turn/whole_session/last_n set" {
            val last = Scope.newBuilder().setLastTurn(true).build()
            last.kindCase shouldBe Scope.KindCase.LAST_TURN

            val whole = last.toBuilder().setWholeSession(true).build()
            whole.kindCase shouldBe Scope.KindCase.WHOLE_SESSION
            whole.lastTurn shouldBe false

            val lastN = whole.toBuilder().setLastN(3u.toInt()).build()
            lastN.kindCase shouldBe Scope.KindCase.LAST_N
            lastN.wholeSession shouldBe false
            lastN.lastN shouldBe 3

            Scope.newBuilder().build().kindCase shouldBe Scope.KindCase.KIND_NOT_SET
            Scope.parseFrom(lastN.toByteArray()) shouldBe lastN
        }

        "ReceiptsSection is present and serializes with profile_name + generated_by" {
            val receipts =
                ReceiptsSection
                    .newBuilder()
                    .setProfileName("operator")
                    .setGeneratedBy("hartland/assembler-1.0")
                    .addSources(
                        SourceReceipt
                            .newBuilder()
                            .setSource("loki")
                            .setStatus("degraded")
                            .setDetail("query timed out"),
                    ).addSources(
                        SourceReceipt
                            .newBuilder()
                            .setSource("tempo")
                            .setStatus("ok")
                            .setDetail("14 spans"),
                    ).build()

            val parsed = ReceiptsSection.parseFrom(receipts.toByteArray())
            parsed shouldBe receipts
            parsed.profileName shouldBe "operator"
            parsed.generatedBy shouldBe "hartland/assembler-1.0"
            parsed.sourcesList.map { it.source } shouldContainExactly listOf("loki", "tempo")

            // PT-13/S-6: receipts is a MANDATORY member of ProtocolDocument, not an
            // optional add-on. Field 6 must exist on the document descriptor.
            ProtocolDocument.getDescriptor().findFieldByName("receipts") shouldNotBe null
        }

        "ConversationalResponse with protocol_hints round-trips; old readers ignore the field" {
            val response =
                ConversationalResponse
                    .newBuilder()
                    .setId("resp-1")
                    .setRequestId("req-1")
                    .setGolemId("golem-finance")
                    .setProtocolHints(
                        ProtocolHints
                            .newBuilder()
                            .addLlmCallRefs("gw-771")
                            .setSqlInline("SELECT 1")
                            .addTimings(HintTiming.newBuilder().setStep("execute").setDurationMs(180)),
                    ).build()

            val parsed = ConversationalResponse.parseFrom(response.toByteArray())
            parsed shouldBe response
            parsed.protocolHints.llmCallRefsList shouldContainExactly listOf("gw-771")

            // contracts §4: additive only. A consumer that predates the field parses
            // the same bytes without error and re-emits them intact.
            val asOlderReader = QuerySection.parseFrom(response.toByteArray())
            ConversationalResponse.parseFrom(asOlderReader.toByteArray()).protocolHints shouldBe response.protocolHints

            // ...and nothing was renumbered underneath it: field 1 is still `id`.
            ConversationalResponse.getDescriptor().findFieldByNumber(1).name shouldBe "id"
        }

        "unparseable bytes fail loudly rather than yielding a half-built record" {
            shouldThrow<InvalidProtocolBufferException> {
                ProtocolRecord.parseFrom(byteArrayOf(0x08, 0x08, 0x08.toByte(), 0xFF.toByte()))
            }
        }
    })
