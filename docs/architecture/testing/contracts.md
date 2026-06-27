# Testing — Contracts (component + integration arc)

> **Scope.** The wire/interface contracts for the test harness: the **context manifest** schema (olymp-owned), the **cross-repo context-name** contract, the **WireMock fixture layout + admin-API load protocol**, the **Gradle task / JUnit tag surface**, and the **readiness-gate annotation**. Source of truth for the kantheon ↔ olymp boundary in testing.
>
> **Reads with.** [`./architecture.md`](./architecture.md), olymp [`docs/test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md) (the olymp side of these contracts), [`../../../AGENTS.md`](../../../AGENTS.md) §1.4/§8, [`../../../EXAMPLES.md`](../../../EXAMPLES.md) §9 (the WireMock fixture pattern this extends).
>
> **Status.** Planned 2026-06-19. Schemas are the target; field names are normative for the task lists.

---

## 1. Context manifest (olymp-owned)

A context is one directory under olymp `test-contexts/<name>/` (exact path ratified olymp-side — see olymp `docs/test-harness.md`). The manifest:

```yaml
# olymp: test-contexts/theseus-runquery/context.yaml  (the ratified shape — olymp test-harness.md §5)
apiVersion: olymp.collite/v1
kind: TestContext
metadata:
  name: theseus-runquery          # THE cross-repo contract key (§2)
services:                         # first-party kantheon charts (D3′ multi-source, scripted)
  - name: theseus                 # == release name == workload name
    chartPath: services/theseus/k8s   # path under the kantheon monorepo → the env-agnostic Helm chart
    chartRevision: testing            # git ref to pin (the nightly renders the checked-out SHA)
    values: theseus.values.yaml       # context-scoped values file, resolved relative to this dir
  - { name: theseus-mcp, chartPath: tools/theseus-mcp/k8s, chartRevision: testing, values: theseus-mcp.values.yaml }
  - { name: proteus, chartPath: services/proteus/k8s, chartRevision: testing, values: proteus.values.yaml }
  - { name: argos,   chartPath: services/argos/k8s,   chartRevision: testing, values: argos.values.yaml }
  - { name: kyklop,  chartPath: services/kyklop/k8s,  chartRevision: testing, values: kyklop.values.yaml }
  - { name: brontes, chartPath: workers/brontes/k8s,  chartRevision: testing, values: brontes.values.yaml }
platform:                         # olymp platform/ components this context requires (bare names)
  - mssql
  - wiremock                      # started EMPTY; fixtures pushed by the suite at runtime (§3)
seed: []                          # optional one-shot seed jobs; theseus-runquery uses the mssql base init-job
readiness:                        # what `infra-up` waits on (the kantheon gate DERIVES its own — §4.2)
  - { kind: Deployment, name: theseus, condition: Available }
  - { kind: Job, name: mssql-init, condition: Complete }
```

Field rules:

- `metadata.name` is the **only** identifier that crosses into kantheon. It must match the `@RequiresContext` value (§4) exactly.
- `services[].chartPath` is a monorepo path to the env-agnostic Helm chart (D3′); `chartRevision`/`values` mirror the D20 `config.json` keys verbatim. `infra-up` renders it with `values` and the run id, then `helm upgrade --install` — **no ArgoCD**. (This replaces the earlier `module:`/`ref:` shorthand — reconciled to olymp's ratified schema, test-harness.md §5.)
- `chartRevision` pins the integration branch; the nightly checks out the SHA under test and renders from that checkout (`--kantheon <path>`), so it tests the merged tree.
- `platform[]` entries are bare component names resolved to `platform/<area>/<name>` olymp-side. Composition is olymp's; kantheon never enumerates it.
- The whole file is consumed only by olymp's `infra-up`. Kantheon reads **none** of it — it knows `metadata.name` and nothing else.

---

## 2. The cross-repo contract: the context name

The entire kantheon ↔ olymp testing coupling is **one string**.

| Direction | Carrier | Meaning |
|---|---|---|
| kantheon → olymp | `-Pcontext=<name>` (Gradle) → `just infra-up <name>` (olymp) | "stand up the environment named X" |
| olymp → kantheon | the live namespace `kantheon-<name>-<run-id>` + Ready endpoints | "X is up; here is where" |
| kantheon internal | `@RequiresContext("<name>")` on the suite | "this suite needs X" |

Invariants:

- A name present in `@RequiresContext` **must** have a matching `test-contexts/<name>/` in olymp. CI fails fast (and a kantheon-side lint, `ContextNameRegistrySpec`, can assert every referenced name resolves against a checked-out olymp — plan.md Stage 1).
- Kantheon never parses the manifest, never lists services, never names a namespace template. It receives the resolved namespace via `-Pnamespace=` (set by the CI wrapper from `infra-up`'s output) or discovers it by the `olymp.collite/context=<name>,run=<id>` label selector.

---

## 3. WireMock fixtures — layout + load protocol

### 3.1 Layout (kantheon, versioned with the suite)

```
<module>/src/test/resources/wiremock/<context>/<scenario>/
  mappings/         # WireMock stub mappings (one JSON per stubbed interaction)
    prometheus-llm-ok.json
    modeler-ttr-ok.json
  __files/          # response bodies referenced by mappings (optional)
    completion.json
```

This extends the existing convention (`src/test/resources/fixtures/<service>/<scenario>.json`, EXAMPLES.md §9) — same JSON shape, namespaced by context + scenario so one WireMock instance serves many suites without collision.

### 3.2 Load protocol (kantheon, at suite setup against the in-cluster instance)

```
# resolve the in-cluster WireMock admin base (from the context namespace)
ADMIN = http://wiremock.<ns>.svc:8080/__admin

# per scenario:
POST  {ADMIN}/reset                          # clear prior mappings + request journal
POST  {ADMIN}/mappings/import                # body = concatenated scenario mappings
# … run assertions …
GET   {ADMIN}/requests                        # optional: verify expected calls were made
```

Rules:

- **Reset between scenarios**, always — no fixture leaks across suites sharing the instance.
- Fixtures are pushed by the **test**, never pre-baked into olymp's WireMock image (the image ships empty). This keeps fixtures versioned with the assertions that depend on them.
- The same `com.github.tomakehurst.wiremock.client.WireMock` client used in-process (EXAMPLES.md §9) targets the in-cluster admin endpoint for integration; only the base URL differs.

---

## 4. Gradle / JUnit surface (kantheon)

### 4.1 Tags + tasks

| Tier | JUnit tag | Gradle task | `just` recipe | Source location |
|---|---|---|---|---|
| unit + in-proc component | *(untagged — default)* | `test` | `test-all` / `test-kt <m>` | `src/test/kotlin` |
| component (real-dep) | `@Tag("component")` | `componentTest` | `test-component [<m>]` | `src/componentTest/kotlin` (own source set) |
| integration (cluster) | `@Tag("integration")` | `integrationTest` | *(driven by CI / `infra-up`)* | `src/integrationTest/kotlin` (own source set) |

- `test` (the PR gate) **excludes** `component`/`integration` source sets, so `just test-all` stays mocked-only per planning-conventions §4 — the policy is preserved verbatim.
- `componentTest` depends on a running Docker daemon (Testcontainers); it does **not** depend on `infra-up` or any cluster.
- `integrationTest` accepts `-Pcontext=<name>` and (from CI) `-Pnamespace=<ns>`; with neither it **skips** (so a local `./gradlew check` never accidentally needs a cluster).
- Python mirror: pytest markers `component` / `integration`; `just test-py <m> -m component`.

### 4.2 The readiness-gate annotation

```kotlin
@RequiresContext("theseus-runquery")
class RunQueryIntegrationSpec : StringSpec({
    // beforeSpec: the gate asserts the context is up; FAIL FAST otherwise — never self-deploy.
})
```

`@RequiresContext(name)` contract:

- Resolves the context namespace from `-Pnamespace` or the `olymp.collite/context=<name>` label.
- **beforeSpec**: **derive** the readiness set from what the namespace actually runs — every Deployment / StatefulSet (Available) + every Job (Complete) — and assert all are Ready. The gate reads **no handshake annotation**; the only cross-repo surface is the two ns labels (§6), and olymp already blocks on its own `readiness[]` before printing the namespace, so this is a defense-in-depth re-assertion that needs nothing extra from olymp. On failure → throw immediately with a message naming the context and the unready workloads. **No provisioning, no retry-deploy** — a down context is a CI/harness error.
- Exposes resolved in-cluster service URLs (incl. the WireMock admin base) to the spec via a small `ContextHandle` injected through the Kotest extension.
- Suites with the *same* `@RequiresContext` value are batched by CI to share one `infra-up`.

---

## 5. CI orchestration contract

### 5.1 `ci.yml` (existing — PR + merge), one added step

```
init → lint → test-all → test-component
```

`test-component` runs on the same ubuntu runner (Docker available for Testcontainers). No olymp checkout, no cluster.

### 5.2 `integration-nightly.yml` (new — schedule + release tag)

```yaml
on:
  schedule: [{ cron: "0 3 * * *" }]
  push:     { tags: ["*/v*"] }            # release tags
jobs:
  integration:
    steps:
      - checkout kantheon                  # the SHA under test
      - checkout olymp (Collite/olymp)     # the harness + contexts
      - for each context in the run set:
          - just -f <olymp> infra-up   <ctx>           # NON-ArgoCD scripted bring-up
          - ./gradlew :integrationTest -Pcontext=<ctx> -Pnamespace=<ns-from-up>
          - just -f <olymp> infra-down <ctx>           # in trap/finally — ALWAYS runs
```

- Teardown is in a `trap`/`finally`; a crashed suite still tears its namespace down (a label/age reaper sweeps anything that slips through).
- The run set starts as `{ theseus-runquery }` and grows per plan.md as arcs reach the cluster.
- Olymp checkout needs the `Collite/olymp` read credential (private repo) — supplied as a CI secret, distinct from the standing `Collite/modeler` packages PAT (CLAUDE.md §7.3).

---

## 6. Open contract points (for Bora)

1. **`test-contexts/` path in olymp** — proposed `test-contexts/<name>/`; could instead live as `clusters/ci/contexts/<name>/` if the test cluster is modelled as a cluster entry. Ratified in olymp `docs/test-harness.md`.
2. **Namespace handshake** — `infra-up` must emit the resolved namespace for the Gradle `-Pnamespace`. Proposed: `infra-up` prints `namespace=<ns>` on stdout (machine-readable last line) and labels the ns `olymp.collite/context`,`olymp.collite/run`.
3. **Nightly run-set source** — who owns the list of contexts nightly runs: a file in olymp (`test-contexts/_nightly.txt`) vs. enumerate all `test-contexts/*`. Proposed: enumerate all, with an opt-out label for WIP contexts.

---

*Doc owner: Bora. Created 2026-06-19. Field names here are normative for the testing arc task lists.*
