module: charon-mcp
image: ghcr.io/boraperusic/charon-mcp   # jib
ports: { http: 7252 }
needs:
  downstream: [ charon, capabilities-mcp ]
wave: 2    # query-path — MCP edge over the Arrow data mover
# notes: base had no resources (added MCP-wrapper defaults) and no OTel (normalized, default OFF);
#        base readiness had no failureThreshold (added 30). strategy: Recreate not carried over.
