# Stage 4.2 — Routine type + delivery loop

> **Phase 4, Stage 4.2.** The product loop, scheduled and delivered.
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §"Stage 4.2", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §7 (the flow + the minimal Telegram rendering rule) + §7.1 (never-silent failure), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §1.2 (`KantheonQuestionBody`, `RoutineRun`, `DeliveryRecord`) + §4.1 (`routines.body_kind = kantheon_question` + columns), [`/docs/design/product-design-issues.md`](../../../design/product-design-issues.md) (PD-2, PD-10).

## Goal

The `kantheon_question` routine type ships end-to-end: scheduled fire → iris-bff turn (Stage 4.1) → envelope rendered to a channel message (conclusion text + artifact counts + Iris deep link) → `DeliveryRecord` + receipts → never-silent failure handling. DONE = a cron-fired **Golem** question delivered to Telegram on local K3s; tag **`hebe/v0.4.0`**; PD-10 layer 3 + PD-2 updated with Resolution pointers.

## Pre-flight

- [ ] **Stage 4.1 DONE** — `IrisBffClient` + `hebe/v1` proto + session management + status mapping exist.
- [ ] **Branch**: `feat/hebe-p4-s4.2-routine-delivery`.
- [ ] A Golem-answerable question is available end-to-end (O-3: Golem + iris-bff live). Pythia is **not** required — it lights up with no Hebe change when it ships.

## Tasks

- [ ] **T1 — Tests first: `kantheon_question` routine lifecycle.**

  Create `KantheonRoutineLifecycleSpec`: fire → run (via the Stage 4.1 client, Wiremock'd iris-bff) → deliver → **retry-on-failure per routine policy** → **never-silent failure** (a stream error/timeout produces a failure notification, never a dropped run). Include the `AWAITING_AGENT` path (Stage 4.1) producing a deep-link message rather than an answer.

  Acceptance: specs written and failing. Commit `[hebe-p4-s4.2] failing routine lifecycle specs`.

- [ ] **T2 — `routines.body_kind = kantheon_question` + console CRUD.**

  Add the `kantheon_question` body kind (contracts §4.1: `body_json` holds `KantheonQuestionBody` fields; new columns `session_ref`, `last_turn_ref` — already in the Phase 3 migration). Implement web-console CRUD for it (`:agents:hebe:modules:channels` console): create/edit/enable/disable a "ask the constellation X on cron Y, deliver to channels Z" routine. Validate `delivery_channels` ⊆ configured channels.

  Acceptance: console can create/list/edit a `kantheon_question` routine; spec green.

- [ ] **T3 — Envelope → channel rendering (minimal, v1).**

  Render the terminal `envelope/v1` to a channel message (architecture §7, "intentionally minimal in v1"): conclusion **text blocks** + **counts** of tables/charts + a **deep link** into the Iris session. **No chart rendering in Telegram.** Use the `envelope-ts`/`envelope-render` helpers' semantics for block typing (server-side; Hebe reads the envelope blocks). Telegram via `telegrambots` (`libs.versions.telegram`).

  Acceptance: a fixture envelope renders to the expected Telegram message (text + counts + link); spec green.

- [ ] **T4 — Delivery records + failure notifications + receipts.**

  Persist a `DeliveryRecord` (channel, `delivered_at`, `ok`) per delivery; on failure, send a failure notification per the routine policy (retry then notify — **never silent**, architecture §7.1). Write **receipts** for the full run (fire → turn → delivery), reusing the receipts chain (file or PG per `receipts.backend`). The `jobs` row of `kind=routine` carries `turn_ref` (contracts §4.1) cross-linking receipts ↔ the Iris session.

  Acceptance: success writes `DeliveryRecord(ok=true)` + receipts; induced failure notifies + receipts the failure; spec green.

- [ ] **T5 — `AWAITING_AGENT` channel message with Iris deep link.**

  When the run is `AWAITING_AGENT` (Stage 4.1 mapping), deliver a channel message with a deep link into the Iris session so the **human resumes in Iris** (v1 rule — Hebe does not answer clarifications, contracts §3.4). 

  Acceptance: an `AWAITING_*` stream produces a deep-link message, not an attempted answer; spec green.

- [ ] **T6 — Demo on K3s + close PD-10/PD-2; tag.**

  Deploy and demo on local K3s: a cron-fired **Golem** question delivered to Telegram with the Iris deep link. Capture a runbook + screencap in `agents/hebe/docs/`. Per planning-conventions §4 this is a **deployment demo** of the scheduled-delivery capability — automated E2E verification of the chain is the integration suite. Then:

  - Update **PD-10** (scheduled work) layer 3 + **PD-2** (out-of-band notification path) in `docs/design/product-design-issues.md` with **Resolution** entries pointing to this stage.
  - Tag **`hebe/v0.4.0`**; bump `gradle/libs.versions.toml`.

  Acceptance: demo works + documented; PD-10/PD-2 Resolutions written; tag pushed. PR `[hebe-p4-s4.2] routine type + delivery loop`.

## DONE — Stage 4.2

- [ ] All six tasks checked.
- [ ] `kantheon_question` routine: fire → iris-bff turn → Telegram delivery (text + artifact counts + Iris deep link) works on local K3s.
- [ ] Retry + never-silent failure notifications; `DeliveryRecord` + receipts for every run.
- [ ] `AWAITING_AGENT` produces a deep-link message (human resumes in Iris).
- [ ] PD-10 layer 3 + PD-2 updated with Resolution pointers.
- [ ] Tag `hebe/v0.4.0` pushed. **Phase 4 DONE — Hebe v1 arc complete.**
- [ ] PR merged.

## Library / pattern references

- **architecture.md §7** — the scheduled-investigation flow + the minimal-Telegram-rendering rule (no charts; text + counts + link).
- **contracts.md §1.2** — `KantheonQuestionBody`/`RoutineRun`/`DeliveryRecord`. **§4.1** — `body_kind = kantheon_question` + `session_ref`/`last_turn_ref`/`turn_ref`.
- **EXAMPLES.md §6** — envelope `Block` types (what Hebe reads to render). **telegrambots** (`libs.versions.telegram = 9.6.0`).
- **PD-2 / PD-10** in `docs/design/product-design-issues.md` — the product motivation this stage resolves.

## Out of scope for Stage 4.2

- Pythia investigations (no Hebe change needed; they light up when Pythia ships — O-3).
- Chart rendering in Telegram (v1 rule: counts + deep link only).
- Shared-bot multi-instance routing (O-1, v1.x); provisioning automation (O-2, v1.x).
- Automated E2E gating (integration suite).
