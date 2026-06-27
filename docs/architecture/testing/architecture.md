# Testing — Solution Architecture (component + integration arc, cross-cutting)

> **Scope.** The cross-cutting architecture for the two test tiers kantheon has been deferring: **component** (service against real backing dependencies, no Kubernetes) and **integration** (the real forked constellation on a cluster). It defines the tier ladder, the **olymp ↔ kantheon ownership split**, the named-context model, the WireMock fixture flow, namespace-per-run isolation, and CI gating. It does **not** restate the existing in-process unit/component specs — those keep running in `just test-all` unchanged (§2).
>
> **Reads with.** [`./contracts.md`](./contracts.md) (context manifest schema, fixture protocol, Gradle/tag surface), [`./howto.md`](./howto.md) (the operational runbook — running each tier, the nightly/runner/CI-secret setup, troubleshooting), [`../../implementation/v1/testing/plan.md`](../../implementation/v1/testing/plan.md), [`../../implementation/planning-conventions.md`](../../implementation/planning-conventions.md) §4 (the mocked-only stage policy this arc complements), [`../../../AGENTS.md`](../../../AGENTS.md) §1.4 + §8 (test stack), and **olymp** [`docs/test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md) + `docs/decisions.md` D3′ (the chart/values split this arc builds on).
>
> **Status.** Planned 2026-06-19 (Bora). Supersedes the placeholder "on-cluster bring-up is the integration-track's job" notes scattered in `deployment/local/README.md` and per-stage task lists.

---

## 1. The problem this arc closes

Every v1 implementation stage develops against **mocked unit tests only** (planning-conventions §4): MockK / in-memory fakes / Wiremock-as-stub, all collaborators isolated, nothing real behind the boundary. That was a deliberate deferral — it kept the arcs moving without a cluster in the loop. The bill now comes due: nothing yet exercises a service against a **real** Postgres, a **real** MSSQL, a real peer service, or the **whole forked constellation wired end-to-end**. This arc designs that missing verification, as the **separate suite** planning-conventions §4 always pointed to.

Two non-negotiable constraints shape every decision below:

1. **The D3′ boundary holds.** Per olymp `decisions.md` D3′, kantheon owns the **env-agnostic Helm chart template** per service (`<module>/k8s`) and nothing about deployment topology; olymp owns clusters, per-cluster values, and how services compose into an environment. **Kantheon must never learn a cluster.** The test harness may not smuggle deployment knowledge back into the monorepo.
2. **The stage policy is untouched.** This arc adds tiers *above* the mocked stage gate. It does not change what an implementation stage's DONE criteria are.

---

## 2. The tier ladder

Four rungs. The bottom two already exist and are **not** what this arc builds; they are listed so the boundary is unambiguous. The top two are the deliverables.

| Rung | What runs | Real dependencies? | Where | CI gate | Status |
|---|---|---|---|---|---|
| **unit** | class / object boundary | none — MockK, in-memory fakes | in-process JVM | every PR (`just test-all`) | exists |
| **component (in-proc)** | one service, Ktor `testApplication`, full Koog graph | none — collaborators stubbed (Wiremock-as-stub, mock gRPC/S3/Redis/DB) | in-process JVM | every PR (`just test-all`) | exists (AGENTS.md §8) |
| **component (real-dep)** | a service — or a small cluster of services — against its **real direct dependencies** | **yes**, via Testcontainers: real Postgres / MSSQL / Redis / SeaweedFS / a WireMock container / a real peer container where cheap | in-process JVM driver, **no Kubernetes** | **every PR + merge** (`just test-component`) | **this arc** |
| **integration (cluster)** | the **real forked constellation** wired end-to-end | **yes** — real services on a real cluster; WireMock only for true externals (LLM upstream, Collite/modeler, third-party) | **Kubernetes** (olymp-owned, non-ArgoCD, scripted) | **nightly + release tags** | **this arc** |

### 2.1 Vocabulary-canon delta — needs Bora's ratification

There is a **naming collision** to resolve. AGENTS.md §8 already calls the in-process Ktor-`testApplication` tier "**component**." This arc reclaims "component" for the **real-dependency Testcontainers tier** (the framing agreed 2026-06-19: *"component on each PR and merge, integration nightly"*). The collision is resolved as follows and should be ratified into AGENTS.md §8 + the CLAUDE.md vocabulary canon:

- The existing in-process Ktor `testApplication` specs are **mocked** and **in-process** — they belong with **unit** on the `just test-all` PR gate. They keep their `*Spec.kt` names and their location; only the *label* "component" stops referring to them at the arc level.
- "**Component**" now canonically means **real-dependency, Testcontainers, no-cluster**. "**Integration**" means **full constellation on a cluster**. This matches the two-tier split Bora agreed to.

Until ratified, the two new Gradle tasks (`test-component`, `:integrationTest`) carry the meaning regardless of the prose label, so nothing is blocked on the rename.

### 2.2 Why a no-cluster component tier at all

Most "does it really work" bugs surface at the *first* real dependency — the SQL dialect Brontes emits against a real MSSQL, the ADBC round-trip in Charon against a real Postgres, the actual MCP wire framing between two real wrappers. Those do not need fifteen pods on K3s; they need *one or two* real things in containers, in-process, fast enough to gate every PR. Reserving the cluster for true end-to-end keeps the heavy, slow, flaky tier rare (nightly) and the high-value tier frequent (every PR). This is the split Bora ratified on 2026-06-19.

---

## 3. The ownership split (the load-bearing decision)

The harness straddles two repos. The D3′ boundary dictates the cut precisely:

```
                 KANTHEON (code · contracts · test logic)        OLYMP (deployment · cluster · topology)
                 ────────────────────────────────────────        ────────────────────────────────────────
 chart template  <module>/k8> Helm chart (env-agnostic) ────────> consumed by infra-up (rendered with ctx values)
 component tier   Testcontainers specs + @Tag("component")        — (no involvement; runner-local)
 integration tier @Tag("integration") + @RequiresContext("…")     just infra-up/down <context>  (scripted, NON-ArgoCD)
 context identity  knows a context *name* (a string) ───────────> defines what that name contains (services+infra)
 stubs            WireMock fixture JSON (lives with the test) ──> WireMock *deployment* (a platform component)
 CI orchestration nightly workflow: checkout olymp → up → test → down
```

**Kantheon owns** (lives with the code that changes it):

- the per-service Helm chart templates (already, D3′ — unchanged by this arc);
- the **test taxonomy + Gradle wiring** — JUnit `@Tag`s, source sets, `just test-component`, the `:integrationTest` task, the readiness gate;
- the **component tier in full** — Testcontainers specs; zero olymp involvement, runner-local;
- the **integration test code** and the **WireMock fixtures** (mapping JSON, versioned next to the test that owns them);
- a **context *name*** per integration suite — a string, nothing more (`@RequiresContext("theseus-runquery")`);
- the **CI orchestration** that wires the two repos together.

**Olymp owns** (deployment is olymp's, including test-infra deployment):

- a new **non-ArgoCD, scripted cluster mode** and the **CI/test cluster** itself (§5);
- `just infra-up <context>` / `just infra-down <context>` — the scripted helm-install + kubectl-apply (no ArgoCD reconciler in the loop);
- the **named-context registry** — the declarative manifest defining *what each context contains* (which charts at which values, which platform deps, the WireMock instance), with namespace-per-run templating;
- the **WireMock deployment** as a `platform/` component (olymp runs infra; the running instance is infra).

**The contract between the repos is the context name.** Kantheon declares a name; olymp defines its contents. Kantheon thereby knows a *string* and a *set of HTTP stubs* — never topology. D3′ stays intact.

> **Consolidation note (surfaced per Bora's standing preference).** Bora's first sketch put `just infra-up <name>` in kantheon. The D3′ boundary forces the opposite: bring-up/teardown is deployment, so it lives in **olymp**. Kantheon's harness *references* a context by name and asserts readiness; it never holds kubectl/helm-apply logic for the constellation. This is the rules-first cut — one place owns deployment, and it is olymp.

---

## 4. The named context

A **context** is a declarative, versioned description of an environment a test suite needs. It is the unit `just infra-up <name>` operates on. Full schema in [`contracts.md`](./contracts.md) §1; the shape:

```
context: theseus-runquery
services:                       # kantheon charts, pinned, with context-scoped values
  - { module: services/theseus,  ref: <git-ref>, values: theseus.values.yaml }
  - { module: services/proteus,  ref: <git-ref>, values: proteus.values.yaml }
  - { module: services/argos,    ref: <git-ref>, values: argos.values.yaml }
  - { module: services/kyklop,   ref: <git-ref> }
  - { module: workers/brontes,   ref: <git-ref> }
platform:                       # olymp platform/ deps this context needs
  - mssql
  - wiremock                    # the stub instance; fixtures injected by the suite at runtime
seed: [ mssql-northwind ]       # optional seed jobs
```

Properties:

- **Declarative, not scripted.** `infra-up` reads the manifest and acts; suites never hand-roll kubectl. (Bora, 2026-06-19.)
- **Lives in olymp** (it is deployment composition). Named contexts: `themis-routing`, `theseus-runquery`, `golem-erp`, `pythia-rca`, … one per coherent integration scenario.
- **Namespace-per-run** (§4.1).
- **WireMock is a first-class member of every context** — it stubs exactly the things the context deliberately does *not* deploy (LLM upstream via Prometheus, Collite/modeler, any third-party). The *running instance* is olymp's; the *fixtures* are the suite's (§6).

### 4.1 Namespace-per-run isolation

A context is a **template instantiated per run**, not a fixed namespace. `infra-up theseus-runquery` deploys into `kantheon-theseus-runquery-<run-id>` (Helm release name + namespace carry the run id). Consequences:

- parallel CI runs and concurrent local iteration never collide;
- teardown is `kubectl delete namespace …` (or `helm uninstall` + ns delete) — total, no residue;
- a crashed suite leaves an identifiable namespace a reaper can sweep by label/age.

Since the cluster is **non-ArgoCD**, nothing fights the scripted lifecycle — there is no reconciler trying to re-sync or prune what the harness just tore down. (This is precisely why the test cluster must not be ArgoCD-driven.)

---

## 5. The cluster (olymp side)

Integration runs need a real Kubernetes cluster that is **scripted, not reconciled**. Olymp's existing clusters (`local`, `bp-dsk`, …) are ArgoCD tenants (D11) — wrong tool for ephemeral, scripted bring-up/teardown. This arc adds a **new cluster *mode*** to olymp, not just another tenant:

- **Persistent "olymp testing server"** — a long-lived cluster reserved for nightly integration, with ArgoCD **absent or idle**; `infra-up/down` drives it directly. Namespace-per-run keeps nightly runs (and any ad-hoc manual runs) isolated. Recommended primary.
- **Ephemeral k3d-in-runner** — `infra-up` stands a throwaway k3d cluster inside the GitHub Actions runner, deploys the context, tears the whole cluster down at job end. Good fallback / for forks without access to the server; pays a per-run cluster-boot + image-build cost.

Recommendation: **persistent server for nightly**, **ephemeral k3d as the documented fallback**. Both run the *same* `infra-up <context>` recipe — the cluster mode is a target, not a different code path. Image supply (the ~15 forked images) is the real cost driver; the persistent server keeps a warm image cache / local registry so nightly does not rebuild from scratch (plan.md Phase 3).

> Olymp-side specifics (the cluster entry, the recipes, the WireMock platform component, the proposed decision number and plan phase) are in **olymp** [`docs/test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md). This doc owns the kantheon side and the cross-repo contract only.

---

## 6. WireMock fixture flow

Decision (Bora, 2026-06-19): **fixtures are part of the test suite and loaded at runtime — not baked into an image.**

- **Deployment** (olymp): one WireMock instance per context, reachable in-cluster (e.g. `wiremock.<ns>.svc`). Started empty.
- **Fixtures** (kantheon): mapping JSON under `src/test/resources/wiremock/<context>/<scenario>/…`, versioned with the suite that owns them (this extends the existing fixture convention in EXAMPLES.md §9 and AGENTS.md §8).
- **Load protocol** (kantheon, at suite setup): `POST /__admin/mappings/import` to push the scenario's mappings; `POST /__admin/reset` between suites/scenarios to prevent cross-talk. Full protocol in [`contracts.md`](./contracts.md) §3.

The payoff: one running WireMock serves many scenarios without redeploy, fixtures live beside the test that asserts on them, and swapping a scenario is an admin-API call — not a kustomize edit + ArgoCD sync. The same `WireMockServer` pattern from EXAMPLES.md §9 (used in-process for the unit tier) is reused against the in-cluster instance for integration.

---

## 7. Harness lifecycle — who starts what

Decision (Bora, 2026-06-19): **the harness owns bring-up/teardown; the suite declares a dependency and fails fast.** Deploy/teardown is explicitly **not** inside the JVM test process.

```
  CI (nightly)                         olymp recipe                  kantheon :integrationTest
  ─────────────                        ────────────                  ─────────────────────────
  checkout kantheon + olymp
  for each context C in the run set:
     just -f olymp infra-up C  ───────> render charts@values + apply
                                        platform deps + WireMock; wait Ready
     ./gradlew :integrationTest \
        -Pcontext=C            ──────────────────────────────────>  @RequiresContext("C") gate:
                                                                     assert ns Ready + endpoints live
                                                                     → FAIL FAST if not (no self-deploy)
                                                                     load WireMock fixtures, run @Tag(integration)
     just -f olymp infra-down C  (in a trap/finally — always runs)
```

Rationale for harness-owned lifecycle over suite-owned:

- **Warm reuse** — suites sharing a context run against one bring-up; no per-suite cluster churn.
- **Reliable teardown** — a `trap`/`finally` in the orchestrator tears down even when the JVM suite crashes; a self-deploying suite that segfaults leaks a namespace.
- **Local iteration** — a developer runs `infra-up theseus-runquery` once and re-runs the suite twenty times against the warm context.
- **Fail-fast, not self-heal** — the suite *asserts* its context is up (namespace Ready, endpoints answering) and aborts immediately if not. It never tries to provision. A missing context is a harness/CI error, surfaced loudly, not papered over.

---

## 8. CI gating

| Trigger | unit | component (in-proc) | **component (real-dep)** | **integration (cluster)** |
|---|---|---|---|---|
| **PR / branch** | ✓ `just test-all` | ✓ `just test-all` | ✓ `just test-component` | ✗ |
| **merge → main** | ✓ | ✓ | ✓ `just test-component` | ✗ |
| **nightly** | ✓ | ✓ | ✓ | ✓ `infra-up → :integrationTest → infra-down` |
| **release tag** | ✓ | ✓ | ✓ | ✓ (full context set) |

- **Component (real-dep) on every PR + merge.** Testcontainers runs in the GH runner with no cluster; cost is container pulls + boot, acceptable per-PR. This is where the agreed *"component on each PR and merge"* lands.
- **Integration nightly (+ release).** Heavy: cluster + ~15 images + end-to-end wiring. Running it per-merge would throttle main velocity (image build alone is minutes — `deploy-fork` builds ~15 images). Nightly + release-tag gating keeps main fast while still catching cross-service regressions within a day. A red nightly opens a tracked issue; it does not retroactively block the merges in that window (judgment call, flagged for Bora — alternative is a merge-queue-gated heavier run).
- **Existing `ci.yml`** (`init → lint → test-all`) gains a `test-component` step on the same workers; the nightly integration job is a **separate workflow** (`integration-nightly.yml`, schedule trigger) that checks out olymp and drives `infra-up/down`.

---

## 9. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Test runner | **Kotest** (`StringSpec`, `*Spec.kt`) | repo idiom (AGENTS.md §1.4); same runner across all tiers |
| Real-dep provisioning (component) | **Testcontainers** (Postgres, MSSQL, Redis, GenericContainer for WireMock/SeaweedFS) | already the chosen integration mechanism (AGENTS.md §1.4); version-pinned in `libs.versions.toml` (`testcontainers = "2.0.3"`) |

> **Mac Silicon / MSSQL caveat.** The official SQL Server image is **amd64-only** (no native ARM64; Azure SQL Edge — the former ARM64 fallback — was retired 2025-09-30). Postgres/Redis/WireMock ship native multi-arch images and run fine on Apple Silicon. The **MSSQL** real-dep spec (`BrontesMssqlComponentSpec`) is therefore **CI-only** — gated on the `CI` env var so it never runs on the dev laptop (where it would need slow amd64 emulation) and always runs in CI on the native-amd64 runner. Bora's call, 2026-06-19. The integration tier is unaffected — cluster nodes are x86. See plan.md Stage 1.2 T3.
| HTTP stubbing | **WireMock 3.9.x** | repo idiom; in-process server (unit/component) and in-cluster instance (integration) share the fixture format |
| Python services (Kadmos, Steropes, Metis) | **pytest** + `testcontainers-python` for the component tier; same context model for integration | mirrors the Kotlin lanes; `just test-py` gains a component marker |
| Cluster bring-up | **helm + kubectl** via olymp `just infra-up/down` (NON-ArgoCD) | D3′ — deployment is olymp's; scripted lifecycle needs no reconciler |
| CI | **GitHub Actions** — `test-component` folded into `ci.yml`; `integration-nightly.yml` separate (schedule + release) | keeps the PR gate fast; isolates the heavy job |

---

## 10. What this arc explicitly does NOT do

- **It does not change the stage policy.** Implementation stages still gate on mocked unit tests (planning-conventions §4). Component/integration tests are a separate suite that runs on its own cadence.
- **It does not move deployment into kantheon.** No kubectl/helm-apply logic for the constellation enters the monorepo. The component tier uses Testcontainers (not Kubernetes); the integration tier calls *olymp's* recipes.
- **It does not author the Helm charts.** Those are D3′ deliverables produced per service as each arc reaches deployment; this arc *consumes* them.
- **It does not require every service to have integration coverage at once.** Contexts are added incrementally as arcs reach the cluster (plan.md sequences `theseus-runquery` first, behind the already-forked stack).

---

*Doc owner: Bora. Created 2026-06-19. Update on every load-bearing decision; revision history via git.*
