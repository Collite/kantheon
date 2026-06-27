# Stage 1.2 — Package rename & hygiene

> **Phase 1, Stage 1.2.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §"Stage 1.2", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §4.1 (`com.hebe.*` → `org.tatrman.kantheon.hebe.*`), [`../../../../CLAUDE.md`](../../../../CLAUDE.md) §4 (proto packaging — Hebe takes the constellation root, not the service root).

## Goal

Every Hebe Kotlin source, test, resource reference, and doc moves from `com.hebe.*` to **`org.tatrman.kantheon.hebe.*`**. The full test suite stays green; the PF4J plugin system and `plugin-template` still load against the renamed `plugin-api`. After this stage: **zero `com.hebe` occurrences outside git history**, tag `hebe/v0.1.0`.

## Pre-flight

- [x] **Stage 1.1 DONE** — merged build green; rename rides on a working build.
- [x] **Branch**: `feat/hebe-p1-s1.2-package-rename` from `main`.
- [x] Baseline grep recorded: `rg -c 'com\.hebe' agents/hebe | wc -l` (file count) and total occurrences — the number must reach **0** in non-`.git` paths by DONE.
- [x] Caution flagged: PF4J resolves extensions by **classname string** (`META-INF/extensions.idx` / `@Extension`), so a blind find-replace can break plugin discovery silently — T3 handles this explicitly and T6 verifies it.

## Tasks

- [x] **T1 — Rename `main` sources + rewrite `package`/`import`.**

  Mechanical rename across all `:agents:hebe:modules:*` `src/main/kotlin/`:

  - Move directory trees `com/hebe/...` → `org/tatrman/kantheon/hebe/...`.
  - Rewrite every `package com.hebe...` → `package org.tatrman.kantheon.hebe...` and every `import com.hebe...` accordingly.

  Use an IDE "Rename Package" refactor where possible (it updates references atomically); fall back to scripted `sed` only for files the IDE misses, and re-run the build after. Do **not** touch test sources yet (T2) — keep the diff reviewable.

  Acceptance: `:agents:hebe:modules:*` main source sets compile (`just build hebe`); `rg 'com\.hebe' agents/hebe --glob '**/src/main/**'` returns nothing.

- [x] **T2 — Rename `test` sources; fix Kotest discovery.**

  Same move for every module's `src/test/kotlin/`. Then fix Kotest config: if any module pins test packages via `io.kotest.framework.config` / `AbstractProjectConfig` or a `kotest.properties` `packagePrefix`, update it to `org.tatrman.kantheon.hebe`. Check `META-INF/services/io.kotest.core.config.AbstractProjectConfig` resource files for the old FQCN.

  Acceptance: `just test hebe` green; the test **count** equals the Stage 1.1 baseline (no specs silently dropped by a stale discovery prefix).

- [x] **T3 — Resource-bound + reflective references (the dangerous ones).**

  Find-replace `com.hebe` in **non-Kotlin** files — these are not caught by the compiler:

  - PF4J: `META-INF/extensions.idx`, `@Extension` index files, `plugin.properties` (`plugin.class=...`), any `ServiceLoader` `META-INF/services/*` files.
  - Reflection / classname strings: `Class.forName("com.hebe...")`, `"com.hebe..."` literals used for dynamic loading, Koog provider class refs.
  - Logback: logger name prefixes / appender package filters in `logback.xml` (`<logger name="com.hebe...">`).
  - HOCON/TOML config keys that embed an FQCN.

  Grep the whole tree for the literal string `com.hebe` (not just imports): `rg -n 'com\.hebe' agents/hebe --glob '!**/*.kt'`. Fix each hit.

  Acceptance: `rg 'com\.hebe' agents/hebe --glob '!**/*.kt' --glob '!.git/**'` returns nothing; Hebe boots in `local` and loads its built-in plugins (manual `just hebe-run-local`, observe plugin registration in logs).

- [x] **T4 — `plugin-template` + plugin-api package.**

  The `plugin-template` is the scaffold third parties copy to write a Hebe plugin; it imports the `plugin-api`. Update the template's code (`package`/`import`), its `plugin.properties`, and any README/docs in `agents/hebe/plugin-template/` that name the old `com.hebe.plugin.api` package. The published plugin ABI is `org.tatrman.kantheon.hebe.plugin.api.*` after this.

  Acceptance: `plugin-template` compiles against the renamed `plugin-api`.

- [x] **T5 — Docs/manuals sweep.**

  Update `agents/hebe/docs/`, `agents/hebe/AGENTS.md`, `agents/hebe/CLAUDE.md`, `agents/hebe/HELP.md`, `agents/hebe/README.md`, and `agents/hebe/api-doc/` for the old package name and any retired build commands (`./gradlew` → `just ... hebe`). Keep historical M0–M10 records under `docs/implementation/v1/hebe/standalone/` factually intact (don't rewrite history), but add a one-line banner where a command no longer works.

  Acceptance: `rg 'com\.hebe' agents/hebe/docs agents/hebe/*.md` returns nothing.

- [x] **T6 — Full suite + built-plugin smoke test + tag.**

  - `just lint && just test` at the root — full green, mutation-funnel detekt rule still active.
  - **Plugin smoke test:** build the `plugin-template` into a loadable plugin artifact, drop it into a scratch Hebe instance's plugin dir, boot `local`, and assert it loads and its tool is dispatchable (this is the real PF4J-classname regression gate — a renamed `@Extension` index that still points at `com.hebe` would fail *here*, not at compile time).
  - Final grep gate: `rg 'com\.hebe' agents/hebe --glob '!.git/**'` → **0**.
  - Tag `hebe/v0.1.0`; bump the Hebe version key in `gradle/libs.versions.toml` if the catalog tracks it.

  Acceptance: all green; tag pushed; PR `[hebe-p1-s1.2] package rename → org.tatrman.kantheon.hebe`.

## DONE — Stage 1.2

- [x] All six tasks checked.
- [x] Zero `com.hebe` occurrences outside `.git`.
- [x] `just test` green at root; test count = Stage 1.1 baseline.
- [x] `plugin-template` smoke test passes against the renamed `plugin-api`.
- [x] Tag `hebe/v0.1.0` pushed.
- [x] PR merged. **Phase 1 DONE.**

## Library / pattern references

- **architecture.md §4.1** — package decision; **CLAUDE.md §4** — Hebe is an agent, takes the constellation proto root `org.tatrman.kantheon.hebe`, not the `org.tatrman.<service>` service root.
- **PF4J docs** (`libs.versions.pf4j = 3.15.0`) — `@Extension` indexing + `plugin.properties` `plugin.class`; the classname-string trap.
- IDE "Rename Package" refactor — prefer over `sed` for reference-safe renames.

## Out of scope for Stage 1.2

- Proto package work — `hebe.v1` protos do not land until **Phase 4** (contracts §1.2); nothing proto-shaped to rename here.
- Any axis/profile work (Phase 2).
