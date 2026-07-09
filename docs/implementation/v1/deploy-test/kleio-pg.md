# Kleio-PG — dedicated Postgres (pgvector + Apache AGE + full-text) — creation & install runbook

> **What this is.** The end-to-end record of how Kleio's **dedicated** Postgres instance (`kleio-pg`)
> was designed, built, wired, and brought up on **bp-dsk** — the custom operand image, the CNPG
> Cluster, the credential/pull-secret plumbing, the migration off the shared PG, every gotcha hit
> during bring-up, and the verification commands. Reproduce from here, or update when the estate moves.
>
> **Decided 2026-07-09 (Bora):** Kleio gets its **own** PG instance with pgvector + Apache AGE +
> full-text — the shared CNPG operand image ships none of the graph/vector extensions.
>
> **Reads with.** [`docs/architecture/kleio/`](../../../architecture/kleio/) (why four planes),
> [`tasks-d3-waves3-7.md`](./tasks-d3-waves3-7.md) (WS-D3 wave 6), [`d3-bring-up.md`](./d3-bring-up.md)
> (the general GitOps sync hand-off). Files: **[K]** kantheon `deployment/kleio-postgres/` · **[O]**
> olymp `platform/data/kleio-pg/` + `clusters/bp-dsk/`.

---

## 1. Why a dedicated instance

Kleio runs **all four RAG planes in one database** (`docs/architecture/kleio`), a single ingestion
transaction fanning out across them:

| Plane        | Mechanism                              | Needs                                                    |
|--------------|----------------------------------------|---------------------------------------------------------|
| relational   | core Postgres                          | —                                                       |
| full-text    | `tsvector` + GIN (`content_tsv`)       | **core** Postgres (free); `pg_trgm` (bundled contrib) for trigram fuzzy |
| vector       | `pgvector` — `embedding vector(N)`     | the **`vector`** extension in the image                 |
| graph        | Apache AGE — `kallimachos_graph`       | **AGE** in the image **+** `shared_preload_libraries = age` |

The shared agent Postgres (the central CNPG `postgres` cluster) runs the **stock** cloudnative-pg
operand image, which has neither `pgvector` nor **AGE**. AGE also needs `shared_preload_libraries`,
a cluster-wide setting we don't want on the shared instance. So Kleio gets an **isolated** CNPG
cluster on a **custom operand image**.

**Version note:** Apache AGE (1.6.x) supports **PG 18**, so `kleio-pg` runs the **same major (18)**
as the rest of the estate — no version fork. Full-text is core; `pg_trgm` is contrib. **AGE is the
only reason a custom image is needed.**

---

## 2. The custom operand image

`kantheon deployment/kleio-postgres/Dockerfile` — a two-stage build on the CNPG PG18 base that adds
pgvector (apt) and compiles AGE from source, preserving the CNPG contract (barman-cloud, uid 26,
entrypoint):

```dockerfile
ARG PG_MAJOR=18
ARG AGE_REF=master   # AGE branch/tag supporting PG18 (1.6.x line; pin to a release tag once cut)

# Stage 1 — compile Apache AGE against the matching PG dev headers
FROM ghcr.io/cloudnative-pg/postgresql:${PG_MAJOR}-standard-bookworm AS age-build
ARG PG_MAJOR
ARG AGE_REF
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential git flex bison postgresql-server-dev-${PG_MAJOR} \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${AGE_REF} https://github.com/apache/age.git /tmp/age \
    && cd /tmp/age \
    && make        PG_CONFIG=/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config \
    && make install PG_CONFIG=/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config

# Stage 2 — runtime = CNPG base + pgvector (apt) + AGE (copied)
FROM ghcr.io/cloudnative-pg/postgresql:${PG_MAJOR}-standard-bookworm
ARG PG_MAJOR
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-${PG_MAJOR}-pgvector \
    && rm -rf /var/lib/apt/lists/*
COPY --from=age-build /usr/lib/postgresql/${PG_MAJOR}/lib/age.so      /usr/lib/postgresql/${PG_MAJOR}/lib/
COPY --from=age-build /usr/share/postgresql/${PG_MAJOR}/extension/age* /usr/share/postgresql/${PG_MAJOR}/extension/
USER 26
```

### 2.1 Build & push — TWO non-obvious rules

```sh
# From the kantheon repo root:
docker buildx build --platform linux/amd64 \
  -t ghcr.io/boraperusic/kleio-postgres:18 \
  --push deployment/kleio-postgres
```

- **Rule 1 — the tag MUST encode the PG major (`:18`), not `:testing`.** CNPG parses the operand
  image tag to determine the Postgres major version; a bare `:testing` is rejected by the admission
  webhook: `spec.imageName: Invalid value: "…:testing": invalid version tag`. (§7, gotcha B.)
- **Rule 2 — build for the CLUSTER's arch (`linux/amd64`).** bp-dsk nodes are amd64; a plain
  `docker build` on an Apple-Silicon Mac produces **arm64-only**, and the node fails with
  `no match for platform in manifest`. Use `buildx --platform linux/amd64 --push` — a foreign-arch
  build can't be loaded into the local Docker daemon, so push straight to the registry. AGE compiles
  from source, so under QEMU emulation this is **slow** but works. (§7, gotcha D.)

The image is a **private** ghcr package → the cluster needs a pull secret (§5, §7 gotcha C).

---

## 3. The CNPG Cluster

`olymp platform/data/kleio-pg/base/cluster.yaml` — a separate CNPG cluster in the `data` ns:

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: kleio-pg
  namespace: data
  annotations:
    argocd.argoproj.io/sync-wave: "1"
    argocd.argoproj.io/sync-options: SkipDryRunOnMissingResource=true
spec:
  instances: 1
  imageName: ghcr.io/boraperusic/kleio-postgres:18   # PG-major tag (Rule 1)
  imagePullPolicy: Always                             # mutable tag → re-pull on (re)create
  imagePullSecrets:
    - name: ghcr-pull                                 # private image (§5)
  storage:
    size: 8Gi
  postgresql:
    shared_preload_libraries:
      - age                                           # AGE MUST be preloaded (pgvector/pg_trgm need not)
  managed:
    roles:
      - name: kleio
        ensure: present
        login: true
        passwordSecret: { name: pg-kleio-cred }
  bootstrap:
    initdb:
      database: kleio
      owner: kleio
      postInitApplicationSQL:                         # runs in the `kleio` DB as superuser at init
        - CREATE EXTENSION IF NOT EXISTS vector;
        - CREATE EXTENSION IF NOT EXISTS pg_trgm;
        - CREATE EXTENSION IF NOT EXISTS age;
```

Reachable at **`kleio-pg-rw.data.svc.cluster.local:5432/kleio`**. The Kleio app sets
`LOAD 'age'; SET search_path = ag_catalog, "$user", public;` **per session** (Exposed init) — AGE's
graph functions live in the `ag_catalog` schema.

---

## 4. Kustomization wiring

```
olymp/platform/data/kleio-pg/
├── base/
│   ├── cluster.yaml            # the Cluster above
│   └── kustomization.yaml      # resources: [cluster.yaml]
└── overlays/bp-dsk/
    ├── kustomization.yaml      # resources: [../../base, externalsecret-pg-kleio-cred.yaml]
    └── externalsecret-pg-kleio-cred.yaml
```

Registered in the bp-dsk data tier — `olymp clusters/bp-dsk/platform/data/kustomization.yaml`:

```yaml
resources:
  - ../../../../platform/data/cnpg
  - ../../../../platform/data/postgres/overlays/bp-dsk
  - ../../../../platform/data/test-pg/overlays/bp-dsk
  - ../../../../platform/data/backstage-postgres/overlays/bp-dsk
  - ../../../../platform/data/kleio-pg/overlays/bp-dsk        # ← added
  - ../../../../platform/data/mssql/overlays/bp-dsk
  - ...
```

Validate locally before syncing:
```sh
cd ~/Dev/collite-gh/olymp && just validate bp-dsk        # renders the whole cluster
kubectl kustomize platform/data/kleio-pg/overlays/bp-dsk  # renders just kleio-pg (Cluster + cred)
```

---

## 5. Credentials & the pull secret

**Role password (`pg-kleio-cred`)** — `overlays/bp-dsk/externalsecret-pg-kleio-cred.yaml`, an ESO
`ExternalSecret` materialising a basic-auth secret in the `data` ns from vault key `pg-kleio`
(username `kleio`, password from vault). CNPG's `managed.roles` reads it for the `kleio` role. This
was **relocated** here from the shared `postgres` overlay when Kleio moved to its own instance (§6).

**Image pull secret (`ghcr-pull`)** — the `kleio-postgres` package is **private**, so the pod needs
credentials (the public cloudnative-pg operand images need none). The auth-tier
`ClusterExternalSecret` distributes `ghcr-pull` — its `namespaceSelectors` was extended to the
`data` ns — `olymp clusters/bp-dsk/platform/auth/clusterexternalsecret-ghcr-pull.yaml`:

```yaml
  namespaceSelectors:
    - matchLabels: { kubernetes.io/metadata.name: kantheon }
    - matchLabels: { kubernetes.io/metadata.name: data }     # ← added for kleio-pg
```

The Cluster references it via `spec.imagePullSecrets: [{name: ghcr-pull}]`.

> **Alternative:** make the ghcr `kleio-postgres` package **public** (ghcr → package settings →
> visibility). It's just Postgres + OSS extensions, no secrets — matching how the cloudnative-pg
> operand images are distributed. Then the pull-secret plumbing above is redundant (but harmless).

---

## 6. Migration off the shared Postgres

Kleio previously had a `kleio` role + `kleio` DB on the **shared central `postgres` cluster**
(waves-1–2 stub). Moving to `kleio-pg` required removing that footprint:

- `olymp platform/data/postgres/base/cluster.yaml` — removed the `kleio` managed role.
- `olymp platform/data/postgres/base/databases.yaml` — removed the `kleio` `Database` CRD.
- `olymp platform/data/postgres/overlays/bp-dsk/kustomization.yaml` — removed the
  `externalsecret-pg-kleio-cred.yaml` reference (the file moved to the `kleio-pg` overlay).

A fresh estate never provisions kleio on the shared instance; existing clusters just drop the unused
role/DB on the next sync.

---

## 7. Gotchas hit during bring-up (in order)

Each blocked the sync until fixed — recorded so the next custom-operand cluster skips them.

| # | Symptom | Cause | Fix |
|---|---------|-------|-----|
| **A** | AGE `CREATE EXTENSION age` would fail | AGE needs preloading | `spec.postgresql.shared_preload_libraries: [age]` (already in §3) |
| **B** | `admission webhook "vcluster.cnpg.io" denied … spec.imageName: Invalid value: "…:testing": invalid version tag` — **whole `data` Application sync blocked** | CNPG parses the tag for the PG major; `testing` has no version | Tag the image `:18` (major-encoded); `imageName: …/kleio-postgres:18` |
| **C** | `failed to authorize: … 401 Unauthorized` on pull | the `kleio-postgres` ghcr package is **private** (cloudnative-pg operand images are public, so those clusters need no secret) | distribute `ghcr-pull` into the `data` ns + `spec.imagePullSecrets` (§5) — or make the package public |
| **D** | `no match for platform in manifest: not found` | image built on Apple-Silicon Mac = **arm64-only**; bp-dsk node is **amd64** | rebuild `docker buildx build --platform linux/amd64 --push …` (§2.1) |

Confirm the node arch when in doubt:
```sh
kubectl --context dsk get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.status.nodeInfo.architecture}{"\n"}{end}'
# dsk  amd64
```

---

## 8. Verification (post-bootstrap)

The bootstrap **completing** (pod `Ready`) is itself a signal the `CREATE EXTENSION` calls
succeeded — CNPG fails the bootstrap if `postInitApplicationSQL` errors. Confirm directly:

```sh
# Extensions present in the kleio DB + AGE preloaded:
kubectl --context dsk -n data exec -i kleio-pg-1 -- \
  psql -U postgres -d kleio -c "\dx" -c "SHOW shared_preload_libraries;"
# expect \dx: age, pg_trgm, plpgsql, vector   |   shared_preload_libraries = age
```

Functional smoke across all four planes (AGE graph + Cypher, pgvector distance, full-text):
```sh
kubectl --context dsk -n data exec -i kleio-pg-1 -- psql -U postgres -d kleio -v ON_ERROR_STOP=1 <<'SQL'
LOAD 'age';
SET search_path = ag_catalog, public;
SELECT create_graph('smoke_graph');
SELECT * FROM cypher('smoke_graph', $$ CREATE (n:Test {name:'ok'}) RETURN n $$) AS (n agtype);
SELECT drop_graph('smoke_graph', true);
CREATE TEMP TABLE vt(embedding vector(3));
INSERT INTO vt VALUES ('[1,2,3]');
SELECT embedding <-> '[1,1,1]' AS vector_distance FROM vt;
SELECT to_tsvector('english','kleio full text search') @@ to_tsquery('english','search') AS fts_ok;
SQL
# clean run to fts_ok = t  →  all four planes functional
```

Boot / status inspection:
```sh
kubectl --context dsk -n data get cluster kleio-pg                 # CNPG Cluster status
kubectl --context dsk -n data get pods -l cnpg.io/cluster=kleio-pg
kubectl --context dsk -n data logs -l cnpg.io/cluster=kleio-pg --tail=40 | grep -iE "extension|age|vector|error|ready"
# force a fresh pull if a pod is stuck in ImagePullBackOff:
kubectl --context dsk -n data delete pod -l cnpg.io/cluster=kleio-pg
```

**Result (2026-07-09):** kleio-pg came up on `PostgreSQL 18.4 (x86_64)`, `\dx` shows `age`,
`pg_trgm`, `vector`; the functional smoke passes → all four planes live.

---

## 9. Updating the image later

The `:18` tag is mutable; `imagePullPolicy: Always`:

```sh
# rebuild + push (e.g. to bump AGE_REF or pgvector):
docker buildx build --platform linux/amd64 --build-arg AGE_REF=<branch> \
  -t ghcr.io/boraperusic/kleio-postgres:18 --push deployment/kleio-postgres
# roll the cluster to re-pull:
kubectl --context dsk -n data delete pod -l cnpg.io/cluster=kleio-pg
```

Bump the **base** tag (`18-standard-bookworm`) in lockstep with the CNPG operator's supported operand
version. For a genuine PG **major** upgrade, that's a CNPG major-upgrade flow (out of scope here).

---

## 10. The Kleio *app* side (still TODO — WS-D3 wave 6)

This runbook covers the **PG instance**. Wiring the Kleio *application* to it (the wave-6 app
authoring) still needs:

- an app-ns `pg-kleio` ClusterExternalSecret delivering the DB credential into the `kantheon` ns;
- the kleio app `KLEIO_DB_URL` → `jdbc:postgresql://kleio-pg-rw.data.svc.cluster.local:5432/kleio`
  (chart default points at `kantheon-pg`);
- the app's per-session `LOAD 'age'; SET search_path = ag_catalog, "$user", public;` (kleio code —
  already AGE-native).

---

## Commit trail (olymp `feat/d3-waves3-7`)

- `b618e96` — kleio dedicated PG + migration off shared (initial)
- `ae88d76` — imageName version tag `:18` (gotcha B)
- `8f27098` — ghcr pull secret into the `data` ns (gotcha C)
- image rebuilt `linux/amd64` (gotcha D) — registry-only, no manifest change

Kantheon: `deployment/kleio-postgres/Dockerfile` (+ this doc) — committed on `demo-prep`.
