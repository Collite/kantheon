# Themis eval harnesses

Two JSONL corpora, two Python harnesses. Both POST to a deployed Themis and score
the response against per-question expectations; both are stdlib-only (no PyYAML /
requests dependency).

| Harness | Corpus | Scores | Endpoint |
|---|---|---|---|
| [`run_eval.py`](run_eval.py) | [`corpus/seed.jsonl`](corpus/seed.jsonl) (50 NORMAL + 12 ENTITIES_ONLY) | function-call accuracy, entity-binding P/R, parse fidelity | MCP `/mcp/v1/tools/resolve` (NORMAL) + REST `/v1/resolve` (ENTITIES_ONLY) |
| [`run_routing_eval.py`](run_routing_eval.py) | [`corpus/routing-seed.jsonl`](corpus/routing-seed.jsonl) | intent-kind, chosen-agent, routing-layer-hit | REST `/v1/resolve` (`profile=CHAT_QUICK`) |

> **Why routing uses REST, not the MCP tool.** The MCP `resolve` tool is a reduced
> surface — it carries no profile/registry/hitl, so it sets `routingEnabled = false`
> (see `Main.kt`). Rich routing + refusal live only on REST `/v1/resolve`. The
> routing harness therefore drives REST and reads the proto `ResolveResponse`.

## Routing eval (Phase 3 Stage 3.5)

```bash
# Live, against a deployed Themis (port-forwards themis-mcp, writes JSONL + MD,
# enforces thresholds.yaml):
just eval-themis-routing

# Harness logic only — no cluster, no LLM (drives a local fake Themis over HTTP):
just eval-themis-routing-selftest
```

The harness:

1. Loads `routing-seed.jsonl`, attributing each question to the most recent
   `#`-comment **bucket header**.
2. POSTs each question to REST `/v1/resolve` with `profile=CHAT_QUICK`.
3. Parses the proto `ResolveResponse` (field-name tolerant: accepts proto
   snake_case *or* lowerCamelCase, and `chosen_agent_id` as a bare string *or* the
   nested `AgentId` `{value}` shape).
4. Scores `intent_kind`, `chosen_agent_id`, and `layer_hit` per question; the
   Layer-1 hit-rate denominator excludes ambiguous (expected-Layer-3) questions.
5. Emits a JSONL of per-question results and a Markdown report (aggregate +
   per-bucket + failed-question table).
6. Reads [`thresholds.yaml`](thresholds.yaml) and exits non-zero on any breach.

### Thresholds + tuning

[`thresholds.yaml`](thresholds.yaml) holds the plan §3.5 floors (routing 70 %,
Layer-1 60 %, intent-kind 85 %). After Bora's corpus fill + the first live run,
retune each to `max(floor, actual − 0.05)`. Layer-1 rule-weight tuning passes are
logged in [`tuning-2026-06.md`](tuning-2026-06.md).

### Corpus content (Bora-owned)

`routing-seed.jsonl` ships as a **6-line, one-per-bucket skeleton**. The
authoritative content fill — ~30 realistic Czech/English questions per bucket
(~180 total) — is a Bora-owned task (plan §8). The harness, recipes, thresholds,
and CI self-test are complete and run against whatever the corpus contains; the
≥60 % Layer-1 DONE gate is signed off once the populated corpus lands.

## Where the gates run (fork-era policy)

Per the ratified testing policy (CLAUDE.md §9; testing arch §2) **CI runs
unit + component only; the full-constellation integration tier runs nightly.**
The routing eval needs the forked NLP stack (Kadmos/Echo) + a real LLM
(Prometheus, for Layer 2) + capabilities-mcp fixtures — an integration-tier
concern. So, exactly as the Stage 2.4 corpus gate was **relocated** into the
integration track:

- **PR CI** (`ci.yml`) runs `eval-themis-routing-selftest` — the deterministic
  harness guard. No cluster, no LLM.
- **Live gate** runs as the nightly **`themis-routing`** integration context
  (`integration-nightly.yml`), mirroring `theseus-runquery`: olymp `infra-up
  themis-routing` brings up the stack, a `@RequiresContext("themis-routing")`
  spec gates readiness, then `just eval-themis-routing` runs the corpus. The
  olymp-side `test-contexts/themis-routing/context.yaml` + the kantheon
  `integrationTest` spec land with the **testing arc** when Themis routing
  reaches the cluster (plan §2.4 close; testing contracts §5.2 — "the run set …
  grows per plan.md as arcs reach the cluster"). Until then the recipe runs
  ad-hoc against any locally-deployed Themis.

> The original Stage 3.5 task doc (T6) proposed a Wiremock **replay gate inside
> `ci.yml`**. That predates the fork-era unit/component-only-in-CI policy; the
> self-test + nightly-relocation above supersede it, consistent with how Stage 2.4
> closed. See [`STAGE-2.4-DEFERRAL.md`](STAGE-2.4-DEFERRAL.md) (historical).

## Files

```
eval/
├── run_eval.py            # resolution-quality harness (Phase 2)
├── run_routing_eval.py    # routing harness (Phase 3 Stage 3.5)
├── selftest.py            # routing-harness self-test (fake Themis over HTTP)
├── thresholds.yaml        # routing CI gate floors
├── tuning-2026-06.md      # Layer-1 weight-tuning log
├── corpus/
│   ├── seed.jsonl                     # resolution-quality corpus
│   ├── ucetnictvi_entities_only.jsonl
│   └── routing-seed.jsonl             # routing corpus (skeleton; Bora fills)
└── results/               # generated JSONL + MD reports (git-ignored)
```
