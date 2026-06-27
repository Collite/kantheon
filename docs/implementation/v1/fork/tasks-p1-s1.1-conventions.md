# Fork — Stage 1.1: Fork point & conventions

> Branch: `feat/fork-p1-s1.1-conventions`. Pre-flight: none. Plan: [`plan.md`](./plan.md) Phase 1. Tracker: [`tasks.md`](./tasks.md).
>
> Goal: pin the fork point in ai-platform, settle the repo conventions every later stage assumes (provenance, `workers/`, Python lane, ports), without forking any code yet.
>
> **Fork-point SHA (T1, 2026-06-12):** `2575b923dca521fea0e3156257e4b779f02a6ed4` on ai-platform `main`, tag `kantheon-fork-point`. Recorded in [`../../architecture/fork/architecture.md`](../../architecture/fork/architecture.md) §1.

- [x] **T1 — Tag the fork point in ai-platform.**
  In `/Users/bora/Dev/ai-platform` (read-only otherwise — **no code changes in that repo, ever, under this arc**): `git tag kantheon-fork-point && git push origin kantheon-fork-point` on current `main` HEAD. Record the resulting SHA in `docs/architecture/fork/architecture.md` §1 (replace the `<sha>` placeholder sentence with the literal SHA) and in this file's header. Every later "fork module X" task copies from exactly this tag (`git -C ~/Dev/ai-platform worktree add /tmp/aip-fork kantheon-fork-point` is the recommended copy source — keeps the working tree clean).
  *Done 2026-06-12: SHA `2575b923dca521fea0e3156257e4b779f02a6ed4`, tag pushed.*

- [x] **T2 — Provenance convention.**
  Create `docs/implementation/v1/fork/provenance-template.md` containing the exact README header block every forked module must carry:
  ```markdown
  > **forked-from:** `ai-platform@<sha>` (`<original path>`), tag `kantheon-fork-point`, forked <YYYY-MM-DD>.
  > Maintained independently since the fork; do not assume parity with the ai-platform original.
  ```
  Add one sentence to `AGENTS.md` (new "Forked modules" subsection) pointing at the template and stating the rule: *every* module under `services/`, `workers/`, `tools/`, `shared/libs/` that originates in ai-platform carries this header.
  *Done 2026-06-12: [`provenance-template.md`](./provenance-template.md) created; `AGENTS.md` §12.1 added.*

- [x] **T3 — Test first: build accepts the `workers/` tree.**
  Add a placeholder module `workers/_smoke-worker` mirroring `tools/_smoke-test`'s minimal shape (copy its `build.gradle.kts` plugins block — kantheon convention is alias(libs.plugins.*), no convention plugins). Write the assertion first: extend the existing CI/build smoke (or add a `workers/_smoke-worker/src/test/kotlin/.../SmokeSpec.kt` Kotest StringSpec with a trivial passing spec) so `./gradlew :workers:_smoke-worker:test` fails before the module exists and passes after. Then add `include(":workers:_smoke-worker")` to `settings.gradle.kts` under a new `// workers/ — the Kyklops (fork Phase 3)` comment block.
  *Done 2026-06-12: settings.gradle.kts include + test file + build.gradle.kts + main stub; `./gradlew :workers:_smoke-worker:test` green.*

- [x] **T4 — Python lane conventions (joint with Metis Phase 1).**
  Check whether Metis Phase 1 has landed (`services/metis` exists? its plan checked off?). If yes: adopt its conventions verbatim and only *document* them here. If no (expected): settle them now, in `AGENTS.md` (new "Python modules" section): `uv` for deps (`pyproject.toml` + `uv.lock`), proto imports from the generated shared-proto package (never `src/`), `pytest` + `ruff` + `mypy --strict`, Dockerfile (uv multi-stage; Jib is JVM-only), and three `justfile` recipes copied/adapted from `ai-platform/justfile`: `py-sync-all`, `test-py <service>`, `lint-py <service>` (read the ai-platform recipe bodies; adapt paths, don't invent). Note in `docs/architecture/metis/architecture.md` §2 that the lane conventions are settled by fork Stage 1.1 and Metis inherits them (amends its "only Python module" framing per fork contracts §6).
  *Done 2026-06-12: Metis not yet in repo. AGENTS.md §4.1 added; justfile gains `proto-py`/`py-sync-all`/`test-py`/`lint-py`; metis/architecture.md amended per fork contracts §6.*

- [x] **T5 — Port & namespace reservations.**
  Charon reserved 7250–7252. Continue the block: assign and record in `docs/architecture/fork/contracts.md` §7 a table — Ariadne 7260/7261 (+ ariadne-mcp 7262), Echo 7265/7266 (+7267), Kadmos 7270 (HTTP) (+kadmos-mcp 7272), Proteus 7275/7276, Prometheus 7280, Argos 7285/7286, Kyklop 7290/7291, Brontes 7295/7296, Steropes 7300/7301, Theseus 7305/7306 (+theseus-mcp 7307). Pattern per service: HTTP probes / gRPC (/ MCP). All pods land in the existing kantheon namespace (same as capabilities-mcp).
  *Done 2026-06-12: table written to `contracts.md` §7.1 (Charon+Metis rows preserved; 10 forked personas in the 7260–7307 block; reserved gaps noted; one-namespace rule reaffirmed).*

- [x] **T6 — Stage-exit checklist + tracker update.**
  Verify: tag pushed (T1), template exists and AGENTS.md references it (T2), `./gradlew projects` lists `:workers:_smoke-worker` and its test passes (T3), AGENTS.md Python section + just recipes exist and `just --list` shows them (T4), contracts §7 table filled (T5). Run `just lint-all`. Check the Stage 1.1 row in [`tasks.md`](./tasks.md).
  *Done 2026-06-13: all five T1–T5 verification points pass on the current commit. `just lint-all` fails on the inherited themis baseline (pre-existing; unchanged by this stage) — see AGENTS.md §12 "tests green ≠ done"; the stage DoD items themselves all pass.*

**DONE means:** conventions documented; fork-point tag pushed and SHA recorded; build green including the workers placeholder; no forked code yet.
