# ttr-translator arc — kantheon-side tracker (Phase B)

> Arc pointer: [`../../../architecture/fork/ttr-translator-extraction.md`](../../../architecture/fork/ttr-translator-extraction.md). Authoritative plan + contracts: **Collite/tatrman** `docs/ttr-translator/`. Tatrman-side Phase A (extraction + publish) must be DONE before B1 — the pre-flight in each list checks it.
>
> Coder protocol: work one list top-to-bottom; check `[x]` immediately after each task's verification passes; blocked ⇒ STOP + that list's §Blockers.

- [x] **B1 · Proto adoption** — [tasks-b1-proto-adoption.md](./tasks-b1-proto-adoption.md)
- [x] **B2 · Translator switch (Proteus + Ariadne + Argos) + lib deletion** — [tasks-b2-translator-switch.md](./tasks-b2-translator-switch.md)
- [x] **B3 · Docs, guards, tags** — [tasks-b3-docs-tags.md](./tasks-b3-docs-tags.md)

## Deviations from the original plan (executed 2026-07-07)

- **Version is `0.8.x`, not `0.1.0`.** tatrman versions the plan-proto/translator on the
  `kotlin-translator/v0.8.x` line (matching the ttr-metadata 0.8.x era). Consumed at **0.8.3**.
- **Three tatrman releases were needed, driven by kantheon's TPC-DS work (WS-T2):**
  - `0.8.1` — new **`plan.v1` Union op** (field 16). WS-T2's `channel_revenue_cte` (CTE + UNION ALL)
    couldn't be lowered — `plan.v1` had no set-op node. Added encoder/decoder + fan-out (below).
  - `0.8.2` — **PEP 420 namespace packages** for the Python wheel (`org` / `org.tatrman` had regular
    `__init__.py`, which shadowed shared-proto's `org.tatrman.*` — steropes couldn't import both).
  - `0.8.3` — pinned `grpcio-tools==1.81.1` so the wheel's **gencode stays on protobuf 6.33** (an
    unpinned build stamped 7.35.0, which kantheon's 6.33 runtime refuses to load).
- **Argos is a third `query-translator` consumer** (the plan didn't list it) — dep-swapped + rewritten.
- **Union fan-out (B2, unplanned):** adding the `Union` node to `plan.v1` broke every exhaustive
  `when(nodeCase)` across the in-repo plan consumers. Handled in **argos** (PlanWalker ×5, PolicyEngine,
  RuleEnforcer), **kyklop** (Routing ×3), **theseus** (PredictedFingerprintComputer) — each recurses
  into `union.inputs` mirroring its Join handling.
- **B1 T3 guard** is `:shared:proto:noTransferredProtoClasses` (wired into `check`): fails if the
  shared:proto jar ever bundles `plan/transdsl/dfdsl` classes again.
- **Python (B1 T5):** shared-proto's `PreparePythonPackage` now emits `org`/`org.tatrman` as namespace
  packages and depends on `ttr-plan-proto==0.8.3`; steropes imports both distributions, 56 tests green.

### Deferred (NOT part of Phase B) — `lint-all` repo debt

Phase B merged with **`test-all` green** and its own files lint-clean, but `just lint-all` is red on
**pre-existing** debt unrelated to the translator swap (master was already red):
- **ktlint:** the 406-sweep `buildJsonObject { put(x); put(y) }` one-liners across ~7 service
  `Application.kt`s + a batch of hebe specs/sources (from commit `9bc684a` and the hebe arc).
- **detekt:** hebe `cli-app` `AgentFactory.build` is a 226-line method (`LongMethod`, max 60).
- **The conflict:** `ktlintFormat` clears the ktlint one-liners but multilining `AgentFactory.kt`
  tips `build()` over the detekt limit — so a mechanical format can't get to green; it needs a
  **refactor** of `AgentFactory.build` (+ any other `LongMethod`/`ComplexMethod` behind it).
- **Owner action:** a dedicated `chore: clear lint-all debt` task — refactor the long hebe methods,
  then `ktlintFormat` the rest. Out of scope for the translator arc.
