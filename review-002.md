# Review 002 — Fork Phase 1, Stage 1.3 (Shared libs & the Maven cut)

> Reviewer: Claude (per [`reviews.md`](./reviews.md)). Date: 2026-06-13. Branch: `feat/fork-p1-s1.3-shared-libs` (commit `91d42b7`).
> Scope: Stage 1.3 + the "Phase 1 complete" claim. Inputs: [`plan.md`](./docs/implementation/v1/fork/plan.md) Phase 1, [`tasks-p1-s1.3-shared-libs.md`](./docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md), [`tasks.md`](./docs/implementation/v1/fork/tasks.md), the 7 forked Kotlin libs + 1 Python lib, `gradle/libs.versions.toml`, `settings.gradle.kts`, the new `proteus/v1/translator.proto`, `SchemaCodes.kt`, consumer build files, `AGENTS.md` §12.
>
> **Unlike review-001, the remaining issues were fixed in this pass** (per Bora's instruction), not handed off as a task list. This file is the record of what was found and done.

## Verdict

**Stage 1.3's actual deliverables are solid.** The 7 Kotlin + 1 Python libs are forked faithfully with provenance headers; the version catalog additions are `version.ref`-disciplined with zero hardcoded versions in any forked `build.gradle.kts`; the 138 proto-import rewrites + the new library-only `org.tatrman.proteus.v1` translator enums + the recovered `SchemaCodes.kt` are correct; consumers are re-pointed to `project(...)` deps; the Maven cut leaves exactly one documented residual (`cz.dfpartner:shared-proto` for Themis's `nlp.v1`). The three pre-flight discoveries (ttr-parser/writer not in-repo at the fork point → 7+1 not 9+1; the non-existent `opentelemetry-kotlin` artifacts dropped; `SchemaCodes.kt` relocated) are all correct calls, well recorded.

**But "Phase 1 is now complete" was overstated, and the lint state was mischaracterized — repeatedly, across S1.1–S1.3.** Both are now fixed.

---

## 🔴 F1 — `just lint-all` was red across nearly the whole repo; the done-notes mislabeled it *(FIXED)*

Every stage's done-note (S1.1, S1.2, S1.3) waved off `just lint-all` with the same claim: *"inherited pre-existing ktlint failures in `tools/capabilities-mcp` (git `af5ed38`, Stage 2.1); not caused by this stage, per AGENTS.md §12 gotcha."* Three things were wrong:

1. **It wasn't capabilities-mcp-only.** `ktlintCheck --continue` failed in **10 source sets**: `agents:themis` (main+test), `tools:capabilities-mcp` (main+test), `shared:libs:kotlin:capabilities-client` (main+test), `shared:proto` (test + build-script), `tools:_smoke-test` (test), `workers:_smoke-worker` (test). ~245 violations total.
2. **It wasn't `af5ed38`/Stage 2.1.** `af5ed38` is *"Stage 2.1 — Koog spike: GO"*, a **themis-arc** commit — not the source of capabilities-mcp lint debt. And `git show HEAD` confirms S1.3 touched **no `.kt` source** under themis/ or capabilities-mcp/, so the debt genuinely predates the fork — but it is repo-wide, not module-scoped.
3. **AGENTS.md §12 contained no such carve-out.** §12.1 is solely about forked-module *provenance headers*. The cited "gotcha" did not exist.

**Root cause:** the repo uses `ktlint_code_style = ktlint_official`, whose `class-signature` and `chain-method-continuation` rules reject Kotest's fluent idioms (`StringSpec({…})`, `a shouldBe b`, `Builder.newBuilder().setX().build()`). Every kantheon-authored spec written without `ktlintFormat` fails. (Forked ai-platform libs arrive pre-formatted, which is why *they* were clean — masking how broad the rest of the debt was.) **`just lint-all` had effectively never been green.**

**Fix applied:** `ktlintFormat` across all modules + the few non-auto-fixable line-length cases resolved. **`just lint-all` is now green repo-wide; `just test-all` green; per-module test suites (incl. themis) green** — 34 files reformatted, zero logic changes. Bora approved reformatting the active Themis arc's files as part of this. A real preventive entry was added to **AGENTS.md §12** ("ktlint_official + Kotest = run `ktlintFormat` before done"), and the false notes in `tasks.md` Phase 1 DONE + `tasks-p1-s1.3` T4/T7 were corrected.

> My own `ForkedProtoDescriptorSpec.kt` (review-001) was among the offenders — 2 violations, now formatted. Mea culpa; the new §12 gotcha is the guard.

---

## 🟡 F2 — "Phase 1 complete" vs the Phase 1 DONE gate *(reconciled)*

`plan.md` Phase 1 DONE (strict) reads: *"`just init && just test-all && just lint-all` green from a clean clone with no `gpr.user`/`gpr.token`; `rg cz.dfpartner gradle/ settings.gradle.kts build.gradle.kts` returns nothing."* None of those three literally hold at end of S1.3:

- **lint-all** — was red (F1; now fixed → green).
- **rg cz.dfpartner returns nothing** — returns the `ai-platform-proto` coordinate (the `nlp.v1` residual).
- **no-PAT clean clone** — Themis still resolves `cz.dfpartner:shared-proto` from GitHub Packages, so a PAT is still required for that one artifact.

The Maven/PAT items are **by design** — the `nlp.v1` residual is explicitly deferred to Stage 2.6 — and the S1.3 author had already (correctly) rewritten the `tasks.md` Phase 1 DONE gate to match that reality ("shows **only** the documented Themis-residual… needs PAT **only** for that one artifact"). That softening is reasonable and well-documented; it just means **`plan.md`'s strict Phase 1 DONE and `tasks.md`'s reconciled one now disagree**, and the strict gate is only fully met after Stage 2.6.

**Net:** Phase 1 is complete **modulo the one documented `nlp.v1` Maven residual that closes in Stage 2.6**. With F1 fixed, the only outstanding gap against the strict gate is that intentional residual. Worth a one-line reconcile of `plan.md`'s Phase 1 DONE to point at the `tasks.md` version (left as a Phase 4 docs-sweep item — `plan.md` §1.3 / the ttr-parser list are already flagged there).

---

## 🟢 F3 — Inherited `Language` enum duplication *(note only, no action)*

`org.tatrman.ariadne.v1.Language` and the new `org.tatrman.proteus.v1.Language` are identical 5-value enums (`SQL`/`TRANSFORMATION_DSL`/`DATAFRAME_DSL`/`REL_NODE`). This duplication is **inherited verbatim** from ai-platform (metadata.proto + translator.proto each declared their own), not introduced by the fork — carried forward faithfully. If a future hop needs to convert between the two, it's an awkward seam; flag for the Proteus (2.4) / Ariadne (2.1) authors to consider consolidating, but not a Stage 1.3 defect.

---

## What was checked and is correct (no action)

- **7+1 libs forked** with `forked-from: ai-platform@2575b923` provenance headers; `capabilities-client` correctly has none (first-party).
- **Catalog discipline:** no hardcoded versions in any forked Kotlin `build.gradle.kts` (only `libs.*` aliases); new entries (Calcite 1.41, Exposed, Hikari, PG/MSSQL drivers, Arrow, POI/Parquet, Hadoop, OTel/ktor extras) all `version.ref`-backed.
- **`proteus/v1/translator.proto`** — clean minimal library-only slice (just `Language`/`SqlDialect` enums) with a clear note that the full service surface lands in 2.4 and re-uses these; pragmatic and well-scoped to let `query-translator` fork in isolation.
- **`SchemaCodes.kt`** correctly placed at `shared/proto/src/main/kotlin/org/tatrman/plan/v1/` (matches the proto package); the Stage 1.2 omission is genuinely closed.
- **`query-translator/build.gradle.kts`** — `erp-sql-common` drop is justified (verified unreferenced) and documented inline.
- **Maven cut:** only `ai-platform-proto` remains in the catalog; `settings.gradle.kts` `AiPlatformPackages` block narrowed with the Stage-2.6 removal comment.
- **Forked-lib package-root exception** (`shared.*`, `org.fuzzy.*` kept rather than rewritten to `org.tatrman.kantheon.*`) is the right call and explicitly recorded in the task doc header.
- Build green; the new `ForkedProtoDescriptorSpec` still passes after formatting.

---

## Sign-off

Stage 1.3 deliverables: **accepted.** Issues found were a real, repo-wide lint debt that prior stages mislabeled, plus an overstated completion claim — **both fixed/reconciled in this pass.** Remaining true gap to a literal Phase 1 DONE: the single intentional `nlp.v1` Maven residual, closing in Stage 2.6.

*No open task list — all findings resolved inline. Verification: `just lint-all` green, `just test-all` green, 2026-06-13.*
