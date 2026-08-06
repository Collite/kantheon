<!-- SPDX-License-Identifier: Apache-2.0 -->
# `lattice/` — vendored recorded-core fixtures (RV-P5.1 T2)

These eight files are **byte-for-byte copies** of `tatrman-server`'s own resolver goldens:

| | |
|---|---|
| source repo | `Collite/tatrman-server` |
| source path | `services/resolver/src/test/resources/lattice/` |
| source commit | `78c29b8` (last commit touching that directory; reachable from master `84ada4e` = `server-libs/v0.11.3-RELEASE`) |
| vendored | 2026-08-06, RV-P5.1 T2 |

Each case is a pair here: `<id>.lattice.json` is the whole `ResolutionState` the core emits,
`<id>.parse.json` is the `org.tatrman.nlp.v1.AnalyzeResponse` that produced it. (The third
member of the upstream triple, `<id>.case.json`, is the *estate* the resolver was configured
with — kantheon never configures the resolver, so it is deliberately not copied.)
`RecordedResolutionCore` stitches the two into the `ResolveResponse` a live door would return.

Four cases: `h1-cs` (the 0-LLM hero, gap-free) · `h1prime-cs` (one `G4_METHOD_MISS`) ·
`h2-cs` (`G1_UNBOUND` on the SUBJECT + `G3_UNATTRIBUTED` on a LOCATION hint) · `h5-cs` (three
`op:` bindings, one honest `G1_UNBOUND`). Read that repo's own `PROVENANCE.md` for how the
parses were captured and which single part of them is authored.

## ⚑ These are COPIES, and RV-28 says there should not be any

RV-28 is "one corpus, one core, N shells". golem-py could honour that literally — it lives in
`tatrman-server`, so it reads these very files off disk with no copy anywhere. The Kotlin shell
lives in a **different repo**, and none of the three ways out is free:

- **a published test-fixtures artifact** from `tatrman-server` (`server-proto-fixtures`, riding
  the same `server-libs` cut) — structurally correct, one source of truth, versioned with the
  proto it describes; costs a new publication and therefore a Bora cut;
- **a git submodule** — no precedent in kantheon, and it drags a whole repo in for 8 files;
- **vendoring** — what this directory is: cheap, works offline and in CI, and drifts silently.

The drift is the only real cost, so it is made loud rather than accepted:
`RecordedCoreProvenanceSpec` re-hashes every file here against the table below and, when
`TATRMAN_SERVER_DIR` points at a sibling checkout, diffs them against the originals. Without
that env var the cross-repo half **skips and says so** — a skip that prints nothing is how a
vendored fixture rots.

⚑ **`p5-4` T1 owns the ruling**, and the recommendation from here is the fixtures artifact: by
then the same question arrives again for `conformance/conversations/`, which RV-28 requires the
Kotlin Golem to pass *unchanged*, and one publication answers both.

## sha256, as vendored

```
841442995821f0bc2827506b2aabebd141d23df0f15c670235e9e244f07f0439  h1-cs.lattice.json
98b772feb540de8bd20e4842cc72816188c7f5bc4c52be84bcbc94874cef31f5  h1-cs.parse.json
229b92a1d3beef78123303c9c7bcfb55a85fba323afa9437f0c7881dcedcd7f2  h1prime-cs.lattice.json
141352712c6ec282c17add9beda0ab7f8c298ecef115063b28a94497959abb30  h1prime-cs.parse.json
9b8fba05a876232bac45345fbd89e59c9277a2879c4323a02d8601606f51fd4a  h2-cs.lattice.json
4164037a76a54e44ea2e1719ff469e05a68cd5e8bee87c235c05f519ee9c1508  h2-cs.parse.json
97348807f0e16aef8def0f33e61bb51d70ef2e625c66fb77a632ec639ad05586  h5-cs.lattice.json
7034fbd3fa02282f3fb0995139f6a72ad9b8268183266a3eba328d9abcc85c43  h5-cs.parse.json
```
