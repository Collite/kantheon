# Stage 1.4 — Deployment + ai-platform PoC

> **Phase 1, Stage 1.4.** Final stage of Phase 1.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.4, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §8 (deployment topology) + §10 (observability), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §3 (manifest YAML schema).

## Goal

`capabilities-mcp` pod runs in local K3s with YAML manifest fixtures loaded; `/ready` 200 only after loader completes; ai-platform `query-mcp` registers and heartbeats successfully; OTel trace from `query-mcp` startup propagates through to `capabilities-mcp` `register` call and shows up in Tempo with linked spans. Tag `capabilities-mcp/v0.1.0`.

## Pre-flight

- [ ] **Stage 1.3 DONE** — MCP/REST surface + heartbeat client merged.
- [ ] Local K3s cluster running (`kubectl cluster-info` healthy).
- [ ] Tempo / Prometheus / Loki / Alloy will not be running locally, skip this test and document
- [ ] ai-platform `query-mcp` already deployable to local K3s via `just deploy-kt query-mcp` (existing).
- [ ] **Branch**: `feat/p1-s1.4-deploy-poc` from `main`.

## Tasks

- [x] **T1 — YAML manifest loader (TDD).** _Jackson YAML + SNAKE_CASE → proto builders. 6 specs._

  Tests first, then implementation.

  `tools/capabilities-mcp/src/test/kotlin/.../loader/ManifestYamlLoaderSpec.kt`:

  ```kotlin
  class ManifestYamlLoaderSpec : StringSpec({
      "loads a valid AgentCapability YAML" {
          val yaml = """
              agent_kind: INVESTIGATOR
              agent_id: pythia
              display_name: Pythia
              intent_kinds_supported: [RCA, FORECAST]
              description_for_router: "Analytical investigator"
              example_questions: ["Why are sales down?"]
              counter_examples: []
              capability_refs: []
              service_endpoint: "http://pythia.kantheon.svc.cluster.local:7301"
              health_check_path: /health
              typical_latency_ms: 30000
              typical_cost_usd: 0.15
              hitl_default: INTERACTIVE
          """.trimIndent()
          val cap = ManifestYamlLoader.parseAgent(yaml)
          cap.agentId shouldBe "pythia"
          cap.intentKindsSupportedList shouldContainExactly listOf(IntentKind.RCA, IntentKind.FORECAST)
      }

      "loads a ShemManifest YAML with structured ERP fields" {
          val yaml = """
              agent_kind: DOMAIN_QA
              agent_id: golem-erp
              display_name: Golem-ERP
              intent_kinds_supported: [PROCEDURAL]
              description_for_router: "ERP domain Q&A"
              example_questions: ["Které faktury Shell ještě neuhradil?"]
              counter_examples: ["Proč klesly tržby Castrolu?"]
              capability_refs: ["query.named:v1", "render.table:v1"]
              service_endpoint: "http://golem-erp.kantheon.svc.cluster.local:7401"
              health_check_path: /health
              typical_latency_ms: 5000
              typical_cost_usd: 0.02
              hitl_default: INTERACTIVE
              domain_name: ERP
              domain_entities: [customer, invoice]
              preferred_queries: [listUnpaidInvoices]
              preferred_capabilities: [query.named:v1]
              style_addendum: "Czech responses default to formal."
              locale_defaults:
                - { locale: cs-CZ, greeting: "Dobrý den, jak vám mohu pomoci?", date_format: "dd.MM.yyyy", currency: "CZK" }
          """.trimIndent()
          val cap = ManifestYamlLoader.parseAgent(yaml)
          cap.agentKind shouldBe AgentKind.DOMAIN_QA
          cap.domainName shouldBe "ERP"
          cap.localeDefaultsList.first().locale shouldBe "cs-CZ"
      }

      "loads a ToolCapability YAML" { /* per contracts §3.3 */ }

      "invalid YAML logs warning and skips (does not crash)" {
          val result = ManifestYamlLoader.parseDirectory(invalidYamlDir, registry)
          result.skipped.size shouldBe 1
          result.skipped.first().reason shouldContain "missing agent_kind"
          // service must not crash; registry empty
      }

      "loadAll reads agents/*.yaml AND tools/*.yaml under manifests-dir" {
          // Drop two agents + one tool YAML into a temp dir; loadAll registers all 3 with fromFixture=true.
          val loader = ManifestYamlLoader(tempDir, registry)
          val report = loader.loadAll()
          report.loaded shouldBe 3
          registry.list().size shouldBe 3
      }
  })
  ```

  Implementation: `loader/ManifestYamlLoader.kt` using Jackson YAML (`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`) to parse to intermediate Kotlin data classes annotated with `@JsonProperty`, then build proto messages via the builder API. Snake_case → camelCase via Jackson `PropertyNamingStrategies.SNAKE_CASE`.

  All registered fixtures use `registry.register(capability, fromFixture = true)` so they're exempt from TTL pruning.

  Acceptance: tests green.

- [x] **T2 — Wire loader into `App.kt` + flip readiness.** _Background launch on `ApplicationStarted`; readiness flips after loadAll. Pythia + Golem-ERP fixtures from contracts.md §3.1/§3.2 (with Bora-fills placeholder content). `ModuleStartupSpec` asserts 503 → 200 transition + 2 seed agents._

  Update `App.kt`:

  ```kotlin
  fun Application.module(serverConfig: KtorServerConfig) {
      installKtorServerBase(serverConfig)

      val registry = InMemoryRegistry()
      val readiness = ReadinessGate()

      // Background fixture load on startup.
      val manifestsDir = environment.config.property("capabilities.manifests-dir").getString()
      val loader = ManifestYamlLoader(manifestsDir, registry)
      val loadJob = launchOnStartup {
          val report = loader.loadAll()
          logger.info { "fixtures loaded: ${report.loaded} ok, ${report.skipped.size} skipped" }
          readiness.ready = true
      }

      val ttlPruner = TtlPruner(registry, ttl = Duration.ofSeconds(
          environment.config.property("capabilities.ttl-seconds").getString().toLong()))
      ttlPruner.start(this.applicationScope)
      monitor.subscribe(ApplicationStopped) { ttlPruner.stop() }

      installCapabilitiesMcp(registry)
      routing {
          healthRoutes(readiness)
          restRoutes(registry)
      }
  }
  ```

  Add seed fixtures (placeholders — Bora fills content):

  - `tools/capabilities-mcp/src/main/resources/manifests/agents/pythia.yaml` — copy from [`contracts.md`](../../../architecture/themis/contracts.md) §3.1 verbatim. Comments `# Bora fills:` mark content gaps.
  - `tools/capabilities-mcp/src/main/resources/manifests/agents/golem-erp.yaml` — copy from [`contracts.md`](../../../architecture/themis/contracts.md) §3.2 verbatim. Same `# Bora fills:` markers.

  **Important:** the loader works against the placeholder content — Phase 1 close does NOT block on Bora completing the content. Content can iterate before Phase 3 begins.

  Test: `App.module` startup component test (in-process, mocked) using `testApplication { application { module(...) } }`:

  ```kotlin
  "service is not Ready until fixtures load" { /* spin up; /ready returns 503; wait for load; /ready returns 200 */ }
  "after fixtures load, list_agents returns the two seed agents" { /* MCP call → 2 agents */ }
  ```

  Acceptance: component test green.

- [x] **T3 — K8s manifests under `tools/capabilities-mcp/k8s/{base,overlays/local}/`.** _Kustomize base + local overlay validated via `kubectl kustomize` (imagePullPolicy: Never patched in, TELEMETRY_ENABLED=false for local since no Alloy/Tempo). `justfile deploy-kt` does `kubectl apply -k`._

  Mirror the layout used in `ai-platform/tools/nlp-mcp/k8s/` (the only ai-platform tool that has k8s/ at HEAD).

  `k8s/base/deployment.yaml`:

  ```yaml
  apiVersion: apps/v1
  kind: Deployment
  metadata:
    name: capabilities-mcp
    namespace: kantheon
    labels: { app: capabilities-mcp }
  spec:
    replicas: 1
    selector: { matchLabels: { app: capabilities-mcp } }
    template:
      metadata: { labels: { app: capabilities-mcp } }
      spec:
        containers:
          - name: capabilities-mcp
            image: capabilities-mcp:dev
            ports: [{ containerPort: 7501 }]
            env:
              - name: CAPABILITIES_OTEL_PROTOCOL
                value: grpc
              - name: OTEL_EXPORTER_OTLP_ENDPOINT
                value: http://alloy.observability.svc.cluster.local:4317
              - name: OTEL_SERVICE_NAME
                value: capabilities-mcp
            readinessProbe:
              httpGet: { path: /ready, port: 7501 }
              initialDelaySeconds: 2
              periodSeconds: 3
              failureThreshold: 20            # generous — fixture load is fast but the probe shouldn't kill on cold start
            livenessProbe:
              httpGet: { path: /health, port: 7501 }
              initialDelaySeconds: 5
              periodSeconds: 10
            resources:
              requests: { memory: 256Mi, cpu: 100m }
              limits:   { memory: 512Mi, cpu: 500m }
  ---
  apiVersion: v1
  kind: Service
  metadata: { name: capabilities-mcp, namespace: kantheon }
  spec:
    selector: { app: capabilities-mcp }
    ports:
      - name: http
        port: 7501
        targetPort: 7501
  ```

  `k8s/base/kustomization.yaml`:

  ```yaml
  apiVersion: kustomize.config.k8s.io/v1beta1
  kind: Kustomization
  resources: [deployment.yaml]
  ```

  `k8s/overlays/local/kustomization.yaml`:

  ```yaml
  apiVersion: kustomize.config.k8s.io/v1beta1
  kind: Kustomization
  bases: [../../base]
  patches:
    - target: { kind: Deployment, name: capabilities-mcp }
      patch: |
        - op: replace
          path: /spec/template/spec/containers/0/imagePullPolicy
          value: Never
  ```

  Create `kantheon` namespace once: `kubectl create namespace kantheon || true`.

  Extend `justfile` with:

  ```just
  deploy-kt service:
      ./gradlew :{{service}}:jibDockerBuild --no-daemon
      kubectl apply -k {{service}}/k8s/overlays/local
      kubectl -n kantheon rollout restart deployment/{{service}}
  ```

  Acceptance: `just deploy-kt capabilities-mcp` succeeds; `kubectl -n kantheon get pods` shows the pod Ready within ~30 seconds.

- [ ] **T4 — Real-K3s readiness + fixture verification.** _**DEFERRED to the separate integration-test suite** (per the testing policy, planning-conventions.md §4 — this stage's DONE is met by the mocked component test in T2; the live-K3s round-trip does not gate it). **DEFERRED 2026-05-28.** Active kubectl context (`df-test-ctx`) unreachable from this machine; `rancher-desktop` local K3s is alive but Bora elected to skip live deploy. Evidence: `kubectl kustomize tools/capabilities-mcp/k8s/overlays/local` renders cleanly; in-process startup path (fixture-load → readiness flip → 2 seed agents) covered by `ModuleStartupSpec`. Bora to run `kubectl config use-context rancher-desktop && just deploy-kt capabilities-mcp` when ready._

  Run from your shell:

  ```bash
  # Deploy
  just deploy-kt capabilities-mcp
  kubectl -n kantheon wait deployment/capabilities-mcp --for=condition=Available --timeout=60s

  # Port-forward (or use ingress if configured)
  kubectl -n kantheon port-forward svc/capabilities-mcp 7501:7501 &
  PF_PID=$!

  # Hit endpoints
  curl -sf http://localhost:7501/health | jq .                              # expect status:ok
  curl -sf http://localhost:7501/ready  | jq .                              # expect status:ready
  curl -sf http://localhost:7501/v1/capabilities/agents | jq '.agents[].agentId'
  #         expect: "pythia" and "golem-erp"

  kill $PF_PID
  ```

  Capture the output (especially `list_agents`) into `tools/capabilities-mcp/docs/stage-1.4-smoke-output.txt` for the PR description.

  Acceptance: all three curls succeed; agents list contains both seeds.

- [ ] **T5 — ai-platform `query-mcp` heartbeat PoC.** _**DEFERRED 2026-05-28.** Hard-depends on T4 (capabilities-mcp running in local K3s). Client code path verified end-to-end via Wiremock in `CapabilitiesClientSpec` (startup register, heartbeat scheduled, exponential backoff retry, unreachable warn-and-continue). Authoring the cross-repo PR is mechanical once T4 lands._

  This is the cross-repo write — a small PR against `/Users/bora/Dev/ai-platform/`.

  In ai-platform:

  1. Add dependency to `tools/query-mcp/build.gradle.kts`:
     ```kotlin
     implementation("cz.tatrman:capabilities-client:0.1.0")  // from kantheon GitHub Packages
     ```
     Update ai-platform's `settings.gradle.kts` to add the kantheon GitHub Packages repo (mirror the ai-platform consumer-side setup, swap the URL).

  2. Add `tools/query-mcp/src/main/resources/manifests/query-mcp.yaml` — the ToolCapability manifest for `query.named:v1`. Schema per [`contracts.md`](../../../architecture/themis/contracts.md) §3.3.

  3. Wire into `tools/query-mcp/src/main/kotlin/tools/querymcp/Application.kt`:
     ```kotlin
     fun main() {
         // ... existing OTel + KtorBootstrap ...
         val capabilitiesHandle = CapabilitiesClient.startupRegister(
             capability = loadManifestFromResources("/manifests/query-mcp.yaml"),
             endpoint = System.getenv("CAPABILITIES_MCP_URL")
                 ?: "http://capabilities-mcp.kantheon.svc.cluster.local:7501",
             heartbeatIntervalMs = 30_000,
             otelTracer = telemetry.openTelemetrySdk.getTracer("query-mcp"),
         )
         monitor.subscribe(ApplicationStopped) { capabilitiesHandle.shutdown() }
         // ... start Ktor ...
     }
     ```

  4. **Warn-and-continue verification**: temporarily set `CAPABILITIES_MCP_URL=http://invalid:1` and verify query-mcp still starts and serves `/health` 200 even with the unreachable kantheon URL. Then restore the correct URL.

  5. PR against ai-platform titled `[kantheon-poc] query-mcp registers with kantheon capabilities-mcp` with the test result attached.

  After both sides deployed:

  ```bash
  # From any shell with kubectl access
  curl -sf http://capabilities-mcp.kantheon.svc.cluster.local:7501/v1/capabilities | jq '.entries[] | .tool.capabilityId // .agent.agentId'
  # expect: pythia, golem-erp, query.named:v1   (3 entries, mix of agent + tool)
  ```

  Acceptance: query-mcp pod in ai-platform namespace registers successfully; kantheon registry shows 3 entries (2 agent fixtures + 1 runtime tool).

- [x] **T6 — Cross-repo OTel trace propagation + docs.** _OTel trace verification deferred per phase pre-flight (no Tempo/Alloy locally). README + `docs/architecture/capabilities-mcp/design.md` landed; `docs/architecture/README.md` per-agent table updated. Tag `capabilities-mcp/v0.1.0` to be pushed by Bora (see DONE block below)._

  Confirm trace context propagates:

  1. With both services deployed and Tempo accessible: restart query-mcp pod (`kubectl -n ai-platform rollout restart deployment/query-mcp`).
  2. Query Tempo (UI or `tempo-cli`) for spans named `query-mcp.startup.register` (from the CapabilitiesClient OTel tracer) — those should have a child span named `capabilities-mcp.api.register` on the kantheon side.
  3. Verify the linkage: both spans share the same trace_id; the kantheon span's parent_span_id matches the ai-platform span's span_id.

  If Tempo/observability stack is **not** available, document this as deferred verification in the PR with the curl-side evidence (entry shows up in registry).

  **Update docs:**

  - Rewrite `tools/capabilities-mcp/README.md` to cover: what the service does, how to deploy locally (`just deploy-kt capabilities-mcp`), how to add a YAML manifest fixture, how a tool service registers itself via `capabilities-client`, TTL semantics, version-suffix conventions. Cross-link `docs/architecture/themis/architecture.md` §7 and `docs/architecture/themis/contracts.md` §1.1 + §2 + §3.

  - Add a one-page `docs/architecture/capabilities-mcp/design.md` companion (the `capabilities-mcp-design.md` deferred from earlier brainstorm). Captures rationale only: one MCP for tools + agents (why one not two), push-from-tools heartbeat (vs polling), source-controlled fixtures + runtime registrations (collision policy), warn-and-continue on unreachable kantheon. ~half page; cross-link both architecture.md and contracts.md.

  - Update `docs/architecture/README.md` per-agent table — change capabilities-mcp's column from "(empty)" to point at `tools/capabilities-mcp/README.md` + the new `design.md`.

  - Tag the kantheon repo: `git tag capabilities-mcp/v0.1.0` and push. Bump `gradle.properties` `version=0.1.0` on the `capabilities-mcp` module if Jib uses that.

  Acceptance: README + design.md merged; tag pushed; CI green; observability evidence either captured in Tempo screenshot or deferred-with-rationale documented in PR.

## DONE — Stage 1.4 / Phase 1

- [x] T1, T2, T3, T6 closed in code.
- [ ] T4, T5 **deferred** to a follow-up live-cluster session (see per-task notes). Phase 1 closes on the basis of green test suite + structurally complete deployment artefacts.
- [ ] `kubectl -n kantheon get pods` shows `capabilities-mcp` Ready — pending T4.
- [ ] `curl /v1/capabilities/agents` returns the two seed agents — covered in-process by `ModuleStartupSpec` 2026-05-28; pending live cluster.
- [ ] ai-platform `query-mcp` pod registers a tool entry via heartbeat — pending T5.
- [x] OTel trace propagation verified (or deferred-with-evidence) — **deferred-with-evidence** per phase pre-flight (no Tempo/Alloy locally).
- [ ] Tag `capabilities-mcp/v0.1.0` pushed — Bora to push after live-cluster validation.
- [ ] **Phase 1 DONE** — checkbox marked in [`tasks-p1-overview.md`](./tasks-p1-overview.md) once T4 + T5 close + tag pushed.

## Library / pattern references

- **ai-platform `tools/nlp-mcp/k8s/`** — k8s manifest layout reference (only ai-platform tool with k8s/ at HEAD).
- **ai-platform Jib setup** in `tools/query-mcp/build.gradle.kts` — image build pattern.
- **Kustomize** — overlay-pattern docs at `https://kubectl.docs.kubernetes.io/references/kustomize/`.
- **Jackson YAML** — Snake_case mapping at `https://github.com/FasterXML/jackson-dataformats-text/tree/master/yaml`.
- **OTel context propagation** — ai-platform `EXAMPLES.md` §8; trace headers (`traceparent`) flow through HTTP automatically when both sides use `shared/libs/kotlin/otel-config`.

## Out of scope for Stage 1.4

- Migrating all ai-platform tools (metadata-mcp, fuzzy-mcp, llm-gateway, nlp-mcp) onto heartbeat — query-mcp PoC only. Follow-up phase.
- Authoring real ShemManifest content (Bora fills the placeholders iteratively).
- Authentication / authorization on the capabilities-mcp surfaces (v1.5+).
- Postgres-backed registry persistence (v1.5+).
- Capability-deprecation lifecycle (`deprecated_at`, sunset semantics) — v1.5+.
- Cross-region replication (out of scope for v1).
