# metis-mcp tool manifests

Seven `ToolCapability` manifests registered with `capabilities-mcp`:
`model.fit`, `model.diagnose`, `model.project`, `model.simulate`,
`data.import`, `data.export`, `data.drop`.

## cost_hints provenance (Stage 3.2 T4)

`typical_latency_ms` values are **bench-derived**, not guessed. Source:
`services/metis/bench/bench_fit.py` (single-host fit/project micro-benchmark
over 1e2 / 1e3 / 1e4-row series), plus MCP/transport headroom, rounded
conservatively upward. Indicative figures behind the current values:

| tool          | basis (compute, p95)                                   | hint (ms) |
|---------------|--------------------------------------------------------|-----------|
| model.fit     | ARIMA auto-order ~880 ms (slow path); explicit ≤182 ms @10k; Prophet ~80–400 ms | 1200 |
| model.project | ~1.5 ms compute + workspace write                      | 80 |
| model.diagnose| Ljung-Box + ADF + Jarque-Bera, single-digit ms         | 100 |
| model.simulate| in-memory delta math                                   | 80 |
| data.import   | Arrow IPC parse + store (size-dependent)               | 150 |
| data.export   | Arrow IPC re-serialise (size-dependent)                | 120 |
| data.drop     | workspace dict op                                      | 30 |

Re-run `uv run python bench/bench_fit.py` after a dependency bump and update
these if the percentiles move materially.
