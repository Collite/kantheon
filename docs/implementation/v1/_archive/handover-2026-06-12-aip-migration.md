# Handover — ai-platform → kantheon migration architecture session

> **EXECUTED 2026-06-12 — with a reframe.** The session happened; Bora reframed the task: **fork, not migration** (copy-paste; ai-platform stays untouched, maintenance-only; kantheon becomes a self-contained platform; zero cross-repo coupling at end state). Deliverables: [`../../architecture/fork/architecture.md`](../../architecture/fork/architecture.md) + [`../../architecture/fork/contracts.md`](../../architecture/fork/contracts.md) + [`fork/plan.md`](./fork/plan.md). Per-question outcomes vs §2: move/stay/split → fork roster (incl. Prometheus = llm-gateway, Argos = validator + sql-security fold); Maven inversion → moot (no publishing in either direction); query-mcp sensitivity → fork plan Phase 3 with dedicated security review; §4 open items → 1 closed (ResponseMessage canon), 2+3 absorbed into Prometheus backlog, 4 closed as superseded, 5 resolved (zero runtime writes remain). The §5 naming trap was overridden: services get pantheon names (two-tier rule, CLAUDE.md §2). This doc is now historical.
>
> **Written:** 2026-06-12, end of the cohesion-review session (see [`review-2026-06-12-v1-cohesion-findings.md`](./review-2026-06-12-v1-cohesion-findings.md), EXECUTED). **For:** the next session — the architectural task Bora queued: **decide the gradual migration of platform-grade services out of `ai-platform` into `kantheon`**, extending the boundary shift that Charon and Metis started. This doc tells you what is already decided, what to read, what the session must produce, and the traps the cohesion review left flags on.
>
> **Sequencing note:** this task runs **before** per-stage task-list writing (which would otherwise start with Iris Stage 1.1). If the migration decisions don't touch the Iris arc's pre-flights, task-list writing can resume in parallel afterwards.

---

## 1. What is already decided (do not reopen)

1. **The direction itself** (CLAUDE.md §1, locked 2026-06-12): platform-grade services migrate *gradually* out of ai-platform into kantheon. Not a big bang; per-service arcs.
2. **The precedent — two migrated services exist on paper:** Charon (`services/charon`, Kotlin, gRPC `org.tatrman.charon.v1`, thin `tools/charon-mcp`) and Metis (`services/metis`, **Python** by library-moat exception, `org.tatrman.metis.v1`, thin Kotlin `tools/metis-mcp`). Both have full three-artefact arcs (architecture/contracts/plan).
3. **The conventions a migrated service inherits:**
   - Package root `org.tatrman.<service>.v1` — *not* `org.tatrman.kantheon.*` (reserved for constellation/agent contracts).
   - **Kotlin unless a library moat says otherwise** (Metis = statsmodels/Prophet; same rule that keeps `infra/nlp` Python).
   - Service-vs-MCP rule: logic in `services/`; MCP servers are thin wrappers in `tools/`.
   - Tags `<service-dir>/v<semver>`; branches `feat/<svc>-p<n>-s<n.m>-<short>`; planning-conventions hierarchy; three artefacts before task lists.
4. **What explicitly stays in ai-platform "for now"** (CLAUDE.md §1): `query-mcp`, `metadata-mcp`, `fuzzy-mcp`, `nlp-mcp`, `llm-gateway`, `infra/nlp`, shared Kotlin/Python libs. *This session's job is to decide whether/when/in what order "for now" ends for each.*
5. **Cohesion-review decisions that constrain the migration** (all 2026-06-12, kantheon-architecture §13):
   - One Kantheon PG, DB per agent (§7.1) — a migrated service that needs persistence gets a database there.
   - Rule-6 = kantheon `common/v1` `ResponseMessage` stand-in (D1) — migrated services adopt it on arrival (Charon/Metis contracts already swapped).
   - OBO discipline + forwarded bearer (D3/D7, `kantheon-security.md`) — anything on the data path must preserve it.

## 2. What the session must produce (exit criteria)

A new cross-cutting doc — suggested home: **`docs/architecture/kantheon-migration.md`** (architecture) + **`docs/implementation/v1/migration/plan.md`** (sequenced waves), following the usual two-artefact-then-plan shape. It must answer, per candidate service:

| Question | Why it matters |
|---|---|
| **Move / stay / split?** | Some candidates are really two things (e.g. an MCP wrapper that could move while its engine stays) |
| **Wave + trigger** | What gates the move (e.g. "after Golem cutover", "when Themis stops being the only consumer") |
| **Package + repo target** | `org.tatrman.<service>.v1`, `services/` vs `tools/` vs `shared/` |
| **Proto consequences** | What `cz.dfpartner.*` imports break; whether consumers in ai-platform remain (→ reverse Maven publishing?) |
| **Maven consequences** | Today's direction is strictly ai-platform → kantheon. Any lib/proto move that leaves consumers behind **inverts the publish direction** — decide whether kantheon starts publishing to GitHub Packages or the consumer moves too |
| **Identity/security consequences** | `IdentityResolver` + Validator RLS live at the query-mcp edge (kantheon-security §1). Moving query-mcp moves the RLS enforcement point — THE most sensitive move on the list |
| **Deployment/local + fabric-infra consequences** | Namespace, secrets, Kustomize, the `deployment/local` infra stage (still unowned/empty — cohesion review §3.7 put its spec in kantheon-architecture §7.1) |

## 3. The candidate inventory (verify against ai-platform at session start)

From this repo's docs + memory (`ai_platform_map.md`); **re-verify against `ai-platform/CLAUDE.md` at HEAD — this list may be stale:**

- **Tool services:** `query-mcp` (data path; IdentityResolver edge), `metadata-mcp` (model graph; Golem PackageContext + Shem bootstrap dependency), `fuzzy-mcp` (Czech-aware fuzzy; Themis dependency), `nlp-mcp` (front of `infra/nlp`).
- **Gateways/infra:** `llm-gateway` (every agent's LLM path; tier routing + cost attribution asks pending), `infra/nlp` (Python NLP foundation — heavyweight, likely stays), `infra/whois` (Keycloak), `infra/sql-security` (OPA).
- **Workers:** `workers/polars` (Pythia DataFrameNode + Charon sessions), `workers/postgres` (Midas parallel track, `aip-v1-pg-worker-plan.md`), `workers/mssql`.
- **Shared libs:** `otel-config`, `fuzzy-common`, `ktor-configurator` (incl. the MCP/Ktor base — **`mcp-server-base` does not exist**; don't resurrect it), `logging-config`, build-convention plugins, `shared/proto` (`cz.dfpartner.*`).

**Decision heuristic worth honoring** (the one that put Charon/Metis first): kantheon-owned = things whose *only* consumers are kantheon agents; ai-platform keeps what serves the platform independently of the constellation. The interesting fights are the services with consumers on both sides.

## 4. Open items this session inherits (resolve or explicitly re-park)

1. **Domain-free `cz.dfpartner.common.v1.ResponseMessage` extraction** (`kantheon-v1.1.md` §1) — if `shared/proto` ownership moves, this ask changes shape or disappears (kantheon stand-in becomes the canon). Decide.
2. **`aip-v1-gateway-worker-plan.md`** (unwritten; due at Pythia Phase 3) — llm-gateway tier routing + Polars workspace read-out. If llm-gateway or workers are migration candidates, fold that doc's scope into the migration plan instead of writing it separately.
3. **llm-gateway cost-attribution headers** (`kantheon-v1.1.md` §5, PD-11) — same: migrating the gateway converts a cross-repo ask into a kantheon backlog item.
4. **aip-v1-impl distribution doc** (kantheon-architecture §12, still open) — the migration plan may finally subsume it; if so, close it explicitly.
5. **Capabilities heartbeat direction** — today ai-platform tools heartbeat *into* kantheon (the one reverse runtime dependency, warn-and-continue). Each migrated tool removes one cross-repo heartbeat; note the end-state (does any ai-platform→kantheon runtime write remain?).

## 5. Traps flagged by the cohesion review

- **The Maven inversion is the structural decision hiding inside the service list.** Moving any shared lib or `shared/proto` slice with remaining ai-platform consumers forces kantheon to become a Maven publisher. That's a new CI/publishing surface — decide it once, globally, not per service.
- **query-mcp is not "just another tool"**: it is the RLS/identity enforcement edge (kantheon-security §1, §3.4). If it moves, `kantheon-security.md` needs a §1 rewrite and the Validator/OPA coupling needs an owner. Recommend treating it as its own wave with its own security review.
- **Don't let migration re-open locked arcs.** Iris → Golem → Pythia order and their plans are post-review stable; migration waves should slot *around* them (the Charon/Metis pattern: independent arcs whose tags gate Pythia Phase 4 pre-flights — reuse that gating idiom).
- **Naming:** migrated services keep their service names; check the Greek-naming convention only applies to *agents* (Charon/Metis already follow mythology — happy accident; don't force it for e.g. a moved query-mcp).
- **Vocabulary + stale-name hygiene:** the review just purged `mcp-server-base`; don't reintroduce it. New docs follow lowercase-hyphenated filenames.

## 6. Read order for the migration session

1. This doc.
2. `CLAUDE.md` §1 (boundary shift) + §7 (cross-repo coupling) — the current contract.
3. `/Users/bora/Dev/ai-platform/CLAUDE.md` — service inventory at HEAD (the authority; §3 list above is secondhand).
4. `docs/architecture/charon/architecture.md` + `docs/architecture/metis/architecture.md` — the migration precedent, esp. how they handle deployment/secrets and the MCP-wrapper split.
5. `docs/architecture/kantheon-security.md` — before touching anything on the data path.
6. `docs/implementation/kantheon-v1.1.md` + `docs/implementation/v1/next-steps.md` — the parked items in §4.

---

*Handover by the 2026-06-12 cohesion-review session. Memory mirror: `kantheon_cohesion_review_2026_06_12.md` points here as the next large task; this doc is the authoritative handover.*
