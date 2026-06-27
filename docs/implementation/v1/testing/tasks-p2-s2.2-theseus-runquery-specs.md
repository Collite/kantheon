# Stage 2.2 — `theseus-runquery` integration specs + fixtures

> **Phase 2, Stage 2.2.** End-to-end assertion code written ahead of the live context (Stage 2.3). Compiles + gated now; green once olymp stands the context up.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §6, [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §3, [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md), `shared/libs/kotlin/integration-harness/README.md`.
>
> **Status.** ✅ Done 2026-06-20 (Bora / Claude). Compiles + gated (`@RequiresContext("theseus-runquery")`); runs in Stage 2.3. **Premise corrections below** — the chain's real shape differs from the plan's assumptions.

## What was built

`RunQueryIntegrationSpec` (`tools/theseus-mcp/src/integrationTest/…`) drives the MCP **`query`** tool over real **StreamableHTTP** (`POST …/mcp:7307`) through the real chain theseus-mcp → Theseus → Proteus → Argos → Kyklop → Brontes → MSSQL, asserting the `CallToolResult` envelope. A `McpQueryDriver` helper supplies `ContextHandle.callQuery(...)` (MCP SDK client over a Ktor engine, OBO bearer on every request) + an unsigned-JWT minter + `CallToolResult` accessors.

## Premise corrections (rules-first — surfaced for Bora)

The plan assumed an `envelope/v1` response and WireMock-stubbed LLM/modeler externals. The actual chain (confirmed in code) differs:

1. **Tool is `query`, not `run_query`; transport is MCP StreamableHTTP JSON-RPC**, not REST. Response is an MCP `CallToolResult`: rows in `content[0].text` (JSON for `format=json`), schema/status in `structuredContent` (`ok`, `rowCount`, `columns[]`, `messages[]`, `pipelineWarnings[]`). No `envelope/v1.FormatEnvelope`.
2. **No external HTTP calls on the `query` path** → **T2 (WireMock LLM/modeler fixtures) and T5 (request-journal) don't apply.** Argos's LLM-guard is only invoked when `ValidationOptions.llmGuard=true`, which Theseus never sets for `query` (and the tool exposes no flag); modeler/TTR is not a runtime call; Argos RLS policy is in-process. The only true external is **MSSQL** (Brontes) — the context's `mssql` platform member + seed, not WireMock. The Stage 2.1 in-cluster WireMock loader remains available for a future LLM-guard scenario. **T5's intent — "prove the chain didn't short-circuit" — is met instead by asserting a real seeded value (`t-alpha`) comes back from MSSQL.**
3. **Identity fail-closed is on missing/unparseable bearer (`missing_user_identity`), not `exp`.** In v1 theseus-mcp's IdentityResolver extracts claims but does not verify signature/expiry (ingress/sidecar does). So T3 asserts the in-service behavior: valid bearer → success + roles reach Argos; **missing** bearer → fail-closed. (Expired-token rejection is an ingress concern, out of scope for this spec.)
4. **RLS denial = column-DENY → error envelope `code=column_denied`** (`isError=true`, `ok=false`); a row-level RLS predicate would instead surface as success + reduced rows + `security_predicate_applied` warnings. T4 uses the column-DENY path for an unambiguous denial signal + a permitted-role variant that returns rows.

## Tasks

- [x] **T1 — happy path** — `query` with an `analyst` OBO bearer returns the 4 seeded rows; asserts `ok`, `rowCount==4`, columns `[id,tenant_id,region,amount]`, and a real seeded value in the body.
- [x] **T2 — external stubs** — **N/A for the `query` path** (no external HTTP deps; see correction #2). No fixtures created; documented.
- [x] **T3 — OBO/bearer** — valid bearer succeeds (roles → Argos); missing bearer → `missing_user_identity` fail-closed (see correction #3).
- [x] **T4 — RLS denial** — `restricted` role → `column_denied` error envelope (no leaked rows); `analyst` → rows.
- [x] **T5 — chain-integrity check** — substituted the WireMock journal (N/A) with a real-seeded-value assertion proving MSSQL was reached (see correction #2).
- [x] **T6 — run-set member + context requirements** — declared in the spec KDoc: services, `mssql` seed + routing, `requireIdentity=true`, the Argos policy (`analyst` full / `restricted` column-DENY on `amount`), and the `readiness` set — to be mirrored by olymp `test-contexts/theseus-runquery/context.yaml`.

## DONE criteria

- [x] All scenarios compile; gated by `@RequiresContext("theseus-runquery")` (skip with no context — verified: `integrationTest` SKIPPED with no `-Pcontext`).
- [x] WireMock external fixtures — **N/A** (corrected); the loader remains for future LLM-guard use.
- [x] OBO/bearer + RLS-denial scenarios written.
- [x] Chain-integrity assertion in place (real seeded value).
- [ ] Specs pass once the live context is up — **verified in Stage 2.3** (needs olymp Phase A: the `theseus-runquery` context with the seed + Argos policy above).

## Notes

- Registration uses `@ApplyExtension(RequiresContextExtension::class)` (Kotest 6 removed `@AutoScan`). `ApplyExtension` is `io.kotest.core.extensions.ApplyExtension`.
- The drift guard (`ContextNameRegistrySpec`) now sees `@RequiresContext("theseus-runquery")`; it will require olymp to define `test-contexts/theseus-runquery/` once run with `-PolympDir` (nightly). It skips on local/PR runs (no olymp checkout).
- The MCP SDK StreamableHTTP **client** (`io.modelcontextprotocol:kotlin-sdk` `HttpClient.mcpStreamableHttp { … }`) is the call path — not the in-process `QueryTool.execute(...)` the component tests use.
