# Stage 2.2 — Resolver extraction via `git filter-repo`

> **Phase 2, Stage 2.2.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4.2, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §3.1 (module map for `agents/themis/`).

## Goal

`agents/resolver/` from ai-platform appears at `kantheon/agents/themis/` with proto package renamed `cz.dfpartner.resolver.v1` → `org.tatrman.kantheon.themis.v1`. Existing unit + mocked component tests pass (real-dependency integration verification deferred to the separate integration-test suite per planning-conventions.md §4). Image builds. The plain-coroutines `ResolverGraph` keeps working (rename to `ThemisGraph` is part of Stage 2.3 — for this stage, identifier remains for cleaner blame).

## Pre-flight

- [ ] **Phase 1 DONE.**
- [ ] **Branch in kantheon**: `feat/p2-s2.2-resolver-extraction` from `main`.
- [ ] **`git-filter-repo` installed**: `brew install git-filter-repo` (not the bundled `git filter-branch` — that's deprecated and slower).
- [ ] **A scratch clone of ai-platform** for the dry-run: `git clone /Users/bora/Dev/ai-platform /tmp/ai-platform-scratch`.
- [ ] **Bora's call on what history to preserve** — default: preserve full ai-platform commit history scoped to `agents/resolver/`. Alternative: snapshot at HEAD without history. Confirm before T1.

## Tasks

- [x] **T1 — Dry-run `git filter-repo` on a scratch clone.**

  In the scratch clone:

  ```bash
  cd /tmp/ai-platform-scratch
  git filter-repo \
    --path agents/resolver/ \
    --path-rename agents/resolver/:agents/themis/
  ```

  Inspect the result:

  ```bash
  git log --oneline --all | head -30      # commits relevant to agents/resolver, now rewritten
  ls agents/themis/                        # files moved
  git log -- agents/themis/ | head -20     # history preserved
  ```

  Verify:
  - History for `agents/resolver/` is preserved under `agents/themis/`.
  - No commits unrelated to `agents/resolver/` remain.
  - Branch heads make sense (only `main` after filter, plus whatever branches touched resolver).

  **If history-preservation surprises:** check the docs at https://github.com/newren/git-filter-repo and adjust the invocation. Common alt: `--subdirectory-filter agents/resolver/` (only the subdir's history; loses the `agents/themis/` path prefix — useful if you want resolver content at repo root).

  Acceptance: dry-run results look as expected; document the chosen filter-repo invocation in this task's PR description.

- [x] **T2 — Apply the filter-repo result into kantheon.**

  Since the scratch already has the right shape, the cleanest path is:

  ```bash
  cd /Users/bora/Dev/kantheon
  git checkout -b feat/p2-s2.2-resolver-extraction
  git remote add scratch /tmp/ai-platform-scratch
  git fetch scratch main:scratch-main
  # Merge scratch-main into the current branch, allowing unrelated histories.
  git merge --allow-unrelated-histories scratch-main -X theirs --no-edit
  git remote remove scratch
  ```

  Verify the result:

  ```bash
  ls agents/themis/                                       # see resolver content
  git log --oneline agents/themis/ | head -10             # ai-platform history preserved
  ./gradlew :agents:themis:build --dry-run                # may fail — proto package still cz.dfpartner.resolver.v1
  ```

  At this point the module is in kantheon but **the proto package still says `cz.dfpartner.resolver.v1`** and Gradle deps still reference ai-platform-internal paths. Those are fixed in subsequent tasks.

  Acceptance: `agents/themis/` exists with full source tree; history shows ai-platform commits.

- [x] **T3 — Proto package rename `cz.dfpartner.resolver.v1` → `org.tatrman.kantheon.themis.v1`.**

  Three things to update:

  **a) Move the proto file:**

  ```bash
  mkdir -p shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/
  # The proto was inside agents/themis/ in ai-platform's layout, OR in ai-platform/shared/proto/cz/dfpartner/resolver/v1/.
  # Find it:
  find . -name "resolver.proto" -type f
  # Then move it:
  git mv <found-path>/resolver.proto shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto
  ```

  **b) Update `package` line + `option java_package` (if present) in `themis.proto`:**

  ```diff
  -package cz.dfpartner.resolver.v1;
  -option java_package = "cz.dfpartner.resolver.v1";
  +package org.tatrman.kantheon.themis.v1;
  +option java_package = "org.tatrman.kantheon.themis.v1";
  ```

  **c) Sweep all Kotlin sources** in `agents/themis/` for the old package and update imports:

  ```bash
  cd /Users/bora/Dev/kantheon/agents/themis
  grep -rln "cz.dfpartner.resolver.v1" src/ | while read f; do
    sed -i.bak 's|cz\.dfpartner\.resolver\.v1|org.tatrman.kantheon.themis.v1|g' "$f"
    rm "$f.bak"
  done
  # Also update any string literals that reference the old package path:
  grep -rn "cz/dfpartner/resolver" src/ tests/ 2>&1 | head
  ```

  Acceptance: `grep -r "cz.dfpartner.resolver" agents/themis/ shared/proto/` returns no matches; `./gradlew :shared:proto:assemble` succeeds; generated Kotlin lives under `org/tatrman/kantheon/themis/v1/`.

- [x] **T4 — Update `agents/themis/build.gradle.kts` for kantheon's Gradle topology.**

  The ai-platform `build.gradle.kts` references ai-platform-internal modules (e.g. `implementation(project(":shared:libs:kotlin:otel-config"))`). Replace those with the published Maven coordinates from kantheon's `gradle/libs.versions.toml`:

  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.jvm)
      alias(libs.plugins.ktor)
      alias(libs.plugins.jib)
      alias(libs.plugins.kotlin.serialization)
  }

  dependencies {
      implementation(project(":shared:proto"))               // local — kantheon proto bindings
      implementation(libs.ai.platform.proto)                  // pulls cz.dfpartner.nlp.v1, .metadata.v1
      implementation(libs.ai.platform.otel.config)
      implementation(libs.ai.platform.fuzzy.common)           // FuzzyMatcher client types
      implementation(libs.ai.platform.mcp.base)
      implementation(libs.ai.platform.ktor.config)
      implementation(libs.ai.platform.logging)
      implementation(project(":shared:libs:kotlin:capabilities-client"))  // local — Phase 1 deliverable
      implementation(libs.ktor.client.cio)
      implementation(libs.ktor.server.cio)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlin.logging)
      // Koog dependency added in Stage 2.3 — for now keep plain-coroutines build green.

      testImplementation(libs.kotest.runner.junit5)
      testImplementation(libs.kotest.assertions.core)
      testImplementation(libs.wiremock)
      testImplementation(libs.ktor.server.test.host)
      testImplementation(libs.mockk)
      // No Testcontainers — per the testing policy (planning-conventions.md §4),
      // dev against mocked unit tests only (Wiremock/MockK); real-dependency
      // tests live in the separate integration-test suite.
  }

  jib {
      to { image = "themis-mcp:dev" }
      container { mainClass = "org.tatrman.kantheon.themis.AppKt" }
  }
  ```

  Also rename the Kotlin root package directory:

  ```bash
  cd agents/themis/src/main/kotlin/
  # The ai-platform directory was probably "agents/resolver/" — rename to "org/tatrman/kantheon/themis/"
  mkdir -p org/tatrman/kantheon
  git mv agents/resolver org/tatrman/kantheon/themis
  # Update any `package agents.resolver` declarations:
  grep -rl "^package agents\.resolver" .
  # batch update:
  grep -rln "package agents\.resolver" . | while read f; do
    sed -i.bak 's|^package agents\.resolver|package org.tatrman.kantheon.themis|; s|^package agents\.resolver\.|package org.tatrman.kantheon.themis.|' "$f"
    rm "$f.bak"
  done
  # Same for test sources.
  ```

  And in `agents/themis/src/main/resources/application.conf`, replace any `resolver` references with `themis`:

  ```bash
  sed -i.bak 's|resolver|themis|g' agents/themis/src/main/resources/application.conf
  rm agents/themis/src/main/resources/application.conf.bak
  ```

  Add to root `settings.gradle.kts`: `include(":agents:themis")`.

  Acceptance: `./gradlew :agents:themis:build --no-daemon` succeeds (may have test failures — fix in T5).

- [x] **T5 — Run carried-over tests; fix the references that didn't sweep cleanly.**

  ```
  just test-kt themis
  ```

  Common failure modes and fixes:

  - **Hardcoded `cz.dfpartner.resolver` in test fixtures or YAML** — sweep with grep, update.
  - **MockK setup for `FuzzyClient` / `NlpClient` interfaces** — if those interfaces came from ai-platform Maven libs, the imports should already be correct via `libs.ai.platform.fuzzy.common`. Verify.
  - **Wiremock stub endpoint URLs** — were `http://localhost:NNNN/v1/...`; should still work.
  - **Application config keys** — if a config key was `resolver.foo`, rename to `themis.foo` consistently.

  Acceptance: all tests that passed in ai-platform's HEAD `agents/resolver/` now pass in kantheon's `agents/themis/`. **Identical green count.** Disagreements are diagnosed and fixed.

- [x] **T6 — K8s manifests + first deploy.**

  Move `agents/resolver/k8s/{base,overlays/local}/` (if filter-repo brought them along) to `agents/themis/k8s/`. If not present (the audit noted they were staged-for-deletion at one point), recreate from scratch using the `capabilities-mcp` layout in Stage 1.4 as a template.

  Key updates:
  - Deployment name: `themis-mcp`.
  - Image: `themis-mcp:dev`.
  - Namespace: `kantheon`.
  - Port: 7401 (or whatever the application.conf says).
  - Env vars:
    - `OTEL_SERVICE_NAME=themis-mcp`
    - `CAPABILITIES_MCP_URL=http://capabilities-mcp.kantheon.svc.cluster.local:7501` (Themis at Stage 2.4 doesn't yet read capabilities, but the env is plumbed).
    - `NLP_MCP_URL=http://nlp-mcp.ai-platform.svc.cluster.local:NNNN`
    - `FUZZY_MCP_URL=http://fuzzy-mcp.ai-platform.svc.cluster.local:NNNN`
    - `LLM_GATEWAY_URL=http://llm-gateway.ai-platform.svc.cluster.local:NNNN`
  - Readiness: `/ready`. Liveness: `/health`.

  Deploy:

  ```
  just deploy-kt themis
  kubectl -n kantheon wait deployment/themis-mcp --for=condition=Available --timeout=120s
  kubectl -n kantheon port-forward svc/themis-mcp 7401:7401 &
  curl -sf http://localhost:7401/health
  ```

  Note: deeper real-services smoke (calling `/v1/resolve` against the fuzzy-mcp/nlp-mcp/llm-gateway) belongs to the **separate integration-test suite** and is exercised by **Stage 2.4's eval gate** (corpus eval). T6 only confirms the pod boots and `/health` answers.

  Acceptance: pod Ready in K3s; `/health` 200; PR opened titled `[p2-s2.2] resolver extraction → agents/themis`.

## DONE — Stage 2.2

- [x] All six tasks above checked. **T1 reframed**: per Bora (2026-05-29), history policy switched to HEAD snapshot — `git filter-repo` not invoked; `git archive HEAD agents/resolver` used instead.
- [x] `./gradlew :agents:themis:build && :agents:themis:test` green (4 specs, 35 tests — parity with ai-platform HEAD).
- [x] `agents/themis` deploys to K3s; `/health` 200. Verified on Rancher Desktop K3s 2026-05-30: pod Ready, `/health` → `{"status":"ok"}` HTTP 200, `/ready` → `{"status":"ready"}` HTTP 200. Restart count 1 on first boot (kubelet probe timing on cold start; benign).
- [x] Proto package fully renamed to `org.tatrman.kantheon.themis.v1`.
- [x] All ai-platform-internal Gradle project deps replaced with Maven coords. **Plus**: `tools.nlp.mcp.client.NlpClient` (128 LOC, not published as a Maven jar by ai-platform) vendored into `org.tatrman.kantheon.themis.client.NlpClient` with a provenance comment. Follow-up: if ai-platform publishes an `nlp-mcp-client` artifact, swap the vendor copy.
- [ ] PR merged. _(pending Bora.)_

## Library / pattern references

- **`git-filter-repo` docs** — https://github.com/newren/git-filter-repo/blob/main/README.md, especially "Examples".
- **ai-platform `agents/resolver/`** — at `/Users/bora/Dev/ai-platform/agents/resolver/`. Compare structure before/after the move.
- **ai-platform `tools/nlp-mcp/k8s/`** — k8s manifest reference (also used in Phase 1 Stage 1.4).
- **kantheon `tools/capabilities-mcp/k8s/`** (from Phase 1 Stage 1.4) — most-recent kantheon-side k8s example.

## Out of scope for Stage 2.2

- Koog migration (Stage 2.3).
- Wiring Themis to read capabilities from `capabilities-mcp` — Stage 3.3 task adds the client.
- Eval-gate run — Stage 2.4.
- Renaming the `ResolverGraph` Kotlin identifier itself — leave it as `ResolverGraph` for now; the rename to `ThemisGraph` happens during Stage 2.3 migration (so blame is cleaner: one rename + one migration commit, not three).
- Retiring `agents/resolver/` from ai-platform — separate PR after Phase 2 + soak period.
