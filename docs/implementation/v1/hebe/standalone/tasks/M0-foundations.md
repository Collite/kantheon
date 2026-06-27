# M0 — Foundations

Project skeleton, kernel + plugin ABIs, observability + config + secrets, lint rules, CI. **No agent behaviour yet** — the goal is a buildable empty repo that the rest of the milestones can fork off in parallel.

**Done when:** project builds end-to-end, CI is green on a fresh clone, and `api` + `plugin-api` + `config` + `observability` are usable as dependencies from other modules.

References: [`../v1-architecture.md`](../v1-architecture.md) §§1–4, 7, 21.

---

## M0.T1 — Gradle multi-module skeleton

**Status**: pending  
**Size**: M  
**Depends on**: —  
**Blocks**: M0.T2, M0.T3, M0.T5–T11, every later milestone

### Goal

Lay out an empty multi-module Gradle build matching `v1-architecture.md` §1. After this task `./gradlew build` succeeds with all modules empty.

### Files to create

- `settings.gradle.kts` (new)
- `build.gradle.kts` (new — root)
- `build-logic/settings.gradle.kts` (new — Gradle convention plugins live here)
- `build-logic/build.gradle.kts` (new)
- `build-logic/src/main/kotlin/hebe.base.gradle.kts` (new)
- `build-logic/src/main/kotlin/hebe.library.gradle.kts` (new)
- `build-logic/src/main/kotlin/hebe.application.gradle.kts` (new)
- `modules/api/build.gradle.kts` (new)
- `modules/plugin-api/build.gradle.kts` (new)
- `modules/observability/build.gradle.kts` (new)
- `modules/config/build.gradle.kts` (new)
- `modules/memory/build.gradle.kts` (new)
- `modules/security/build.gradle.kts` (new)
- `modules/providers/openai-compat/build.gradle.kts` (new)
- `modules/tools/dispatch/build.gradle.kts` (new)
- `modules/tools/builtin/build.gradle.kts` (new)
- `modules/tools/mcp-client/build.gradle.kts` (new)
- `modules/core/build.gradle.kts` (new)
- `modules/plugins/build.gradle.kts` (new)
- `modules/channels/api/build.gradle.kts` (new)
- `modules/channels/cli/build.gradle.kts` (new)
- `modules/channels/web/build.gradle.kts` (new)
- `modules/channels/telegram/build.gradle.kts` (new)
- `modules/mcp-server/build.gradle.kts` (new)
- `modules/gateway/build.gradle.kts` (new)
- `modules/scheduler/build.gradle.kts` (new)
- `modules/detekt-rules/build.gradle.kts` (new)
- `modules/cli-app/build.gradle.kts` (new)
- `modules/<each>/src/main/kotlin/.gitkeep` (new)
- `modules/<each>/src/test/kotlin/.gitkeep` (new)
- `gradle/libs.versions.toml` (edit — placeholders only; real content in M0.T2)
- `.gitignore` (edit — add `build/`, `.idea/`, `*.log`, `~/.hebe/`)

### Detailed work

1. Set Gradle wrapper to 8.x (already shipped under `gradle/wrapper/`). Confirm `gradle-wrapper.properties` points at `gradle-8.x-bin`.

2. **Root `settings.gradle.kts`**:

   ```kotlin
   pluginManagement {
       includeBuild("build-logic")
       repositories {
           gradlePluginPortal()
           mavenCentral()
       }
   }

   dependencyResolutionManagement {
       repositories {
           mavenCentral()
       }
   }

   rootProject.name = "hebe"

   include(
       ":modules:api",
       ":modules:plugin-api",
       ":modules:observability",
       ":modules:config",
       ":modules:memory",
       ":modules:security",
       ":modules:providers:openai-compat",
       ":modules:tools:dispatch",
       ":modules:tools:builtin",
       ":modules:tools:mcp-client",
       ":modules:core",
       ":modules:plugins",
       ":modules:channels:api",
       ":modules:channels:cli",
       ":modules:channels:web",
       ":modules:channels:telegram",
       ":modules:mcp-server",
       ":modules:gateway",
       ":modules:scheduler",
       ":modules:detekt-rules",
       ":modules:cli-app",
   )
   ```

3. **Convention plugin `hebe.base.gradle.kts`**:

   ```kotlin
   plugins {
       kotlin("jvm")
       id("io.gitlab.arturbosch.detekt")
       id("org.jlleitschuh.gradle.ktlint")
   }

   kotlin {
       jvmToolchain(21)
       compilerOptions {
           freeCompilerArgs.add("-Xjsr305=strict")
       }
   }

   tasks.test {
       useJUnitPlatform()
       testLogging {
           events("passed", "failed", "skipped")
       }
   }

   dependencies {
       "testImplementation"("org.junit.jupiter:junit-jupiter")
       "testImplementation"("io.kotest:kotest-assertions-core")
       "testImplementation"("io.mockk:mockk")
       "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
   }
   ```

4. **`hebe.library.gradle.kts`** applies `hebe.base` and adds nothing extra in v1 (publishing config later).

5. **`hebe.application.gradle.kts`** applies `hebe.base` plus the Shadow plugin (skeleton; M9.T6 fills in the manifest):

   ```kotlin
   plugins {
       id("hebe.base")
       application
       id("com.github.johnrengelman.shadow")
   }
   ```

6. **Root `build.gradle.kts`** is essentially empty (everything is in convention plugins):

   ```kotlin
   plugins {
       id("hebe.base") apply false
       id("hebe.library") apply false
       id("hebe.application") apply false
   }
   ```

7. **Per-module `build.gradle.kts`** template (e.g. `modules/api/build.gradle.kts`):

   ```kotlin
   plugins {
       id("hebe.library")
   }

   // module-specific deps go here in later tasks
   ```

   `modules/cli-app/build.gradle.kts` uses `hebe.application` instead.

8. Create the `src/main/kotlin/.gitkeep` and `src/test/kotlin/.gitkeep` for every module so Gradle treats them as real source sets.

### Tests / verification

- `./gradlew projects` lists 23 subprojects under `:modules`.
- `./gradlew build` succeeds (all modules empty, just the toolchain runs).
- `./gradlew :modules:api:test` runs (no tests yet, exits 0).

### Acceptance criteria

- ✅ `settings.gradle.kts` declares all 23 modules from `v1-architecture.md` §1.
- ✅ Convention plugins live under `build-logic/` and are applied via `id("hebe.base")` etc.
- ✅ `./gradlew build` is green on a clean checkout.
- ✅ JVM toolchain is set to 21 in the convention plugin.
- ✅ Detekt + ktlint plugins applied (real config follows in M0.T3).

### Pitfalls

- Gradle's included build (`includeBuild("build-logic")`) must be in `pluginManagement {}` of the root settings, not `dependencyResolutionManagement` — otherwise the convention plugins aren't visible.
- The `org.jlleitschuh.gradle.ktlint` plugin defaults to a heavy filter that can pull in source-set files we don't want; add `ktlint { filter { exclude("**/generated/**") } }` if needed.

### References

- `v1-architecture.md` §1 (module layout)
- `v1-architecture.md` §2 (versions)

---

## M0.T2 — `gradle/libs.versions.toml` aligned with stack table

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1  
**Blocks**: every later task that adds dependencies

### Goal

Populate the version catalogue so any module can declare deps as `libs.kotlinx.coroutines.core` etc., matching the stack pinned in `v1-architecture.md` §2.

### Files to create / modify

- `gradle/libs.versions.toml` (edit)

### Detailed work

1. Define `[versions]` for: `kotlin`, `coroutines`, `serialization`, `datetime`, `koog`, `ktor`, `mcp`, `sqlite`, `flyway`, `telegram`, `jgit`, `bouncycastle`, `pf4j`, `oras`, `tomlj`, `kotlin-logging`, `logback`, `otel`, `junit`, `kotest`, `mockk`, `testcontainers`, `detekt`, `ktlint`, `shadow`.

2. Pick concrete versions. Suggested anchors (verify latest at PR time):

   ```toml
   [versions]
   kotlin           = "2.3.0"           # bump to 2.3.x when GA
   coroutines       = "1.10.2"
   serialization    = "1.11.0"
   datetime         = "0.7.1"
   koog             = "0.8.0"           # confirm latest before pinning
   ktor             = "3.2.3"
   mcp              = "0.11.0"           # io.modelcontextprotocol:kotlin-sdk
   sqliteJdbc       = "3.53.0.0"
   sqliteVec        = "0.1.8"           # native bundle
   flyway           = "11.20.3"
   telegram         = "8.0.0"           # org.telegram:telegrambots
   jgit             = "6.10.0.202406032230-r"
   bouncycastle     = "1.84"
   pf4j             = "3.15.0"
   oras             = "0.6.0"           # land.oras:oras-java-sdk; verify
   tomlj            = "1.1.1"
   kotlinLogging    = "8.0.02"
   logback          = "1.5.26"
   otel             = "1.58.0"
   junit            = "5.11.0"
   kotest           = "6.1.2"
   mockk            = "1.14.9"
   testcontainers   = "2.0.3"
   detekt           = "1.23.8"
   ktlint           = "14.0.1"          # plugin version
   shadow           = "8.1.1"
   ```

3. Define `[libraries]` for everything used in `v1-architecture.md` §2's table. Use `version.ref` to reference `[versions]`. Examples:

   ```toml
   [libraries]
   kotlinx-coroutines-core   = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core",   version.ref = "coroutines" }
   kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
   kotlinx-datetime          = { module = "org.jetbrains.kotlinx:kotlinx-datetime",            version.ref = "datetime" }
   koog-core                 = { module = "ai.koog:koog-core",                                 version.ref = "koog" }
   koog-ktor                 = { module = "ai.koog:koog-ktor",                                 version.ref = "koog" }
   ktor-server-core          = { module = "io.ktor:ktor-server-core",                          version.ref = "ktor" }
   ktor-server-netty         = { module = "io.ktor:ktor-server-netty",                         version.ref = "ktor" }
   ktor-server-cnegot        = { module = "io.ktor:ktor-server-content-negotiation",           version.ref = "ktor" }
   ktor-server-sse           = { module = "io.ktor:ktor-server-sse",                           version.ref = "ktor" }
   ktor-server-websockets    = { module = "io.ktor:ktor-server-websockets",                    version.ref = "ktor" }
   ktor-server-auth          = { module = "io.ktor:ktor-server-auth",                          version.ref = "ktor" }
   ktor-serialization-json   = { module = "io.ktor:ktor-serialization-kotlinx-json",           version.ref = "ktor" }
   ktor-client-core          = { module = "io.ktor:ktor-client-core",                          version.ref = "ktor" }
   ktor-client-cio           = { module = "io.ktor:ktor-client-cio",                           version.ref = "ktor" }
   mcp-kotlin-sdk            = { module = "io.modelcontextprotocol:kotlin-sdk",                version.ref = "mcp" }
   sqlite-jdbc               = { module = "org.xerial:sqlite-jdbc",                            version.ref = "sqliteJdbc" }
   flyway-core               = { module = "org.flywaydb:flyway-core",                          version.ref = "flyway" }
   telegrambots              = { module = "org.telegram:telegrambots",                         version.ref = "telegram" }
   jgit                      = { module = "org.eclipse.jgit:org.eclipse.jgit",                 version.ref = "jgit" }
   bouncycastle              = { module = "org.bouncycastle:bcprov-jdk18on",                   version.ref = "bouncycastle" }
   pf4j                      = { module = "org.pf4j:pf4j",                                     version.ref = "pf4j" }
   oras-sdk                  = { module = "land.oras:oras-java-sdk",                           version.ref = "oras" }
   tomlj                     = { module = "org.tomlj:tomlj",                                   version.ref = "tomlj" }
   kotlin-logging            = { module = "io.github.oshai:kotlin-logging-jvm",                version.ref = "kotlinLogging" }
   logback-classic           = { module = "ch.qos.logback:logback-classic",                    version.ref = "logback" }
   otel-api                  = { module = "io.opentelemetry:opentelemetry-api",                version.ref = "otel" }
   junit-jupiter             = { module = "org.junit.jupiter:junit-jupiter",                   version.ref = "junit" }
   junit-platform-launcher   = { module = "org.junit.platform:junit-platform-launcher" }
   kotest-assertions         = { module = "io.kotest:kotest-assertions-core",                  version.ref = "kotest" }
   mockk                     = { module = "io.mockk:mockk",                                    version.ref = "mockk" }
   testcontainers            = { module = "org.testcontainers:testcontainers",                 version.ref = "testcontainers" }
   ```

4. Define `[bundles]` where useful:

   ```toml
   [bundles]
   ktor-server = ["ktor-server-core", "ktor-server-netty", "ktor-server-cnegot",
                  "ktor-server-sse", "ktor-server-websockets", "ktor-server-auth",
                  "ktor-serialization-json"]
   ktor-client = ["ktor-client-core", "ktor-client-cio"]
   logging     = ["kotlin-logging", "logback-classic"]
   testing     = ["junit-jupiter", "kotest-assertions", "mockk"]
   ```

5. Define `[plugins]`:

   ```toml
   [plugins]
   kotlin-jvm           = { id = "org.jetbrains.kotlin.jvm",                  version.ref = "kotlin" }
   kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
   detekt               = { id = "io.gitlab.arturbosch.detekt",               version.ref = "detekt" }
   ktlint               = { id = "org.jlleitschuh.gradle.ktlint",             version.ref = "ktlint" }
   shadow               = { id = "com.github.johnrengelman.shadow",           version.ref = "shadow" }
   ```

6. Update `build-logic/build.gradle.kts` to depend on the Kotlin Gradle plugin via the catalogue:

   ```kotlin
   dependencies {
       implementation(libs.plugins.kotlin.jvm.dependency)
       implementation(libs.plugins.detekt.dependency)
       // …
   }
   ```

   (Gradle 8 lets you reference `libs.plugins.<id>` as a normal artifact; if your version does not, fall back to literal coordinates and add a comment.)

### Tests / verification

- `./gradlew dependencies --configuration runtimeClasspath` (in any module) lists `kotlinx-coroutines-core` etc. with the catalogued versions.
- `./gradlew :modules:api:dependencyInsight --dependency koog-core` errors cleanly (no koog dep yet) — this is fine.

### Acceptance criteria

- ✅ Every library named in `v1-architecture.md` §2 is in `[libraries]`.
- ✅ `[bundles]` covers Ktor server/client, logging, testing.
- ✅ `[plugins]` covers Kotlin/Detekt/ktlint/Shadow.
- ✅ All convention plugins reference `libs.*` rather than literal coordinates.

### Pitfalls

- The `version.ref` keys are case-sensitive and use camelCase by convention; aliases under `[libraries]` are kebab-case. Don't mix.
- The MCP Kotlin SDK and `oras-java-sdk` versions in particular need verifying at PR time — both are young projects.

### References

- `v1-architecture.md` §2 (versions and dependency stack)

---

## M0.T3 — Detekt + ktlint baseline

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1, M0.T2  
**Blocks**: M0.T4 (CI), M0.T10 (custom rule)

### Goal

`./gradlew detekt ktlintCheck` clean on the empty project, with config files committed.

### Files to create / modify

- `config/detekt/detekt.yml` (new)
- `config/detekt/baseline.xml` (new — empty XML root)
- `build-logic/src/main/kotlin/hebe.base.gradle.kts` (edit — wire config files)
- `.editorconfig` (new — ktlint reads this)

### Detailed work

1. Generate a starter detekt config: `./gradlew detektGenerateConfig --quiet` then move the generated file to `config/detekt/detekt.yml` and trim to the rules we care about. Recommended toggles:
   - `complexity` enabled at default thresholds.
   - `style.MagicNumber` excluded for tests.
   - `naming.FunctionNaming` allows backtick test names.
   - `formatting` left to ktlint (disable formatting rules in detekt).

2. Wire detekt into `hebe.base.gradle.kts`:

   ```kotlin
   detekt {
       config.setFrom(rootProject.files("config/detekt/detekt.yml"))
       baseline = rootProject.file("config/detekt/baseline.xml")
       buildUponDefaultConfig = true
       autoCorrect = false
   }
   ```

3. **`.editorconfig`** sets:

   ```
   root = true

   [*]
   charset = utf-8
   end_of_line = lf
   insert_final_newline = true
   indent_style = space

   [*.{kt,kts}]
   indent_size = 4
   max_line_length = 140
   ij_kotlin_imports_layout = *
   ij_kotlin_packages_to_use_import_on_demand = unset
   ```

4. Optional: enable detekt + ktlint in `tasks.check`:

   ```kotlin
   tasks.named("check") {
       dependsOn("detekt", "ktlintCheck")
   }
   ```

### Tests / verification

- `./gradlew detekt` exits 0 with no findings.
- `./gradlew ktlintCheck` exits 0.
- A deliberate violation (e.g. add a 200-character line) is caught by the right tool.

### Acceptance criteria

- ✅ Both linters clean on empty modules.
- ✅ `.editorconfig` committed; ktlint respects it.
- ✅ Config files committed under `config/detekt/`.

### Pitfalls

- detekt's default config includes both `style` and `formatting` — turn off `formatting` (it duplicates ktlint).
- ktlint Gradle plugin sometimes picks up `.kts` build scripts; usually fine, but `.gradle.kts` files in `build-logic/` may need filter exclusions.

### References

- `v1-architecture.md` §2 (versions)
- `v1-specs.md` §4 (NFRs — "zero detekt warnings on main")

---

## M0.T4 — CI pipeline (GitHub Actions)

**Status**: pending  
**Size**: M  
**Depends on**: M0.T2, M0.T3  
**Blocks**: every PR after this one

### Goal

A GitHub Actions workflow that runs `./gradlew check` on every PR and `./gradlew check shadowJar` on `main`. Caches the Gradle build to keep runs under 5 minutes.

### Files to create

- `.github/workflows/ci.yml` (new)
- `.github/workflows/release.yml` (new — placeholder; M9.T6 fills in)
- `.github/dependabot.yml` (new)

### Detailed work

1. **`.github/workflows/ci.yml`**:

   ```yaml
   name: CI
   on:
     pull_request:
     push:
       branches: [main]

   jobs:
     check:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with:
             distribution: temurin
             java-version: 21
         - uses: gradle/actions/setup-gradle@v3
           with:
             cache-read-only: ${{ github.ref != 'refs/heads/main' }}
         - run: ./gradlew check --no-daemon --stacktrace
   ```

2. Add a `shadowJar` step to a separate `release.yml` workflow that fires on tags only. Keep its body trivial for now (`./gradlew shadowJar`); M9.T6 will expand it.

3. **`.github/dependabot.yml`** for Gradle + GitHub Actions:

   ```yaml
   version: 2
   updates:
     - package-ecosystem: "gradle"
       directory: "/"
       schedule: { interval: "weekly" }
     - package-ecosystem: "github-actions"
       directory: "/"
       schedule: { interval: "monthly" }
   ```

4. (Optional) cache custom paths if needed. Default `actions/setup-gradle` cache is normally enough.

### Tests / verification

- Open a draft PR with a no-op change → CI runs, completes < 5 min, ends green.
- Tag a synthetic `v0.0.1` on a fork → release workflow runs `shadowJar` (no upload yet).

### Acceptance criteria

- ✅ PR CI runs `./gradlew check` and gates merges.
- ✅ Cache hit rate ≥ 80% on subsequent runs.
- ✅ Dependabot configured for Gradle + GitHub Actions.

### Pitfalls

- `gradle/actions/setup-gradle@v3` is the modern action; `gradle/gradle-build-action@v2` is deprecated.
- `--no-daemon` is recommended in CI to avoid stale daemon issues; combine with the action's caching for speed.

### References

- `v1-architecture.md` §2 (versions)

---

## M0.T5 — `api` module: kernel ABI types

**Status**: pending  
**Size**: L  
**Depends on**: M0.T1, M0.T2  
**Blocks**: every module that imports `api`

### Goal

Implement the kernel ABI as described in `v1-architecture.md` §3. Pure interfaces + data classes; no runtime behaviour.

### Files to create

- `modules/api/build.gradle.kts` (edit — add deps)
- `modules/api/src/main/kotlin/com/hebe/api/LlmProvider.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/Tool.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/Channel.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/MemoryStore.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/Observer.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/Submission.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/HandleOutcome.kt` (new)
- `modules/api/src/main/kotlin/com/hebe/api/Common.kt` (new — shared types: Attachment, JsonObject re-exports if needed)
- `modules/api/src/main/kotlin/com/hebe/api/Errors.kt` (new — `HebeException` sealed hierarchy from arch §20)
- Test scaffolding: at least one round-trip serialisation test per data class

### Detailed work

1. `modules/api/build.gradle.kts` deps:

   ```kotlin
   plugins {
       id("hebe.library")
       alias(libs.plugins.kotlin.serialization)
   }

   dependencies {
       api(libs.kotlinx.coroutines.core)
       api(libs.kotlinx.serialization.json)
       api(libs.kotlinx.datetime)
   }
   ```

   Use `api()` not `implementation()` because consumers need `Flow`, `JsonObject`, `Instant` visible.

2. Copy the interface and data class shapes from `v1-architecture.md` §3 verbatim. Keep them in the files above, organised by concern. Apply `@Serializable` to every data class that crosses a module boundary.

3. For `StreamEvent`, `ToolResult`, `Submission`, `HandleOutcome`, `PendingReason`, `ChatMessage`, `ToolChoice`, `ExternalThreadId`, `ObserverEvent` — use `sealed` interface or class with `@Serializable` and `@SerialName` on each variant.

4. `HebeException` should be a sealed `Exception` hierarchy. Match arch §20 exactly:

   ```kotlin
   sealed class HebeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
       class Config(message: String) : HebeException(message)
       class Provider(val retriable: Boolean, message: String, cause: Throwable? = null) : HebeException(message, cause)
       class Tool(val tool: String, val retriable: Boolean, message: String) : HebeException(message)
       class Plugin(val pluginId: String, message: String, cause: Throwable? = null) : HebeException(message, cause)
       class Security(message: String) : HebeException(message)
       class PolicyDenied(message: String) : HebeException(message)
       class Approval(message: String) : HebeException(message)
       class Memory(message: String) : HebeException(message)
       class Channel(val channel: String, message: String, cause: Throwable? = null) : HebeException(message, cause)
   }
   ```

5. Helper types in `Common.kt`: `Attachment`, `WorkspacePath` (typealias to `String` for now; v1 keeps it stringy and validates via `WorkspaceFs`), `RiskLevel` enum, `ProviderCapabilities`, `ChatRequest`, `ToolSpec`.

6. Tests: for every `@Serializable` data class, write a round-trip test (`Json.encodeToString(x).let(Json::decodeFromString)` returns the same value). Use Kotest `shouldBe`. Aim for 100% line coverage of the data classes.

### Tests / verification

- `./gradlew :modules:api:test` passes.
- `./gradlew :modules:api:detekt` clean.
- A consumer module (test-only, in this task) can `import com.hebe.api.*` and reference types.

### Acceptance criteria

- ✅ All interfaces and data classes in arch §3 implemented.
- ✅ Round-trip serialisation tests pass for every `@Serializable` type.
- ✅ Module declares only `kotlinx-*` deps (verified by `./gradlew :modules:api:dependencies`).

### Pitfalls

- `JsonObject` must come from `kotlinx-serialization-json` — easy to accidentally import a different one.
- `sealed interface` requires Kotlin ≥ 1.5; we're on 2.2 so it's fine.
- `@JvmInline value class` for `SecretHandle` (lives in `plugin-api`) won't be needed here, but `MemoryScope` etc. are plain enums.

### References

- `v1-architecture.md` §3 (kernel ABI)
- `v1-architecture.md` §20 (error taxonomy)

---

## M0.T6 — `plugin-api` module: plugin ABI types

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: every plugin and the `plugins` module (M6)

### Goal

Implement the plugin-author-facing ABI from `v1-architecture.md` §4. This module is what plugin JARs compile against; keep its surface minimal.

### Files to create

- `modules/plugin-api/build.gradle.kts` (edit)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/HebePlugin.kt` (new)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/PluginHost.kt` (new)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/PluginManifest.kt` (new)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/Capability.kt` (new — enum)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/Permission.kt` (new — sealed)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/SecretHandle.kt` (new — value class)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/HttpResponse.kt` (new)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/PluginCapabilityException.kt` (new)
- Tests: serialisation round-trips for `PluginManifest`, etc.

### Detailed work

1. `build.gradle.kts`:

   ```kotlin
   plugins {
       id("hebe.library")
       alias(libs.plugins.kotlin.serialization)
   }

   dependencies {
       api(project(":modules:api"))
       api(libs.pf4j)
   }
   ```

2. `HebePlugin`:

   ```kotlin
   abstract class HebePlugin(wrapper: org.pf4j.PluginWrapper) : org.pf4j.Plugin(wrapper) {
       open fun tools(host: PluginHost): List<Tool> = emptyList()
       open fun channels(host: PluginHost): List<Channel> = emptyList()
       open fun memoryStores(host: PluginHost): List<MemoryStore> = emptyList()
       open fun observers(host: PluginHost): List<Observer> = emptyList()

       open fun init(host: PluginHost) {}
       open fun teardown() {}
   }
   ```

3. `PluginHost`:

   ```kotlin
   interface PluginHost {
       val pluginId: String
       val manifest: PluginManifest

       fun http(): GatedHttpClient
       fun env(name: String): String?
       fun secret(name: String): SecretHandle?

       val observer: Observer
       val log: org.slf4j.Logger
   }
   ```

4. `GatedHttpClient` interface + `HttpResponse` data class as in arch §4.

5. `Permission` is sealed; `Secret(name)` is a data class variant. Use `@Serializable` with a custom polymorphic discriminator if you want JSON round-trips (likely yes, since manifest carries permissions).

6. `SecretHandle`:

   ```kotlin
   @JvmInline value class SecretHandle(val name: String)
   ```

7. `PluginManifest`:

   ```kotlin
   @Serializable
   data class PluginManifest(
       val hebeApiVersion: String,
       val capabilities: Set<Capability>,
       val permissions: Set<Permission>,
       val allowlistDomains: List<String>,
       val signature: String? = null,
       val publisherKey: String? = null,
   )
   ```

   Polymorphic serialisation: register `Permission.HttpClient`, `Permission.EnvRead`, `Permission.Secret` under a single discriminator `"type"`.

### Tests / verification

- Round-trip serialisation tests for `PluginManifest` and each `Permission` subtype.
- Smoke test: instantiate a synthetic `HebePlugin` subclass; call default methods; observe empty lists returned.

### Acceptance criteria

- ✅ Module compiles with deps `api` + `pf4j` only (verified).
- ✅ A plugin author can write `class MyPlugin(wrapper) : HebePlugin(wrapper) { override fun tools(host) = listOf(...) }` against this module alone.
- ✅ `PluginManifest` round-trips through JSON.

### Pitfalls

- `org.pf4j.Plugin`'s constructor takes `PluginWrapper`; subclasses must pass it through.
- Sealed-interface polymorphic JSON requires `Json { classDiscriminator = "type" }` or per-class `@SerialName`. Pick one and document.
- Don't accidentally re-export `koog` here. Plugin authors must not see koog.

### References

- `v1-architecture.md` §4 (plugin ABI)
- `v1-architecture.md` §11 (plugin lifecycle, sets the contract `init`/`teardown` hook into)

---

## M0.T7 — `observability` module: OTel + structured logging

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: every module that emits events (memory, dispatcher, channels, plugins)

### Goal

Wire up logback with the JSON encoder and the hebe `Observer` interface backed by both OTel spans and an in-memory ring buffer (for `hebe doctor --verbose`).

### Files to create

- `modules/observability/build.gradle.kts` (edit)
- `modules/observability/src/main/kotlin/com/hebe/observability/LogbackObserver.kt` (new)
- `modules/observability/src/main/kotlin/com/hebe/observability/RingBuffer.kt` (new)
- `modules/observability/src/main/kotlin/com/hebe/observability/SensitiveRedactor.kt` (new — used by both Observer and dispatcher)
- `modules/observability/src/main/resources/logback.xml` (new)
- Tests: every event variant gets logged in JSON; redactor masks the right keys.

### Detailed work

1. `build.gradle.kts` deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       api(libs.bundles.logging)
       implementation(libs.otel.api)
   }
   ```

2. **`logback.xml`** with JSON encoding (use `net.logstash.logback:logstash-logback-encoder` or write a small custom encoder; the encoder dep is fine to add to the catalogue):

   ```xml
   <configuration>
     <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
       <encoder class="net.logstash.logback.encoder.LogstashEncoder">
         <fieldNames>
           <timestamp>ts</timestamp>
           <message>msg</message>
           <logger>logger</logger>
           <thread>thread</thread>
           <level>level</level>
         </fieldNames>
         <customFields>{"app":"hebe"}</customFields>
       </encoder>
     </appender>
     <root level="info">
       <appender-ref ref="STDOUT" />
     </root>
   </configuration>
   ```

3. `LogbackObserver`: implements `Observer`. Each `ObserverEvent` variant maps to a structured log line with the right fields. Holds an injected `RingBuffer<ObserverEvent>` (default size 256) for `doctor`.

4. `RingBuffer<T>` — small thread-safe bounded queue using `ConcurrentLinkedDeque` with a size cap; eviction of the oldest item.

5. `SensitiveRedactor` — pure function that takes a `JsonElement` (typically tool args) and returns a redacted copy where keys in a denylist are replaced with `"[REDACTED]"`. Default denylist: `api_key`, `apikey`, `token`, `secret`, `password`, `auth`, `bearer`, `signature`, `cookie`, case-insensitive. Make the denylist injectable from config.

6. `Span` impl wraps an OTel `Span`; `setAttribute` and `recordError` delegate. If OTel isn't configured, use a no-op `Span`.

### Tests / verification

- Each `ObserverEvent` subtype produces a parseable JSON line with the right fields. (Capture stdout in a test using `java.io.ByteArrayOutputStream`.)
- `RingBuffer` evicts oldest at capacity; iteration yields newest-first.
- Redactor masks `api_key` (and case variants) and leaves other fields untouched.

### Acceptance criteria

- ✅ Logs are valid JSON.
- ✅ `LogbackObserver` plus a no-op OTel exporter satisfies the `Observer` contract.
- ✅ Ring buffer accessible via `LogbackObserver.recent()` for use by `doctor`.
- ✅ `SensitiveRedactor` exposes a public function used by the dispatcher (M2.T6) and receipts (M3.T8).

### Pitfalls

- The `LogstashEncoder` requires the `net.logstash.logback:logstash-logback-encoder` dep; add to the version catalogue.
- OTel is opt-in: don't crash on missing endpoint. Default to a no-op exporter if `OTEL_EXPORTER_OTLP_ENDPOINT` is unset.

### References

- `v1-architecture.md` §21 (logging + observability conventions)
- `v1-architecture.md` §3 (`Observer` interface)

---

## M0.T8 — `config` module: TOML schema + loader

**Status**: pending  
**Size**: L  
**Depends on**: M0.T5  
**Blocks**: M0.T9 (secrets store), M9.T4 (onboarding wizard), every module that reads config

### Goal

Load `~/.hebe/config.toml`, validate it, return a typed `HebeConfig`. Bad input produces row/column-pinpointed diagnostics.

### Files to create

- `modules/config/build.gradle.kts` (edit)
- `modules/config/src/main/kotlin/com/hebe/config/HebeConfig.kt` (new — typed projection)
- `modules/config/src/main/kotlin/com/hebe/config/ConfigLoader.kt` (new)
- `modules/config/src/main/kotlin/com/hebe/config/ConfigError.kt` (new — sealed)
- `modules/config/src/main/kotlin/com/hebe/config/Defaults.kt` (new)
- Tests: golden config files + bad-input cases

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(libs.tomlj)
   }
   ```

2. `HebeConfig` should mirror the schema in `v1-architecture.md` §7 exactly. Use nested data classes:

   ```kotlin
   @Serializable
   data class HebeConfig(
       val hebe: General,
       val llm: Llm,
       val autonomy: Autonomy,
       val security: Security,
       val scheduler: Scheduler,
       val channels: Channels,
       val plugins: Plugins,
       val mcp: Mcp,
   ) {
       @Serializable data class General(val dataDir: String, val logLevel: String)
       @Serializable data class Llm(val baseUrl: String, val apiKeySecret: String,
                                    val defaultModel: String, val embeddingModel: String, val embeddingDim: Int)
       @Serializable data class Autonomy(val level: AutonomyLevel)
       // … etc
   }
   ```

3. `ConfigLoader.load(path: Path): Result<HebeConfig, List<ConfigError>>`:
   - Reads the file with `tomlj`.
   - For each required key, look it up; on miss, emit `ConfigError.Missing(path, key, line=…, col=…)` using `tomlj`'s position info.
   - Resolve `${ENV_VAR}` references in string values. If a referenced env var is missing, emit `ConfigError.UnresolvedEnv`.
   - Type checks: cron expressions parsed (via M8's parser later — for v1 stub, just regex-validate `^[0-9*/,-]+( [0-9*/,-]+){4}$`).

4. `Defaults` provides a `HebeConfig.minimal(dataDir)` used by the onboarding wizard (M9.T4) to write a starter file.

5. Diagnostics: every `ConfigError` includes `line` + `col`. Format errors as `~/.hebe/config.toml:42:5 — missing required key 'llm.base_url'`.

### Tests / verification

- Golden test: a minimal valid `config.toml` loads; round-trip through `HebeConfig.toToml()` (if implemented) matches.
- Bad input: missing key → error with right line/col. Invalid type → error.
- Env-ref test: `api_key_secret = "${MY_KEY}"` resolved when env var set; flagged when not.

### Acceptance criteria

- ✅ All keys from arch §7 modelled in `HebeConfig`.
- ✅ Bad config produces row/col diagnostics.
- ✅ Env-ref resolution tested.

### Pitfalls

- `tomlj` returns `null` for missing keys, not throws — wrap each lookup in error reporting.
- TOML's table syntax (`[mcp.client.servers]`) is array-of-tables; tomlj exposes it as `TomlArray`. Iterate explicitly.

### References

- `v1-architecture.md` §7 (config schema)

---

## M0.T9 — `config` module: secrets store

**Status**: pending  
**Size**: L  
**Depends on**: M0.T8  
**Blocks**: M2.T1 (LLM provider), M3.T8 (receipts signing key), M5.T8 (Telegram bot token), M6.T5 (signature verification)

### Goal

Implement `SecretStore` backed by AES-256-GCM-encrypted SQLite. Master key in OS keychain, with a passphrase-derived fallback.

### Files to create

- `modules/config/src/main/kotlin/com/hebe/config/secrets/SecretStore.kt` (new — interface)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/SqliteSecretStore.kt` (new — impl)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/MasterKeyResolver.kt` (new)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/MacKeychain.kt` (new — JNA against `Security.framework` or shell-out to `security`)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/LinuxSecretService.kt` (new — `libsecret` via `secret-tool` shell-out)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/WindowsCredentialManager.kt` (new — JNA `Advapi32`)
- `modules/config/src/main/kotlin/com/hebe/config/secrets/PassphraseFallback.kt` (new)
- Tests on a tmp DB

### Detailed work

1. `SecretStore` interface from arch §8:

   ```kotlin
   interface SecretStore {
       fun put(name: String, value: String)
       fun get(name: String): String?
       fun delete(name: String): Boolean
       fun list(): List<String>
   }
   ```

2. `MasterKeyResolver.resolve(): SecretKey` tries (in order):
   1. OS keychain (`MacKeychain`/`LinuxSecretService`/`WindowsCredentialManager` based on `os.name`).
   2. Passphrase file at `${dataDir}/.master-passphrase` (chmod 600), key derived via PBKDF2-HMAC-SHA256 (600,000 rounds, 16-byte salt, 32-byte key).
   3. If neither works, prompt the user (CLI-only path; raise if no TTY).

3. `SqliteSecretStore`:
   - Opens `~/.hebe/secrets.db`.
   - Single table:
     ```sql
     CREATE TABLE secrets (
       name       TEXT PRIMARY KEY,
       nonce      BLOB NOT NULL,
       ciphertext BLOB NOT NULL,
       ts         INTEGER NOT NULL
     );
     ```
   - On `put`: generate a random 12-byte nonce, encrypt with AES-256-GCM, insert.
   - On `get`: decrypt; null if not found.

4. `MacKeychain` simplest impl: shell out to `security add-generic-password -a hebe -s hebe-master -w <key>` and `security find-generic-password -a hebe -s hebe-master -w`. Document the JNA path as a v2 cleanup.

5. `LinuxSecretService` shells out to `secret-tool store --label="hebe master key" service hebe account master`. If `secret-tool` is unavailable, fall back to passphrase file.

6. `WindowsCredentialManager` uses JNA against `Advapi32`'s `CredRead`/`CredWrite`.

### Tests / verification

- `put → get` round-trip on a tmp DB with an in-memory master key.
- `delete` removes the row; subsequent `get` returns null.
- `list()` returns names only, never values (verify in test by ensuring no values appear in output).
- A test that simulates a missing keychain on Linux falls back to passphrase mode.

### Acceptance criteria

- ✅ AES-256-GCM with 12-byte nonce + auth tag.
- ✅ Master key path documented in module README.
- ✅ Names are listable; values never appear in logs (verify by capturing stdout in a test).

### Pitfalls

- macOS Keychain prompt may pop up on first access; test interactively before stamping done.
- Linux `secret-service` is finicky on headless systems; the passphrase fallback is the recommended path for headless deployments.
- Don't confuse AES-256-GCM (96-bit nonce, 128-bit tag) with AES-256-CBC. JCA: `Cipher.getInstance("AES/GCM/NoPadding")`.

### References

- `v1-architecture.md` §8 (secrets store)
- `v1-specs.md` §2.10 (security)

---

## M0.T10 — `detekt-rules` module: mutation-funnel custom rule

**Status**: pending  
**Size**: M  
**Depends on**: M0.T1, M0.T3  
**Blocks**: M2.T6 (dispatcher) lands without the rule, but the rule is much cheaper to add now than after.

### Goal

Custom Detekt rule that flags any direct write to fields like `state.store`, `state.workspace`, `state.memory` (and similar) outside lines preceded by a `// dispatch-exempt: <reason>` comment. The rule names are a starting set; the list is expanded as the dispatcher lands in M2.

### Files to create

- `modules/detekt-rules/build.gradle.kts` (edit)
- `modules/detekt-rules/src/main/kotlin/com/hebe/detekt/MutationFunnelRule.kt` (new)
- `modules/detekt-rules/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` (new)
- `modules/detekt-rules/src/main/kotlin/com/hebe/detekt/HebeRuleSetProvider.kt` (new)
- `modules/detekt-rules/src/test/kotlin/com/hebe/detekt/MutationFunnelRuleTest.kt` (new)
- `config/detekt/detekt.yml` (edit — register the rule set)

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       compileOnly("io.gitlab.arturbosch.detekt:detekt-api:${libs.versions.detekt.get()}")
       testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${libs.versions.detekt.get()}")
       testImplementation(libs.bundles.testing)
   }
   ```

2. `MutationFunnelRule` extends `Rule`. Visit `KtBinaryExpression` with operator `=`, `+=`, etc. If the LHS qualifier matches one of the protected names (`state.store`, `state.workspace`, `state.memory`, etc.) AND the line lacks an immediately preceding `// dispatch-exempt:` comment, report an issue.

3. `Issue` config:

   ```kotlin
   override val issue = Issue(
       id = "MutationFunnelBypass",
       severity = Severity.Defect,
       description = "Direct mutation of agent state outside ToolDispatcher.dispatch. Add `// dispatch-exempt: <reason>` if intentional.",
       debt = Debt.TWENTY_MINS
   )
   ```

4. Register via `RuleSetProvider`:

   ```kotlin
   class HebeRuleSetProvider : RuleSetProvider {
       override val ruleSetId = "hebe"
       override fun instance(config: Config) = RuleSet(ruleSetId, listOf(MutationFunnelRule(config)))
   }
   ```

5. Add the SPI file at `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` containing the FQCN of the provider.

6. Wire into root `config/detekt/detekt.yml`:

   ```yaml
   hebe:
     active: true
     MutationFunnelBypass:
       active: true
   ```

7. In `hebe.base.gradle.kts`, add the rule set to the detekt classpath:

   ```kotlin
   dependencies {
       "detektPlugins"(project(":modules:detekt-rules"))
   }
   ```

### Tests / verification

- Negative test: `state.store = newStore` flagged.
- Positive test (suppressed): `// dispatch-exempt: bootstrap\n state.store = newStore` not flagged.
- Negative test: `state.somethingElse = …` not flagged (only protected names trigger).

### Acceptance criteria

- ✅ Rule fires on a synthetic positive test.
- ✅ `// dispatch-exempt: <reason>` suppresses on the same or previous line.
- ✅ Active by default in `detekt.yml`.
- ✅ The protected-names list lives in a `companion object` in the rule for easy extension.

### Pitfalls

- The protected-names list is a starting point; the dispatcher (M2.T6) defines the real list. Adjust this rule when M2.T6 lands.
- Detekt API version must match the runner — pin to `libs.versions.detekt`.

### References

- `v1-architecture.md` §1 (single mutation funnel principle)
- `v1-specs.md` §2.1 (kernel ABI rules)

---

## M0.T11 — `cli-app` skeleton (clikt)

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1  
**Blocks**: M9.* (every operations command)

### Goal

A `hebe` binary stub with all v1 subcommands wired as no-ops, so contributors can run `./hebe <subcommand> --help` and see the surface.

### Files to create

- `modules/cli-app/build.gradle.kts` (edit)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Run.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Onboard.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Service.kt` (new — subgroup with install/start/stop/uninstall)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Doctor.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Tool.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Plugin.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Mcp.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Memory.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Pairing.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Estop.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Status.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Completion.kt` (new)
- Wrapper script: `hebe` (new at repo root)
- `gradle/libs.versions.toml` (edit — add clikt)

### Detailed work

1. Add to `libs.versions.toml`:

   ```toml
   clikt = "5.0.0"
   ```
   ```toml
   clikt = { module = "com.github.ajalt.clikt:clikt", version.ref = "clikt" }
   ```

2. `cli-app/build.gradle.kts`:

   ```kotlin
   plugins {
       id("hebe.application")
   }

   application {
       mainClass.set("com.hebe.cli.MainKt")
       applicationName = "hebe"
   }

   dependencies {
       implementation(project(":modules:api"))
       implementation(project(":modules:config"))
       implementation(libs.clikt)
       implementation(libs.bundles.logging)
   }
   ```

3. `Main.kt`:

   ```kotlin
   class Hebe : CliktCommand(name = "hebe") {
       override fun run() = Unit
   }

   fun main(args: Array<String>) = Hebe()
       .subcommands(
           Run(), Onboard(), Service(), Doctor(),
           Tool(), Plugin(), Mcp(), Memory(),
           Pairing(), Estop(), Status(), Completion(),
       )
       .main(args)
   ```

4. Each command stub prints "not yet implemented" and exits 0:

   ```kotlin
   class Run : CliktCommand(name = "run") {
       override fun run() {
           echo("hebe run — not yet implemented")
       }
   }
   ```

5. The `Service` and `Plugin` and `Mcp` commands are command groups with subcommands (e.g. `service install/start/stop/uninstall`). Use clikt's `subcommands()` mechanism.

6. Wrapper script `./hebe` (repo root):

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   exec "$(dirname "$0")/gradlew" --quiet --console=plain :modules:cli-app:run --args="$*"
   ```

   Make it executable. Document that this is the dev wrapper; the production wrapper is generated by Shadow in M9.T6.

### Tests / verification

- `./hebe --help` lists all subcommands.
- `./hebe service --help` shows `install/start/stop/uninstall`.
- `./hebe doctor` exits 0 with the placeholder message.

### Acceptance criteria

- ✅ Every subcommand from `v1-specs.md` §2.11 wired as a stub.
- ✅ `./hebe --help` shows the surface.
- ✅ Run via `./gradlew :modules:cli-app:run --args="--help"` also works.

### Pitfalls

- Clikt 5 is a major rewrite of the API; verify the version you pin and use the correct `subcommands()` shape.
- The wrapper script must use `--args="$*"` (not `--args=$*`) to handle quoted argument lists correctly.

### References

- `v1-specs.md` §2.11 (CLI subcommands)


---

## M0.T12 — `just` command runner

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1  
**Blocks**: M1.* (every other task)

### Goal

Create a local `justfile` that will allow to run
- build
- test
- deploy locally
- tag a commit with a new (semantic) version
etc.

See the `justfile.sample` - a sample file from other (large) project

### Files to create

- `justfile`
