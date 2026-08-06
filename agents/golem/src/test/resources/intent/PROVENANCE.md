# `routing-seed.jsonl` — vendored copy

Copied verbatim from **`agents/themis/eval/corpus/routing-seed.jsonl`** at commit
`8b904f84bb4f546d1147e7cdd3c1ec145d787f25` (the last commit to touch that file).

    sha256  75ade8bef0e66b17bc4f937000bd59753d203d832382c7ad138bdcab1d525616
    entries 10 (plus 12 comment lines)

## Why a copy and not a read across modules

RV-P5.3 T1(b) wants the no-op baseline pinned over "the existing routing-seed corpus
slice". Reading `../themis/eval/corpus/…` from a `:agents:golem` test would make golem's
suite fail on a themis-side edit — which is the opposite of a pin: the point is to notice
when the *seam* changes, not to inherit someone else's churn. Vendoring per Bora's
2026-08-06 ruling ("OK to copies"), with drift caught by `IntentBaselineGoldenSpec`'s
provenance check rather than by physical single-sourcing.

⚑ This corpus is a **skeleton** — the file's own header says so ("one seed per bucket
anchors the shape; Bora's content fill (~30/bucket, ~180 total) lands in parallel through
Stage 3.5"). Ten entries is enough to pin that the seam is a no-op on every intent kind
the vocabulary has, which is what T1(b) asks; it is not enough to be an eval. When the
fill lands, re-copy and update the two hashes above.
