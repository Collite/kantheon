# Midas — Phase 3 task lists (overview / management doc)

> **What this is.** The task-management index for **Midas Phase 3 — Q&A + reports + dashboards** (the only remaining Midas build; closes v1 domain development before the Stream-T testing/deploy pass). It structures the six stage task lists, their dependencies, and the cross-arc + fork reconciliations that the 2026-06-12 plan text predates. Each stage has its own `tasks-p3-s3.<n>-*.md` with 5–8 checkbox tasks, TDD-shaped.
>
> **Reads with.** [`plan.md`](./plan.md) §5 (Phase 3), [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) (§6 midas-core, §10 report-renderer, §11 dashboards, §12 PG worker), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) (§3 MCP tools, §7 dashboards, §8 report API, §9 manifests), [`../../planning-conventions.md`](../../planning-conventions.md), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md).
>
> *Created 2026-06-27. Owner: Bora. Phase 3 = Stages 3.1–3.6 → tag `midas-arc/phase-3-qa-reports-v1`.*

---

## 0. ⚠ Plan reconciliation (2026-06-27) — read before executing any stage

The Phase 3 plan + `contracts.md` §9.1 were authored mid-2026-06 and **predate the fork and the assembled-Shem convergence**. The task lists below are written against current repo reality; these are the substitutions they apply (and that `plan.md` §5 + `contracts.md` §9.1 should be swept to match — tracked as a follow-up, not a blocker):

| Plan/contracts text (stale) | Current reality (use this) | Affects |
|---|---|---|
| `query-mcp` / `query.run:v1` / `query.compile:v1` | **`theseus-mcp`** — query path edge; tool `query` (+ `compile`/`compiledSql`/`queryRef`) (`tools/theseus-mcp`) | S3.1 capability_refs, S3.2 |
| "register curated queries in **metadata-mcp** / ai-platform metadata catalog" | **Ariadne** serves the model + queries (`ListQueries`/`GetModel`); curated queries are authored in **`ai-models`** and loaded by Ariadne. No ai-platform metadata-mcp. | S3.1 T3 |
| `workers/postgres` (ai-platform, cross-repo) | **Arges** (`workers/arges`, `arges/v0.1.0`) — kantheon PG worker, dispatched via **Kyklop**, translated via **Proteus** | S3.2 pre-flight |
| `agent_kind: DOMAIN_QA`, `domain_name`/`domain_entities`/`domain_terminology` | **`AREA_QA`** (capabilities.proto:88), **`area_name`** (field 20) / `area_entities` / `area_terminology` (renamed 2026-06-25, vocab canon) | S3.1 manifest |
| "Golem template image launches with the Shem **mounted** (ConfigMap YAML)" | The Shem is **assembled** (ai-models area-def + Ariadne model + thin overlay + template constants), prompts live with the Shem — mirror **`golem-ucetnictvi`** (`agents/golem/shems/golem-ucetnictvi`, `ShemAssembler.kt`, `GolemUcetnictviBundleSpec.kt`) | S3.1 (whole stage) |
| Iris-BFF `dashboard`/`dashboard_pane` DDL (contracts §7) + `agent_call_spec` panes | **Superseded by PD-6** — the dashboard *system* is generic in the **Iris arc** (`iris_artifacts`; pane source = `common.v1.ViewProvenance`; refresh = typed-action replay). Iris P4.2 is **code-complete**. S3.5 is a **consumer**: Midas templates + content only. **Do not implement the §7 DDL.** | S3.5 |

Two follow-on items that touch the **live** path (not stage gates, but needed for the closing demo): the **Proteus PostgreSQL unparse** gap (owned by Arges S1.2) and the Midas **`midas_app_readonly`** role migration (arges contracts §6).

---

## 1. Stage map & ordering

| Stage | Task list | Tasks | Hard gate | Can start when |
|---|---|---|---|---|
| **3.1 — Golem-Investment Shem + curated queries** | [`tasks-p3-s3.1-shem-investment.md`](./tasks-p3-s3.1-shem-investment.md) | 6 | Themis routing (`themis/v0.2.0` ✓) + Ariadne live + `golem-ucetnictvi` assembly precedent (✓) | **now** (no Arges, no live Iris) |
| **3.2 — Q&A green path** | [`tasks-p3-s3.2-qa-green-path.md`](./tasks-p3-s3.2-qa-green-path.md) | 7 | **Arges** (`arges/v0.1.0`) + theseus-mcp query path + S3.1 | after S3.1 + Arges tag |
| **3.3 — Complex calc tools** | [`tasks-p3-s3.3-calc-tools.md`](./tasks-p3-s3.3-calc-tools.md) | 6 | S3.2 (validates real flows first) | after S3.2 (calc unit work parallelisable earlier) |
| **3.4 — Report-renderer + v1 templates** | [`tasks-p3-s3.4-report-renderer.md`](./tasks-p3-s3.4-report-renderer.md) | 7 | S3.3 (PPTX charts use real perf numbers) | parallelisable with 3.3 (mock calc until 3.3) |
| **3.5 — Dashboards (Midas content on Iris artifacts)** | [`tasks-p3-s3.5-dashboards.md`](./tasks-p3-s3.5-dashboards.md) | 6 | Iris P4.2 artifact system (code-complete ✓) + S3.2 + S3.4 | after S3.2 + S3.4 |
| **3.6 — Google Finance poller + FX** | [`tasks-p3-s3.6-google-finance.md`](./tasks-p3-s3.6-google-finance.md) | 6 | none beyond Midas-core (S1.x ✓) | parallelisable any time after S1.x |

**Sequencing (conservative default = strict sequence; parallelise if split across developers):**

```
S3.1 ──► S3.2 ──► S3.3 ──┐
                  S3.4 ──┼──► S3.5 ──► closing demo + tag
         S3.6 (parallel) ┘
```

If **Arges slips**, run **S3.1 → S3.3 → S3.4 → S3.6** (none need the live PG worker) and defer **S3.2** + the S3.5 chart pane (which calls Golem-Investment) until `arges/v0.1.0` + `midas_app_readonly` land.

---

## 2. Testing policy (applies to every stage)

Per [`planning-conventions.md`](../../planning-conventions.md) §4 and the Phase-1 precedent: **stage DONE = mocked unit tests green** (Kotest + MockK + Wiremock + in-memory fakes). Each stage also ships its real-dependency checks as `src/componentTest` specs (Testcontainers via `shared/libs/kotlin/component-testkit`) where the plan calls them out — those run in CI on every PR but are *additional deliverables*, not the gate. **End-to-end-through-Iris and live-cluster smoke are Stream T**, not these stages — "deploy + smoke" tasks here are deployment confirmations / demo steps, not an automated e2e gate.

TDD shape is mandatory: every implementation task is preceded by (or contains) a "tests first" sub-step that defines the Kotest spec before the code.

---

## 3. Definition of DONE for Phase 3

- [ ] All six stage DONE blocks checked.
- [ ] Final demo (plan §"Phase 3 closing"): log in to Iris → ask "summarise Smith's portfolio for Q1" → chart + table + narrative → pin chart to a "Smith book" dashboard → export a PDF performance report. Daily pollers refresh FX/prices.
- [ ] `capabilities-mcp` lists `golem-investment` (AREA_QA) + the five `midas.*:v1` tools.
- [ ] Tag **`midas-arc/phase-3-qa-reports-v1`** → **Midas arc complete** → hand off to Stream T for the multi-service `golem-investment`/Midas integration context.

---

## 4. Cross-arc / Bora-owned content fills (surface early)

- **Golem-Investment area + curated queries** in `ai-models` (S3.1 T1/T3) — Bora-owned content (the `investment` area-def + the five `q.midas.*` query templates + terminology). Mirror the `ucetnictvi` area authoring.
- **Reference-portfolio fixtures** with hand-computed TWR/MWR/FIFO/fee results (S3.2 T5, S3.3) — the calc correctness oracle.
- **Three report templates** authored in Excel/PPT (S3.4 T7) — `portfolio-statement.v1.xlsx`, `performance-report.v1.{xlsx,pptx}`, `transaction-ledger.v1.xlsx`.
- **Confirm before S3.2:** `arges/v0.1.0` cut + Proteus PG-unparse green for the five `q.midas.*` queries + `midas_app_readonly` role migration applied to the `midas` database.
- **Confirm before S3.5:** Iris generic artifact endpoints (pins/dashboards/`iris_artifacts`) reachable on the live Iris (post-M3).

---

## 5. Follow-up (doc hygiene, not a build task)

Sweep `plan.md` §5 + `contracts.md` §9.1 to the §0 reconciliations (theseus/Ariadne/Arges/AREA_QA/assembled-Shem/PD-6 dashboards). The task lists are already written to current reality, so this is documentation alignment only.
