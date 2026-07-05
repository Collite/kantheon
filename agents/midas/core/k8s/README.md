module: midas-core
image: ghcr.io/boraperusic/midas-core     # jib
ports: { http: 7310, grpc: 7311 }   # 7311 = MCP, mapped to the library's grpc slot
needs:
  pg-database: midas         # MIDAS_DB_HOST=postgres, db `midas`, user `midas_app`; password via secret midas-db-secret
  downstream: [ capabilities-mcp ]
wave: 4    # midas domain (agents/services it calls are wave 2–3)
externally-exposed: {}

# Notes:
# - The base's `strategy: Recreate` is OMITTED per the D2 recipe (env-specific; deploying repo may re-add).
# - MIDAS_DB_PASSWORD stays in _env.tpl as a secretKeyRef (db.existingSecret / db.passwordKey);
#   host/name/user are values-driven (db.*).
# - CAPABILITIES_MCP_URL is an extraEnv default. OTLP endpoint omitted while telemetry disabled.
