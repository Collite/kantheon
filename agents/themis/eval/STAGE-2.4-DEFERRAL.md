# Stage 2.4 deferral note

> **SUPERSEDED (2026-06-20).** This note is historical. The deferral it describes (waiting on a deployable ai-platform Resolver/nlp/fuzzy/llm-gateway stack for a parity check) was overtaken by the fork: fork Stage 2.6 (2026-06-14) switched Themis onto the in-repo forked stack (Kadmos/Echo/Prometheus) and **relocated the eval no-regression gate into the integration track** (the `themis-routing` nightly context, testing Stage 3.1). Stage 2.4 closed by relocation at **`themis/v0.1.0` (2026-06-20)**; Phase 2 is done. The parity-vs-Resolver baseline (T2/T5) is no longer the gate — the comparand is being retired. See [`../../../docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md`](../../../docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md) (status banner) for the close. Original text retained below for history.

> **Status (2026-05-30):** Stage 2.4's eval-gate run is deferred until the ai-platform service stack is deployable locally. This PR lands the prep work; the eval gate itself blocks Phase 2 close.

## What Stage 2.4 needs

The plan ([`docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md`](../../../docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md)) is structured around a parity check:

- **T2 — Baseline.** Run the corpus against a deployed ai-platform `agents/resolver`, capture per-question pass/fail.
- **T4 — Candidate.** Run the same corpus against kantheon `themis-mcp`, capturing the same shape.
- **T5 — Diff.** Phase 2 closes iff `themis-mcp` green-rate ≥ Resolver baseline green-rate.

Both runs need a real LLM (jointInference is sonnet-class; filterRelevantSpans is haiku) plus the NLP and fuzzy services.

## What's actually deployable today

| Component | Local K3s? | Notes |
|---|---|---|
| kantheon `themis-mcp` | ✅ running | `kantheon/themis-mcp-…` pod; `/health` 200; `/ready` 200. |
| kantheon `capabilities-mcp` | ✅ running | Phase 1 deliverable. |
| ai-platform `agents/resolver` | ❌ | **No K8s manifests in `ai-platform/agents/resolver/k8s/`** — never deployed. The Stage 2.4 plan's T2 baseline cannot run. |
| ai-platform `tools/nlp-mcp` | ❌ | K8s manifests exist (`tools/nlp-mcp/k8s/`) but no pods running in the cluster. |
| ai-platform `tools/fuzzy-mcp` | ❌ | Likewise. |
| ai-platform `infra/llm-gateway` | ❌ | Spring Boot service; no K8s deploy in this cluster, no API key wired. |

## What landed in this PR (Stage 2.4 prep)

The eval gate itself is parked, but everything around it is now ready:

1. **`run_eval.py` retargeted to Themis.** Default port `7171` → `7901`; `Resolver` → `Themis` in labels + the `call_themis` helper. Wire shape (REST `/v1/resolve` for ENTITIES_ONLY; MCP `/mcp/v1/tools/resolve` for NORMAL; response field names) preserved verbatim so the same harness runs against a future ai-platform-Resolver baseline if needed.

2. **`application.conf` env-substitution rewrite.** Every host/port/timeout in the `themis { nlp / fuzzy / llm-gateway }` blocks now carries a `${?NLP_MCP_HOST}` / `${?NLP_MCP_PORT}` / `${?NLP_MCP_TIMEOUT_MS}` (and equivalents) substitution. Defaults stay the same; env vars override when set. The themis-mcp pod's logs now print the env-resolved hostname (verified: `NLP endpoint: nlp-service.ai-platform.svc.cluster.local:8000`) rather than the bare config default.

3. **K8s manifests split `*_URL` into `*_HOST` + `*_PORT`.** Base deployment and local overlay both expose the pair (HOST/PORT) the HOCON substitutions consume, plus the legacy `*_URL` alias for tooling that doesn't speak HOCON. Verified live on Rancher Desktop K3s after `kubectl apply -k`.

4. **README rewritten.** Replaces the carried-over ai-platform Resolver content with kantheon Themis docs: API surface, env contract, deploy/eval recipes, related-docs index. Stage 2.4 deferral pointer at the top.

## What's needed to unblock Stage 2.4

Roughly in dependency order:

1. **Local-K3s deploys for `nlp-mcp`, `fuzzy-mcp`, `llm-gateway`** under the `ai-platform` namespace. `nlp-mcp` and `fuzzy-mcp` already have base K8s manifests; need a local overlay. `llm-gateway` is a Spring Boot service that needs containerisation + a configured LLM provider (OpenAI/Anthropic API key).
2. **An LLM API key for `llm-gateway`.** Without real LLM access the joint-inference and filter calls return stubs (the silent-fallback path), so every NORMAL-mode corpus question fails.
3. **ai-platform `agents/resolver` K8s manifests + deploy.** Needed for the T2 baseline run. The Resolver code lives in `ai-platform/agents/resolver/` but `k8s/` is empty. Either write manifests (~30 LOC, mirror kantheon's `themis-mcp` overlay) or accept "no baseline" and treat the Phase 2 gate as "themis-mcp resolves the corpus without errors" rather than "parity vs Resolver".

Once those land, the Stage 2.4 plan T2 / T3 / T4 / T5 / T6 sequence runs as written, and `themis/v0.1.0` ships.

## Quick check the eval harness wire shape

The harness POSTs against `themis-mcp` with the same proto/MCP shape ai-platform's Resolver baseline accepts. Dry-run against the currently-deployed (but upstream-blind) `themis-mcp`:

```bash
kubectl -n kantheon port-forward svc/themis-mcp 7901:7901 &
python3 agents/themis/eval/run_eval.py --host localhost --port 7901 --verbose
```

Expect every question to error out at the NLP-call boundary (`Failed to call NLP service`) since `nlp-service.ai-platform.svc.cluster.local:8000` is unreachable. This proves the harness wiring; the actual eval needs the upstream stack.
