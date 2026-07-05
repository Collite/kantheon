module: metis-mcp
image: ghcr.io/boraperusic/metis-mcp   # jib
ports: { http: 7262 }
needs:
  downstream: [ metis ]
wave: 2    # query-path — MCP edge over the model-estimation service
# notes: base had no resources (added MCP-wrapper defaults) and no OTel (normalized, default OFF);
#        base readiness had no failureThreshold (added 30). Probes use /health/ready + /health/live.
