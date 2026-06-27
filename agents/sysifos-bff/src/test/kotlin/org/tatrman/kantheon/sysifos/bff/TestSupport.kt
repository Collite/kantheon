package org.tatrman.kantheon.sysifos.bff

import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.health.Readiness
import org.tatrman.kantheon.sysifos.bff.dictionaries.DictionaryService
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.bff.session.DraftScratch
import org.tatrman.kantheon.sysifos.bff.session.InMemorySessionStore
import org.tatrman.kantheon.sysifos.bff.stream.SessionStreamBus
import org.tatrman.kantheon.sysifos.bff.write.DraftStateMachine
import org.tatrman.kantheon.sysifos.bff.write.defaultCommitters
import java.time.Instant
import java.util.Base64

/** A decode-mode bearer for tests: `Bearer h.<payload>.s` (no signature). */
fun bearer(payloadJson: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
    return "Bearer h.$payload.s"
}

/**
 * Test deps with injectable readiness + heartbeat + Midas base URL (point it at a
 * Wiremock for the proxy/draft specs; the default bogus URL is never called by the
 * session/health/dictionary/stream specs). Decode-mode auth, no reachability poller.
 */
fun testDeps(
    ready: Boolean = true,
    heartbeatMs: Long = 50,
    midasBaseUrl: String = "http://localhost:1",
    loaderBaseUrl: String = "http://localhost:1",
): SysifosDeps {
    val midas = MidasCoreClient(midasBaseUrl)
    val loader = MidasCoreClient(loaderBaseUrl)
    val scratch = DraftScratch()
    return SysifosDeps(
        auth = BearerAuthenticator(now = { Instant.parse("2026-06-23T00:00:00Z") }),
        sessions = InMemorySessionStore(),
        dictionaries = DictionaryService(),
        readiness = Readiness { ready },
        heartbeatMs = heartbeatMs,
        midas = midas,
        loader = loader,
        bus = SessionStreamBus(),
        draftScratch = scratch,
        stateMachine = DraftStateMachine(defaultCommitters(midas, loader), scratch),
        reachabilityPoll = null,
        onStop = {},
    )
}
