# ttr-translator arc — Stage B1: Proto adoption

> Branch: `feat/ttr-translator-b1-proto`. Tracker: [`tasks.md`](./tasks.md). Arc doc: [`../../../architecture/fork/ttr-translator-extraction.md`](../../../architecture/fork/ttr-translator-extraction.md). Normative contracts: tatrman `docs/ttr-translator/architecture/contracts.md` §2/§4.1.
>
> Goal: `shared:proto` stops generating `plan.v1`/`transdsl.v1`/`dfdsl.v1` and consumes `org.tatrman:ttr-plan-proto` instead — **zero import changes anywhere** (FQCNs identical). Python moves to the wheel. Everything else must be provably untouched.

## ⛔ Pre-flight (EXTERNAL GATE)

- [ ] `kotlin-translator/v0.1.0` resolvable: temporary `implementation(libs.tatrman.ttr.plan.proto)` after T1's toml entry, then `./gradlew :shared:proto:dependencies --configuration compileClasspath | grep "org.tatrman:ttr-plan-proto"` → resolved line, no FAILED. (Repo + credentials already configured in `settings.gradle.kts` — `includeGroup("org.tatrman")` covers the new artifacts.)
- [ ] `pip index versions ttr-plan-proto` (or `pip download ttr-plan-proto==0.1.0`) → 0.1.0 exists.
- [ ] Clean baseline: `just test-all && just lint-all` green at the starting commit.

## Tasks

- [ ] **T1 — Version catalog entries.**
  `gradle/libs.versions.toml`: version `tatrman-translator = "0.1.0"`; libraries `tatrman-ttr-plan-proto = { module = "org.tatrman:ttr-plan-proto", version.ref = "tatrman-translator" }` and `tatrman-ttr-translator = { module = "org.tatrman:ttr-translator", version.ref = "tatrman-translator" }` (translator entry used in B2; land both now, one toml diff).
  *Verify:* `./gradlew help` configures.

- [ ] **T2 — Delete the transferred protos; adopt the artifact (test = `just proto`).**
  Delete `shared/proto/src/main/proto/org/tatrman/{plan,transdsl,dfdsl}/` (5 files). Add `api(libs.tatrman.ttr.plan.proto)` to `shared/proto/build.gradle.kts`. The protobuf-gradle-plugin extracts `.proto` files from dependency jars onto the protoc include path **without compiling them** (plugin README "Protos in dependencies") — so `import "org/tatrman/plan/v1/plan.proto"` in proteus/theseus/argos/kyklop/worker/ariadne/security protos keeps resolving while generation of plan/transdsl/dfdsl stops.
  *Verify:* `just proto` green; `find shared/proto/build/generated -path "*org/tatrman/plan/v1*" -name "*.kt" | wc -l` → 0 (nothing regenerated locally).

- [ ] **T3 — Duplicate-class guard.**
  Assert exactly one classpath source for the wire classes: `./gradlew :shared:proto:dependencies --configuration runtimeClasspath` shows ttr-plan-proto; and the shared:proto jar itself contains no plan/v1 classes: `./gradlew :shared:proto:jar && unzip -l shared/proto/build/libs/*.jar | grep -c "org/tatrman/plan/v1"` → 0. Add this as a Gradle check task (`noTransferredProtoClasses`) wired into `check` so a stray re-add of the files fails CI loudly.
  *Verify:* `./gradlew :shared:proto:check` green; deliberately re-adding a deleted proto file makes it fail (then revert).

- [ ] **T4 — Kotlin-wide compile + test (the FQCN-stability proof).**
  No import rewrites anywhere in this stage. Run the full Kotlin gate.
  *Verify:* `./gradlew build -x ktlintCheck` green repo-wide (then targeted: `just test-kt proteus`, `just test-kt ariadne`) — proteus, ariadne, theseus, argos, kyklop all compile against artifact-sourced `org.tatrman.plan.v1.*` unchanged.

- [ ] **T5 — Python onto the wheel.**
  `./gradlew :shared:proto:preparePythonPackage` no longer emits `org/tatrman/{plan,transdsl,dfdsl}` modules (files deleted ⇒ nothing generated; imports still resolve at codegen from the extracted include path). Add `ttr-plan-proto==0.1.0` to the shared-proto Python package dependencies (and/or steropes' `pyproject.toml` — wherever `plan_pb2` is imported: `grep -rln "plan_pb2" workers/`). uv lock refresh.
  *Verify:* `just proto-py` green; steropes suite green (`cd workers/steropes && uv run pytest` or the just recipe); `uv run python -c "from org.tatrman.plan.v1 import plan_pb2; print(plan_pb2.__file__)"` → resolves from the wheel path.

- [ ] **T6 — TS bindings untouched (verification only).**
  The buf/ts-proto generations (envelope-ts: envelope/iris/common; sysifos: sysifos/midas/envelope/common) never referenced plan/transdsl/dfdsl.
  *Verify:* `just proto-ts` green; `git status` shows no changes under `shared/libs/ts/` or `frontends/sysifos/src/generated/`.

- [ ] **T7 — Full gate + commit.**
  *Verify:* `just proto && just test-all && just lint-all` green. Commit `ttr-translator B1: shared:proto consumes org.tatrman:ttr-plan-proto`; check B1 in [`tasks.md`](./tasks.md).

**DONE means:** the 5 proto files are gone; all codegen (Kotlin, Python, TS) green; zero import diffs outside build files; duplicate-class guard active; full test gate green.

## Blockers

_(empty)_
