# The Platform Fork — Contracts

> **Status:** v0.1 — 2026-06-12, companion to [`./architecture.md`](./architecture.md). This is the authoritative old→new map for everything on a wire: proto packages, gRPC service names, MCP surfaces, shared libs, manifests. Principle: **wire *shapes* fork unchanged; only names and packages change.** Anything not listed here forks byte-identical.

---

## 1. Proto package map

All renames happen in the fork commit that lands the owning module (architecture §5). Source of truth: kantheon `shared/proto/src/main/proto/org/tatrman/…`.

| ai-platform package | kantheon package | Owner | gRPC service rename | Notes |
|---|---|---|---|---|
| `cz.dfpartner.plan.v1` | `org.tatrman.plan.v1` | shared (pipeline) | — (types only) | canonical RelNode/PlanNode; imported by nearly everything; forks in Phase 1 |
| `cz.dfpartner.worker.v1` | `org.tatrman.worker.v1` | shared (pipeline) | `WorkerService` (name kept) | implemented by Brontes, Steropes, **Arges** (Postgres, active arc 2026-06-23); Charon's `WorkerEndpoint` re-targets this package |
| `cz.dfpartner.transdsl.v1` | `org.tatrman.transdsl.v1` | shared (DSL) | — | |
| `cz.dfpartner.dfdsl.v1` | `org.tatrman.dfdsl.v1` | shared (DSL) | — | |
| `cz.dfpartner.metadata.v1` | `org.tatrman.ariadne.v1` | Ariadne | `MetadataService` → `AriadneService` | its local `ResponseMessage` is dropped — Rule 6 retargets to kantheon `common/v1` (§4); **gains `ResolveArea` (§1.1, 2026-06-25); `GetPrompts` was added 2026-06-13 then removed 2026-06-25 — prompts moved to the Shem** |
| `cz.dfpartner.runner.v1` | `org.tatrman.theseus.v1` | Theseus | `QueryRunnerService` → `TheseusService` | |
| `cz.dfpartner.fuzzy.v1` | `org.tatrman.echo.v1` | Echo | `FuzzyMatcherService` → `EchoService` | self-contained, no cross-imports |
| `cz.dfpartner.nlp.v1` | `org.tatrman.kadmos.v1` | Kadmos | — (HTTP at v1, proto types only) | **`themis/v1` import swap** — see §5 |
| `cz.dfpartner.translator.v1` | `org.tatrman.proteus.v1` | Proteus | `TranslatorService` → `ProteusService` | RPCs keep names: `ParseToRelNode`, `UnparseFromRelNode`, `Translate`, `Explain` |
| `cz.dfpartner.dispatcher.v1` | `org.tatrman.kyklop.v1` | Kyklop | `DispatcherService` → `KyklopService` | |
| `cz.dfpartner.validator.v1` | `org.tatrman.argos.v1` | Argos | `ValidatorService` → `ArgosService` | |
| `cz.dfpartner.security.v1` | **folded into `org.tatrman.argos.v1`** | Argos | `SecurityService` → internal (no separate gRPC surface) | v1 RelNode-based shapes only; legacy SQL-fragment endpoints are not forked |
| `org.tatrman.llmgateway.v1` | `org.tatrman.prometheus.v1` | Prometheus | service name aligns to `PrometheusService` | already org.tatrman — rename for persona consistency |
| `cz.dfpartner.resolver.v1` | — not forked | — | — | Themis owns `org.tatrman.kantheon.themis.v1` already |
| `cz.dfpartner.erp.v1`, `cz.dfpartner.erp.sql.v1` | — not forked | — | — | legacy line stays behind |

**Technical wave (Phase 5) — Kotlin package roots, no protos.** whois and health are REST/JSON services with **no proto**; the "rename" is a Kotlin-package move only (greppability, not wire):

| ai-platform Kotlin root | kantheon root | Module | Notes |
|---|---|---|---|
| `infra.whois.*` | `org.tatrman.whois.*` | `infra/whois` | REST + OPA bundle server; no proto. Sweep `package`/`import`, Jib `mainClass`, `application.conf` |
| `com.platform.health.*` | `org.tatrman.health.*` | `infra/health` | health-check aggregator; no proto |
| `infra.whois.domain.*` (in `whois-common`) | `org.tatrman.whois.domain.*` | lib `whois-common` | 3 domain records (§6) |
| `infra.erp.sql.common.auth.*` | `org.tatrman.keycloak.auth.*` | new lib `keycloak-auth` | 4 token-provider files extracted off erp-sql-common (§6) |

`landing` (Vue/TS) and `backstage` (Node/TS) carry no JVM packages; their "rename" is a **rebrand** (strings + catalog), specified in §8.

**Import graph after rename** (acyclic, unchanged in shape):

- `theseus/v1` → `plan/v1`, `proteus/v1`, `kyklop/v1`, `worker/v1`, `ariadne/v1`
- `argos/v1` → `plan/v1`, `ariadne/v1` (security/v1 absorbed)
- `kyklop/v1` → `plan/v1`, `worker/v1`, `ariadne/v1`
- `worker/v1`, `proteus/v1`, `ariadne/v1`, `kadmos/v1` → `plan/v1` (+ `ariadne/v1` where today's module imports `metadata/v1`)
- everything → `org.tatrman.kantheon.common.v1` for Rule 6 (§4)

`org.tatrman.kantheon.*` remains agents-only; no platform service may import an agent package (envelope/themis/pythia/golem/iris/hebe).

### 1.1 Ariadne `GetPrompts` — REMOVED 2026-06-25 (prompts moved to the Shem); `ResolveArea` added

> **REVERSAL (2026-06-25, converged Golem design).** `GetPrompts` / `get_prompts` is **removed**.
> Prompts belong to the **Shem**, not the model — the model is just the model (entities, packages,
> areas). Each Golem Shem carries its own `prompts/{cs,en}/…` (seeded from `ai-models/prompts/golem`),
> loaded by the agent's `PromptStore` from the mounted Shem bundle; Ariadne's Git source narrows back
> to `model-ttr/` only. In its place Ariadne gains **`ResolveArea(area) → packages`** (+ area
> `description`/`tags`), since the Shem references `areas` and areas are not in the metadata graph.
> Rationale + the assembled-Shem model: [`../golem/contracts.md`](../golem/contracts.md) §6,
> [`../golem/architecture.md`](../golem/architecture.md) §4.1. **The 2026-06-13 design below is
> retained for history.**

The fork principle is "wire shapes fork unchanged", but Ariadne is the deliberate exception: it takes over the prompt-serving job that each agent pod did for itself in ai-platform (golem `prompt_source.py`, resolver's equivalent), so the contract is **kantheon-new**, not a forked shape. It is additive to `AriadneService` — every existing RPC forks byte-identical.

```proto
// In services/ariadne — org.tatrman.ariadne.v1, added beside the forked RPCs.
service AriadneService {
  // ... forked RPCs: ListObjects, GetObject, Search, ListQueries, GetModel ...

  // Serve the agent prompt set from the ai-models repo (prompts/<agent_id>/).
  // Replaces each agent's per-pod git-fetch client. Prompts are central
  // (one set across all instances of an agent) per the ai-models README.
  rpc GetPrompts(GetPromptsRequest) returns (GetPromptsResponse);
}

message GetPromptsRequest {
  string agent_id = 1;   // "golem" | "themis"(resolver) — selects prompts/<agent_id>/
  string locale   = 2;   // optional filter, e.g. "cs"; empty = all locales
}

message GetPromptsResponse {
  repeated PromptDef prompts = 1;
  string tree_hash            = 2;   // sha256 of the **filtered** set's `\n`-joined content_hash field, in declaration order — agent cache key
  string source_commit        = 3;   // git commit the tree was loaded from (empty for classpath fallback); per-source load time remains available via `GetStatus`
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;  // Rule 6
}

message PromptDef {
  string name         = 1;   // filename relative to prompts/<agent_id>/, e.g. "system.cs.yaml"
  string locale       = 2;   // parsed from the filename's last `.` segment (blocklist: default/common/shared/base/main)
  string content      = 3;   // raw YAML text — Ariadne does NOT parse or substitute; the agent does
  string content_hash = 4;   // sha256 of `content` (lowercase hex); per-file cache key
}
```

> **Wire delta vs. the original plan (review-004 F3, 2026-06-13).** Two renames + one field add:
> - `content_hash` (planned field 2 on `GetPromptsResponse`) is renamed to `tree_hash` — same algorithm, same shape, but the name reflects that it's a *tree-level* hash, not a per-file one. The per-file `content_hash` is added as field 4 on `PromptDef` (the per-file cache key the plan lacked).
> - The planned `loaded_at` (ISO-8601, field 3) is **dropped in favor of `source_commit` (git SHA)**. Per-source load time is still observable via `GetStatus` (`SourceStatus.last_success` / `last_error_at`), so the wire loss is acceptable and the gain is tighter cache invalidation.
>
> These are improvements, not regressions; the plan was committed to in `docs/implementation/v1/fork/plan.md` §2.1 before the GetPrompts design crystallized.

Loading: Ariadne's Git source (today `METADATA_GIT_SUBDIR=model-ttr`, single subdir) widens to read the **whole `ai-models` repo** — `model-ttr/` (or `model-yaml/`) **and** `prompts/`. The existing `/refresh` + poll covers both trees; there is no second poller. `GetPrompts` returns raw YAML — keeping all parsing/substitution agent-side preserves the `{{ … }}` template semantics each agent already owns (zero behavior change beyond the source swap).

## 2. MCP surfaces

Tool names and JSON schemas fork **unchanged** — they are the agent-facing surface and prompt-load-bearing. Renaming the tool vocabulary (e.g. `run_query` → `query.run` to match Charon's `move.*` / Metis's `model.*` style) is explicitly **out of scope** for the fork; revisit when Golem/Pythia integration tests exist to catch it. Each wrapper stays zero-logic: JSON ↔ proto, one gRPC/HTTP call.

| Wrapper | Wraps | Surface (forked as-is) |
|---|---|---|
| `tools/theseus-mcp` | Theseus | `run_query` (Arrow IPC → JSON-encoded responses); **IdentityResolver lives here** (§3) |
| `tools/ariadne-mcp` | Ariadne | `ListObjects`, `GetObject`, `Search`, `ListQueries`, `GetModel`, **`resolve_area`** (§1.1, 2026-06-25). *(`get_prompts` existed 2026-06-13→2026-06-25, now removed — prompts moved to the Shem.)* |
| `tools/echo-mcp` | Echo | `Match` (cascade algorithm selection exposed) |
| `tools/kadmos-mcp` | Kadmos (HTTP) | `Analyze` |

All four register `ToolCapability` manifests with capabilities-mcp at startup (heartbeat, warn-and-continue) — now an in-repo call. The ai-platform-side PoC heartbeat (query-mcp → kantheon) is decommissioned at fork Phase 4; ai-platform is not touched to do this (its heartbeat target simply stops resolving from its network policy — confirm at Phase 4, see plan).

## 3. Identity contract (the sensitive part)

- `theseus-mcp` forks IdentityResolver as-is: Keycloak JWT → `PipelineContext.user_id` + `auth_roles`. Callers present the **user's OBO bearer** (kantheon-security §2 — unchanged rule).
- `PipelineContext` propagates Theseus → Proteus/Argos/Kyklop → worker exactly as today (in-cluster trust model; zero-trust hop re-validation stays deferred, `kantheon-v1.1.md` §1).
- **Argos role resolution — default `bearer`, optional `whois` (Phase 5):** at Phase 3 (Stage 3.1) Argos ships **bearer-only** — `auth_roles` from the forwarded bearer's `realm_access.roles`, the `user_id → whois → roles` lookup removed, policy fixtures fork unchanged (Keycloak realm is the shared source). The **technical wave adds a configurable role source** (additive; does not change the Phase-3 default):

  ```hocon
  # services/argos — application.conf
  argos {
    roleSource = "bearer"            # bearer | whois   (default: bearer)
    roleSource = ${?ARGOS_ROLE_SOURCE}
    whois {
      baseUrl = "http://whois:7110"  # only read when roleSource = "whois"
      baseUrl = ${?ARGOS_WHOIS_BASE_URL}
      cacheTtlSeconds = 300
    }
  }
  ```

  - `bearer` (default): unchanged Phase-3 behavior — no whois dependency, no per-query hop.
  - `whois`: Argos resolves the base roles from the bearer **then enriches** them via whois, keyed by the bearer-trusted `user_id`, expanding the ERP role hierarchy the Keycloak token doesn't carry. Identity is still bearer-only (theseus-mcp edge); whois is a *role-enrichment* source, never an identity authority. Results cached (`cacheTtlSeconds`) so the hot path takes the whois hop at most once per user per TTL.

- **whois role-enrichment endpoint** (consumed only in `whois` mode): the forked whois service's existing `GET /whois?userId=<id>` returns the user's roles + hierarchy as JSON (`UserRecord` shape from `whois-common`). No new endpoint is invented — Argos's `WhoisRoleSource` is a thin client over what whois already serves. OPA bundle serving (`GET /bundle/{type}/roles.tar.gz`) forks unchanged and is independent of Argos.
- `kantheon-security.md` §1/§3.4 rewrite lands with fork Phase 3 (owner sentence swap: query-mcp edge → theseus-mcp edge; ai-platform Validator → Argos). Principle text unchanged. A Phase-5 addendum documents the optional `whois` role source and its security envelope (whois enriches, never authenticates).

## 4. Rule 6 / Rule 7

- **Rule 6:** every forked response keeps `repeated … ResponseMessage messages = 99;` but the type re-targets `org.tatrman.kantheon.common.v1.ResponseMessage` (the stand-in, **now canon** — the "await ai-platform's domain-free extraction" ledger item closes with the fork). Ariadne drops its package-local definition; the kantheon shape was modeled on it, so this is a package-path change only — **plus one deliberate, domain-neutral addition (Stage 2.1, 2026-06-13):** `string source_file = 4`, a generic source-attribution channel (the file/URI a message is raised against) carried over from ai-platform's metadata-local `ResponseMessage`. It is kept platform-generic (any file-loading service may set it), not metadata-specific, to preserve the domain-free promise of the stand-in. First consumer: Ariadne source-load warnings.
- **Rule 7:** `string argsJson`, camelCase keys — unchanged everywhere.

## 5. Cross-arc contract touches

| Contract doc | Change | When |
|---|---|---|
| `themis/contracts.md` | `Profile`/`Resolution` proto imports: `cz.dfpartner.nlp.v1` → `org.tatrman.kadmos.v1`; nlp-mcp/fuzzy-mcp endpoints → kadmos-mcp/echo-mcp | Themis switch-over stage (fork Phase 2 exit) |
| `charon/contracts.md` | `WorkerKind`/worker session endpoints: `cz.dfpartner.worker.v1` (ai-platform Maven) → `org.tatrman.worker.v1` (in-repo); Polars Worker references → Steropes | fork Phase 3 |
| `pythia/plan.md`, `golem/plan.md` pre-flights | query-mcp → theseus-mcp, metadata-mcp → ariadne-mcp pointers | fork Phase 4 (docs sweep) |
| `golem/{architecture,contracts}.md`, `golem/plan.md` | prompt source: per-pod git-fetch (`prompt_source.py`, `GOLEM_PROMPTS_GIT_*`) → Ariadne `get_prompts`; bundled YAML demoted to offline fallback | done 2026-06-13 (this edit set); consumed when Golem Stage 2.2 builds |
| `themis/contracts.md` | resolver prompt source (`prompts/resolver/`) → Ariadne `get_prompts` when Themis adopts the in-repo prompt path | optional, post fork Phase 2 (Themis already on the forked stack) |
| `capabilities-mcp` fixtures | seed `ToolCapability` fixtures for the four forked wrappers | fork Phase 2/3 as each lands |
| `kantheon-architecture.md` §4 proto table | add the §1 packages | fork Phase 4 |

## 6. Shared libs map

Forked into `kantheon/shared/libs/` in Phase 1 (names stay functional — libs are not personas):

| Lib | From | Used by (post-fork) |
|---|---|---|
| `kotlin/query-translator` | ai-platform | Proteus (engine), Argos, Theseus (plan cache) — the Calcite/RelNode core |
| `kotlin/db-common` | ai-platform | Brontes, Argos, Theseus |
| `kotlin/data-formatter` | ai-platform | theseus-mcp, workers |
| `kotlin/ttr-parser`, `kotlin/ttr-writer` | ai-platform | Proteus (Tatrman DSL) |
| `kotlin/fuzzy-common` | ai-platform (today a Maven dep) | Echo, echo-mcp, Themis — **Maven dep replaced by in-repo module** |
| `kotlin/otel-config`, `kotlin/logging-config`, `kotlin/ktor-configurator` | ai-platform (today Maven deps) | every Kotlin module — **Maven deps replaced by in-repo modules**; GitHub Packages consumption + PAT bootstrap removed |
| `python/otel-config` | ai-platform | Kadmos, Steropes (Metis adopts when convenient) |
| `kotlin/whois-common` | ai-platform (**Phase 5**) | whois service, Argos `WhoisRoleSource` — 3 domain records (`UserRecord`, `UserIdRecord`, `UserSource`); pkg → `org.tatrman.whois.domain` |
| `kotlin/keycloak-auth` (**new, extracted**) | ai-platform `erp-sql-common.auth` (**Phase 5**) | whois service — `CachingTokenProvider`, `KeycloakTokenProvider`, `TokenProvider`, `TokenResponse`; pkg → `org.tatrman.keycloak.auth`. Extracted because these 4 files have **no** imports from the rest of erp-sql-common, so the legacy line need not fork |

Still not forked: the rest of `erp-sql-common`, `erp-sql-metadata` (legacy line), `aip_security` (golem-only). Existing kantheon libs (`capabilities-client`, `envelope-render`, `envelope-ts`) unaffected. (`db-common`, a whois dependency, was already forked in Phase 1.3.)

**Metis amendment:** `metis/architecture.md` §"only Python module" → "first of the Python lane (with Kadmos, Steropes)"; uv/just/CI conventions are shared, settled by whichever lands first (plan Phase 1 pre-flight).

## 7. Config & deployment contracts

- Ports: assigned in the kantheon range alongside Charon (7250/7251/7252) — concrete numbers fixed in plan Phase 2/3 task lists, recorded in each module's `application.conf` + k8s base. The full reservation table follows.
- Secrets: named-connection / sealed-secret idiom (fabric-infra); Brontes's customer-DB connections follow Charon's `ConnectionRegistry` YAML pattern where applicable; Prometheus's upstream LLM keys via sealed secrets.
- `deployment/local`: + MSSQL manifest (reused from ai-platform local-infra), + Wiremock LLM fixtures, + Ariadne model fixtures.
- CI: fork extends `ci.yml` matrix with the new modules; Python lane (`test-py`) gains kadmos + steropes next to metis.

### 7.1 Port & namespace reservations

> Settled with fork Phase 1 Stage 1.1 (T5), 2026-06-12. Pattern: `service.HTTP.port` (probes + REST), `service.GRPC.port` (service-to-service; not all services expose gRPC), and `service-mcp.port` (thin MCP wrapper). The first column after the service name is **HTTP**; the second is **gRPC** (or "—" if none); the third is the **MCP wrapper** when one exists (always "—" when the service has no wrapper).
>
> All forked pods land in the existing **kantheon** namespace (same as `capabilities-mcp`, Charon, Metis). No new namespaces are introduced by the fork.

| Persona / Service       | Path                              | HTTP | gRPC | MCP wrapper  | Notes                                                                                       |
|-------------------------|-----------------------------------|------|------|--------------|---------------------------------------------------------------------------------------------|
| Charon (pre-fork)       | `services/charon`                 | 7250 | 7251 | 7252         | Reserved 2026-06-12 (pre-fork) — gRPC service-to-service, MCP thin wrapper                  |
| Metis (pre-fork)        | `services/metis`                  | 7255 | 7256 | 7257         | Reserved 2026-06-12 (pre-fork) — Python, FastAPI probes, gRPC `MetisService`                |
| **Ariadne**             | `services/ariadne`                | 7260 | 7261 | 7262         | Model graph; ClasspathStorage; in-memory at v1                                              |
| **Echo**                | `services/echo`                   | 7265 | 7266 | 7267         | Czech-aware fuzzy; algorithm cascade exposed in `Match`                                     |
| **Kadmos**              | `services/kadmos`                 | 7270 | —    | 7272         | Python NLP; HTTP service (no gRPC at v1 — kept as in ai-platform); `:7271` reserved, unused |
| **Proteus**             | `services/proteus`                | 7275 | 7276 | —            | Calcite/RelNode ↔ SQL translator; RPCs `ParseToRelNode` / `UnparseFromRelNode` kept          |
| **Prometheus**          | `services/prometheus`             | 7280 | 9090 | —            | LLM gateway; Spring Boot (forked as-is — no Ktor rewrite). gRPC kept at ai-platform's **9090** (the verbatim fork left it; reserved 7281 stays free) |
| **Argos**              | `services/argos`               | 7285 | 7286 | —            | Validator + RLS (sql-security folded in); bearer-role rework at Stage 3.1                    |
| **Kyklop**              | `services/kyklop`                 | 7290 | 7291 | —            | Worker dispatcher; HOCON worker-capability config                                          |
| **Brontes**             | `workers/brontes`                 | 7295 | 7296 | —            | MSSQL worker; implements `org.tatrman.worker.v1`                                            |
| **Steropes**            | `workers/steropes`                | 7300 | 7301 | —            | Polars worker (Python); implements `org.tatrman.worker.v1`                                   |
| **Arges**               | `workers/arges`                   | 7302 | 7303 | —            | **Postgres worker** (active arc 2026-06-23); implements `org.tatrman.worker.v1`; RLS `SET LOCAL app.tenant_id`; first bench Kyklops, takes the reserved gap; `7304` stays reserved |
| **Theseus**             | `services/theseus`                | 7305 | 7306 | 7307         | Query orchestrator + plan cache; IdentityResolver lives at the theseus-mcp edge             |
| whois (technical)       | `infra/whois`                     | 7110 | —    | —            | **Phase 5.** Kept from ai-platform (`WHOIS_SERVER_PORT`); own Postgres; OPA bundle server   |
| health (technical)      | `infra/health`                    | 7000 | —    | —            | **Phase 5.** Kept from ai-platform (`HEALTH_CHECK_SERVICE_PORT`); stateless                 |
| backstage (technical)   | `infra/backstage`                 | 7007 | —    | —            | **Phase 5.** Backstage default backend port; Node, own build                               |
| landing (technical)     | `frontends/landing`               | —    | —    | —            | **Phase 5.** Static Nginx bundle (no JVM port); served behind ingress                       |

**Block strategy.** The block starts at 7250 (Charon) and reserves **contiguous slots** in increments of 5 per service. `:7271` (Kadmos gRPC), `:7263` (reserved between Ariadne/Echo), `:7273/7274`, `:7277/7278/7279`, `:7281` (reserved for Prometheus gRPC but **unused** — the as-is fork kept 9090), `:7282/7283/7284`, `:7287/7288/7289`, `:7292/7293/7294`, `:7297/7298/7299`, `:7304` are reserved for future growth (extra ports per service — health-vs-admin, internal-vs-public, future gRPC additions) and for the remaining bench Kyklops (Pyrakmon, Halimedes, Euryalos, Elatreus, Trachios) landing in later arcs. **`:7302/7303` are now assigned to Arges** (the first bench Kyklops, active 2026-06-23) — it took the reserved Kyklops gap to stay adjacent to Brontes/Steropes. **Do not** assign a service to a reserved number without updating this table.

**Conflicts to watch.** Port 8080 is the local default for `tools/_smoke-test` and the smoke worker. The fork keeps 8080 reserved for placeholders / smoke modules — production services move to the 7250+ block above. Existing modules' `application.conf` files adopt the new port at the Stage that lands them. The technical-wave ports (7110 / 7000 / 7007) sit **below** the 7250+ pipeline block and are kept verbatim from ai-platform — no collision with reserved pipeline slots; they are recorded here so nothing in the block claims them later.

## 8. Technical-wave rebrand & catalog contract (Phase 5)

`landing` and `backstage` carry no wire contract; their "fork rename" is a content rebrand. This is the greppable spec so the sweep (plan Stage 5.6 / Phase 4 idiom) is mechanical:

- **landing** (`frontends/landing/src/i18n/locales/*.json`): `app.title` strings `"DF Partner AI Platform"` / `"DF Partner AI Plattform"` / `"DF Partner AI Platforma"` / `"AI Platforma DF Partner"` → the Kantheon equivalents across `en/de/cs/sk/hu`. `.env` link vars (`VITE_LINK_DEV_PORTAL`, `VITE_LINK_GRAFANA`, `VITE_LINK_ARGOCD`, `VITE_LINK_TRAEFIK`, `VITE_LINK_KEYCLOAK`) re-point at kantheon ingress hosts; `VITE_KEYCLOAK_*` to the kantheon realm/client. Health-roll-up source points at the forked `infra/health`.
- **backstage** (`infra/backstage/app-config*.yaml`): `organization.name: DF Partner` → Kantheon; `app.baseUrl` / `backend.baseUrl` / `*_BASE_URL` env to kantheon hosts; catalog locations and every module's `catalog-info.yaml` re-pointed at kantheon repo paths (the catalog should list the pantheon — Ariadne, Theseus, …, and the technical services — not ai-platform modules). TechDocs/scaffolder templates that reference ai-platform repos updated.
- **Greppable end state:** after Phase 5, `rg -i "df.?partner|ai-platform" frontends/landing infra/backstage` returns only historical/provenance notes.

---

*Doc owner: Bora. Wire shapes fork unchanged; this doc exists so every rename is deliberate and greppable.*
