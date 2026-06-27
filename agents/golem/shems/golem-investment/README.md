# Golem-investment Shem

The **Investment** Golem — the Q&A face of [Midas](../../../../docs/architecture/midas/). Answers
procedural questions about a client's investment portfolios (positions, valuation, performance,
transactions, dividends, fees, FX) over the kantheon-owned operational Postgres.

It is an **assembled Shem** (converged design 2026-06-25), not a hand-curated manifest. The boot
assembly merges four sources into one `capabilities/v1` `AgentCapability` (`agent_kind = AREA_QA`):

1. **ai-models agent definition** (`agents/investment.yaml`) — identity + `areas: [investment]`.
2. **Ariadne model** (by package) — `area_entities` (clients / portfolios / assets / transactions /
   `mv_position_current`), `preferred_queries` (the curated `q.midas.*` templates), and
   `area_terminology` (the investment glossary, authored as entity descriptions/aliases in ai-models).
3. **This overlay** (`shem.yaml`) — the per-agent residue kantheon owns: `visibility_roles`,
   the router seed, example questions, counter-examples, locale defaults.
4. **Golem-template constants** — `agent_kind`, `intent_kinds: [PROCEDURAL]`, `capability_refs`
   (theseus query/compile + render table/chart), `hitl_default`, the service endpoint.

The financial calc surface (TWR/MWR, cost-basis, fee allocation, reconciliation) lives in
**Midas-core** as `midas.*:v1` MCP tools — separate `ToolCapability` entries the Golem calls.

## Layout

```
golem-investment/
├── shem.yaml            # the thin overlay (this Shem's residue)
└── prompts/
    ├── en/{intent,free-sql,chip-topup}.yaml
    └── cs/{intent,free-sql,chip-topup}.yaml
```

## Deploy

Mounted into the Golem template image at `/etc/golem/shem` (Helm values select the `investment`
Shem; `golem.ariadne.host`/`golem.capabilities.url` wired per environment). Mirrors the
`golem-ucetnictvi` deploy. See `docs/implementation/v1/midas/tasks-p3-s3.1-shem-investment.md`.

> **Cross-repo content (Bora-owned).** The `investment` area-def + the five `q.midas.*` curated
> queries + the entity glossary are authored in `ai-models` (`model-ttr/areas.ttrm` + an
> `investment` package + `agents/investment.yaml`) and served by Ariadne `ResolveArea`/`ListQueries`/
> `GetModel`. Until that lands, the assembled bundle's model-derived fields resolve empty at runtime;
> the assembly contract itself is proven here against fixtures (`GolemInvestmentBundleSpec`).
