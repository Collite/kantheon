# Testing — HOWTO (operational runbook)

> **Scope.** The hands-on runbook for the kantheon test tiers: how to run each tier locally, how
> the integration nightly is wired across kantheon ↔ olymp, how to set up the CI credential and a
> self-hosted runner, and how to triage the common failures. The *why* lives in
> [`architecture.md`](./architecture.md); the *contracts* in [`contracts.md`](./contracts.md);
> the *plan* in [`../../implementation/v1/testing/plan.md`](../../implementation/v1/testing/plan.md).
> This doc is the *how*.
>
> **Status.** Created 2026-06-20. Reflects testing arc through Stage 2.3 (component tier live on
> every PR/merge; integration tier code-complete, first live nightly pending).

---

## 1. The tier ladder at a glance

| Tier | What it runs | Real deps? | Invoke | Gate |
|---|---|---|---|---|
| **unit + in-proc** | class/object boundary; Ktor `testApplication`; full Koog graph, **mocked** collaborators | no | `just test-all` | every PR + merge |
| **component (real-dep)** | one service (or a few) vs **real** backing deps in Testcontainers; **no cluster** | yes | `just test-component [<module>]` | every PR + merge |
| **integration (cluster)** | the **real forked constellation** wired end-to-end on a cluster | yes | `:integrationTest -Pcontext=<name>` (driven by CI / `infra-up`) | nightly + release tags |

Vocabulary (ratified 2026-06-19): "**component**" = the real-dep Testcontainers tier; "**integration**"
= the full constellation on a cluster. The older in-process `testApplication` specs are *mocked* and
run with **unit** in `test-all`. See architecture §2.1.

---

## 2. Running the tiers locally

### 2.1 Unit + in-proc (mocked) — the PR gate

```bash
just test-all                  # ./gradlew test — every module, mocked only
just test-kt services/theseus  # one module's unit/in-proc specs
```

`test-all` is guaranteed mocked-only: a build-level guard fails the `test` task if any
component/integration class leaks onto its classpath (planning-conventions §4).

### 2.2 Component (real-dep, Testcontainers) — needs Docker

```bash
just test-component                  # every module's componentTest
just test-component workers/brontes  # one module
```

Requirements + caveats:

- **A running Docker daemon** (Testcontainers boots real Postgres / WireMock / MSSQL containers).
- **MSSQL specs are CI-only.** The SQL Server image is amd64-only (no native ARM64), so
  `BrontesMssqlComponentSpec` is gated with `@EnabledIf(CiOnly::class)`: it **runs in CI** (native
  amd64 runner), is **skipped** on Apple Silicon `just test-component`, and can be **forced locally**
  under emulation with `-DmssqlLocal`:
  ```bash
  ./gradlew :workers:brontes:componentTest -DmssqlLocal   # slow amd64 emulation; rarely needed
  ```
- Postgres / WireMock specs are native multi-arch and run fine on the laptop.

### 2.3 Python tiers

```bash
just test-py services/metis                    # all tests
just test-py services/metis -m component       # real-dep component tier (testcontainers-python)
just test-py services/metis -m "not component" # mocked unit tier only
```

### 2.4 Integration (cluster) — local iteration against a live context

The integration suite **skips** unless a context is named, so `./gradlew check` never needs a
cluster. To run it against a real context you stand the context up **once** (olymp), then re-run the
suite against the warm namespace as many times as you like:

```bash
OLYMP=~/Dev/collite-gh/olymp
RUN=local-$USER

# 1. Bring the context up (olymp owns this — scripted, NON-ArgoCD). The LAST stdout line is the
#    namespace handshake; everything else goes to stderr.
NS=$(just -f $OLYMP/justfile infra-up theseus-runquery $RUN bp-olymp01 \
       --kantheon "$PWD" --ghcr-from argocd/ghcr-pull | sed -n 's/^namespace=//p')
echo "context namespace: $NS"

# 2. Run the suite against it (re-runnable while the context stays up).
./gradlew :tools:theseus-mcp:integrationTest -Pcontext=theseus-runquery -Pnamespace=$NS

# 3. Tear it down when done (idempotent / safe-if-absent).
just -f $OLYMP/justfile infra-down theseus-runquery $RUN bp-olymp01
```

The `@RequiresContext` gate resolves the namespace from `-Pnamespace` (or, if omitted, by the
`olymp.collite/context=<name>` label), **derives** readiness from the namespace's workloads (every
Deployment/StatefulSet Available + Job Complete), and **fails fast** if anything is not Ready — it
never provisions. See contracts §4.2.

---

## 3. The olymp CLI (the cross-repo seam)

Olymp owns bring-up/teardown; kantheon only references a context **by name** and reads the namespace
handshake (the entire coupling is one string — contracts §2). The real signature:

```
just infra-up   <context> <run-id> <kube> [--kantheon <path>] [--ghcr-from <ns>/<secret>] [--dry-run]
just infra-down <context> <run-id> <kube>
just contexts                                  # list registered context names
```

- `<kube>` — **required positional**: the kubectl context name on the host (e.g. `bp-olymp01`).
- `--kantheon <path>` — **required when services are present**: the kantheon checkout the charts
  render from (olymp holds no chart copy, D3′). In CI this is the job workspace.
- `--ghcr-from <ns>/<secret>` — copies an existing in-cluster dockerconfig into the run namespace for
  private image pulls (on `bp-olymp01`: `argocd/ghcr-pull`).
- `--dry-run` — print the plan (helm/kubectl commands + the handshake) without touching a cluster.
  Use it to validate a context manifest against a kantheon checkout:
  ```bash
  just -f $OLYMP/justfile infra-up theseus-runquery dryrun bp-olymp01 --kantheon "$PWD" --dry-run
  ```

Namespace-per-run: each `infra-up` deploys into `kantheon-<context>-<run-id>`, labelled
`olymp.collite/context`, `olymp.collite/run`, `olymp.collite/managed-by`. Concurrent runs with
distinct run-ids never collide. `infra-down` deletes the namespace.

---

## 4. The integration nightly (CI)

`.github/workflows/integration-nightly.yml` runs the full loop on `schedule` (03:00 UTC),
`push` to release tags (`*/v*`), and manual `workflow_dispatch`. Per run:

```
checkout kantheon (SHA under test) + checkout Collite/olymp
trap 'infra-down …' EXIT                      # teardown ALWAYS runs
NS = infra-up theseus-runquery <run> bp-olymp01 --kantheon $PWD --ghcr-from argocd/ghcr-pull
./gradlew :tools:theseus-mcp:integrationTest -Pcontext=theseus-runquery -Pnamespace=$NS
# on failure → open/refresh a tracked issue (label: integration-nightly); teardown still fires
```

It runs on a **self-hosted runner** tagged `[self-hosted, olymp-test]` (needs kube access to the
test cluster + `just`/`helm`/`kubectl`/`python3`). A red nightly is a *tracked issue*, not a
retroactive block on the window's merges (architecture §8).

### Manual trigger

```bash
gh workflow run integration-nightly.yml --repo BoraPerusic/kantheon --ref <branch>
gh run watch --repo BoraPerusic/kantheon
```

> **Heads-up:** the `theseus-runquery` context references the `theseus-mcp` chart + its values, so
> the matching olymp change must be merged before a green run — otherwise `infra-up` fails on the
> missing chart entry.

---

## 5. One-time setup

### 5.1 T3 — the olymp checkout credential

The nightly checks out the **private** `Collite/olymp` repo, which the built-in `GITHUB_TOKEN`
can't read (different owner). Provide a dedicated token:

1. **Create a read-only token for `Collite/olymp`.** github.com → Settings → Developer settings →
   Personal access tokens → **Fine-grained tokens** → Generate:
   - Resource owner: `Collite`; Repository access: only `Collite/olymp`.
   - Permissions → Repository → **Contents: Read-only** (all `actions/checkout` needs).
   - Set an expiry + a renewal reminder. Copy the `github_pat_…` value.
   *(A classic PAT with the `repo` scope also works, but fine-grained + Contents:Read is least-privilege.)*

2. **Add it as a repo secret** on `BoraPerusic/kantheon` named `OLYMP_GITHUB_TOKEN`:
   ```bash
   gh secret set OLYMP_GITHUB_TOKEN --repo BoraPerusic/kantheon   # prompts for the value
   ```

3. **(Optional) Override the cluster/pull defaults** — only if your runner's context name or pull
   secret differs from `bp-olymp01` / `argocd/ghcr-pull`:
   ```bash
   gh variable set OLYMP_KUBE_CONTEXT --repo BoraPerusic/kantheon --body bp-olymp01
   gh variable set OLYMP_GHCR_FROM    --repo BoraPerusic/kantheon --body argocd/ghcr-pull
   ```

4. **Verify**: trigger the workflow (§4) — T3 is satisfied once the *Checkout olymp* step succeeds.
   (The `infra-up` step still needs the runner of §5.2.)

### 5.2 The self-hosted runner

The integration job needs a runner tagged `[self-hosted, olymp-test]` with kube access to the test
cluster and `just`/`helm`/`kubectl`/`python3` on PATH. Any host with the `bp-olymp01` kube-context
qualifies (a dev Mac works to get the first green run; an always-on box is better for true nightly).

Register one (run in a normal terminal — the last command stays in the foreground):

```bash
mkdir -p ~/actions-runner && cd ~/actions-runner

# Download + extract (macOS arm64 shown; pick the matching package for your host OS/arch)
curl -o runner.tar.gz -L https://github.com/actions/runner/releases/download/v2.335.1/actions-runner-osx-arm64-2.335.1.tar.gz
tar xzf runner.tar.gz

# Register with the olymp-test label ("self-hosted" is implicit). Token is short-lived.
TOKEN=$(gh api -X POST repos/BoraPerusic/kantheon/actions/runners/registration-token -q .token)
./config.sh --url https://github.com/BoraPerusic/kantheon --token "$TOKEN" \
  --labels olymp-test --name olymp-test-mac --unattended --replace

# Start it — leave this terminal open
./run.sh
```

Within seconds of "Listening for Jobs", a queued `integration-nightly` job moves to *running*.

- **Run as a background service** (survives terminal close / reboot) instead of `./run.sh`:
  ```bash
  ./svc.sh install && ./svc.sh start
  ```
  Caveat: launchd services may not inherit your Homebrew PATH — if `just`/`helm` aren't found, use
  the foreground `./run.sh`, or set the PATH in the service plist.
- **Why it must be self-hosted:** GitHub-hosted runners can't reach the private test cluster. The
  runner inherits the host's `~/.kube/config`, so `kubectl --context bp-olymp01` resolves.
- **Inspect/remove:**
  ```bash
  gh api repos/BoraPerusic/kantheon/actions/runners -q '.runners[] | {name, status, labels:[.labels[].name]}'
  cd ~/actions-runner && ./config.sh remove --token "$(gh api -X POST repos/BoraPerusic/kantheon/actions/runners/remove-token -q .token)"
  ```

---

## 6. Adding a new context

Coverage grows one context at a time (plan.md Phase 3). Each context is **two repos**:

- **olymp** owns the deployment — `test-contexts/<name>/context.yaml` (the `TestContext` manifest:
  `services[]` with `chartPath`/`chartRevision`/`values`, `platform[]`, `readiness[]`) + per-service
  `*.values.yaml`. See olymp `docs/test-harness.md` §5.
- **kantheon** owns the assertion code + fixtures — an `@RequiresContext("<name>")` spec in the
  owning module's `src/integrationTest/kotlin`, plus any WireMock fixtures under
  `src/integrationTest/resources/wiremock/<name>/<scenario>/`.

The cross-repo drift guard (`ContextNameRegistrySpec`, component tier) asserts every
`@RequiresContext` name resolves to an olymp `test-contexts/<name>/context.yaml` when run with
`-PolympDir=<checkout>` (the nightly does this; it skips on a plain PR run).

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `integrationTest` reports *SKIPPED* / no tests | no `-Pcontext` given | expected locally; pass `-Pcontext=<name> -Pnamespace=<ns>` (§2.4) |
| Gate throws `Integration context '…' is not ready: namespace '…' has no Deployments/StatefulSets/Jobs` | wrong namespace resolved, or `infra-up` didn't install the workloads | check `kubectl get ns -l olymp.collite/context=<name>` and the `infra-up` logs |
| Gate throws `… not ready: <workloads>` | a Deployment isn't Available / a Job didn't Complete | `kubectl -n <ns> get deploy,job`; inspect the failing pod's logs |
| `infra-up` fails on a missing chart | the olymp context references a chart not in the kantheon checkout (e.g. theseus-mcp before the chart landed) | merge the matching kantheon + olymp changes; re-run |
| MSSQL component spec fails locally on Apple Silicon | amd64-only image under emulation | it's CI-only by design — don't run it locally (or accept slow `-DmssqlLocal`) |
| `just test-component` errors with no containers | Docker daemon not running | start Docker / Rancher Desktop |
| Job stuck *waiting for a runner* | no online runner tagged `olymp-test` | register one (§5.2) |
| Leaked `kantheon-<ctx>-<run-id>` namespace after a crash | a CI job skipped its teardown trap | `just -f $OLYMP/justfile infra-down <ctx> <run-id> <kube>`; the olymp reaper (Phase B) is the backstop |

---

*Doc owner: Bora. Created 2026-06-20. Update as the arc adds contexts / changes the harness.*
