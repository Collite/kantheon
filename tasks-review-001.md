# Tasks — Review 001 fixes (Fork P1 S1.1 & S1.2)

> Source: [`review-001.md`](./review-001.md). Work top to bottom. Check each box immediately after the item is done **and verified** (run the stated verify command). Do not batch.
>
> One-line summary of priorities: **R1 is a real latent bug — do it first.** R2–R5 are correctness/consistency fixes. R6–R9 are cleanliness; R6 needs a decision from Bora.

---

## 🔴 R1 — Fix the Python wheel `packages` list (latent build break)

**File:** `shared/proto/build.gradle.kts`, inside `PreparePythonPackage.buildPackage()` (the `targetDir.resolve("pyproject.toml").writeText(...)` block).

- [x] **R1.1** — Change the wheel packages line from:
  ```toml
  packages = ["src/cz", "src/org"]
  ```
  to:
  ```toml
  packages = ["src/org"]
  ```
- [x] **R1.2** — Update the comment block directly above the `val pythonPackageDir` / inside the task that explains the `src/cz` shipping. Replace the sentences:
  > *"The wheel is configured to ship `src/cz` (the Themis-residual ai-platform types Themis keeps consuming from Maven until Stage 2.6) and `src/org` (everything we own). When the Themis swap lands, the `src/cz` line drops."*

  with an accurate statement, e.g.:
  > *"The wheel ships only `src/org` — the in-repo `org.tatrman.*` generated types. The `cz.dfpartner.*` types Themis still consumes arrive as a compiled Maven jar (Java only); protoc emits no Python for them in this repo, so there is no `src/cz` tree to ship."*
- [x] **R1.3 — Verify the wheel actually builds** (the gap that let this through). Run:
  ```bash
  ./gradlew :shared:proto:preparePythonPackage
  cd shared/proto/build/python-package && uv build --wheel
  ```
  The `uv build` must succeed and produce a `.whl` under `dist/`. If `uv` is unavailable, instead run `python -m build --wheel` (after `pip install build hatchling`). **Done = a wheel is produced with no "Unable to determine which files to ship" error.** Delete the `dist/`/build artifacts afterward (they live under `build/`, so a `:shared:proto:clean` clears them).

---

## 🟡 R2 — Confirm the restored descriptor spec is in place (mostly done — verify only)

The Kotest spec that T1 originally mandated was added this session at
`shared/proto/src/test/kotlin/org/tatrman/kantheon/fork/ForkedProtoDescriptorSpec.kt`.

- [x] **R2.1** — Confirm the file exists and run it green:
  ```bash
  ./gradlew :shared:proto:clean :shared:proto:test --tests '*ForkedProtoDescriptorSpec*'
  ```
  Must be `BUILD SUCCESSFUL` from a **clean** build (proves the "brittle source-set" theory was wrong). If it is not present or fails, stop and report — do **not** reintroduce any `compileTestKotlin.dependsOn(...)` or `sourceSets.main.output` workaround; the fix for a phantom unresolved-reference is `:shared:proto:clean`, nothing in the build script.
- [x] **R2.2** — Confirm the S1.2 T1 done-note in `docs/implementation/v1/fork/tasks-p1-s1.2-pipeline-protos.md` already reflects this (it was corrected this session). No edit expected; just verify it says the invariants are enforced by `ForkedProtoDescriptorSpec`, not by a "brittle" rationale.

---

## 🟡 R3 — Correct the stale comments in `verify-forked-proto-layout.sh`

**File:** `scripts/verify-forked-proto-layout.sh`, top-of-file comment block (the `#` header before `set -euo pipefail`).

- [x] **R3.1** — Remove the false "brittle" justification. Replace the paragraph that begins *"Done as a shell check rather than a Kotest spec because the in-module Kotlin/Java source-set ordering makes a `Descriptors.FileDescriptor.dependencies` walk brittle…"* with an accurate description, e.g.:
  > *"This shell check is a cheap textual pre-check over the `*.proto` source files. The authoritative wire-contract gate is the Kotest `ForkedProtoDescriptorSpec` in `:shared:proto` (a `Descriptors.FileDescriptor` walk over the generated types). Both are kept: the script is fast and runs without Gradle; the Kotest is the real gate in CI."*
- [x] **R3.2** — Fix the (c) wire-level comment. Replace the sentence *"Wire-level (c) … is enforced by Kotest in `tools/capabilities-mcp` (see `ForkedProtoDescriptorSpec` if/when restored)…"* and the matching inline comment near the `messages = 99` checks so they point at `shared/proto/src/test/kotlin/org/tatrman/kantheon/fork/ForkedProtoDescriptorSpec.kt` — **not** `tools/capabilities-mcp` (those tests were deleted).
- [x] **R3.3 — Verify** the script still runs green after the comment edits:
  ```bash
  bash scripts/verify-forked-proto-layout.sh
  ```
  Must print `OK — all forked proto layout invariants hold.`

---

## 🟡 R4 — Reconcile the Python proto-import convention in `AGENTS.md` §4.1

The docs reference a non-existent `kantheon_proto` package; the build produces `shared-proto` with imports rooted at `org.tatrman.*`. **Fix the docs to match the implementation** (do not rename the package).

**File:** `AGENTS.md`, §4.1 "Python modules".

- [x] **R4.1** — In the conventions table, change the **Imports** row from:
  > "**Generated shared-proto package only** (from `just proto-py`). Never `import src.proto.*` paths"

  to something accurate, e.g.:
  > "**Generated `shared-proto` package only** (from `just proto-py`): `from org.tatrman.<pkg>.v1 import <file>_pb2`. Never reach into the source tree."
- [x] **R4.2** — Change the Conventions bullet from:
  > "Proto imports come from the **generated** package — `import kantheon_proto.<package>.v1.<type>` — never by reaching into the shared-proto source tree."
  > "Proto types stay generated by `just proto-py`; hand-edits to `kantheon_proto/` get clobbered."

  to use the real import form and package root, e.g.:
  > "Proto imports come from the **generated** `shared-proto` package — `from org.tatrman.<pkg>.v1 import <file>_pb2` — never by reaching into the source tree."
  > "Proto types stay generated by `just proto-py`; hand-edits to the generated `org/` tree get clobbered."
- [x] **R4.3 — Verify** there is no remaining `kantheon_proto` reference anywhere in the repo docs:
  ```bash
  rg -n 'kantheon_proto' AGENTS.md docs/ CLAUDE.md EXAMPLES.md
  ```
  Must return **no matches**. (If any match is a deliberate future-rename plan, leave it but add a one-line note that the current package is `shared-proto`/`org.tatrman.*`.)

---

## 🟡 R5 — Add the platform-service / pipeline proto-root exception to `AGENTS.md` §4

**File:** `AGENTS.md`, §4 "Conventions" bullet that currently reads: *"Package root: `org.tatrman.kantheon.<module>` for Kotlin. Proto root: `org.tatrman.kantheon.<package>.v1`."*

- [x] **R5.1** — Append the exception so it agrees with `CLAUDE.md` §4, e.g.:
  > "Package root: `org.tatrman.kantheon.<module>` for Kotlin. Proto root: `org.tatrman.kantheon.<package>.v1` for constellation/agent contracts — **but platform services use `org.tatrman.<service>.v1` and cross-service pipeline packages keep functional names `org.tatrman.{plan,worker,transdsl,dfdsl}.v1`** (see `CLAUDE.md` §4)."
- [x] **R5.2 — Verify** the claim against the tree (these must all be `org.tatrman.*`, not `org.tatrman.kantheon.*`):
  ```bash
  rg -n '^package ' shared/proto/src/main/proto/org/tatrman/{plan,worker,transdsl,dfdsl,ariadne}/v1/*.proto
  ```

---

## 🟢 R6 — DECISION NEEDED, then optional prose pass on forked proto headers

Forked proto files still title themselves `// AI Platform v1 — …` and `ariadne.proto`'s doc-comment calls its own service "the metadata service" though it is now `AriadneService`.

- [x] **R6.1 — Decision recorded 2026-06-13.** Bora chose **(A) leave the prose verbatim as provenance** — `// AI Platform v1 — …` titles and the "the metadata service" doc-comment wording stay as a historical artifact of the fork. R6.2 / R6.3 are not executed.
- [ ] **R6.2 — If (B) chosen:** in `ariadne.proto`, change the title `// AI Platform v1 — Metadata service` → `// Ariadne v1 — model-graph service (forked from ai-platform metadata)` and reword the opening doc-comment so the subject is "Ariadne" / "the model-graph service" rather than "the metadata service". For the other six files, update only the title line `// AI Platform v1 — …` → `// <persona/package> v1 — …` (e.g. plan/worker/transdsl/dfdsl keep their functional names: `// plan/v1 — Plan wire format (forked from ai-platform)`). **Do not** touch field numbers, types, packages, or imports — comments only.
- [ ] **R6.3 — If (B) chosen, verify** nothing but comments changed:
  ```bash
  ./gradlew :shared:proto:clean :shared:proto:test
  git diff --stat shared/proto/src/main/proto
  ```
  Build green; diff limited to the proto files; descriptor spec still passes.

---

## 🟢 R7 — Tidy the stale `proto` recipe comment in `justfile`

**File:** `justfile`, the comment directly above the `proto:` recipe.

- [x] **R7.1** — Replace *"Python + TS targets land when their consumer packages exist (Stage 1.3+)."* with an accurate note, e.g.:
  > "Emits Kotlin + Python (via `:shared:proto:assemble`, which depends on `preparePythonPackage`). TS bindings land when `envelope-ts` consumers exist."
- [x] **R7.2 — Verify** the recipe still works: `just proto` completes and `shared/proto/build/python-package/src/org/` exists afterward.

---

## 🟢 R8 — Record the Themis Rule-6 follow-up for Stage 2.6 (no code change now)

`themis.proto` field 99 still uses `cz.dfpartner.metadata.v1.ResponseMessage` (deferred, allowlisted). Make sure the swap isn't forgotten.

- [x] **R8.1** — In `docs/implementation/v1/fork/tasks-p2-s2.6-themis-switchover.md`, confirm there is an explicit task (or add a checkbox) to **retarget `themis.proto`'s `messages = 99` field from `cz.dfpartner.metadata.v1.ResponseMessage` to `org.tatrman.kantheon.common.v1.ResponseMessage`** alongside the `nlp.v1` import swap, so post-2.6 every in-repo proto shares one Rule-6 type.
- [x] **R8.2 — Verify** the pointer exists:
  ```bash
  rg -n 'ResponseMessage|messages = 99|Rule.?6' docs/implementation/v1/fork/tasks-p2-s2.6-themis-switchover.md
  ```

---

## 🟢 R9 — (Optional) fork banner on the other six forked protos

Lowest priority; skip unless Bora wants full divergence traceability.

- [ ] **R9.1** — If desired, add a one-line comment near the top of `plan.proto`, `context.proto`, `parameters.proto`, `worker.proto`, `transdsl.proto`, `dfdsl.proto` mirroring the `ariadne.proto` fork note: `// Forked 2026-06-13 from ai-platform@2575b923 (cz.dfpartner.<X>.v1 → org.tatrman.<X>.v1).` Comments only — no contract change. *Skipped 2026-06-13: Bora's R6 decision ("leave prose verbatim as provenance") also covers this — no fork banners added beyond ariadne's existing note.*

---

## Final gate (run after R1–R7)

- [x] **G1** — `./gradlew :shared:proto:clean :shared:proto:test` green (includes `ForkedProtoDescriptorSpec`).
- [x] **G2** — `bash scripts/verify-forked-proto-layout.sh` prints OK.
- [x] **G3** — `just proto` green; `uv build --wheel` (or `python -m build`) in `build/python-package` succeeds.
- [x] **G4** — `rg -n 'kantheon_proto' AGENTS.md docs/ CLAUDE.md EXAMPLES.md` returns nothing.
- [x] **G5** — `just test-all` green (no regression in other modules).
