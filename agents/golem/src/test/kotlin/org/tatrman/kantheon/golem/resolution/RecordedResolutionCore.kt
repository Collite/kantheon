package org.tatrman.kantheon.golem.resolution

import com.google.protobuf.util.JsonFormat
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse

/**
 * RV-P5.1 T2 — the **recorded `resolve.bind` exchange**.
 *
 * Stitches `src/test/resources/lattice/<id>.{lattice,parse}.json` into the `ResolveResponse`
 * a live door would return, so every test below drives the real proto surface rather than a
 * hand-built approximation of it. Those files are `tatrman-server`'s own resolver goldens,
 * copied byte-for-byte — see that directory's `PROVENANCE.md`, including why a copy exists
 * at all when RV-28 says there should not be one.
 *
 * The parse is merged INTO the lattice (`ResolutionState.parse`) rather than only sitting at
 * `ResolveResponse.parse`, because the proto's own comment says a lattice travels alone in
 * the RV-26 delegation payload and must be readable without its envelope. A recorded core
 * that filled only one of the two would let a consumer bug hide.
 */
object RecordedResolutionCore {
    /** The four cases `tatrman-server` commits. */
    val CASES = listOf("h1-cs", "h1prime-cs", "h2-cs", "h5-cs")

    fun readResource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/lattice/$name")) {
            "missing recorded fixture: lattice/$name"
        }.bufferedReader().use { it.readText() }

    fun parse(case: String): AnalyzeResponse =
        AnalyzeResponse.newBuilder().also { JsonFormat.parser().merge(readResource("$case.parse.json"), it) }.build()

    fun lattice(case: String): ResolutionState =
        ResolutionState
            .newBuilder()
            .also { JsonFormat.parser().merge(readResource("$case.lattice.json"), it) }
            .setParse(parse(case))
            .build()

    fun response(case: String): ResolveResponse =
        ResolveResponse
            .newBuilder()
            .setParse(parse(case))
            .setResolutionState(lattice(case))
            .setTraceId("recorded-$case")
            .setCapabilities(
                Capabilities
                    .newBuilder()
                    .setLanguage("cs")
                    .setCsNer(true)
                    .setDepParse(true)
                    .setFuzzyReady(true),
            ).build()

    /** A client that answers one recorded case and records the request it was asked with. */
    fun client(case: String): RecordingClient = RecordingClient(answer = { response(case) })

    /** A client whose door is down. */
    fun failing(
        code: String = "UNAVAILABLE",
        message: String = "resolver unreachable",
    ): RecordingClient = RecordingClient(answer = { throw ResolutionCoreException(code, message) })

    class RecordingClient(
        private val answer: () -> ResolveResponse,
        private val gateAnswer: (GateRequest) -> GateResponse = { GateResponse.getDefaultInstance() },
    ) : ResolutionCoreClient {
        val requests = mutableListOf<ResolveRequest>()
        val gateRequests = mutableListOf<GateRequest>()

        override suspend fun resolve(request: ResolveRequest): ResolveResponse {
            requests += request
            return answer()
        }

        override suspend fun gate(request: GateRequest): GateResponse {
            gateRequests += request
            return gateAnswer(request)
        }
    }
}
