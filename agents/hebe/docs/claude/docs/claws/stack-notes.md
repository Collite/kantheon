# Kotlin/JVM stack notes for koklyp

Research notes on the Kotlin ecosystem readiness as of April 2026 for the libraries the project will lean on. Sourced from web research + library docs; not from running the code.

## Verdict up front

**Target: JVM (not Kotlin/Native).** Two independent constraints force this:

1. **Wasmtime/component-model is not viable on Kotlin/Native.** The mature WASM hosts on JVM are Chicory (pure-Java interpreter) and Wasmtime via JNI. Both require a JVM. Kotlin/Native has no equivalent in 2026.
2. **Koog targets JVM, JS, WasmJS, Android, iOS — not Native.** The `Native` Kotlin/Native target is absent from koog's published platforms. Using koog forces JVM.

Even if either constraint were lifted, the rest of the stack (Ktor server, Slack/Telegram SDKs, Postgres drivers, JavaMail) is JVM-native and would need parallel ports. Native is a no-go.

GraalVM native-image *for distribution* (single binary) is a separate question, addressed at the bottom — viable, but not necessary for v1.

## Kotlin 2.3.x

Kotlin 2.3.x as specified in `req.md`: as of April 2026, Kotlin's release cadence has 2.2.x as the recent stable; 2.3.x is plausible by mid-2026 but the user may be slightly ahead of the release. Recommendation: **target the latest stable** (2.2.x) for the project skeleton; bump to 2.3.x once GA. Most libraries below ship for 2.x; minor differences are negligible.

## Library shortlist

| Concern | Library | Version (~Apr 2026) | Why |
|---|---|---|---|
| Agent orchestration | **JetBrains koog** | latest (active dev as of Mar 2026) | Official, JVM-first, history compression, agent persistence, Java interop, Ktor integration |
| HTTP server / client | **Ktor 3.x** | 3.x stable | Coroutines-native, Kotlin-idiomatic, official, integrates with koog |
| Serialization | **kotlinx.serialization** | 1.x | Standard for Kotlin |
| MCP server/client | **MCP Kotlin SDK** | latest | Official, JetBrains-maintained; multiplatform (JVM/Native/JS/Wasm); Ktor extensions for SSE + WebSocket transports |
| WASM plugin host | **Extism Java SDK** *or* **Chicory** | Chicory v1.x; Extism Java SDK GA, Chicory-backed in finalisation | See section below |
| Database | **SQLite via JDBC (Xerial)** *or* **PostgreSQL via pgvector** | both stable | SQLite as default; PG as opt-in for shared deployments |
| FTS5 + vector | SQLite FTS5 + sqlite-vec | sqlite-vec 0.x | FTS5 ships with SQLite; sqlite-vec is the de-facto SQLite vector extension |
| Slack | **slack-bolt** Kotlin DSL | check current; bolt-jvm is the official path | First-party Slack SDK, Java/Kotlin |
| Telegram | **kotlin-telegram-bot** or **TelegramBots (Java)** | both maintained | Pick whichever matches token + webhook flow |
| WhatsApp | **Cloud API** via Ktor client | n/a (just HTTP) | No SDK needed; raw Cloud API |
| Email | **Jakarta Mail** | latest | Mature; SMTP/IMAP. JavaMail's successor |
| Logging | **kotlin-logging** + SLF4J | latest | Standard |
| Coroutines | **kotlinx.coroutines** | 1.x | Standard |

## Question-by-question

### 1. Wasmtime on JVM

**Mature options in 2026:**

- **Chicory (Dylibso)** — pure-Java WASM interpreter, zero native deps, runs on any JVM. WASI Preview 1 supported. **WASI Preview 2 / component model is in development** but not stable. Active project; corporate backing from Dylibso. Best fit if you want zero JNI/native deps and are willing to live without component-model types.
- **Extism Java SDK** — wraps Chicory or a native runtime. Higher-level: plugin protocol (manifest + permissions + host functions) on top of WASM. This is what zeroclaw uses (in Rust); a JVM port is a near-trivial migration.
- **wasmtime-java** — JNI bindings to native Wasmtime. Heavier deploy (carries the native lib), but full Wasmtime feature set including component model. Less popular than Chicory in JVM communities.

**Verdict: pick Extism (running on Chicory).** Reasons:

- Component-model isn't available on JVM yet in production. Even if you wanted ironclaw-style WIT-typed plugins, you'd be paving the road.
- Extism's JSON-string in/out plugin protocol is straightforward for kotlinx.serialization.
- Chicory ships with no native deps, so the koklyp binary stays portable.
- Permissions/host-functions model in Extism mirrors the capability story you'd build anyway.
- Trade-off: lower expressivity (no rich WIT types) — accept it. The win in operational simplicity is huge.

For *future* component-model support, watch Chicory's WASIp2 work; flip the switch when stable.

### 2. Kotlin/Native viability

Confirmed **NOT VIABLE** for the koklyp use case:

- No mature WASM host (Chicory is JVM-only; Wasmtime via JNI is JVM-only).
- Koog does not list Native as a published target.
- Ktor server runs on JVM (Ktor client has Native targets, but server doesn't).
- Most Slack/Telegram/email SDKs are JVM-native.

Even Kotlin/Wasm (compile *Kotlin to* WASM) is still **beta** in 2026 with WASI target not recommended for production server workloads.

### 3. Koog (JetBrains)

- **Status:** Apache 2.0, active development, recent comprehensive Java-interop work as of March 2026.
- **Targets:** JVM, JS, WasmJS, Android, iOS via Kotlin Multiplatform — *not* Kotlin/Native.
- **Capabilities:** agent runtime with reliability features (retries, agent persistence to restore state at specific points), intelligent history compression, OpenTelemetry exporters. Ktor and Spring Boot integrations.
- **Multi-agent:** supports orchestrating multiple agents from one process (verify exact API in docs.koog.ai).
- **Tool/skill model:** koog has its own tool definition shape; aligns naturally with MCP. The skill format is library-internal — to interop with agentskills.io markdown bundles, write an adapter.
- **Memory:** koog's memory abstractions exist but are basic. Pair with a separate memory layer (zeroclaw-style `Memory` trait) and adapt at the boundary.
- **Production-readiness:** "enterprise-ready" per JetBrains. v1.0 timing unclear — verify before locking in.

**Verdict: use koog for the orchestration/agent-loop layer.** Gives you streaming, history compression, retries, persistence, OpenTelemetry for free. Wrap it behind a koklyp-native facade so you can swap if needed.

### 4. GraalVM native-image for single-binary deployment

Possible but not required for v1. Considerations:

- Ktor server compiles to native-image with some effort (reflection config).
- koog has Kotlin Multiplatform targets but native-image AOT is a different axis. JetBrains have not advertised native-image readiness; expect to do reflection-config work.
- Chicory + Extism have native-image build instructions on their docs.
- JNI dependencies (Wasmtime native lib if you go that route) complicate native-image significantly.

For koklyp v1: **ship a JAR + JVM**. Add native-image as a follow-up optimization. The 50-100MB JVM bundle vs 10-20MB native binary matters for distribution but is invisible to a self-hosted small-team setup.

### 5. Kotlin 2.3.x

`req.md` specifies 2.3.x. As of April 2026 the latest GA Kotlin minor is 2.2.x. 2.3.x is likely mid-2026. Recommendation:
- Start the project on **Kotlin 2.2.x** (latest stable).
- Bump to 2.3.x when it ships; the change is usually a one-line gradle bump for projects in this shape.

### 6. MCP Kotlin SDK

`io.modelcontextprotocol:kotlin-sdk:<version>`. Official, JetBrains-collaborated, multiplatform.

- **Transports:** stdio (CLI/editor bridges), SSE + POST back-channel via Ktor extension `mcp`, WebSocket via Ktor extension `mcpWebSocket`, Streamable HTTP.
- **Server-side primitives:** tools, prompts, resources.
- **Client-side primitives:** can connect to any MCP server.

**Verdict: ship MCP support from day one.** Both directions:

- koklyp-as-MCP-server: expose koklyp's built-in tools via MCP so Claude Desktop / IDEs / other agents can call them.
- koklyp-as-MCP-client: import third-party MCP servers as tool sources. Mirror zeroclaw's `Always`/`Dynamic` filtering pattern to keep prompt token usage sane.

### 7. Channel libraries

| Channel | Library | Status |
|---|---|---|
| Slack | bolt-jvm (Kotlin DSL on top of bolt-java) | Maintained, official, supports Events API + Socket Mode |
| Telegram | TelegramBots (Java) — works fine from Kotlin; or kotlin-telegram-bot | Both active; pick by API ergonomics |
| WhatsApp | No SDK; use Cloud API directly via Ktor HTTP client | n/a — just HTTP+JSON |
| Email | Jakarta Mail (formerly JavaMail) | Mature; SMTP/IMAP/IDLE for push |

For WhatsApp specifically: the Cloud API is Meta's hosted gateway, requiring a verified business account. There's no maintained pure-Kotlin SDK; raw Ktor + JSON is the right path. Cf. `whatsapp-cloud-api-java` if a Java helper is desired.

### 8. Persistence

- **SQLite** via Xerial JDBC + sqlite-vec extension for vector search + FTS5 (built-in). Single file at `~/.koklyp/koklyp.db`. Default for v1.
- **PostgreSQL** via JDBC + pgvector for shared/multi-user deployments. Opt-in.
- **Migrations:** Flyway or Liquibase; both are JVM-standard. Flyway is simpler.

Avoid Exposed for the persistence DSL unless you really want it; raw JDBC + a lightweight wrapper is cleaner for an agent that mostly does append-only writes.

## Recommended dependency shortlist (Gradle)

```kotlin
dependencies {
    // Core agent
    implementation("ai.koog:koog-core:<latest>")
    implementation("ai.koog:koog-ktor:<latest>")              // or koog-spring-boot

    // Server
    implementation("io.ktor:ktor-server-core:3.x")
    implementation("io.ktor:ktor-server-netty:3.x")
    implementation("io.ktor:ktor-server-content-negotiation:3.x")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.x")
    implementation("io.ktor:ktor-server-websockets:3.x")
    implementation("io.ktor:ktor-server-sse:3.x")

    // HTTP client (for outbound)
    implementation("io.ktor:ktor-client-core:3.x")
    implementation("io.ktor:ktor-client-cio:3.x")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.x")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.x")

    // MCP
    implementation("io.modelcontextprotocol:kotlin-sdk:<latest>")

    // WASM plugin host
    implementation("org.extism.sdk:extism:<latest>")
    // OR
    implementation("com.dylibso.chicory:runtime:<latest>")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:<latest>")
    // sqlite-vec extension — load via JDBC URL or via a small loader

    // Postgres (optional)
    implementation("org.postgresql:postgresql:<latest>")
    // pgvector via Hibernate or hand-rolled

    // Slack
    implementation("com.slack.api:bolt:<latest>")
    implementation("com.slack.api:bolt-jetty:<latest>")        // or socket-mode

    // Telegram
    implementation("org.telegram:telegrambots:<latest>")

    // Email
    implementation("org.eclipse.angus:angus-mail:<latest>")    // Jakarta Mail impl

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:<latest>")
    implementation("ch.qos.logback:logback-classic:<latest>")

    // Crypto for tool receipts (Ed25519)
    implementation("org.bouncycastle:bcprov-jdk18on:<latest>")

    // Migrations
    implementation("org.flywaydb:flyway-core:<latest>")
}
```

## Top 3 risks

1. **Koog is young.** v1.0 may not have shipped by the time koklyp needs to lock in. Mitigation: wrap koog in a koklyp-native facade — `LlmAgent`, `LlmTool`, etc. so swapping out is at most a week's work. Don't let koog APIs leak into channel/tool code.

2. **WASI Preview 2 / component model is not yet stable on JVM.** Chicory's preview-2 support is in development. If you want ironclaw-style WIT-typed plugins, you're either waiting for Chicory or carrying a native Wasmtime dep. Mitigation: adopt **Extism (WASI Preview 1 + JSON in/out)** as the v1 plugin contract. The capability/permission model is what actually matters; the type system on top is icing.

3. **Slack/Telegram/WhatsApp APIs change.** Less a Kotlin issue than a "running on top of someone else's product" issue. Mitigation: hide each platform behind a stable internal `Channel` interface (zeroclaw-style ABI), so platform changes affect one file each.

## Sources

- [JetBrains/koog](https://github.com/JetBrains/koog)
- [koog docs](https://docs.koog.ai/)
- [dylibso/chicory](https://github.com/dylibso/chicory)
- [extism/java-sdk](https://github.com/extism/java-sdk)
- [Extism FAQ — JVM](https://extism.org/docs/questions/)
- [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
- [MCP Kotlin SDK docs](https://kotlin.sdk.modelcontextprotocol.io/)
- [Ktor](https://ktor.io/)
- [WebAssembly in 2026 — Java/Kotlin perspective](https://www.javacodegeeks.com/2026/04/webassembly-in-2026-where-it-has-landed-what-wasi-0-2-changes-and-why-java-and-kotlin-developers-should-pay-attention-now.html)
