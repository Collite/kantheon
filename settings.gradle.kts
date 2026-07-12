rootProject.name = "kantheon"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // `org.tatrman:*` — the TTR toolchain (Collite/tatrman) + the open read
        // spine's shared libs/proto stubs (Collite/tatrman-server) — now resolve
        // from **Maven Central** (SV-P1 S4 review-input ⚑2, repointed 2026-07-12 at
        // the 0.9.4 public line). Central is public/anonymous, so the two
        // `Collite/tatrman{,-server}` GitHub Packages repos (and the `gpr.*` PAT they
        // needed) are RETIRED here — mavenCentral() above serves the whole group.
        // GitHub Packages remains the pre-release *staging* lane on the publisher
        // side; kantheon just consumes the released Central artifacts.
        // NB: no `mavenLocal()` either (the SV-P0 interim, review-input ⚑5, long
        // retired). No ai-platform Maven coupling remains (fork Stage 2.6).
    }
}

// Phase 1 modules — populated as stages land.
include(":shared:proto")
include(":tools:_smoke-test")
include(":tools:capabilities-mcp")
include(":shared:libs:kotlin:capabilities-client")
include(":shared:libs:kotlin:envelope-render")
// Golem S2.4 (parametrization rail) — pure-Kotlin port of `aip_pattern_params`;
// builds the typed {name:{value,type}} parameters map. Consumed by Golem (and,
// later, Pythia/Wrangler) — no proto coupling, so it stays library-local.
include(":shared:libs:kotlin:pattern-params")
// Testing arc Phase 1 Stage 1.2 — shared component-tier support (Testcontainers
// factories, WireMock admin helper, CI-gating condition). Consumed by
// `componentTest` source sets only.
include(":shared:libs:kotlin:component-testkit")
// Testing arc Phase 2 Stage 2.1 — shared integration harness (@RequiresContext
// readiness gate, ContextHandle, fabric8 read-only cluster reader). Consumed by
// `integrationTest` source sets.
include(":shared:libs:kotlin:integration-harness")
// Sysifos Stage 1.2 — shared dispatch-BFF foundation (Keycloak JWT verify,
// tenant-header forwarding, health/ready routes). Consumed by sysifos-bff;
// iris-bff migrates onto it in a follow-up (deferred — Stage 1.2 audit).
include(":shared:libs:kotlin:bff-base")
// Golem Stage 2.2 — shared Veles gRPC client (extracted from tools/veles-mcp).
// Golem Stage 2.3 — shared LLM-gateway client + Koog executor (extracted from agents/themis).
// Fork Phase 5 Stage 5.0 — technical-wave shared libs.
// whois-common: 3 domain records (UserRecord/UserIdRecord/UserSource), pkg org.tatrman.identity.domain.
// keycloak-auth: generic Keycloak client_credentials token provider, EXTRACTED off
// erp-sql-common.auth (4 self-contained files) so the legacy ERP-SQL line need not fork.
include(":agents:themis")

// Iris arc — dispatch BFF (Phase 1 Stage 1.2: skeleton + session persistence).
include(":agents:iris-bff")

// Golem arc — per-domain Q&A template (Phase 2 Stage 2.1: skeleton + golem_turns persistence).
include(":agents:golem")

// Pythia arc — autonomous analytical investigator (P1 S1.2: skeleton + persistence + checkpointer).
include(":agents:pythia")

// Phase 2 — first off-data-path service (Stage 2.1).
// Stage 2.1 T4 — veles's MCP wrapper (forked from tools/meta-mcp).
// Stage 2.2 — the fuzzy matcher (moved to tatrman-server).
// Stage 2.2 T4 — the fuzzy MCP wrapper (moved to tatrman-server).
// Stage 2.3 T4 — Nlp's MCP wrapper (forked from tools/nlp-mcp; HTTP, Analyze).
// (services/nlp itself is a Python module, not a Gradle subproject.)
// Stage 3.5 T4 — Query's MCP wrapper (forked from tools/query-mcp; run_query + IdentityResolver).
// Stage 2.4 — Translate (translator: lang ↔ RelNode ↔ SQL), forked from services/translator.
// No MCP wrapper — internal pipeline service called by Query.
// Stage 2.5 — the LLM gateway (moved to tatrman-server).
// The repo's only Spring Boot module (documented exception).
// Phase 3 Stage 3.1 — Validate (validator: RLS + TopN + column rules + LLM-judge),
// forked from services/validator. sql-security folds in at Stage 3.2.
// Phase 3 Stage 3.3 — Dispatch (worker dispatcher), forked from services/dispatcher.
// Phase 3 Stage 3.5 — Query (query orchestrator + plan cache), forked from services/query-runner.

// workers/ — the Dispatchs (fork Phase 3). The _smoke-worker placeholder (Phase 1
// Stage 1.1 T3) retired at Stage 3.3 when the first real worker landed.
// Phase 3 Stage 3.3 — Mssql (MSSQL worker), forked from workers/mssql.
// Stream B — Postgres (Postgres worker, postgres/plan.md Phase 1 Stage 1.1). Born in-repo by
// mirroring Mssql; adds the per-tenant RLS `SET LOCAL app.tenant_id` session contract.
// Stream B — Metis (metis/plan.md Phase 1 Stage 1.1).
// services/metis is a Python module (uv / pyproject.toml), not a Gradle subproject — no include().
// Stage 3.4 — Metis MCP wrapper (tools/metis-mcp). Mirrors veles-mcp in structure and patterns.
include(":tools:metis-mcp")

// Charon MCP wrapper (tools/charon-mcp; charon/plan.md Phase 3 Stage 3.2). Mirrors metis-mcp.
include(":tools:charon-mcp")

// Stream B — Charon (charon/plan.md Phase 1 Stage 1.1).
// First `services/` platform-grade module written in kantheon from the start
// (no `forked-from` provenance header per AGENTS.md §12.1). Settles the
// `services/` module conventions that Midas's `report-renderer` will follow.
include(":services:charon")

// Stream B — Midas (midas/plan.md Phase 1 Stage 1.1). The three Midas-owned
// Kotlin modules; `agents/midas/shem` holds a YAML manifest (not a module) and
// `frontends/sysifos` + `agents/sysifos-bff` belong to the Sysifos arc.
include(":agents:midas:core")
include(":agents:midas:loaders:excel")
include(":agents:midas:loaders:google-finance")
include(":services:report-renderer")

// infra/ — off-constellation technical-wave infrastructure (fork Phase 5, no personas).
// Tree introduced in Stage 5.0 (the _smoke placeholder, now retired — superseded by whois,
// mirroring the workers/_smoke-worker → Mssql retirement).
// Stage 5.1 — whois (user/role directory + OPA bundle server), forked from infra/whois.
// Stage 5.2 — health (cluster health aggregator), forked from infra/health.
// Stage 5.5 — backstage is a Node module (own Yarn build), not a Gradle subproject.

// Stream B — Sysifos (sysifos/plan.md Phase 1 Stage 1.1). The back-office
// workbench: `agents/sysifos-bff` (dispatch BFF — auth/tenant/session/draft/
// stream/dictionaries + Midas-core client) and `frontends/sysifos` (Vue SPA).
// The FE is an npm module (vite + vitest), not a Gradle subproject — no include().
include(":agents:sysifos-bff")

// Stream B — Hebe (hebe/plan.md Phase 1 Stage 1.1 — gradle merge). The 21
// standalone Hebe modules become kantheon root-build modules; the standalone
// build machinery (own settings/build-logic/wrapper) is retired. `cli-app`
// keeps its shadowJar packaging for the local `hebe` binary. Package rename
// `com.hebe.*` → `org.tatrman.kantheon.hebe.*` is Stage 1.2. `plugin-template`
// stays an out-of-build scaffold (verified by the Stage 1.2 T6 smoke test).
include(
    ":agents:hebe:modules:api",
    ":agents:hebe:modules:plugin-api",
    ":agents:hebe:modules:observability",
    ":agents:hebe:modules:config",
    ":agents:hebe:modules:memory",
    ":agents:hebe:modules:security",
    ":agents:hebe:modules:providers:openai-compat",
    ":agents:hebe:modules:tools:dispatch",
    ":agents:hebe:modules:tools:builtin",
    ":agents:hebe:modules:tools:mcp-client",
    ":agents:hebe:modules:core",
    ":agents:hebe:modules:plugins",
    ":agents:hebe:modules:channels:channel-manager",
    ":agents:hebe:modules:channels:cli",
    ":agents:hebe:modules:channels:web",
    ":agents:hebe:modules:channels:telegram",
    ":agents:hebe:modules:mcp-server",
    ":agents:hebe:modules:gateway",
    ":agents:hebe:modules:scheduler",
    ":agents:hebe:modules:detekt-rules",
    ":agents:hebe:modules:cli-app",
)

// Stream B — Kleio / DocWH (kleio/plan.md). The knowledge-warehouse arc:
// Pinakes (pipelines/catalogue/compile) + Kallimachos (corpus warehouse +
// retrieval) + the kallimachos-mcp wrapper (P4) + the Kleio agent (P5).
// services/kallimachos lands P1 Stage 1.1; services/pinakes lands P1 Stage 1.3;
// tools/kallimachos-mcp lands P4; agents/kleio lands P5.
include(":services:kallimachos")
include(":services:pinakes")
include(":tools:kallimachos-mcp")

// Stream B — Kleio (kleio/plan.md P5). The NotebookLM agent over a mart.
include(":agents:kleio")
