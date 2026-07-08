# WS-C Stage 2 — Integration contexts (incl. `tpcds-query`) + run-set

> **Workstream C (Test suite), Stage 2.** Build the integration-tier contexts: the new **`tpcds-query`** showcase (Goals 2+4), finish the existing draft contexts, and assemble the run-set that runs green via `infra-up --kube dsk` (**MP-4**).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §6 (integration contract) + §7 (bp-dsk), [`tasks-r1-bp-dsk-run-mode.md`](./tasks-r1-bp-dsk-run-mode.md), [`../testing/plan.md`](../testing/plan.md) §3.1 (existing contexts).
> **Reference.** olymp `test-contexts/theseus-runquery/` (the live, complete example) + `golem-erp/` (draft). kantheon `RunQueryIntegrationSpec` + `@RequiresContext` + `ContextNameRegistrySpec`. Repos: **[K]** kantheon specs+fixtures · **[O]** olymp `test-contexts/`.

## Goal

The run-set — `theseus-runquery` (✓), **`tpcds-query`** (new), `golem-erp`, `themis-routing`, `pythia-rca` — runs green on bp-dsk via `infra-up --kube dsk`; nightly (bp-olymp01) stays green too.

## Pre-flight

- [ ] WS-R1 DONE (`--kube dsk` + reconcile-boundary verified).
- [ ] WS-T2 DONE (TPC-DS model + `pg-tpcds` + Proteus unparse) for the `tpcds-query` context.
- [ ] Query-path services have charts (D2) + are deployable; `test-pg` reachable (T1).
- [ ] Branch `feat/c2-integration-contexts` in **both** repos.

## Tasks

- [x] **T1 [K] — `TpcdsQueryIntegrationSpec` (tests first).** ✅ Done. `@RequiresContext("tpcds-query")` drives the 4 curated shapes through theseus-mcp `query`, asserting the SF1 oracle (12/30/30/3), `{year}`→2002, no-RLS analyst bearer; `assertOk()` surfaces the error envelope on failure. `ContextNameRegistrySpec` auto-scans the name (no manual list). Also fixed `McpQueryDriver` to disable the CIO 15s engine timeout + govern the call with a 3-min MCP `RequestOptions` timeout (heavy SF1 first query).
- [x] **T2 [O] — `tpcds-query` context.** ✅ Done (olymp `feat/c2-integration-contexts`). `test-contexts/tpcds-query/` — 7 services helm-installed, pointed at the **STANDING** test-pg (no per-run warehouse, no mssql/wiremock); Arges restates Proteus + pg-tpcds host/user/password; 6 services inherit chart-default wiring (MP-2's config). The run-ns cred is solved by extending the `pg-tpcds-ro` ClusterExternalSecret namespaceSelectors to match `olymp.collite/managed-by=test-harness`.
- [x] **T3 [K/O] — Run `tpcds-query` on bp-dsk.** ✅ **GREEN on bp-dsk 2026-07-07** — `just it-bp-dsk tpcds-query`: 4 tests, 0 skipped, 0 failures; the 4 curated shapes return the exact SF1 oracle (12/30/30/3) through the full Postgres-worker chain. The Goals-2+4 convergence demo. *(Cold-start note: the first query takes ~20s (Calcite cold-compile + cold Arges pool); one earlier run hit a first-query transient — add a warmup/retry if it recurs. k3d-local leg not run — the bp-dsk green is the deliverable.)*
- [x] **T4 [K/O] — `golem-erp` (fixture agent-showcase).** ✅ **FULLY GREEN on bp-dsk 2026-07-08** — all 3 assertions pass: 401 (missing bearer) + 403 (outsider) PD-8 admission **and** the answer-turn (`STATUS_DONE` render envelope) through the LIVE LLM roundtrip golem→Prometheus→WireMock→render. **The prometheus→WireMock Anthropic roundtrip is now confirmed — unblocks every themis/pythia LLM tier.** Live-run fixes (all on `feat/c2-themis-routing`): Prometheus `/v1/chat` controller alias + `haiku`/`sonnet`/`claude-haiku` model aliases (findByName was exact → fell to the Azure default); `startupProbe`→readiness group + Redis health off (503 CrashLoop); golem `wait-for-ariadne` initContainer (boot race → permanent not-ready); WireMock stub loaded at runtime via `WireMockAdmin` (the in-cluster WireMock starts empty); answer-turn client timeout 3m; `it-bp-dsk` dumps service logs on failure; prometheus jib `-PimageRepo`/`-PimageTag`. Reframed to the **agent showcase** (Bora, 2026-07-08): the fixture query leg returns `detection_failed` not rows (`theseus-runquery` finding), so golem-erp proves the **Golem agent turn**, not real query data (that is `tpcds-query`'s job). **No image rebuilds** — the golem-erp Shem points at the existing bundled Ariadne `accounting` area, and is delivered as a per-run **ConfigMap** (new olymp harness `shems:` field → `<agent_id>-shem`, mounted via golem `shem.configMapName`; prompts fall back to the golem image classpath `prompts/cs/*`).
  - **Landed (kantheon):** `agents/golem/shems/golem-erp/` (Shem + prompts + README); `GolemErpBundleSpec` (green — Shem parses/assembles); `GolemErpIntegrationSpec` restructured to **two gates** — `contextLive=true` (401 missing-bearer + 403 outsider, PD-8 admission — robust) and `answerTurnLive=false` (the render-only LLM-planned `STATUS_DONE` turn); WireMock Anthropic `/v1/messages` → render-only MiniPlan stub.
  - **Landed (olymp):** `just-helper.py` Shem-ConfigMap support (`shems:` + `_apply_shem_configmap`, dry-run verified); `test-contexts/golem-erp/` trimmed to golem+ariadne+prometheus + `platform:[wiremock]` + `shems:[golem-erp]`; prometheus on the Spring `test` profile (H2 — no PG member) with the LLM base-url'd at WireMock. Helm-template + infra-up `--dry-run` both clean.
  - **Admission tier GREEN on bp-dsk 2026-07-08** — `just it-bp-dsk golem-erp` passes the two PD-8 admission assertions (401 missing-bearer + 403 outsider) through a real Golem-ERP pod (Shem via ConfigMap, prometheus on the H2 `test` profile). Fixes the live run forced, all landed: **golem `:testing` image published** (it never existed — only `0.1.0`); **prometheus `startupProbe`** added to the shared chart (the ~97s boot was killed by the 90s liveness budget → CrashLoop); node `fs.inotify` limits raised (fd starvation stalled image pulls). Standing golem flipped `0.1.0`→`:testing`. Dynamic per-Shem golem ApplicationSet (`bp-dsk-golems`) landed for prod multi-instance.
  - **Remaining (gated flip):** `answerTurnLive=true` + re-run to exercise the golem→prometheus→WireMock LLM roundtrip (Spring AI 2.0.0-M2 Anthropic wire shape / gateway path `/v1/chat/completions` vs `/api/v1/chat/completions` may need one live tweak). **No image builds needed** — the render-only MiniPlan stub is authored.
- [ ] **T5 [K/O] — `themis-routing` + `pythia-rca`.** Bring `themis-routing` green (real Kadmos/Echo); stand `pythia-rca` (minimal investigation DAG vs real Charon+Metis) — gated on those services being deployable (D2/D3). Specs first, then contexts, then local→bp-dsk.
- [ ] **T6 [K] — Re-enable `theseus-runquery` result/RLS asserts.** Flip `RunQueryIntegrationSpec.modelAlignedContext = true` once a seed-aligned model exists (testing S3.1 T7); run on bp-dsk.
- [ ] **T7 [K/O] — Assemble the run-set + bp-dsk full-run.** Define the bp-dsk on-demand run-set (all five contexts) + keep `nightly.txt` (bp-olymp01) authoritative for the scheduled nightly. Prove the **full set green on bp-dsk** via the `just it-bp-dsk` loop (sequential or namespace-isolated parallel). Document the runbook.

## DONE

- [x] `tpcds-query` green on bp-dsk (the four queries return correct SF1 results — 12/30/30/3, 2026-07-07). *(local k3d leg optional; not run.)*
- [ ] `golem-erp` / `themis-routing` / `pythia-rca` green; `theseus-runquery` result/RLS asserts re-enabled.
- [ ] Full run-set green on bp-dsk via `infra-up --kube dsk`; nightly on bp-olymp01 unaffected. **→ MP-4.**

## Follow-ups → next stage

- After MP-4: cut the deferred release tags (contracts §9) — the program finish line.
- Graduate the `iris-session` / `sysifos-workbench` deploy-smokes (testing S3.3/S3.4) to `@RequiresContext` nightly specs (optional, post-program).
