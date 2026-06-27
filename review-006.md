# Review 006 — Charon Phase 1 (object-store mover)

**Scope.** Charon arc, Phase 1 (Stages 1.1 / 1.2 / 1.3) as delivered on branch
`feat/charon-p1-s1.3-redis` (HEAD `74b28b3`, contains 1.1 + 1.2 + 1.3). The
developing agent claims "Charon P1 is done" and tags `charon/v0.1.0`.

**Method.** Reviewed against `reviews.md` instructions: architecture vs. code,
plan vs. delivery, modularity, cleanliness, extensibility, and **deviations from
the task lists**. Read the arc docs (`docs/architecture/charon/{architecture,contracts}.md`,
`docs/implementation/v1/charon/{plan,tasks-p1-s1.1,tasks-p1-s1.2,tasks-p1-s1.3}.md`),
all eight source files, all six test specs, the proto, the build, and the
bootstrap. Built and ran the suite (**136 tests, 0 failures, ktlint clean**),
then wrote a throw-away probe to exercise the one path the suite never covers
(a multi-batch Arrow stream). The probe **failed**, surfacing a silent
data-loss bug (Finding 1).

**Verdict.** **P1 is NOT done.** The skeleton, planner, legality matrix, error
model, and proto are genuinely good work — clean, well-modularised, the
table-driven legality matrix is exemplary. But the actual *move core* — the one
thing Phase 1 exists to deliver ("Seaweed ↔ Redis moves with fingerprint
verification and no-partial-write semantics") — is correct **only for
single-batch datasets**, silently loses all data on multi-batch streams while
reporting success, and the cross-engine fingerprint invariant that the whole
design rests on is asserted by a tautology rather than verified. Three of the
plan's named acceptance criteria (Redis byte cap, Rule-6 error payloads, the
Python fingerprint cross-check) are documented as done but are not actually
implemented/verified. These must be fixed before `charon/v0.1.0` is real.

---

## Severity legend

- **BLOCKER** — correctness or data-integrity defect; P1 cannot ship.
- **HIGH** — a named plan acceptance criterion is not met, or an architectural
  invariant is violated.
- **MEDIUM** — contract/behaviour gap or protocol misuse; fix before P2 builds on it.
- **LOW** — cleanliness, dead code, doc drift.

---

## BLOCKER

### B1 — Multi-batch moves silently produce an EMPTY object and report success

**Where.** `endpoints/SeaweedEndpoint.kt` (`writeBatch` + `serializeBatchesToIpcStream`),
`endpoints/RedisEndpoint.kt` (`writeBatch` + reuse of the same serializer),
driven by `core/ArrowPipe.kt`.

**What.** The Source/Target pump in `ArrowPipe.pipe` loops:

```kotlin
while (src.loadNextBatch()) {
    ...
    target.writeBatch(writeReceipt, src.vectorSchemaRoot)   // same root object every iteration
    ...
}
```

`ArrowStreamReader.getVectorSchemaRoot()` returns **one** `VectorSchemaRoot`
that `loadNextBatch()` reloads *in place* on each call. Both endpoints'
`writeBatch` do `r.pendingBatches.add(root)` — i.e. they buffer **N references
to the same object**. By the time `commit()` runs, the reader is exhausted and
that single root holds 0 rows, so `serializeBatchesToIpcStream` (which does
`if (batch.rowCount == 0) continue`) skips *every* buffered entry and emits a
**schema-only stream**.

**Proven.** A 3-batch source (rows 0..8) driven through the real pipe and the
real `SeaweedEndpoint.serializeBatchesToIpcStream` round-trips to `[]` (zero
rows) — while `ArrowPipe` returns `Either.Right` with `MoveResult.rowCount = 9`
and a valid fingerprint. So the caller is told "9 rows moved successfully" and
the stored object contains **nothing**. This is silent data loss with a
success status and a plausible row count — the worst possible failure mode for
a data mover, and exactly what Pythia will depend on for evidence persistence.

**Why the suite is green.** Every test feeds a *single* 100-row batch
(`CharonMoveExecutorSpec`) or drives `commit()` with an empty `pendingBatches`
(`RedisEndpointSpec`). Single-batch never reuses the root, so the bug is masked.
Real datasets above one IPC record batch (the common case for anything large —
the very thing `chunk_rows = 65536` exists for) hit it immediately.

**Fix.** Each buffered batch must be *materialised into its own owned vectors*
at `writeBatch` time (deep copy via `TransferPair` into a fresh root, or write
straight through — see B2), not stored as a reference to the reader's reused
root. Add a multi-batch round-trip test as the gate (see tasks-review-006 R1).

---

## HIGH

### H1 — The "streaming pump, bounded memory" invariant is not implemented

**Where.** `SeaweedEndpoint.writeBatch`/`commit`, `RedisEndpoint.commit`.

**What.** Architecture §2/§3 specify "streaming IPC reader → chunker → writer;
bounded memory", and §10 lists "Large-blob memory pressure" as a risk
*mitigated by* "streaming pump only — bounded chunk size, no full
materialisation in Charon". The implementation does the opposite: it buffers
**all** batches and serialises the entire dataset in one `ByteArrayOutputStream`
at `commit()` (the code comment admits "The cost is O(total bytes) memory").
For S3 this also forces a full in-memory blob before a single-shot
`putObject` — the multipart-over-threshold behaviour the plan (Stage 1.2 T3 case
(b), "1.5M-row dataset … the put uses multipart") claims is **absent**; there
is no multipart upload anywhere in `SeaweedEndpoint`.

Even once B1 is fixed by deep-copying batches, memory is still O(total bytes).
The fix for B1 and H1 is the same shape: stream batch-by-batch to the target
(S3 multipart upload part-per-batch, or at minimum a bounded buffer with a
documented cap) rather than accumulate-then-flush.

**Note.** This is a real architectural deviation, not a deferral — nothing in
the task lists says "buffering is acceptable for P1". If buffering *is* an
accepted interim simplification, it must be written into the architecture doc
(risk §10) and the per-move byte cap must hard-bound it; right now the cap
(B-relatedly) isn't enforced for Redis at all (M1).

### H2 — The fingerprint "cross-check" is a tautology, not a cross-check

**Where.** `IntegritySpec.kt`, test "cross-check fixture: 3-column fingerprint
matches the workers/polars reference".

**What.** The load-bearing cross-engine invariant (architecture §9; §10 risk
"Fingerprint algorithm drift vs workers — shared fixture cross-check test
pinned in CI"; contracts §6; plan Stage 1.2 T1(b): compute "the Python way …
the spec pins the expected hex digest [from Python]") is supposed to prove that
Charon's Kotlin `Integrity.fingerprint` produces **byte-identical** output to
`workers/polars` `_schema_fingerprint`. The test instead does:

```kotlin
val expected = "3c04fd33…"          // comment: "Pinned … against the Kotlin Arrow 18.3.0 output"
val actual = Integrity.fingerprint(schema)
actual shouldBe expected
```

Both sides come from the Kotlin implementation. The comment openly states the
"Bora-side Python run (Python 3.13 + pyarrow 17.0.0) is the confirming check"
— i.e. the Python comparison **was never run**. The test guards against future
Kotlin drift only; it does **not** verify Python compatibility. Given the
version skew (pyarrow 17 vs Arrow Java 18.3) IPC schema-message bytes are
*plausibly* different (continuation/alignment/metadata ordering), this invariant
may already be broken and the suite would never know. Until a Python-generated
digest is pinned, the cross-engine pipe contract is unproven.

**Fix.** Generate the digest from the actual Python `_schema_fingerprint` for
the reference schema, commit it as a fixture (a checked-in hex value with a
script/CI job that regenerates it), and assert the Kotlin output equals *that*.
If they differ, reconcile the IPC writer config — that reconciliation is itself
Phase-1 work, not a deferral.

**DIAGNOSED (post-review, reviewer-run — pyarrow 18.0.0 vs Arrow Java 18.3.0).**
The "reconcile the IPC writer config" hint above is **wrong** — superseded by the
measured result. The same logical schema yields four different SHA-256s:
Kotlin notNullable `3c04fd33`, pyarrow nullable `bbb57020` (the agent's reported
digest — a *nullability* fixture mismatch), pyarrow notNullable `3bf31b9a`, and
pyarrow re-serialising the schema it parsed back from the Kotlin bytes `df43adfb`.
pyarrow confirms the Kotlin bytes decode to a logically identical schema
(`schema.equals → True`), so the divergence is pure flatbuffer encoding, not a
config knob (streams are even different lengths: 280 B vs 240 B). Hashing raw
Arrow IPC bytes is **not** a stable schema identity across implementations or even
across a pyarrow round-trip. **Resolution: abandon the IPC-byte hash on both sides
for a canonical, implementation-independent fingerprint computed from the logical
schema** — a cross-service contract decision (the Python side is `workers/polars`
→ Steropes, fork Phase 3), recorded in `contracts.md` §6. Full corrected
instructions + the evidence table are in `tasks-review-006.md` R3.

---

## MEDIUM

### M1 — Redis `max-value-bytes` cap → `RESOURCE_EXHAUSTED` is not implemented

**Where.** `RedisEndpoint`, `CharonMoveExecutor.pipeWithOptions`, `RedisEndpointSpec`.

**What.** Plan Stage 1.3 T1 names "max-value-bytes cap → `RESOURCE_EXHAUSTED`"
as an acceptance case. `RedisEndpoint.maxValueBytes` is stored but **never read**
for enforcement; `commit()` `SET`s the bytes regardless of size. The pipe's
byte cap (`PipeOptions.maxBytes`) is wired from `plan.options.maxBytes ?: 128 MiB`
— the generic default, **not** the Redis cap — so a Redis target is bounded at
128 MiB, not `charon.redis.max-value-bytes`. The only test
("maxValueBytes is plumbed from constructor") asserts `endpoint.maxValueBytes
shouldBe 8L` — a field read, not the contract. There is no test that an
oversize Redis value yields `RESOURCE_EXHAUSTED`, and no code path that produces
it.

**Fix.** Enforce the Redis value cap (either pass `redisMaxValueBytes` into the
pipe options for Redis targets, or check in `RedisEndpoint.commit` and raise
`CharonError.ByteCapExceeded`). Add the oversize→`RESOURCE_EXHAUSTED` test.

### M2 — Rule-6 error payloads are dropped on every failure

**Where.** `grpc/CharonServiceImpl.kt` (`handle`, `evict`, `describe`).

**What.** Plan Stage 1.1 T6: "each [error] carries a Rule-6 `ResponseMessage`
payload on the `metadata` key for the wire." The class KDoc repeats it: messages
ride "on the gRPC `metadata` (`StatusException.trailers`)". The implementation
does neither — it only calls `Status.withDescription(humanMessage)`. The
`toResponseMessage()` / `toResponseMessages()` helpers in `Errors.kt` exist but
are never attached to any trailer. The code comment even concedes "Rule-6
messages lost". So a failing caller gets a bare status code + a description
string, not the structured `ResponseMessage` the contract promises (and that
Pythia's resume logic, per contracts PD-5, may parse).

**Fix.** Attach the `ResponseMessage`(s) to the `Status` trailers via a
`Metadata` key (proto-bytes), or document a contract change in
`contracts.md` §1 if bare description is now deemed sufficient. Do not leave the
doc and code disagreeing.

### M3 — `onCompleted()` called after `onError()` (gRPC protocol misuse)

**Where.** `grpc/CharonServiceImpl.kt` — `handle`, `evict`, `describe`.

**What.** All three methods run a `when` that may call
`responseObserver.onError(...)`, then fall through to an **unconditional**
`responseObserver.onCompleted()`. Once `onError` closes the call, a trailing
`onCompleted` is illegal — grpc-java treats the call as already-closed (logged
warning at best, `IllegalStateException` in stricter paths). The success path is
fine (`onNext` then `onCompleted`); the error path must `return` after `onError`.

**Fix.** Move `onCompleted()` into the success branches only (or `return` after
each `onError`).

### M4 — Stage 1.2 task list never reconciled with what shipped (deviation)

**Where.** `docs/implementation/v1/charon/tasks-p1-s1.2-arrow-seaweed.md`.

**What.** Per `reviews.md` ("point out the deviations to the task list"):
- Stage 1.2's checkboxes are **all unticked `[ ]`** despite the work being
  claimed done and merged forward into 1.3 — the tracker lies about state.
- The planned `SeaweedEndpointSpec.kt` (T3) and `SeaweedRoundTripSpec.kt` (T6)
  were **never created**; their coverage was folded into the mocked
  `CharonMoveExecutorSpec`. The Testcontainers/MinIO approach mandated by T3/T4
  and architecture §9 was replaced by `mockk`-on-`S3Client`. This is a defensible
  call (attributed to "Bora 2026-06-14" in 1.3's notes and the build comment),
  **but** the 1.2 task doc still prescribes Testcontainers in full and was not
  updated. The decision is recorded in the *wrong* stage's doc.
- Consequence the mock-only choice hides: the no-partial-write / fault-injection
  guarantees are asserted against a mocked `S3Client`, so temp-key+rename
  atomicity is verified only at the call-shape level, never against a real S3
  semantics. That's acceptable as a unit gate **if** the deferred integration
  pass actually exists and is tracked — it currently lives only as prose in
  1.3 T5/T6 (both unchecked), not as a real suite or CI job.

**Fix.** Update `tasks-p1-s1.2-*.md`: tick what shipped, strike the Testcontainers
items, and reference the deferred integration pass as a tracked item (with a
home — e.g. a `tasks-p1-s1.4-integration.md` or an entry in `kantheon-v1.1.md`).
Reconcile the "mocked, not Testcontainers" decision into the 1.2 doc and
architecture §9.

---

## LOW (cleanliness / dead code / doc drift)

### L1 — Dead code
- `core/CharonMoveExecutor.kt`: the `private enum class MoveKind` and the `kind`
  parameter of `copyOrMaterialize(plan, kind)` are never used (metrics read
  `plan.rpc`). Remove both.
- `core/CharonMoveExecutor.kt:60`: `private val seaweed = SeaweedEndpoint(s3Client)`
  is never read — `evict`/`describe`/the move legs all `new` a fresh
  `SeaweedEndpoint(s3Client)`. The sibling `redis` field *is* reused. Make it
  consistent (reuse a field, or drop the field).
- `core/Integrity.kt`: `LongArray.sumCounts()` and `inline fun readerBytesDelta`
  are unused (the pipe inlines the delta arithmetic). Remove.
- `endpoints/SeaweedEndpoint.kt`: `fun SeaweedEndpoint.at(loc)` and
  `fun Location.toSeaweed()` appear unused in `main`. Remove or move to test
  helpers if only tests need them.

### L2 — `CharonServiceImpl.suppressLint()` is a hack
A dead `private fun suppressLint(): Pair<Status, StatusException>` exists purely
to stop ktlint flagging the `Status`/`StatusException` imports. `StatusException`
is otherwise unused — delete the import and the function rather than feeding the
linter a fake usage.

### L3 — `RootAllocator` leaked per move
`SeaweedEndpoint.open()` and `RedisEndpoint.open()` each do
`ArrowStreamReader(stream, RootAllocator())`. Closing the reader (via the pipe's
`use {}`) does **not** close the `RootAllocator` passed in — Arrow allocators are
native-memory-backed and must be closed explicitly. Every move leaks one
allocator. Use a single shared/parent allocator with a closed child per move, or
close the allocator after the reader. (Borderline Medium for a long-running pod.)

### L4 — Stateful endpoint design smell
`SeaweedEndpoint`/`RedisEndpoint` implement `Source, Target` with a mutable
`currentLocation` set via `setLocation()`, and `open()`/`begin()` `error()` if
it's unset. Combined with L1 (fresh instances created ad-hoc per leg), the
object lifecycle is muddled and not obviously thread-safe. Prefer
constructor-injected (immutable) location, or a small `Source.of(loc)` /
`Target.of(loc)` factory returning a per-move handle.

### L5 — Empty `Location` throws instead of `INVALID_ARGUMENT`
`Location.kind()` (MovePlanner.kt) does `else -> error("Location has no kind set")`.
A request with no `oneof` set therefore surfaces as an uncaught exception
(→ `UNKNOWN`/`INTERNAL`), not the `INVALID_ARGUMENT` the plan's "malformed
locations" case (Stage 1.1 T4) expects. Guard it and map to a
`CharonError.IllegalPair`-style `INVALID_ARGUMENT`.

### L6 — `/ready` KDoc overstates what it checks
`Application.module` serves a static `{status: UP, stage: 1.3}` for `/ready`;
the KDoc claims it returns 200 "once the gRPC server is bound and both S3 +
Redis clients are constructed". It checks none of that. Given the lazy-reachability
decision this is acceptable behaviour, but trim the KDoc to match, or have
`/ready` actually assert the constructed clients / bound server.

---

## What is genuinely good (keep)

- `core/Legality.kt` — the single-source-of-truth, compile-checked legality
  matrix with `ALLOWED | DISALLOWED | SAME_LOCATION` as a first-class outcome is
  exactly what the plan asked for and is a clean extensibility seam (new kind =
  one enum value + one row). `MovePlannerSpec` walks all 80 cells.
- `core/Errors.kt` — tidy sealed hierarchy, one variant per gRPC status, Rule-6
  helpers present (just not wired — M2).
- `core/MovePlanner.kt` — pure, no I/O, returns multi-error `Planned.Invalid`;
  good separation from the executor seam.
- The proto matches `contracts.md` §1 byte-for-byte; package convention correct.
- `MoveExecutor` seam + `SkeletonMoveExecutor` is a clean staged-fill design.

---

## Recommendation

Do not tag `charon/v0.1.0` / mark P1 done until **B1, H1, H2, M1, M2, M3** are
closed and a multi-batch round-trip test + a real Python fingerprint fixture are
in the suite. M4 (task-list reconciliation) and the L-items should be cleared in
the same pass. Fix list with exact instructions: `tasks-review-006.md`.
