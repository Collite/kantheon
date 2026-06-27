# charon-mcp ToolCapability manifests

The five `move.*:v1` capabilities Charon's MCP wrapper advertises to
capabilities-mcp (charon/contracts.md §3). `category: "charon"`. The
`cost_hints` (`typical_latency_ms`) are **seeded** values; the authoritative
figures come from the `bench/` harness (Stage 3.2 T4) over the 1e5/1e6-row
reference sets — see `tools/charon-mcp/bench/README.md`. Pythia's planner reads
these for move-cost budget projection.
