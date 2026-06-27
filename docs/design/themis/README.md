# Themis — Design

Themis is the routing + question-understanding agent. Stateless. Iris calls Themis at the start of every conversational turn; Themis returns parsed intent + resolved entities + a routing decision naming which agent should answer. When the answer is ambiguous, `needs_user_pick: true` tells Iris to render alternates as chips.

Implementation reality: Themis is the post-extraction continuation of "Resolver" — currently in `ai-platform/agents/resolver/`. Stages 01–04 are essentially complete there; the move to `kantheon/agents/themis/` plus Koog adoption plus the routing layer is planned in [`../../implementation/v1/themis/plan.md`](../../implementation/v1/themis/plan.md).

## Files

| File | What |
|---|---|
| [`themis-brief.md`](./themis-brief.md) | Original brief: an intent and entity resolver service for the platform — what we set out to build and why. Source: `ai-platform/docs/v1/resolver.md`. |
| [`themis-design.md`](./themis-design.md) | Full design: three-layer architecture (Kotlin agent + Kotlin MCP wrapper + Python NLP infra), engine plugin system, joint-inference LLM call, HMAC resume tokens, eval-corpus growth strategy. Currently Resolver-era prose; the routing-layer section folds in after Phase 3 Stage 3.6 ships. Source: `ai-platform/resolver-design.md`. |
| [`themis-brainstorming.md`](./themis-brainstorming.md) | Process record: original Resolver brainstorm (early May 2026) + the Themis reframe (2026-05-08 onwards) + the six open Stage 4.5 design points resolved 2026-05-11. |

## What's elsewhere

- **Implementation architecture and contracts** for the kantheon-side build: [`../../architecture/themis/architecture.md`](../../architecture/themis/architecture.md) + [`../../architecture/themis/contracts.md`](../../architecture/themis/contracts.md).
- **Phased plan and task lists**: [`../../implementation/v1/themis/`](../../implementation/v1/themis/).
- **ai-platform-side stage docs** (Stages 01–06 originals; mostly complete in ai-platform): mirrored under [`../../implementation/v1/themis/tasks-stage-*.md`](../../implementation/v1/themis/).

## Up / across

- Up: [`../README.md`](../README.md) — design entry point.
- Across: [`../iris/`](../iris/) — Iris is Themis's primary caller. [`../pythia/`](../pythia/) — Pythia uses Themis at `INVESTIGATION_DEEP` profile.
