# Kantheon — Canonical Code Examples

> **Status:** living document. Code here is the **canonical pattern** for the kantheon repo. When you write new code that mirrors one of these shapes, use the snippet verbatim — don't invent a variant.
>
> **Read with.** [`CLAUDE.md`](./CLAUDE.md) (architecture + planning), [`AGENTS.md`](./AGENTS.md) (tech stack + dev hints).
>
> **Mirrors.** This document mirrors the structure of [`/Users/bora/Dev/ai-platform/EXAMPLES.md`](../ai-platform/EXAMPLES.md). Where a pattern is identical to ai-platform's, this doc forwards to it rather than duplicating. Where kantheon has a different shape (Koog graph nodes, envelope blocks, capabilities-client, etc.), the canonical snippet lives here.

---

## Index

| §   | Topic                                                            | Mirrors ai-platform §  |
|-----|------------------------------------------------------------------|------------------------|
| 1   | Ktor service bootstrap (`App.kt`)                                | §1a, §1b               |
| 2   | MCP server — tool registration + `structuredContent`             | §2                     |
| 3   | Proto wiring — `argsJson` + `ResponseMessage messages = 99;`     | (kantheon-specific)    |
| 4   | `capabilities-client` — heartbeat + read-mostly cache            | (kantheon-specific)    |
| 5   | Koog graph node — typed input/output, OTel span, retry           | (kantheon-specific)    |
| 6   | Envelope assembly — `Block` types + Vega-Lite spec               | (kantheon-specific)    |
| 7   | Kotlin MCP client — calling an in-repo MCP tool                  | §2 (consumer side)     |
| 8   | OTel SDK init                                                    | §8                     |
| 9   | Kotest unit spec + Wiremock HTTP stub                            | (test patterns)        |
| 10  | K8s Kustomize manifest (base + local overlay)                    | (deployment)           |
| 11  | Vue FE — consuming the envelope (TS bindings, vega-embed)        | (kantheon-specific)    |

---

## 1. Ktor service bootstrap

Every Kotlin service has an `App.kt` that does only bootstrap. Business logic lives in `api/`, `domain/`, `infra/`.

### 1a. Minimal Ktor + OTel + logging — production shape

> **Forked shared libs use `shared.*` package roots, not `cz.dfpartner.*`** (renamed one-shot at fork landing; CLAUDE.md §4). `shared.ktor` = `ktor-configurator`, `shared.otel` = `otel-config`, `shared.logging` = `logging-config`. They are **in-repo Gradle modules** consumed as `project(":shared:libs:kotlin:<lib>")`, not Maven artifacts. The canonical live reference for the bootstrap below is any forked service `Application.kt` (e.g. `services/charon/.../Application.kt`).

```kotlin
// services/<svc>/src/main/kotlin/org/tatrman/kantheon/<svc>/Application.kt
package org.tatrman.kantheon.example

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()                                 // HOCON: application.conf
    val serverConfig = KtorConfigFactory.fromConfig(config, "example", 7400)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "example", 7400))

    // OTel SDK init: OTLP trace/metric/log exporters + the Logback OpenTelemetryAppender
    // bridge (all SLF4J logs → OTLP → Alloy → Loki). Bare-persona service.name.
    val otel = createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "example",
            protocol = System.getenv("EXAMPLE_OTEL_PROTOCOL") ?: "grpc",
        ),
    )

    routing {
        // installHealthRoutes(); installResolveRoutes(graph); ...
    }
}
```

This is the **45-line ceiling**: if `Application.kt` grows past that, factor wiring into a `Wiring.kt` and keep it declarative.

### 1b. `App.kt` for an MCP server — `capabilities-mcp`

```kotlin
// tools/capabilities-mcp/src/main/kotlin/org/tatrman/kantheon/capabilities/App.kt
package org.tatrman.kantheon.capabilities

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.routing.routing
import org.tatrman.kantheon.capabilities.api.capabilitiesRestRoutes
import org.tatrman.kantheon.capabilities.api.healthRoutes
import org.tatrman.kantheon.capabilities.api.installCapabilitiesMcp
import org.tatrman.kantheon.capabilities.loader.ManifestYamlLoader
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.capabilities.registry.TtlPruner
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(OtelEndpointConfig(serviceName = "capabilities-mcp"))
    val serverConfig = KtorConfigFactory.fromConfig(config, "capabilities-mcp", 7501, KtorEngine.CIO)
    KtorServerBootstrap.createServer(serverConfig) { module(serverConfig, config) }.start(wait = true)
}

fun Application.module(serverConfig: KtorServerConfig, config: Config) {
    installKtorServerBase(serverConfig)

    val registry = InMemoryRegistry()
    val service = RegistryQueryService(registry)
    val loader = ManifestYamlLoader(classpathBase = "/manifests")

    monitor.subscribe(ApplicationStarted) {                  // fixtures load off the readiness path
        backgroundScope.launch { loader.loadAll(registry) }  // bootstrap fixtures; runtime heartbeats supersede
    }
    TtlPruner(registry, ttl = /* from config */).start(backgroundScope)

    installCapabilitiesMcp(service)                          // mounts the six capabilities.* tools at POST /mcp
    routing {
        healthRoutes(readiness)
        capabilitiesRestRoutes(service)
    }
}
```

> **Pattern note.** `installCapabilitiesMcp` mounts the MCP transport via the Kotlin MCP SDK's `mcpStreamableHttp` (Streamable HTTP at `POST /mcp`) and registers the tool surface with `addTool`. There is **no `mcp-server-base` artifact** (it never existed — CLAUDE.md/AGENTS.md); the MCP/Ktor glue is the in-repo `shared.ktor.mcp`. Don't hand-roll the MCP wire.

---

## 2. MCP server — tool registration + `structuredContent`

MCP tools are declared directly with the Kotlin MCP SDK (`Server.addTool`) and mounted via `mcpStreamableHttp` — there is **no `mcp-server-base` artifact**. Every tool sets `structuredContent` per the MCP spec (and mirrors the same JSON into `content[0].text` for SDK clients that only read the text channel).

```kotlin
// tools/capabilities-mcp/src/main/kotlin/org/tatrman/kantheon/capabilities/api/McpRoutes.kt
package org.tatrman.kantheon.capabilities.api

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.ktor.server.application.Application
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.installCapabilitiesMcp(service: RegistryQueryService, path: String = "/mcp") {
    mcpStreamableHttp(path = path, enableDnsRebindingProtection = false) {
        Server(serverInfo = Implementation("capabilities-mcp", "0.1.0"), options = /* ... */).apply {
            addTool(
                name = "capabilities.search",
                description = "Search tool + agent capabilities by intent kind, entity type, or tag.",
                inputSchema = searchSchema(),               // hand-written JSON schema
            ) { req ->
                guarded("capabilities.search") {            // timeout + exception → isError result
                    val params = JsonAdapters.searchParamsFromJson(req.params.arguments ?: JsonObject(emptyMap()))
                    structured(
                        buildJsonObject {
                            put("entries", JsonArray(service.search(params).map { CapabilityJson.capabilityToJson(it) }))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }
            // capabilities.get / .register / .heartbeat / .list_agents / .list_tools ...
        }
    }
}

// shared result helpers (same file)
private fun structured(content: JsonObject): CallToolResult =
    CallToolResult(content = listOf(TextContent(content.toString())), structuredContent = content, isError = false)
```

**Conventions:**

- One `addTool(...)` block per tool. Tool name is the dotted capability id (`capabilities.search`, not `searchCapabilities`).
- `inputSchema` is hand-written JSON schema (the SDK does not generate it from a Kotlin type).
- Return via `structured(content)` — sets both `structuredContent` and a `TextContent` mirror.
- Wrap every callback in `guarded(toolName)` — it surfaces timeouts/exceptions as `isError = true` results (a `ResponseMessage` payload) instead of bubbling out of the transport. See §3.

### 2a. Ktor responses — `buildJsonObject`, never `respond(mapOf(...))`

**Rule: never `call.respond(mapOf(...))` (or any raw `Map`/`List`) from a Ktor route. Always `call.respond(buildJsonObject { ... })` (or a `@Serializable` type).**

A raw `Map<String, …>` has no compile-time `KSerializer`, so with `ContentNegotiation`
installed kotlinx-serialization cannot negotiate a representation and the route returns
**HTTP 406 Not Acceptable** — which silently turns into a failing `/health`/`/ready` probe
and a CrashLoopBackOff on the cluster. (It *appears* to work in services that don't install
`ContentNegotiation` because `respond` falls back to `toString()` — a latent trap: adding
`ContentNegotiation` later breaks every such route.)

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray   // list values
import kotlinx.serialization.json.add

// ✗ WRONG — 406 with ContentNegotiation
get("/ready") { call.respond(mapOf("status" to "UP", "connections" to ids)) }

// ✓ RIGHT
get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
get("/ready") {
    call.respond(
        buildJsonObject {
            put("status", "UP")                                       // String/Number/Boolean overloads
            putJsonArray("connections") { ids.forEach { add(it) } }   // list values
        },
    )
}
```

Health/readiness/status/JSON routes across every Kotlin+Ktor service and worker follow this
(`services/charon/…/Application.kt` is the reference). Plain-text endpoints (`/metrics`) use
`respondText(...)` and are unaffected.

---

## 3. Proto wiring — `argsJson` + `messages = 99;`

Two load-bearing rules inherited from ai-platform. Every proto in `shared/proto/src/main/proto/org/tatrman/kantheon/**` applies them.

### 3a. Response with `messages = 99;`

```proto
// shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto
syntax = "proto3";

package org.tatrman.kantheon.themis.v1;

import "org/tatrman/kantheon/common/v1/response_message.proto";
import "org/tatrman/kantheon/capabilities/v1/capabilities.proto";

message ResolveResponse {
  oneof outcome {
    Resolution                resolution             = 1;
    AwaitingClarification     awaiting_clarification = 2;
    RefusalWithGaps           refusal                = 3;
  }
  RoutingDecision             routing_decision       = 4;
  string                      trace_id               = 5;

  // Application-layer outcomes — warnings, hints, non-fatal errors. Field 99 reserved platform-wide.
  // Rule-6 type is the kantheon-local stand-in (CLAUDE.md §4), not ai-platform's cz.dfpartner.metadata.v1.
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
```

**Always 99.** Even when the response has no other fields. Future evolution stays clean.

### 3b. Function-call args as JSON string

Function calls (Resolver/Themis outputs binding to a target agent's domain registry; Pythia plan-node specs) ride the wire as `argsJson`:

```proto
// shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto (excerpt)
message FunctionCall {
  string capability_id = 1;   // e.g. "query.run:v1"
  string args_json     = 2;   // JSON object; camelCase keys; validated against ParamSpec at call site
}
```

Kotlin side — emit and consume via `kotlinx.serialization.json`:

```kotlin
// agents/themis/src/main/kotlin/org/tatrman/kantheon/themis/koog/nodes/JointInferenceNode.kt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

fun emitFunctionCall(capabilityId: String, args: JsonObject): FunctionCall =
    FunctionCall.newBuilder()
        .setCapabilityId(capabilityId)
        .setArgsJson(json.encodeToString(JsonObject.serializer(), args))
        .build()

fun parseFunctionCallArgs(call: FunctionCall): JsonObject =
    json.decodeFromString(JsonObject.serializer(), call.argsJson)
```

> **Why not `google.protobuf.Struct`?** Awkward across JVM/Python/TS bindings, duplicates validation that already lives caller-side in the `ParamSpec` schema. JSON-string round-trips cleanly.

---

## 4. `capabilities-client` — heartbeat + read-mostly cache

`shared/libs/kotlin/capabilities-client` is the single source of cache + TTL + fail-fast logic. Used by Themis (heavy reader), Pythia (cross-domain plan context), Iris-BFF (display names), Golem (self-register on startup).

### 4a. Read-mostly client — Themis at boot + on-demand

```kotlin
// shared/libs/kotlin/capabilities-client/src/main/kotlin/org/tatrman/kantheon/capabilities/client/CapabilitiesClient.kt
package org.tatrman.kantheon.capabilities.client

import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CapabilitiesClient(
    private val mcp: McpClient,
    otel: OpenTelemetry,
    private val ttl: Duration = 60.seconds,
    private val failFastOnEmpty: Boolean = true,
) {
    private val mutex = Mutex()
    private var cache: List<AgentCapability> = emptyList()
    private var loadedAt: Long = 0

    /** Called once during App.kt boot. Throws if the registry is empty and `failFastOnEmpty == true`. */
    suspend fun primeAtBoot() {
        val agents = mcp.callTool("list_agents", emptyMap()).agents
        if (agents.isEmpty() && failFastOnEmpty) {
            error("capabilities-mcp returned no agents at boot — refusing to start")
        }
        cache = agents
        loadedAt = System.currentTimeMillis()
    }

    suspend fun listAgents(): List<AgentCapability> = mutex.withLock {
        if (System.currentTimeMillis() - loadedAt > ttl.inWholeMilliseconds) {
            refresh()
        }
        cache
    }

    private suspend fun refresh() {
        // OTel span; emits `themis_capabilities_cache_age_seconds` gauge as side effect.
        cache = mcp.callTool("list_agents", emptyMap()).agents
        loadedAt = System.currentTimeMillis()
    }
}
```

**Invariants:**

- **Fail-fast at boot** on empty registry (Themis must not silently degrade routing to "no agents → refuse everything").
- **TTL + lazy refresh** in the read path; no background poller (keeps the client transparent in tests).
- **Single mutex** guards the cache — last-writer-wins on a stale read is fine; correctness for stronger guarantees lives server-side.

### 4b. Self-registration — Golem at boot

```kotlin
// agents/golem/src/main/kotlin/org/tatrman/kantheon/golem/Wiring.kt (excerpt)
suspend fun registerSelfWith(mcp: McpClient, shem: ShemManifest) {
    val agent = AgentCapability.newBuilder()
        .setAgentKind(AgentKind.DOMAIN_QA)
        .setAgentId(shem.agentId)
        .setDisplayName(shem.displayName)
        .addAllIntentKindsSupported(shem.intentKindsSupported)
        .setDescriptionForRouter(shem.descriptionForRouter)
        // ... ShemManifest-specific fields ...
        .build()

    runCatching {
        mcp.callTool("register", mapOf("agent" to agent.toJson()))
    }.onFailure { e ->
        log.warn("capabilities-mcp unreachable at boot; continuing (will retry on heartbeat). cause={}", e.message)
    }
}
```

> **Warn-and-continue.** Per the platform-wide cross-repo rule, kantheon outage must not cascade into platform outage; ai-platform tools follow the same shape when they register against kantheon's `capabilities-mcp`.

---

## 5. Koog graph node — typed input/output, OTel span, retry

Koog nodes live under `agents/<agent>/src/main/kotlin/org/tatrman/kantheon/<agent>/koog/nodes/`. One node per file.

```kotlin
// agents/themis/src/main/kotlin/org/tatrman/kantheon/themis/koog/nodes/JointInferenceNode.kt
package org.tatrman.kantheon.themis.koog.nodes

import ai.koog.agents.core.tools.StructureFixingParser
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import org.tatrman.kantheon.themis.koog.ThemisState
import org.tatrman.kantheon.themis.infra.LlmGatewayClient

/** FAST-tier LLM call: combines fuzzy candidates + intent + entity bindings into a structured Resolution. */
class JointInferenceNode(
    private val llm: LlmGatewayClient,
    otel: OpenTelemetry,
) {
    private val tracer: Tracer = otel.getTracer("themis.koog")
    private val parser = StructureFixingParser(
        targetSchema = JointInferenceOutput.jsonSchema,
        maxRepairAttempts = 2,
    )

    suspend fun run(state: ThemisState): JointInferenceOutput {
        val span = tracer.spanBuilder("jointInference").startSpan()
        return try {
            val prompt = JointInferencePrompt.render(state)        // loads from prompts/joint-inference.md
            val raw = llm.complete(tier = "FAST", prompt = prompt) // retries baked into the client
            parser.parse(raw)                                       // throws on unrepairable schema mismatch
        } finally {
            span.end()
        }
    }
}
```

**Conventions:**

- One node = one file = one class. Constructor takes its dependencies (no service-locator).
- `StructureFixingParser` from Koog wraps the LLM call with retry + repair against a target JSON schema. Pythia and Golem use the same pattern for structured outputs.
- OTel span name = node name. Don't add extra spans inside a node — the node *is* the span.
- Prompts externalised to `prompts/<node-name>.md` and loaded by a thin `*Prompt.render(state)` helper.

> **Reference.** Locally-cloned Koog at `~/Dev/view-only/koog`. Use `graphify-out/` to find current API names (`StructureFixingParser`, `AIAgentStrategy`, `ToolDescriptor`).

---

## 6. Envelope assembly — `Block` types + Vega-Lite spec

`shared/libs/kotlin/envelope-render` builds `envelope/v1.FormatEnvelope`s server-side. Pythia, Golem, and (occasionally) Themis use it for ad-hoc rendering.

```kotlin
// shared/libs/kotlin/envelope-render/src/main/kotlin/org/tatrman/kantheon/envelope/render/EnvelopeBuilder.kt
package org.tatrman.kantheon.envelope.render

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.ChartIntent
import org.tatrman.kantheon.envelope.v1.FormatEnvelope

fun lineChartBlock(intent: ChartIntent, data: List<DataPoint>): Block {
    val spec = buildJsonObject {
        put("\$schema", "https://vega.github.io/schema/vega-lite/v5.json")
        put("mark", "line")
        put("encoding", buildJsonObject {
            put("x", buildJsonObject { put("field", intent.xField); put("type", "temporal") })
            put("y", buildJsonObject { put("field", intent.yField); put("type", "quantitative") })
        })
        put("data", buildJsonObject {
            put("values", data.toJsonArray())
        })
    }
    return Block.newBuilder()
        .setChart(Block.Chart.newBuilder()
            .setVegaLiteSpec(spec.toString())
            .setIntent(intent))
        .build()
}

fun textBlock(markdown: String): Block =
    Block.newBuilder().setText(Block.Text.newBuilder().setMarkdown(markdown)).build()

fun envelopeOf(vararg blocks: Block): FormatEnvelope =
    FormatEnvelope.newBuilder().addAllBlocks(blocks.toList()).build()
```

**Header inference + retry + deterministic-fallback patterns** for `RenderTable` / `RenderChart` ports the G-21 through G-25 lessons from the current Python Golem. Each `Render*` helper has a `*Spec` colocated with it that documents the gotcha.

---

## 7. Kotlin MCP client — calling an in-repo MCP tool

Post-fork, kantheon agents call the read-spine MCPs — `ttr-nlp-mcp` (NLP), `ttr-fuzzy-mcp` (fuzzy), `ttr-query-mcp` (`run_query`, capability id `query.run:v1`), `ttr-meta-mcp` (model graph) — never ai-platform's old `nlp-mcp`/`fuzzy-mcp`/`query-mcp`/`metadata-mcp`. These services were extracted from kantheon to the open-source **tatrman-server** repo (SV-P0/P1, 2026-07), so the call is now cross-repo; the consumer (Themis) stays in kantheon. Protos are the extracted packages (`org.tatrman.nlp.v1`, `org.tatrman.fuzzy.v1`, `org.tatrman.query.v1`, `org.tatrman.meta.v1`). Use the SDK client through a thin wrapper that enforces traceparent propagation and adds OTel spans.

```kotlin
// agents/themis/src/main/kotlin/org/tatrman/kantheon/themis/infra/NlpMcpClient.kt
package org.tatrman.kantheon.themis.infra

import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import io.modelcontextprotocol.kotlin.sdk.client.McpClient
import io.opentelemetry.api.OpenTelemetry

class NlpMcpClient(private val mcp: McpClient, otel: OpenTelemetry) {
    private val tracer = otel.getTracer("themis.nlp")

    suspend fun analyze(req: AnalyzeRequest): AnalyzeResponse {
        val span = tracer.spanBuilder("ttr-nlp-mcp.analyze").startSpan()
        return try {
            val result = mcp.callTool(
                name = "nlp.analyze",
                args = mapOf("request" to req.toJson()),    // generated proto-JSON
            )
            AnalyzeResponse.parseFromJson(result.structuredContent)
        } finally {
            span.end()
        }
    }
}
```

**Hardened patterns:**

- Read `ops` from `result.structuredContent["ops"]` as a **JsonArray**, not a JsonObject — `nlp.analyze`'s shape is `repeated Op ops = ...`. (The G4 bug carried over from ai-platform's nlp-mcp had the *server* getting this wrong; clients have always read it as an array. See `aip_v1_gap_closure` memory.)
- Always propagate `traceparent` — the SDK client does this automatically when the surrounding span is active.

---

## 8. OTel SDK init

The factory lives in the **in-repo** `shared.otel` (`shared/libs/kotlin/otel-config`, forked Phase 1):

```kotlin
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

val otel = createOpenTelemetrySdk(
    OtelEndpointConfig(
        serviceName = "ttr-query",                // bare service name — NOT "kantheon-ttr-query"
        protocol = System.getenv("TTR_QUERY_OTEL_PROTOCOL") ?: "grpc",
    ),
    // enabled = config.getBoolean("telemetry.enabled")   // optional gate
)
```

This wires:

- OTLP exporter (grpc/http per `protocol`) to the collector at `OTEL_EXPORTER_OTLP_ENDPOINT` (defaults to local Alloy).
- Metric / trace / log providers, plus the Logback `OpenTelemetryAppender` bridge (SLF4J → OTLP → Loki).
- Returns an `OpenTelemetry` instance you pass into clients, Koog nodes, registries.

**Coroutine-safe manual spans (Stage 4.1 T3).** For orchestration spans across `suspend` / `Flow` seams, use the in-repo helpers in `shared.otel` — they propagate context via `asContextElement()` (and `flowOn` for flows, so the Flow invariant holds):

```kotlin
import shared.otel.withSpan
import shared.otel.tracedFlow

suspend fun translate(req: R): T = tracer.withSpan("ttr-query.translate", SpanKind.CLIENT) { client.translate(req) }
fun run(req: R): Flow<E> = inner.tracedFlow(tracer, "ttr-query.run")     // NOT withContext around emit
```

Leaf gRPC services rely on auto-instrumentation; add manual spans only at orchestration seams (ttr-query-mcp tool boundary + ttr-query). Don't roll your own SDK init. See [`docs/architecture/fork/observability.md`](./docs/architecture/fork/observability.md).

---

## 9. Kotest unit spec + Wiremock HTTP stub

Test class naming: `<ProductionClassName>Spec`. Kotest `StringSpec` for unit tests; `FunSpec` only when the structure benefits from `describe` / `context`.

```kotlin
// tools/capabilities-mcp/src/test/kotlin/org/tatrman/kantheon/capabilities/registry/InMemoryRegistrySpec.kt
package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind

class InMemoryRegistrySpec : StringSpec({

    "register stores an agent under its agent_id" {
        val registry = InMemoryRegistry(otel = otelNoop)
        val agent = AgentCapability.newBuilder()
            .setAgentId("pythia")
            .setAgentKind(AgentKind.AGENT)
            .build()

        registry.register(agent)

        registry.getAgent("pythia") shouldBe agent
    }

    "idempotent re-register updates last_heartbeat_at without duplicating" {
        val registry = InMemoryRegistry(otel = otelNoop)
        val a1 = agentFixture("pythia")
        registry.register(a1)
        registry.register(a1.toBuilder().setDisplayName("Pythia v2").build())

        registry.listAgents() shouldHaveSize 1
        registry.getAgent("pythia").displayName shouldBe "Pythia v2"
    }
})
```

Wiremock for HTTP stubs in component tests:

```kotlin
// agents/themis/src/test/kotlin/org/tatrman/kantheon/themis/infra/NlpMcpClientSpec.kt
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import io.kotest.core.spec.style.StringSpec

class NlpMcpClientSpec : StringSpec({
    val server = WireMockServer(0)         // random port

    beforeSpec { server.start() }
    afterSpec  { server.stop() }
    afterTest  { server.resetAll() }

    "analyze forwards request and parses ops as JsonArray" {
        server.stubFor(post(urlEqualTo("/mcp/analyze"))
            .willReturn(okJson(loadFixture("nlp/analyze-cs-success.json"))))

        val client = NlpMcpClient(McpClient.http("http://localhost:${server.port()}"), otelNoop)
        val resp = client.analyze(AnalyzeRequest.newBuilder().setText("Kolik objednávek?").build())

        resp.opsList shouldHaveSize 3
        resp.opsList[0].kind.name shouldBe "TOKENIZE"
    }
})
```

Fixtures live at `src/test/resources/fixtures/<service>/<scenario>.json`. One stub per fixture.

---

## 10. K8s Kustomize manifest (base + local overlay)

Each deployable service ships with `k8s/{base,overlays/local}/` Kustomize manifests. Local overlay uses `imagePullPolicy: Never` so Jib-loaded images don't trigger a registry pull on K3s.

```yaml
# tools/capabilities-mcp/k8s/base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: capabilities-mcp
  namespace: kantheon
spec:
  replicas: 1
  selector: { matchLabels: { app: capabilities-mcp } }
  template:
    metadata:
      labels: { app: capabilities-mcp }
    spec:
      containers:
        - name: capabilities-mcp
          image: capabilities-mcp:dev               # overridden per-overlay
          ports: [{ containerPort: 8080 }]
          env:
            - { name: OTEL_EXPORTER_OTLP_ENDPOINT, value: "http://alloy.observability.svc:4317" }
          readinessProbe:
            httpGet: { path: /ready, port: 8080 }
            initialDelaySeconds: 2
            periodSeconds: 2
          livenessProbe:
            httpGet: { path: /health, port: 8080 }
            initialDelaySeconds: 10
```

```yaml
# tools/capabilities-mcp/k8s/overlays/local/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: kantheon
resources:
  - ../../base
patches:
  - target: { kind: Deployment, name: capabilities-mcp }
    patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/imagePullPolicy
        value: Never
      - op: replace
        path: /spec/template/spec/containers/0/image
        value: capabilities-mcp:local
```

Deploy via `just deploy-kt capabilities-mcp` — under the hood this runs `gradle jibDockerBuild && kubectl apply -k k8s/overlays/local`.

---

## 11. Vue FE — consuming the envelope (TS bindings, vega-embed)

`shared/libs/ts/envelope-ts` ships generated TypeScript bindings for `envelope/v1` plus a hand-written `FormatRenderer` helper.

```ts
// frontends/iris/src/components/BlockRenderer.vue (excerpt)
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import vegaEmbed from 'vega-embed';
import type { Block } from '@kantheon/envelope-ts';

const props = defineProps<{ block: Block }>();
const chartContainer = ref<HTMLDivElement | null>(null);

const kind = computed(() => {
  if (props.block.text)  return 'text';
  if (props.block.table) return 'table';
  if (props.block.chart) return 'chart';
  return 'unknown';
});

onMounted(async () => {
  if (props.block.chart && chartContainer.value) {
    const spec = JSON.parse(props.block.chart.vegaLiteSpec);
    await vegaEmbed(chartContainer.value, spec, { actions: false });
  }
});
</script>

<template>
  <div class="block">
    <MarkdownText  v-if="kind === 'text'"  :markdown="block.text!.markdown" />
    <TableRenderer v-else-if="kind === 'table'" :table="block.table!" />
    <div v-else-if="kind === 'chart'" ref="chartContainer" class="chart" />
    <UnknownBlock  v-else :block="block" />
  </div>
</template>
```

**Conventions:**

- One Vue component per `Block` variant. Dispatch in `BlockRenderer.vue`.
- Vega-Lite specs come **fully assembled** from the server — the FE does not template them. The FE may add interaction (zoom, hover) via `vegaEmbed` options, never modify the data or marks.
- TS bindings regenerate via `just proto` — never edit by hand.

---

## 12. When the snippet you need isn't here

1. Check [`ai-platform/EXAMPLES.md`](../ai-platform/EXAMPLES.md) — for Ktor / serialization / MCP / OTel / Calcite / Spring patterns kantheon inherits, that doc is canonical.
2. Check the locally-cloned library under `~/Dev/view-only/{koog,calcite,kotlin-mcp-sdk}` — query `graphify-out/` for symbol graphs.
3. Check the Themis-arc architecture doc ([`docs/architecture/themis/architecture.md`](./docs/architecture/themis/architecture.md)) for service-layout precedent.
4. If you found a new canonical pattern while implementing, **add it here** with a section number. Citing `EXAMPLES.md §N` in task lists is the standard way to anchor task instructions.

---

*Doc owner: Bora. Add a section whenever a recurring pattern crystallises; don't let the doc go stale by accreting one-off snippets.*
