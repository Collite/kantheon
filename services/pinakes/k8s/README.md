```
module: pinakes
image: ghcr.io/boraperusic/pinakes     # jib
ports: { http: 7280, grpc: 7281 }
needs:
  seaweed-bucket: docwh-stage        # PINAKES_SEAWEED_ENDPOINT + PINAKES_SEAWEED_BUCKET
  keycloak: {}
  downstream: [ kallimachos ]        # PINAKES_KALLIMACHOS_HOST:PORT (7261)
wave: 5                              # librarian-tier service
externally-exposed: {}
```

## Notes
- **downstream/storage wiring** (Seaweed endpoint + bucket, kallimachos host/port) lives in
  `extraEnv` defaults.
- `PINAKES_KALLIMACHOS_PORT=7261` targets kallimachos's HTTP API port.
