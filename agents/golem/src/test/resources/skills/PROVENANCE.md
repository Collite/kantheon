# `operator-library.json` — vendored copy

Copied from **`tatrman-server:services/golem-py/tests/fixtures/lexicon/operator-library.json`**
at commit `8bf6da805f343030bd875aa2f19f1a1922c0ab47`, with **one edit** (below).

    source sha256  8a6c4411285f292df6fef2eeb6f3207feb271a2d945056cc5bb53b787976d65b
    this file      2b6b87a9fa12bbf510a1b81d9c1079c77b01dfe47d38fbb13dfb361edcbaf107
    schema         ttr-operator-library/v1
    operators      op:show · op:trend · op:top-n · op:share-of · op:compare

## ⚑ The one edit: `source.layer` removed, because the artifact type rejects it

The source fixture writes `"source": {"file": …, "line": …, "layer": "STDLIB"}`. There is no
`layer`: `org.tatrman.ttr.lexicon.EntryProvenance` is `{file, line}`, the compiler has nothing
to emit into a third field, and `OperatorLibrary.fromJson` is **strict** (`ignoreUnknownKeys =
false`) — so the published artifact type refuses the file outright.

golem-py never noticed because its loader reads the dict leniently
(`(entry.get("source") or {}).get("file", "")`), so the invented key sails through. The two
shells therefore disagree about what a *valid artifact* is, and only the Kotlin one is
checking against the producer's real schema.

**Carried to P5.4:** the upstream fixture should drop the key too, or `EntryProvenance` should
gain the field if a layer tag is genuinely wanted in the artifact. Until one of those happens,
this copy is deliberately not byte-identical to its source, and `SkillLibrarySpec` has a test
named after the discrepancy so it cannot be quietly "fixed" by re-copying.

## RV-28 "one corpus, N shells", upheld by provenance rather than by a symlink

Per Bora's 2026-08-06 ruling ("OK to copies"). The two Golems live in different repos with
different build systems, so a physically shared file would mean a git submodule or a
published test-fixture artifact — both heavier than the drift they prevent.

What keeps the copy honest is `SkillLibrarySpec`'s hash check plus this file: a drift shows up
as a **loud test failure naming the source**, not as two suites quietly agreeing on different
bytes. Re-copy and update the hash when the source moves; P5.4's conformance tier is where
the two shells are made to answer identically over it.

## What this fixture does and does not exercise

`op:trend` (`time-grain`) and `op:compare` (`two-series`) carry `Applicability:` lines, so the
`requires:` path is live. `op:top-n` does **not** carry one here even though the real stdlib
body declares `order-measure` — so the `order-measure` and `parent-context` predicates are
covered by hand-built layers in the spec rather than by this file. Recorded because "the
fixture has five operators" reads like "five operators are covered" and it is not the same
statement.
