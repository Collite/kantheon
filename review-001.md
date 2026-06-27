# Review 001 — Fork Phase 1, Stages 1.1 & 1.2

> Reviewer: Claude (per [`reviews.md`](./reviews.md)). Date: 2026-06-13. Branch: `feat/fork-p1-s1.2-pipeline-protos`.
> Scope: Stage 1.1 (Fork point & conventions) + Stage 1.2 (Pipeline protos), per [`docs/implementation/v1/fork/tasks.md`](./docs/implementation/v1/fork/tasks.md).
> Inputs read: [`plan.md`](./docs/implementation/v1/fork/plan.md) Phase 1, [`tasks-p1-s1.1-conventions.md`](./docs/implementation/v1/fork/tasks-p1-s1.1-conventions.md), [`tasks-p1-s1.2-pipeline-protos.md`](./docs/implementation/v1/fork/tasks-p1-s1.2-pipeline-protos.md), all 7 forked protos, `shared/proto/build.gradle.kts`, `workers/_smoke-worker/*`, `justfile`, `scripts/verify-forked-proto-layout.sh`, `AGENTS.md` §4/§4.1/§12, `provenance-template.md`, `contracts.md` §7.

## Verdict

**Both stages are sound.** The fork is faithful: all 7 pipeline protos (`plan`/`context`/`parameters`, `worker`, `transdsl`, `dfdsl`, `ariadne`) carry correct `org.tatrman.*` packages + `option java_package`, imports retarget cleanly, the `MetadataService → AriadneService` rename is in place, the local `ResponseMessage`/`Severity` were deleted with a fork note, and every `messages = 99` field (plus the stray non-99 `issues` field) was retargeted to the kantheon stand-in. The `workers/` tree wiring (`_smoke-worker`) is minimal and mirrors `tools/_smoke-test` correctly. The justfile Python lane and the ports/provenance conventions are well structured. Build is green; the descriptor invariants now have a real JVM gate (added this session — see D1).

No architectural problems with the module boundaries or the proto packaging design. The findings below are **one latent bug that will break the Python lane later**, **two task-list deviations** (one already remediated), and **a cluster of cross-document inconsistencies** that will mislead the next contributor if not reconciled now while the conventions are fresh.

Severity legend: 🔴 will break later · 🟡 deviation / inconsistency · 🟢 cleanliness.

---

## 🔴 C1 — Python wheel packaging references a directory that never exists

`shared/proto/build.gradle.kts`, `PreparePythonPackage.buildPackage()` writes:

```toml
[tool.hatch.build.targets.wheel]
packages = ["src/cz", "src/org"]
```

But the protobuf `python` builtin only generates an `org/` tree in this repo — confirmed: `build/generated/sources/proto/main/python/` contains **only `org/`**, no `cz/`. The `cz.dfpartner.*` types Themis still consumes arrive as a **compiled Maven jar (Java)**; protoc never emits Python for them here, so `src/cz` is never created.

**Impact:** latent, but guaranteed. The moment a real Python module (Kadmos in Stage 2.3, Steropes in 3.4, or Metis) declares `shared-proto` as a path dependency and `uv sync` builds the wheel, hatchling fails with "Unable to determine which files to ship inside the wheel" because `src/cz` doesn't exist. Stage 1.2 T4's done-note only verified the *import path* resolves against the generated tree — it never built the wheel, so the bug slipped through.

The in-code comment ("ship `src/cz` … When the Themis swap lands, the `src/cz` line drops") is doubly wrong: `src/cz` is not produced *now*, so there is nothing to drop later. **Fix → R1.**

---

## 🟡 D1 — Stage 1.2 T1 deviation: mandated Kotest replaced by a shell script on a false premise *(remediated this session)*

T1 explicitly required a Kotest `ForkedProtoDescriptorSpec` in `:shared:proto` doing a `Descriptors.FileDescriptor` walk for invariants (a)/(b)/(c). The done-note instead substituted `scripts/verify-forked-proto-layout.sh`, justified by: *"the in-module Kotlin/Java source-set ordering makes a `FileDescriptor.dependencies` walk in a Kotest inside `:shared:proto` brittle."*

**That premise is false.** It was investigated separately this session: the symptom (test Kotlin failing to resolve main-output proto classes) was a **stale Kotlin incremental-compile cache / stale main jar**, cleared by `:shared:proto:clean` — not a source-set wiring defect. A full clean build compiles and runs a descriptor-walking Kotest against the Java-generated `org.tatrman.*` types with **no** source-set workarounds.

**Status: resolved.** `shared/proto/src/test/kotlin/org/tatrman/kantheon/fork/ForkedProtoDescriptorSpec.kt` now implements all three invariants (22 cases over the 7 outer classes), passes clean, and was negative-tested (breaking invariant (c) fails exactly on `worker.proto` + `ariadne.proto`, the only field-99 carriers). The shell script is kept as a cheap textual pre-check. **Remaining work → R2** (the task-doc note was corrected; the *script header* still carries the false rationale — see D2).

---

## 🟡 D2 — `verify-forked-proto-layout.sh` header comments are now factually wrong

The script's top-of-file comment block still states three things that are no longer true:

1. *"a `Descriptors.FileDescriptor.dependencies` walk … brittle from inside `:shared:proto`'s test source set"* — disproven (D1).
2. *"Wire-level (c) … is enforced by Kotest in `tools/capabilities-mcp` (see `ForkedProtoDescriptorSpec` if/when restored)"* — the capabilities-mcp tests were **deleted**, and the spec now lives in `:shared:proto`, not capabilities-mcp.
3. The (c) inline comment repeats the same stale "enforced by Kotest in `tools/capabilities-mcp`" pointer.

A reader trusting these comments would look in the wrong module for the real gate and re-absorb the false "brittle" theory. **Fix → R3.**

---

## 🟡 I1 — Python proto import convention: docs say `kantheon_proto`, build produces `shared-proto` / `org.tatrman.*`

Settled-in-S1.1 vs implemented-in-S1.2 disagree on the single most load-bearing Python convention:

| Source | Says |
|---|---|
| `AGENTS.md` §4.1 (table row "Imports") | "**Generated shared-proto package only**" |
| `AGENTS.md` §4.1 (Conventions bullet) | `import kantheon_proto.<package>.v1.<type>` … "hand-edits to `kantheon_proto/` get clobbered" |
| `shared/proto/build.gradle.kts` (actual) | pyproject `name = "shared-proto"`; import resolves as `from org.tatrman.<pkg>.v1 import <file>_pb2` |
| Stage 1.2 T4 done-note (actual) | verified `from org.tatrman.plan.v1 import plan_pb2` |

There is **no `kantheon_proto` package** anywhere. Every Python module that follows AGENTS.md §4.1 literally (Kadmos, Steropes, Metis) will write imports that don't resolve. The implemented `org.tatrman.*` rooting is the natural choice (it matches the proto packages); the docs are stale/aspirational. **Reconcile → R4** (recommend: fix the docs to the implemented `org.tatrman.*` convention, not rename the package).

---

## 🟡 I2 — `AGENTS.md` §4 proto-root rule omits the platform-service / pipeline exception

`AGENTS.md` §4 (Conventions): *"Proto root: `org.tatrman.kantheon.<package>.v1`."* — stated unconditionally. But the forked protos (correctly, per `CLAUDE.md` §4) are rooted at `org.tatrman.<package>.v1` **without** `.kantheon.`: `org.tatrman.plan.v1`, `org.tatrman.worker.v1`, `org.tatrman.ariadne.v1`, etc. `CLAUDE.md` §4 documents the split ("Platform services use `org.tatrman.<service>.v1`; cross-service pipeline packages keep functional names `org.tatrman.{plan,worker,transdsl,dfdsl}.v1`"); AGENTS.md does not. A contributor reading AGENTS.md alone would package a forked service wrongly. **Fix → R5.**

---

## 🟢 M1 — Forked proto file headers/prose still read as ai-platform "metadata service"

`ariadne.proto` was renamed at the *contract* level (`package`, `option`, `AriadneService`) but its prose was left as the verbatim ai-platform original: the file title is still `// AI Platform v1 — Metadata service`, and the service doc-comment says *"The metadata service owns the in-memory model graph…"*. The other six files likewise still title themselves `// AI Platform v1 — …` and reference `query-runner` / `Translator` / `Validator` / `Dispatcher` / `fuzzy-matcher` / `sql-security` in prose.

This is **within the letter of T2** (which scoped only package/options/imports/service-name) but against `CLAUDE.md` §9's persona-vocabulary rule and hurts understandability — a reader opening `ariadne.proto` sees "Metadata service" as the title of a service named `AriadneService`. Provenance prose (the "Promoted in Phase 1.0" notes) is fine to keep; the **service identity in titles/doc-comments** is what jars. **Decision needed → R6** (light header pass vs. deliberate "leave as provenance"). Low priority; not blocking.

---

## 🟢 M2 — Stale `proto` recipe comment in `justfile`

The `proto:` recipe comment reads *"Python + TS targets land when their consumer packages exist (Stage 1.3+)."* — but `proto-py` already exists (landed S1.2 T4), and `just proto` (`:shared:proto:assemble` → `dependsOn(preparePythonPackage)`) already emits the Python package today. Tidy the comment so it doesn't imply Python codegen is still pending. **Fix → R7.**

---

## 🟢 M3 — `themis.proto` field 99 still typed against `cz.dfpartner` (deferred, not a defect — recorded for traceability)

`shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto:130` still declares `repeated cz.dfpartner.metadata.v1.ResponseMessage messages = 99;` — i.e. Themis responses carry a *different* Rule-6 type than every forked file (which now uses the kantheon stand-in). This is **explicitly out of scope** for S1.2 (themis is allowlisted in the verify script and the plan defers the swap to Stage 2.6). **No action now** — logged here only so the Rule-6 split isn't forgotten when Stage 2.6 lands. **Tracked → R8** (verification-only checkbox for 2.6).

---

## 🟢 M4 — Forked proto files (except `ariadne`) carry no fork-provenance note *(optional)*

`shared/proto` is **exempt** from the README provenance rule (the rule names `services/`/`workers/`/`tools/`/`shared/libs/`, not `shared/proto`), so this is not a convention violation. Only `ariadne.proto` got a fork note (because it had a deletion to explain). For divergence traceability you may want a one-line fork banner on the other six forked files too, but it's the lowest-priority item and arguably noise. **Optional → R9.**

---

## What was checked and is correct (no action)

- All 7 forked protos: `package` + `option java_package` = `org.tatrman.*` target; verified by the new Kotest + the shell script.
- `worker.proto` imports retargeted (`plan`, `context`, `ariadne`, `common/v1/response_message`); `metadata.proto → ariadne.proto` filename fix on the import path applied.
- Field-99 retarget complete across `worker.proto` (3) and `ariadne.proto` (16) + the non-99 `issues` field; bare/`cz.dfpartner` field-99 forms absent.
- `ariadne.proto` local `ResponseMessage` + `Severity` deleted; fork note added; `OverallStatus`/`DependencyStatus` (consumed by `worker.proto`) present and defined.
- `worker → ariadne` proto coupling is an inherited design (worker status reuses the metadata status enums), correctly carried forward; T2's "discovered constraint" note documents it.
- `workers/_smoke-worker`: clean placeholder, mirrors `tools/_smoke-test`, README states its throwaway purpose; `settings.gradle.kts` include is correctly commented.
- `provenance-template.md`, ports table (`contracts.md` §7.1), and the justfile `tag`/`tags`/`deploy-kt` machinery are well-formed.
- Build green: `:shared:proto:test` (incl. the new spec) and `scripts/verify-forked-proto-layout.sh` both pass.

---

*Follow-up task list: [`tasks-review-001.md`](./tasks-review-001.md).*
