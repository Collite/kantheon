<!-- SPDX-License-Identifier: Apache-2.0 -->
# `ladder/` — the open Golem's shipped config, vendored (RV-P5.2 T2)

| | |
|---|---|
| file | `golem-ladder-open.yaml` |
| source repo | `Collite/tatrman-server` |
| source path | `services/golem-py/config/golem-ladder.yaml` |
| source commit | `01a026a` ("RV-P4.1: golem-py — the RV-11 loop as a pydantic-graph") |
| sha256 | `a4f049c32ef5eca2efb4090de84f446a05a9ee2607cae8f77a38d508c1bf360a` |
| vendored | 2026-08-06 |

This is the **open** Golem's shipped default — the full ladder SHAPE with
`policy.*.rungs: []` (RV-27). It is here as a **conformance fixture**, not as kantheon's
config: `LadderConformanceSpec` loads it through the Kotlin loader and asserts the same
facts golem-py's `test_ladder_config.py` asserts about it, which is what makes "one
schema, two loaders" (P5.2 T2) a proven statement rather than an intended one.

kantheon's own default is `src/main/resources/golem-ladder.yaml` and it is deliberately
**different** — contracts §3's normative table, rungs on, because RV-27's zero-rung floor
is stated for *the open Golem* and this is the internal-full one. The two files existing
side by side is the point: the loader must read both.

Copies are ruled OK (Bora, 2026-08-06) — RV-28's "one corpus" is upheld here by
provenance plus a drift check, not by physical single-sourcing. `LadderConformanceSpec`
re-hashes this file against the table above, and diffs it against the original when
`TATRMAN_SERVER_DIR` points at a sibling checkout; without that env var the cross-repo
half **skips loudly**, because a skip that prints nothing is how a vendored fixture rots.
