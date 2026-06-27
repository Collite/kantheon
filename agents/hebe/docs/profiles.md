# Profiles & the axis model

> **P2 Stage 2.1.** Hebe's four deployment profiles — `local`, `personal`,
> `server`, `k8s` — are **named presets over orthogonal axes**, not four code
> paths. `profile` only selects a bundle of axis *defaults* at boot; every axis
> stays individually overridable. Subsystems read **axes**, never the profile
> name (there is no `when(profile)` anywhere in the code). The authority is
> [`docs/architecture/hebe/contracts.md` §5](../../../docs/architecture/hebe/contracts.md).

## Resolution & precedence

`config.toml` carries a top-level `profile` and, optionally, any axis key under
its section (`[storage] backend = …`, `[tools] posture = …`). Resolution order:

- **axis value:** env axis (`HEBE_STORAGE_BACKEND`) > file axis (`[storage] backend`) > profile default
- **profile:** `HEBE_PROFILE` env > file `profile` (absent ⇒ `local`)
- **instance id:** `HEBE_INSTANCE_ID` env > file `instance_id` (absent ⇒ `local`)

Every axis is a typed enum — an invalid value (`storage.backend = "mysql"`) fails
fast at boot, not at use. Two cross-axis invariants are boots-stopping:

- `fs.durability = ephemeral` ⇒ `workspace.backend` **and** `receipts.backend`
  must be `postgres` (an ephemeral pod FS would otherwise lose state).
- `storage.backend = postgres` ⇒ an explicit `instance_id` (it keys the PG
  schema `hebe_<instance_id>`).

## Profile → axis default matrix (contracts §5.1)

| Axis key | `local` | `personal` | `server` | `k8s` |
|---|---|---|---|---|
| `storage.backend` | sqlite | sqlite | postgres | postgres |
| `fs.durability` | persistent | persistent | persistent | ephemeral |
| `workspace.backend` | files | files | files | postgres |
| `receipts.backend` | file | file | file | postgres |
| `platform.reach` | none | remote | remote | in_cluster |
| `platform.availability` | — | intermittent | always | always |
| `llm.source` | byok | gateway_with_byok_fallback | gateway | gateway |
| `security.platform_identity` | none | keycloak | keycloak | keycloak |
| `security.console_auth` | password | password | oidc | oidc |
| `security.secrets_backend` | keychain | keychain | file¹ | k8s |
| `otel.enabled` | false | false | true | true |
| `capabilities.enabled` | false | optional | true | true |
| `tools.posture` | full | full | full¹ | restricted |

¹ `server` lists a choice in §5.1 (`file`/`keychain`, `full`/`restricted`); the
preset defaults to the first (`file`, `full`) — both overridable.

## `hebe doctor` is axis-aware

`doctor` reports the resolved `platform.reach`/`availability` and labels platform
dependencies **required** (`availability = always` → unreachable is a `FAIL`) or
**probed** (`availability = intermittent` → unreachable is a `WARN`/degraded, not
a failure — an offline personal host is healthy, just disconnected). With
`reach = none` (local) there are no platform dependencies; only the
self-contained checks run. Concrete platform checks (gateway, Keycloak, OTel,
capabilities-mcp, iris-bff) register into this matrix in Stages 2.2–2.4.

## Onboarding

`hebe onboard` asks for the profile first (default `local`) and writes it to the
top of the generated `config.toml`. The `local` onboarding path is otherwise
unchanged.
</content>
</invoke>
