# Implementation — v1

The v1 implementation arc. First production-shipping version of the Kantheon constellation.

## Start here

- **[`master-plan.md`](./master-plan.md)** — the cross-arc orchestration plan: the three parallel streams (A = Spine, B = Body, T = Testing), per-arc sequencing, the mergepoints between streams, and the live status board. **This is the resumption pointer** — read it first when returning to the project.

## Per arc

Each arc's `plan.md` is the source of truth for its phases, stages, and tasks. The master plan sequences them; it does not restate their detail.

| Arc | Stream | Plan |
|---|---|---|
| Fork (platform fork) | A (P1–P4) / B (P5) | [`fork/plan.md`](./fork/plan.md) |
| Themis (routing) | A | [`themis/plan.md`](./themis/plan.md) |
| Iris (FE + BFF) | A | [`iris/plan.md`](./iris/plan.md) |
| Golem (per-domain Q&A) | A | [`golem/plan.md`](./golem/plan.md) |
| Pythia (investigator) | A | [`pythia/plan.md`](./pythia/plan.md) |
| Charon (data mover) | B | [`charon/plan.md`](./charon/plan.md) |
| Metis (model estimation) | B | [`metis/plan.md`](./metis/plan.md) |
| Midas (brokerage domain) | B | [`midas/plan.md`](./midas/plan.md) |
| Sysifos (data-entry workbench) | B | [`sysifos/plan.md`](./sysifos/plan.md) |
| Arges (Postgres worker) | B | [`arges/plan.md`](./arges/plan.md) |
| Hebe (personal agent) | B | [`hebe/plan.md`](./hebe/plan.md) |
| Kleio (librarian / RAG warehouse) | B | [`kleio/plan.md`](./kleio/plan.md) |
| Testing (component + integration + deploy/smoke) | **T** | [`testing/plan.md`](./testing/plan.md) |

> **Stream B remaining-work order (2026-06-24):** Fork P5 → Hebe → Kleio. **Stream T** cuts the deploy-gated release tags for Iris (S3.3) and Sysifos (S3.4).

## Reading order

1. **[`master-plan.md`](./master-plan.md)** — streams, mergepoints, status board.
2. The arc `plan.md` you're executing (table above).
3. **[`../planning-conventions.md`](../planning-conventions.md)** — the task / stage / phase hierarchy all plans follow.

## Archive

Superseded cross-cutting docs (`next-steps.md`, `aip-v1-*`, the 2026-06-12 handover/review notes) live in **[`_archive/`](./_archive/)** — kept for provenance, not authoritative. See [`_archive/README.md`](./_archive/README.md).

## Up / across

- Up: [`../README.md`](../README.md) — implementation entry point.
- Across: [`../../design/`](../../design/) — *what* each agent is. [`../../architecture/`](../../architecture/) — *how* each agent is built.
