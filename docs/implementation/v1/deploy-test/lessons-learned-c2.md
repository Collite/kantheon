# WS-C2 lessons learned — the bp-dsk integration debugging arc (2026-07-08)

> Captured after MP-4 (all five contexts green via `just it-bp-dsk-all`). This is the field guide
> for the next person standing a service up on a real cluster: the schema/type traps, the
> resource/cold-start traps, the fixture-mode boundaries, and the tooling gotchas — each as
> **symptom → root cause → fix → lesson**. Companion to [`runbook-bp-dsk.md`](./runbook-bp-dsk.md) and
> [`tasks-c2-integration-contexts.md`](./tasks-c2-integration-contexts.md).

Contexts touched: `tpcds-query`, `golem-erp`, `themis-routing`, `theseus-runquery`, `pythia-rca`.

---

## 1. Schema & type mismatches (the big theme)

The single richest source of failures. The recurring meta-lesson: **a "schema" means three different
things in this stack — a Calcite namespace, a model-fixture table set, and a proto/JSON wire shape —
and they are defined in different places, with different defaults, and do NOT validate against each
other until a live request crosses all three.**

### 1.1 Where models/schemas are defined (and where they are NOT)

| Concern | Defined in | NOT a schema source |
|---|---|---|
| Proteus fixture model (tables/columns for validation + unparse) | `services/proteus/.../model/BootFixtureModel.kt` — a **hand-written Kotlin builder** (`ModelTable`/`ModelColumn`), NOT a TTR text file | Ariadne is not deployed in `theseus-runquery`/`tpcds-query`'s fixture path; the tatrman TTR toolchain is not involved in fixtures |
| Argos RLS policy | `services/argos/.../resources/policies/policies.conf` (HOCON) — **but loaded only in non-fixture mode** | In `ARGOS_USE_FIXTURE_MODEL=true` the SecurityClient is a stub that returns **zero policies** |
| MSSQL seed (real table + rows) | olymp `platform/data/mssql/base/init-sql-configmap.yaml` (`dbo.sample_orders`, `kantheon_local`) | — |
| capabilities-mcp agent/tool manifests | image classpath `src/main/resources/manifests/{agents,tools}/*.yaml` (seed fixtures) | — |
| Proto wire contracts | `shared/proto/.../{themis,pythia,...}/v1/*.proto` | — |

### 1.2 A namespace is NOT a Calcite schema — reference tables UNQUALIFIED

- **Symptom.** `theseus-runquery` result query failed: `CalciteContextException: Object 'dbo' not found`
  (from Proteus `validation_failed`, echoed by Theseus `translator_rejected`).
- **Root cause.** The test SQL wrote `SELECT … FROM dbo.sample_orders`. In the ttr-translator Calcite
  catalog, the model's **namespace `dbo` is not exposed as a Calcite schema object** — so `dbo.` fails
  to resolve. Tables are referenced **bare**, and Calcite resolves them against the model's **default
  DB namespace, which is `dbo`** (Proteus `TranslatorServiceImpl` uses `"dbo"` as the DB default;
  Theseus sets `targetSchema=DB`).
- **Proof it was the qualifier.** Proteus' own `WARMUP_SQL` does `FROM QSUBJEKT` (unqualified) and the
  green `tpcds-query` does `FROM store_sales` — both unqualified, both work. Proteus **re-qualifies to
  `dbo.sample_orders` on unparse to the MSSQL dialect**, so Brontes still hits the right physical table.
- **Fix.** `FROM sample_orders` (unqualified) in `RunQueryIntegrationSpec`. Test-only — no rebuild.
- **Lesson.** Source SQL is written against the **logical model default namespace**, not the physical
  schema. The physical `dbo.` qualifier is Proteus' job on unparse. If you see `Object '<ns>' not
  found`, drop the qualifier before suspecting the model.

### 1.3 The fixture model has to actually contain the table

- **Symptom.** Earlier `theseus-runquery` run: `detection_failed` (before Calcite even validated).
- **Root cause.** Proteus' fixture model had only `dbo.QSUBJEKT`; schema auto-detection couldn't
  classify a query over `sample_orders`, and the `query` tool exposes no schema hint.
- **Fix.** Added `dbo.sample_orders(id, tenant_id, region, amount)` to `BootFixtureModel` **aligned
  with the mssql-init seed** — same columns, same names. Requires the Proteus `:testing` image
  republished (the fixture is compiled into the JAR).
- **Lesson.** `detection_failed` = "no model knows this table," distinct from `validation_failed` =
  "the model knows the namespace but not this table/column." They are different stages; fix the right
  one. Keep the fixture model and the seed **column-for-column identical**.

### 1.4 `SurfaceType` has no DECIMAL

- **Symptom.** (Caught at author time.) An Explore report suggested `SurfaceType.DECIMAL`; it doesn't
  exist — the enum is `TEXT, INT, FLOAT, BOOL, DATETIME`.
- **Fix.** `amount` (MSSQL `DECIMAL(18,2)`) is modelled as `SurfaceType.FLOAT`. A **bare projection
  never type-coerces**, so MSSQL returns the real `DECIMAL` values as-is; the surface type only feeds
  Proteus' internal validation.
- **Lesson.** The logical surface type need not exactly match the physical column type for a bare
  `SELECT col` — pick the closest available and let the engine return the real values. Verify enum
  values against the jar (`javap`), don't trust a suggested constant.

### 1.5 Argos fixture mode applies NO policies (a wrong assumption corrected)

- **Symptom / assumption.** An earlier note claimed the deployed Argos fixture enforced
  `tenant_isolation` (row-level). The RLS test expected column-DENY. Both were unreachable.
- **Root cause.** `ARGOS_USE_FIXTURE_MODEL=true` wires a **stub SecurityClient that returns an empty
  policy set** (`Application.kt`: "no row-level policies applied"). The `tenant_isolation` HOCON policy
  loads **only in non-fixture mode**, which needs an Ariadne metadata source. So neither column-DENY
  nor row-level was enforced — RLS is simply not testable in this context.
- **Fix.** Split the test gate: `resultAlignedContext` (result rows) vs `rlsPolicyContext` (OFF, RLS
  deferred to a richer Ariadne-backed context). Also: Argos' engine column-rules are **unconditional**
  (no role-based column DENY exists yet).
- **Lesson.** "Fixture mode" for a policy/security service usually means **no enforcement**, not
  "default enforcement." Verify what the fixture stub returns before designing an assertion on it.

### 1.6 Proto/JSON wire shapes differ per service — and per outcome branch

- **themis REST `/v1/resolve`** speaks **proto-canonical JSON via `JsonFormat`** (mirror iris-bff's
  `HttpThemisClient`), even though the server installs only `json(McpJson)` (kotlinx). The MCP
  `resolve` tool returns its outcome as a **JSON object inside a text content block**, not
  `structuredContent`.
- **pythia** speaks **proto-JSON** on `POST /v1/investigations` (via its `ProtoJson` helper) and
  returns `{id, status}` (202) then the `InvestigationArtifact` proto-JSON on GET.
- **themis `trace_id` is emitted only on the `resolved` and `refused` outcome branches — NOT on
  `awaiting_clarification`.** A robust assertion that required `trace_id` failed on the (expected)
  degraded `awaiting_clarification` path. **Lesson: the response JSON shape varies by outcome branch;
  don't assert a field that only some branches carry.** `isError=false` already proved NLP ran.
- **`CallToolResult.isError` is `Boolean?` (nullable).** `!res.isError` doesn't compile; the non-error
  guard is `res.isError != true`.

### 1.7 Identity shapes differ per agent

- **pythia** uses a **v1 structural bearer**: `Bearer <userId>` or `Bearer <userId>#role1,role2` —
  **not a JWT**. theseus/golem/themis use an unsigned JWT (`realm_access.roles`, Keycloak shape).
- **pythia returns 403 for BOTH** a missing bearer (Rule-6 code `unauthenticated`) **and** a cross-user
  read (code `forbidden`) — there is **no 401**. Distinguish by the Rule-6 `messages[].code`, not the
  HTTP status.
- **Lesson.** Don't assume a uniform auth wire across agents. Read each service's `Admission`/auth
  before writing the identity assertions.

---

## 2. Memory, CPU & cold-start timeouts

The bp-dsk test nodes are **CPU-throttled and memory-bounded**; first-request costs that are invisible
locally dominate on-cluster. Three distinct flavours bit us.

### 2.1 Kadmos OOM on model load → `Connection refused`

- **Symptom.** themis-routing: a warm-up NLP call **succeeded** (graph reached the LLM legs), then the
  asserted call failed at **4 ms with `java.net.ConnectException: Connection refused`**.
- **Root cause.** Kadmos (Python NLP) loads spaCy/NLP models into memory on the first `/v1/analyze`.
  The `1Gi` limit **OOM-killed the pod mid-load** right after it served the warm-up; the next connect
  hit a restarting pod. `4 ms` + `Connection refused` = **the target is down**, not slow.
- **Fix.** Kadmos memory `limits 1Gi→2Gi` (requests `512Mi→768Mi`) **and** a `resolveUntilOk` retry
  (4×, 8 s apart) that survives a restart.
- **Lesson.** A **fast** `Connection refused` (single-digit ms) is a down/restarting target, not a
  slow one — look for an OOM/restart, not a timeout. Memory-heavy Python services need headroom for
  lazy model loads; the Deployment going `Available` does **not** mean the model is loaded.

### 2.2 Kadmos lazy-load blows Themis' 30 s NLP timeout

- **Symptom.** Before the OOM surfaced: `HttpRequestTimeoutException [url=http://kadmos:7270/v1/analyze,
  request_timeout=30000 ms]`, so `resolve` returned `isError=true`.
- **Root cause.** The cold first `/v1/analyze` (model load) exceeded Themis' **internal** 30 s NLP
  client timeout — distinct from the test's 3-min **MCP-client** timeout (bumping the outer one doesn't
  help; the service gives up internally first).
- **Fix.** `NLP_MCP_TIMEOUT_MS` **and** `NLP_SERVICE_TIMEOUT_MS` = `120000` (both names — one overrides
  the HOCON value, the other is `ResolverConfig`'s env fallback; set both to be precedence-safe). Plus
  a test-side warm-up so the asserted call runs warm. Env-only — no rebuild (HOCON `${?…}`).
- **Lesson.** Distinguish **client-outer** vs **service-internal** timeouts — a generous test timeout
  is useless if an intermediate service has its own tighter sub-timeout. Grep the service config for
  every `timeout` on the call path.

### 2.3 Prometheus slow boot (from the golem-erp leg)

- **Symptom.** Prometheus CrashLoopBackoff.
- **Root cause.** ~97 s Spring Boot start on a throttled node exceeded the 90 s liveness budget; also a
  full `/actuator/health` 503'd on the optional Redis indicator.
- **Fix.** `startupProbe` on the **readiness** group (`/actuator/health/readiness`, 10 s + 40×10 s =
  410 s budget) so kubelet suspends liveness/readiness until startup succeeds; `management.health.redis
  .enabled=false`. Also `SPRING_PROFILES_ACTIVE=test` (H2, no PG member).
- **Lesson.** Use a **startupProbe** for slow-booting services on throttled nodes; point liveness at a
  state-only group, never a full `/health` that fails on optional dependencies.

### 2.4 First-query cold compile (tpcds/theseus)

- The first Calcite parse pays ~40–50 s of JVM/JIT/Janino/planner static-init (model-independent) —
  Proteus warms it at boot with `WARMUP_SQL`. The first real query on a cold Arges pool also ran ~20 s.
- **Lesson.** Governing timeouts on the **first** request must budget cold-compile + cold-pool; the
  `McpQueryDriver` disables the CIO engine's 15 s default and governs with a 3-min MCP timeout instead.

---

## 3. Fixture-mode boundaries (what "fixture" silently disables)

Every service has a `use-fixture` (or DB-disabled) mode for cheap wiring tests. Each **silently
disables** something you may actually need. Map before assuming.

| Service | Flag | What fixture mode disables |
|---|---|---|
| Proteus | `PROTEUS_USE_FIXTURE_MODEL` | Real model → uses `BootFixtureModel` (only the tables you added) |
| Argos | `ARGOS_USE_FIXTURE_MODEL` | **All policy enforcement** (empty SecurityClient) |
| Brontes | `BRONTES_USE_FIXTURE` | Real JDBC pool → **advertises no connection** (`no_worker_for_connection`) |
| Kyklop | `KYKLOP_USE_FIXTURE` | **The CapabilityPoller** — so no worker's connections are ever learned |
| Pythia | `PYTHIA_DB_ENABLED=false` | Postgres → in-memory repos (fine); `/ready` is UP unconditionally |

- **Lesson.** `use-fixture` is not "lighter but equivalent" — it removes a capability. For a real-data
  assertion you often must turn OFF **more than one** fixture flag in concert (Brontes **and** Kyklop),
  and the failure only names the last hop.

---

## 4. Worker dispatch & connection wiring (the `df-test` saga)

Turning `theseus-runquery` from a wiring proof into real MSSQL rows was the deepest thread.

- **How routing actually works.** Kyklop does **not** hold a static `connection_id → worker` map. Each
  worker endpoint is configured (`KYKLOP_WORKER_BRONTES_ENDPOINT=brontes:7296`); Kyklop's
  **CapabilityPoller** polls each worker's `GetCapabilities`, which advertises its
  `supportedConnections`; the registry then answers "who serves `df-test`?" dynamically. Error `No
  HEALTHY worker advertises connection_id=df-test` means: Kyklop reached Brontes, but Brontes
  advertised nothing (fixture mode).
- **No chicken-and-egg.** Brontes advertises `df-test` from **config** (`ConnectionPoolManager
  .supportedConnections = configs.keys`) — **before** the pool is lazily opened on first query — so
  Kyklop learns it even though no MSSQL connection is open yet, and Brontes is `/ready` on
  `supportedConnections.isNotEmpty()`.
- **The connection template was commented out** in `brontes/application.conf`, and
  `ConnectionPoolManager.fromConfig` **threw** on a block with a missing `host`. Uncommenting it
  as-is would have crashed **every** fixture deployment (bp-dsk prod) that doesn't set `BRONTES_DB_*`.
  **Fix:** make the loader **skip a host-less block** (inert-when-unset), so a shipped env-driven
  template is dormant until an overlay supplies `BRONTES_DB_HOST/…`.
- **The credential** comes from the harness's per-run `mssql-sa-secret`/`SA_PASSWORD` — Brontes
  references the **same** secret the mssql pod uses, so it matches by construction.
- **Lesson.** For dynamic worker-registry systems, the fix for "no worker for X" is almost always
  **worker-side** (advertise X) — but only if the poller is running (Kyklop non-fixture) and the
  worker endpoint is configured. A shipped config template that reads secret env vars must be
  **inert when those vars are absent**, or it becomes a boot-time landmine for every other deployment.

---

## 5. Boot behaviour: fail-fast vs self-heal vs degrade

Knowing a service's boot contract tells you the minimal context and the failure mode.

| Service | Boot contract |
|---|---|
| **themis** | **Fail-fast** `assertRoutableAgentsAvailable` — refuses to start unless capabilities-mcp returns ≥1 agent. **Self-heals** via CrashLoop-restart once capabilities-mcp is up (unlike golem's cache-empty race). |
| **pythia** | Only **DB migration** is fail-fast (skipped when `PYTHIA_DB_ENABLED=false`). All downstream URLs blank → in-process stubs; capabilities registration is warn-and-continue → **boots standalone**. |
| **golem** | One-shot Ariadne model-load at boot with **no retry** — a race caches empty and the pod stays **permanently not-ready** (fixed with a `wait-for-ariadne` initContainer). |
| **capabilities-mcp** | Serves **classpath seed manifests** (`classpath:manifests` → golem-erp/pythia/hebe) — jib **explodes** resources onto the classpath (`file:`, not `jar:`), so the loader's directory scan works. Starts non-empty → satisfies themis' gate with no agents deployed. |

- **Lesson.** "Fail-fast then self-heal via restart" and "cache-empty then stay dead" look identical for
  ~30 s but need opposite fixes (wait vs nothing). Check whether a boot-time dependency load **retries**.

---

## 6. Graceful-degradation matrix (which legs tolerate a stubbed/absent dependency)

Whether a downstream failure degrades or hard-fails determines how tolerant an assertion can be.

| Path | On dependency failure |
|---|---|
| themis LLM legs (`classify`/`filter`/`joint`/`route`) | **Degrade** (`.getOrElse` fallback) → terminates as `awaiting_clarification`/`refused`, never a crash |
| themis **NLP (Kadmos)** + **fuzzy (Echo)** legs | **Hard-fail** → whole resolve `isError=true` |
| pythia LLM legs (planner/synth/eval) | **Hard-fail** → investigation `FAILED` (no `.getOrElse`) |
| golem PlanComposer LLM | `PlanDecodeException` → routes to clarification (not `STATUS_DONE`) |

- **Lesson.** "Graceful LLM degradation" does **not** imply graceful degradation of *every* dependency.
  themis-routing's robust tier tolerates an empty WireMock (LLM stubbed) precisely because the LLM legs
  degrade — but it is fragile to Kadmos/Echo being down, which is why the retry was needed.

---

## 7. Tooling & harness gotchas

- **`just publish-image` assumed image name == module basename.** `agents/themis` publishes as
  `themis-mcp` (its chart `image.repository` + serviceName), so the recipe couldn't produce it. Added
  an optional 3rd arg: `just publish-image agents/themis testing themis-mcp`.
- **`just tag all` scans only `agents/ tools/ frontends/ shared/libs/kotlin/`** — it **skips
  `services/`, `workers/`, `infra/`**, exactly where most deployables live. Tag those by path
  (`just tag services/proteus minor`). Bare `just tag <name>` also can't resolve services/workers.
- **No project version in the repo.** `libs.versions.toml` holds only third-party dep versions; the
  project version is a build-time `-Pkantheon.version` property (default `0.0.0-SNAPSHOT`). Git tags
  are the sole source of truth (the contracts §9 "coordinate with libs.versions.toml" note is stale).
- **The `it-bp-dsk` failure log-dump was blind.** It listed `theseus-mcp` but not `themis-mcp` (nor
  `pythia`), so the service under test never showed; and OTEL exporter noise flooded the error grep.
  Fixed: added the missing services + a `grep -v` that strips the OTEL lines first.
- **OTEL export noise is universal and harmless.** Every service retries `localhost:4317` even with
  `telemetry.enabled=false` (the SDK still targets the default endpoint). It is **not** a failure —
  filter it out and look past it. (A real fix — fully disabling the SDK when telemetry is off — is a
  separate chart/image concern.)
- **In-cluster WireMock starts EMPTY.** Fixtures must be pushed at runtime via `WireMockAdmin`
  (`reset()` + `importMappingsFromResource(...)`); an authored `mappings.json` that is never loaded
  yields a 404 (`No response could be served as there are no stub mappings`).
- **Prometheus gateway path + model aliases.** Clients POST `/v1/chat/completions`, but the controller
  served only `/api/v1/...` (added a `/v1/chat` alias); `findByName` is an **exact** match, so tier ids
  `haiku`/`sonnet` fell through to the Azure default until aliased in `models.yaml`.

---

## 8. A checklist for the next integration context

Distilled from the above — do these before the first live run:

1. **Trace every timeout on the call path** — client-outer *and* each service-internal sub-timeout.
   Budget the first request for cold JVM/model/pool loads (seconds→minutes on throttled nodes).
2. **Map each `use-fixture`/DB-disabled flag** on the path and know what it disables; a real-data
   assertion may need several turned off together.
3. **Confirm the model/fixture contains the exact table+columns** the query names, and reference tables
   **unqualified** (let the translator re-qualify on unparse).
4. **Read each service's auth `Admission`** — bearer shape, and which HTTP codes/Rule-6 codes it uses.
5. **Know the response JSON shape per outcome branch** — don't assert a field only some branches carry.
6. **Size memory for lazy model loads** (Python NLP ≥ 2Gi); a fast `Connection refused` is a
   down/restarting pod, not a slow one.
7. **Give slow-booting services a `startupProbe`**; point liveness at a state-only health group.
8. **Push WireMock stubs at runtime**; author the fixture *and* load it.
9. **Make the failure dump cover the service under test** and strip OTEL noise before you trust it.
10. **Prefer env/config/test-side fixes** (no rebuild) before an image change; when an image change is
    unavoidable, make shipped config templates **inert when their env is unset**.
