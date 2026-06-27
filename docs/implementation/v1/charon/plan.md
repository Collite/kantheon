# Charon — Phased Implementation Plan (kantheon arc)

> **Scope.** From "Charon exists only as a contract" to "`services/charon` + `tools/charon-mcp` live in local K3s, moving Arrow between Seaweed / Redis / Polars Worker sessions / databases, registered in capabilities-mcp, benchmarked — ready for Pythia Phase 4." Three phases, eight stages, ~48 tasks.
>
> **Companions.** [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md), [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md); design origin `Pythia-v1-Design.md` §6.2.
>
> **Testing.** Per the testing policy ([`planning-conventions.md`](../../planning-conventions.md) §4): plans develop against mocked unit tests only (MockK, in-memory fakes, Wiremock, mocked clients/drivers); the integration-test suite (real Seaweed / Redis / ADBC drivers / live worker pod) is separate.
>
> **Arc position.** Independent of Iris/Golem — can run in parallel with either (it touches no agent surface). **Hard consumer deadline: Pythia Phase 4 pre-flight requires `charon/v0.3.0`.** Natural slot: during the Golem arc, or as the opening act of the Pythia arc. Charon is the first migrated platform-grade service and the first `services/` module — its conventions (module layout, gRPC bootstrap, ports) become the template Midas's `report-renderer` follows.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — proto + integrity core + object-store movers** | `services/charon` pod in local K3s; Seaweed ↔ Redis moves with fingerprint verification and no-partial-write semantics; `Describe`/`Evict` for both tiers. | 1.1 / 1.2 / 1.3 | ~1–1.5 weeks |
| **Phase 2 — database edges (ADBC)** | Named-connection registry; DB→Arrow extract and Arrow→DB ingest (PG + MSSQL) with allow-lists and write modes; deployed with provisioned connections. | 2.1 / 2.2 / 2.3 | ~1.5 weeks |
| **Phase 3 — worker integration + MCP wrapper + ship** | Polars Worker stage-in/read-out; `tools/charon-mcp` + `move.*` capability registration; benchmark + observability. Pythia-ready. | 3.1 / 3.2 | ~1 week |

Critical path: P1 → P2 → P3 (P2 and the worker part of P3 are independent; conservative default sequential).

## 2. Pre-flight — before Phase 1 starts

| Item | Status (2026-06-12) |
|---|---|
| `org.tatrman.charon.v1` package convention confirmed (migrated-service root) | locked 2026-06-12 |
| Seaweed S3 reachable from kantheon namespace (`data-seaweedfs:8333`) + credentials (sealed secret) | deployed (fabric-infra); kantheon-namespace credential provisioning is Stage 1.3 T5 |
| Redis reachable + binary-value sanity | platform Redis exists; smoke in Stage 1.3 |
| `cz.dfpartner.worker.v1` proto available via ai-platform Maven | true |
| Arrow Java + ADBC versions pinned in `gradle/libs.versions.toml` | Stage 1.1 T1 |
| Worker workspace **read-out** verification (scan-plan vs `ReadWorkspace` RPC) | **open — needed by Phase 3 Stage 3.1**; tracked in `aip-v1-gateway-worker-plan.md` |

## 3. Phase 1 — proto + integrity core + object-store movers

### Stage 1.1 — charon/v1 proto + module skeleton

**Goal.** Proto compiles; `services/charon` module exists with gRPC + probe bootstrap; conventions for `services/` settled.

**Tasks (6).**
1. Pin Arrow Java / ADBC / AWS SDK / Lettuce / grpc-kotlin versions in `libs.versions.toml`.
2. Write `org/tatrman/charon/v1/charon.proto` per contracts §1 (full file); `just proto`; KT bindings green. Note: proto path is outside `org/tatrman/kantheon/` — verify codegen config handles the second root.
3. Module skeleton `services/charon`: build.gradle.kts, `App.kt` (Ktor probes + gRPC server bootstrap), settings.gradle.kts entry, CI wiring; document the `services/` module conventions in the module README.
4. Tests first: `CharonServiceImplSpec` request-validation cases (illegal pairs per legality matrix §2, missing db_write_mode, malformed locations) against in-process gRPC server — all returning `INVALID_ARGUMENT`; implement `MovePlanner` legality matrix to green.
5. `Errors.kt` typed-failure → gRPC status mapping + `messages = 99` plumbing; unit specs.
6. k8s `base/` + `overlays/local/`; lint clean.

**DONE.** Module compiles; validation suite green; pod starts (endpoints stubbed NOT_IMPLEMENTED).

### Stage 1.2 — ArrowPipe + integrity + Seaweed endpoint

**Tasks (6).**
1. Tests first: `IntegritySpec` — fingerprint algorithm against shared fixtures **cross-checked byte-for-byte with `workers/polars` `_schema_fingerprint` outputs** (fixtures exported from the Python impl); row counting; chunk boundary cases.
2. `Integrity.kt` + `ArrowPipe.kt` (streaming IPC reader → chunker → writer; bounded memory; per-move byte cap).
3. Tests first: `SeaweedEndpointSpec` (mocked S3 client / in-memory fake) — put via temp-key + rename, get streaming, multipart over threshold, fault injection mid-stream asserts no visible partial object. (Real-S3 fidelity deferred to the separate integration-test suite.)
4. `SeaweedEndpoint` (Source + Target); retention-tag → object tagging for lifecycle rules.
5. Wire `Copy` seaweed→seaweed + `Describe`/`Evict` for seaweed; component test round-trip (reference dataset, schema byte-identical).
6. `MoveResult` assembly (fingerprint, rows, bytes, duration) + metrics counters.

**DONE.** Seaweed round-trip green incl. fault injection.

### Stage 1.3 — Redis endpoint + deploy

**Tasks (6).**
1. Tests first: `RedisEndpointSpec` (mocked Redis client / in-memory fake) — binary value integrity, TTL set/honoured, max-value-bytes cap → `RESOURCE_EXHAUSTED`. (Real-Redis fidelity deferred to the separate integration-test suite.)
2. `RedisEndpoint` (Source + Target); `Describe` (TTL surfaced) + `Evict`.
3. Full pump matrix for the two tiers (seaweed↔redis both directions) — component suite.
4. `Materialize` semantics complete for blob tiers; same-location no-op path.
5. Seaweed credentials + Redis URL provisioning for kantheon namespace (sealed secrets; fabric-infra change); `just deploy-kt charon`; readiness gates (S3 + Redis reachable).
6. Live K3s smoke vs deployed Seaweed/Redis; tag.

**DONE.** Tag `charon/v0.1.0`. **Phase 1 DONE — object-store mover live.**

## 4. Phase 2 — database edges (ADBC)

### Stage 2.1 — connection registry + ADBC spike gate + extract

**Tasks (7).**
1. Tests first: `ConnectionRegistrySpec` — YAML parse + env substitution, unknown id, allow-list enforcement (read/write/schema), pool config, `/refresh` reload.
2. `ConnectionRegistry` implementation.
3. **ADBC spike (gate):** unit-test both `AdbcReader/Writer` impls behind the common interface with a **mocked ADBC-JDBC driver** — bulk extract to Arrow, type-mapping coverage on the contracts §5 matrix at the driver boundary. Verdict recorded in module README: ADBC per dialect, or `arrow-jdbc` fallback per dialect. Both live behind the same `AdbcReader/Writer` interface either way. (Real-driver dialect fidelity against live PG + MSSQL deferred to the separate integration-test suite.)
4. Tests first: `AdbcReaderSpec` — reference tables (all mapped types, NULLs, empty table, wide rows) extract to expected Arrow schemas + fingerprints.
5. `AdbcReader` (DB → Arrow stream) per spike verdict; streaming, not full materialisation.
6. `Describe` for `DbTable` (schema from catalog; row count estimate vs exact flag).
7. Wire `Materialize`/`Copy` with db_table sources (db→seaweed, db→redis) — component tests.

**DONE.** Extract path green on PG + MSSQL; spike verdict documented.

### Stage 2.2 — ingest (Arrow → DB)

**Tasks (6).**
1. Tests first: `AdbcWriterSpec` — CREATE (fail-if-exists honoured), REPLACE (transactional swap; reader sees old-or-new never partial), APPEND (schema-compat check), unmappable type → `FAILED_PRECONDITION` naming the column, mid-stream fault → rollback clean.
2. Arrow→DDL type mapping per contracts §5 (deterministic; both dialects); property tests on round-trip (extract(ingest(x)) schema-equal).
3. `AdbcWriter` per spike verdict (bulk ingest; single transaction).
4. Write allow-list enforcement integration (never attempted on violation).
5. Wire `Materialize`/`Copy` with db_table targets (seaweed→db, redis→db, db→db cross-connection) — full legality-matrix component suite now complete.
6. `Evict(db_table)` rejection path + security-note review (table-level only, no query bypass) signed off.

**DONE.** Ingest green both dialects; full matrix suite green.

### Stage 2.3 — deploy + provisioned connections

**Tasks (4).**
1. v1 connection registry content (**Bora**: which named connections exist — e.g. ERP replica read-only, analytics-staging read-write) + sealed secrets in fabric-infra.
2. Deploy; lazily-validated connections surface in `/ready` degraded-set semantics (one broken DB ≠ unready pod).
3. Live smoke: ERP-replica table → `pythia-evidence` Seaweed blob; staging round-trip.
4. Tag.

**DONE.** Tag `charon/v0.2.0`. **Phase 2 DONE — DB edges live.**

## 5. Phase 3 — worker integration + MCP wrapper + ship

**Pre-flight.** Worker workspace read-out verified (scan-plan streams `ResultBatch` out) **or** `ReadWorkspace` RPC landed in ai-platform (`aip-v1-gateway-worker-plan.md` item).

### Stage 3.1 — Polars Worker endpoint

**Tasks (6).**
1. Tests first: `WorkerEndpointSpec` against a gRPC fixture worker — stage-in plan construction (`assign_to_workspace`), read-out scan, session reuse, workspace-cap error mapping (`workspace_cap_exceeded` → `RESOURCE_EXHAUSTED`).
2. `WorkerEndpoint` (Target via `Stage`; Source via scan-out) per the verified read-out path.
3. `Stage` RPC complete (any source → worker session); cross-engine/cross-session stage (worker→worker).
4. `Describe`/`Evict` for worker DFs (`GetStatus` stats; DF drop).
5. Component test (mocked worker + mocked Seaweed): seaweed→worker→seaweed round-trip asserts byte-identical schema through the endpoint wiring. (Live in-K3s round-trip against the real `worker-polars` pod deferred to the separate integration-test suite — the seaweed→worker→seaweed capability is unchanged.)
6. Legality matrix final review; matrix doc in contracts §2 confirmed against implementation (table-driven test generates the doc table).

### Stage 3.2 — charon-mcp + registration + bench + ship

**Tasks (6).**
1. Tests first: `McpToolsSpec` — JSON↔proto fidelity per tool, error pass-through incl. `messages`.
2. `tools/charon-mcp` module (thin wrapper; ktor-configurator MCP base; zero logic — review-enforced).
3. `move.*:v1` ToolCapability manifests + heartbeat via `capabilities-client`; visible in capabilities-mcp `list()`.
4. `bench/` harness: rows/s + MB/s per legal pair on 1e5/1e6-row reference sets; results → manifests' `cost_hints` + module README.
5. Observability completion: metrics per architecture §8, Grafana panel set, trace nesting verified from a fixture caller.
6. Docs (READMEs, kantheon-architecture cross-refs) + tags.

**DONE.** Tags `charon/v0.3.0`, `charon-mcp/v0.1.0`. **Phase 3 DONE — Pythia Phase 4 unblocked.**

## 6. Cross-cutting

| Item | Where |
|---|---|
| Worker read-out verification (scan-plan vs `ReadWorkspace`) | `aip-v1-gateway-worker-plan.md` (ai-platform side); P3 pre-flight here |
| Fingerprint cross-check fixtures (Python ↔ Kotlin) | CI job pinned from Stage 1.2 on |
| `services/` module conventions (first instance) | Stage 1.1 T3; Midas report-renderer follows |
| Pythia consumes via `CharonClient` | Pythia plan Stage 4.2 (Pythia arc — not here) |

## 7. Out of scope (v1.x triggers)

- Async moves + NATS completion events (trigger: first real >30 s move).
- Predicate pushdown on DB extract (trigger: staging volumes demand it; needs security review).
- Parquet/CSV endpoint kinds; DuckDB worker kind (trigger: first consumer).
- Provenance ledger (trigger: cross-agent lineage requirements).
- List/Struct/Map Arrow types over DB edges.
- Metis `WorkerKind` activation — cross-arc: Charon Stage 3.1 gains the METIS endpoint variant against Metis contracts §1.3 once Metis Phase 1 is live (see [`../metis/plan.md`](../metis/plan.md) §6); enum slot reserved here. **Landed at Stage 3.1** — METIS is the fully-supported worker path.
- ~~POLARS Arrow stage-in (worker-arc trigger).~~ **CLOSED at Stage 3.1 closeout (2026-06-26).** Added `ImportDataFrame(stream ImportChunk)` + `DropWorkspaceEntry` RPCs to `worker.v1` (mirroring Metis §1.3) + implemented them in Steropes (`workers/steropes` — `import_data_frame`/`drop_workspace_entry` + `WorkspaceStore.drop`, 6 pytest); `PolarsWorkerGateway.stageIn`/`evict` now drive them. `Stage(X → worker_df{POLARS})` + `Evict(worker_df{POLARS})` are live. Pythia 4.1 POLARS DataFrame staging unblocked.

## 8. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| v1 named-connection list (ids, dialects, allow-lists) | Stage 2.3 | the only content task of the arc |
| Arc scheduling slot (parallel with Golem vs opening Pythia) | arc start | Claude lean: start Phase 1 during Golem Phase 2–3 idle moments; it's the best-bounded module in the constellation |
| ADBC vs arrow-jdbc per dialect | Stage 2.1 T3 decides | spike gate, fallback locked |

## 9. Phase progression checklist

- [x] **Stage 1.1** — proto + skeleton + legality matrix. (commit `e46aff5` on `feat/charon-p1-s1.1-skeleton`)
- [x] **Stage 1.2** — ArrowPipe + integrity + Seaweed. (commit `a6c04ed` on `feat/charon-p1-s1.2-arrow-seaweed`; *mocked S3, multi-batch buffer bug — see review-006 B1; deferred live K3s round-trip to Stage 1.4*)
- [x] **Stage 1.3** — Redis + deploy. (commit `74b28b3` on `feat/charon-p1-s1.3-redis`; *mocked Redis, byte-cap test was a field read, deferred live K3s round-trip to Stage 1.4*)
- [x] **Stage 1.4** — Phase 1 closeout: verified R1–R8 (164 tests green), CI fingerprint regen+diff guard added, `IntegritySpec` aligned to the shared cross-engine pin (`shared/testdata/fingerprints/` incl. `map.arrow`; private `fixtures/integrity/` deleted), R6.2 no-double-close test added, re-tagged. **`charon/v0.1.1` re-applied here** (the candidate `v0.1.0` at `74b28b3` is superseded; mocked-unit + CI gate; live-K3s pass → integration suite, [`tasks-p1-s1.4-closeout.md`](./tasks-p1-s1.4-closeout.md) Part B). See [`tasks-p1-s1.4-closeout.md`](./tasks-p1-s1.4-closeout.md).
- [x] **Stage 2.1** — connections + ADBC gate + extract. Spike verdict: **plain JDBC behind `AdbcReader`/`AdbcWriter`**, hand-rolled JDBC↔Arrow over the §5 matrix (README). `ConnectionRegistry` (YAML + env-subst + allow-list + `/refresh`, lazily-validated), `JdbcAdbcReader` (streaming `JdbcArrowReader`), `Describe(db_table)`, db→seaweed/redis wired. H2 stand-in driver; real PG/MSSQL → integration suite. See [`tasks-p2-s2.1-connections-extract.md`](./tasks-p2-s2.1-connections-extract.md).
- [x] **Stage 2.2** — ingest + full matrix. `JdbcAdbcWriter` (CREATE fail-if-exists / REPLACE transactional swap / APPEND schema-compat; single transaction, rollback on fault), Arrow→DDL §5 mapping both dialects, seaweed→db / redis→db / db→db wired. See [`tasks-p2-s2.2-ingest.md`](./tasks-p2-s2.2-ingest.md).
- [x] **Stage 2.3** — provisioned deploy. Connection registry ConfigMap (`charon-connections`; v1 set: erp-replica RO mssql, analytics-staging RW postgres), `charon-db-credentials` sealed-secret env (optional), `/ready` degraded-set + `/refresh` reload, lazily-validated connections. Live smoke = integration carry-over (testing policy §4). **Phase 2 DONE — `charon/v0.2.0`.** See [`tasks-p2-s2.3-deploy-provisioned.md`](./tasks-p2-s2.3-deploy-provisioned.md).
- [x] **Stage 3.1** — worker endpoint (+ METIS variant + POLARS stage-in). `WorkerGateway` SPI + `WorkerEndpoint`; **both METIS and POLARS** full stage-in/scan-out/describe/evict. METIS = `metis.v1` Import/Export/Drop; **POLARS = `worker.v1` `ImportDataFrame`/`DropWorkspaceEntry` (added to Steropes at the closeout, 2026-06-26)** + Execute/WorkspaceRef read-out. Legality matrix doc-confirmed (`LegalityMatrixDocSpec`; tightened Materialize(db→db)→DISALLOWED per §2). See [`tasks-p3-s3.1-worker-endpoint.md`](./tasks-p3-s3.1-worker-endpoint.md).
- [x] **Stage 3.2** — charon-mcp + bench + ship. `tools/charon-mcp` (thin pass-through, 5 `move.*` tools, structured-JSON locations, `messages` pass-through), 5 `move.*:v1` manifests + capabilities-client heartbeat, move-core throughput bench (`bench/`), fingerprint-mismatch counter + Grafana dashboard. **Phase 3 DONE — `charon/v0.3.0` + `charon-mcp/v0.1.0`.** See [`tasks-p3-s3.2-mcp-bench-ship.md`](./tasks-p3-s3.2-mcp-bench-ship.md).

> **Note (2026-06-14, post review-006; closed out 2026-06-26).** The `charon/v0.1.0` tag was
> cut at `74b28b3` ("Phase 1 closed") on the assumption that the
> mocked unit suite proved the move-pipe contract. Review-006
> demonstrated that the mocked suite was single-batch-only and the
> multi-batch code path silently lost data. **R1–R8 were closed in code**
> (re-review 2026-06-15 — [`tasks-review-006.md`](../../../tasks-review-006.md)).
> The Stage 1.4 **closeout** finished the residual — CI fingerprint regen+diff guard,
> `IntegritySpec` aligned to the shared cross-engine pin (`shared/testdata/fingerprints/`,
> incl. the `map.arrow` entries-wrapped case; private `fixtures/integrity/` deleted), the
> R6.2 no-double-close test — and **re-tagged `charon/v0.1.1`** at the closeout commit (the
> candidate `v0.1.0` on `74b28b3` is superseded; 164 tests green). Per testing policy §4 the
> live-K3s round-trip does **not** gate the tag (it is an integration-suite carry-over,
> [`tasks-p1-s1.4-closeout.md`](./tasks-p1-s1.4-closeout.md) Part B). **Phase 1 closed.**

---

*Plan owner: Bora. Charon arc planned 2026-06-12. Per-stage task lists at `docs/implementation/v1/charon/tasks-p<n>-s<n.m>-*.md` after review.*
