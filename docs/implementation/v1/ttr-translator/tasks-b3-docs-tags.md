# ttr-translator arc — Stage B3: Docs, guards, tags

> Branch: `feat/ttr-translator-b3-docs` (or fold into B2's PR). Pre-flight: **B2 DONE.** Tracker: [`tasks.md`](./tasks.md).
>
> Goal: the repo's self-description matches the new reality; the governance rule is written where future sessions will read it; release tags cut per convention.

## Tasks

- [ ] **T1 — CLAUDE.md sweep.**
  §3 repo-layout tree: remove `shared/libs/kotlin/query-translator`. §2 constellation table Proteus row: role text now "thin gRPC wrapper over `org.tatrman:ttr-translator`". §7.3 (Collite/tatrman standing deps): add `ttr-translator` + `ttr-plan-proto` to the artifact list, note the **proto ownership transfer** (plan.v1/transdsl.v1/dfdsl.v1 canonically tatrman-owned; FQCNs unchanged) and the **governance rule**: plan.v1 changes = PR to Collite/tatrman `packages/kotlin/ttr-plan-proto` → prompt lockstep `kotlin-translator/v*` release → bump `tatrman-translator` here.
  *Verify:* `grep -n "query-translator" CLAUDE.md` → only the historical/fork-provenance mentions you deliberately kept.

- [ ] **T2 — Architecture docs.**
  `docs/architecture/kantheon-architecture.md` Proteus paragraph (~line 66): "consumes the Collite/modeler TTR toolchain" → realized wording ("consumes `org.tatrman:ttr-translator` from Collite/tatrman"). `docs/architecture/fork/ttr-translator-extraction.md`: status → executed, with dates + versions. `docs/architecture/fork/ttr-metadata-adoption.md`: the "(QueryParseWorker — its query-translator dependency belongs to the ttr-translator arc)" parenthetical → mark resolved by this arc. `services/ariadne/README.md` line ~15 same treatment.
  *Verify:* `grep -rn "query-translator" docs/ services/*/README.md` → every remaining hit is historical narrative, none describes present-tense structure.

- [ ] **T3 — Version-bump discipline note.**
  `gradle/libs.versions.toml`: comment above `tatrman-translator` mirroring the existing `tatrman-modeler` comment style (where it comes from, tag prefix, who bumps it).
  *Verify:* toml comment present; `./gradlew help` green.

- [ ] **T4 — Tags + tracker.**
  Tag per service-release convention after deploy verification (`proteus/v<next>`, `ariadne/v<next>` if Ariadne's image changed). Check B3 + the arc header in [`tasks.md`](./tasks.md); notify the tatrman side (their `docs/ttr-translator/implementation/v1/tasks-overview.md` B-rows).
  *Verify:* tags pushed; both trackers consistent.

**DONE means:** docs grep-clean (T1/T2 verifications), governance rule recorded in CLAUDE.md §7.3, tags cut, both repos' trackers ticked. **The extraction arc is complete.**

## Blockers

_(empty)_
