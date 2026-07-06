# ttr-translator arc — Stage B2: Translator switch (Proteus + Ariadne) + lib deletion

> Branch: `feat/ttr-translator-b2-switch`. Pre-flight: **B1 DONE.** Tracker: [`tasks.md`](./tasks.md). Contracts: tatrman `docs/ttr-translator/architecture/contracts.md` §3/§4.2.
>
> Goal: both in-repo consumers of `query-translator` (Proteus, Ariadne's `QueryParseWorker`) consume `org.tatrman:ttr-translator` 0.1.0; the in-repo lib is deleted. The one deliberate break is the package rename — a mechanical import rewrite. Behavior frozen: suites (incl. Calcite golden tests) must pass unchanged.

## Pre-flight

- [ ] B1 checked in [`tasks.md`](./tasks.md); tree green.
- [ ] Baseline inventory of the rewrite surface (record the count for T2's verification):
  `grep -rln "org\.tatrman\.query\.shared\.translator" services shared --include="*.kt" | wc -l` → N = ___.

## Tasks

- [ ] **T1 — Re-point the Gradle deps.**
  `services/proteus/build.gradle.kts` and `services/ariadne/build.gradle.kts`: `implementation(project(":shared:libs:kotlin:query-translator"))` → `implementation(libs.tatrman.ttr.translator)`. If either module used the lib's test fixtures (`InMemoryModelHandle`): `testImplementation(testFixtures(libs.tatrman.ttr.translator))` — check with `grep -rln "InMemoryModelHandle" services/`.
  *Verify:* `./gradlew :services:proteus:dependencies --configuration compileClasspath | grep "org.tatrman:ttr-translator"` → resolved (same for ariadne).

- [ ] **T2 — Import rewrite (mechanical, repo-wide).**
  ```bash
  grep -rl "org\.tatrman\.query\.shared\.translator" services shared --include="*.kt" | \
    xargs sed -i '' 's/org\.tatrman\.query\.shared\.translator/org.tatrman.translator/g'
  ```
  *Verify:* `grep -rn "org\.tatrman\.query" services shared --include="*.kt" | wc -l` → 0; rewritten-file count matches the pre-flight N.

- [ ] **T3 — Consumer suites green (behavior-frozen proof).**
  *Verify:* `./gradlew :services:proteus:test` green **including the Calcite golden tests** (the Stage-2.4 DONE bar re-held against the artifact); `./gradlew :services:ariadne:test` green (QueryParseWorkerSpec et al.).

- [ ] **T4 — Delete the in-repo lib.**
  `git rm -r shared/libs/kotlin/query-translator`; remove `include(":shared:libs:kotlin:query-translator")` from `settings.gradle.kts`.
  *Verify:* `./gradlew projects | grep query-translator` → nothing; repo-wide `grep -rn "query-translator" --include="*.kts" .` → only historical docs remain (none in build files).

- [ ] **T5 — Full gate.**
  *Verify:* `just test-all && just lint-all` green; `kubectl kustomize overlays/local` still renders (no manifests referenced the lib, but the render check is cheap).

- [ ] **T6 — Local K3s smoke (Stage-2.4 pattern).**
  `just deploy-kt proteus`; exercise a translate round-trip against the deployed pod (the existing smoke used for 2.4). Ariadne: `just deploy-kt ariadne` + a QueryParseWorker-touching request if the smoke exists; else its unit gate from T3 stands.
  *Verify:* pods healthy; smoke calls succeed.

- [ ] **T7 — Commit + tracker.**
  Commit series `ttr-translator B2: consume org.tatrman:ttr-translator; delete query-translator`. Check B2 in [`tasks.md`](./tasks.md).

**DONE means:** zero `org.tatrman.query.shared.translator` references; `shared/libs/kotlin/query-translator` gone from tree + settings; proteus/ariadne suites green incl. golden tests; `just test-all && just lint-all` green; local deploy smoke passed.

## Blockers

_(empty)_
