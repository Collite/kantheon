module: pythia
image: ghcr.io/boraperusic/pythia     # jib
ports: { http: 7090 }
needs:
  pg-database: pythia        # PYTHIA_DB_HOST=kantheon-postgres, db `pythia`; credentials via envFrom secret pythia-db-credentials (optional)
  downstream: [ nats ]       # PYTHIA_NATS_URL=nats://nats:4222 (unreachable → degrade to PG-log-only)
wave: 3    # analytical investigator agent
externally-exposed: {}

# Notes:
# - DB credentials injected wholesale via envFrom (secretRef pythia-db-credentials, optional: true),
#   set in values.yaml; the library renders envFrom when present.
# - PYTHIA_DB_ENABLED/HOST/NAME + PYTHIA_NATS_URL are extraEnv defaults (env-agnostic in-cluster names).
# - OTLP endpoint omitted while telemetry disabled.
