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
        // TEMPORARY (ttr-metadata adoption, Stage M4.1): consume the tatrman
        // `org.tatrman:ttr-metadata(+-git)` artifacts from Maven Local while the
        // swap is validated locally (publishToMavenLocal, 0.0.1-LOCAL). Flip the
        // `tatrman-ttr-metadata` pin to the released 0.1.x and remove this line
        // once `kotlin-metadata/v0.1.0` is on GitHub Packages (arc-checklist item 1).
        mavenLocal()
        // The ai-platform GitHub Packages repo (DFPartner/ai-platform, group
        // cz.dfpartner) was removed in fork Stage 2.6 — Themis retargeted off
        // cz.dfpartner:shared-proto (nlp.v1 → in-repo kadmos.v1). No ai-platform
        // Maven coupling remains. The Collite/tatrman repo below stays (third-
        // party TTR toolchain, org.tatrman:* — permanent, see CLAUDE.md §7.3).
        maven {
            // TTR parser/writer/semantics (third-party, from the `Collite/tatrman` repo (ex-modeler, forked 2026-07-03)).
            // The `org.tatrman:ttr-{parser,writer,semantics}:0.8.4` artifacts are NOT
            // published to Maven Central; they live in this GitHub Packages repo. The
            // same `gpr.*` PAT works (GitHub Packages auth is per-user, per-package-visibility).
            // Stage 2.1 (Ariadne) and 2.4 (Proteus) consume these.
            name = "Tatrman"
            url = uri("https://maven.pkg.github.com/Collite/tatrman")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("org.tatrman")
            }
        }
    }
}

// Phase 1 modules — populated as stages land.
include(":shared:proto")
include(":tools:_smoke-test")
include(":tools:capabilities-mcp")
include(":shared:libs:kotlin:capabilities-client")
include(":shared:libs:kotlin:otel-config")
include(":shared:libs:kotlin:logging-config")
include(":shared:libs:kotlin:ktor-configurator")
include(":shared:libs:kotlin:fuzzy-common")
include(":shared:libs:kotlin:db-common")
include(":shared:libs:kotlin:data-formatter")
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
// Golem Stage 2.2 — shared Ariadne gRPC client (extracted from tools/ariadne-mcp).
include(":shared:libs:kotlin:ariadne-client")
// Golem Stage 2.3 — shared LLM-gateway client + Koog executor (extracted from agents/themis).
include(":shared:libs:kotlin:llm-gateway-client")
// Fork Phase 5 Stage 5.0 — technical-wave shared libs.
// whois-common: 3 domain records (UserRecord/UserIdRecord/UserSource), pkg org.tatrman.whois.domain.
// keycloak-auth: generic Keycloak client_credentials token provider, EXTRACTED off
// erp-sql-common.auth (4 self-contained files) so the legacy ERP-SQL line need not fork.
include(":shared:libs:kotlin:whois-common")
include(":shared:libs:kotlin:keycloak-auth")
include(":agents:themis")

// Iris arc — dispatch BFF (Phase 1 Stage 1.2: skeleton + session persistence).
include(":agents:iris-bff")

// Golem arc — per-domain Q&A template (Phase 2 Stage 2.1: skeleton + golem_turns persistence).
include(":agents:golem")

// Pythia arc — autonomous analytical investigator (P1 S1.2: skeleton + persistence + checkpointer).
include(":agents:pythia")

// Phase 2 — first off-data-path service (Stage 2.1).
include(":services:ariadne")
// Stage 2.1 T4 — ariadne's MCP wrapper (forked from tools/meta-mcp).
include(":tools:ariadne-mcp")
// Stage 2.2 — Echo (lean fuzzy matcher, forked from services/fuzzy-matcher).
include(":services:echo")
// Stage 2.2 T4 — Echo's MCP wrapper (forked from tools/fuzzy-mcp).
include(":tools:echo-mcp")
// Stage 2.3 T4 — Kadmos's MCP wrapper (forked from tools/nlp-mcp; HTTP, Analyze).
// (services/kadmos itself is a Python module, not a Gradle subproject.)
include(":tools:kadmos-mcp")
// Stage 3.5 T4 — Theseus's MCP wrapper (forked from tools/query-mcp; run_query + IdentityResolver).
include(":tools:theseus-mcp")
// Stage 2.4 — Proteus (translator: lang ↔ RelNode ↔ SQL), forked from services/translator.
// No MCP wrapper — internal pipeline service called by Theseus.
include(":services:proteus")
// Stage 2.5 — Prometheus (LLM gateway), forked from infra/llm-gateway.
// The repo's only Spring Boot module (documented exception).
include(":services:prometheus")
// Phase 3 Stage 3.1 — Argos (validator: RLS + TopN + column rules + LLM-judge),
// forked from services/validator. sql-security folds in at Stage 3.2.
include(":services:argos")
// Phase 3 Stage 3.3 — Kyklop (worker dispatcher), forked from services/dispatcher.
include(":services:kyklop")
// Phase 3 Stage 3.5 — Theseus (query orchestrator + plan cache), forked from services/query-runner.
include(":services:theseus")

// workers/ — the Kyklops (fork Phase 3). The _smoke-worker placeholder (Phase 1
// Stage 1.1 T3) retired at Stage 3.3 when the first real worker landed.
// Phase 3 Stage 3.3 — Brontes (MSSQL worker), forked from workers/mssql.
include(":workers:brontes")
// Stream B — Arges (Postgres worker, arges/plan.md Phase 1 Stage 1.1). Born in-repo by
// mirroring Brontes; adds the per-tenant RLS `SET LOCAL app.tenant_id` session contract.
include(":workers:arges")
// Stream B — Metis (metis/plan.md Phase 1 Stage 1.1).
// services/metis is a Python module (uv / pyproject.toml), not a Gradle subproject — no include().
// Stage 3.4 — Metis MCP wrapper (tools/metis-mcp). Mirrors ariadne-mcp in structure and patterns.
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
// mirroring the workers/_smoke-worker → Brontes retirement).
// Stage 5.1 — whois (user/role directory + OPA bundle server), forked from infra/whois.
include(":infra:whois")
// Stage 5.2 — health (cluster health aggregator), forked from infra/health.
include(":infra:health")
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
