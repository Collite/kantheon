# Stage 02 — `nlp-mcp` thin wrapper

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: Kotlin/Ktor MCP wrapper around `infra/nlp`, exposed to agents per the
platform's service-vs-MCP separation rule. The wrapper is **thin** —
translation only, no business logic.

## Entry criteria

- Stage 01 complete: `infra/nlp` is deployable and answers `POST /v1/analyze`
  with proto-shaped JSON.
- `tools/fuzzy-mcp` and existing patterns in `mcp-server-base` reachable as a
  reference implementation.
- `shared/libs/kotlin/otel-config` provides the `createOpenTelemetrySdk()`
  helper used across other Kotlin services.

## Exit criteria

- `just deploy-kt nlp-mcp` deploys `tools/nlp-mcp` to local K3s.
- A Python agent (`agents/erp-agent-2`) successfully calls the `analyze` MCP
  tool and receives proto-shaped JSON back.
- OTEL trace propagation verified at the component level (Wiremock-mocked
  `infra/nlp`): spans created and context propagated through `nlp-mcp`. (Full
  agent → `nlp-mcp` → `infra/nlp` → engines (Stanza, NameTag) trace
  confirmation in Tempo via `just debug-tunnel` is deferred to the separate
  integration-test suite.)
- Wiremock-based component tests cover happy path and the principal error
  paths (`infra/nlp` 503, malformed input, timeout).
- Build is via Jib (no Dockerfile, per CLAUDE.md).

## Tasks

### 1. Project scaffolding
- [ ] Create `tools/nlp-mcp/` mirroring `tools/fuzzy-mcp/`'s layout.
- [ ] Apply the `id("my.kotlin-ktor")` convention plugin.
- [ ] `build.gradle.kts` references versions only via `gradle/libs.versions.toml`.
- [ ] Dependencies: `mcp-server-base`, generated proto Kotlin types from
      `cz.dfpartner.nlp.v1`, `shared/libs/kotlin/otel-config`, gRPC or HTTP
      client for `infra/nlp`.

### 2. MCP tool definitions
- [ ] `analyze` tool: takes full op set, optional language, mode, engineHints.
      Mirrors the proto request 1:1.
- [ ] `parse` tool: convenience shorthand for `[TOKENIZE, LEMMATIZE, POS_TAG,
      DEP_PARSE]`; auto-detects language. Reduces boilerplate for the common
      case Resolver will use.
- [ ] Tool input/output schemas declared per MCP spec; structured content uses
      camelCase JSON (proto3 default; matches wire-format Rule 2).

### 3. `infra/nlp` client
- [ ] Decision: gRPC or REST?
      - gRPC matches wire-format Rule 3 ("gRPC default for new v1 services");
        requires `infra/nlp` to expose a gRPC server (Stage 01 ships REST
        first; gRPC may be added here or deferred).
      - REST is simpler to start; ktor-client with `kotlinx.serialization` against
        the same proto-aligned JSON.
      - **Default proposal: REST in Stage 02; promote to gRPC in a follow-up
        once `infra/nlp` exposes gRPC.** Note this in the README.
- [ ] Set client timeouts: 5s default, configurable. Layered timeouts per
      `agents/erp-agent-2` reference pattern (memory note on layered
      timeouts applies to MCP-via-gRPC too if/when promoted).
- [ ] gRPC client (when added): set `maxInboundMessageSize` and
      `maxOutboundMessageSize` to 32 MiB per Rule 5.

### 4. Pass-through logic
- [ ] No business logic in the wrapper — only request translation, header
      propagation, and error mapping. Per service-vs-MCP rule: if you find
      yourself writing parsing or selection logic, it belongs in `infra/nlp`,
      not here.
- [ ] Forward auth context from MCP request to `infra/nlp`.
- [ ] Forward W3C trace context to `infra/nlp` so the trace stitches.

### 5. Ktor response patterns
- [ ] **Use `buildJsonObject` with `JsonPrimitive`**, never `mapOf(...)`, in
      `call.respond(...)`. Memory note on type erasure applies to all Ktor
      services in this monorepo.
- [ ] All responses include `messages: []` field (initially always empty)
      per wire-format Rule 6.

### 6. Error handling
- [ ] `infra/nlp` 503 → MCP error response with diagnostic, status mapping
      preserved.
- [ ] `infra/nlp` 4xx → MCP error response with the original detail.
- [ ] Timeout → MCP timeout error; trace span marks failure with
      `error.kind=timeout`.
- [ ] Unexpected exception → log with stack, return generic 5xx; never leak
      stack to the MCP caller.

### 7. Observability
- [ ] Wire OTEL via `createOpenTelemetrySdk()`.
- [ ] One span per MCP call wrapping the downstream HTTP call.
- [ ] Span attributes: `mcp.tool` (analyze/parse), `nlp.lang`,
      `nlp.ops` (joined), `nlp.engine_hints` (if any).
- [ ] Metrics: `nlp_mcp_request_total{tool,status}`,
      `nlp_mcp_request_duration_seconds`.

### 8. Testing
- [ ] Unit tests for tool input validation and request translation.
- [ ] Component tests against a Wiremock stub of `infra/nlp`. Cover
      happy path, 503, 4xx, timeout. (Per the testing policy,
      planning-conventions.md §4: mocked unit tests only; Wiremock is the
      sanctioned mock — no Testcontainers; real-dependency verification moves
      to the separate integration-test suite.)
- [ ] Trace propagation test: assert that the trace context header sent to
      Wiremock matches what the MCP caller injected.
- [ ] CamelCase serialization check on the response.

### 9. Deployment
- [ ] Jib build configured in `build.gradle.kts`. The CI pipeline auto-detects
      Jib services and builds them; do not hardcode the service into
      `ci.yml`.
- [ ] K8s manifests under `tools/nlp-mcp/k8s/{base,overlays/local}/` using
      Kustomize. `imagePullPolicy: Never` in the local overlay.
- [ ] `just deploy-kt nlp-mcp` recipe present.

### 10. Real-services check — deferred to the integration-test suite
Per the testing policy (planning-conventions.md §4), this stage is gated by the
mocked unit/component tests above; the all-real-services check below moves to
the separate integration-test suite (it does not gate this stage's DONE).
- [ ] _(integration suite)_ From `agents/erp-agent-2` (Python), invoke the
      `analyze` MCP tool with a Czech sentence. Verify the response shape
      matches the proto and that Tempo shows the continuous trace.
- [ ] Document the verification procedure in the README so future contributors
      can repeat it.

### 11. Documentation
- [ ] `tools/nlp-mcp/README.md`: tool descriptions, input/output schemas,
      configuration (target `infra/nlp` URL, timeouts), local dev workflow.
- [ ] If new MCP patterns emerge here that aren't already documented in
      `docs/implementation/MCP-Servers-HowTo.md`, contribute back.

## Risks and mitigations

**MCP wrapper temptation to add logic.** Easy slip: "I'll just normalize the
input here." Resist — every such normalization belongs in `infra/nlp`.
Reviewer enforces this on PR.

**REST-vs-gRPC decision creates a future migration.** Document explicitly that
the REST-first approach is interim; future PR promotes to gRPC when
`infra/nlp` exposes gRPC. The proto stays the source of truth either way.

**Trace propagation across Python↔Kotlin boundary.** Test explicitly. Both
sides use OTEL, but header injection/extraction details differ; verify before
Stage 04 starts depending on continuous traces.

## Out of scope for Stage 02

- Replacing the REST hop with gRPC (follow-up).
- Caching at the wrapper layer (caching lives in `agents/resolver` per Stage 04
  design; the MCP wrapper is stateless).
- `compare` MCP tool (Stage 03 lands COMPARE mode in `infra/nlp`; the MCP
  surface for it can land then or in a follow-up).
