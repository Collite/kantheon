# Hebe instance bring-up (K3s) — reproducible runbook

> Hebe arc P3 S3.3. The `k8s` profile: one pod per instance, schema-per-instance
> Postgres, workspace + receipts in PG (`fs.durability = ephemeral`), registered
> `non_routable` in capabilities-mcp. Authority: [`contracts.md`](../../../docs/architecture/hebe/contracts.md) §4
> (per-instance PG schema, V1–V7, the §4.4 provisioning runbook).

## Prerequisites

- Local K3s up; the Kantheon Postgres reachable in-cluster (database `hebe`, with the
  **pgvector** extension — `provision.sh` creates it once per DB).
- `deployment/local` Keycloak realm available (the `k8s` profile mints OBO via the
  in-cluster client-credentials → OBO exchange).
- `kubectl`, `kustomize`, `flyway`, `psql`, `just` on PATH.

## Steps

```bash
# 1. Provision the instance: schema hebe_<id> + a grant-limited role (NO UPDATE/DELETE
#    on receipts/messages/llm_calls/tool_calls — append-only; no cross-schema access),
#    the shared db/migration-pg/ set applied to that schema, and the hebe-<id> Secret
#    skeleton. Fill the Secret with the real PG/Keycloak/llm-gateway/Telegram/signing values.
just hebe-provision dev

# 2. Build the server-mode image (Jib) + apply the local overlay (imagePullPolicy: Never).
just deploy-hebe dev

# 3. Watch readiness: /ready returns 200 once config + PG + migrations + channels are up
#    (the ReadinessGate); /healthz is liveness.
kubectl -n kantheon rollout status deployment/hebe-dev
```

## Verify (the deploy smoke — not an automated CI gate, planning-conventions §4)

1. **Converse** via the web console (port-forward the pod's `:8765`).
2. **Routine fires** — create a `kantheon_question` routine on a near cron; confirm a
   job runs and a turn lands in the Iris session (gated on iris-bff ≥ Iris Phase 2).
3. **Receipts verify** — `hebe memory show receipts --verify` walks the PG `receipts`
   chain (same algorithm as the file log; the signing key is the instance K8s Secret).
4. **Registered + unroutable** — the instance appears in capabilities-mcp `list`/`get`
   but never in the Themis routing view (`non_routable`, Stage 3.4).

## Provisioning another instance

`provision.sh <id>` + a sibling overlay (`agents/hebe/k8s/overlays/<id>`) with a
distinct `nameSuffix` + `HEBE_INSTANCE_ID` + `hebe-<id>` Secret. One pod, one schema,
one bound Keycloak user per instance. Automation of this runbook is open question O-2
(architecture §10) — manual in v1.

> The automated in-cluster round-trip (real PG + real iris-bff) is the **integration
> suite**, not this smoke. This runbook is the reproducible manual bring-up.
