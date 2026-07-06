# WS-T1 runbook — load the TPC-DS `tpc-ds-1g` warehouse on bp-dsk

> The GitOps (test-pg cluster + DB + creds) and the load Job are authored; the DDL + the
> trailing-pipe load form are proven by `TpcdsLoadComponentSpec` (kantheon). This runbook is
> the operator steps that need the cluster + your data + the vault. Data host: `~/Data/TPC-DS`
> (25 `.dat`, SF1 ~1.2 GB, + `tpcds.sql` / `tpcds_ri.sql`). olymp branch: `feat/t1-test-pg-load`.

## 0 — Prereqs (yours)

1. **Vault keys** (Azure KV, same store as the other `pg-*` creds) — add two passwords so the
   ExternalSecrets can materialize the roles:
   - `pg-tpcds` (owner role `tpcds`)
   - `pg-tpcds-ro` (read role `tpcds_readonly`, the one Arges connects as)
2. **Merge olymp `feat/t1-test-pg-load` → master.** ArgoCD then creates: the `test-pg` CNPG
   cluster (ns `data`, 8Gi), the `tpc-ds-1g` Database, the two role secrets, and the
   `tpcds-staging` Seaweed bucket. Confirm:
   ```sh
   kubectl --context dsk -n data get cluster test-pg          # → Cluster in healthy state
   kubectl --context dsk -n data get database tpc-ds-1g
   ```

## T3 — stage the data to Seaweed (one-time; reloads never need host access again)

Seaweed S3 auth is off, so `--no-sign-request` works for PUT. Upload the 25 `.dat` + both DDLs
to the `tpcds-staging` bucket (the Job reads `http://seaweedfs-s3.data.svc…:8333/tpcds-staging/*`):

```sh
kubectl --context dsk -n data port-forward svc/seaweedfs-s3 8333:8333 &
PF=$!
aws s3 cp ~/Data/TPC-DS/ s3://tpcds-staging/ --recursive \
  --exclude "*" --include "*.dat" --include "tpcds.sql" --include "tpcds_ri.sql" \
  --endpoint-url http://localhost:8333 --no-sign-request
aws s3 ls s3://tpcds-staging/ --endpoint-url http://localhost:8333 --no-sign-request   # 27 objects
kill $PF
```
*(`mc` works too: `mc alias set sw http://localhost:8333 "" "" ; mc cp --recursive ~/Data/TPC-DS/ sw/tpcds-staging/`. `tpcds_source.sql` is intentionally not uploaded — it's the unused ETL schema.)*

## T5 — run the load + verify

The Job pulls the DDL + `.dat` from Seaweed, drops+recreates the 25 tables, strips the trailing
`|` and `\copy … WITH (DELIMITER '|', NULL '')` each, applies `tpcds_ri.sql` FKs + `ANALYZE`,
then grants `tpcds_readonly`. It's **not** ArgoCD-synced — apply it by hand:

```sh
kubectl --context dsk apply -f platform/data/test-pg/overlays/bp-dsk/load-job.yaml   # (olymp repo)
kubectl --context dsk -n data wait --for=condition=complete job/tpcds-load --timeout=1800s
kubectl --context dsk -n data logs job/tpcds-load | tail -20     # prints the row counts
```

**Expected SF1 row counts (the deterministic oracle — `wc -l` of the `.dat`):**

| table | rows |
|---|---|
| `store_sales` | 2,880,404 |
| `catalog_sales` | 1,441,548 |
| `web_sales` | 719,384 |
| `customer` | 100,000 |
| `date_dim` | 73,049 |
| `item` | 18,000 |

Confirm `tpcds_readonly` is read-only (Arges's path):
```sh
kubectl --context dsk -n data exec -it test-pg-1 -- psql -U tpcds_readonly -d tpc-ds-1g \
  -c "SELECT count(*) FROM store_sales;"                 # → 2880404
  # a write should be denied:
kubectl --context dsk -n data exec -it test-pg-1 -- psql -U tpcds_readonly -d tpc-ds-1g \
  -c "DELETE FROM reason;"                               # → ERROR: permission denied
```

## T6 — reload (idempotent)

The Job drops the 25 tables first, so a reload is a delete + re-apply — same counts, no
duplicate-key errors:
```sh
kubectl --context dsk -n data delete job tpcds-load
kubectl --context dsk apply -f platform/data/test-pg/overlays/bp-dsk/load-job.yaml
```

## Next (WS-T2)

With `tpc-ds-1g` loaded, WS-T2 adds the Ariadne TPC-DS model + curated queries + the `pg-tpcds`
connection (Arges reads `tpcds_readonly` on `test-pg`, `requires-tenant-id = false`), and Kyklop
routes `connection_id = pg-tpcds` → Arges. Then arges/brontes drop fixture mode and the
`tpcds-query` integration context runs the real path (MP-2).
