# Hebe Integration Arc — Phased Plan

> **Status:** draft v0.1 — 2026-06-12. Follows [`planning-conventions.md`](../../planning-conventions.md) (task ≈ ½–1 day; stage ≈ 6 tasks, testable; phase = deployable).
>
> **Pre-reading:** [`/docs/architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) + [`contracts.md`](../../../architecture/hebe/contracts.md). Standalone internals: [`standalone-v1-architecture.md`](../../../architecture/hebe/standalone-v1-architecture.md). Completed standalone M0–M10 history: [`standalone/`](./standalone/).
>
> **Arc position:** Stream B (the Body). **Stream-B remaining-work order (master-plan, set 2026-06-24): Fork P5 → Hebe → Kleio** — Hebe is the **2nd** of the three remaining Body pushes (after the Fork Phase 5 infra wave). Independent of the Iris → Golem → Pythia order for Phases 1–3; **Phase 4 depends on iris-bff (Iris arc Phase ≥2 — i.e. master-plan M3)** and is fully lit by Pythia later. Branches `feat/hebe-p<n>-s<n.m>-<short>`; tags `hebe/v0.<phase>.x`.

---

## Phase overview

| Phase | Deliverable (deployable) | Gates / gated by |
|---|---|---|
| **P1 — Build & repo citizenship** | Hebe compiles, lints, tests green **inside the kantheon root build**; CI runs it; packages renamed | none — can start immediately |
| **P2 — Axes & profiles** | `hebe run` works in `local` profile exactly as before; the **axis model** (config §5) + four presets (`local`/`personal`/`server`/`k8s`) resolve and doctor-verify; `personal` offline tolerance (outbox, catch-up, circuit-breaker, byok fallback) lands; gateway/Keycloak/OTel/posture configurable against local-infra | P1 |
| **P3 — Postgres & instances** | A Hebe instance runs on local K3s: schema-per-instance PG, workspace + receipts in PG (`fs.durability=ephemeral`), Jib/Kustomize deploy, registered `non_routable`; the **`server` shape** (external PG + file workspace/receipts) validated as a side-effect of the axis split | P1, P2; Kantheon PG available in `deployment/local` |
| **P4 — Constellation client** | Scheduled routine → iris-bff turn → delivery to Telegram, E2E on local K3s (Golem question first; Pythia investigations light up when Pythia ships); the same client path runs from `personal`/`server` over the public ingress through the P2 outbox | P3 + **iris-bff deployed (Iris arc ≥ Phase 2)**; `TurnOrigin` co-design |

Tags: `hebe/v0.1.0` (P1) → `hebe/v0.2.0` (P2) → `hebe/v0.3.0` (P3) → `hebe/v0.4.0` (P4).

---

## Phase 1 — Build & repo citizenship

**Deliverable:** one `just test` at kantheon root builds and tests all 21 Hebe modules; `org.tatrman.kantheon.hebe.*` packages; standalone artifacts (shadowJar + `hebe.sh`) still produced.

### Stage 1.1 — Gradle merge

*Goal:* Hebe modules are kantheon root-build modules; hebe-local build machinery retired.

Pre-flight: kantheon root build green; inventory version conflicts between the two catalogs (Kotlin, Ktor, coroutines, serialization, Koog).

1. T1 Merge `agents/hebe/gradle/libs.versions.toml` into kantheon's catalog (conflict log; kantheon pins win; record exceptions).
2. T2 Add `:agents:hebe:modules:*` (21) to kantheon `settings.gradle.kts`; delete Hebe's `settings.gradle.kts`, wrapper, `gradle.properties`.
3. T3 Replace Hebe `build-logic` conventions with ai-platform build-convention plugins; port shadowJar packaging for `cli-app` into its module build (or a kantheon convention plugin if reusable).
4. T4 Wire `modules/detekt-rules` (mutation-funnel guard) into kantheon's lint pipeline; keep rule coverage identical.
5. T5 Fold Hebe `justfile` recipes into root `justfile` (`build hebe`, `test hebe`, `hebe-run-local`); delete `justfile.sample`.
6. T6 CI: kantheon `ci.yml` picks Hebe up via auto-detection; full pipeline green.

DONE: `just init && just lint && just test` green at root with Hebe included; `./agents/hebe/gradlew` gone; shadowJar artifact still builds.

### Stage 1.2 — Package rename & hygiene

*Goal:* `com.hebe.*` → `org.tatrman.kantheon.hebe.*` everywhere.

Pre-flight: Stage 1.1 green (rename rides on a working build).

1. T1 Mechanical rename of `main` sources + `package`/`import` rewrite.
2. T2 Rename `test` sources; fix Kotest discovery configs.
3. T3 Update resource-bound references: `META-INF/services/*` (PF4J), `plugin.properties`, reflection/classname strings, logback logger prefixes.
4. T4 Update `plugin-template` (template code + docs reference the new plugin-api package).
5. T5 Sweep docs/manuals in `agents/hebe/docs/` for the old package name.
6. T6 Full test suite + a built plugin-template smoke test against the renamed `plugin-api`.

DONE: zero `com.hebe` occurrences outside git history; tests green; tag `hebe/v0.1.0`.

---

## Phase 2 — Axes & profiles

**Deliverable:** axis model + four presets live. `local` behaves byte-for-byte like pre-merge Hebe; `personal`/`server`/`k8s` axis surfaces fully configurable and `hebe doctor`-verifiable against `deployment/local` infra (no PG storage yet — that is P3); `personal` offline tolerance implemented and tested.

### Stage 2.1 — Axis model + profile presets in `config`

*Goal:* axes resolve from a `profile` preset bundle, every axis overridable ([`contracts.md`](../../../architecture/hebe/contracts.md) §5). **No `when(profile)` anywhere downstream — subsystems read axes.**

1. T1 Tests first: axis-default matrix per profile (§5.1), override precedence (axis key > profile default; env > file), invalid-profile + invalid-axis failure, the `fs.durability=ephemeral` ⇒ workspace/receipts=postgres fail-fast assertion.
2. T2 `Axes` type + `Profile` preset bundles + resolution in the config module; `HEBE_PROFILE` env override.
3. T3 Thread resolved axes into boot sequence (no behaviour change yet under `local`).
4. T4 `instance_id` concept (required for postgres backends; `"local"` default).
5. T5 `hebe doctor` axis-aware check matrix (skeleton; checks added per stage below) — including the *probed-not-required* vs *required* split driven by `platform.availability`.
6. T6 `hebe onboard` asks profile first; `local` path unchanged.

DONE: `local` profile regression-identical (existing suite green untouched); axis/preset docs updated; preset smoke tests assert each profile wires the right axis values (test the axes, not the profiles — risks note).

### Stage 2.2 — LLM via llm-gateway + cost attribution

*Goal:* `k8s` default LLM target is llm-gateway through the existing OpenAI-compat client.

1. T1 Tests first: Wiremock'd gateway — base-URL/auth swap, streaming, tool use, capability probe.
2. T2 Gateway config defaults under `k8s`; secret-ref resolution for the key.
3. T3 Cost-attribution headers (`X-Cost-Center: hebe/<instance>`, `X-Turn-Ref`) — degrade gracefully when gateway ignores them (PD-11).
4. T4 `llm_calls` cost capture from gateway response metadata where present.
5. T5 doctor: gateway reachability + model availability check.
6. T6 Component test of the gateway turn against a Wiremock'd llm-gateway (base-URL/auth swap, streaming, cost-header capture) — per the testing policy (planning-conventions.md §4), mocked at the unit/component level. Manual verification against the real local llm-gateway via `just debug-tunnel` is a deploy-time check, deferred to the separate integration-test suite.

DONE: chat turn through the (Wiremock'd) llm-gateway recorded in `llm_calls` with cost at the component level; BYOK path untouched under `local` (live-gateway verification deferred to the separate integration-test suite).

### Stage 2.3 — Security: console-auth vs platform-identity split

*Goal:* split the two auth concerns onto their own axes ([`contracts.md`](../../../architecture/hebe/contracts.md) §5, arch §6). `console_auth` (password|oidc) and `platform_identity` (none|keycloak) resolve independently — **Keycloak OBO is needed by any `platform.reach != none` profile (`personal`/`server`/`k8s`), not k8s alone**; `local` stays password+keychain.

1. T1 Tests first: OIDC console login (Wiremock'd Keycloak), OBO mint for bound user via **both** grant paths (device-code+refresh for personal/server; client-credentials→OBO for k8s), unmapped-Telegram-chat rejection.
2. T2 `SecretsStore` seam: keychain impl (existing) + file impl + K8s-secret/env impl (the `secrets_backend` axis).
3. T3 Console auth driven by `console_auth` axis: OIDC when `oidc`, password path active when `password` (no compile-out — it's an axis, both real).
4. T4 Bound-user model: instance ↔ Keycloak user; OBO token service (cached, refreshed) — same model across all three platform-reaching profiles.
5. T5 Telegram `chat_user_map` enforcement whenever `platform_identity = keycloak`.
6. T6 doctor: Keycloak reachability + token mint + secret resolution checks (required vs probed per `platform.availability`).

DONE: console logs in via Keycloak on `oidc` profiles against local-infra Keycloak; OBO mint works from a simulated `personal` host (device-code path); receipts record acting identity.

### Stage 2.4 — Tool posture + OTel

*Goal:* restricted tool default under `k8s`; OTel via ai-platform `otel-config`.

1. T1 Tests first: posture matrix (restricted blocks shell/kubectl/git/filesystem; enable-list opt-ins; dispatcher refusal receipts).
2. T2 Posture resolution in `tools/dispatch` from profile config.
3. T3 Replace `observability` internals with `otel-config` adapter; `enabled=false` ⇒ true no-op (local).
4. T4 W3C trace-context propagation on outbound HTTP (gateway, future iris-bff).
5. T5 Span + metric set: routine fire, job run, tool call, channel delivery.
6. T6 Verify traces/logs in local Grafana stack (`just debug-tunnel`); doctor OTel check.

DONE: `k8s`-profile Hebe (still SQLite) runs locally with gateway+Keycloak+OTel against local-infra.

### Stage 2.5 — Offline tolerance (`personal` profile)

*Goal:* the one piece of genuinely new engineering in the four-profile expansion (arch §7.1). Gated by `platform.availability = intermittent`; harmless and enabled-but-idle on always-on profiles.

Pre-flight: Stages 2.2 (gateway client) + 2.3 (OBO) green — the outbox wraps both.

1. T1 Tests first: missed-trigger catch-up (process-down-over-cron → owed fire on boot) for each policy (`run_once_on_wake`/`run_all_missed`/`skip`); coalescing after a long sleep.
2. T2 Durable scheduler catch-up: evaluate past `next_run_at` on boot against per-routine policy; the `jobs` queue already persists — add owed-fire detection + coalescer.
3. T3 Outbox seam: iris-bff turn dispatch + channel deliveries become idempotent queue rows draining on connectivity behind a backend-agnostic `OutboxStore` (in-memory in P2; the durable SQLite/PG store — restart survival — is Phase 3); idempotent enqueue/drain (tests first).
4. T4 Runtime circuit-breaker / connectivity probe for gateway + iris-bff (distinct from the static axis); open ⇒ outbox holds, doctor reports *degraded*; half-open probe restores.
5. T5 LLM byok-fallback wiring (`llm.source = gateway_with_byok_fallback`): Hebe's own routines fall back when the breaker is open; `kantheon_question` turns defer (never fall back, never silent — "queued, will run when reconnected" channel note).
6. T6 Component test of the offline loop with a faked connectivity probe / circuit-breaker: simulate connectivity loss, fire routines, restore → assert owed fires catch up, outbox drains, byok-fallback served own-routines, deferred constellation turns run. Per the testing policy (planning-conventions.md §4): mocked at the component level (no real intermittent host); the live simulated-host run is deferred to the separate integration-test suite.

DONE: tag `hebe/v0.2.0`. Component tests prove a `personal`-profile Hebe survives a connectivity gap across a scheduled fire and reconciles on resume; always-on profiles unaffected (live-host verification deferred to the separate integration-test suite).

---

## Phase 3 — Postgres & instances

**Deliverable:** Hebe pod on local K3s, instance `hebe_dev`: PG memory/workspace/receipts, Jib+Kustomize, capabilities registration. Pre-flight for the phase: Kantheon PG in `deployment/local` with pgvector; `hebe` database created.

### Stage 3.1 — PG MemoryStore backend

*Goal:* second `MemoryStore` implementation; RRF parity proven.

1. T1 Golden fixture corpus + expected rankings; parity harness running both backends (tests first — this is the stage gate).
2. T2 PG migration set `db/migration-pg/` V1–V5 ports ([`contracts.md`](../../../architecture/hebe/contracts.md) §4.1–4.2); Flyway schema-targeted config.
3. T3 Exposed-DSL PG impl: docs/chunks CRUD + append-only conversations/messages.
4. T4 Hybrid retrieval: `ts_rank_cd` + pgvector HNSW candidates → shared RRF.
5. T5 `jobs`/`routines`/`llm_calls`/`tool_calls`/`pending_approvals`/`settings` on PG; append-only grants.
6. T6 PG-backend unit suite + RRF parity gate in CI, run against a mocked DB driver / in-memory fake exercising the Exposed-DSL query construction and the parity harness on fixtures. Per the testing policy (planning-conventions.md §4): mocked unit tests only; the real-Postgres/pgvector boot + parity-against-live-PG proof moves to the separate integration-test suite.

DONE: parity test green at the unit level on fixtures; `storage.backend=postgres` wiring asserted with a mocked driver (real-Postgres boot verified in the separate integration-test suite).

### Stage 3.2 — Workspace + receipts in PG

*Goal:* the two new tables; seams implemented.

1. T1 Tests first: `WorkspaceStore` contract tests run against both impls; receipts chain-verify tests (tamper detection).
2. T2 `WorkspaceStore` seam extraction; filesystem impl behind it (local unchanged).
3. T3 PG `workspace_files` impl with revision-based optimistic concurrency (V6).
4. T4 PG `receipts` impl (V7): append-only role grants, hash chain, Ed25519 signing key from secrets backend.
5. T5 Console workspace editor works against either backend; `--verify` command supports PG.
6. T6 Maintenance routines (daily digest, summariser, embedding refresh) green on PG.

DONE: full Hebe loop runs on PG with zero filesystem state besides logs.

### Stage 3.3 — Instance provisioning + K8s deploy

*Goal:* runnable pod per instance on K3s.

1. T1 Provisioning runbook script: schema + role + Flyway + Secret skeleton ([`contracts.md`](../../../architecture/hebe/contracts.md) §4.4).
2. T2 Jib image for `cli-app` (`hebe run` server mode as entrypoint); shadowJar path retained.
3. T3 Kustomize `k8s/{base,overlays/local}` parameterised by instance id (`imagePullPolicy: Never` local).
4. T4 Probes: `/healthz`, `/ready` (ready = config + PG + migrations + channels up).
5. T5 `just deploy hebe` + `just hebe-provision <id>` recipes.
6. T6 Deploy smoke on K3s: provision `dev`, deploy, converse via web console, routine fires, receipts verify — a deployment confirmation of the K3s bring-up capability (per the testing policy, planning-conventions.md §4, this is a smoke/demo, not an automated e2e test gate; automated in-cluster round-trip verification is deferred to the separate integration-test suite).

DONE: documented instance bring-up reproducible from clean K3s.

### Stage 3.4 — capabilities-mcp v0.2.0 + Hebe registration *(renamed 2026-06-12, cohesion review — this stage owns the post-v0.1.0 registry contract changes)*

*Goal:* Hebe visible in the registry, invisible to routing; the registry serves the 2026-06-12 manifest fields (`non_routable = 16`, `visibility_roles = 17`) that landed in `capabilities.proto` after `capabilities-mcp/v0.1.0` was tagged.

1. T1 Tests first: registration payload (manifest §2), heartbeat loop, warn-and-continue when registry down.
2. T2 `capabilities-client` integration; register at boot under `k8s`, heartbeat per config.
3. T3 Fixture `manifests/agents/hebe.yaml` in capabilities-mcp.
4. T4 capabilities-mcp: honour `non_routable` in the routing view served to Themis (exclude from routing list; keep in list/get/search).
5. T5 **capabilities-mcp: store + serve `visibility_roles` (PD-8)** — manifest loader, register/heartbeat, search/list/get surfaces all carry it (Themis does the per-request filtering — roles are per-caller; the registry only transports the declaration); seed fixtures updated with an example (`golem-hr: [kantheon-domain-hr]`).
6. T6 **Themis regression test: `non_routable` agents never appear in any `RoutingDecision`** (Layer 1 scoring, Layer 2 prompt, Layer 3 alternates) — pairs with Themis Stage 3.3 T0's routing-view derivation.
7. T7 Registry TTL/prune behaviour verified for Hebe instances coming and going.

DONE: tags `hebe/v0.3.0` + **`capabilities-mcp/v0.2.0`**. K3s Hebe registered, heartbeating, and provably unroutable; registry serves the PD-8/Hebe manifest fields.

---

## Phase 4 — Constellation client

**Deliverable:** "every Monday 03:00, ask X, message me the answer" works E2E on local K3s. **Gated by iris-bff deployed (Iris arc ≥ Phase 2).** First target is a Golem-answerable question (O-3); Pythia investigations require no Hebe-side change when they land.

### Stage 4.1 — iris-bff headless client

*Goal:* Hebe drives a chat turn through iris-bff with OBO identity.

Pre-flight: ~~`TurnOrigin`/`origin_ref` co-design~~ **landed 2026-06-12 (cohesion review)** — `iris/contracts.md` §1.2 + `iris_turns.origin/origin_ref`; iris-bff reachable in K3s.

1. T1 Tests first: Wiremock'd iris-bff — session create, turn POST, SSE consumption (envelope/step/done/error), reconnect, timeout.
2. T2 `hebe/v1` proto (`Routine`/`RoutineBody`/`RoutineRun`, [`contracts.md`](../../../architecture/hebe/contracts.md) §1.2) + codegen wiring.
3. T3 IrisBffClient module (`tools/builtin` "kantheon" tool family) using OBO token from Stage 2.3.
4. T4 Per-routine session management (create once, store `session_ref`, append turns).
5. T5 Stream-state mapping → `RoutineRun` statuses incl. `AWAITING_AGENT` on agent pause.
6. T6 Trace propagation: one trace cron-tick → iris-bff → agent → delivery.

DONE: manual routine run produces a turn visible in Iris session history.

### Stage 4.2 — Routine type + delivery loop

*Goal:* the product loop, scheduled and delivered.

1. T1 Tests first: `kantheon_question` routine lifecycle — fire, run, deliver, retry-on-failure, never-silent-failure.
2. T2 `routines.body_kind = kantheon_question` (+ `session_ref`, `last_turn_ref` columns); console CRUD for it.
3. T3 Envelope→channel rendering: conclusion text + artifact counts + deep link into Iris (no chart rendering in Telegram).
4. T4 Delivery records + failure notifications per routine policy; receipts for the full run.
5. T5 `AWAITING_AGENT` channel message with Iris deep link (human resumes in Iris — v1 rule).
6. T6 Demo on K3s: cron-fired Golem question delivered to Telegram; runbook + screencap for the docs; close PD-10 layer 3 with a Resolution pointer. (A deployment demo of the scheduled-delivery capability — per the testing policy, planning-conventions.md §4, automated end-to-end verification of this chain is deferred to the separate integration-test suite.)

DONE: tag `hebe/v0.4.0`. PD-10 (scheduled work) and PD-2 (out-of-band notification path) updated with Resolution entries pointing here.

---

## Cross-arc coordination

| With | What | When |
|---|---|---|
| Iris arc | ~~`TurnOrigin` + `origin_ref` on `ChatTurnRequest`~~ landed in iris contracts 2026-06-12; remaining: machine-client OBO acceptance (standard bearer validation) + "scheduled" badge implementation (Iris Stage 4.1) | before Hebe Stage 4.1 |
| Themis arc | routing-view exclusion of `non_routable` (capabilities-mcp side done in Hebe Stage 3.4 T4; Themis-side regression in T5) | Stage 3.4 |
| ai-platform | llm-gateway cost-attribution headers (optional, graceful degrade) | Stage 2.2 |
| Pythia arc | nothing required from Hebe; scheduled investigations work via iris-bff once Pythia is routable | post-P4 |
| deployment/local | Kantheon PG with pgvector; Keycloak realm for dev | before P2.3 / P3 |

## Risks

- **Catalog merge surprises (P1):** Hebe pins (Kotlin 2.3.20, Gradle 9, Koog version) vs kantheon canon — resolve in Stage 1.1 T1 conflict log before touching code; biggest unknown of the arc.
- **Offline tolerance is real engineering, not plumbing (P2.5):** outbox + catch-up + circuit-breaker are the only new mechanisms in the four-profile expansion; mis-scoping them as "config" under-budgets P2. Idempotency of enqueue/drain and the owed-fire coalescer are the sharp edges.
- **Axis combinatorics in CI:** four profiles naively quadruple the doctor/posture/component test matrices. Mitigation (baked into Stage 2.1 T6, and aligned with the testing policy — planning-conventions.md §4): unit-test each seam's impls (mocked) + a handful of cheap **preset smoke tests** asserting axis wiring; do **not** fan the separate integration-test suite out ×4 — the mocked-unit gate carries the per-profile coverage.
- **RRF parity across tokenizers (P3.1):** FTS5 porter vs PG text-search will diverge on edge queries; the golden set is the arbiter — budget a tuning pass.
- **Iris arc timing (P4):** if iris-bff slips, P4 stalls; P1–P3 are unaffected and worth shipping regardless.
- **Real-Postgres/pgvector coverage (P3):** PG/pgvector behaviour (HNSW recall, `ts_rank_cd`, RLS) cannot be H2-faked, so it is **not** covered by the in-repo unit suite. Per the testing policy (planning-conventions.md §4) those plans develop against mocked drivers / in-memory fakes; real-PG verification (including RRF parity against a live instance) lives in the **separate integration-test suite**, not in CI's mocked-unit gate. (This supersedes the old "Testcontainers vs Hebe's no-testcontainers rule" tension — no Testcontainers in the unit suite.)

---

*Plan owner: Bora. Per-stage task lists land as `tasks-p<phase>-s<phase.stage>-<short>.md` in this directory when each stage starts.*
