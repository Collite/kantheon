```
module: report-renderer
image: ghcr.io/boraperusic/report-renderer     # jib
ports: { http: 7320 }
needs:
  keycloak: {}
  downstream: [ ]        # called by the constellation; calls nothing itself
wave: 5                  # domain/reporting-tier service
externally-exposed: {}
```

## Notes
- HTTP-only (POI/Playwright renderer): single `ports.http` (7320), no gRPC.
- Heavier `resources` than the standard backend (1Gi/500m req, 2Gi/2 limit), lifted from the base.
- No `extraEnv` (base had no downstream/DB wiring).
