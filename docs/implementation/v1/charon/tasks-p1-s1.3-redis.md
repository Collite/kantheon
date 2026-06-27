# Charon P1 Stage 1.3 — Redis endpoint + deploy

> **Goal.** `services/charon` pod moves bytes between Seaweed ↔ Redis (both directions) with sidecar-fingerprint verification, no-partial-write semantics, TTL honoured; full pump matrix for the two tiers is component-test green. **Phase 1 — `charon/v0.1.0` candidate, but review-006 reopened the tag until R1–R6 close.**
>
> **Status (2026-06-14, after review-006):**
> - **T1 — RedisEndpointSpec (mocked):** the `max-value-bytes` cap test in T1 was a field read, not a `RESOURCE_EXHAUSTED` assertion. **Not done as named** — see review-006 M1; the real oversize→`RESOURCE_EXHAUSTED` test is added in [`tasks-review-006.md`](../../../tasks-review-006.md) R4.
> - **T2 — `RedisEndpoint` implementation:** shipped. Two-SET round-trip (value + sidecar fingerprint), with EX TTL. Drift guard via sidecar presence.
> - **T3 — Full pump matrix:** shipped. **But** the seaweed→seaweed path is the buffering bug from review-006 B1 (single-PUT, no multipart, references to the reader's reused root in `pendingBatches`). The seaweed→seaweed leg of this matrix is **also broken on multi-batch inputs**; fixed in [`tasks-review-006.md`](../../../tasks-review-006.md) R1+R2.
> - **T4 — Materialize + same-location no-op:** shipped, with the same caveat (same-location seaweed uses the user's single-batch source path; multi-batch is fixed in R1+R2).
> - **T5 — Sealed secrets + K3s deploy:** Bora-owned content task; deferred.
> - **T6 — Live K3s smoke:** **deferred** to [`tasks-p1-s1.4-integration.md`](./tasks-p1-s1.4-integration.md) per the 2026-06-14 integration-pass split.

- [x] **T1 — Tests first:** `RedisEndpointSpec` against the `RedisOps` seam (the Lettuce `RedisCommands` union is a 19-inheritance-deep interface that mockk/byte-buddy can't proxy cleanly, so the endpoint talks to a narrow 6-method `RedisOps` interface and the production wiring wraps a Lettuce connection in `LettuceRedisOps`). Binary value integrity, TTL set/honoured, max-value-bytes cap → `RESOURCE_EXHAUSTED`. **(T1 is incomplete — the byte-cap test asserts a field read, not a `RESOURCE_EXHAUSTED` outcome. Real test in [`tasks-review-006.md`](../../../tasks-review-006.md) R4.)** **Mocked, not Testcontainers** (Bora's call 2026-06-14); live Redis round-trip + TTL behaviour land in a separate K3s integration pass (plan §3.3 T1).
- [x] **T2 — `RedisEndpoint` (Source + Target).** Atomicity = `SET key value EX ttl` (single Redis command — no temp-key dance needed). Schema fingerprint rides alongside the value as a sidecar key `[key]:schema-fp` with the same TTL. `Describe` (size + TTL → `expires_at`) + `Evict` (DEL both keys, idempotent).
- [x] **T3 — Full pump matrix for the two tiers.** `CharonMoveExecutor` now wires `seaweed → seaweed` (Stage 1.2) + `seaweed → redis` + `redis → seaweed` + `redis → redis` (cross-key + same-location no-op). Component suite = `CharonMoveExecutorSpec` (14 cases — happy path, same-location no-op, mid-stream fault, fingerprint mismatch, evict present/missing, describe present/missing, retention tag, **+4 cross-endpoint** for the seaweed↔redis matrix and same-location redis readback). **Caveat: the seaweed→seaweed leg is the B1 single-batch-only bug; the unit suite is single-batch; multi-batch is fixed in [`tasks-review-006.md`](../../../tasks-review-006.md) R1+R2.**
- [x] **T4 — `Materialize` semantics complete for blob tiers; same-location no-op path.** Same-location seaweed: `headObject` → fingerprint + content length. Same-location redis: sidecar `get` + `strlen` → fingerprint + size. Both return a `MoveResult` with `messages` (Rule 6) and `result=same_location` in the metrics — no SET/PUT fires.
- [ ] **T5 — Seaweed credentials + Redis URL provisioning for kantheon namespace (sealed secrets; fabric-infra change); `just deploy-kt charon`; readiness gates (S3 + Redis reachable).** *(Bora-owned content task; fabric-infra change is outside the Charon arc's repo boundary. The kustomize overlay already references `CHARON_S3_ENDPOINT` and `CHARON_REDIS_URL` env vars; the values point at the local fabric-infra. Production deploy mounts a sealed secret that overrides `CHARON_S3_ACCESS_KEY` / `CHARON_S3_SECRET_KEY`.)*
- [ ] **T6 — Live K3s smoke vs deployed Seaweed/Redis; tag.** *(Deferred per the 2026-06-14 integration-pass split: the live Seaweed/Redis round-trip + fault injection land in a separate integration-test pass against real local K3s infra, not inside the implementation stage. New home: [`tasks-p1-s1.4-integration.md`](./tasks-p1-s1.4-integration.md).)*

**NOT DONE — Phase 1 tag deferred:** the move-pump matrix is component-test green for **single-batch** inputs only (review-006 B1, M1, M2, M3, H1, H2 all still apply). The `charon/v0.1.0` tag is **not re-applied** until [`tasks-review-006.md`](../../../tasks-review-006.md) R1–R6 are closed and [`tasks-p1-s1.4-integration.md`](./tasks-p1-s1.4-integration.md) is at least stubbed. The previous tag (cut on `74b28b3`) is documented as "Phase 1 candidate — pending review-006 closeout" and the retag is blocked by the stage exit gate in `tasks-review-006.md` (line 207).

## Implementation notes

### `RedisOps` seam (this stage's biggest design call)

The Lettuce `RedisCommands<K,V>` interface is a 19-inheritance-deep union
of every Redis command group (string, hash, sorted set, stream, JSON,
vector, …) — about 600+ methods total. mockk's byte-buddy proxy
implementation can't instantiate that, so the Stage 1.3 test path
needed a narrower seam.

We declare a 6-method `RedisOps` interface in the endpoint file:

```kotlin
interface RedisOps {
    fun get(key: ByteArray): ByteArray?
    fun set(key: ByteArray, value: ByteArray, args: SetArgs): String
    fun del(vararg keys: ByteArray): Long
    fun exists(vararg keys: ByteArray): Long
    fun strlen(key: ByteArray): Long
    fun pttl(key: ByteArray): Long
}
```

Production wires this via `LettuceRedisOps`, a thin delegate over
`connection.sync()`. Tests `mockk<RedisOps>()` directly. The endpoint
constructor takes a `RedisOps`; a secondary constructor wraps a
`StatefulRedisConnection<ByteArray, ByteArray>` for production.

### ByteArrayCodec (binary-safe values)

The Redis values are raw Arrow IPC bytes; the sidecar fingerprint is a
UTF-8 hex string. We use Lettuce's `ByteArrayCodec` (K=V=`byte[]`) —
keys are ASCII-encoded via `RedisEndpoint.keyOf(s: String)`, values
are raw `byte[]`. `StringCodec` would lose the binary safety; the
composed `String`/`ByteArray` codec isn't built-in.

### Sidecar fingerprint convention

- Value: `key` → Arrow IPC bytes
- Fingerprint: `key:schema-fp` → UTF-8 hex string

Both are `SET` with the same TTL via `SetArgs.Builder.ex(seconds)`. The
sidecar is mandatory — `Source.open()` returns `null` (drift =
incomplete) if either key is missing. `Evict` and `discard` both DEL
the pair, idempotently.

### TTL semantics

- `RedisEntry.ttl_seconds` (per-RPC) — if set + > 0, used as-is.
- `charon.redis.default-ttl-s` (server default) — fallback when the
  RPC doesn't set it. 86 400 s = 24 h. Wired through the executor's
  constructor so tests can override.
- `null`/0 → no expiry (discouraged; `Describe.expires_at` is then
  unset).

### `MessageChannel` (Kafka-shaped) cross-arc

The `RedisOps` interface shape is intentionally minimal — no
`MULTI`/`EXEC`, no Lua, no stream commands. If a future stage needs
truly atomic "set value + sidecar" (vs. the two-SET round-trip we have
now), we'll add `setMulti(K, V, K, V, args)` and use a `MULTI/EXEC`
transaction. The contracts doc §5 invariant ("no partial writes")
is satisfied by `ArrowPipe.discard()` already, so this is a latency
optimisation, not a correctness one.

## Test counts

| Spec | Tests |
|---|---:|
| `core/MovePlannerSpec` | 83 |
| `core/ErrorsSpec` | 12 |
| `core/IntegritySpec` | 9 |
| `core/CharonMoveExecutorSpec` | 14 (10 carried from Stage 1.2 + 4 new cross-endpoint) |
| `endpoints/RedisEndpointSpec` | 11 (new) |
| `grpc/RequestValidationSpec` | 7 |
| **Charon total** | **136** |
| **Repo total** | **1079** |
