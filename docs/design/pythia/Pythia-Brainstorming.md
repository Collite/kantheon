# Pythia Brainstorming — Process Record

> **Purpose.** This document records *how* the Pythia v1 Design was reached, not *what* it concluded. The conclusions live in [`Pythia-v1-Design.md`](./Pythia-v1-Design.md), [`../../implementation/v1/pythia/v1.5-backlog.md`](../../implementation/v1/pythia/v1.5-backlog.md), and [`open-questions.md`](./open-questions.md). This document captures the discussion: the questions that were on the table, the alternatives considered, the insights that broke the tie, and who contributed what.
>
> Useful when reviewing a decision later and asking "why did we pick that?" — the design doc has the *what*, this doc has the *why*.
>
> **Format.** Each section is one decision episode in the order we worked through them.
>
> **Date.** 2026-05-04. Bora and Claude.
>
> **Naming note (added 2026-05-04, post-brainstorm).** Several agent personas were renamed after this brainstorm finished. This document preserves the original brainstorm terminology for historical accuracy. The rename map: **Wrangler → Iris**, **Mover → Charon**, **DataScientist → Metis**, **Resolver → Themis** (service `resolver-mcp` → `themis-mcp`), **Secretary → Hebe** (Hebe lives in the Kyklop project, not Pythia — replaces what koklyp called Talos for that role). The reframe to "Iris is the FE, Golem is a parameterised backend agent template" also happened after this brainstorm — see [framework-evaluation.md](./framework-evaluation.md) and the conversation that followed for the agent-constellation reframing.

---

## 1. How this brainstorm came about

Bora opened with the brief in `pythia-brief.md`: an autonomous analytical agent that does (semi)autonomous data analysis, root-cause analysis, forecasting, and simulation. The brief sketched four use cases (complex Q&A, RCA, forecasting, simulation), four candidate sub-agents (Mover, DataScientist, Wrangler, Secretary), and a tech-stack preference (JVM/Kotlin for the main agent, Python for workers).

Claude's first read of the brief (before the platform docs were available) produced a strawman framing with **five tensions**: agent topology, plan representation, grounding the LLM in the model, output as artifact, and the human-in-the-loop seam. Bora then added the platform documentation — the V1 platform architecture, the Analytical Agent on V1 spec, the Golem frontend docs.

Reading those docs corrected the framing significantly:

- Pythia is **not** the conversational front. The V1 Analytical Agent (heir to ER-AS / AI-AG) already owns chat, named queries, stackable variations, EntityContext, click-to-select, edit-and-resend. Pythia's job *starts where the Analytical Agent's ends* — at multi-step plans, RCA, forecasting, simulation.
- The platform already gives Pythia an extraordinarily clean execution surface: `query-mcp.compile`, `query-mcp.query` (with TransDSL stackable composition), `metadata-mcp`, `fuzzy-mcp`, `data-formatter`. Pythia never writes raw SQL; it composes via stackable queries on top of named queries.
- The platform is *evolving toward Pythia's needs already* — Polars Worker with session DataFrames, sticky routing, the `cnc.role` schema, surfaced pipeline warnings.

Bora confirmed the re-framing and the brainstorm proceeded against this corrected picture. The five tensions became six (Pythia/Wrangler contract, plan representation, investigation artifact, multi-agent topology, tech stack, HITL seam) and we picked tension (1) to pull on first.

---

## 2. Tension 1 — the Investigation contract

This was the longest single episode and produced the most decisions.

### The strawman

Claude proposed working backwards from the three use cases (complex Q&A, RCA, forecasting/simulation) — for each, what does the request look like, what does the streamed artifact look like, what does "done" look like? That produced a strawman `Investigation` request and `InvestigationArtifact` response, plus a streaming protocol mirroring the Analytical Agent's existing `step` / `tool_call` / `envelope` vocabulary.

### Six design questions on the strawman

Claude surfaced six choices the strawman exposed:

1. Style as input or output? (caller hints vs. Pythia infers)
2. Plan approval as a HITL gate (defaults, when REQUIRED)
3. Suspicion classifier (catch the "LLM saw an empty table, hallucinated, called it done" failure mode)
4. Investigations as citable / replayable / continuable objects
5. What Pythia explicitly does NOT do
6. Working memory vs. session DataFrames

### Bora's positions and the key insight

Bora confirmed (1), (2), (4) directly. The big contributions were on (3) and (5):

**On (3) — suspicion → hypotheses.** Bora extended the suspicion classifier idea: *"in my view the plan should define the hypotheses; and if a hypothesis is proven not true, we should update the plan — add more steps, remove some etc."*

This was the single most consequential reframe of the brainstorm. It pushed Pythia from "execute a plan" to "**scientific method**" — formulate hypotheses, design experiments to test them, evaluate results, revise. Procedural Q&A becomes the degenerate case (one trivial hypothesis); RCA becomes natively hypothesis-driven; forecasting and simulation are about model-fit and scenario-sensitivity hypotheses respectively.

The downstream consequences: the plan structure now carries a hypothesis layer with its own state machine (PROPOSED → SUPPORTED / REFUTED / INCONCLUSIVE / ABANDONED); the suspicion classifier gets a second job (hypothesis evaluation) and a structured plan-revision response (PRUNE / PIVOT / DECOMPOSE / HALT); plan revision becomes a first-class operation, not an afterthought.

**On (5) — Pythia is stateless about data.** Bora pushed back on Claude's working-memory framing: *"I do not like the idea of Pythia being a storage; intermediate results are either in existing Worker (Polars, or DuckDB), or materialized and stored on Seaweed or Redis or something, in Arrow-native format."*

This crystallised the **handle table** model: Pythia holds typed pointers (`Handle`); the Mover materialises bytes between storage tiers; **Arrow IPC is the universal exchange format**. Pythia never grows into a data store. The Mover becomes an explicit data-plane utility, not a storage abstraction inside Pythia.

These two contributions plus Bora's confirmations on (1), (2), (4) settled the contract's spine: structured request, structured streamable artifact, hypothesis-driven plan, handle-based data model, citable/replayable/continuable identity, Pythia-doesn't-render principle.

### Streaming protocol

The streaming protocol fell out of the contract. Wrangler subscribes to per-investigation events (`plan_drafted`, `step_started`, `step_completed`, `hypothesis_supported`, etc.) and renders progress meaningfully. The protocol mirrors the Analytical Agent's existing event vocabulary so Wrangler doesn't need a second handler.

---

## 3. Worked examples — Nescafe-Maggi and Private-channel RCA

Claude proposed two end-to-end worked examples to stress-test the contract before tweaking the schema. Bora agreed.

### Nescafe-Maggi (procedural)

A complex Q&A: *"Customers that returned Nescafe stock in the last year and whose Maggi revenue dropped over the last 2 quarters."* The plan was a 4-node DAG (returns query → Maggi revenue query → DataFrame pivot/filter → table render).

Pressure points surfaced (1–6 in our numbering):

1. **Discovery as preface, not plan.** Metadata + fuzzy lookups happen *before* the plan, in service of drafting it. They're real LLM-tool-calls but they're not plan nodes — should live in a `discovery` block in the artifact, visible for audit.
2. **Cross-step parameter binding.** `customer_ids = $H1.customer_id` is the binding primitive; needs list / scalar variants; large IN-lists need a materialise-vs-stay-hot decision.
3. **DataFrame steps require Polars Worker.** Phase 2.2 of the platform. Pythia v0 should compose SQL-only and let DataFrameNode light up when the capability registers.
4. **Replay vs. reproduce.** Relative time references ("last 12mo") behave differently for fresh-data re-runs vs. audit reproduction.
5. **Trivial hypotheses are uniform but not user-visible.** Display priority field needed.
6. **Confidence is meaningless for procedural plans.** Make it nullable.

### Private-channel RCA

The harder example: *"Why is our revenue YoY lower for channel Private?"* The plan was a hypothesis tree — seven sibling first-wave hypotheses (fewer customers / lower order value / price drops / mix shift / seasonal / big-customer churn / data quality) plus declared planning-time loose ends (salesforce headcount and marketing spend, both out-of-data-scope).

Pressure points surfaced (7–14):

7. **Parallel execution is mandatory for RCA** — drives tension (5).
8. **Hypothesis evaluation is hot** — argues for a two-tier LLM strategy (strong planner + cheap evaluator).
9. **Cost budget enforcement is real** — needs a budget tracker that intercepts before parallel batches.
10. **Hypothesis prioritization** for deepening — confidence × explanatory_power × cost_remaining heuristic with LLM tie-breaker.
11. **Variance attribution wants DataScientist** — capability registry needed.
12. **Loose ends declared at planning time, not just at conclusion** — honesty about scope upfront.
13. **Stop conditions formalisation** — when does Pythia decide "we have enough"?
14. **The narrative renderer is its own beast** — multi-paragraph artifact with charts isn't a chat bubble; argues for a Report Renderer as a separate concern.

The RCA example also revealed three **architectural commitments** that hadn't been explicit: a parallel execution scheduler, a capability registry, and a budget tracker. None huge to build, all first-class subsystems.

---

## 4. Discovery is a process, not a step

The first procedural pressure point (Discovery) prompted a substantive framing change. Bora's exact phrasing: *"It is not a 'step', it is actually a 'process' — there might be more candidates, the same term referring to different concepts, misspelled entities etc. The whole 'Intent clarification' with 'Entity clarification' is a process. Agent? Set of Tools? It is not 1-pass, for sure."*

### What's actually inside Discovery

Seven sub-tasks, in a loop: candidate extraction, entity resolution, term-sense disambiguation, intent classification, capability matching, gap detection, decision (clarify / speculate / refuse). Each pass can change the next pass's inputs — entity resolution feeds intent classification, intent classification can change entity resolution priors, etc. **Convergence**, not single-shot.

### Position: extract as a shared sub-agent

Three reasons to extract it as its own MCP service rather than embed in Pythia:

- The Analytical Agent already does most of this work in Python. Either re-implement for Pythia in Kotlin (drift, double maintenance) or extract once.
- Secretary needs it too — without Wrangler's chat session, Secretary still has to resolve "Maggi revenue last quarter" before Pythia can plan.
- The platform pattern is MCP-everything; a `resolver-mcp` fits alongside `query-mcp` and `metadata-mcp`.

Named **Resolver**. Bora confirmed.

### The multi-turn HITL twist

Bora added: *"the resolution is a process with the human-in-the-loop (optionally) so there will be backs and forths."*

This was the second-largest architectural insight. It meant Resolver returns not just `ResolvedIntent` but also `ClarificationRequest` (when blockers exist and disambiguation is INTERACTIVE), and the **caller** (Pythia or Wrangler) holds the resolution continuation across multi-turn. Resolver stays stateless; Pythia threads `prior_context` between calls.

The bigger consequence: this generalised. **Pythia is a pausable investigator** — four `AWAITING_*` states (RESOLUTION_INPUT, PLAN_APPROVAL, USER_INPUT, PLAN_REVISION_APPROVAL) became baked-in architectural properties. State lives in Postgres; in-flight steps drain on park; resume is first-class.

### Locked

- Resolver as a top-level platform MCP service
- Pinned to E-R model for v1; cnc-aware in v1.5
- Stateless service; Pythia holds the resolution continuation
- INTERACTIVE / SPECULATIVE / STRICT disambiguation modes (caller-driven)
- Multi-turn HITL via batched `ClarificationRequest`
- Convergence loop, max 3 passes
- Two profiles: CHAT_QUICK (Analytical Agent today), INVESTIGATION_DEEP (Pythia)

---

## 5. Capability registry — `capabilities-mcp`

The realisation that simplified this: a capability is **an MCP tool plus planner metadata**. The platform's pattern is MCP-everything; capabilities registry = aggregated MCP tool catalog with cost hints, preconditions, search tags, examples. Invocation is just MCP tool calls — no new dispatch layer.

### Split with metadata-mcp

Two distinct registries, two distinct questions:

- `metadata-mcp` answers *"what's in the world?"* — entities, attributes, relations, **named queries** (model-bound), and eventually `cnc` roles. Already exists.
- `capabilities-mcp` (new) answers *"what can be done?"* — non-model-bound operational capabilities: DataFrame ops, ML/stats operations, data movement, rendering.

A named query is model-bound (defined on `customer` and `invoice` entities) → metadata-mcp. A capability like `model.forecast.arima` works on any time series → capabilities-mcp. Resolver queries **both** during capability matching and returns a unified relevant set in `ResolvedIntent`.

### The capability vs. policy distinction

Claude raised the question: where does "always materialise DataScientist output to Seaweed" live? Capability or policy?

Bora's framing made it clean: *"moving something is a capability; but one is not required to use a capability; the fact that something should be moved is **policy**."*

So: **capabilities are primitives** (the option exists); **policies are defaults** (when to use the option). The Mover exposes `move.materialize.seaweed`; Pythia's executor (or the planner LLM's prompt) carries the policy "always persist forecast outputs after generation." Effects on capabilities are descriptive (what *will* happen); policies are prescriptive (what *should* happen).

### Locked

- New `capabilities-mcp` as top-level platform service
- Capability = MCP tool + planner metadata; invocation via MCP, no new dispatch layer
- Resolver queries both metadata-mcp and capabilities-mcp; returns unified relevant set
- Declared in versioned manifests + runtime-registered for liveness; multi-version support
- Primitives are capabilities, defaults are policies (Bora's test)

---

## 6. Parallel execution scheduler — and how it split tension 5

The framing realisation that came out of this discussion was the bigger contribution: **tension 5 splits into two layers**.

### The split

The thing Pythia needs is a typed-DAG executor with concurrency, retries, pause/resume, and budget awareness. That's *not* what LangChain4j / Koog / Embabel / Spring AI primarily give you — those are LLM-orchestration tools. Genuinely useful for the LLM-shaped parts (planner prompt, evaluator, synthesizer), but not the right primitive for executing a 12-node hypothesis DAG with 7 parallel siblings.

So:

- **DAG executor layer** — custom, built on Kotlin coroutines + structured concurrency. Small, well-fitted to JVM. (Decided this round.)
- **LLM orchestration layer** — Koog / Embabel / LangChain4j / Spring AI compete. (Deferred to a focused evaluation.)

Bora confirmed and added a useful future-positioning: *"Temporal will run later. If we want to move this level up (sweeping simulations instead of 1 simulation), that's a new infra."* This positioned Temporal as the v2 upgrade trigger, not v1.

### Bora-confirmed scheduler properties

- Custom Kotlin coroutines + Postgres-backed checkpointer for v1
- Per-investigation parallelism cap (default 5), Pythia-wide global cap, **per-provider** cap (Bora explicitly agreed — DataScientist might say "max 3 concurrent ARIMA fits per pod"; declared in capability manifest)
- Sticky-affinity: inherited from `WorkerSessionDF` parents (data locality), dropped for `SeaweedArrowBlob` parents (free to balance)
- Tiered failure handling: transient retry / permanent INCONCLUSIVE / systemic HALT
- Drain semantics for pause states (in-flight completes; no new launches)
- Budget project-and-reserve before parallel batches
- Priority-aware launch with promotion as slots free

### NATS positioning

Claude's question: NATS as event-sourced state authority, or just transport?

Bora: *"NATS is NOT a state source of truth."*

So: Postgres is authoritative for state; NATS is the streaming event transport (per-investigation event subscriptions for clients) and cross-service notification bus. Postgres-backed event log keeps the cold record after JetStream retention expires.

### Bora's added framework candidates

During this episode (specifically when the LLM orchestration framework question was deferred), Bora added: *"Koog, and Embabel as two additional frameworks to look at."*

These were saved to project memory and added to the open-questions framework evaluation list.

---

## 7. Two-tier LLM strategy — refined to modality × tier

Claude's initial framing: STRONG (planner, synthesizer) + CHEAP (evaluator) + EMBEDDING. Bora's nitpick:

> *"embedding is a different model, not tier, and we might have STRONG embeddings and CHEAP embeddings. But in general I agree, this is just a wording."*

The cleaner factoring (Bora's correction): **modality × tier**, two orthogonal axes.

- **Modality**: CHAT, EMBEDDING (extensible)
- **Tier**: STRONG, CHEAP

So a call is `(modality, tier)`. CHAT/STRONG = Sonnet/Opus for the planner. CHAT/CHEAP = Haiku for the evaluator. EMBEDDING/STRONG = Azure OpenAI today. EMBEDDING/CHEAP = self-hosted (BGE-M3 or similar) when it lands.

Plus a **rule-based 0th tier** (predicates, threshold checks, schema-shape checks) — runs first, no LLM call when it can decide.

### Bora's old-school preference

On the rule-based 0th tier:

> *"Yes, push rules aggressively; I prefer rules (old school)."*

Saved to memory as a Bora preference. The implication for the design: rules-first is the default discipline across suspicion classifier, hypothesis evaluator, intent classifier, gap detection. LLM is the fallback when a rule can't decide.

### Other locked decisions in this round

- Tier-as-intent: Pythia declares (modality, tier, task_kind); LLM Gateway picks the model
- Per-call-site tier defaults in Pythia config (YAML); per-investigation override possible
- Per-call-site mid-flight escalation deferred to v1.5+ (power feature; calibration risk)
- Structured output / tool-use for all Pythia-parsed calls
- Caching at the LLM Gateway (Redis-backed); not in Pythia
- Streaming for synthesizer + planner only (cheap-tier classifiers don't need it)
- Embeddings already in production (Azure OpenAI); self-hosted CHEAP variant is v1.5

---

## 8. Budget tracker

Most of the budget tracker was specified by the time we reached it explicitly. This was the fast pass.

### Multi-dimensional budget

Caller can set any combination of `max_llm_cost_usd`, `max_llm_tokens`, `latency_budget_ms`, `max_step_count`, plus a categorical `depth_budget` (SHALLOW / NORMAL / DEEP) that maps to defaults. Most-restrictive wins.

### Threshold ladder

- 75 % → emit `budget_threshold` event (warning; no action)
- 90 % → if `on_budget_threshold: ASK`, pause; otherwise stronger warning
- 100 % → HALT_GRACEFULLY: skip remaining batches, jump to synthesizer with current evidence, conclusion marked `budget_truncated`
- 110 % → emergency hard halt; one synthesizer call still permitted for graceful close-out

### Bora's positions

- Default policy is HALT_GRACEFULLY at 100 %, not ASK (avoid mid-investigation modal interruptions). ASK is opt-in.
- Per-task-kind token estimates as configured constants for v1; learned moving averages deferred to v1.5.

The tracker connects directly to the scheduler (project-and-reserve before parallel batches) and to the LLM Gateway (tier-pricing API for cost projection; `cached: bool` on responses for accurate attribution).

---

## 9. Stop conditions

The framing realisation: stop conditions are **investigation-type-specific with a shared spine**, not one universal criterion.

### Shared spine (all investigation types)

- STOP_USER (explicit halt)
- STOP_BUDGET (delegated to budget tracker)
- STOP_HARD_CAP (max_step_count, max_revisions, max_depth)
- STOP_PLAN_EXHAUSTED (no runnable steps, no pending revisions)
- STOP_GOAL_REACHED (type-specific completion criterion met)

### Per-type completion criteria

- PROCEDURAL: final answer step completed
- RCA: explained_variance ≥ 0.75 AND ≥ 1 SUPPORTED hyp with confidence ≥ 0.6 AND all top-tier hypotheses evaluated
- FORECAST: model fit AND diagnostics pass AND CI within target
- SIMULATION: all scenarios computed (or sweep convergence stable)

These are policies, not code — declarative expressions, ops-tunable.

### Four brakes on RCA dynamic plan growth

Layered:
- Decomposition depth ≤ 3 levels (hard cap)
- Per-hypothesis test count ≤ 3 (hard cap)
- Plan revision count ≤ 2 for NORMAL, ≤ 5 for DEEP (hard cap)
- Marginal-value heuristic: each new hypothesis carries an LLM self-rating of expected explanatory power; below 5 % → don't pursue (soft brake)

### Bora's scoping move

When Claude noted the marginal-value heuristic carries calibration risk (LLMs are bad at predicting their own usefulness), Bora's response was a clean scoping decision:

> *"I agree for now, for the v1. The dynamic planning and parts will be a project of its own; we are only setting up the envelope for this now."*

This recurred throughout the brainstorm: Bora consistently positioned Pythia v1 as the **envelope** — the structured shape, contracts, lifecycle, scaffolding — with the genuinely AI-shaped parts (sophisticated dynamic planning, calibrated evaluators, learned token estimates) as v1.5+ projects of their own. The envelope is what's hard to refactor; the AI sophistication can iterate.

The honest variance decomposition (DataScientist's `model.decompose.variance`) was also explicitly tagged as v1.5 work; Pythia v1 uses heuristic-with-cap.

### Synthesizer conditioning on stop reason

The synthesizer's prompt is conditioned on the `stop_reason`. STOP_GOAL_REACHED → confident framing. STOP_BUDGET / STOP_HARD_CAP → flagged with `budget_truncated` and lower confidence. The artifact's `confidence.caveats` carries the stop reason explicitly.

---

## 10. Loose ends

The realisation that simplified this: loose ends aren't a separate concept — they're a **derived view over hypothesis state**.

Add `OUT_OF_SCOPE` to the Hypothesis status enum: `PROPOSED | UNDER_TEST | SUPPORTED | REFUTED | INCONCLUSIVE | ABANDONED | OUT_OF_SCOPE`.

Then: every loose end is a hypothesis that didn't reach a definitive verdict, plus a reason. The artifact carries a `loose_ends` view over hypothesis state at stop time, sourced from PLANNING_TIME (declared by the planner up front as out-of-scope) or EXECUTION_TIME (orphaned by stops, abandoned-after-INCONCLUSIVE, pivoted-away).

### What this enables

- **Honesty upfront.** When `plan_approval: REQUIRED`, the user sees both in-scope hypotheses and out-of-scope ones. They can react: "salesforce headcount IS available — see /sharepoint/hr_data" → planner re-drafts.
- **Synthesizer references loose ends in the narrative**, not as a footer. "We did not investigate salesforce headcount because that data is out of scope. Within the data we have, the dominant driver appears to be …"
- **`suggested_followup` makes loose ends actionable.** Each loose end carries an optional string that becomes the seed for a child investigation when the user wants to pursue it.

### Bora's call: REJECT_WITH_COMMENT in v1, full editing v1.5

For plan approval:

> *"Yes, makes sense for v1 (REJECT WITH COMMENT)."*

So v1 supports `APPROVE | REJECT_WITH_COMMENT` (re-plan with comment as additional context). True interactive plan editing — promote/demote/add/reorder hypotheses — is v1.5 work. The data model supports it; only the UX is deferred.

### Suggested-followup in v1

> *"Yes, in v1."*

Cheap (one optional string field); valuable in the artifact even if Wrangler renders it as plain text initially. Real one-click child-investigation UX is v1.5.

---

## 11. Hypothesis prioritization

Three moments where prioritization fires:

- **Initial planning** — what's tested first? Determines which hypotheses fill the first parallel batches.
- **Deepening after first wave** — when several hypotheses come back SUPPORTED, which gets decomposed first?
- **Plan revision** — where do new hypotheses slot among the pending queue?

### One scoring frame, five dimensions

```
priority_score(hyp) = confidence
                    × estimated_explanatory_power
                    × (1 / cost_estimate_for_next_step)
                    × diagnostic_power
                    × novelty_bonus
```

Each scaled to [0, 1]. The formula is hard-coded; dimension *weights* are exposed in YAML for ops tuning.

### LLM tie-breaker, narrowly scoped

When the heuristic produces a top-2 within ~10 %, fire a CHEAP-tier call to break the tie. Most of the time the heuristic decides; the LLM is invoked only for genuine ties. Power-saving by design.

### Bora's clean confirmations

On (i) diagnostic power as a separate dimension: *"Agreed."*

On (ii) where diagnostic_power comes from (planner-supplied at proposal time vs. structurally inferred): *"Agreed."*

The brainstorm's pace had picked up by this point — Bora was giving fast, decisive yes/no answers with occasional refinements.

### Layering with related concerns

Three concerns compose without overlap:
- **Marginal-value brake** (#13) decides *whether to run at all* — gate
- **Prioritization** (#10) decides *order* — queue
- **Scheduler** (#7) decides *concurrent slot allocation* — pool

Each lives in one place.

---

## 12. RenderNode + Report Renderer split

The framing realisation: rendering is *always* downstream of the artifact, and there are *many* renderers, not one. Wrangler renders the chat bubble; Report Renderer produces DOCX/PDF/HTML; Secretary produces messaging digests; API consumers get JSON; future BI tools embed artifacts.

### Three layers, cleanly separated

```
1. Synthesizer (in Pythia, CHAT/STRONG)
   produces a structured RenderableArtifact — sequence of typed Blocks

2. Formatter libraries (platform)
   - data-formatter: Handle → markdown/csv/tsv/json table
   - chart-formatter (NEW): Handle → Vega-Lite spec

3. Renderers (downstream consumers)
   - Wrangler: chat bubble (real-time streaming)
   - Report Renderer: DOCX/PDF/HTML (on-demand)
   - Email/digest, API, future BI
```

The artifact is the seam. Every renderer consumes the same structured artifact; differences are in target medium, not in content.

### Vega-Lite as the canonical chart spec

Claude proposed Vega-Lite over ECharts. Reasons: declarative JSON, multi-target, mature; Vega-Lite → ECharts adapter at the FE is small. The existing FE uses ECharts; the trade-off is FE-side adapter work vs. cross-renderer consistency.

Bora: *"ok to Vega Lite."*

This stays as Q8 in `open-questions.md` for confirmation against the FE team's actual preferences when Wrangler renders its first Pythia chart.

### NARRATIVE_FRAGMENT kept

For per-hypothesis localized text generation ("Customer count grew +2% YoY, refuting hypothesis A"), distinct from the synthesizer's full-artifact composition. CHEAP-tier per-fragment LLM call.

Bora: *"yes, keep NARRATIVE FRAGMENT."*

### Block-by-block streaming

`synthesizer_block_started` / `synthesizer_block_streaming` (token-level inside a block) / `synthesizer_block_completed` events with explicit `block_index`. Allows out-of-order delivery (ChartBlock can take 100-500ms to materialise; text blocks can stream while the chart renders async).

---

## 13. Procedural cleanups (#2-6 swept)

By the end, the remaining pressure points (#2-6 from the procedural example, kept until last) were quick batch confirmations:

- **(2) IN-list threshold** — 500 default; per-engine refinement v1.5
- **(3) Polars Worker as enabler not prereq** — feature-flag DataFrameNode via capabilities-mcp liveness
- **(4) Replay vs. reproduce** — both modes in v1; reproduce-fidelity bounded by Seaweed retention
- **(5) Hypothesis display priority** — HIDDEN / SECONDARY / PRIMARY enum, planner-set initial, synthesizer-adjustable at conclusion
- **(6) Confidence nullable for procedural** — `Conclusion.confidence` is nullable; null for procedural, populated for hypothesis-driven

Bora: *"All that makes sense."*

---

## 14. Synthesis pass — producing the design docs

After all 14 pressure points were pinned, the design moved from brainstorm to artifact. Claude proposed three output files:

- `Pythia-v1-Design.md` — comprehensive design doc, modeled on `Analytical Agent on V1.md`'s structure (vision, place in platform, contract, worked examples, components, dependencies, roadmap, decisions, glossary)
- `v1.5-backlog.md` — explicitly-deferred items with what / why / when-to-revisit
- `open-questions.md` — things needing decision before or shortly after v0 implementation (Q1 = framework evaluation; plus seven smaller-but-structural questions)

Bora confirmed: *"OK, confirmed, full draft, then I review, please."*

The design doc was drafted in four passes (vision/contract; worked examples; components/dependencies; roadmap/decisions/glossary). The backlog and open-questions docs were drafted afterwards. Each captures what was discussed, but in different framings — the design doc is the *what*; the backlog is the *deliberately deferred*; the open-questions is the *not-yet-decided*; this brainstorming doc is the *how we got there*.

---

## 15. Reflections on the process

Three things worth noting about how this brainstorm went, useful for future Pythia design conversations.

### The platform docs were load-bearing

Claude's first framing (before the platform docs) was wrong in important ways — Pythia was over-scoped (encroaching on the Analytical Agent), and the framing of plumbing concerns missed how clean the platform's execution surface already is. The brainstorm got significantly sharper after Bora added the platform docs. The lesson: **design conversations against an unspecified platform produce strawmen; design conversations against the actual platform produce architecture**.

### Bora's "we are only setting up the envelope" reframe

Recurred throughout. When sophisticated AI-shaped concerns came up (calibrated marginal-value brakes, learned token estimates, mid-flight tier escalation), Bora's instinct was consistently to defer them and lock the envelope first. The result: Pythia v1 is structurally complete — contracts, lifecycle, components, integrations all specified — while explicitly deferring the genuinely AI-experimental work to follow-on phases. This is conservative in the right way. The contracts won't change (we hope) when the AI sophistication evolves.

### The hypothesis-driven reframe was the highest-leverage moment

Bora's contribution on tension 1, sub-question 3 — "the plan should define hypotheses; if a hypothesis is proven not true, update the plan" — reframed Pythia from a plan executor to a scientific-method investigator. Almost every later decision was downstream of this: the suspicion classifier's two-job design, the plan revision FSM (PRUNE / PIVOT / DECOMPOSE / HALT), the loose-ends-as-derived-view, the per-type stop conditions, the four brakes on plan growth, the synthesizer's stop-reason conditioning. Without this reframe, Pythia would have been a more conventional "execute a plan, get a result" agent, less suited to the actual use cases (RCA, forecast model selection, simulation sensitivity).

### Pace observation

The early sections (tension 1, worked examples) were dense and slow — many design questions, careful consideration, multiple back-and-forths. The later sections (capabilities-mcp, scheduler, LLM tiers, budget, stop conditions, prioritization, renderers) moved much faster — the contract was settled, downstream decisions composed cleanly. The procedural-cleanups sweep (#2-6) was a single batch confirmation. This is what good brainstorms tend to look like: front-loaded depth, back-loaded velocity.

---

*Document owner: Bora. Authored during the design brainstorm by Claude (Sonnet 4.7), 2026-05-04.*
