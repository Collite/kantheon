# Review 005 — Fork Phase 2, Stage 2.2 (Echo + echo-mcp)

> Reviewer: Claude (per [`reviews.md`](./reviews.md)). Date: 2026-06-13. Branch: `feat/fork-p2-s2.2-echo` (commits `394709b` T1–T3, `f1d771c` T4–T6, `2421d36` tracker).
> Scope: the full stage — proto fork (T1), lean echo service fork (T2), in-repo catalog (T3), `echo-mcp` wrapper (T4), k8s + Jib (T5), e2e/tracker (T6). Plan: [`tasks-p2-s2.2-echo.md`](./docs/implementation/v1/fork/tasks-p2-s2.2-echo.md); contracts §1 (proto map), §2 (zero-logic wrappers), §7 (ports).

## Verdict

**Stage 2.2 is done and correctly done.** This is the cleanest fork stage so far — every lesson from the 2.1 reviews was applied rather than re-learned. No blockers; no contract divergences; the suites and lint are green and the claimed numbers are accurate. The remaining findings are all cosmetic (🟢).

I verified the high-risk areas **directly against the code**, not the checklist (the last two stages had claims that didn't hold):

| Area | 2.1 failure mode | 2.2 result |
|---|---|---|
| `main()` → config-read gRPC wiring | F2/R2: `main()` read stale `METADATA_GRPC_*` / `7204`, never the config | ✅ `main()` calls `buildEchoClient(config, telemetry)`; reads `echo.client.host` / `echo.client.grpc.port` |
| env chain k8s ↔ conf ↔ code | F2: k8s set `ARIADNE_GRPC_*`, code read `METADATA_*` | ✅ k8s `ECHO_GRPC_HOST/PORT` → conf `${?ECHO_GRPC_*}` → `buildEchoClient` — consistent end to end |
| wiring test honesty | R2: `GrpcTargetConfigSpec` was vacuous (re-implemented the read inline) | ✅ now calls production `buildEchoClient(config, mockk)` — 3 real cases |
| capability registration | F4: registered a single `get_model` shim, 6 manifests dead | ✅ `ManifestLoader().loadAll()` → registers each; blank-URL guard; spec asserts the real manifest shape |
| proto-import root | the compile bug I fixed last turn (`kantheon.echo.v1`) | ✅ imports `org.tatrman.echo.v1`; rule documented at the top of the task doc |

**Verified green myself:** `:services:echo:test` = 44/0, `:tools:echo-mcp:test` = 16/0, `ktlintCheck` green on both. Proto file carries no `FuzzyMatcherService` / `cz.dfpartner` leftover; no `cz.dfpartner` / `fuzzy-matcher` / `fuzzy_match` strings anywhere in the two modules' `src/`.

---

## What's correct (the substance)

- **Proto fork (T1).** `echo_service.proto` forked byte-identical bar the renames (`package`/`java_package` → `org.tatrman.echo.v1`, `java_outer_classname = EchoProto`, `FuzzyMatcherService` → `EchoService`). Message shapes (`MatchRequest`/`AlgorithmSpec`/`FuzzyMatch`/`FuzzyMatchResponse`) unchanged — correct per the "wire shapes fork unchanged" principle (Ariadne's `GetPrompts` remains the sole additive exception). `ForkedProtoDescriptorSpec` extended to 8 descriptors; layout script updated.
- **Lean carve-out (T2/T3) is disciplined.** The SQL backend (Exposed/Hikari/MSSQL), `MetadataLoaderSource`/`MetadataServiceClient`/`SqlComposer`/`PkResolver`/`QualifiedNameExtensions`, `DatabaseFactory`, and the DB `*Config` fields are all dropped with their tests. The replacement — `EchoCatalog.fromResource(...)` → `StaticLoaderSource` — is small, folds values at load time, and **degrades gracefully** (missing/unparseable resource → empty catalog + warning, `/ready` stays 503, no boot crash). The `LoaderSource` interface is preserved so the metadata-driven loader can return later without reshaping callers. The `echo.nlp.*` Kadmos client stays in code, disabled — correct forward-compat for Stage 2.3.
- **Zero-logic wrappers (T4, contracts §2).** `EchoGrpcClient.match` is one `stub.match()` with proto↔lib translation only; `Tools.matchCallback` is not-wired-guard → parse args → one `client.match(...)` → structured content. The cascade (`algorithm` = TATRMAN|LEVENSHTEIN|JARO_WINKLER) is exposed as a tool arg, not reimplemented. `match.yaml` is well-formed (`echo.match:v1`).
- **Persona discipline is right where it matters.** Service/wrapper/client/telemetry classes renamed to Echo*; the `FuzzyMatch`/`FuzzyMatchResult`/`FuzzyMatchResponse` *domain/algorithm* types are deliberately kept (algorithm terminology, not persona) — consistent with the task's stated rule.
- **Ports (§7):** echo 7265/7266, echo-mcp 7267 — matches the reservation table. k8s base + local overlay (`imagePullPolicy: Never`, OTel-off) mirror the capabilities-mcp/Ariadne pattern.

---

## 🟢 Minor (optional cleanups — none block the stage)

- **F1 — stale filename.** `services/echo/src/main/kotlin/.../telemetry/FuzzyMatcherTelemetry.kt` still carries the old name; the class inside is `EchoTelemetry` (that's why it compiles). The persona rename was completed at the class level but not the file. Rename the file to `EchoTelemetry.kt` for greppability. (Distinct from the intentionally-kept `core/FuzzyMatchResult.kt` algorithm type — leave that.)
- **F2 — double manifest load.** `registerWithCapabilities` calls `ManifestLoader().loadAll()` twice (once inside the `logger.info` arg on line 168, once on line 169) — two constructions + two classpath walks at startup. Hoist to a single `val manifests = ManifestLoader().loadAll()` and log `manifests.size`.
- **F3 — deferred, by accepted precedent (not a defect).** Live-K3s round-trip and the `echo/v0.1.0` + `echo-mcp/v0.1.0` tags are deferred to the deploy pipeline — the same call made for Ariadne T5/T7. Flagging only so it's tracked: the DONE line ("round-trips … on local K3s") is proven by the unit + e2e-spec surface, not an actual cluster. Acceptable given the established pattern, but the tags genuinely don't exist yet.

---

## Tracker

`tasks.md` marks Stage 2.2 `[x]` — **correct this time** (unlike 2.1, where boot/wiring bugs made the tick premature). All six tasks are genuinely complete; suites + lint green. No un-ticking needed.

*The two 🟢 cleanups are listed in [`tasks-review-005.md`](./tasks-review-005.md); both are trivial and can be folded into the next commit on this branch or carried into 2.3.*
