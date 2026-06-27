# Iris Phase 2 Stage 2.1 — FE into kantheon + build wiring

> **Goal (plan §4 Stage 2.1).** The Iris Vue SPA lives in `frontends/iris`, builds + tests green from kantheon, and runs in CI. No behavioural change yet — just the source landing + build wiring. (Envelope-ts adoption + BFF re-point are Stage 2.2; session UX 2.3; deploy/cutover 2.4 → `iris/v0.1.0`, which **crosses M3**.)
>
> **Companions.** [`plan.md`](./plan.md) §4 · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) · [`EXAMPLES.md`](../../../../EXAMPLES.md) (FE build recipes) · the `no-ai-platform-olymp-clusters` memory.

## Pre-flight — the FE source (no-ai-platform reconciliation)

The Vue SPA does not yet exist in kantheon (`frontends/` is an empty stub); the working FE lives only in **ai-platform's `agents-fe`**. Under the **no-ai-platform** rule (2026-06-21) this stage needs an explicit call:

- [x] **T0 — DECISION (Bora, 2026-06-21): (A) one-time read-only extraction.** how does the FE arrive?
  - **(A) One-time read-only source extraction (recommended).** `git filter-repo` the FE out of a *scratch ai-platform clone* into `frontends/iris`, history preserved. This is a **copy, not an integration** — exactly the fork pattern ("copy-paste, not cut-paste") — and is the **single, final, read-only touch** of ai-platform; afterwards kantheon never reads/calls it again. Keeps the working, daily-driven FE intact.
  - **(B) Fresh FE build.** Start a new Vue 3 SPA in `frontends/iris`, porting only the pieces deliberately. Cleaner lineage, much larger effort, loses the proven UI.
  - *Recommendation: (A).* It satisfies no-ai-platform (the extraction is not a runtime tie) and preserves a known-good FE; (B) is only warranted if the agents-fe is judged not worth carrying. **Tasks below assume (A); if (B), this stage is rescoped to a scaffold.**

| Pre-flight item | Status |
|---|---|
| Source path confirmed (`frontends/agents-fe` in ai-platform; CLAUDE.md also references `golem/frontend/` heritage) | **open — confirm exact path at T1** |
| Scratch ai-platform clone available for the one-way filter-repo (read-only; not a submodule/remote) | needed for T1 |
| Node/pnpm toolchain + `just *-fe` recipe convention (mirror an existing FE if one lands first) | confirm in T3 |

## Tasks (assuming decision A)

- [x] **T1 — Dry-run extraction.** On a *throwaway* ai-platform clone, `git filter-repo --path <fe-path>` (confirm the exact path); inspect the rewritten history + tree. No write to kantheon yet. Read-only ai-platform touch.
- [x] **T2 — Import into `frontends/iris`.** Bring the filtered history into `kantheon/frontends/iris` (one-way merge with `--allow-unrelated-histories`, history preserved). After this, **the ai-platform clone is discarded** — no remote, no submodule, no further reads.
- [x] **T3 — Kantheon build wiring.** `just build-fe / test-fe / lint-fe iris` (mirror the repo's FE recipe convention); add a `frontends/iris` CI job to `.github/workflows/ci.yml`. Node/pnpm versions pinned to the catalog where applicable.
- [x] **T4 — Green unmodified.** Vitest + oxlint/eslint pass on the imported tree **without behavioural edits** (config-only fixes allowed: paths, tsconfig, lockfile). Establishes the green baseline before Stage 2.2 changes anything.
- [x] **T5 — Rename pass.** `agents-fe` → `iris` in `package.json` name, app title, i18n keys, build artifacts; **store names unchanged** (Stage 2.2 depends on them). No `cz.dfpartner`/ai-platform identifiers left in package metadata.
- [x] **T6 — README + provenance note.** `frontends/iris/README.md`: heritage (one-time agents-fe extraction, date, source commit), the no-ai-platform note, build/test/lint recipes, and the golden-sample fixture-refresh discipline pointer (plan §6).

## DONE — 2026-06-21 ✅
- `frontends/iris` builds + tests + lints green from kantheon; CI runs it; no runtime or build-time tie to ai-platform remains.
- Plan §9 `Stage 2.1` checkbox ticked.

## Notes / onward
- **No deploy in this stage** — the FE is wired to the BFF and deployed in Stages 2.2–2.4 (and onto **bp-dsk**, not ai-platform). The transitional `/v2` path it talks to must resolve to an in-cluster backend.
- Envelope-ts adoption (replace `src/types/envelope.ts` with the generated `envelope-ts` bindings) is **Stage 2.2**, not here — keep the import surface untouched at 2.1 so the green baseline is meaningful.
