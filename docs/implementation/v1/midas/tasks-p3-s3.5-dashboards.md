# Stage 3.5 — Midas content on the Iris dashboard system

> **Phase 3, Stage 3.5.** **Consumer stage.** The dashboard *system* (pins, dashboards, `iris_artifacts`, refresh) is generic and lives in the **Iris arc** (PD-6; Iris P4.2 is code-complete). This stage supplies **Midas content**: the `investment-overview:v1` template, pinnable Golem-Investment blocks, and the report-preview pane — plus the end-to-end proof.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md) §0 (**PD-6: do NOT implement contracts §7 DDL / `agent_call_spec`**), [`plan.md`](./plan.md) §Stage 3.5, [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §11 (requirements input), [`../iris/`](../iris/) arc (the generic system — `iris_artifacts`, pane source = `common.v1.ViewProvenance`).
> **Note.** Pane source is **`common.v1.ViewProvenance`** (deterministic typed-action/replay refresh), NOT this arc's superseded `agent_call_spec`. The template/param model (`ParamDef`/`ParamKind`, panes) carries over from contracts §7's YAML.

## Goal

Midas content is live on the generic Iris artifact system: Golem-Investment chart/table blocks are pinnable; the `investment-overview:v1` dashboard template ships; the report-preview pane renders on open; an end-to-end smoke passes.

## Pre-flight

- [ ] **Iris generic artifact system live** — Iris P4.2 (`iris_artifacts`, pins/dashboards, render SSE) code-complete (✓); reachable on the target Iris (live path needs M3).
- [ ] S3.2 DONE (Golem-Investment answers — chart/table panes call it) + S3.4 DONE (report-renderer — the REPORT_PREVIEW pane calls it).
- [ ] Confirm the generic template-loader seam in the Iris arc accepts a Midas-contributed `dashboard-templates/*.yaml` (overview §0/§4).
- [ ] Branch `feat/p3-s3.5-midas-dashboards` from `main`.

## Tasks

- [ ] **T1 — Template schema fit + loader contribution (tests first).** Confirm/contribute the `dashboard-templates/*.yaml` ParamDefs/pane model to the **generic** Iris loader (architecture §11.1 model; `template_id`/`params_json`). Tests-first: a loader spec that parses the YAML into the generic template type. Lands Iris-side; Midas supplies the content. **Do not** create the contracts §7 `dashboard`/`dashboard_pane` tables.
- [ ] **T2 — `investment-overview:v1` template.** Author `dashboard-templates/investment-overview.v1.yaml` (contracts §7 YAML body, carried to the generic system): params `client_id` (CLIENT_ID), `portfolio_id` (PORTFOLIO_ID), `period` (PERIOD, default `ytd`); panes = a CHART pane (Golem-Investment "YTD performance for portfolio {portfolio_id}"), a TABLE pane (`midas.position.valuation:v1` for `{portfolio_id}`/`{period.end}`), and a REPORT_PREVIEW pane (report-renderer `portfolio-statement:v1` → `OUTPUT_HTML`). Express pane sources as `ViewProvenance`, not `agent_call_spec`.
- [ ] **T3 — Report-preview pane kind (the one Midas-specific mechanic).** Render-on-open via a `/reports/render` proxy to report-renderer (`OUTPUT_HTML`), surfaced as a pane in the generic system. Tests-first: pane render returns the HTML artifact preview; error state on render failure.
- [ ] **T4 — Pin + refresh verification against Golem-Investment (tests first).** Prove the generic typed-action/replay refresh path works for an investment chart pane: pin a Golem-Investment chart block → reopen → refresh re-executes via `ViewProvenance`; assert per-pane error state on a model/param change. Component-level (Golem-Investment + report-renderer Wiremock-stubbed).
- [ ] **T5 — `investment-overview` param-fill + create-from-template.** Verify creating a dashboard from `investment-overview:v1` with `{client, portfolio, period}` resolves all three panes' params correctly (the `{portfolio_id}`/`{period.end}` interpolation). Tests-first on the param resolution.
- [ ] **T6 — End-to-end smoke (demo, not gate).** Ask "YTD performance for Smith portfolio" → pin the chart to a new "Smith book" dashboard → reopen → refresh; create an `investment-overview` from template. Per planning-conventions §4 this is the Phase-3 demo step on a live Iris (Stream T), not an automated gate.

## DONE (plan §Stage 3.5)

- [ ] Midas content pinnable on the generic system; `investment-overview:v1` template ships and resolves params (T2/T5).
- [ ] Report-preview pane renders on open (T3).
- [ ] Pin → reopen → refresh works via `ViewProvenance` replay; per-pane error states present (T4).
- [ ] (Demo) end-to-end pin + create-from-template confirmed on a live Iris.

## Follow-ups (not in this stage)

- Dashboard sharing / per-portfolio ACLs / versioning — out of v1 (architecture §11.4).
- Any generic dashboard-system gaps belong to the Iris arc, not here.
