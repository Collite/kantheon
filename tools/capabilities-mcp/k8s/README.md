# capabilities-mcp Helm chart

Env-agnostic Helm chart for the capabilities-mcp service. It holds **no cluster
knowledge** — image, replicas, resources, telemetry endpoint and extra env are all
values. Per-environment values live in the deploying GitOps repo (Olymp) and are wired
in via ArgoCD multi-source (chart here `@ref` + `$values` from Olymp). See Olymp
`docs/decisions.md` D3′.

## Local dev (side-loaded image)

Build the image into your local cluster (`capabilities-mcp:dev`) as before, then:

```bash
helm template cap k8s --set image.pullPolicy=Never | kubectl apply -n kantheon -f -
# or
helm upgrade --install cap k8s -n kantheon --set image.pullPolicy=Never
```

Telemetry export is **off** by default (no collector assumed). Enable it by pointing at a
real OTLP collector:

```bash
helm template cap k8s \
  --set telemetry.enabled=true \
  --set telemetry.otlpHost=mon-alloy.monitoring.svc.cluster.local
```

## Values

| key | default | purpose |
|-----|---------|---------|
| `image.repository` / `image.tag` | `capabilities-mcp` / `""`→`appVersion` | container image |
| `image.pullPolicy` | `IfNotPresent` | set `Never` for side-loaded local images |
| `replicaCount` | `1` | |
| `service.port` / `containerPort` | `7501` | MCP HTTP port |
| `telemetry.enabled` | `false` | emit OTLP |
| `telemetry.otlpHost` / `telemetry.otlpGrpcPort` | `""` / `4317` | collector host+port (host required when enabled) |
| `resources` | 100m/256Mi … 500m/512Mi | |
| `extraEnv` | `[]` | extra env passthrough |
