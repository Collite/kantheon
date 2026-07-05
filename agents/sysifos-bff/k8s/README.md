module: sysifos-bff
image: ghcr.io/boraperusic/sysifos-bff     # jib
ports: { http: 7601 }
needs:
  keycloak: { }              # JWKS signature verification (SYSIFOS_AUTH_VERIFY_SIGNATURE); issuer/audience realm-dependent, set per env
  downstream: [ midas-core ] # SYSIFOS_MIDAS_BASE_URL=http://midas-core:7310 (no DB; every write proxies here)
wave: 4    # sysifos domain BFF
externally-exposed: {}

# Notes:
# - The base's `strategy: Recreate` is OMITTED per the D2 recipe (env-specific).
# - SYSIFOS_AUTH_VERIFY_SIGNATURE is values-driven (auth.verifySignature).
# - midas-core upstream is an extraEnv default. OTLP endpoint omitted while telemetry disabled.
