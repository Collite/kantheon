# MP-4 release-tag sweep (deploy-test finish line)

> **Staged 2026-07-08**, ready to execute once **MP-4** is reached. Supersedes the stale
> per-service list in [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md)
> §9 (which predates the synchronized-version scheme — see "Version" below).

## Gate — do NOT tag until all of these hold

1. **`just it-bp-dsk-all` is all-green** on bp-dsk (the five contexts: pythia-rca, golem-erp,
   themis-routing, theseus-runquery, tpcds-query). This is MP-4.
2. **Branches merged to `master`**, working tree clean:
   - kantheon `feat/c2-themis-routing` → `master`
   - olymp `feat/c2-themis-routing-context` → `master`
   `just tag` refuses a dirty tree and warns off-master (release tags come off `master`).
3. The `:testing` images the run used are the ones the tags will represent (no local-only drift).

## Version — v0.5.0 → **v0.6.0** (uniform minor)

The scheme is a **synchronized version** across deployables, not per-service semver: the last sweep
put **26 modules at `v0.5.0`** (2026-06-27) plus a top-level `v0.6.0`-style `kantheon` tag. MP-4 is a
program milestone (integration green on bp-dsk), so cut a uniform **minor** bump to **v0.6.0**.

**`libs.versions.toml` / `gradle.properties`: no change.** The project version is supplied at build
time via `-Pkantheon.version` (default `0.0.0-SNAPSHOT`); the catalog holds only third-party
dependency versions. Nothing service-versioned lives in the repo — the git tags are authoritative.

## The sweep — explicit, because `just tag all` is insufficient

`just tag all` only scans `agents/ tools/ frontends/ shared/libs/kotlin/` — it **skips `services/`,
`workers/`, and `infra/`** (where proteus/brontes/prometheus/theseus/… live) and would also mint
`v0.1.0` tags for the never-released `*-mcp` tools. So drive the 26 baselined modules explicitly by
path (`just tag <path> minor` → bumps that module's latest tag; the path form works for Python and
non-Gradle modules too):

```sh
# workers (3)
just tag workers/arges minor
just tag workers/brontes minor
just tag workers/steropes minor

# services (13)
just tag services/argos minor
just tag services/ariadne minor
just tag services/charon minor
just tag services/echo minor
just tag services/kadmos minor
just tag services/kallimachos minor
just tag services/kyklop minor
just tag services/metis minor
just tag services/pinakes minor
just tag services/prometheus minor
just tag services/proteus minor
just tag services/report-renderer minor
just tag services/theseus minor

# agents (7)
just tag agents/golem minor
just tag agents/hebe minor
just tag agents/iris-bff minor
just tag agents/midas minor
just tag agents/pythia minor
just tag agents/sysifos-bff minor
just tag agents/themis minor

# frontends (1) + infra (2)
just tag frontends/iris minor
just tag infra/health minor
just tag infra/whois minor
```

Each command creates + pushes `<name>/v0.6.0`. Then the top-level milestone tag:

```sh
git tag v0.6.0 -m "kantheon v0.6.0 — MP-4: integration green on bp-dsk"
git push origin v0.6.0
```

## After

- `just tags` lists every module's latest tag — **note it too omits `services/`/`workers/`/`infra/`**;
  cross-check with `git tag -l '*/v0.6.0' | sort`.
- The tags matter beyond bookkeeping: `integration-nightly.yml` runs on `*/v*` release tags, and
  `release-image.yml` builds release images from them.

## Follow-ups (NOT part of MP-4 — decide separately)

- **Fix the `tag`/`tags` recipes** to include `services workers infra` in their `find` roots, so a
  future sweep is a single `just tag all minor`. (Left out here to avoid deciding the `*-mcp`/kleio/
  extra-frontends question mid-sweep — those are currently untagged and would start at `v0.1.0`.)
- **The deferred gated tiers** (themis `routingLive`, pythia `investigationLive`, theseus-runquery
  `rlsPolicyContext`) remain their own arcs' follow-ups — not release-gating.
- **Cut the never-released modules** (`tools/*-mcp`, `agents/kleio`, `frontends/{landing,sysifos,
  kallimachos-browse}`) into the version scheme when they first deploy.
