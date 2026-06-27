# Iris arc — Phase 1 review (Stages 1.1–1.3) and fixes

> Multi-dimension code review of the Iris arc as built (protos + envelope-ts + iris-bff),
> with every finding resolved in-branch (`feat/iris-arc`). Suites after fixes:
> **iris-bff 46**, **shared:proto golden 8**, **envelope-ts 61** — all green, ktlint clean.

## Critical / High (gated the arc PR)

| # | Sev | Finding | Resolution |
|---|-----|---------|------------|
| C1 | Critical | Upstream `/v2` flow throwing mid-stream skipped `done` synthesis **and** turn persistence **and** audit (`IrisStreamMux.run` collected with no `try/catch`; `persist` ran only after a clean return). | `run()` now wraps the collect: `CancellationException` re-thrown (client disconnect), any other `Throwable` → synthesised terminal `error` + `done(failed)`, FAILED outcome returned so `ChatDispatcher` still persists + audits. Test: `IrisStreamMuxSpec` "upstream flow throwing…" + `ChatRoutesSpec` "an upstream stream failure…". |
| C2 | Critical | OBO bearer never forwarded — downstream got a spoofable `X-User-ID` and no `Authorization`. | `GolemV2Client` methods take `bearer`; `GolemV2HttpClient.identity` injects `Authorization: Bearer …`; `ChatDispatcher` passes `caller.bearer`. Test: `ChatRoutesSpec` "the caller's OBO bearer is forwarded…". |
| H1 | High | No fail-closed on token expiry. | `BearerAuthenticator` checks `exp` (injectable clock); already-expired → null → 401. Test: `AuthSpec`. |
| H2/H3 | High | SSE response missing `Cache-Control: no-cache` / nginx no-buffer hint; throw-after-headers couldn't 500. | New `respondSse` helper sets `Cache-Control: no-cache` + `X-Accel-Buffering: no`; terminal frame always emitted (C1) so the wire stays well-formed. Test: `ChatRoutesSpec` "the SSE response carries…". |
| H4 | High | SSE idle heartbeat (`iris.stream.heartbeat-s`) unimplemented (dead config key). | `respondSse` runs a mutex-serialised `:heartbeat` ticker every `heartbeatMs`; key wired through `Wiring` → `chatRoutes`. |
| H(persist) | High | `appendTurn` seq race vs `UNIQUE(session_id, seq)` under REPEATABLE READ. | `ExposedSessionStore.appendTurn` takes a `SELECT … FOR UPDATE` row lock on the session before `MAX(seq)+1` (matches in-memory `synchronized`). |
| H(persist) | High → **false positive** | `array<Uuid>` claimed to throw at runtime. | Verified: Exposed 1.0 `resolveColumnType` registers `Uuid::class → UuidColumnType()`. Made the element type explicit anyway (`array("turn_ids", UuidColumnType())`) to kill the reflection. |

## Medium

| Finding | Resolution |
|---------|------------|
| DONE-with-`error_code` recorded as DONE (metrics undercount). | `computeOutcome`: a terminal envelope carrying `error_code` finalises FAILED. Test added. |
| Resume token never cleared (replayable forever). | `SessionStore.clearPendingResumeToken`; `ChatDispatcher.runResume` clears it on a non-FAILED outcome. Test added. |
| Audit `self_hash` over raw string, not canonical (contract said `canonical(payload)`). | `canonicalizePayload` (recursively key-sorted, compact) applied at append; stored payload + hash are canonical. Test: "self_hash is canonical…". |
| `verifyChain` missed seq gaps (deleted middle row). | Added gap-free `seq` 1..N check. Test: "a deleted middle row…". |
| `detail_json` built by string interpolation (quote/escape injection). | `planDetail`/`execDetail` use `buildJsonObject`. |
| `iris.db` config shape doc-vs-code drift. | **Doc** was wrong — code matches shared `db-common` (`type/host/port/database/user/password`). Fixed contracts.md §6. |
| `/ready` claimed to gate on migration but discarded the outcome. | Boot fails fast if migration throws; comment corrected, outcome logged. |
| Dead config keys (`excerpt.max-turns`, `stream.heartbeat-s`). | heartbeat now wired; `excerpt` block dropped from conf + contracts §6 (excerpt isn't forwarded until Phase 3). |
| `SseAccumulator` `.trim()` deviated from SSE one-space rule. | Strips only a single leading space after `data:`/`event:`. |
| `putV2Thread` insert-vs-upsert divergence. | `insertIgnore` (idempotent, matches in-memory overwrite). Test added. |
| Golden spec: only 2/6 `IrisStreamEvent` arms; `ignoringUnknownFields` blind spot. | All 6 arms + `ChatResumeRequest` both arms covered; canonical-field round-trips drop `ignoringUnknownFields` so renames now fail. |
| TS: sign-aware zero-pad bug (`%05d` of -42 → `00-42`). | Sign moved outside the zero-fill; tests added. |
| TS: `value: null` filter dropped. | `toJsonString(null)` → `"null"`; tests added. |
| `fromWire` threw an opaque `NoSuchElementException`; no DB CHECK constraints. | Clear `error(...)` messages + `CHECK` constraints on `status`/`origin`/`verdict`. |

## Low / Nit
SQL-side discard filter in `loadTurns`; safe `singleOrNull` session lookups; collapsed dead `V2EnvelopeNormalizer` branch; `reserved 99;` across all non-carrier messages; narrowed buf lint exceptions; `Application.kt` single `serverConfig` + OTel gated on `telemetry.enabled`; owner-scoping documented as the route-layer trust boundary; tautological `V2SseParserSpec` assertion removed.

## Deferred (unchanged — Stage 1.4 / integration)
Live `/v2` HTTP fidelity + recorded fixtures; Exposed `iris_audit` writer + Secret-loaded signing key; JWKS signature verification; real-PG fidelity for `ExposedSessionStore`. The synthesized-not-recorded golden corpus (KT + TS) remains a known carry-in until Stage 1.4 captures land.
