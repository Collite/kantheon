# Tasks — Review 006 fixes (Charon Phase 1)

> Source: [`review-006.md`](./review-006.md). Branch under review:
> `feat/charon-p1-s1.3-redis`. Do this work on a new branch
> `fix/charon-p1-review-006` cut from that branch.
>
> **Rule for every task below:** TDD — write the failing test first, then make
> it pass. Do **not** tick a box until you have run `./gradlew :services:charon:test
> :services:charon:ktlintCheck` and pasted the green result into the task's
> "Done evidence" line. Per `AGENTS.md` §12: a ticked checkbox is not proof —
> the test run is.
>
> Order matters: **R1 → R2 → R3** are coupled (the multi-batch fix and the
> streaming rework touch the same code). Do them in sequence. R4–R6 are
> independent. R7 (doc reconciliation) last. R8 (cleanups) any time.

---

## Re-review status (2026-06-15)

Re-reviewed the dev agent's R1–R7 work on `fix/charon-p1-review-006` against the
code (per `AGENTS.md` §12 — checkboxes were all still unticked). Findings + what
the re-review changed:

| Item | Verdict on agent's work | Action taken in re-review |
|---|---|---|
| **R1** multi-batch | ✅ Correct (deep-copy via `TransferPair`; `MultiBatchMoveSpec`). | kept |
| **R2** streaming/multipart | ❌ **BLOCKER — reintroduced silent data loss.** The per-batch multipart wrote each part as a self-contained `schema+batch+EOS` stream; the concatenation read back **empty** (proven). Tests missed it (asserted the pipe's row count, never the stored object). | **Reverted to a single-shot coherent-stream PUT** (temp-key + copy + delete), matching the agent's own architecture §10 "single-shot PUT" note. Added a **readback gate** (`MultiBatchMoveSpec` "writes one readable object") that reassembles the actual PUT body and asserts all 9 rows. |
| **R3** fingerprint | ⚠️ First attempt mis-executed (nullable digest, tautological test, §6 claim intact). | **RESOLVED — Bora-approved 2026-06-15.** Implemented the **canonical, implementation-independent fingerprint** (logical-schema string → SHA-256) in `Integrity.kt` + a byte-identical Python reference (`regenerate.py`); `IntegritySpec` asserts Kotlin == Python for a rich schema (utf8/int64/float64/decimal128/timestamp+tz/date32/bool/binary/struct), digest `69779ea6…`. `contracts.md` §6 + architecture §9/§10 rewritten to spec it. Remaining: CI regen guard + Steropes parity (s1.4 T3.4/T3.5). |
| **R4** Redis cap | ✅ Done (cap wired through `PipeOptions.maxBytes`; real `ByteCapExceeded` test). | kept |
| **R5** Rule-6 trailers | ✅ Done well (binary `Metadata` key; `suppressLint` hack gone). | kept |
| **R6** onCompleted-after-onError | ⚠️ Half-done: fixed in `handle()` but `evict`/`describe` still fell through to `onCompleted()` after `onError()`. | **Fixed** evict/describe (return after onError). |
| **R7** doc reconciliation | ✅ Mostly (s1.2 doc, plan §9, `tasks-p1-s1.4` created). | Corrected doc claims that said multi-batch is "broken/fixed-in-1.4" (it's fixed now); struck contracts §6. |
| **R8** cleanups | Incomplete (agent stuck on ktlint). | **Finished:** R8.1 `MoveKind` removed, R8.2 dead `seaweed` field removed, R8.3 already done, R8.4 `at()`/`toSeaweed()` removed, R8.5 verified, R8.6 source-allocator leak fixed (reader uses the shared parent), R8.7 empty-`Location` → `INVALID_ARGUMENT` (+2 planner tests), R8.8 `/ready` KDoc trimmed. R8.9 (stateful `setLocation`) left as-is (optional). |

**Result: `:services:charon:test` + `ktlintCheck` green — 145 tests, 0 failures.**
(MovePlanner 85, CharonMoveExecutor 15, Errors 12, Integrity 9, MultiBatchMove 3,
RedisEndpoint 11, RequestValidation 7, ResponseMessageTrailer 3.)

**Still open before `charon/v0.1.0`:**
1. ~~R3 canonical fingerprint~~ **DONE** (Bora-approved 2026-06-15; cross-engine
   equality enforced in `IntegritySpec`). Residual: CI regen guard + Steropes parity
   (s1.4 T3.4/T3.5) — not blocking the algorithm.
2. **Stage 1.4 integration pass** (`tasks-p1-s1.4-integration.md`): live K3s
   Seaweed/Redis round-trip, bounded-memory streaming (the multipart hardening done
   *correctly* this time, with a readback assertion), real fault injection.
3. **R6.2 / R8.9** (minor): an explicit no-double-close test for evict/describe; the
   stateful `setLocation()` endpoint shape.

---

## BLOCKER

### R1 — Fix silent data loss on multi-batch streams

The endpoints buffer references to the reader's single reused `VectorSchemaRoot`,
so any source with more than one Arrow record batch is serialised as **empty**
while the move reports success. (review-006 B1.)

- [ ] **R1.1 — Write the failing gate test first.** Add
  `services/charon/src/test/kotlin/org/tatrman/kantheon/charon/core/MultiBatchMoveSpec.kt`.
  Build a real 3-batch Arrow IPC stream (batch row counts e.g. `2, 3, 4`, column
  `i: Int64` with values `0..8`). Drive it through `ArrowPipe.pipe` with a
  `Source` wrapping an `ArrowStreamReader` over those bytes and a `Target` that
  calls the **real** `SeaweedEndpoint.serializeBatchesToIpcStream`. Read the
  produced bytes back with an `ArrowStreamReader` and assert: total rows == 9 AND
  the values read back == `[0,1,2,3,4,5,6,7,8]` in order. Confirm it FAILS first
  (it will currently read back `[]`).
- [ ] **R1.2 — Fix the buffering.** In `ArrowPipe.pipe`, each batch handed to
  `target.writeBatch(...)` must be captured as **its own owned data**, not a
  reference to the reader's reused root. Choose ONE:
  - (preferred, also fixes R2) write each batch straight through to the target as
    it arrives — see R2; OR
  - if you keep buffering for now, deep-copy each batch into a freshly-allocated
    `VectorSchemaRoot` (via `TransferPair` into a new root owned by a per-move
    child allocator) inside `writeBatch`, so `pendingBatches` holds N distinct,
    fully-populated roots. Remember to close them after `commit`/`discard`.
- [ ] **R1.3 — Run R1.1; it must now pass.** Also re-run the full charon suite —
  no regressions.
- [ ] Done evidence: _paste `./gradlew :services:charon:test` result here._

---

## HIGH

### R2 — Make the move actually stream (bounded memory) + S3 multipart

Architecture §2/§3/§10 require a streaming pump with bounded memory and "no full
materialisation"; the current code accumulates the whole dataset in memory and
does a single-shot `putObject`. (review-006 H1.)

- [ ] **R2.1 — Decide and record the approach.** Either implement true
  per-batch streaming to S3 via the AWS SDK **multipart upload** API
  (`createMultipartUpload` → `uploadPart` per batch/threshold → `completeMultipartUpload`,
  with `abortMultipartUpload` on `discard`), keeping the temp-key+rename atomicity;
  OR, if streaming-to-S3 is deferred, write the deferral explicitly into
  `docs/architecture/charon/architecture.md` §10 (risk row) AND hard-bound memory
  by enforcing the per-move byte cap before buffering. **Do not leave the doc
  claiming "streaming, no materialisation" while the code buffers.**
- [ ] **R2.2 — Write the test.** A move of a dataset large enough to exceed the
  multipart threshold (or your documented buffer cap) must (a) round-trip
  byte-identical schema + all rows, and (b) for the multipart path, assert
  `uploadPart` was called more than once. For the deferral path instead: a move
  exceeding the cap returns `RESOURCE_EXHAUSTED` (ties into R4).
- [ ] **R2.3 — Implement; suite green.**
- [ ] Done evidence: _paste result here._

### R3 — Replace the IPC-byte fingerprint with a canonical, implementation-independent one

The current "cross-check" compares the Kotlin output to a digest produced by the
Kotlin output — it proves nothing. (review-006 H2.) **The reviewer ran the real
diagnosis (pyarrow 18.0.0 vs Arrow Java 18.3.0); the resolution direction the dev
agent proposed ("reconcile the `ArrowStreamWriter` config to match Python") is NOT
viable. Measured facts:**

| schema (3 primitive fields) | SHA-256 of IPC bytes |
|---|---|
| Kotlin `notNullable` (Arrow Java 18.3.0) | `3c04fd33…` |
| pyarrow `nullable=True` (the agent's reported digest) | `bbb57020…` |
| pyarrow `nullable=False` (logically matches Kotlin) | `3bf31b9a…` |
| pyarrow re-serialize of the schema **parsed from the Kotlin bytes** | `df43adfb…` |

Two independent defects were tangled together:
1. **Fixture mismatch.** The agent's Python digest (`bbb57020…`) is `nullable=True`
   (pyarrow's default), but the Kotlin fixture uses `FieldType.notNullable`. They
   compared different logical schemas.
2. **Raw IPC bytes are not a stable cross-impl identity — proven, not theorised.**
   Even after matching nullability, Kotlin (`3c04fd33`) ≠ pyarrow (`3bf31b9a`); the
   streams differ structurally (Kotlin 280 B / Python 240 B) though pyarrow reads
   the Kotlin bytes back as a **logically identical** schema (`schema.equals → True`).
   Worse, pyarrow is not even self-consistent: re-serializing the schema it parsed
   from Kotlin gives a **third** digest (`df43adfb`). Hashing Arrow IPC flatbuffer
   bytes is sensitive to encoder/version/round-trip artefacts, not just the logical
   schema. **No `ArrowStreamWriter` config knob fixes this** — it is the flatbuffer
   encoder, and it diverges across implementations and across round-trips.

**Therefore the byte-hash approach must be abandoned on BOTH sides.** This is not
Charon adapting to Python — the existing `workers/polars` `_schema_fingerprint`
(`grpc_service.py:316`) is itself fragile and must change too. Since that Python
impl forks into **Steropes** (fork Phase 3), this is a cross-service contract
decision, not a Charon-local one.

**✅ APPROVED + IMPLEMENTED (2026-06-15, Bora).** Canonical implementation-independent
fingerprint adopted. Done:

- [x] **R3.1 — Canonical algorithm decided + recorded.** Logical-schema string
  `name|type|nullability[<child;child;…>]`, type tokens spell out all params with
  shared unit tokens (`s|ms|us|ns`, `day`, `ym|dt|mdn`), metadata excluded, SHA-256
  of UTF-8 bytes. Recorded in `contracts.md` §6 (the "byte-compatible" language is
  struck) with the Steropes obligation noted.
- [x] **R3.2 — Implemented in `Integrity.kt`.** `fingerprint(schema)` now hashes
  `canonicalSchemaString(schema)`; raw-IPC-bytes path removed. Signature unchanged.
- [x] **R3.3 — Genuine cross-language fixture.** `regenerate.py` is the byte-identical
  Python reference; fixtures `python-canonical-{fingerprint.hex,schema.txt}` checked
  in. `IntegritySpec` builds the identical rich schema (utf8/int64/float64/decimal128/
  timestamp+tz/date32/bool/binary/struct) and asserts the Kotlin canonical string +
  digest **equal** the Python output (digest `69779ea6…`). Version-independent —
  stable across Arrow/pyarrow bumps.
- Remaining: **CI regen+diff guard** + **Steropes parity** (fork Phase 3) — moved to
  `tasks-p1-s1.4-integration.md` T3.4 / T3.5.
- ~~R3.4 — keep-the-IPC-byte fallback~~ **N/A:** Bora approved the canonical
  fingerprint (R3.1–R3.3), so the fallback path was not taken.
- [x] Done evidence: Python reference digest `69779ea65b0e127c59dc4f537bc33f62f08835c0098dbf313d61b35955fea7b8`;
  `IntegritySpec` "canonical fingerprint matches the Python reference byte-for-byte"
  green; `:services:charon:test` + `ktlintCheck` green (145 tests).

---

## MEDIUM

### R4 — Enforce the Redis value cap → `RESOURCE_EXHAUSTED`

Plan Stage 1.3 T1 names this; it is currently unimplemented (the field is stored
but never enforced; the pipe uses the 128 MiB generic default for Redis targets).
(review-006 M1.)

- [ ] **R4.1 — Write the failing test.** In `RedisEndpointSpec` (or
  `CharonMoveExecutorSpec`): a Redis target whose serialized value exceeds
  `redisMaxValueBytes` returns `Either.Left(CharonError.ByteCapExceeded)` and
  `CharonServiceImpl` maps it to gRPC `RESOURCE_EXHAUSTED`; assert **no `SET`
  fires**.
- [ ] **R4.2 — Implement.** Pass `redisMaxValueBytes` into the pipe's
  `PipeOptions.maxBytes` for Redis targets (or check in `RedisEndpoint.commit`
  before the `SET` and raise `CharonError.ByteCapExceeded`). Replace the existing
  tautological "maxValueBytes is plumbed from constructor" test with the real one
  (or keep it and add the real one).
- [ ] Done evidence: _paste result here._

### R5 — Attach Rule-6 `ResponseMessage`(s) to failure responses

Plan Stage 1.1 T6 and the class KDoc both promise the error payload rides on the
gRPC metadata/trailers; the code only sets `withDescription`. (review-006 M2.)

- [ ] **R5.1 — Write the test.** Over the in-process gRPC server
  (`RequestValidationSpec` style), assert that a rejected request carries the
  `ResponseMessage` (code + human_message + severity) on the `Status` trailers
  (a `Metadata` key holding the proto bytes).
- [ ] **R5.2 — Implement.** Use `CharonError.toResponseMessage()` /
  `toResponseMessages()` (already in `Errors.kt`) and attach via a
  `Metadata.Key` to the `StatusException`. Apply in `handle`, `evict`, `describe`.
- [ ] **R5.3 — If bare description is intentionally sufficient,** instead update
  `docs/architecture/charon/contracts.md` §1 to say so and delete the
  contradicting KDoc/T6 claim — but do not leave doc and code disagreeing.
- [ ] Done evidence: _paste result here._

### R6 — Stop calling `onCompleted()` after `onError()`

gRPC protocol misuse in `handle`, `evict`, `describe`. (review-006 M3.)

- [ ] **R6.1 — Fix.** Ensure each method calls `onCompleted()` **only** on the
  success branch (move it inside the `Either.Right` / `Planned.Plan→Right` arm,
  or `return` immediately after every `onError(...)`).
- [ ] **R6.2 — Test.** Add/extend a test asserting a rejected request closes the
  call exactly once with the error status and does not also complete. (At minimum,
  verify no `IllegalStateException`/duplicate-close is logged; ideally assert via
  a `ServerCallStreamObserver` spy or the in-process client seeing one terminal
  event.)
- [ ] Done evidence: _paste result here._

---

## DOC RECONCILIATION

### R7 — Reconcile the Stage 1.2 task list and the Testcontainers decision

(review-006 M4.) Per `reviews.md`, deviations from the task list must be surfaced
and the docs corrected.

- [ ] **R7.1 — Tick/strike `tasks-p1-s1.2-arrow-seaweed.md`.** Mark the boxes
  for work that actually shipped; strike (with a dated note) T3's
  `SeaweedEndpointSpec` and T6's `SeaweedRoundTripSpec` as "folded into mocked
  `CharonMoveExecutorSpec`, Testcontainers dropped — decision Bora 2026-06-14".
- [ ] **R7.2 — Record the mock-only decision in the right place.** Add the
  "mocked `S3Client`, not Testcontainers; live round-trip deferred to a separate
  integration pass" note to `tasks-p1-s1.2-*.md` AND to
  `docs/architecture/charon/architecture.md` §9 (Testing strategy), so §9 no
  longer prescribes Testcontainers for the unit gate.
- [ ] **R7.3 — Give the deferred integration pass a tracked home.** It currently
  exists only as prose in 1.3 T5/T6 (both unchecked). Create either
  `docs/implementation/v1/charon/tasks-p1-s1.4-integration.md` (live K3s
  Seaweed+Redis round-trip + fault injection) or an entry in
  `docs/implementation/v1/kantheon-v1.1.md`, and reference it from the plan's
  Phase-1 progression checklist.
- [ ] **R7.4 — Do not re-tag `charon/v0.1.0` until R1–R6 are closed.** Update the
  plan §9 checklist note accordingly.

---

## CLEANLINESS (do in one sweep; ktlint must stay green)

### R8 — Remove dead code and smells

(review-006 L1–L6.)

- [ ] **R8.1** — Delete `private enum class MoveKind` and the unused `kind`
  parameter of `CharonMoveExecutor.copyOrMaterialize`.
- [ ] **R8.2** — Remove the never-read `private val seaweed` field in
  `CharonMoveExecutor` (or reuse it consistently the way `redis` is reused).
- [ ] **R8.3** — Remove unused `Integrity.sumCounts()` and `readerBytesDelta`.
- [ ] **R8.4** — Remove unused `SeaweedEndpoint.at()` and `Location.toSeaweed()`
  from `main` (move to a test helper only if a test needs them).
- [ ] **R8.5** — Delete `CharonServiceImpl.suppressLint()` and the now-unused
  `io.grpc.StatusException` import (use `StatusException` properly in R5, or drop it).
- [ ] **R8.6** — Close the `RootAllocator` created in `SeaweedEndpoint.open()` and
  `RedisEndpoint.open()` (use a shared parent allocator + per-move child closed
  after the reader, or close it explicitly). (review-006 L3.)
- [ ] **R8.7** — Guard `Location.kind()`'s empty-location case so an unset
  `oneof` maps to `INVALID_ARGUMENT`, not an uncaught `error()`. Add a planner
  test for an empty `Location`. (review-006 L5.)
- [ ] **R8.8** — Trim the `/ready` KDoc in `Application.kt` to match what it
  actually checks (or make it check the constructed clients). (review-006 L6.)
- [ ] **R8.9** — Refactor the stateful `setLocation()` endpoint pattern toward a
  per-move immutable handle/factory **only if** R1/R2 don't already restructure
  it. (review-006 L4 — optional if superseded.)
- [ ] Done evidence: _paste `./gradlew :services:charon:test :services:charon:ktlintCheck` result here._

---

## Stage exit gate (do not mark Charon P1 done until ALL are true)

- [ ] R1–R6 closed with passing tests (multi-batch round-trip + Python
  fingerprint fixture present in the suite).
- [ ] Full charon suite green; ktlint clean.
- [ ] R7 docs reconciled; deferred integration pass tracked.
- [ ] `git log` inspected to confirm the code state matches these boxes
  (`AGENTS.md` §12 — don't trust the checkboxes alone).
- [ ] Only then tag `charon/v0.1.0`.
