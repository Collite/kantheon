# Pythia — Open Questions

> Decisions that need to land before (or shortly after) Pythia v0 implementation begins. The brainstorm of 2026-05-04 left these explicitly unresolved or under-specified. Each entry sketches the question, the candidate answers, and what additional information would unlock a decision.

## Status as of 2026-05-04

| # | Topic | Status | Resolution |
|---|---|---|---|
| Q1 | LLM orchestration framework | **Resolved** | **Koog** (see below) |
| Q2 | Iris vs. Analytical Agent identity | **Resolved** | Same agent, evolved (Golem evolves into Iris — see [Golem-evolution-plan.md](#) — pending) |
| Q3 | Pythia API surface | **Resolved** | HTTP REST (with streaming) for v1.0; NATS for events deferred to v1.1 |
| Q4 | Themis migration path | **Resolved** | Themis is a new platform service (extracted from Analytical Agent) |
| Q5 | Retention policies | **Resolved** | Defaults confirmed (Seaweed: 90 d production / 7 d SHALLOW; NATS: 24 h; Postgres event log indefinite with 1-year archive) |
| Q6 | Tenant model | **Resolved** | `tenant_id` added to Investigation.caller; one tenant per deployment for v1 |
| Q7 | Metis initial capability set | **Resolved** | Confirmed (ARIMA, Prophet, linear; project + simulate); decompose.variance v1.5 |
| Q8 | Vega-Lite vs ECharts | **Resolved** | **Vega-Lite** for both Pythia and (migrated) Golem; see [golem-charting-migration.md](./golem-charting-migration.md) for the migration plan |

---

## Q1 — LLM orchestration framework choice

**The question.** Tension (5) was split into two layers during the brainstorm: the **DAG executor** (decided: custom Kotlin coroutines + Postgres-backed checkpointer) and the **LLM orchestration framework** (deferred). Pythia's LLM-shaped parts — planner, evaluator, plan reviser, suspicion classifier, loose-end identifier, synthesizer, NARRATIVE_FRAGMENT generators — all want common patterns: prompt templating, structured output / tool-use, streaming, multi-vendor abstraction (since LLM Gateway brokers multiple vendors), observability hooks, and graceful composition with Spring (since `llm-gateway` is Spring Boot).

**Candidates** (in alphabetical order; not ranked):

- **Embabel** — Spring-based agent framework. Pro: Spring-native, fits the gateway. Con: maturity / ecosystem to verify.
- **Koog** — JetBrains' Kotlin-native agent framework. Pro: Kotlin-first, idiomatic. Con: relative newness; structured-output story to verify.
- **LangChain4j** — Mature JVM port of LangChain. Pro: large ecosystem, well-documented patterns, multi-vendor LLM abstraction baked in. Con: opinionated abstractions; sometimes more than we need.
- **Spring AI** — Spring's official AI integration. Pro: first-party Spring; aligns with `llm-gateway`. Con: still evolving; agent-orchestration patterns less developed than LangChain ecosystem.
- **Roll-your-own thin wrapper** — Direct calls to LLM Gateway with a small in-house abstraction. Pro: no framework lock-in; total control. Con: re-builds well-trodden patterns (prompt templating, tool-use, streaming) by hand.

**Evaluation criteria** (proposed):

1. **Structured output / tool-use ergonomics** — Pythia parses *every* LLM response (Plan, Evaluation verdict, Plan revision decision, Synthesizer block sequence). The framework must make tool-use first-class for both Anthropic and OpenAI families (the two we route via Gateway).
2. **Streaming support** — Synthesizer + planner stream. The framework must support token-level streaming with structured-output validation as tokens arrive (or at completion-of-tool-call boundaries).
3. **Multi-vendor abstraction** — Pythia talks to LLM Gateway (which talks to multiple vendors). The framework must not assume a single vendor SDK.
4. **Composability with Spring** — The Gateway is Spring Boot. Pythia is Kotlin/JVM (Spring-compatible). The framework should not fight the existing infrastructure.
5. **Observability** — Prompts, completions, tool calls, costs, latencies all need to flow into existing OTel instrumentation.
6. **Async / coroutines** — Pythia's executor uses Kotlin coroutines. The framework should not force a competing concurrency model (Reactor, callbacks).
7. **Footprint** — How much of the framework do we actually need? Lighter is better.

**What's needed to decide.** A focused evaluation: build a small spike (planner + evaluator) in each of the top-2 candidates, score against the criteria above, pick. Estimated effort: 2-3 days per candidate spike.

**Status.** **Resolved 2026-05-04 — Koog.** See [framework-evaluation.md](./framework-evaluation.md) for the full analysis. Bora's stated reasons:

1. Trust in JetBrains and their products. Long-term commitment signal (Junie + IDEA AI Assistant use Koog internally).
2. Strong personal preference for **Kotlin** as a language; Kotlin-idiomatic API has high value.
3. **Multi-agent strategy**: other agents Bora plans to build will be graph-based. He wants one framework across all of them. Koog is graph-first AND has a usable LLM-call subset for non-graph agents like Pythia.

For Pythia specifically: we use Koog's LLM-call layer (`PromptExecutor`, `LLMClient`, structured output, MCP) and *not* the strategy/graph runtime — Pythia has its own DAG executor (Kotlin coroutines + Postgres-backed checkpointing). For the future graph-based agents in Bora's roadmap, Koog's primary paradigm fits naturally.

---

## Q2 — Iris vs. Analytical Agent: same surface, two roles?

**The question.** This document refers to "Iris" as the conversational front that submits investigations to Pythia. The Analytical Agent (per `docs/platform/Analytical Agent on V1.md`) is the V1-era Czech-speaking entity-aware multi-turn assistant. Are these the same thing — Iris being the new name / role for a Pythia-aware Analytical Agent — or two distinct agents?

**Two interpretations:**

- **(a) Same agent, evolved.** The Analytical Agent gains the capability to recognise complex / RCA-shaped questions and submit them as Pythia investigations, while continuing to handle simple named-query questions itself. "Iris" is just the role the Analytical Agent plays when it submits to Pythia.
- **(b) Separate agent.** A new agent (Iris) sits alongside the Analytical Agent. The Analytical Agent handles single-call analytical questions; Iris handles complex investigations. Front-end routing decides which receives a given user message.

**Why this matters.** Affects (i) where the "is this complex enough for Pythia?" routing decision lives, (ii) how Iris shares state with the Analytical Agent (or vice versa) for EntityContext, snapshot rollback, etc., (iii) how the Backstage catalog represents the agent landscape.

**My instinct:** (a) — same agent, evolved. Reasons: shared EntityContext / PendingSelection state is operationally simpler when one agent owns it; user UX is one chat, not two; the routing decision ("submit to Pythia vs. handle locally") lives where the conversation lives. But this needs Bora's call.

**Status.** Open; needs decision before Iris-side integration work plans are drawn.

---

## Q3 — Pythia API surface: HTTP / gRPC / MCP / NATS-only?

**The question.** Pythia is invoked by Iris, Hebe, scheduled jobs, and API clients (per §1.1 of the design doc). What's the actual transport / API style? §1.3 says "not an MCP server in v1" — so what *is* it?

**Candidates:**

- **HTTP REST + NATS for events.** REST endpoints for `submit`, `get_status`, `get_artifact`, `approve_plan`, `answer_clarification`, `halt`. NATS JetStream for live streaming.
- **gRPC bidi-streaming.** Single bidi stream per investigation; client sends control messages (approval, halt), server sends events. Tighter coupling.
- **MCP exposure (deferred per §1.3).** Pythia exposes its operations as MCP tools. Lets any MCP-aware client (Iris, Claude in any host, ad-hoc agents) invoke Pythia uniformly. Deferred to v1.5+.

**Why this matters.** Affects what Iris integration looks like, what API tooling needs to exist, and what the Hebe / scheduled-job invocation paths look like.

**My instinct:** HTTP REST for the request/response/control surface (familiar, easy to consume from anything), NATS JetStream for event streams (Pythia already emits them; clients subscribe). MCP exposure can come in v1.5 once the contract is stable. But this is structurally important — needs a position.

**Status.** Open; needs decision before API code is scaffolded.

---

## Q4 — Where Iris/Analytical-Agent's existing entity-resolution code goes when Themis is extracted

**The question.** Themis (new platform service) is to be extracted from the Analytical Agent's existing Czech entity detection, fuzzy matching, and named-query selection logic. What's the migration path? Two scenarios:

- **(a) Big-bang extraction.** A platform-team work package extracts Themis as `themis-mcp`, the Analytical Agent is refactored to call Themis instead of doing the work in-process. One PR (or short series); cleanly cut-over.
- **(b) Parallel path.** Themis is built fresh, runs alongside the Analytical Agent's in-process logic for a transition period, both produce results, results are diffed for confidence-building, then the Analytical Agent cuts over.

**Why this matters.** Affects effort estimation for the Analytical Agent team and the dependency tree for Pythia v0.

**My instinct:** (b) for risk-aversion — the Analytical Agent's existing logic is battle-tested in Czech; Themis is new; running both lets us catch regressions before the cut-over. Adds operational complexity for the transition window.

**Status.** Open; needs Bora's call. Not blocking Pythia v0 design but blocking implementation sequencing.

---

## Q5 — Per-deployment retention policies (Seaweed evidence blobs, NATS event streams, Postgres event log)

**The question.** Pythia produces persistent investigation artifacts. The artifact references evidence handles (Seaweed Arrow blobs); the streaming event log is in NATS (short-term) and Postgres (long-term). Retention policies are per-deployment, but defaults need to be set:

- **Seaweed evidence blobs** — current proposal: 90 days for production investigations, 7 days for SHALLOW. Acceptable?
- **NATS event stream retention** — current proposal: 24 hours (live streaming + recent reconnect). Postgres event log keeps the cold record.
- **Postgres event log** — current proposal: indefinite (with archival to Seaweed for cold investigations after 1 year).

**Why this matters.** Affects `reproduce()` fidelity (if evidence blobs are GC'd, reproduce falls back to re-execution against frozen-time-resolved-params). Also affects storage cost.

**Status.** Open; sensible defaults proposed; needs Bora's confirmation before deployment manifests are written.

---

## Q6 — Per-investigation tenant model

**The question.** Each `Investigation.caller` has a `user_id`. But Pythia doesn't have an explicit *tenant* (organisation, customer, business unit) field. Whois maps Keycloak users to ERP user IDs (per the V1 platform docs); is that enough, or does Pythia need to track tenant explicitly for partitioning, billing, retention policies?

**My instinct:** Add `tenant_id` to `Investigation.caller` for v1 — even if unused initially, having the field future-proofs partition-by-tenant scaling, per-tenant retention policies, per-tenant budget caps, billing.

**Status.** Open; small decision but should be locked before the Investigation schema is implemented.

---

## Q7 — Initial Metis capability set

**The question.** §6.2 lists Metis's initial capability set:

- `model.fit.arima`, `model.fit.prophet`, `model.fit.linear`
- `model.project.arima`, `model.project.prophet`
- `model.simulate.scenario`

Is this the right v1 set? Specifically:
- Does forecasting need anything else for the use cases at hand (exponential smoothing? state-space models? ML-based regressors)?
- Is there a v1 use case for `model.fit.logistic` or `model.fit.tree` (classification)?
- Is `model.simulate.scenario` general enough, or should we also have specific simulation kinds (Monte Carlo, sensitivity analysis primitives)?

**My instinct:** Start with the listed set; let real Pythia investigations drive the next additions. ARIMA + Prophet covers the major time-series cases; linear regression covers basic decomposition. Logistic / tree-based / Monte Carlo are v1.5 additions when they're needed.

**Status.** Open for confirmation; not blocking but should be aligned with the Metis-agent team early.

---

## Q8 — Vega-Lite vs. ECharts at the FE

**The question.** §6.2 chose Vega-Lite as the canonical chart spec emitted by `chart-formatter`. The existing Iris / Analytical Agent FE uses ECharts (per `docs/golem/architecture.md`). Two paths originally proposed:

- **(a)** `chart-formatter` emits Vega-Lite; the FE has a small Vega-Lite → ECharts adapter.
- **(b)** `chart-formatter` emits Vega-Lite; the FE renders Vega-Lite directly (drop ECharts entirely; migrate Golem).
- **(c)** `chart-formatter` emits both (over-engineering for v1).

**Status.** **Resolved 2026-05-04 — option (b): Vega-Lite everywhere.** Bora's pushback on the original framing was correct: two chart specs in the same product is a smell; pick one and use it everywhere. Reasons:

1. Vega-Lite has first-class server-side rendering (`vl-convert`), which Pythia's Report Renderer needs for DOCX/PDF output. ECharts can be server-rendered but is more involved.
2. Vega-Lite's declarative grammar is more concise for programmatic chart generation (chart-formatter, agent emissions). ECharts requires more verbose config.
3. Pythia's chart shapes are analytical (forecast CIs, decomposition bars, error bars, faceted plots) — Vega-Lite's wheelhouse. ECharts can do them but with more wrestling.
4. Bora's roadmap: future agents will be analytical-investigator-shaped, not dashboard-shaped (where ECharts would be ahead). Dashboarding is a separate concern, handled differently.

The migration plan for Golem's existing ECharts usage is captured in [golem-charting-migration.md](./golem-charting-migration.md).

---

*Last updated: 2026-05-04.*
