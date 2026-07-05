module: kallimachos-mcp
image: ghcr.io/boraperusic/kallimachos-mcp   # jib
ports: { http: 7262 }
needs:
  downstream: [ kallimachos, capabilities-mcp ]
wave: 5    # librarian — MCP edge over Kallimachos
# notes: base port was named `mcp` (standardized to `http`); base had no OTel (normalized, default
#        OFF) and readiness only (liveness added on /health, mirroring the MCP-wrapper idiom).
