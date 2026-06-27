# golem Helm chart

Env-agnostic Helm chart for the golem agent. Holds **no cluster knowledge** — image,
replicas, resources, the Postgres connection and telemetry are all values. Per-environment
values live in the deploying GitOps repo (Olymp), wired via ArgoCD multi-source (D3′).

## Local dev (side-loaded image, in-memory repo)

```bash
helm template golem k8s --set image.pullPolicy=Never | kubectl apply -n kantheon -f -
```

## With Postgres

```bash
helm template golem k8s \
  --set db.enabled=true \
  --set db.host=postgres-rw.data.svc.cluster.local \
  --set db.existingSecret=pg-golem-cred
```

`db.password` is never set in values — it is read from `db.existingSecret` (key
`db.passwordKey`, default `password`), e.g. a CNPG/ESO basic-auth secret.

## Values

| key | default | purpose |
|-----|---------|---------|
| `image.repository` / `image.tag` | `golem` / `""`→`appVersion` | container image |
| `image.pullPolicy` | `IfNotPresent` | `Never` for side-loaded local images |
| `service.port` / `containerPort` | `7420` | HTTP port |
| `db.enabled` | `false` | enable Postgres persistence |
| `db.host` / `db.port` / `db.name` / `db.user` | `""` / `5432` / `golem` / `golem` | connection |
| `db.existingSecret` / `db.passwordKey` | `""` / `password` | password source |
| `telemetry.enabled` | `false` | emit OTLP (golem's own toggle) |
| `extraEnv` | `[]` | extra env passthrough |
