# Stage 1.1 — `componentTest` source set + wiring

> **Phase 1, Stage 1.1.** Foundational — no service code under test yet, just the tier plumbing.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §"Phase 1", [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §2 (the ladder) + §2.1 (the ratified rename), [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §4.1 (tags/tasks), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Status.** ✅ Done 2026-06-20 (Bora / Claude). Tasks below track **plan.md's** T1–T6 (the authoritative breakdown), plus the already-landed vocabulary canon edit (T7). The earlier sketch in this file proposed a manual `sourceSets.create` + a `componenttest` bundle + the Kotest Testcontainers extension; the executed implementation follows plan.md's *"JVM test suite plugin"* directive instead — see **Divergences** below.

## Goal

A `componentTest` source set exists, runs Kotest against Testcontainers, and is wired into `ci.yml` so it gates every PR + merge — **without** disturbing the mocked-only `test` gate.

## Tasks (plan.md T1–T6)

- [x] **T1 — `componentTest` source set + task in the convention build.**
  Registered with the **JVM Test Suite plugin** in the root `build.gradle.kts`
  `subprojects { plugins.withId("org.jetbrains.kotlin.jvm") { … } }` block (the
  repo's convention mechanism — there is no `buildSrc`). Every Kotlin module now
  gets a `componentTest` source set (`src/componentTest/kotlin`) + a
  `componentTest` task. Suite deps: `kotest` (expanded to members — the suite
  `dependencies {}` DependencyCollector takes single-dependency providers, not
  bundle providers), `testcontainers`, `project()`. The task is plain
  `useJUnitPlatform()` with **no** `includeTags` (see Divergences) and
  `shouldRunAfter("test")`.

- [x] **T2 — `just test-component [<module>]` recipe.**
  Mirrors `test-kt`/`test-all` resolution via `_resolve`. No arg → aggregate
  `./gradlew componentTest`; with a module → `:<path>:componentTest`.

- [x] **T3 — `SmokeComponentSpec`.**
  `tools/_smoke-test/src/componentTest/kotlin/org/tatrman/kantheon/smoke/SmokeComponentSpec.kt`
  boots an `alpine:3.20` `GenericContainer` and asserts it is running. Green
  locally (5.2 s container boot). *(Lives in `_smoke-test`, not `charon` — it is a
  throwaway plumbing proof; `charon`'s real spec lands in Stage 1.2.)*

- [x] **T4 — `ci.yml` `test-component` step.**
  Added after `test` on the same `ubuntu-latest` runner (Docker present).

- [x] **T5 — isolation regression guard.**
  Source-set separation keeps component specs out of `test`. On top, the
  convention adds a `doFirst` guard on every module's `test` task that **fails**
  if any `componentTest` output leaks onto the unit `test` runtime classpath.
  Verified both ways: fires on a deliberate leak, passes otherwise; full
  `test-all` (152 tasks) stays green.

- [x] **T6 — Python mirror.**
  `component` pytest marker registered in `services/metis`, `services/kadmos`,
  `workers/steropes` `pyproject.toml`. `just test-py` now forwards trailing args,
  so `just test-py <m> -m component` selects the tier. testcontainers-python
  usage documented below.

- [x] **T7 — Vocabulary canon (ratified 2026-06-19; applied ahead of execution).**
  Verified present + consistent: AGENTS.md §1.4 + §8 (component = real-dep
  Testcontainers, integration = cluster) and CLAUDE.md §9 vocabulary row.

## DONE criteria (plan.md)

- [x] `just test-component` green (smoke spec boots a container).
- [x] `test-all` collection unchanged — still mocked-only; isolation guard active.
- [x] `ci.yml` runs both `test-all` and `test-component`.

## Divergences from the pre-execution sketch (rules-first, surfaced)

1. **JVM Test Suite plugin, not manual `sourceSets.create`.** plan.md T1 says
   *"JVM test suite plugin"*; the executed convention uses
   `testing { suites.register("componentTest", JvmTestSuite::class) }`. Same
   outcome (separate source set + task), idiomatic modern Gradle.
2. **`@Tags("component")` (Kotest), not `@Tag("component")` (JUnit Platform).**
   Kotest ships its own JUnit-Platform engine and its **own** tagging
   (`io.kotest.core.annotation.Tags`); JUnit-Platform `@Tag` / `includeTags`
   do **not** apply to Kotest specs. So the `componentTest` task must **not**
   filter by `includeTags("component")` — it would match nothing and silently
   skip every spec. The marker is `@Tags("component")`; the real isolation is
   the source-set split + the T5 classpath guard.
3. **DB-specific Testcontainers catalog entries deferred to Stage 1.2.**
   `testcontainers-postgresql` / `-mssqlserver` and any Northwind seed are only
   needed by the real-dep specs (`CharonPostgresComponentSpec`,
   `BrontesMssqlComponentSpec`) — they land with Stage 1.2, not here. Stage 1.1
   uses only the base `org.testcontainers:testcontainers` (`GenericContainer`).
4. **No `kotest-extensions-testcontainers` yet.** The smoke spec drives the
   container lifecycle manually (`start()`/`stop()`). Adopt the extension in
   Stage 1.2 if the real specs want managed lifecycle.

## testcontainers-python usage (T6 doc)

The Python lane mirrors the Kotlin component tier. Real-dependency specs use
[`testcontainers-python`](https://testcontainers-python.readthedocs.io/) and are
selected by the `component` marker (registered per-module in `pyproject.toml`):

```python
import pytest
from testcontainers.postgres import PostgresContainer

@pytest.mark.component
def test_against_real_postgres():
    with PostgresContainer("postgres:16") as pg:
        dsn = pg.get_connection_url()
        # … exercise the service against the real DB …
```

Run lanes:

```sh
just test-py services/metis                     # everything
just test-py services/metis -m component        # real-dep component tier only
just test-py services/metis -m "not component"  # mocked unit tier only
```

`testcontainers` is added to a module's dev dependencies (e.g. the
`testcontainers[postgres]` extra) when that module lands its first real-dep spec
(Stage 1.2+). Stage 1.1 only establishes the marker + recipe so the lane is
ready. Like Kotlin, the Python component tier needs a running Docker daemon and
is **not** part of the mocked `just test-py` default.
