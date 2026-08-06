<!-- SPDX-License-Identifier: Apache-2.0 -->
# `conversations/` — the SHARED conformance corpus, vendored (RV-P5.4 T1)

**Byte-for-byte copies, no edits.** These five files are the corpus RV-28 means by *"one
corpus, one core, N shells"*: the same fixtures pass through the Python OS Golem
(`services/golem-py/tests/test_conformance_conversations.py`) and the Kotlin platform Golem
(`ConformanceConversationsSpec`), unchanged.

| | |
|---|---|
| source repo | `Collite/tatrman-server` |
| source path | `conformance/conversations/` |
| source commit | `eea5ae1` (last commit touching that directory; reachable from master `84ada4e`) |
| vendored | 2026-08-06, RV-P5.4 T1 |
| schema | `conformance/conversations/SCHEMA.md` in that repo — read it before adding a key |

## Why a copy, when RV-28 says there should not be one

✅ **Ruled by Bora 2026-08-06: COPIES ARE OK — vendor, do not build a fixtures artifact.**
The three candidate answers (a published `server-proto-fixtures` artifact riding the
`server-libs` cut · a git submodule · vendoring) are set out in `../lattice/PROVENANCE.md`,
which raised the same question one stage earlier and deferred the ruling here. RV-28 is
therefore upheld by **provenance + a drift check**, not by physical single-sourcing.

The cost of a copy is silent drift, so it is made loud twice:

1. `ConformanceConversationsSpec` re-hashes every file here against the table below. That
   proves the bytes have not changed **HERE**.
2. The same spec diffs them against the originals when `TATRMAN_SERVER_DIR` points at a
   sibling checkout, and **prints a loud SKIP when it does not** — a skip that says nothing
   is how a vendored fixture rots. That is the half that proves they still match **THERE**,
   and it is the reason `eval-nightly.yml` exists: nothing in *this* repo changes when the
   corpus moves upstream, so there is no PR for a gate to catch it on.

## sha256, as vendored

```
e911b4f97bd42cff1be9f442106a21a133bf3af74a8565cafa75dd19c9999810  h1-answer.json
845c5fd891c6161dbcb7bb86388303a3ee0658c252f90d87fc2a275b5d935c15  h1prime-regate.json
b60cdb88d3c33ac5f038118642b84a32270f438e3017ca36d13c4c23adda0354  h2-ask-pin-resume.json
215928615eff0de91818cf0b9b3dcde7d12d19e84760e8a870c6675912196918  h4-refusal.json
3b0eb8b31502e2cc881fc284bd4220a84253f654bf80f84d0249cf491eea624f  h5-answer-with-gap.json
```

These are the same hashes `conformance/corpus-hashes.sha256` pins upstream, so a drift is
detectable from either side.

## What a fixture may NOT do here either

SCHEMA.md's rule, restated because it is the one that keeps a shared corpus honest: **a runner
must reject a fixture that states an `expect:` key it does not assert.** A corpus that asserts
less than it says is worse than a smaller one, because the second shell reads the file and
believes it. SCHEMA.md says in as many words that *"the Kotlin shell needs its own"* guard —
it is `no fixture states an expectation the runner never reads`.

⚑ The lattices are **named by golden id**, never inlined (`"lattice": "h1-cs"`). Those goldens
are vendored separately under `../lattice/` and carry their own provenance.
