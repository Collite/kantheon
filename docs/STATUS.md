---
effort: kantheon (runtime constellation)
repo_home: kantheon
code_home: [kantheon]
state: executing
phase: deploy-test PROGRAM DONE 2026-07-09 · SV read-spine extraction landed · ttr-translator Phase B in progress · ttr-metadata publish gate open
next: finish translator B1–B3 (delete 73-file vendored copy); unpin ttr-metadata once kotlin-metadata/v0.1.0 is published; fold/close the unmerged sv-p1-* branches (Bora)
blocked_on: ["kotlin-metadata/v0.1.0 publish (tatrman, Bora tag)", "Bora: fold sv-p1-* + fix/docs branches", "Themis P3: Bora routing corpus (~180 Q, ≥60% L1) → v0.2.0 tag"]
gates: ["kantheon+ai-platform CI-reproducibility (metadata pin)"]
updated: 2026-07-13
---
⚠ `implementation/v1/master-plan.md` is stale (bannered) — current truth = this file +
`architecture/fork/extraction-inventory-260710.md` + `implementation/v1/deploy-test/`.
Parked/gated: Midas P3 (gated M3/live Iris — the only unbuilt arc), Golem live cutover (M3 +
Bora soak corpus), hartland TPC-DS demo (design done, Bora go/no-go — competes with the gateway
for Lane 3), grounding/resolver stacks (superseded in direction by RG; Q-21 Resolving Agent
rides SV-P5 pre-flight). SV-P5 (Nov) generates the kantheon-side cutover lists at phase start.
