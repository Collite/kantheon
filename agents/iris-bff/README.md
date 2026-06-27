# iris-bff

Dispatch BFF for the Iris SPA — conversation state, Themis routing, SSE multiplex
back to the FE. Kotlin + Ktor. See [`docs/architecture/iris/`](../../docs/architecture/iris/)
(architecture + contracts) and [`docs/implementation/v1/iris/`](../../docs/implementation/v1/iris/) (plan).

## Surfaces (REST + SSE, bearer-authenticated; proto-shape JSON)

| Endpoint | Notes |
|---|---|
| `POST /v1/session`, `GET /v1/sessions`, `GET /v1/session/{id}`, `/reset`, `/undo`, `/turn/{turnId}` | session CRUD + history hydration; create mirrors curated static chips (`static-chips.yaml`) + golem discovery |
| `POST /v1/chat/stream` \| `/turn` \| `/resume` | a turn — **resolved through Themis** (`POST {iris.themis.base-url}/v1/resolve`) before dispatch, then streamed as `IrisStreamEvent` SSE (`step`/`envelope`/`error`/`done`) |
| `POST /v1/action` | typed actions: `sort`/`filter`/`paginate` (BFF-side shaping on cached rows), `select_row` (drilldown→new bubble), `chip_invocation`, `edit_resend`, `reask_agent` (PD-14), `investigate` (PD-1 escalation) |
| `POST /v1/refresh` | proxy golem metadata refresh |
| `POST/GET/PATCH/DELETE /v1/artifacts`, `POST /v1/artifacts/{id}/refresh`, `GET /v1/dashboards/{id}/open` | **Phase 4.2 (PD-6)** pins & dashboards: capture a turn's bubble; deterministic refresh (re-execute, re-apply display state, explicit stale/error); dashboard open = per-pin refresh SSE |
| `GET /v1/discover` | **Phase 4.3 (PD-7)** role-filtered DomainCards from the capabilities cache (`non_routable` excluded) |
| `POST /v1/turns/{id}/feedback` | **Phase 4.3 (PD-3)** 👍/👎 + reason, upsert per (turn,user); offline `just feedback-export` → `eval/candidates/` |
| `GET /v1/audit/verify?segment=` | **Phase 4.3 (PD-8)** admin-gated per-segment hash-chain + signature verification |
| `GET /v1/inbox`, `GET /v1/inbox/stream` | **Phase 4.1 (PD-2)** investigation inbox — a view over Pythia state ⋈ `iris_turns` (12→5 status); SSE re-emits on each lifecycle change (NATS in the Pythia arc, polling fallback now). Fake-backed until `pythia/v0.1.0` |
| `GET /ready`, `/health`, `/metrics` | readiness + Prometheus scrape |

## Routing layer (Phase 3)

`ChatDispatcher` resolves every turn through Themis (`routing/HttpThemisClient`,
OBO bearer, assembled `HandoffContext` PD-1), then branches on the `ResolveResponse`
outcome: **Resolution** → `dispatch/AgentDispatcher` (keyed by `agent_id`;
`golem-v2` only at Phase 3); **needs_user_pick** → `RoutingPickChip`s +
`alternates_offered`; **multi_question** → PD-13 SPLIT chips / KEEP_TOGETHER
dispatch + hint; **RefusalWithGaps** → error envelope. PD-4: the agent's echoed
`entity_context` is read back into the session; a changed applied scope appends a
`scope_changed` WARNING to the answer bubble.

## Observability

`/metrics` exposes `iris_turn_duration_seconds{outcome}`,
`iris_routing_needs_user_pick_total`, `iris_routing_refusal_total{code}`,
`iris_typed_action_total{kind}`, `iris_escalation_total`. Grafana dashboard:
[`k8s/grafana-dashboard.json`](./k8s/grafana-dashboard.json). Single-trace OTel:
kantheon#27.

## Build / test

```
just test-kt iris-bff          # unit/component (Kotest + Ktor testApplication + fakes)
./gradlew :agents:iris-bff:ktlintFormat
```

Themis/golem/capabilities are mocked (`FakeThemisClient` / `FakeGolemV2Client` /
Ktor `MockEngine`); Exposed stores are integration-deferred (in-memory fakes back
the unit gate) per [planning-conventions §4](../../docs/implementation/planning-conventions.md).
