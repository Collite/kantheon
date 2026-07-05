module: midas-excel-loader
image: ghcr.io/boraperusic/midas-excel-loader     # jib
ports: { http: 7315 }
needs:
  downstream: [ midas-core ]   # MIDAS_CORE_BASE_URL=http://midas-core:7310
wave: 4    # midas domain loader
externally-exposed: {}

# Notes:
# - Mounts an emptyDir scratch volume for uploaded statement blobs (EXCEL_LOADER_BLOB_DIR),
#   gated on blobs.enabled in templates/_volumes.tpl (overrides the library's empty hooks).
# - midas-core upstream is an extraEnv default. OTLP endpoint omitted while telemetry disabled.
# - Chart dependency path is five deep (../../../../../) — agents/midas/loaders/excel/k8s.
