# Iris — Stage 1.3: dispatch + SSE multiplex (transitional /v2)

> Branch: `feat/iris-arc`. Plan: [`plan.md`](./plan.md) §3 Stage 1.3. Contracts: [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.2 (chat endpoints), §2.3 (SSE framing), §5 (`/v2` adapter mapping), §3.1 (audit). Pre-flight: Stage 1.2 done.
>
> **Goal (testable boundary).** A full turn flows through: `POST /v1/chat/stream` → new-golem `/v2` → `IrisStreamEvent` SSE out → turn + audit persisted. `just test-kt iris-bff` green incl. fixture-driven mux specs and a component clarify→resume round-trip.

## Decisions locked at stage start

- **v2 SSE contract pinned from source** (`ai-platform/agents/golem/src/api/v2_routes.py::_stream_turn`): events `node_start`/`node_done`/`plan_pick`/`exec_done`/`envelope`/`error`; `: ready`/`: ping` comment frames; **terminal is `envelope` (or `error`) — there is no `done` event**, so the BFF **synthesises `done`** on stream close. `thread_id` is client-supplied (OQ-04.A) → the BFF uses `session_id` as the v2 thread id, created lazily on first chat.
- **`GolemV2Client` is an interface; the HTTP impl is quarantined** in `dispatch/golemv2/` (deleted at cutover). The mux + routes are tested against a **fake client** that replays recorded SSE fixtures; the real `GolemV2HttpClient` SSE consumption is unit-tested via the `V2SseParser` against the same fixtures, with live-HTTP fidelity deferred to integration (testing policy — no Wiremock-SSE flakiness in the gate).
- **KT envelope normaliser mirrors `normalize.ts`** (Stage 1.1): enum-case uppercasing, opaque-JSON → Rule-7 `*_json`, v2 chip → `Chip.prompt` arm; then `JsonFormat` parse → `FormatEnvelope`. `agent_id` BFF-enriched to `golem-v2`.
- **Audit is in-memory + hash-chained + Ed25519 at v1.** The Exposed `iris_audit` writer + Secret-loaded signing key are a Stage 1.4 hardening item (an ephemeral keypair is used meanwhile, with a warning). Chain shape matches contracts §3.1 (`self_hash = sha256(payload + prev_hash)`).
- **Resume is sync-over-/v2** surfaced as a single terminal envelope so the mux path is uniform; the issuer is recovered by matching the persisted `pending_resume_token`.

## Tasks

- [x] **T1 — Tests first: `IrisStreamMuxSpec` (fixture-driven).** Recorded `/v2` SSE fixtures (`test/resources/v2-sse/{happy-table,clarification,error}.sse`) → mux → assert ordering, monotone `sequence`, `plan_pick`/`exec_done` → step+detail, terminal envelope normalised (PlanSource/FormatKind/agentId), synthesised `done`, and outcome (DONE / CLARIFICATION+token / FAILED). `V2SseParserSpec` covers the wire parse (comment-frame skip, event mapping).
- [x] **T2 — `GolemV2Client` (quarantined).** `dispatch/golemv2`: V2 DTOs, sealed `V2StreamEvent` + `V2SseParser`/`SseAccumulator`, `GolemV2Client` interface + `GolemV2HttpClient` (Ktor CIO, line-wise SSE consume, `X-User-ID`/`X-Correlation-Id` injection) + `FakeGolemV2Client`.
- [x] **T3 — Envelope normalisation (KT).** `V2EnvelopeNormalizer` (v2 JSON → `envelope/v1.FormatEnvelope`), the BFF-side mirror of `envelope-ts/normalize.ts`.
- [x] **T4 — `IrisStreamMux` + chat routes.** `/v1/chat/stream` (SSE), `/v1/chat/turn` (sync terminal), sequence numbering, `done` synthesis; `IrisSse` frame writer (event name = oneof case). `ChatDispatcher` owns ensure-thread → mux → persist.
- [x] **T5 — `ConversationExcerpt` builder.** Last-N visible turns, oldest→newest; `ConversationExcerptSpec`.
- [x] **T6 — Clarification resume.** Persist `pending_resume_token` + issuer on the clarification turn; `/v1/chat/resume` recovers the issuer by token and routes; component round-trip in `ChatRoutesSpec`.
- [x] **T7 — Audit write at turn finalization.** Hash-chained Ed25519 `AuditStore` (in-memory at v1) written on every finalised turn; `AuditChainSpec` (chain verify; tamper + forged-sig rejection).
- [x] **T8 — Component test.** `ChatRoutesSpec` (Ktor `testApplication` + fake client + in-memory store): full turn lifecycle, SSE framing, persistence, clarify→resume, ownership 404.

**DONE means:** `./gradlew :agents:iris-bff:build` green — **33 tests** (mux/parser/excerpt/audit unit + chat component) + ktlint. Turn flows end-to-end through the mux to SSE; terminal envelope + audit persisted. **Stage 1.3 DONE.**

## Carry-forward notes

- **Live `/v2` HTTP + recorded fixtures.** `GolemV2HttpClient` is compile-verified; its SSE consumption is validated via `V2SseParser` on recorded fixtures, not a live new-golem. Re-record fixtures from a live session and add a live smoke in Stage 1.4 / integration. The fixtures are synthesised from the v2 `_stream_turn` source (same discipline as Stage 1.1).
- **Exposed `iris_audit` writer + signing key.** Audit is in-memory at v1 (not durable across restarts); the `iris_audit` Exposed write path + `iris.audit.signing-key-ref` Secret loading land in Stage 1.4. `ConversationExcerpt` is wired + tested but not yet forwarded to new-golem (it keeps its own thread state — informational until Phase 3 native agents).
- **`/chat/turn` + `/chat/resume` typed-action / edit-resend** beyond the clarification path are Phase 3 (typed actions) — out of scope here.

## Up / across

- Up: [`./README.md`](./README.md). Plan: [`./plan.md`](./plan.md). Prev: [`tasks-p1-s1.2-bff-sessions.md`](./tasks-p1-s1.2-bff-sessions.md).
- Next: Stage 1.4 — deploy + live smoke (`iris-bff/v0.1.0`).
