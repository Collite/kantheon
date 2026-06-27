# AI Platform v1 — Gap Closure Plan (revision 2)

> **Revised 2026-05-12** based on [`aip-v1-status-audit.md`](./aip-v1-status-audit.md). This audit is more grounded than the prior `aip-v1-status-report-2026-05-12.md` — it distinguishes HEAD state from working-tree state, inspected actual file contents rather than assuming task-list checkboxes reflected reality, and corrected several findings from the earlier report.
>
> Where this plan and the older one disagree, **the audit wins** and this plan reflects the corrected picture.

---

## TL;DR — what changed since the first report

**Good news (corrections in the audit):**

- **Resolver Stage 04 is committed as "complete"** at HEAD (`55fdd01`, 2026-05-12). 21 unit tests, 6 integration tests, eval harness, README, prompt templates all in. The "build broken" state from `progress-stage-04.md` was a pre-commit working-tree snapshot, now superseded. LLM and fuzzy calls are **real HTTP**, not stubs.
- **G7 (`pipeline_warnings`) is CLOSED**, not "not started." `tools/query-mcp/PipelineWarnings.kt` exists with full test coverage; both `query` and `compile` tools emit `pipelineWarnings` in `structuredContent`.
- **Stage 03 is largely delivered**: corpus at `infra/nlp/eval/corpus/seed.jsonl` (50 entries), eval harness at `run_eval.py`, MorphoDiTa engine present, COMPARE mode in orchestrator. The previous "~20% in flight" estimate was wrong.

**Bad news (newly revealed by the audit):**

- **G2 (`cnc.role` schema) is OPEN**, not closed. No `cnc` directory in `shared/proto`; no `er2cncRole` in TTR parser; no `cnc.role` query in meta-mcp. The first report's "closed" status was based on the proto having `SchemaCode.CNC = 3` constant — but the surrounding implementation isn't there.
- **G4 and G5 are PARTIAL**, not closed. Protos have `value_labels` and `display_label` fields and a round-trip test passes, but the formatter's Arrow IPC schema-metadata wiring to consume them is not confirmed.
- **Resolver uses plain Kotlin coroutines, NOT Koog.** Koog deps are declared in `libs.versions.toml` but never referenced in `agents/resolver/build.gradle.kts`. The "Koog graph" is plain Kotlin due to a Ktor 2.x/3.x transitive conflict. Major architectural drift from the design doc and the kantheon framework-choice decision.
- **Resolver K8s manifests are staged for deletion** (`base/deployment.yaml`, `base/kustomization.yaml`, `overlays/local/kustomization.yaml`). Resolver is not deployable to K3s in its current working-tree state.
- **agents/erp-agent-2 deletion is uncommitted** — 163+ files deleted in working tree but not staged. `agents/golem` (Python, LangGraph) exists untracked. Repo state is dirty in a way that obscures what's intended.
- **Convention plugins `my.kotlin-ktor` / `my.kotlin-spring` don't exist.** CLAUDE.md describes them; no `gradle-build/` directory exists. Services use direct `alias(libs.plugins...)`. Documentation is aspirational.
- **`just proto-all`, `just sync-py`, `just debug-tunnel` don't exist.** CLAUDE.md documents them; the justfile has `proto`, `py-sync-all`, no `debug-tunnel` at all.
- **nlp-mcp `analyze` tool has a critical bug from review-002** (ops read as `jsonObject` instead of `jsonArray`). `tasks-review-002.md` shows 0/17 items checked. Whether HEAD fixed it is unconfirmed.
- **office-agent does not exist in the repo at all** (the previous report had it with just a `catalog-info.yaml`; the audit can't find it).

**Net effect:** the picture is *better than the first report suggested on Stage 04 progress* but *worse on architectural conformance* (Resolver-vs-Koog, convention plugins, justfile drift). Several gaps that were "platform doesn't exist yet" turn out to be "platform exists but doesn't match the docs." That's a different kind of work — alignment, not implementation.

---

## Gaps grouped by urgency

| Tier | Gap | What it blocks |
|---|---|---|
| **CRITICAL** | Maven publishing infrastructure | Kantheon bootstrap (Stage 4.4 task 1) |
| **CRITICAL** | G1 — Czech-aware fuzzy (NFD + inflection trim) | Themis quality on Czech questions |
| **CRITICAL** | Verify Resolver builds at HEAD | Confidence that "Stage 04 complete" actually means complete |
| **CRITICAL** | nlp-mcp `analyze` ops bug (review-002, 17 items) | Resolver → nlp-mcp integration path |
| **HIGH** | Resolver-vs-Koog drift decision | Constellation framework consistency; kantheon framework-choice memory says "Kotlin + Koog across all agents" |
| **HIGH** | G2 — `cnc.role` schema implementation | metadata semantics; affects future Pythia / Themis features that lean on role-based queries |
| **HIGH** | G3 — `hide_columns_matching` exposed in query-mcp + sql-formatter HTTP endpoint | Agent-side hiding of internal-ID columns |
| **HIGH** | G4 + G5 end-to-end wiring — `value_labels` / `display_label` through Arrow IPC | Localised display in tables; ChartIntent label rendering |
| **HIGH** | Resolver K8s manifests staged for deletion | Local-dev deployability; Stage 04 task 12 |
| **HIGH** | `infra/llm-gateway` gaps (embeddings live endpoint, pricing API, Redis cache, Anthropic provider) | Pythia Budget Tracker, multi-vendor strategy |
| **MEDIUM** | erp-agent-2 deletion / golem-untracked repo cleanup | Repo hygiene; settings.gradle.kts coverage |
| **MEDIUM** | CLAUDE.md ↔ justfile alignment (proto-all, sync-py, debug-tunnel, convention plugins) | Developer onboarding friction; misleading docs |
| **MEDIUM** | K8s manifests for `workers/mssql`, `workers/polars` | Deployment consistency |
| **LOW** | Backstage catalog entries for services without them | Discoverability |
| **LOW** | Doc drift in `docs/v1/v1-architecture.md` | Onboarding clarity |
| **LOW** | Observability stack manifests (Alloy, Tempo, Prometheus, Grafana, Keycloak, NATS, Seaweed, Redis) | Repo completeness; may live in a separate ops repo |

---

## CRITICAL

### Gap 1 — Maven publishing for `shared/proto` + `shared/libs/kotlin/*` via GitHub Packages

**Decision (2026-05-12):** target is **GitHub Packages** (Maven feed). Native Gradle Maven support, free for typical small-team use, integrates with the existing GitHub Actions CI. **Not** Maven Central (which is for public OSS artifacts) and **not** ACR (which is OCI / Docker only — Gradle doesn't speak that protocol). For runtime/deploy artifacts ACR stays in use; for build-time artifacts GitHub Packages is the path.

**Convention plugins**: separately resolved — `my.kotlin-ktor` / `my.kotlin-spring` are aspirational, not real; services use `alias(libs.plugins.*)` from `libs.versions.toml` directly. Kantheon follows the same pattern. **No convention-plugin publishing needed.** CLAUDE.md updated to drop the convention-plugin section (covered in Gap 12).

#### Scope — what to publish

**Publish:**
- `shared/proto` — the proto bindings (Themis, Iris-BFF, capabilities-mcp client all consume `cz.dfpartner.nlp.v1.AnalyzeResponse` and related types).
- `shared/libs/kotlin/otel-config` — every kantheon Kotlin service consumes this.
- `shared/libs/kotlin/fuzzy-common` — Themis client types.
- `shared/libs/kotlin/mcp-server-base` — capabilities-mcp + Themis use this (verify it exists in ai-platform; if not, scaffold during Stage 4.4).
- `shared/libs/kotlin/logging-config` — logging conventions shared across the constellation.
- `shared/libs/kotlin/ktor-configurator` — Ktor service patterns (CORS, error handlers, request IDs) that kantheon-side Ktor services adopt.
- `shared/libs/kotlin/data-formatter` — *if* kantheon agents render envelopes server-side (otherwise covered by kantheon's own `envelope-render` lib).
- `shared/libs/kotlin/ttr-parser` — *if* kantheon agents need to parse TTR (likely no, since metadata access goes via `meta-mcp`).

**Don't publish (internal to ai-platform's multi-module build):**
- `erp-sql-common`, `erp-sql-metadata`, `db-common`, `whois-common`, `query-translator`, `ttr-writer` — consumed only by ai-platform services; kantheon has no use for them.

#### Publishing-side configuration (per module)

Add to each publishable module's `build.gradle.kts`:

```kotlin
plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "cz.dfpartner"
            artifactId = "<module-name>"          // e.g. "shared-proto", "otel-config"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/<org>/ai-platform")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
```

The credentials block reads from env vars only — local-dev publishes are not the intended use case. CI is the publisher.

#### CI workflow (`.github/workflows/publish.yml`)

```yaml
name: Publish Maven artifacts

on:
  push:
    tags:
      - 'shared/v*'           # bundle release (publish all shared libs at once)
      - 'shared-proto/v*'     # proto-only release (faster cadence likely)

permissions:
  contents: read
  packages: write             # required to publish to GitHub Packages

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Publish to GitHub Packages
        run: |
          ./gradlew \
            :shared:proto:publish \
            :shared:libs:kotlin:otel-config:publish \
            :shared:libs:kotlin:fuzzy-common:publish \
            :shared:libs:kotlin:mcp-server-base:publish \
            :shared:libs:kotlin:logging-config:publish \
            :shared:libs:kotlin:ktor-configurator:publish
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Refinement option: split into per-library tags (`otel-config/v*`, `fuzzy-common/v*`) and publish only the changed library per tag. Simpler to start with the bundle approach and split later if cadences diverge.

#### Consumer-side configuration (kantheon)

In `kantheon/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "AiPlatformPackages"
            url = uri("https://maven.pkg.github.com/<org>/ai-platform")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

In `kantheon/gradle/libs.versions.toml`:

```toml
[versions]
ai-platform-proto         = "1.0.0"
ai-platform-otel          = "1.0.0"
ai-platform-fuzzy-common  = "1.0.0"
ai-platform-mcp-base      = "1.0.0"
ai-platform-logging       = "1.0.0"
ai-platform-ktor-config   = "1.0.0"

[libraries]
ai-platform-proto         = { module = "cz.dfpartner:shared-proto",      version.ref = "ai-platform-proto" }
ai-platform-otel-config   = { module = "cz.dfpartner:otel-config",       version.ref = "ai-platform-otel" }
ai-platform-fuzzy-common  = { module = "cz.dfpartner:fuzzy-common",      version.ref = "ai-platform-fuzzy-common" }
ai-platform-mcp-base      = { module = "cz.dfpartner:mcp-server-base",   version.ref = "ai-platform-mcp-base" }
ai-platform-logging       = { module = "cz.dfpartner:logging-config",    version.ref = "ai-platform-logging" }
ai-platform-ktor-config   = { module = "cz.dfpartner:ktor-configurator", version.ref = "ai-platform-ktor-config" }
```

Per-module consumption in agent `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.ai.platform.proto)
    implementation(libs.ai.platform.otel.config)
    implementation(libs.ai.platform.mcp.base)
    // …
}
```

#### Local-developer authentication

Each developer needs a GitHub Personal Access Token (PAT) with `read:packages` scope. Stored in `~/.gradle/gradle.properties` (not committed):

```
gpr.user=<github-username>
gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

This is per-developer one-time setup. Document the setup step in `kantheon/README.md` and ai-platform's `CLAUDE.md`.

CI doesn't need this — `GITHUB_TOKEN` is auto-provisioned in Actions.

#### Versioning policy

- **Tag format:** continue ai-platform's existing `<name>/v<major>.<minor>.<patch>` convention. Bundle releases tagged `shared/v1.2.0`; proto-only releases tagged `shared-proto/v1.2.0`.
- **Cadence:** publish on demand. Kantheon work that needs a new field in `nlp.proto` is the trigger to cut a `shared-proto/v1.2.1` release.
- **Semver discipline:**
  - Major: breaking proto changes (field renumber, message rename, removed field). Coordinated upgrade across consumers.
  - Minor: additive changes (new fields, new messages, new RPCs). Backwards-compatible; consumers pull when ready.
  - Patch: bug fixes in shared libs; no proto changes.
- **Snapshot versions:** avoid — they consume storage without cleanup. Release real versions instead, even for early iteration.

#### Gotchas to flag

1. **GitHub Packages requires authentication even for "public" packages** — the most-cited quirk. Mitigated by always passing a PAT or `GITHUB_TOKEN`; not a real problem for internal use but worth knowing if a contractor tries to pull anonymously.
2. **Storage and bandwidth limits** — free private packages get 500 MB storage + 1 GB/month bandwidth; the next tier (GitHub Pro / Team) raises both substantially. For our scale (Maven artifacts are small, ~MB each) we're nowhere near the limits.
3. **No automatic version cleanup** — old versions stay until manually deleted. Schedule a quarterly cleanup or configure a retention policy if cadence grows.
4. **PAT rotation** — developers' PATs need periodic refresh (GitHub default: 90 days). Document the rotation step in onboarding notes.
5. **First-time publish bootstrap** — the very first publish needs the GitHub repo's package settings configured (Settings → Packages → "Inherit access from source repository" usually). Confirm during the first tag push.

#### Work breakdown

| Day | Work |
|---|---|
| 1 | Add `maven-publish` config to `shared/proto/build.gradle.kts` + one shared lib as proof-of-concept. Verify manual publish to GitHub Packages succeeds via `./gradlew publish` with local PAT. |
| 2 | Apply the same publishing config to remaining shared libs (5 modules). Verify each publishes independently. |
| 3 | Add `.github/workflows/publish.yml` triggered by tag push. Test by pushing a `shared-proto/v0.1.0-test` tag. Verify the workflow publishes successfully. |
| 4 | Add consumer-side config to kantheon (when kantheon is bootstrapped — this can also happen later as part of Stage 4.4 task 1). Verify a kantheon module can resolve `cz.dfpartner:shared-proto:1.0.0`. |
| 5 | Documentation: update `CLAUDE.md` with the publish flow + PAT setup; add `PUBLISHING.md` capturing the versioning policy. Buffer for issues. |

**Effort:** ~3–5 days. Independent of all other gaps.
**Sequencing:** Start immediately. Days 1–3 happen in ai-platform; day 4 happens when kantheon bootstrap begins; day 5 is documentation.
**Owner:** Platform team / Bora for the initial setup.

---

### Gap 2 — G1: Czech-aware fuzzy matching

**Unchanged.** `services/fuzzy-matcher` uses only `lowercase()`. No NFD, no inflection trim. The most impactful gap for Czech-speaking users.

**Work:** Per the first plan — pre-computed lemma index via MorphoDiTa (recommended); NFD-only first PR (~1 day) to ship a partial improvement; full inflection-trim pipeline ~3–5 more days.

**Effort:** ~5–8 days total.
**Sequencing:** Independent of Maven setup; start in parallel.
**Owner:** Someone with `infra/nlp` MorphoDiTa context.

---

### Gap 3 — Verify Resolver builds at HEAD

**New framing.** The audit says Stage 04 is *committed* as complete, but the older `progress-stage-04.md` describes a build error that may or may not have been fixed. The unstaged `progress-stage-04.md` could be either stale (from before the fix) or current (still describing a real issue).

**Work:**
1. Run `just build-kt resolver` (or `./gradlew :agents:resolver:build --no-build-cache`) on a clean machine.
2. If it succeeds: clear the stale `progress-stage-04.md` (or update it to reflect current state).
3. If it fails: diagnose. The hypothesised root cause was Gradle cache corruption or Kotlin 2.3.0 ↔ kotlinx-serialization 1.10.0 incompatibility. `progress-stage-04.md` documented the specific error symptoms — `Implementation`, `ServerCapabilities`, `Tool`, `ToolSchema`, `CallToolResult`, `encodeToString`, `decodeFromString` unresolved.

**Effort:** ~1 day if it builds clean; ~1–3 days if there's a real issue to fix.
**Sequencing:** First thing to do. Confirms whether "Stage 04 complete" is real.
**Owner:** Bora or whoever's been working on the resolver.

---

### Gap 4 — nlp-mcp `analyze` ops-array bug

**New.** Review-002 (`review-002.md` + `tasks-review-002.md`) identified a critical bug: in `tools/nlp-mcp/src/main/kotlin/tools/nlp/mcp/Tools.kt`, the `ops` argument is read as `jsonObject` when it should be `jsonArray`. This makes the `analyze` MCP tool completely non-functional. `tasks-review-002.md` shows 0/17 items checked.

The Resolver's `detectLangAndParse` node calls `nlp-mcp.analyze` — if the bug is present at HEAD, the Resolver's full pipeline doesn't actually work end-to-end (the unit tests use mocks).

**Work:**
1. Inspect `tools/nlp-mcp/src/main/kotlin/tools/nlp/mcp/Tools.kt` at HEAD to verify the bug status.
2. If unfixed: fix the `ops` parsing.
3. Walk through `tasks-review-002.md`'s 17 items. Some may already be addressed; check each.
4. Add an integration test that calls `analyze` with a multi-op request to prevent regression.

**Effort:** ~1–2 days for the primary bug; ~2–4 days for the full review-002 backlog.
**Sequencing:** Independent of other gaps; should happen before any end-to-end Resolver testing.
**Owner:** Whoever owns `tools/nlp-mcp`.

---

## HIGH

### Gap 5 — Resolver-vs-Koog architectural drift

**New.** `agents/resolver/build.gradle.kts` does not reference `koog-*` dependencies. The Resolver graph is implemented as a `ResolverGraph` class with plain Kotlin coroutines + a `NodeResult` sealed class. Koog entries in `libs.versions.toml` are declared but unused.

The audit notes this is due to a Ktor 2.x/3.x transitive conflict — the kind of pragmatic deviation that's defensible but needs to be either:

- **(a) Accepted as the v1 design.** Update `resolver-design.md` to drop "Koog graph" language. Update the kantheon `pythia_framework_choice` memory to note "Kotlin + plain coroutines is acceptable when Koog can't be used; Koog remains the default." Document the Ktor 2.x/3.x conflict in the design doc as the reason. Move forward.
- **(b) Rectified later.** Keep the current implementation; resolve the Ktor conflict and migrate to Koog as a v1.1 cleanup. Don't block on it for Stage 04 close.
- **(c) Rectified now.** Resolve the Ktor conflict before Stage 04 close, switch to Koog graph implementation. Highest cost; lowest risk of carrying the drift forward.

Recommend **(a)** combined with **(b)**: accept the v1 deviation, document it, plan a v1.1 migration. The Koog-everywhere principle from the framework choice memory was about *consistency across the constellation*; if Themis ships in plain Kotlin and Pythia + Golem ship in Koog, the constellation is still 2/3 on the original preference.

**Work for (a) + (b):**
1. Update `resolver-design.md` to reflect plain-coroutines implementation. Drop "Koog graph" terminology; note Ktor conflict.
2. Add `kantheon/docs/v1/themis/themis-design.md` note that Themis-as-Resolver uses plain coroutines; Koog migration is v1.1 work.
3. Update `pythia_framework_choice` memory file with the v1 deviation.
4. File a tracking issue (or doc-line) for the v1.1 Koog migration.

**Effort:** ~half a day of doc work.
**Sequencing:** Any time before kantheon-side Themis work starts in earnest.
**Owner:** Bora.

---

### Gap 6 — G2: `cnc.role` schema implementation

**New.** The first report said G2 was closed because the proto has `SchemaCode.CNC = 3` and there's a `ListRoles` RPC. The audit checked more carefully: no `cnc` directory in `shared/proto`; no `er2cncRole` mapping in TTR parser; no `cnc.role` query in `tools/meta-mcp`. The constant exists but the implementation around it doesn't.

**Work:**
1. Define the `cnc` schema in `shared/proto/src/main/proto/cz/dfpartner/cnc/v1/` — `Role` enum or message, role-to-entity mapping types.
2. Add TTR DSL syntax for `roles: [fact, transaction]` shorthand. Update `services/translator` or wherever TTR parsing lives.
3. Add `er2cncRole` mapping in the metadata service. Persist role assignments per entity.
4. Expose `cnc.role` query in `tools/meta-mcp` — `list_roles()`, `get_roles_for_entity(qname)`.
5. Tests.

**Effort:** ~3–5 days. The scope is moderate — schema + parser + metadata exposure + tests.
**Sequencing:** Independent; can happen in parallel with most other work.
**Owner:** Platform team — metadata + TTR area.
**Importance check:** Pythia §6.1 lists G2 as a v1.5 nice-to-have ("cnc-aware lookups for v1.5"), not a v1 hard requirement. Could be deferred if scope is tight. Bora call.

---

### Gap 7 — G3: `hide_columns_matching` exposed through sql-formatter HTTP endpoint + query-mcp tool schema

**Refined from first plan.** The audit confirmed the library has it (`FormatOptions.hideColumnsMatching: List<Regex>`) but identified two missing exposure layers:

1. `services/sql-formatter/LibraryFormatterAdapter.kt` calls `DataFormatter.fromJsonRows(bytes, outputFormat, FormatOptions())` — empty options, no patterns passed.
2. `tools/query-mcp` doesn't expose `hide_columns_matching` in `QueryTool` input schema.

**Work:**
1. Add `hideColumnsMatching: List<String>?` parameter to sql-formatter HTTP endpoint; pass through to `FormatOptions`.
2. Add `hide_columns_matching: List[string]` parameter to query-mcp `QueryTool` input schema; pass through when calling formatter.
3. Tests at both layers.

**Effort:** ~1 day.
**Sequencing:** Bundle with another query-mcp PR.
**Owner:** Whoever's next in query-mcp / sql-formatter.

---

### Gap 8 — G4 + G5: `value_labels` + `display_label` end-to-end wiring

**Newly discovered (was "closed" in first report).** Protos have the fields; `LocalizedStringSpec.kt` tests roundtrip; but the audit could not confirm that:
- Arrow IPC schema metadata carries the label info from worker → formatter.
- The formatter actually consumes the labels when rendering column headers and cell values.

**Work:**
1. **Investigate the wiring gap.** Read `workers/mssql` and `workers/polars` to confirm what schema metadata they attach to Arrow output. Does the schema include `value_labels` / `display_label` maps per column?
2. **If absent:** add Arrow `Field.metadata` population in worker output for columns whose metadata model carries labels.
3. **In the formatter:** read `Field.metadata` and apply `display_label` to column headers and `value_labels` (per-value mapping for enum-like columns) to cell rendering.
4. End-to-end test: a query against a column with `value_labels` (e.g. `status: {1: "Active", 0: "Inactive"}`) returns formatted output with "Active"/"Inactive", not 1/0.

**Effort:** ~3–5 days. Some of this is investigation; the wiring itself is mechanical once it's clear what's missing.
**Sequencing:** Independent.
**Owner:** Platform team — worker / formatter area.

---

### Gap 9 — Resolver K8s manifests staged for deletion

**New.** Three files exist at HEAD (`agents/resolver/k8s/base/deployment.yaml`, `base/kustomization.yaml`, `overlays/local/kustomization.yaml`) but are in `git status` as **staged for deletion** in the working tree. The resolver is not currently deployable to K3s via Kustomize.

**Work — decide first, then execute:**

- **(a) Un-stage the deletion**, fix the manifests for current local-K3s usage (`imagePullPolicy: Never`, correct namespace, correct image refs), commit them. Restores deployability.
- **(b) Commit the deletion**, document that K3s deployment is deferred (perhaps the resolver runs via `just deploy-kt` only and that's enough for v1).

Recommend (a). The `fwd-stage-04.md` file lists K8s manifests as a remaining MEDIUM-priority item; preserving them costs little and unblocks local-K3s testing.

**Effort:** ~1 day for (a); ~1 hour for (b).
**Sequencing:** Resolves alongside the broader "Stage 04 close" verification.
**Owner:** Bora.

---

### Gap 10 — `infra/llm-gateway` gaps

**Promoted from "Unknown — verify before Pythia v0".** The audit confirmed concrete gaps that affect Themis (uses llm-gateway for joint inference + filter span) and will block Pythia later:

- **Anthropic provider is commented out.** Only Azure OpenAI is wired. Multi-vendor strategy is paper-only.
- **No embeddings live endpoint.** Field `modelType = "embedding"` exists in `ModelRepository.kt` but no endpoint serves it.
- **No pricing API or `cached: bool`.** Pythia's Budget Tracker depends on this.
- **No Redis cache.** Kantheon arch and Pythia §6.1 expect it.
- **NATS async job queue exists** (`NatsConfig.kt`) — but no standalone NATS deployment manifest.

**Work — scope can be staged:**
- **Stage A (Themis-blocking):** Enable Anthropic provider, verify multi-vendor routing. ~2–3 days.
- **Stage B (Pythia-blocking, can wait):** Embeddings endpoint, pricing API, Redis cache. ~5–8 days.

**Effort:** Stage A ~2–3 days; Stage B ~5–8 days when needed.
**Sequencing:** Stage A before Themis ships in production. Stage B can wait for Pythia v0 implementation.
**Owner:** Platform team — llm-gateway area.

---

## MEDIUM

### Gap 11 — erp-agent-2 deletion / golem repo cleanup

**New.** 163+ files for `agents/erp-agent-2` are deleted in the working tree but not staged. `agents/golem` (Python, LangGraph) exists untracked. `settings.gradle.kts` doesn't include either. The repo state is dirty in a way that obscures intent.

**Work:**
1. Decide: is `agents/golem` a rename/evolution of `agents/erp-agent-2`, or a separate new agent? (The audit's read: likely a rename.)
2. If rename: stage the `erp-agent-2` deletions + add `agents/golem` in one commit titled "rename erp-agent-2 → golem".
3. Add `agents/golem` to `settings.gradle.kts` (or to the equivalent Python-services list if it's a Python project the Gradle build ignores).
4. Update Backstage catalog entry path.

**Effort:** ~half a day.
**Sequencing:** Any time. Should happen before any other deep work on the agent directory.
**Owner:** Bora.

---

### Gap 12 — CLAUDE.md ↔ justfile drift

**New.** Several documented commands don't exist:
- `just proto-all` → actual: `just proto` (no `-all`).
- `just sync-py` → actual: `just py-sync-all`.
- `just debug-tunnel` → does not exist at all.
- `id("my.kotlin-ktor")` / `id("my.kotlin-spring")` convention plugins → do not exist; no `gradle-build/` directory.

**Work — pick the canonical truth:**
- **(a) Update CLAUDE.md** to match the justfile reality. Drop convention-plugin references. Add a real `just debug-tunnel` recipe (if the use case is real).
- **(b) Update the justfile + add convention plugins** to match CLAUDE.md. More work, but aligns with the aspirational design.

Recommend (a) — most developer-friction wins. Building convention plugins to match aspirational docs is busywork without clear value when services already work with direct `alias(...)`.

**Work:**
1. Edit CLAUDE.md: change `proto-all` → `proto`; change `sync-py` → `py-sync-all`; drop convention-plugin section or replace with "services use `alias(libs.plugins.*)` from `gradle/libs.versions.toml`."
2. Add a real `just debug-tunnel` recipe if it would be useful.

**Effort:** ~1–2 hours.
**Sequencing:** Any time.
**Owner:** Bora or whoever owns CLAUDE.md.

---

### Gap 13 — K8s manifests for `workers/mssql`, `workers/polars`

**Unchanged from first plan.** Both have no `k8s/` directory. Deployment via `deployment/apps/` Kustomize structure unclear.

**Work:** Add `k8s/{base,overlays/local}/` for each worker. ~1–2 days total.

---

## LOW

### Gap 14 — Backstage catalog entries

**New.** 15 catalog-info.yaml files found; 15+ services missing them, including: `tools/query-mcp`, `tools/meta-mcp`, `tools/nlp-mcp`, `infra/nlp`, `infra/metadata`, `infra/sql-security`, `infra/whois`, `agents/resolver`, `agents/golem`, `services/translator`, `services/validator`, `services/dispatcher`, `services/query-runner`, `workers/mssql`, `workers/polars`.

**Work:** Add catalog-info.yaml per service using existing template. ~half a day.

---

### Gap 15 — Doc drift in `docs/v1/v1-architecture.md`

**Unchanged from first plan.** Update §6 service inventory; note Polars Worker shipped in Phase 2.4.

**Effort:** ~1 day.

---

### Gap 16 — Observability + infrastructure deployment manifests

**Newly revealed (was "Unknown" in first plan).** No Alloy / Tempo / Prometheus / Grafana / Keycloak / NATS / Seaweed / Redis deployment manifests in `deployment/local/`. The platform's observability and infrastructure stack may live in a separate ops repo.

**Work — investigate first:**
1. Confirm whether these are deployed via a separate ops repo or whether they should be in this repo.
2. If they should be here: add Kustomize manifests for each. ~3–5 days.
3. If they're in a separate repo: cross-reference in CLAUDE.md so the doc isn't misleading.

**Effort:** ~1 day investigation; 0–5 days work depending on outcome.
**Sequencing:** Any time. Doesn't block Themis or Kantheon.

---

## Sequencing — dependency graph + recommended order

```
Week 1
├── Gap 3 — Verify Resolver builds at HEAD (~1 day, first action)
├── Gap 1 — Maven publishing (~3–5 days, parallel)
│     └── Sub-decision: keep convention plugins (build + publish) or drop them (just update CLAUDE.md)
├── Gap 4 — nlp-mcp ops bug fix (~1–2 days, parallel; needed before integration testing)
├── Gap 11 — commit erp-agent-2 deletion / add golem (~half day, parallel)
├── Gap 12 — CLAUDE.md ↔ justfile alignment (~1–2 hours, parallel)
└── Gap 2 — G1 NFD-only first PR (~1 day)

Week 2
├── Gap 2 — G1 inflection-trim + lemma-index (~3–5 days)
├── Gap 9 — Resolver K8s manifests (un-stage, fix, commit) (~1 day)
├── Gap 5 — Resolver-vs-Koog drift decision + doc updates (~half day)
├── Gap 8 — G4 / G5 Arrow metadata wiring investigation (~1–2 days investigation)
└── Gap 7 — G3 hide_columns_matching exposure (~1 day, bundle with next query-mcp PR)

Week 3
├── Gap 10 Stage A — llm-gateway Anthropic provider (~2–3 days; needed before Themis ships in prod)
├── Gap 8 — G4 / G5 wiring (~3–4 days remaining)
├── Gap 13 — workers K8s manifests (~1–2 days, parallel)
├── Gap 6 — G2 cnc.role schema (~3–5 days; or defer to v1.5)
└── Gap 14 — Backstage catalog entries (~half day, can be background work)

Week 4+
├── Resolver Stage 04 finalised (eval gate against Stage 03 corpus passes)
├── Themis extraction kantheon-side becomes unblocked
├── Gap 15 — v1-architecture.md drift fix (paired with aip-v1-impl distribution doc)
├── Gap 16 — observability investigation
└── Gap 10 Stage B (embeddings, pricing API, Redis) — when Pythia work starts
```

**Total to "Themis extraction unblocked":** ~3–4 weeks of platform-team work, similar to the first plan but with substantially more confidence (Stage 04 is committed, not in flight; G7 is closed; Stage 03 corpus exists).

---

## Open decisions Bora needs to make

Updated from the first plan with new findings. **Decisions #1 and #2 resolved 2026-05-12** — kept in the table for traceability.

| # | Decision | Status | Notes |
|---|---|---|---|
| 1 | **Maven repository host**: Artifactory / GitHub Packages / OSS Sonatype / private. | **RESOLVED 2026-05-12 — GitHub Packages.** | Native Maven feed, free for our scale, integrates with existing GitHub Actions. Not Maven Central (public-only); not ACR (OCI protocol, doesn't speak Maven). ACR continues to handle runtime/deploy artifacts via Jib; GitHub Packages handles build-time artifacts. See Gap 1 for full config. |
| 2 | **Convention plugins**: drop `my.kotlin-ktor` / `my.kotlin-spring` aspiration (option A) or build them (option B). | **RESOLVED 2026-05-12 — option A (drop).** | Services already use `alias(libs.plugins.*)` from `libs.versions.toml`. CLAUDE.md to be updated (Gap 12). Kantheon follows the same pattern; no convention-plugin publishing needed. |
| 3 | **G1 inflection-trim strategy**: bundled Snowball stemmer vs pre-computed lemma index via MorphoDiTa. | open | Pre-compute via MorphoDiTa likely correct (avoids per-match latency); Snowball as a fallback if MorphoDiTa licensing isn't settled. |
| 4 | **Resolver-vs-Koog drift**: accept plain Kotlin as v1 (recommended) vs migrate to Koog now. | open | If accept: update design docs + memory; if migrate: blocks Stage 04 close. |
| 5 | **Resolver K8s manifests**: keep (un-stage, fix for local) vs commit deletion (defer K8s). | open | Recommend keep — `fwd-stage-04.md` lists K8s as MEDIUM priority remaining work. |
| 6 | **erp-agent-2 vs golem**: confirm `golem` is a rename of `erp-agent-2`, then commit accordingly. | open | Should be a one-liner once Bora confirms intent. |
| 7 | **G2 (`cnc.role`) priority**: build for v1 vs defer to v1.5 (Pythia §6.1 says v1.5). | open | If scope is tight, defer. Themis doesn't need it directly. |
| 8 | **Observability + infra stack location**: in this repo vs separate ops repo. | open | Investigation answers this first; not a real decision until then. |

---

## What this plan deliberately does *not* address

Same as the first plan:

- Pythia v0 implementation gaps (out of scope; Pythia is post-Themis).
- Kantheon-side design work (captured in `next-steps.md`).
- Architecture redesign (the design is locked; this plan closes gaps in it).
- aip-v1-impl roadmap distribution doc (Bora's parked work; this plan is the input).

---

## Progress tracking

Suggestion (unchanged): copy this file's gap list into your task tracker. Mark each gap with status `not started` / `in flight` / `done`. Re-run the AIP audit in ~3–4 weeks; the next `aip-v1-status-audit-<DATE>.md` should show all CRITICAL gaps `done`.

The big watch-points for the next audit:

1. Did `agents/resolver` actually build clean? (Gap 3)
2. Is `maven-publish` configured and a release tag published? (Gap 1)
3. Did the nlp-mcp `analyze` bug get fixed and review-002 backlog cleared? (Gap 4)
4. Is G1 NFD landed (even if inflection trim is still partial)? (Gap 2)
5. Are K8s manifests for Resolver restored or deletion committed? (Gap 9)
6. Did the erp-agent-2 → golem rename get committed cleanly? (Gap 11)

If those six show progress, Themis extraction can begin.

---

*Plan owner: Bora. Source: `aip-v1-status-audit.md` (revision 2). Next review: when CRITICAL gaps are marked done.*
