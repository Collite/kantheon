# Charon P1 Stage 1.4 — Phase 1 closeout + `charon/v0.1.0` re-tag

> **Renamed + realigned 2026-06-26** (was `tasks-p1-s1.4-integration.md`). The original file framed Stage 1.4 as a **live-K3s integration pass** and gated the `charon/v0.1.0` re-tag on it. That predates the **testing policy** ([`../../planning-conventions.md`](../../planning-conventions.md) §4, locked 2026-06-14): Testcontainers / live-K3s / e2e are a **separate integration-test suite** and do **not** gate a stage. This file is restructured accordingly:
> - **Part A — the re-tag closeout gate** (mocked-unit + CI + code). Achievable now; this is what re-tags `charon/v0.1.0` and unblocks **Pythia Phase 4.1 CG1** ([`../pythia/tasks-p4-s4.1-charon-dataframe.md`](../pythia/tasks-p4-s4.1-charon-dataframe.md)).
> - **Part B — integration-suite carry-overs** (live K3s Seaweed/Redis). Tracked, **not** stage-gating.
>
> **Cross-references.** [`tasks-review-006.md`](../../../tasks-review-006.md) (the R1–R8 closeout — re-review 2026-06-15: R1–R8 **done in code**, 145 tests green), [`tasks-p1-s1.2-arrow-seaweed.md`](./tasks-p1-s1.2-arrow-seaweed.md), [`tasks-p1-s1.3-redis.md`](./tasks-p1-s1.3-redis.md), [`../plan.md`](./plan.md) §9, [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §6 (canonical fingerprint), [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md) §9.

## Where this stands (2026-06-26)

Per the review-006 re-review and a code scan:

| Item | State | Evidence |
|---|---|---|
| R1 multi-batch data-loss fix | ✅ done | `core/MultiBatchMoveSpec.kt` (deep-copy via `TransferPair`; readback gate) |
| R2 single-shot coherent PUT + memory bound | ✅ done | reverted to temp-key+copy+delete; readback asserted |
| R3.1–R3.3 canonical fingerprint (Kotlin == Python) | ✅ done | `core/Integrity.kt` + `fixtures/integrity/` ; digest `69779ea6…` |
| R4 Redis byte-cap → `RESOURCE_EXHAUSTED` | ✅ done | `RedisEndpointSpec` real `ByteCapExceeded` |
| R5 Rule-6 trailers on failures | ✅ done | `grpc/ResponseMessageTrailerSpec.kt` |
| R6.1 no `onCompleted` after `onError` | ✅ done (handle/evict/describe) | re-review fix |
| R8 cleanups | ✅ done | re-review sweep |
| **Cross-engine pin (Steropes + Brontes)** | ✅ landed via fork Stage 3.4 | `shared/testdata/fingerprints/` (anchor `reference.arrow`), `workers/steropes/tests/test_fingerprint.py`, `workers/brontes/.../SchemaFingerprintCrossEngineSpec.kt` |
| **T3.4 CI fingerprint regen+diff guard** | ✅ **done (S1.4 T3)** | `.github/workflows/ci.yml` "cross-engine fingerprint regen+diff guard" step (pyarrow==18.0.0, `git diff --exit-code`) |
| **Charon `IntegritySpec` ↔ shared pin alignment** | ✅ **done (S1.4 T2)** | `IntegritySpec` recomputes against `shared/testdata/fingerprints/` incl. `map.arrow` (entries-wrapped); private `fixtures/integrity/` **deleted** |
| R6.2 explicit no-double-close test | ✅ **done (S1.4 T4)** | `grpc/NoDoubleCloseSpec.kt` — counting `StreamObserver` spy, evict/describe/materialize error paths assert exactly-one onError, zero onCompleted |

So the closeout is small: one CI guard, one fixture-alignment, one minor test, the doc/plan reconciliation, and the re-tag — **all done 2026-06-26** (164 tests green). **No new feature work.**

---

# Part A — Re-tag closeout gate (the `charon/v0.1.0` gate)

> Mocked-unit + CI + code only — satisfies the stage under testing policy §4. This is the Pythia 4.1 CG1 unblock.

## Pre-flight

- [x] Worked on branch `charon` (Bora's directive: P1+P2+P3 on one branch, commit/push per phase).
- [x] `python` + `uv` available; regen verified locally (no drift).

## Tasks (TDD-shaped where code changes)

- [x] **T1 — Verify R1–R8 closed against the code (AGENTS.md §12).** Do **not** trust checkboxes: run `./gradlew :services:charon:test :services:charon:ktlintCheck` and confirm green (expected ~145 tests: MovePlanner, CharonMoveExecutor, Errors, Integrity, MultiBatchMove, RedisEndpoint, RequestValidation, ResponseMessageTrailer). Inspect `git log` for the R1/R2/R3 commits. Record the test count + commit shas in the PR. If any R-item regressed, fix before proceeding.

  Acceptance: full charon suite + ktlint green; evidence pasted in PR.

- [x] **T2 — Align Charon `IntegritySpec` to the shared cross-engine pin.** The authoritative cross-engine anchor is now `shared/testdata/fingerprints/` (`reference.arrow` + `fingerprints.json`, digest `69779ea6…`) — shared by Steropes (`test_fingerprint.py`) and Brontes (`SchemaFingerprintCrossEngineSpec.kt`). Point Charon's `core/IntegritySpec.kt` at the **same** shared fixtures (recompute `Integrity.fingerprint` over `reference.arrow` / `scalars.arrow` / `list.arrow` / `map.arrow` and assert equality with `fingerprints.json`), so all three engines verify against one anchor rather than Charon's private `fixtures/integrity/` copy. Fix the latent `children(map)` flat-vs-entries-wrapped inconsistency (contracts §6 "Stream B" follow-up: adopt the entries-wrapped `{key,value}` form) so the `map.arrow` fixture passes.

  Tests-first: add the `map.arrow` assertion (it will fail on the flat form), then fix `Integrity.kt` / `regenerate.py` to the entries-wrapped form.

  Acceptance: `IntegritySpec` recomputes against `shared/testdata/fingerprints/` incl. `map.arrow`; green. The private `fixtures/integrity/` copy is either deleted or documented as superseded.

- [x] **T3 — CI fingerprint regen+diff guard (review-006 T3.4).** Add a `.github/workflows/ci.yml` step that runs the shared `shared/testdata/fingerprints/generate.py` in a uv venv (`pyarrow==18.0.0`) and **diffs** the output against the checked-in `fingerprints.json`; any drift fails the build (forcing a conscious regen or an encoder fix). This locks Kotlin (Charon/Brontes) ↔ Python (Steropes) fingerprint identity in CI — **the guarantee Pythia's PD-5 drift detection relies on** ([pythia contracts §3a](../../../architecture/pythia/contracts.md)).

  Acceptance: CI step present + green; an intentional fixture perturbation fails the build (verify once, then revert).

- [x] **T4 — R6.2 explicit no-double-close test.** Add a test (over the in-process gRPC server, `RequestValidationSpec` style) asserting a rejected `evict` / `describe` closes the call exactly once with the error status and does **not** also `onCompleted()` — a `ServerCallStreamObserver` spy or the in-process client seeing one terminal event. (R6.1 fixed the code; this pins it.)

  Acceptance: no-double-close test green for handle/evict/describe.

- [x] **T5 — Doc + plan reconciliation (finish R7).** Update [`../plan.md`](./plan.md) §9: tick Stage 1.4 closeout; replace the "tag is a candidate, blocked on review-006" note with "re-tagged at 1.4 closeout (mocked-unit + CI gate); live-K3s pass tracked in the integration suite". Confirm [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md) §9 (Testing strategy) points the live round-trip at the integration suite, not the unit gate. Ensure the Pythia CG1 reference still resolves to this file's new name.

  Acceptance: plan §9 + architecture §9 reconciled; no doc claims the live pass gates the tag.

- [x] **T6 — Re-tag `charon/v0.1.0`.** Check the tag history (`git tag -n | grep charon`); if `v0.1.0` was already published on the candidate commit, bump to **`charon/v0.1.1`** (and note it in the plan). Tag the closeout commit. **This flips Pythia 4.1 CG1 green** — update [`../pythia/tasks-p4-s4.1-charon-dataframe.md`](../pythia/tasks-p4-s4.1-charon-dataframe.md) CG1 + the [`../pythia/tasks-p4-overview.md`](../pythia/tasks-p4-overview.md) note when done (note: CG2/CG3 still gate on Charon Phases 2–3).

  Acceptance: tag pushed; CI green on `[charon-p1-s1.4] closeout + re-tag`.

## DONE — Part A (Phase 1 closeout)

- [x] T1–T6 checked; full charon suite + ktlint green (164 tests); CI fingerprint guard live.
- [x] **Tag `charon/v0.1.1` applied** (candidate `v0.1.0` on `74b28b3` superseded). **Charon Phase 1 closed** — Stage 2.1 may start; Pythia 4.1 CG1 cleared.

---

# Part B — Integration-suite carry-overs (tracked, NOT stage-gating)

> Per testing policy §4 these run in the **separate integration-test suite** (live local-K3s infra), built after the mocked implementation lands. They do **not** gate the `charon/v0.1.0` re-tag or any Charon stage. Listed here so nothing is lost; move to the integration-suite arc when it's planned.

## IS-1 — Live K3s Seaweed round-trip + fault injection
- [ ] Live `data-seaweedfs:8333` reachable from a test JVM with prod-shaped credentials (sealed secret / fixture).
- [ ] Single-batch + **multi-batch** (3-batch, 9-row) round-trip against **real** Seaweed — byte-identical schema fingerprint + all rows + `MoveResult.rowCount`.
- [ ] Mid-stream fault (Toxiproxy / iptables drop): `EndpointUnavailable`; target key never visible (HEAD 404); temp key gone; no partial object.
- [ ] Multipart behaviour against real S3 (if/when streaming-multipart is taken up — currently single-shot coherent PUT per R2): `UploadPart` >1 / `AbortMultipartUpload` on abort.
- [ ] Retention-tag → object-tag round-trip (local Seaweed may not honour tags — assert SDK call shape).

## IS-2 — Live K3s Redis round-trip + TTL
- [ ] `data-redis:6379` reachable; single-value round-trip (value + sidecar, fingerprint matches, rows match).
- [ ] TTL honoured (`EX 2`, sleep 3 s → null; sidecar expired alongside).
- [ ] Sidecar drift (value only) → `Source.open()` null → `SourceNotFound`.
- [ ] Multi-batch round-trip against real Redis; oversize value → `RESOURCE_EXHAUSTED` (real backend).

## IS-3 — Live cross-engine fingerprint at runtime
- [ ] (Beyond the CI fixture guard in Part A T3) confirm a real Seaweed→Steropes→Seaweed move preserves the canonical fingerprint end-to-end against live pods.

---

## Library / pattern references

- **tasks-review-006.md** — the authoritative R1–R8 closeout (done in code); **contracts §6** — canonical fingerprint + the "Stream B" `children(map)` follow-up; **architecture §9** — testing strategy.
- **`shared/testdata/fingerprints/`** — the cross-engine anchor (Charon/Brontes/Steropes recompute against it); **planning-conventions §4** — why the live pass is a separate suite.

## Out of scope

- Phase 2 (DB edges), Phase 3 (worker + MCP) — separate stages ([`tasks-p2-s2.1-connections-extract.md`](./tasks-p2-s2.1-connections-extract.md) onward).
- Building the integration-test suite itself — its own arc (Part B is the Charon backlog for it).
