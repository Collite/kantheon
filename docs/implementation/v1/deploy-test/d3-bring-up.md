# WS-D3 bring-up runbook — query-path wave on bp-dsk (MP-1)

> **Scope of this chunk (query-path-first, single-namespace).** The olymp branch
> `feat/d3-bp-dsk-apps` wires the **foundation + waves 1–2** (registry/core + the query
> path) as ArgoCD apps in a **single shared `kantheon` namespace**. Reaching this live is
> **MP-1** (query path — theseus→proteus→argos→kyklop→**arges** — live on bp-dsk). Agents /
> domain / librarian / infra waves (3–7) + the DB-backed + FE + backstage apps are a
> **follow-up D3 chunk**.
>
> **Two repos.** kantheon `feat/d2-charts-images` (the charts) · olymp `feat/d3-bp-dsk-apps`
> (the apps + platform). ArgoCD on bp-dsk reconciles **olymp master (HEAD)** and the kantheon
> ref in each `config.json` (`feat/d2-charts-images`). Nothing goes live until the olymp branch
> merges to master.

## What landed (no cluster action needed to review)

**Fix (critical):** the stale git repo `github.com/BoraPerusic/kantheon` → **`github.com/Collite/kantheon`** across all 3 clusters (appset + project sourceRepos + bootstrap + CLAUDE.md). This alone un-breaks the 3 apps that were `Unknown` (they pointed at dead BoraPerusic branches). The `ghcr.io/boraperusic/*` **image** registry is unchanged (different thing).

**Single-namespace model (WS-D3 decision):** the appset `destination.namespace` is now `kantheon` (was per-app `{{.path.basename}}`). The constellation's charts use bare in-cluster service names (the local-infra topology), which resolve in one namespace — so each app's `values.yaml` is just image + `ghcr-pull`, no FQDN wiring. Test runs still isolate in their own `it-*`/`kantheon-<ctx>-<run>` namespaces (WS-R boundary unaffected).

**Apps (23 in `clusters/bp-dsk/apps/`):**
- **Existing 4 repointed** → `chartRevision: feat/d2-charts-images` (the library-based charts): `capabilities-mcp`, `golem`, `iris`, `iris-bff`. They **migrate into the `kantheon` namespace** on next sync (their old per-app namespaces are pruned). iris's `bffUpstream.host` → bare `iris-bff`. Tags stay at released `0.1.0`.
- **19 new (waves 1–2), `:testing` / `pullPolicy: Always`:** ariadne, prometheus, echo, kadmos, ariadne-mcp, echo-mcp, kadmos-mcp (wave 1); charon, proteus, argos, kyklop, theseus, theseus-mcp, brontes, steropes, charon-mcp, metis, metis-mcp, **arges** (wave 2). All render green with their values overlay.

**Platform (`platform/` + `clusters/bp-dsk/platform/`):**
- CNPG: DB + managed role + `pg-{midas,hebe,kleio}-cred` ExternalSecret for the agent DBs **coming in later waves** (backstage uses its own existing `backstage-postgres`, not the shared cluster). No wave-1/2 app needs a DB.
- `ghcr-pull` ClusterExternalSecret + `pg-iris`/`pg-golem` cred ClusterExternalSecrets retargeted to the **`kantheon`** namespace.
- Seaweed buckets `charon` + `docwh-stage` pre-created.

## To make waves 1–2 live (your actions — the deploy gates)

1. **Publish the 19 wave-1/2 `:testing` images** to GHCR (needs your `write:packages` PAT). Per the [T6 runbook](./d2-image-publish.md): `just publish-image <path> testing` (Jib) for the Kotlin ones; `just build-py` + amd64 push for `metis`/`steropes`. The 4 existing images (`:0.1.0`) already exist. *(No new Azure KV secrets needed for this wave — `ghcr-pull-token` already exists; the `pg-*` KV keys are for later DB waves.)*
2. **Merge `feat/d2-charts-images` on kantheon** — or keep the branch; `config.json` points at it, so it resolves on Collite/kantheon as-is. (Flip `chartRevision`→`master` per app when D1+D2 merge — T7.)
3. **Merge olymp `feat/d3-bp-dsk-apps` → master.** ArgoCD then: repoints to Collite/kantheon, moves the 4 existing apps into `kantheon` ns, and generates the 19 new apps there.
4. **Sync + verify (MP-1):**
   ```sh
   kubectl --context dsk -n argocd get applications          # 23 apps Synced/Healthy
   kubectl --context dsk -n kantheon get pods                # constellation in one ns
   ```
   Then a query through the live path (theseus-mcp `query` → proteus → argos → kyklop → arges) returns — **MP-1**. arges deploys Healthy on its own `/health`; its `pg-midas`/`pg-tpcds` named connections wire with the Midas arc / WS-T (`test-pg`), and fail lazily (not the pod) until then.

## Heads-up
- **Namespace migration:** the 4 live apps move from their own namespaces into `kantheon` on the first post-merge sync (prune + recreate) — a brief disruption for iris/golem. Their old namespaces linger empty (delete manually if desired).
- **sync-wave ordering** isn't enforced per-app (the appset sets none, matching today's apps); ArgoCD self-heal/retry converges crash-looping-until-deps-up services. Add `argocd.argoproj.io/sync-wave` to the appset template if strict ordering is wanted.
- **Follow-up D3 chunk:** waves 3–7 (themis, pythia, midas-core/loader, sysifos-bff, report-renderer, kleio, kallimachos, pinakes, kallimachos-mcp, hebe, whois, health, backstage + FEs sysifos/landing/kallimachos-browse). These add the DB-cred ClusterExternalSecrets (+ Azure KV `pg-{midas,hebe,kleio}` keys) and Keycloak realm clients (`iris`/`sysifos`/`landing`/`kallimachos-browse`/`backstage` — none exist in the realm JSON yet).
