# Phase 1 — Build & repo citizenship

> **Reads with.** [`plan.md`](./plan.md) §"Phase 1", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §4 (module + package alignment), [`../../../architecture/hebe/standalone-v1-architecture.md`](../../../architecture/hebe/standalone-v1-architecture.md) (kernel/plugin ABI, the 16 modules), [`../../planning-conventions.md`](../../planning-conventions.md), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md).
>
> **Phase deliverable (deployable).** One `just test` at the **kantheon root** builds and tests all Hebe modules; packages are `org.tatrman.kantheon.hebe.*`; the standalone artifacts (shadowJar + `hebe.sh`) still build. CI picks Hebe up automatically. Tag **`hebe/v0.1.0`**.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **1.1** — Gradle merge | `just init && just lint && just test` green at kantheon root with Hebe included; `agents/hebe/gradlew` gone; shadowJar still builds | [`tasks-p1-s1.1-gradle-merge.md`](./tasks-p1-s1.1-gradle-merge.md) |
| **1.2** — Package rename & hygiene | Zero `com.hebe` occurrences outside git history; full suite green; plugin-template smoke test passes against renamed `plugin-api`; tag `hebe/v0.1.0` | [`tasks-p1-s1.2-package-rename.md`](./tasks-p1-s1.2-package-rename.md) |

## Sequencing

Strictly sequential — the rename (1.2) rides on a working merged build (1.1).

```
Stage 1.1 ──► Stage 1.2
 gradle merge   package rename + tag v0.1.0
```

## Pre-flight for the phase

- [ ] Kantheon root build green on a clean checkout (`just init && just lint && just test`).
- [ ] Hebe source present at `agents/hebe/` (moved 2026-06-12; original `~/Dev/hebe` keeps git history).
- [ ] **Version-conflict inventory** between the two catalogs prepared as Stage 1.1 T1 (Kotlin, Ktor, coroutines, serialization, Koog, kotest, detekt, flyway). Known deltas to confirm against kantheon canon: Hebe pins `kotlin = 2.3.20`, `ktor = 3.2.3`, `koog = 0.8.0`, `kotest = 6.1.2`, `flyway = 11.20.3`, `mcp = 0.12.0`, Gradle 9 wrapper.
- [ ] Decide the kantheon canon for each conflicting key **before** touching code (this is the biggest unknown of the arc — risks note in `plan.md`).

## The 16 Hebe modules (under `agents/hebe/modules/`)

`api`, `channels`, `cli-app`, `config`, `core`, `detekt-rules`, `gateway`, `mcp-server`, `memory`, `observability`, `plugin-api`, `plugins`, `providers`, `scheduler`, `security`, `tools` (with submodules `tools/builtin`, `tools/dispatch`, `tools/mcp-client`). After the merge these become Gradle paths `:agents:hebe:modules:<name>` (e.g. `:agents:hebe:modules:tools:dispatch`).

> The plan refers to "21 modules" — that counts the `tools/*` submodules and the `plugin-template` build. The inventory in Stage 1.1 T2 is the authority; reconcile the count there.

## Aggregate progress

Mark each stage when DONE:

- [x] **Stage 1.1** — Gradle merge.
- [x] **Stage 1.2** — Package rename & hygiene.

When both boxes are checked, push tag `hebe/v0.1.0` and move to Phase 2.

## Up / across

- Up: [`./README.md`](./README.md) — Hebe implementation index.
- Phase neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`tasks-p4-overview.md`](./tasks-p4-overview.md).
