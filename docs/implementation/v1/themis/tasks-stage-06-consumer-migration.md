# Stage 06 — Consumer migration

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: Resolver becomes the single source of truth for entity and intent
resolution across all consumers. Golem cuts over from `shadow` to `primary`
and eventually has its in-process logic removed. Wrangler (the evolved Golem
from the Pythia conversation) uses Resolver as its standard pre-step. Pythia
v0 integrates with Resolver as soon as Pythia is itself callable.

This stage has external dependencies — specifically Pythia v0 — and may
extend in time accordingly. The Wrangler portion can land before Pythia v0;
the Pythia portion is gated.

## Entry criteria

- Stage 05 complete: divergence rate stable below threshold; cut-over
  decision documented.
- Pythia v0 callable, OR explicit decision to proceed with Wrangler-only
  cut-over and add Pythia later.
- Wrangler evolution work in progress (per Pythia conversation Phase α
  notes); ai-platform team owns Wrangler so this is internal coordination.

## Exit criteria

- Golem feature flag flipped to `primary`. In-process fallback retained for
  ~2 weeks for safety.
- After the safety window: in-process entity-detection and named-query
  selection logic removed from Golem.
- Wrangler calls Resolver as standard pre-step in its conversation flow.
- Pythia v0 calls Resolver as its resolution layer.
- Pythia open-questions Q4 closed with reference to this stage.
- Eval corpus continues to grow, now sourced from real Wrangler and Pythia
  traffic in addition to direct chat-direct calls.
- `docs/overview/Architecture-v1.md` updated to reflect the new topology
  (Resolver as the single resolution surface; Golem in-process logic
  removed).

## Tasks

### 1. Cut-over in Golem
- [ ] Flip `resolver.mode` from `shadow` to `primary` per environment.
      Stagger: dev → staging → production.
- [ ] Monitor for one week per environment. Watch fallback-to-in-process
      rate; should be near zero.
- [ ] If stable, schedule the in-process removal task. If not stable,
      flip back to `shadow` and treat as a Stage 05 regression.

### 2. In-process logic removal in Golem
- [ ] Identify the modules in `agents/erp-agent-2` that handled Czech entity
      detection and named-query selection. (These were the historical
      starting point for the Resolver design.)
- [ ] Remove them in a single PR that documents what was removed and why.
      Per memory ("do only what is asked — no unsolicited refactoring, no
      deleting 'unused' code without approval"): this PR is the explicit
      approval; it should not bundle other refactoring.
- [ ] Update Golem's tests to call Resolver-mocked paths in places where
      tests previously tested in-process logic directly.

### 3. Wrangler integration
- [ ] Wrangler (the evolved Golem) calls Resolver as a standard pre-step in
      its conversation flow before triaging to its own in-house path or to
      Pythia. The Pythia conversation's Phase α describes this in detail;
      cross-reference that when implementing.
- [ ] EntityContext from Wrangler's conversation state flows into
      `ResolveContext.entityContext`; Wrangler decides what to do with
      Resolver's `AwaitingClarification` outputs (the Pythia conversation
      noted a possible "auto-answer when EntityContext exact-matches"
      heuristic — defer that decision until the integration is functional).

### 4. Pythia integration
- [ ] When Pythia v0 is callable, wire Pythia's resolution layer to call
      Resolver. Pythia's design treats Resolver-MCP as one of its core
      dependencies (along with capabilities-mcp, query-mcp, metadata-mcp,
      fuzzy-mcp).
- [ ] Update Pythia's open-questions Q4 ("Resolver migration path") with
      closure: parallel deployment shipped, Pythia consumes Resolver from v0
      onwards.

### 5. Cross-system eval-corpus growth
- [ ] Extend the curation pipeline (Stage 05) to sample from Wrangler and
      Pythia traffic in addition to the original Golem stream.
- [ ] Periodically retrain or re-tune Resolver against the broader corpus.
      "Retrain" here is mostly LLM-prompt iteration and confidence-threshold
      tuning, not a model fine-tune.

### 6. Documentation
- [ ] `docs/overview/Architecture-v1.md`: update the architecture diagram to
      show Resolver as the entity/intent surface, with Wrangler, Pythia, and
      chat-direct as consumers. Remove references to in-process Golem
      resolution.
- [ ] `docs/overview/Managerial-Overview-v1.md`: update if it covers
      resolution flow.
- [ ] Cross-link the Pythia design doc and the resolver design doc so each
      references the other's integration point.

## Risks and mitigations

**Cut-over regression.** The 2-week fallback window before in-process
removal is the mitigation. Track fallback rate explicitly; if it's
non-trivial, postpone removal.

**Pythia v0 timing.** Stage 06 may stretch indefinitely if Pythia is
delayed. The Wrangler portion is independent and can land separately;
do not block Wrangler integration on Pythia.

**Wrangler evolution timing.** The Wrangler portion depends on Wrangler
being ready to use Resolver. Per the Pythia conversation, Wrangler's
Phase α is "Resolver extraction" — which is exactly this stage from
Wrangler's perspective. Coordinate timing with whoever owns Wrangler
(likely the same team).

**EntityContext propagation correctness.** Wrangler maintaining "active
customer = X" state and passing it through `ResolveContext.entityContext`
needs explicit testing. Wrong propagation would silently bias Resolver's
outputs.

**Auto-answer-when-unambiguous-EntityContext is deferred.** The Pythia
conversation flagged this as an open question. Do not implement
auto-answering in Stage 06; ship Wrangler-surfaces-clarifications-to-user
behaviour and revisit auto-answering as a v1.1 enhancement after we have
data on user friction.

## Out of scope for Stage 06

- Stateful HITL with DB-backed conversations (still v1 deferral).
- Streaming progress events from Resolver.
- Embedding-based function pre-selection for very large registries.
- Local-licensed MorphoDiTa/NameTag (license decision lands in Stage 03;
  embedding is its own future stage).
- NATS-based async Resolver invocation (v1.1 per Pythia conversation Q3).
