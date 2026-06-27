# Stage 1.1 — Gradle merge

> **Phase 1, Stage 1.1.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §"Stage 1.1", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §4.2 (full Gradle merge — no `includeBuild` half-step), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md), root [`settings.gradle.kts`](../../../../settings.gradle.kts) + [`justfile`](../../../../justfile).

## Goal

Hebe's 16 modules (+ `tools/*` submodules) become **kantheon root-build modules** (`:agents:hebe:modules:*`). Hebe's standalone build machinery (own `settings.gradle.kts`, `build-logic/`, Gradle wrapper, `gradle.properties`) is retired. `just init && just lint && just test` at the kantheon root is green with Hebe included; the `cli-app` shadowJar (`hebe.sh` artifact) still builds. **No package rename yet** — that is Stage 1.2.

## Pre-flight

- [x] Phase pre-flight done (kantheon root build green; Hebe at `agents/hebe/`).
- [x] **Branch**: `feat/hebe-p1-s1.1-gradle-merge` from `main`.
- [x] Snapshot the standalone build as a baseline: from `agents/hebe/`, run `./gradlew test shadowJar` once and record (a) test count, (b) the produced shadowJar path/name, (c) the detekt findings count. This is the regression target — the merged build must reproduce all three.

## Tasks

- [x] **T1 — Catalog conflict log + merge `libs.versions.toml`.**

  Produce `docs/implementation/v1/hebe/notes/s1.1-catalog-conflicts.md` first: a table of every version key present in **both** `agents/hebe/gradle/libs.versions.toml` and `gradle/libs.versions.toml`, with columns `key | hebe pin | kantheon pin | winner | rationale`. Rule from architecture §4.2: **kantheon's pin wins** unless Hebe has a hard requirement, in which case kantheon bumps (record as an exception and flag for Bora).

  Known keys to reconcile: `kotlin`, `ktor`, `coroutines` (Hebe `1.10.2`), `serialization` (Hebe `1.11.0`), `koog` (Hebe `0.8.0`), `kotest` (Hebe `6.1.2`), `detekt` (Hebe `1.23.8`), `flyway` (Hebe `11.20.3`), `mcp` (Hebe `0.12.0`), `otel` (Hebe `1.58.0`), `junit`, `mockk`, `ktlint`, `shadow`.

  Then merge Hebe-only keys (`sqliteJdbc`, `sqliteVec`, `telegram`, `jgit`, `bouncycastle`, `pf4j`, `oras`, `tomlj`, `clikt`, `jline`, `logstashEncoder`, `datetime`, `kotlinx-io`) and their `[libraries]` entries into kantheon's `gradle/libs.versions.toml`. Do **not** duplicate keys that already exist in kantheon's catalog — point Hebe modules at the existing alias.

  Acceptance: conflict log committed; `gradle/libs.versions.toml` parses (`./gradlew help` resolves the catalog); no duplicate-key error.

- [x] **T2 — Register modules in root `settings.gradle.kts`; delete Hebe's build entry points.**

  Add every Hebe module to the **root** `settings.gradle.kts`:

  ```kotlin
  include(
      ":agents:hebe:modules:api",
      ":agents:hebe:modules:channels",
      ":agents:hebe:modules:cli-app",
      ":agents:hebe:modules:config",
      ":agents:hebe:modules:core",
      ":agents:hebe:modules:detekt-rules",
      ":agents:hebe:modules:gateway",
      ":agents:hebe:modules:mcp-server",
      ":agents:hebe:modules:memory",
      ":agents:hebe:modules:observability",
      ":agents:hebe:modules:plugin-api",
      ":agents:hebe:modules:plugins",
      ":agents:hebe:modules:providers",
      ":agents:hebe:modules:scheduler",
      ":agents:hebe:modules:security",
      ":agents:hebe:modules:tools:builtin",
      ":agents:hebe:modules:tools:dispatch",
      ":agents:hebe:modules:tools:mcp-client",
  )
  ```

  Verify the inventory count against `plugin-template` (is it a built module or a packaged resource? — record the answer in the conflict-log note; if it builds, add it). Then **delete** `agents/hebe/settings.gradle.kts`, `agents/hebe/gradlew`, `agents/hebe/gradlew.bat`, `agents/hebe/gradle/wrapper/`, `agents/hebe/gradle.properties`, `agents/hebe/build.gradle.kts` (the standalone root build script).

  Acceptance: `./gradlew projects` from the kantheon root lists all `:agents:hebe:modules:*` paths; `agents/hebe/gradlew` no longer exists.

- [x] **T3 — Port the build-logic conventions + shadowJar packaging.**

  Replace Hebe's `agents/hebe/build-logic/` convention plugins with kantheon's build-convention plugins (the same ones every kantheon Kotlin module already applies — see any existing `services/*/build.gradle.kts`). Rewrite each Hebe module `build.gradle.kts` to apply the kantheon convention plugin + reference the merged catalog aliases (`libs.koog.agents`, `libs.ktor.server.core`, `libs.sqlite.jdbc`, etc.).

  The **shadowJar packaging** for `cli-app` (the local binary, entrypoint `hebe run`) has no kantheon equivalent — port it into `agents/hebe/modules/cli-app/build.gradle.kts` (apply `id("com.gradleup.shadow")` via `libs.versions.shadow`, configure `mainClass`, the `hebe.sh`/`hebe` launcher reference). Keep the produced artifact name stable so `hebe.sh` still finds it.

  Then delete `agents/hebe/build-logic/`.

  Acceptance: `./gradlew :agents:hebe:modules:cli-app:shadowJar` from the root produces the same artifact recorded in the pre-flight baseline; `agents/hebe/build-logic/` gone.

- [x] **T4 — Wire `detekt-rules` (mutation-funnel guard) into kantheon's lint pipeline.**

  `:agents:hebe:modules:detekt-rules` is Hebe's most valuable invariant — the custom detekt rule asserting **every state change flows through `ToolDispatcher.dispatch`** (architecture §4.2, §4.3). Keep it. Wire it into kantheon's detekt config so `just lint-all` (root) runs it against the Hebe modules with identical rule coverage. Confirm by counting rule activations against the pre-flight baseline (T-pre): the merged lint run must report the **same** mutation-funnel findings (ideally zero) as standalone.

  Acceptance: `just lint-all` at the root runs the mutation-funnel rule over Hebe sources; finding count matches baseline.

- [x] **T5 — Fold Hebe `justfile` recipes into the root `justfile`.**

  Add to the root `justfile` (mirroring the existing `build-kt`/`test-kt`/`deploy-kt` recipe shapes):

  - `build hebe` → `:agents:hebe:modules:cli-app:shadowJar`
  - `test hebe` → run all `:agents:hebe:modules:*` Kotest suites
  - `hebe-run-local` → run the `cli-app` shadowJar in `local` profile (`./agents/hebe/hebe.sh run` or equivalent)

  Delete `agents/hebe/justfile` and `agents/hebe/justfile.sample`. Cross-check that no recipe references a path under the deleted `agents/hebe/gradlew`.

  Acceptance: `just test hebe`, `just build hebe`, `just hebe-run-local` all resolve and run from the kantheon root.

- [x] **T6 — CI pickup + full green pipeline.**

  Confirm kantheon's `.github/workflows/ci.yml` `init → lint-check → test-all` auto-detects the new modules (it iterates Gradle subprojects). If Hebe's `cli-app` needs Jib for the k8s image, that is **Phase 3** — do not add Jib here; shadowJar only. Delete `agents/hebe/.github/` (the standalone CI workflows) so there is one CI.

  Run the full pipeline locally: `just init && just lint && just test`. All Hebe Kotest suites green; test count ≥ the pre-flight baseline.

  Acceptance: full root pipeline green; PR opened `[hebe-p1-s1.1] gradle merge`.

## DONE — Stage 1.1

- [x] All six tasks checked.
- [x] `just init && just lint && just test` green at the kantheon root **with Hebe included**.
- [x] `agents/hebe/gradlew`, `agents/hebe/settings.gradle.kts`, `agents/hebe/build-logic/`, `agents/hebe/justfile*` all deleted.
- [x] `just build hebe` produces the shadowJar (artifact name unchanged from baseline).
- [x] Catalog conflict log committed under `notes/`.
- [x] PR merged.

## Library / pattern references

- **architecture.md §4.2** — the merge decision (no `includeBuild`; catalog merged; build-logic retires; detekt-rules survives).
- Any existing kantheon `services/*/build.gradle.kts` — canonical convention-plugin application + catalog-alias usage (e.g. `services/theseus/build.gradle.kts`).
- **Gradle Shadow plugin** `com.gradleup.shadow` (`libs.versions.shadow = 9.4.1`) — for the `cli-app` packaging.
- Root [`justfile`](../../../../justfile) recipes `build-kt`/`test-kt`/`deploy-kt` — the recipe shapes to mirror.

## Out of scope for Stage 1.1

- Package rename `com.hebe.*` → `org.tatrman.kantheon.hebe.*` (Stage 1.2).
- Jib image for the k8s profile (Phase 3 Stage 3.3).
- Any behaviour change to Hebe's runtime (`local` must behave byte-for-byte as before — verified by the unchanged test suite).
