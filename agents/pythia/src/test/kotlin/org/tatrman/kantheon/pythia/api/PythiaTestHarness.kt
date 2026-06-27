package org.tatrman.kantheon.pythia.api

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tatrman.kantheon.pythia.auth.BearerAdmission
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.InvestigationOrchestrator
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryHypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRecord
import org.tatrman.kantheon.pythia.v1.Status
import java.time.Instant
import java.util.UUID

/**
 * In-memory wiring of the full control surface for component tests. Investigation
 * coroutines run on [Dispatchers.Unconfined] so the scripted-stub pipeline
 * completes synchronously within the request — no timing flakiness.
 */
class PythiaTestHarness {
    val investigations = InMemoryInvestigationRepository()
    val hypotheses = InMemoryHypothesisRepository()
    val steps = InMemoryStepRepository()
    val events = InMemoryEventRepository()
    private val nats = RecordingNatsPublisher()
    val emitter = EventEmitter(events, nats)
    private val checkpointer = Checkpointer(InMemoryCheckpointRepository(), investigations)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    val orchestrator = InvestigationOrchestrator(investigations, checkpointer, emitter, scope, awaitingTtlHours = 24)
    val assembler = ArtifactAssembler(investigations, hypotheses, steps)
    val admission = BearerAdmission()

    fun mount(builder: ApplicationTestBuilder) {
        builder.application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                controlRoutes(orchestrator, investigations, assembler, admission)
                sseRoutes(investigations, events, assembler, admission)
            }
        }
    }

    /** Seed an investigation directly at [status] (for resume-endpoint tests). */
    fun seed(
        status: Status,
        userId: String = "u1",
        id: UUID = UUID.randomUUID(),
    ): UUID {
        val now = Instant.now()
        investigations.insert(
            InvestigationRecord(
                id = id,
                callerJson = """{"kind":"IRIS","userId":"$userId","tenantId":"t1"}""",
                question = "why?",
                requestJson = """{"caller":{"userId":"$userId"}}""",
                status = status.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }
}
