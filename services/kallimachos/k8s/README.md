```
module: kallimachos
image: ghcr.io/boraperusic/kallimachos     # jib
ports: { http: 7260, grpc: 7261 }   # http = probe/management port (7260); grpc = HTTP API port (7261)
needs:
  pg-database: kallimachos           # postgres storage profile; needs vector/age extensions
  keycloak: {}
  downstream: [ ]
wave: 5                              # librarian-tier service
externally-exposed: {}
```

## Notes
- **Port mapping deviation:** the base names its ports `probe`(7260) / `http`(7261) and targets probes
  at 7260. The library targets probes at `ports.http` and names the container/service ports
  `http`/`grpc`. To preserve probe behaviour verbatim, `ports.http = 7260` (probe target) and
  `ports.grpc = 7261` (the HTTP API port). Numbers and probe targeting are exact; only the port
  *names* differ cosmetically (http/grpc vs probe/http). Env vars `KALLIMACHOS_PROBE_PORT` /
  `KALLIMACHOS_HTTP_PORT` keep their base values (7260 / 7261).
- **DB wiring** (`KALLIMACHOS_STORAGE_PROFILE=postgres`, `KALLIMACHOS_DB_URL`, `KALLIMACHOS_DB_USER`)
  lives in `extraEnv` defaults. DB password is supplied out of band, not by this chart.
