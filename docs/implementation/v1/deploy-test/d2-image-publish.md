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

## Python (amd64 — build in CI, NOT on a Mac) — 3 modules: `kadmos`, `metis`, `steropes`
**Do not build these locally on Apple Silicon.** `docker buildx --platform linux/amd64` runs
the amd64 `uv`/Python under **QEMU**, and `uv sync` **segfaults** there (`qemu: uncaught target
signal 11`). They also need `shared/proto/build/python-package` generated first (`just proto-py`
— the `docker build` alone fails with `shared/proto/build/python-package: not found`).

**→ Use the `publish-python-images` GitHub Actions workflow** (`.github/workflows/publish-python-images.yml`)
— ubuntu runners are native amd64 (no QEMU), and it runs `proto-py` first:
```sh
# one-time: add a repo secret GHCR_TOKEN = a write:packages PAT for the boraperusic namespace
gh workflow run publish-python-images.yml -f tag=testing        # all three
gh workflow run publish-python-images.yml -f tag=testing -f services="services/kadmos"   # subset
# watch:
gh run watch "$(gh run list --workflow=publish-python-images.yml -L1 --json databaseId -q '.[0].databaseId')"
```

**Manual fallback (only on a native amd64 host — never Apple Silicon):**
```sh
just proto-py                                   # generate shared/proto/build/python-package first
echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
for m in services/kadmos services/metis workers/steropes; do
  n=$(basename "$m")
  docker buildx build --platform linux/amd64 --push \
    -t "ghcr.io/boraperusic/$n:testing" -f "$m/Dockerfile" .   # repo-root context (COPY shared/)
done
```

> **MP-1 note:** none of the three Python services sit on the PG query path
> (`theseus→proteus→argos→kyklop→arges`, all Kotlin/Jib). They can lag — their pods
> `ImagePullBackOff` until published, harmless for the query-path bring-up.

## Backstage (Node/custom) — best-effort
`infra/backstage` is a Backstage/Node build, not covered by the Jib/FE/py recipes. Build its image per the module's own Dockerfile (amd64) and push to `ghcr.io/boraperusic/backstage:testing`. Best-effort (§7-D3).

## Verify what's published

The packages are **private, under the `boraperusic` user namespace** — so the reliable check is
the **GitHub UI**, not the CLI. `docker manifest inspect` / `buildx imagetools inspect` in a loop
rate-limits the osxkeychain cred helper and returns **false negatives** (a standalone `docker pull`
works, but 15 rapid calls all report MISS). In the browser:

- **All packages (filter box):** `https://github.com/boraperusic?tab=packages&ecosystem=container`
- **A specific tag:** `https://github.com/users/boraperusic/packages/container/package/<name>` → the
  Versions/Tags section shows whether `:testing` exists.

A CLI spot-check works if you must, but space the calls out (`sleep`) or check one at a time:
```sh
docker pull ghcr.io/boraperusic/theseus:testing   # a real pull is the definitive check
```

The **18 D1 charts** already have images / are partly live (`capabilities-mcp`,`golem`,`iris`,`iris-bff` run on bp-dsk); republish any at `:testing` the same way if an integration context needs a fresh push.
