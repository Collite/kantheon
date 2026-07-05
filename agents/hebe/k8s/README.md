module: hebe
image: ghcr.io/boraperusic/hebe     # jib
ports: { http: 8765 }
needs:
  pg-database: hebe          # k8s profile → in-cluster PG (credentials in the instance Secret)
  keycloak: { client: hebe, serviceAccount: hebe }   # OBO for platform-reaching profiles
  downstream: [ iris-bff ]   # scheduled constellation turns call iris-bff
wave: 5    # personal agent, one pod per instance
externally-exposed: {}

# Notes:
# - The kustomize base carries no OTEL vars; the standard telemetry block (off by default) was
#   added for constellation parity (OTEL_ENABLED_HEBE + OTEL_SERVICE_NAME).
# - Per-instance Secret (instanceSecrets.secretName) is provisioned by the deploying context
#   (provision.sh), not templated. Empty = skeleton boot, no mount. Mount is gated in _volumes.tpl.
# - HEBE_PROFILE=k8s / HEBE_INSTANCE_ID are values-driven (hebe.profile / hebe.instanceId).
