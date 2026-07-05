module: metis
image: ghcr.io/boraperusic/metis     # build-py (Python image; not Jib)
ports: { http: 7260, grpc: 7261 }
needs:
  pg-database: null
  seaweed-bucket: null
  keycloak: null
  downstream: []
wave: 2    # query-path-adjacent / model estimation (services that agents call)
externally-exposed: null
