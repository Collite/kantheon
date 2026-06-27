# Stage 3.4 — capabilities-mcp v0.2.0 + Hebe registration

> **Phase 3, Stage 3.4.** *(Renamed 2026-06-12, cohesion review — this stage owns the post-`v0.1.0` registry contract changes.)*
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §"Stage 3.4", [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §2 (registration manifest + registry behaviour contract), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.1 (`capabilities.proto`, `non_routable` field 16, `visibility_roles` field 17), [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §3 (registration is presence-and-discovery only).

## Goal

Hebe is **visible in the registry, invisible to routing**. The registry serves the two 2026-06-12 manifest fields that landed in `capabilities.proto` after `capabilities-mcp/v0.1.0` was tagged: `non_routable = 16` and `visibility_roles = 17`. Hebe registers per instance under `k8s` (`agent_id: hebe-<instance_id>`, `non_routable: true`), heartbeats, and is **provably excluded** from every Themis routing layer. Tags **`hebe/v0.3.0`** + **`capabilities-mcp/v0.2.0`**.

> Cross-arc: T4–T6 touch **capabilities-mcp** and **Themis**. Coordinate with the Themis arc (its Stage 3.3 derives the routing view that excludes `non_routable`); this stage adds the registry-side enforcement + the Themis regression test.

## Pre-flight

- [ ] **Stage 3.3 DONE** — a Hebe pod deploys on K3s; provisioning runbook step 5 (register) is what this stage fills.
- [ ] **Branch**: `feat/hebe-p3-s3.4-capabilities-registration`.
- [ ] Confirm `capabilities.proto` already carries `non_routable = 16` + `visibility_roles = 17` (added 2026-06-12; contracts §6 says "done"). If only the field exists but the registry doesn't honour it, that is exactly T4/T5.
- [ ] `capabilities-client` lib (from Themis Phase 1) available for the boot-time registration.

## Tasks

- [ ] **T1 — Tests first: registration payload + heartbeat + warn-and-continue.**

  In `:agents:hebe:modules:mcp-server` (or wherever Hebe's outbound capabilities client lives), `HebeRegistrationSpec`: the registration payload matches the manifest (contracts §2) — `agent_kind = PERSONAL_ASSISTANT`, `agent_id = hebe-<instance_id>`, `non_routable = true`, empty `description_for_router`, empty `intent_kinds_supported`, `service_endpoint`, `health_check_path`, `hitl_default = SPECULATIVE`. The heartbeat loop ticks per `capabilities.heartbeat_seconds`. Registry down ⇒ **warn-and-continue** (Hebe boots regardless — registration is presence-only, architecture §3).

  Acceptance: specs written and failing. Commit `[hebe-p3-s3.4] failing registration specs`.

- [ ] **T2 — `capabilities-client` integration; register at boot under `k8s` + heartbeat.**

  Wire the `capabilities-client` lib: register at boot when `capabilities.enabled` is `true` (`k8s`/`server`) or `optional` (`personal` = enabled, warn-and-continue); heartbeat per config. Driven by the `capabilities.enabled` axis (Stage 2.1), not the profile name. `local` (`false`) does not register.

  Acceptance: T1 specs pass; a `local` run does not register.

- [ ] **T3 — Fixture manifest `manifests/agents/hebe.yaml` in capabilities-mcp.**

  Add the bootstrap fixture at `tools/capabilities-mcp/src/main/resources/manifests/agents/hebe.yaml` exactly as contracts §2 (the runtime per-instance registration supersedes it; the fixture seeds discovery). 

  Acceptance: the fixture loads in capabilities-mcp without error; appears in `list`/`get`.

- [ ] **T4 — capabilities-mcp honours `non_routable` in the routing view.**

  In `tools/capabilities-mcp`: the `list_agents()` **routing view** served to Themis excludes entries with `non_routable = true`; plain `list`/`get`/`search` **keep** them (discovery). Add `RoutingViewSpec`: a `non_routable` agent is absent from the routing view but present in `list`/`get`/`search`.

  Acceptance: routing view excludes Hebe; discovery surfaces include it.

- [ ] **T5 — capabilities-mcp stores + serves `visibility_roles` (PD-8).**

  Extend the manifest loader + register/heartbeat + `search`/`list`/`get` surfaces to carry `visibility_roles` (field 17). The registry only **transports** the declaration — Themis does the per-request, per-caller filtering (roles are per-caller). Update seed fixtures with an example (e.g. `golem-hr: [kantheon-domain-hr]`). Spec: a manifest with `visibility_roles` round-trips through register → list/get/search.

  Acceptance: `visibility_roles` carried end-to-end through the registry; fixtures updated.

- [ ] **T6 — Themis regression: `non_routable` agents never appear in a `RoutingDecision`.**

  Cross-arc test in the Themis module: a `non_routable` agent (Hebe) never appears in any `RoutingDecision` — Layer 1 scoring, Layer 2 prompt assembly, Layer 3 alternates. Pairs with Themis Stage 3.3's routing-view derivation. This is the **product guarantee** of the whole "Hebe is a client, not a candidate" design (architecture §1).

  Acceptance: Themis regression green — Hebe is provably unroutable across all layers.

- [ ] **T7 — Registry TTL/prune for instances coming and going; tags.**

  Verify TTL/prune behaviour for Hebe instances that register and disappear (a stopped `personal` host, a deleted pod): the entry prunes after TTL and re-registers cleanly on return; no duplicate entries. Then tag **`hebe/v0.3.0`** + **`capabilities-mcp/v0.2.0`**; bump `gradle/libs.versions.toml` for capabilities-mcp.

  Acceptance: TTL/prune verified; both tags pushed. PR `[hebe-p3-s3.4] cap-mcp v0.2.0 + hebe registration`.

## DONE — Stage 3.4

- [ ] All seven tasks checked.
- [ ] K3s Hebe registered, heartbeating, and **provably unroutable** (Themis regression green).
- [ ] Registry serves `non_routable` (routing-view exclusion) + `visibility_roles` (PD-8 transport).
- [ ] Registry down ⇒ warn-and-continue; TTL/prune verified for transient instances.
- [ ] Tags `hebe/v0.3.0` + `capabilities-mcp/v0.2.0` pushed. **Phase 3 DONE.**
- [ ] PR merged.

## Library / pattern references

- **hebe/contracts.md §2** — the manifest + the registry behaviour contract (the authority).
- **themis/contracts.md §1.1** — `capabilities.proto` fields `non_routable = 16`, `visibility_roles = 17`.
- **architecture.md §3** — registration is presence-and-discovery only; warn-and-continue.
- **capabilities-client** lib (Themis Phase 1) + **EXAMPLES.md §4** — heartbeat + read-mostly cache.

## Out of scope for Stage 3.4

- The actual iris-bff turn dispatch (Phase 4) — registration ≠ calling; this stage only makes Hebe *visible and unroutable*.
- PD-8 per-caller role filtering logic (Themis-side; the registry only transports `visibility_roles`).
