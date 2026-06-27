# Review 003 — Fork Phase 2, Stage 2.1 T1–T3 (Ariadne fork)

> Reviewer: Claude (per [`reviews.md`](./reviews.md)). Date: 2026-06-13. Branch: `feat/fork-p2-s2.1-ariadne` (commit `5b4b717`).
> Scope: the **lean-ariadne scope carve-out** + T1–T3 (fork module, package sweep, model fixtures). T3b–T7 not yet started (correctly).
> Fixes were applied inline this pass (Bora's instruction), except one open item that needs source data (F1b).

## Verdict

**The lean-ariadne scope decision is sound and the fork shape is correct.** Dropping the YAML→TTR import path (kantheon is TTR-only; `model-ttr/` is already the converted output) and dropping the declared-but-unused `erp-sql-metadata` dep are both right calls, consistent with the Stage 1.3 precedent and Bora's 2026-06-13 decision. The 138-import sweep, the `grpc`/`grpckt` proto builtins, the new library-only `org.tatrman.proteus.v1` enums, and the `ColliteModeler` repo wiring (`includeGroup("org.tatrman")`, correctly *not* capturing kantheon's own `org.tatrman.kantheon`) are all clean.

**But the agent's characterization of the 3 test failures was wrong, and that matters.** One was a feature being revisited (drill maps — handled); the other two are **not** "warning-as-error miscounting" — they expose a **genuinely incomplete seed model**. Details below.

Severity: 🔴 real correctness/data issue · 🟡 scope/contract decision to record · 🟢 minor.

---

## 🔴 F1 — The 3 test failures, correctly diagnosed

The agent reported all 3 as "T2 follow-ups (warning-as-error counting in the reconciler)." Investigation (probe over the live reconcile, since removed) shows two distinct, more serious realities:

### F1a — Drill-map test *(handled this pass, per Bora)*
`GetModelSpec` "…drill maps land in ModelBundle.drillMaps" — drill maps are being revisited from the product side. **Deleted** the loading test (breadcrumb left in place), kept the flag-gating test, and logged **kantheon-v1.1 §8** (Ariadne / model graph) for the redefinition. Proto + loader stay in tree.

### F1b — Incomplete seed model *(tests rewritten to a complete package; seed completion tracked)*

> **Update (2026-06-13):** per Bora, `ModelTtrLoadSpec` was rewritten to scope to the self-contained **`ucetnictvi`** package (0 errors, 228 objects) via a `ScopedStorage` + `SEED_PACKAGES` allowlist — root stays `model-ttr` so package derivation is correct. **Both tests pass; the ariadne suite is green.** The underlying seed gap is *not* silenced or closed: it's tracked in **kantheon-v1.1 §8** ("`model-ttr` seed completeness") — complete the missing `def entity` files from `ai-models`, then widen `SEED_PACKAGES`. The diagnostic was **not** downgraded. Original analysis below.

The two `ModelTtrLoadSpec` failures assert `result.errors shouldHaveSize 0`. The reality:

- **101 errors, all `ttr/unimported-reference`**, over **15 distinct entity names**: `artikl`, `produkt`, `podprodukt`, `subjekt`, `dodací_místo`, `obchodní_kanál`, `cenová_skupina_artiklu`, `zásoba_artiklu`, `cenová_politika`, `tržní_skupina`, `obchodní_vztah`, `osoba_dodacího_místa`, `platnost_dm_v_tdm`, `typ_dodacího_místa`, `prodejní_akce`.
- These are referenced by relations (`model-ttr/artikl/er.ttr`: `from: er.entity.artikl, to: er.entity.produkt`, …) but **have no `def entity` anywhere in the fixture tree** (`def entity` count across all of `model-ttr/` = **51**, none of these 15 — verified). The `artikl` package dir contains only `db.ttr` + `er.ttr`; no entity file.
- The model **does** load (1908 objects, 620 ER entities), so it is not a parser/version failure. These are **genuine dangling relation endpoints** in the committed seed — the converted `model-ttr/` snapshot is internally inconsistent (relations present, their entity definitions missing). modeler-0.4.0's resolver correctly flags them.

**Why the agent's planned fix is wrong:** "relax the warning-as-error counting" would turn 101 real broken-relation signals into silent warnings — the seed would "load green" with 15 core business entities referenced by relations that point at nothing. That masks a data defect rather than fixing it.

**Correct fix (open — T2):** complete the `model-ttr` seed. The entity-definition files for these packages are missing from the converted snapshot; re-convert / restore from the `ai-models` source so every referenced `er.entity.X` has a `def entity X`. *Or*, if the committed fixture is deliberately a partial subset, narrow the `ModelTtrLoadSpec` expectation to the seeded packages and document the subset — but the dangling-ref policy must stay an error for in-scope packages. I could not do this myself: it needs the source entity definitions, which aren't in the repo. The corrected diagnosis is now recorded in the S2.1 task doc T1 note for whoever picks up T2.

> Note: `ttr/unimported-reference` currently conflates "found-but-not-import-reachable" (line 110, bare names) with genuine `NotFound` (line 118) in `PublishedResolverAdapter` — same code, same message. If T2 ever does want to distinguish import-discipline lint from dangling refs, split those into two codes first; otherwise any blanket downgrade hits both.

---

## 🟡 F2 — Rule-6 `source_file` field *(fixed: reframed + documented)*

`string source_file = 4` was added to the **domain-free** `common/v1.ResponseMessage` with an ariadne/YAML-specific comment — the kind of domain bleed into the universal contract that CLAUDE.md §4 explicitly guards against. Kept the field (a typed attribution channel fits Bora's typed-contracts preference better than stuffing paths into `human_message`), but **reframed it as a deliberate, platform-generic source-attribution field** (neutral comment; any file-loading service may set it) and **recorded it in fork `contracts.md` §4** so it's an intentional platform decision rather than a silent per-service bolt-on.

---

## 🟡 F3 — Maven re-expansion (Collite/modeler) *(confirmed real per Bora; docs updated)*

Stage 2.1 adds a **permanent** GitHub Packages dependency — `org.tatrman:ttr-{parser,writer,semantics}` from `Collite/modeler` — requiring a `gpr.*` PAT that does **not** dissolve at the fork end-state. This contradicted CLAUDE.md §1/§7's "zero coupling / no Maven consumption / clean-clone needs no PAT" framing. Per Bora: the modeler dep is real and stays (not vendored). **Docs reconciled:**
- **CLAUDE.md §1** — "zero cross-repo coupling" scoped to *ai-platform*; modeler called out as the standing external dep.
- **CLAUDE.md §7.3** (new) — records the modeler TTR toolchain as a permanent third-party dependency; the Phase-1 "no PAT" goal is scoped to ai-platform only; the modeler PAT stands forever; not vendored (Bora, 2026-06-13).
- **AGENTS.md §2 bootstrap** — PAT now documented for two groups: permanent `org.tatrman` (modeler) + temporary `cz.dfpartner` (Themis nlp.v1, until 2.6).

---

## 🟢 F4 — Tracking accuracy *(fixed)*

"T1–T3 done" was loose: **T2 is correctly unticked** (its DoD "suite fully green" isn't met) and the suite is **224 tests** (after F1a removal), not the "60+" reported. The T1 done-note also mislabeled the failure cause. Corrected the count and the cause in the S2.1 task doc. The package sweep itself (proto imports + module packages under `org/tatrman/kantheon/ariadne/`) compiles; whether the Kotlin package root should be `org.tatrman.ariadne` vs the current `org.tatrman.kantheon.ariadne` is a T2 question (the task says `org/tatrman/<service>/` per the Charon idiom) — flagging for the dev to confirm against Charon/Metis before T2 closes.

---

## What's correct (no action)

- Lean-ariadne carve-out (YAML path dropped); `erp-sql-metadata` drop; `model-ttr/` + fixtures carried in T1.
- `ColliteModeler` repo `includeGroup("org.tatrman")` scoping (won't shadow kantheon's `org.tatrman.kantheon`).
- `proteus/v1/translator.proto` minimal library-only enum slice, clearly scoped ahead of Stage 2.4.
- grpc/grpckt builtins on `:shared:proto` (additive; other consumers unaffected — full repo still builds).
- 12 catalog additions are `version.ref`-disciplined.

## Open items for T2 (the dev continues here)

1. **F1b — complete the `model-ttr` seed** (restore the 15 missing `def entity` definitions / re-convert from `ai-models`), then widen `ModelTtrLoadSpec.SEED_PACKAGES` toward the whole tree. *(No longer a suite blocker — the test is scoped to `ucetnictvi` and green — but the seed is still incomplete; tracked in kantheon-v1.1 §8. Do not downgrade the diagnostic to silence it.)*
2. Finish the T2 package sweep + confirm the Kotlin package-root convention (`org.tatrman.kantheon.ariadne` vs `org.tatrman.ariadne`).
3. Then T3b (prompt-serving), T4–T7 as planned.

*Fixed inline this pass: F1a (drill-map test + v1.1 §8), F2 (source_file reframe + contracts §4), F3 (CLAUDE.md §1/§7.3 + AGENTS.md §2), F4 (task-doc corrections). Open: F1b (needs source data).*
