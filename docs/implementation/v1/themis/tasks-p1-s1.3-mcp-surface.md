# Stage 1.3 — MCP + REST surface + heartbeat client

> **Phase 1, Stage 1.3.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.3, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §2 (MCP/REST surface) + §4 (heartbeat client), [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §7 (capabilities-mcp internals).

## Goal

`capabilities-mcp` answers all six MCP tools and their REST mirrors against the in-memory registry. TTL pruner + version resolver behave per spec. `shared/libs/kotlin/capabilities-client` library exists, with idempotent startup register and 30s-TTL read cache, all tested against Wiremock.

## Pre-flight

- [ ] **Stage 1.2 DONE** — capabilities proto + service skeleton merged.
- [ ] **Branch**: `feat/p1-s1.3-mcp-surface` from `main`.

## Tasks

- [x] **T1 — Tests-first for the MCP tool surface.** _Order swapped: REST mirror tested first (CapabilitiesRestSpec, 9 cases) since both MCP + REST delegate to a shared RegistryQueryService. MCP-side coverage is a smoke test (`CapabilitiesMcpSpec`) that asserts tools/list advertises the six tools; per-tool semantics covered through REST._

  Create `tools/capabilities-mcp/src/test/kotlin/.../api/CapabilitiesMcpSpec.kt` covering the six MCP tools per [`contracts.md`](../../../architecture/themis/contracts.md) §2.1. Use Ktor `testApplication` with the SDK's `mcpStreamableHttp` mounted; drive via raw HTTP POST (no MCP client SDK needed for tests).

  ```kotlin
  class CapabilitiesMcpSpec : StringSpec({
      "capabilities.list_agents returns only live agents" { /* register 2 agents + 1 tool; list_agents returns 2 */ }
      "capabilities.list returns all entries by default" { /* same fixture; list returns 3 */ }
      "capabilities.search filters by intent_kinds" { /* register 2 agents with disjoint intent_kinds; search returns matching */ }
      "capabilities.get returns null for unknown id" { /* response.capability is empty proto */ }
      "capabilities.register is idempotent on capability_id" { /* register twice → same registration_id */ }
      "capabilities.heartbeat refreshes last_heartbeat_at" { /* register → heartbeat → assert acceptedAt > registeredAt */ }
      "capabilities.heartbeat with unknown registration_id returns 404-ish messages entry" { /* assert messages.size == 1, severity == ERROR */ }
      "every response carries messages: [] per Rule 6" { /* JSON path messages exists on every response */ }
  })
  ```

  Helpers in `Fixtures.kt` from Stage 1.2 carry over. Add `mcpPost(toolName: String, input: JsonObject): JsonObject` helper that builds an MCP call-tool request frame and parses the streaming response.

  Acceptance: tests written; they fail; commit `[p1-s1.3] failing MCP surface specs`.

- [x] **T2 — Implement the six MCP tools.** _via Kotlin MCP SDK `Application.mcpStreamableHttp("/mcp")` + `Server.addTool`. ai-platform's `safeMcpTool` doesn't exist (no mcp-server-base) so an inline `guarded(...)` wrapper provides the same timeout + isError semantics._

  Use ai-platform's MCP pattern from `EXAMPLES.md` §3a + §3b + §3c. Reference impl: `/Users/bora/Dev/ai-platform/tools/query-mcp/src/main/kotlin/tools/querymcp/mcp/`.

  Files:

  - `api/McpTools.kt` — `Application.installCapabilitiesMcp(registry)` extension function mounting `mcpStreamableHttp { ... }` per `EXAMPLES.md` §3b. Inside, create one `Server(Implementation("capabilities-mcp", "0.1.0"), ...)` and register the six tools.
  - `api/tools/SearchTool.kt`, `ListTool.kt`, `ListAgentsTool.kt`, `GetTool.kt`, `RegisterTool.kt`, `HeartbeatTool.kt` — one file per tool, each implementing the `McpTool` interface from `mcp-server-base` (see `EXAMPLES.md` §3b's `tools/query-mcp/.../McpTool.kt` for the interface shape).

  Each tool defines:
  - `name`, `description`, `inputSchema` (JSON Schema), `outputSchema` (JSON Schema or null).
  - `execute(request, identity)` returning `CallToolResult`.

  Wrap each tool's callback with `safeMcpTool("<name>", timeoutMs = 5_000) { ... }` per `EXAMPLES.md` §3c so timeouts and exceptions become well-formed `CallToolResult(isError = true, ...)`.

  Wire `installCapabilitiesMcp(registry)` from `App.kt` after `installKtorServerBase(...)`.

  **JSON serialization:** every `structuredContent` JSON uses `buildJsonObject` + `JsonPrimitive`, never `mapOf` (Rule from `EXAMPLES.md` §2a). For the `entries` arrays: use `JsonArray(list.map { capabilityToJson(it) })` where `capabilityToJson` builds the discriminated-union JSON.

  Acceptance: T1's spec turns green. Commit: `[p1-s1.3] MCP surface green`.

- [x] **T3 — REST mirror at `/v1/capabilities/...`.** _MCP + REST share `RegistryQueryService`. All endpoints emit `messages: []` per Rule 6. Heartbeat on unknown rid → 404 with `severity=ERROR, code=unknown_registration_id`._

  Create `api/RestRoutes.kt` per [`contracts.md`](../../../architecture/themis/contracts.md) §2.2:

  ```
  POST   /v1/capabilities/search                            → SearchResponse
  GET    /v1/capabilities                                    → ListResponse
  GET    /v1/capabilities/agents                             → ListAgentsResponse
  GET    /v1/capabilities/{id}                              → GetResponse
  POST   /v1/capabilities/register                          → RegisterResponse
  POST   /v1/capabilities/{registrationId}/heartbeat        → HeartbeatResponse
  ```

  The handlers **delegate to the same domain logic** the MCP tools use — extract a `RegistryQueryService` from T2 so MCP and REST both call it. Don't duplicate logic.

  All responses include `messages: []` per Rule 6 (use `buildJsonObject` with explicit `JsonArray(emptyList())` for the `messages` field on success cases).

  **Tests:** `api/RestRoutesSpec.kt` covers each endpoint — request → response JSON shape. Smaller cases than the MCP spec since logic is shared:

  ```kotlin
  "POST /v1/capabilities/search returns SearchResponse JSON" { /* ... */ }
  "GET /v1/capabilities/agents returns ListAgentsResponse JSON with empty messages array" { /* ... */ }
  "GET /v1/capabilities/{unknown_id} returns 200 with null capability" { /* ... */ }
  ```

  Acceptance: REST endpoints respond correctly; `just test-kt capabilities-mcp` still green.

- [x] **T4 — TTL pruning + version-suffix resolver.**

  Create `registry/TtlPruner.kt`:

  ```kotlin
  class TtlPruner(
      private val registry: InMemoryRegistry,
      private val ttl: Duration,
      private val clock: Clock,
      private val tickInterval: Duration = Duration.ofSeconds(30),
  ) {
      private var job: Job? = null

      fun start(scope: CoroutineScope) {
          job = scope.launch {
              while (isActive) {
                  delay(tickInterval.toKotlinDuration())
                  prune()
              }
          }
      }

      fun stop() { job?.cancel() }

      internal fun prune(): Int {
          val cutoff = Instant.now(clock).minus(ttl)
          return registry.markPrunedOlderThan(cutoff)  // returns count; entries stay fetchable via get()
      }
  }
  ```

  Add `markPrunedOlderThan(cutoff: Instant): Int` to `InMemoryRegistry` — entries with `lastHeartbeatAt != null && lastHeartbeatAt < cutoff` get a `pruned = true` flag in `RegistryEntry` (new field). Source-controlled fixtures (`lastHeartbeatAt == null`) are exempt.

  Update `list()` and `listAgents()` to filter out pruned entries unless `filter.includePruned == true`. `get()` returns pruned entries regardless (audit semantics).

  Create `registry/VersionResolver.kt`:

  ```kotlin
  object VersionResolver {
      // "model.fit.arima:v1" → ("model.fit.arima", "v1")
      // "model.fit.arima"    → ("model.fit.arima", null = latest)
      fun parse(id: String): Pair<String, String?> {
          val idx = id.lastIndexOf(':')
          return if (idx >= 0 && id.substring(idx + 1).startsWith("v")) {
              id.substring(0, idx) to id.substring(idx + 1)
          } else id to null
      }

      // Pick latest by lexicographic comparison of version suffix.
      fun resolveLatest(candidates: List<RegistryEntry>): RegistryEntry? =
          candidates.maxByOrNull {
              val ver = parse(it.capability.idOrThrow()).second ?: "v0"
              ver
          }
  }
  ```

  Wire VersionResolver into `InMemoryRegistry.get(id)`: if `id` has no `:vN` suffix, look up all entries whose base matches and return the latest.

  **Tests:**

  ```kotlin
  class TtlPrunerSpec : StringSpec({
      "entries past TTL are excluded from list()" { /* clock-advance + prune + assert filtered */ }
      "pruned entries remain fetchable via get()" { /* clock-advance + prune + get → entry returned */ }
      "fixtures (last_heartbeat_at == null) are never pruned" { /* register fromFixture=true; prune; still in list() */ }
  })

  class VersionResolverSpec : StringSpec({
      "parse strips :vN suffix" { VersionResolver.parse("model.fit.arima:v1") shouldBe ("model.fit.arima" to "v1") }
      "parse returns null version when suffix missing" { VersionResolver.parse("model.fit.arima") shouldBe ("model.fit.arima" to null) }
      "resolveLatest picks highest version" { /* register v1 + v2; get("model.fit.arima") returns v2 entry */ }
      "exact version request returns that version" { /* get("model.fit.arima:v1") returns v1 entry even if v2 exists */ }
  })
  ```

  Acceptance: both specs green; existing tests still green.

- [x] **T5 — Create `shared/libs/kotlin/capabilities-client` library.** _Published as `cz.tatrman:capabilities-client` to `maven.pkg.github.com/DFPartner/kantheon`._

  New module per [`architecture.md`](../../../architecture/themis/architecture.md) §3.1.

  Directory:

  ```
  shared/libs/kotlin/capabilities-client/
  ├── build.gradle.kts
  ├── src/main/kotlin/org/tatrman/kantheon/capabilities/client/
  │   ├── CapabilitiesClient.kt          # startupRegister + heartbeat scheduler
  │   ├── CapabilitiesReadClient.kt      # read-mostly with 30s TTL cache
  │   ├── CapabilitiesClientHandle.kt    # status + shutdown
  │   └── HeartbeatStatus.kt
  ├── src/test/kotlin/.../              # Wiremock-driven tests
  └── README.md
  ```

  Public API per [`contracts.md`](../../../architecture/themis/contracts.md) §4.1 — copy that snippet byte-for-byte into the Kotlin source.

  `build.gradle.kts`:

  ```kotlin
  plugins {
      `java-library`
      alias(libs.plugins.kotlin.jvm)
      `maven-publish`                              // publishable for cross-repo consumption
  }

  dependencies {
      api(project(":shared:proto"))
      implementation(libs.ktor.client.cio)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.opentelemetry.api)
      implementation(libs.kotlin.logging)

      testImplementation(libs.kotest.runner.junit5)
      testImplementation(libs.kotest.assertions.core)
      testImplementation(libs.wiremock)
      testImplementation(libs.kotlinx.coroutines.test)
  }

  publishing {
      publications {
          create<MavenPublication>("maven") {
              from(components["java"])
              groupId = "cz.tatrman"
              artifactId = "capabilities-client"
              version = project.version.toString()
          }
      }
      repositories {
          maven {
              name = "GitHubPackages"
              url = uri("https://maven.pkg.github.com/<org>/kantheon")
              credentials {
                  username = System.getenv("GITHUB_ACTOR") ?: ""
                  password = System.getenv("GITHUB_TOKEN") ?: ""
              }
          }
      }
  }
  ```

  Add to `settings.gradle.kts`: `include(":shared:libs:kotlin:capabilities-client")`.

  **Implementation details:**

  - `CapabilitiesClient.startupRegister(capability, endpoint, heartbeatIntervalMs, otelTracer)`:
    - POST `/v1/capabilities/register` with the capability JSON.
    - On success → schedule heartbeat coroutine: `delay(intervalMs); POST /v1/capabilities/{rid}/heartbeat`.
    - On register failure → exponential backoff (1s → 2s → 4s → ... cap 60s) **in background coroutine**; the call returns `CapabilitiesClientHandle` immediately with `registrationId = null` and `lastHeartbeatStatus = NEVER_REGISTERED`. **The service ALWAYS starts** — warn-and-continue.
    - On mid-flight heartbeat failure → `lastHeartbeatStatus = STALE`; keep retrying.

  - `CapabilitiesReadClient`:
    - `listAgents()` / `search(...)` / `get(id)` — cache responses by request hash for `cacheTtlMs` (default 30_000).
    - On cache miss + endpoint unreachable: throw `CapabilitiesUnreachableException`. Caller decides whether to fail-fast (Themis at boot) or warn-and-continue.

  Acceptance: module compiles; tests in T6 below.

- [x] **T6 — Wiremock-driven tests for `capabilities-client`.**

  `src/test/kotlin/.../CapabilitiesClientSpec.kt`:

  ```kotlin
  class CapabilitiesClientSpec : StringSpec({
      lateinit var wm: WireMockServer
      beforeTest { wm = WireMockServer(0); wm.start() }
      afterTest { wm.stop() }

      "startupRegister succeeds, schedules heartbeat" {
          wm.stubFor(post("/v1/capabilities/register").willReturn(okJson("""{"registrationId":"abc","messages":[]}""")))
          wm.stubFor(post("/v1/capabilities/abc/heartbeat").willReturn(okJson("""{"acceptedAt":"...","messages":[]}""")))

          val handle = CapabilitiesClient.startupRegister(
              capability = toolCapability("query.named:v1").asCapability(),
              endpoint = "http://localhost:${wm.port()}",
              heartbeatIntervalMs = 100,
          )

          eventually(2.seconds) {
              handle.registrationId shouldBe "abc"
              handle.lastHeartbeatStatus shouldBe HeartbeatStatus.OK
              wm.findAll(postRequestedFor(urlPathMatching("/v1/capabilities/abc/heartbeat"))).size shouldBeGreaterThan 0
          }
          handle.shutdown()
      }

      "startupRegister with unreachable endpoint warns-and-continues" {
          val handle = CapabilitiesClient.startupRegister(
              capability = toolCapability("query.named:v1").asCapability(),
              endpoint = "http://localhost:1",                  // unreachable
              heartbeatIntervalMs = 100,
          )

          // Service starts even though register failed.
          handle.lastHeartbeatStatus shouldBeIn listOf(HeartbeatStatus.NEVER_REGISTERED, HeartbeatStatus.FAILED)
          handle.shutdown()
      }

      "exponential backoff retries register on failure" {
          val scenario = "register-retry"
          wm.stubFor(post("/v1/capabilities/register").inScenario(scenario).whenScenarioStateIs(STARTED)
                    .willReturn(serverError()).willSetStateTo("attempt-2"))
          wm.stubFor(post("/v1/capabilities/register").inScenario(scenario).whenScenarioStateIs("attempt-2")
                    .willReturn(okJson("""{"registrationId":"abc","messages":[]}""")))

          val handle = CapabilitiesClient.startupRegister(...)
          eventually(5.seconds) { handle.registrationId shouldBe "abc" }
          handle.shutdown()
      }

      "CapabilitiesReadClient caches list_agents within TTL" {
          wm.stubFor(get("/v1/capabilities/agents").willReturn(okJson(/* one agent */)))
          val client = CapabilitiesReadClient("http://localhost:${wm.port()}", cacheTtlMs = 1000)
          client.listAgents()
          client.listAgents()
          wm.findAll(getRequestedFor(urlPathMatching("/v1/capabilities/agents"))).size shouldBe 1
      }

      "CapabilitiesReadClient invalidates cache after TTL" { /* clock-advance + re-query → 2 requests */ }
  })
  ```

  Acceptance: all CapabilitiesClient tests green; PR opened with title `[p1-s1.3] MCP surface + heartbeat client` and CI green.

## DONE — Stage 1.3

- [x] All six tasks above checked.
- [x] `just test-kt capabilities-mcp` and `just test-kt capabilities-client` both green (42 tests across modules, 0 failures, 2026-05-28).
- [ ] Full MCP tool surface answered (six tools, JSON shapes per `contracts.md` §2.1).
- [ ] REST mirror endpoints answered (`contracts.md` §2.2).
- [ ] TTL pruning + version resolver behave per `contracts.md` §1.1 + §3.4.
- [ ] `capabilities-client` warn-and-continue policy verified; cache TTL verified.
- [ ] PR merged.

## Library / pattern references

- **ai-platform `EXAMPLES.md` §3a + §3b + §3c + §3d** — full MCP server bootstrap, tool registration, safeMcpTool wrapper, McpJson serializer. **Cite section numbers in PR description.**
- **ai-platform `tools/query-mcp/src/main/kotlin/tools/querymcp/mcp/{McpTool.kt, ToolRegistry.kt, McpTransport.kt}`** — full reference implementation.
- **ai-platform `tools/fuzzy-mcp/src/main/kotlin/org/tatrman/fuzzy/mcp/Application.kt`** — single-tool inline variant, simpler.
- **Kotlin MCP SDK 0.12.0** at `~/Dev/view-only/kotlin-mcp-sdk/README.md` → "Creating a Server" + "Streamable HTTP Transport" sections.
- **MCP SDK `Server` + `addTool`** API — used by both `query-mcp` and the kantheon implementation; consult `~/Dev/view-only/kotlin-mcp-sdk/src/jvmMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/Server.kt` for the canonical signature.
- **Wiremock-Kotest** patterns — `/Users/bora/Dev/ai-platform/services/fuzzy-matcher/src/test/kotlin/` has examples.

## Out of scope for Stage 1.3

- YAML manifest loader (Stage 1.4).
- K8s manifests + deployment (Stage 1.4).
- ai-platform PoC heartbeat integration (Stage 1.4).
- Prometheus metrics — `capabilities_register_total{result}` etc. (Stage 1.4 task observability).
- Multi-tenant scoping / auth (v1.5+ per Out-of-scope list).
