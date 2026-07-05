module: kleio
image: ghcr.io/boraperusic/kleio     # jib
ports: { http: 7270 }
needs:
  pg-database: kleio         # KLEIO_DB_URL → jdbc:postgresql://kantheon-pg:5432/kleio
  downstream: [ kallimachos-mcp, prometheus ]
wave: 5    # librarian line
externally-exposed: {}

# Notes:
# - The kustomize base ships only a readiness probe (/ready); the library always renders a
#   liveness probe, so one was added on the same /ready endpoint (no /health declared by base).
# - The base carries no OTEL vars; the standard telemetry block (off by default) was added for
#   parity (OTEL_ENABLED_KLEIO + OTEL_SERVICE_NAME).
# - DB URL + downstream hosts are extraEnv defaults (env-agnostic in-cluster names).
