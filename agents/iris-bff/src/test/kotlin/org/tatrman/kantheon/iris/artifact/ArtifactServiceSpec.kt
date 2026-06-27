package org.tatrman.kantheon.iris.artifact

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.domain.ArtifactKind
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.domain.InMemoryArtifactStore
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnOriginKind
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus
import java.time.Instant
import java.util.UUID

private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
private val caller = CallerIdentity("u1", "t1", "tok")

/** A captured-table turn: envelope carries current_view (provenance) + rows. */
private fun tableTurn(
    rows: List<Pair<String, Int>>,
    bubbleId: String = "b-1",
    agentId: String = "golem-erp",
): TurnRecord {
    val content = rows.joinToString(prefix = "[", postfix = "]") { (m, r) -> """{"m":"$m","r":$r}""" }
    val env =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(bubbleId)
            .setContentJson(content)
            .setAgentId(agentId)
            .setCurrentView(
                CurrentView
                    .newBuilder()
                    .setPatternId("revenue-by-month")
                    .setArgsJson("""{"year":2026}""")
                    .setBubbleId(bubbleId)
                    .setTotalRows(rows.size.toLong()),
            ).build()
    return TurnRecord(
        turnId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        seq = 0,
        agentId = agentId,
        question = "tržby po měsících",
        status = TurnStatus.DONE,
        origin = TurnOriginKind.USER,
        envelopeJson = printer.print(env),
        createdAt = Instant.now(),
    )
}

private fun session(
    sessionId: UUID,
    entityContextJson: String = """[{"entityId":"c-1","display":"Kaufland"}]""",
    currentDisplayJson: String = "{}",
): SessionRecord =
    SessionRecord(
        sessionId = sessionId,
        userId = "u1",
        tenantId = "t1",
        entityContextJson = entityContextJson,
        currentDisplayJson = currentDisplayJson,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

/** Executor stub: returns a canned envelope, or throws to drive the error path. */
private class FakeExecutor(
    private val envelopeJson: String? = null,
    private val fail: String? = null,
) : ArtifactExecutor {
    var calls = 0

    override suspend fun reexecute(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
    ): String {
        calls++
        fail?.let { throw ArtifactRefreshException(it) }
        return envelopeJson ?: error("no envelope configured")
    }
}

class ArtifactServiceSpec :
    StringSpec({

        "capturePin assembles envelope + provenance + applied_context + display slice" {
            val store = InMemoryArtifactStore()
            val service = ArtifactService(store, FakeExecutor(), InMemoryAuditStore(Ed25519Signer()))
            val turn = tableTurn(listOf("Jan" to 10, "Feb" to 20))
            val sess =
                session(
                    turn.sessionId,
                    currentDisplayJson = """{"b-1":{"sort":{"column":"r","direction":"desc"}}}""",
                )

            val pin = service.capturePin(caller, turn, sess, "b-1", "Revenue").shouldNotBeNull()

            pin.kind shouldBe ArtifactKind.PIN
            pin.agentId shouldBe "golem-erp"
            // provenance from the envelope's current_view
            Json
                .parseToJsonElement(pin.provenanceJson!!)
                .jsonObject["patternId"]!!
                .jsonPrimitive.content shouldBe
                "revenue-by-month"
            // applied_context = the session's entity bindings
            pin.appliedContextJson!! shouldContain "Kaufland"
            // display slice = the bubble's sort directive
            pin.displayStateJson!! shouldContain "desc"
            pin.paramMode shouldBe null // golem pin
        }

        "capturePin returns null when the turn has no envelope" {
            val store = InMemoryArtifactStore()
            val service = ArtifactService(store, FakeExecutor(), InMemoryAuditStore(Ed25519Signer()))
            val turn = tableTurn(emptyList()).copy(envelopeJson = null)
            service.capturePin(caller, turn, session(turn.sessionId), "b-1", "X") shouldBe null
        }

        "refresh re-executes, re-applies the stored sort, records refreshed_at + audits ok" {
            val artifacts = InMemoryArtifactStore()
            val audit = InMemoryAuditStore(Ed25519Signer())
            // fresh rows arrive unsorted; the pin's display state sorts by r desc.
            val fresh =
                printer.print(
                    FormatEnvelope
                        .newBuilder()
                        .setBubbleId("b-1")
                        .setContentJson("""[{"m":"Jan","r":10},{"m":"Feb","r":20}]""")
                        .build(),
                )
            val executor = FakeExecutor(envelopeJson = fresh)
            val service = ArtifactService(artifacts, executor, audit)
            val turn = tableTurn(listOf("Jan" to 10, "Feb" to 20))
            val sess =
                session(turn.sessionId, currentDisplayJson = """{"b-1":{"sort":{"column":"r","direction":"desc"}}}""")
            val pin = service.capturePin(caller, turn, sess, "b-1", "Revenue")!!

            val refreshed = runBlocking { service.refresh(caller, pin) }.shouldNotBeNull()

            executor.calls shouldBe 1
            refreshed.refreshError shouldBe null
            refreshed.refreshedAt.shouldNotBeNull()
            // sorted by r desc → Feb (20) first
            val rows =
                Json
                    .parseToJsonElement(refreshed.envelopeJson!!)
                    .jsonObject["contentJson"]!!
                    .jsonPrimitive.content
            Json
                .parseToJsonElement(rows)
                .jsonArray[0]
                .jsonObject["m"]!!
                .jsonPrimitive.content shouldBe "Feb"
            audit.all().any { it.eventKind == "artifact_refresh" } shouldBe true
        }

        "refresh failure records an explicit stale/error state and keeps the last good envelope" {
            val artifacts = InMemoryArtifactStore()
            val audit = InMemoryAuditStore(Ed25519Signer())
            val service = ArtifactService(artifacts, FakeExecutor(fail = "pattern timeout"), audit)
            val turn = tableTurn(listOf("Jan" to 10))
            val pin = service.capturePin(caller, turn, session(turn.sessionId), "b-1", "Revenue")!!
            val goodEnvelope = pin.envelopeJson

            val refreshed = runBlocking { service.refresh(caller, pin) }.shouldNotBeNull()

            refreshed.refreshError shouldBe "pattern timeout"
            refreshed.envelopeJson shouldBe goodEnvelope // last good kept — never silently wrong
            audit.all().any { it.eventKind == "artifact_refresh" } shouldBe true
        }

        "refresh is a no-op pass-through for a dashboard" {
            val artifacts = InMemoryArtifactStore()
            val executor = FakeExecutor()
            val service = ArtifactService(artifacts, executor, InMemoryAuditStore(Ed25519Signer()))
            val dash = service.createDashboard(caller, "Board", emptyList(), null, null, null, "manual")
            runBlocking { service.refresh(caller, dash) }
            executor.calls shouldBe 0
        }
    })
