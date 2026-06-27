# Stage 2.1 — Axis model + profile presets in `config`

> **Phase 2, Stage 2.1.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §"Stage 2.1", [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5 (config schema — §5.1 axis-default matrix, §5.2 annotated `config.toml`, §5.3 doctor matrix), [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §2 + §2.2 (profile principles).

## Goal

Axes resolve from a `profile` preset bundle in the `:agents:hebe:modules:config` module; **every axis is individually overridable**; **no `when(profile)` exists anywhere downstream** — subsystems read axes. `local` profile boots regression-identical. The `fs.durability=ephemeral ⇒ workspace+receipts=postgres` fail-fast assertion is enforced. `hebe doctor` gains an axis-aware skeleton.

## Pre-flight

- [x] **Phase 2 pre-flight** met (Phase 1 done; seams confirmed).
- [x] **Branch**: `feat/hebe-p2-s2.1-axis-model`.
- [x] Re-read contracts §5.1 — the canonical axis→default matrix. The `Axes` type fields and `Profile` preset values are transcribed directly from it.

## Tasks

- [x] **T1 — Tests first: axis resolution matrix + precedence + fail-fast.**

  Create `agents/hebe/modules/config/src/test/kotlin/org/tatrman/kantheon/hebe/config/AxesResolutionSpec.kt` **before** any implementation. Cover, using contracts §5.1 as the oracle:

  - **Per-profile defaults** — for each of `local`/`personal`/`server`/`k8s`, resolving with no overrides yields the exact matrix row (assert every axis: `storage.backend`, `fs.durability`, `workspace.backend`, `receipts.backend`, `platform.reach`, `platform.availability`, `llm.source`, `security.platform_identity`, `security.console_auth`, `security.secrets_backend`, `otel.enabled`, `capabilities.enabled`, `tools.posture`).
  - **Override precedence** — explicit axis key in `config.toml` beats the profile default; `HEBE_PROFILE` env beats the file's `profile`; an explicit env axis override (e.g. `HEBE_STORAGE_BACKEND`) beats the file axis. Document the precedence ladder in the spec name: `env axis > file axis > profile default`, and `HEBE_PROFILE env > file profile`.
  - **Invalid input** — unknown profile name → fail fast with a clear message; unknown axis value (e.g. `storage.backend = "mysql"`) → fail fast.
  - **The load-bearing fail-fast** — `fs.durability = "ephemeral"` with `workspace.backend != "postgres"` OR `receipts.backend != "postgres"` → boot fails with the contracts §5.3 message ("ephemeral FS would lose state").

  ```kotlin
  class AxesResolutionSpec : StringSpec({
      "k8s preset resolves the contracts §5.1 row" {
          val axes = ProfileResolver.resolve(rawConfig(profile = "k8s"))
          axes.storage.backend shouldBe StorageBackend.POSTGRES
          axes.fs.durability shouldBe Durability.EPHEMERAL
          axes.workspace.backend shouldBe WorkspaceBackend.POSTGRES
          axes.tools.posture shouldBe Posture.RESTRICTED
      }
      "explicit axis key overrides the profile default" {
          val axes = ProfileResolver.resolve(rawConfig(profile = "k8s", overrides = mapOf("tools.posture" to "full")))
          axes.tools.posture shouldBe Posture.FULL
      }
      "HEBE_PROFILE env beats file profile" { /* ... */ }
      "ephemeral FS without postgres workspace fails fast" {
          shouldThrow<ConfigValidationException> {
              ProfileResolver.resolve(rawConfig(profile = "k8s", overrides = mapOf("workspace.backend" to "files")))
          }.message shouldContain "ephemeral"
      }
      "unknown profile fails fast" { /* ... */ }
  })
  ```

  Acceptance: specs written and **failing** (no `ProfileResolver` yet). Commit `[hebe-p2-s2.1] failing axes specs`.

- [x] **T2 — Implement `Axes` + `Profile` presets + resolution.**

  In `:agents:hebe:modules:config`:

  - `Axes` — a typed, immutable data class with one nested group per axis family (`storage`, `fs`, `workspace`, `receipts`, `platform`, `llm`, `security`, `otel`, `capabilities`, `tools`), each field a sealed enum (`StorageBackend`, `Durability`, `Posture`, `PlatformReach`, `Availability`, `LlmSource`, `PlatformIdentity`, `ConsoleAuth`, `SecretsBackend`, …). No `String` axis values escape the resolver.
  - `Profile` — the four preset bundles as `Map<AxisKey, AxisValue>` (or an `Axes` factory per profile), transcribed from contracts §5.1.
  - `ProfileResolver.resolve(raw)` — load profile → apply file axis overrides → apply env axis overrides → validate (T1's fail-fast checks) → return `Axes`. Parse `config.toml` with `tomlj` (`libs.versions.tomlj`); `HEBE_PROFILE` env override.

  Acceptance: T1 specs pass. Commit `[hebe-p2-s2.1] axes green`.

- [x] **T3 — Thread resolved `Axes` into the boot sequence (no behaviour change under `local`).**

  Replace the boot wiring so each subsystem receives the axis value(s) it needs from the resolved `Axes` (constructor injection), not the profile name. Audit the codebase for any existing `when(profile)` / profile-name branch and convert it to an axis read. Under `local`, the resolved axes reproduce the previous defaults exactly — the existing suite must stay green untouched.

  Acceptance: `just test hebe` green; `rg 'when *\(.*profile' agents/hebe/modules --glob '**/src/main/**'` returns nothing (no profile-name branching).

- [x] **T4 — `instance_id` concept.**

  Add `instance_id` to config (contracts §5.2): required when `storage.backend = postgres` (fail fast if missing), defaults to `"local"` otherwise. It is the key for the PG schema name (`hebe_<instance_id>`, Phase 3) and the registration `agent_id` (`hebe-<instance_id>`, Phase 3 Stage 3.4) — wire the value through now even though both consumers land later.

  Acceptance: a `postgres` config without `instance_id` fails fast; `local` defaults to `"local"`; unit test added.

- [x] **T5 — `hebe doctor` axis-aware check matrix (skeleton).**

  Build the `doctor` command's check-registry keyed by resolved axes (contracts §5.3). Implement the **structure** now; individual checks are added by later stages:

  - `platform.reach = none` → LLM endpoint, keychain, SQLite writable, workspace dir writable (these exist today — wire them in).
  - `platform.availability = intermittent` → gateway/Keycloak/iris-bff are **probed, not required** (unreachable ⇒ `DEGRADED`, not `FAILED`).
  - `platform.availability = always` → the same dependencies are **required** (unreachable ⇒ `FAILED`).

  Encode the **required-vs-probed** split as a property of each check, driven by `platform.availability`. Stages 2.2–2.4 register their concrete checks (gateway, Keycloak, OTel) into this matrix.

  Acceptance: `hebe doctor` runs under each profile and prints the right check set with correct required/probed labels; unit test over the matrix (no live calls — stub the probes).

- [x] **T6 — `hebe onboard` asks profile first + preset smoke tests.**

  - `hebe onboard` prompts for the profile as its first question; the `local` onboarding path is unchanged.
  - Add **preset smoke tests** (`ProfilePresetSmokeSpec`) — one cheap assertion per profile that the preset wires the expected axis bundle (this is the "test the axes, not the profiles" mitigation from the plan's risks note; keeps CI from fanning ×4).
  - Update `agents/hebe/docs/` config/profile docs with the axis model + the §5.1 matrix.

  Acceptance: `local` onboarding identical to before; preset smoke tests green; docs updated. PR `[hebe-p2-s2.1] axis model + presets`.

## DONE — Stage 2.1

- [x] All six tasks checked.
- [x] `local` profile regression-identical (existing suite green, untouched).
- [x] No `when(profile)` branch anywhere in Hebe main sources.
- [x] Fail-fast assertions (ephemeral⇒postgres; postgres⇒instance_id; invalid profile/axis) unit-green.
- [x] `hebe doctor` skeleton runs per-profile with correct required/probed labelling.
- [x] PR merged.

## Library / pattern references

- **contracts.md §5** — the authority for every axis value, the precedence ladder, and the fail-fast rules.
- **architecture.md §2.2** — the four profile principles (axes not branches; no dead code; static-vs-live; doctor is axis-aware).
- **tomlj** (`libs.versions.tomlj = 1.1.1`) — `config.toml` parsing.

## Out of scope for Stage 2.1

- Actual gateway/Keycloak/OTel **checks** (registered by 2.2/2.3/2.4 into the matrix built here).
- PG backends (Phase 3). The `instance_id` is wired but unused until Phase 3.
- The outbox/breaker/catch-up machinery (2.5).
