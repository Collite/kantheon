# WS-D2 T6 — image-publish runbook (needs Bora's GHCR PAT)

> **Why a runbook, not done in-repo.** Publishing pushes to `ghcr.io/boraperusic/*` and
> requires `GHCR_USER` + a `write:packages` PAT (`GHCR_TOKEN`) — a credential the coding
> agent doesn't hold, and an outward, credential-gated action. The charts + goldens (T1–T5,
> T7) are done and green; **run the commands below to close T6.** Tag `:testing` (mutable,
> `pullPolicy: Always`) for integration contexts; a `vX.Y.Z` release tag for bp-dsk live apps
> (contracts §8). bp-dsk nodes are **amd64** — Jib/FE recipes already build amd64; the Python
> lane needs an explicit `--platform linux/amd64` (see caveat).

Set once:
```sh
export GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_…   # PAT with write:packages
```

## Kotlin (Jib, multi-arch amd64+arm64) — 16 modules
`just publish-image <path> [tag]` → `ghcr.io/boraperusic/<basename>:<tag>` (default `:testing`).
```sh
for m in services/charon services/kallimachos services/pinakes services/report-renderer \
         agents/hebe agents/kleio agents/midas/core agents/midas/loaders/excel \
         agents/pythia agents/sysifos-bff \
         tools/ariadne-mcp tools/charon-mcp tools/echo-mcp tools/kadmos-mcp \
         tools/kallimachos-mcp tools/metis-mcp; do
  just publish-image "$m" testing
done
```
Note `midas/loaders/excel`'s image basename is `excel` under `just publish-image` (it uses `basename`), but the **chart's `image.repository` is `midas-excel-loader`**. Publish it explicitly to the chart name:
```sh
CI=true ./gradlew ":agents:midas:loaders:excel:jib" \
  -Djib.to.image=ghcr.io/boraperusic/midas-excel-loader:testing \
  -Djib.to.auth.username="$GHCR_USER" -Djib.to.auth.password="$GHCR_TOKEN" --no-daemon
```
(Verify the gradle path with `just _resolve excel` / `./gradlew projects`.)

## FE nginx (linux/amd64) — 3 modules
`just publish-fe-image <service> [tag]` → `ghcr.io/boraperusic/<service>:<tag>`.
```sh
for s in landing sysifos kallimachos-browse; do just publish-fe-image "$s" testing; done
```
Caveat: `kallimachos-browse` has no Dockerfile/nginx.conf yet (best-effort) — `build-fe` will fail until its FE build lands. Skip if not ready.

## Python (build-py + manual amd64 push) — 2 modules
`build-py` builds `<name>:dev` locally at host arch only — **not** GHCR, **not** amd64. For bp-dsk push an amd64 image explicitly:
```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
for m in services/metis workers/steropes; do
  n=$(basename "$m")
  # metis/steropes Dockerfiles copy shared/ → repo-root build context:
  docker buildx build --platform linux/amd64 --push \
    -t "ghcr.io/boraperusic/$n:testing" -f "$m/Dockerfile" .
done
```
(If a module's Dockerfile is self-contained — no `COPY shared` — use `"$m"` as the build context instead of `.`. `just build-py <m>` shows which.)

## Backstage (Node/custom) — best-effort
`infra/backstage` is a Backstage/Node build, not covered by the Jib/FE/py recipes. Build its image per the module's own Dockerfile (amd64) and push to `ghcr.io/boraperusic/backstage:testing`. Best-effort (§7-D3).

## Verify each pulls
```sh
for n in charon kallimachos pinakes report-renderer hebe kleio midas-core midas-excel-loader \
         pythia sysifos-bff ariadne-mcp charon-mcp echo-mcp kadmos-mcp kallimachos-mcp metis-mcp \
         metis steropes landing sysifos; do
  docker manifest inspect "ghcr.io/boraperusic/$n:testing" >/dev/null && echo "ok  $n" || echo "MISS $n"
done
```

The **18 D1 charts** already have images / are partly live (`capabilities-mcp`,`golem`,`iris`,`iris-bff` run on bp-dsk); republish any at `:testing` the same way if an integration context needs a fresh push.
