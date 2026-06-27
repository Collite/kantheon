# Stage 1.2 — Capabilities proto + service skeleton

> **Phase 1, Stage 1.2.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.2, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.1 (capabilities/v1 proto) + §3 (manifest YAML schema), [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §7 (capabilities-mcp internal structure).

## Goal

`tools/capabilities-mcp` Ktor module exists, compiles, and exposes `/health` (200) and `/ready` (503 — no loader yet). Capabilities proto generated and Kotlin bindings compile. In-memory registry skeleton compiles with unit tests green for core CRUD ops.

## Pre-flight

- [ ] **Stage 1.1 DONE** — repo bootstrapped, CI green, `_smoke-test` builds and tests pass.
- [ ] **Branch**: `feat/p1-s1.2-capabilities-proto` from `main`.

## Tasks

- [x] **T1 — Write `capabilities.proto`.** _Drift: messages use `org.tatrman.kantheon.common.v1.ResponseMessage`, not `cz.dfpartner.common.v1.ResponseMessage`. ai-platform's `ResponseMessage` lives in `cz.dfpartner.metadata.v1` and pulls metadata-domain `ObjectRef`. A local stand-in (`shared/proto/.../common/v1/response_message.proto`) was created; swap when ai-platform extracts a domain-free common.v1._

  Create `shared/proto/src/main/proto/org/tatrman/kantheon/capabilities/v1/capabilities.proto`. Use the full proto definition from [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.1 byte-for-byte. Key points to verify:

  - Sealed `Capability` oneof with `ToolCapability tool = 1` and `AgentCapability agent = 2`.
  - `AgentCapability` carries ShemManifest-specific fields (`domain_name`, `domain_entities`, etc.) at field numbers 20+ — populated only when `agent_kind == DOMAIN_QA`.
  - `IntentKind` enum: `PROCEDURAL = 1`, `RCA = 2`, `FORECAST = 3`, `SIMULATION = 4`.
  - `AgentKind` enum: `INVESTIGATOR = 1` (Pythia), `DOMAIN_QA = 2` (Golems), `PERSONAL_ASSISTANT = 3` (future Hebe).
  - `HitlProfile` enum: `INTERACTIVE = 1`, `SPECULATIVE = 2`, `STRICT = 3`.
  - Every Response message ends with `repeated ResponseMessage messages = 99;` per ai-platform Rule 6.
  - Import `cz/dfpartner/common/v1/response_message.proto` from ai-platform Maven.

  Run `just proto`; verify Kotlin bindings appear under `shared/proto/build/generated/source/proto/main/kotlin/org/tatrman/kantheon/capabilities/v1/`.

  **Tests first:** add `shared/proto/src/test/kotlin/.../CapabilitiesProtoSpec.kt`:

  ```kotlin
  class CapabilitiesProtoSpec : StringSpec({
      "ToolCapability round-trips through proto" {
          val tool = ToolCapability.newBuilder()
              .setCapabilityId("model.fit.arima:v1")
              .setCategory("model.fit.*")
              .setVersion("v1")
              .setDescription("ARIMA forecast")
              .build()
          val bytes = tool.toByteArray()
          val parsed = ToolCapability.parseFrom(bytes)
          parsed.capabilityId shouldBe "model.fit.arima:v1"
      }

      "AgentCapability with ShemManifest fields populated only when DOMAIN_QA" {
          val golem = AgentCapability.newBuilder()
              .setAgentKind(AgentKind.DOMAIN_QA)
              .setAgentId("golem-erp")
              .setDomainName("ERP")
              .addDomainEntities("customer")
              .build()
          golem.agentKind shouldBe AgentKind.DOMAIN_QA
          golem.domainName shouldBe "ERP"
          golem.domainEntitiesList shouldContain "customer"
      }
  })
  ```

  Acceptance: proto compiles; tests green via `just test-kt shared:proto`.

- [x] **T2 — Create `tools/capabilities-mcp` module skeleton.** _Drift: `ai-platform.mcp-base` omitted — `cz.dfpartner:mcp-server-base` does not exist in ai-platform. MCP SDK still pulled in for Stage 1.3._

  Directory layout per [`architecture.md`](../../../architecture/themis/architecture.md) §3.1:

  ```
  tools/capabilities-mcp/
  ├── build.gradle.kts
  ├── src/main/kotlin/org/tatrman/kantheon/capabilities/
  │   ├── App.kt
  │   ├── api/
  │   │   └── HealthRoutes.kt
  │   ├── registry/
  │   ├── loader/
  │   └── observability/
  ├── src/main/resources/
  │   ├── application.conf
  │   └── manifests/
  │       ├── agents/   # empty for now
  │       └── tools/    # empty for now
  ├── src/test/kotlin/
  └── k8s/              # placeholder; populated in Stage 1.4
  ```

  `build.gradle.kts` — apply `kotlin.jvm` + `ktor` + `jib` from libs.versions.toml. Dependencies:

  ```kotlin
  dependencies {
      implementation(project(":shared:proto"))
      implementation(libs.ai.platform.ktor.config)      // installKtorServerBase, installMcpKtorBase
      implementation(libs.ai.platform.otel.config)
      implementation(libs.ai.platform.mcp.base)         // McpTool, ToolRegistry, safeMcpTool
      implementation(libs.ai.platform.logging)
      implementation(libs.kotlin.mcp.sdk)               // io.modelcontextprotocol:kotlin-sdk
      implementation(libs.ktor.server.cio)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.jackson.dataformat.yaml)      // YAML loader
      implementation(libs.kotlin.logging)

      testImplementation(libs.kotest.runner.junit5)
      testImplementation(libs.kotest.assertions.core)
      testImplementation(libs.ktor.server.test.host)
      testImplementation(libs.mockk)
  }
  ```

  Add `include(":tools:capabilities-mcp")` to root `settings.gradle.kts`.

  Acceptance: `./gradlew :tools:capabilities-mcp:build --dry-run` succeeds (resolves Maven coords without error).

- [x] **T3 — Tests-first for the in-memory registry.**

  Create `tools/capabilities-mcp/src/test/kotlin/org/tatrman/kantheon/capabilities/registry/InMemoryRegistrySpec.kt`. Write tests BEFORE implementing the registry. Cover:

  ```kotlin
  class InMemoryRegistrySpec : StringSpec({
      lateinit var reg: InMemoryRegistry

      beforeTest { reg = InMemoryRegistry(clock = Clock.fixed(...)) }

      "register tool returns stable registration_id" {
          val rid = reg.register(toolCapability("model.fit.arima:v1"))
          rid.shouldNotBeBlank()
          reg.get("model.fit.arima:v1")?.shouldBeToolWithId("model.fit.arima:v1")
      }

      "register is idempotent on capability_id (same id → same registration_id)" {
          val rid1 = reg.register(toolCapability("model.fit.arima:v1"))
          val rid2 = reg.register(toolCapability("model.fit.arima:v1", description = "updated"))
          rid1 shouldBe rid2
          reg.get("model.fit.arima:v1")?.tool?.description shouldBe "updated"
      }

      "list returns all entries, list_agents only agent entries" {
          reg.register(toolCapability("model.fit.arima:v1"))
          reg.register(agentCapability("pythia", AgentKind.INVESTIGATOR))
          reg.register(agentCapability("golem-erp", AgentKind.DOMAIN_QA))
          reg.list().entries shouldHaveSize 3
          reg.listAgents().agents.map { it.agentId } shouldContainExactlyInAnyOrder listOf("pythia", "golem-erp")
      }

      "get returns null for unknown id" {
          reg.get("unknown") shouldBe null
      }

      "register an AgentCapability with ShemManifest fields preserves them" {
          val shem = agentCapability("golem-erp", AgentKind.DOMAIN_QA) {
              domainName = "ERP"
              addAllDomainEntities(listOf("customer", "invoice"))
          }
          reg.register(shem)
          val got = reg.get("golem-erp")!!.agent
          got.domainName shouldBe "ERP"
          got.domainEntitiesList shouldContainAll listOf("customer", "invoice")
      }

      // TTL pruning and version handling have their own specs in Stage 1.3.
  })
  ```

  Helpers (e.g. `toolCapability(...)`, `agentCapability(...)`) live in `src/test/kotlin/.../Fixtures.kt`.

  Acceptance: tests written; **they fail** (no implementation yet). Commit at this point with message `[p1-s1.2] failing registry specs`.

- [x] **T4 — Implement `InMemoryRegistry`.**

  Create `tools/capabilities-mcp/src/main/kotlin/org/tatrman/kantheon/capabilities/registry/InMemoryRegistry.kt`:

  ```kotlin
  package org.tatrman.kantheon.capabilities.registry

  import org.tatrman.kantheon.capabilities.v1.*
  import java.time.Clock
  import java.time.Instant
  import java.util.UUID
  import java.util.concurrent.ConcurrentHashMap

  data class RegistryEntry(
      val capability: Capability,
      val registrationId: String,
      val lastHeartbeatAt: Instant?,    // null = source-controlled fixture
      val registeredAt: Instant,
  )

  class InMemoryRegistry(private val clock: Clock = Clock.systemUTC()) {

      private val byId = ConcurrentHashMap<String, RegistryEntry>()

      fun register(capability: Capability, fromFixture: Boolean = false): String {
          val id = capability.idOrThrow()
          val existing = byId[id]
          val registrationId = existing?.registrationId ?: UUID.randomUUID().toString()
          val now = Instant.now(clock)
          byId[id] = RegistryEntry(
              capability = capability,
              registrationId = registrationId,
              lastHeartbeatAt = if (fromFixture) null else now,
              registeredAt = existing?.registeredAt ?: now,
          )
          return registrationId
      }

      fun heartbeat(registrationId: String): Instant? {
          val entry = byId.values.firstOrNull { it.registrationId == registrationId } ?: return null
          val now = Instant.now(clock)
          byId[entry.capability.idOrThrow()] = entry.copy(lastHeartbeatAt = now)
          return now
      }

      fun get(id: String): RegistryEntry? = byId[id]

      fun list(filter: CapabilityFilter? = null): List<RegistryEntry> =
          byId.values.filter { it.matches(filter) }

      fun listAgents(filter: CapabilityFilter? = null): List<AgentCapability> =
          byId.values
              .filter { it.capability.hasAgent() && it.matches(filter) }
              .map { it.capability.agent }

      // ID resolution: ToolCapability uses capability_id, AgentCapability uses agent_id.
      private fun Capability.idOrThrow(): String = when {
          hasTool() -> tool.capabilityId
          hasAgent() -> agent.agentId
          else -> error("Capability must be either tool or agent")
      }

      private fun RegistryEntry.matches(filter: CapabilityFilter?): Boolean {
          if (filter == null) return true
          // includePruned/includeTools/includeAgents — full impl in Stage 1.3's TTL pruner.
          // For now, default-true on all three.
          return true
      }
  }
  ```

  Re-run the spec from T3. **All five tests pass.** Commit: `[p1-s1.2] registry green`.

  Acceptance: `just test-kt capabilities-mcp` green.

- [x] **T5 — Ktor `App.kt` + `/health` and `/ready` routes.**

  Create `tools/capabilities-mcp/src/main/kotlin/org/tatrman/kantheon/capabilities/App.kt` following ai-platform `EXAMPLES.md` §1b template (≤45 lines):

  ```kotlin
  package org.tatrman.kantheon.capabilities

  import com.typesafe.config.ConfigFactory
  import io.ktor.server.application.Application
  import io.ktor.server.cio.CIO
  import org.tatrman.kantheon.capabilities.api.healthRoutes
  import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
  import shared.ktor.installKtorServerBase
  import shared.ktor.KtorConfigFactory
  import shared.ktor.KtorEngine
  import shared.ktor.KtorServerBootstrap
  import shared.ktor.KtorServerConfig
  import shared.otel.createOpenTelemetrySdk
  import shared.otel.OtelEndpointConfig

  fun main() {
      val config = ConfigFactory.load()
      createOpenTelemetrySdk(
          OtelEndpointConfig(
              serviceName = "capabilities-mcp",
              protocol = System.getenv("CAPABILITIES_OTEL_PROTOCOL") ?: "grpc",
          ),
      )
      val serverConfig = KtorConfigFactory.fromConfig(
          config = config,
          defaultServiceName = "capabilities-mcp",
          defaultPort = 7501,
          engine = KtorEngine.CIO,
      )
      KtorServerBootstrap.createServer(serverConfig) { module(serverConfig) }.start(wait = true)
  }

  fun Application.module(serverConfig: KtorServerConfig) {
      installKtorServerBase(serverConfig)
      val registry = InMemoryRegistry()
      val readiness = ReadinessGate()       // toggled true after fixtures load (Stage 1.4)
      routing {
          healthRoutes(readiness)
      }
  }

  class ReadinessGate {
      @Volatile var ready: Boolean = false
  }
  ```

  Create `api/HealthRoutes.kt`:

  ```kotlin
  fun Route.healthRoutes(readiness: ReadinessGate) {
      get("/health") {
          call.respond(buildJsonObject { put("status", JsonPrimitive("ok")) })
      }
      get("/ready") {
          if (readiness.ready) {
              call.respond(buildJsonObject { put("status", JsonPrimitive("ready")) })
          } else {
              call.respond(HttpStatusCode.ServiceUnavailable,
                  buildJsonObject { put("status", JsonPrimitive("not-ready")) })
          }
      }
  }
  ```

  Create `src/main/resources/application.conf`:

  ```hocon
  ktor {
      deployment { port = 7501 }
      application { serviceName = "capabilities-mcp" }
  }
  capabilities {
      manifests-dir = "classpath:manifests"
      manifests-dir = ${?CAPABILITIES_MANIFESTS_DIR}
      ttl-seconds = 300
      ttl-seconds = ${?CAPABILITIES_TTL_SECONDS}
  }
  ```

  **Health tests:** create `src/test/kotlin/.../api/HealthSpec.kt`:

  ```kotlin
  class HealthSpec : StringSpec({
      "GET /health returns 200 ok" { /* testApplication ... shouldContain "ok" */ }
      "GET /ready returns 503 when not ready" { /* ... shouldBe ServiceUnavailable */ }
      "GET /ready returns 200 ok when readiness toggled" { /* toggle then assert 200 */ }
  })
  ```

  Acceptance: `just build-kt capabilities-mcp` produces a runnable JAR; `just test-kt capabilities-mcp` green.

- [x] **T6 — OTel + ktlint sanity.**

  - Confirm `createOpenTelemetrySdk(...)` is called before `start(wait = true)` per ai-platform `EXAMPLES.md` §8.
  - Run `just lint-all`; resolve any ktlint findings. Commit clean.
  - Add a `README.md` at `tools/capabilities-mcp/README.md` placeholder (full content lands in Stage 1.4):

    ```markdown
    # capabilities-mcp

    Unified registry of agent + tool capabilities for the Kantheon constellation.
    See [`docs/architecture/themis/architecture.md`](../../docs/architecture/themis/architecture.md) §7
    and [`docs/architecture/themis/contracts.md`](../../docs/architecture/themis/contracts.md) §1.1.

    Status: **Phase 1 in flight.** This README is filled in at Phase 1 Stage 1.4 close.
    ```

  Acceptance: `just lint-all` green; PR opened with title `[p1-s1.2] capabilities proto + skeleton`.

## DONE — Stage 1.2

- [x] All six tasks above checked.
- [x] `just test-kt capabilities-mcp` and `just test-kt proto` both green (15 tests, 0 failures, 2026-05-28).
- [x] `just build-kt capabilities-mcp` produces a runnable Jar (`tools/capabilities-mcp/build/libs/capabilities-mcp-0.0.0-SNAPSHOT.jar`).
- [ ] PR merged.

## Library / pattern references

- **ai-platform `EXAMPLES.md` §1b** — canonical `Application.kt` template (≤45 lines).
- **ai-platform `EXAMPLES.md` §2a** — `buildJsonObject` for `call.respond`, never `mapOf` (type-erasure trap).
- **ai-platform `EXAMPLES.md` §8** — OTel setup; `createOpenTelemetrySdk(...)` before Ktor start.
- **ai-platform `tools/query-mcp/src/main/kotlin/tools/querymcp/Application.kt`** — full reference for a deployed MCP service Application.kt.
- **Kotlin MCP SDK** at `~/Dev/view-only/kotlin-mcp-sdk/README.md` — quickstart for server-side; will become live in Stage 1.3.
- **kotest StringSpec** docs — `~/Dev/view-only/koog/agents/agents-test/` has Kotest usage examples.

## Out of scope for Stage 1.2

- TTL pruning (Stage 1.3).
- Version-suffix resolution (Stage 1.3).
- YAML manifest loader (Stage 1.4).
- MCP tool surface (Stage 1.3).
- REST mirror endpoints beyond `/health` + `/ready` (Stage 1.3).
- Heartbeat client lib (Stage 1.3).
- K8s manifests (Stage 1.4).
