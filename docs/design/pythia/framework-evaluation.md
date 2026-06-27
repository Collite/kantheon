# Pythia LLM-Orchestration Framework Evaluation

> **Purpose.** Evaluate the candidate frameworks for Pythia's LLM-orchestration layer (Open Question Q1). Pythia owns its DAG executor (custom Kotlin coroutines + Postgres-backed checkpointing) — the framework is *only* responsible for the LLM-call ergonomics: prompt construction, tool-use, structured output, streaming, observability hooks. We are not buying a workflow/graph engine.
>
> **Constraints.**
> - Pythia is Kotlin/JVM. Existing platform services are Kotlin + Ktor (one Spring Boot exception: `llm-gateway`). Bora is **neutral** on Spring as a selection criterion — neither bonus nor penalty for Spring vs. non-Spring. A framework that requires Spring Boot is a *deployment-shape* note (Pythia would be Spring Boot rather than Ktor) but not a strike against the framework.
> - LLM Gateway already provides vendor abstraction (multi-provider routing, caching, pricing). Frameworks that "wrap many LLM providers" duplicate the Gateway's job; we'd use only their typed-call interface.
> - Pythia produces structured output for *every* parsed call (Plan, EvaluationVerdict, PlanRevision, Conclusion). Tool-use / structured-output ergonomics are the highest-leverage criterion.
>
> **Status.** Research draft — for discussion. Last updated 2026-05-04 (revised from earlier draft that incorrectly disqualified Spring-coupled frameworks; the original draft misread Bora's "I do not care about Spring" as "I am opposed to Spring." Re-evaluated below with Spring being neutral.)

---

## 1. The ideology question (the most interesting part)

Bora flagged this as the thing he most wants to discuss. The four candidate frameworks (plus homemade) embody **five different design philosophies**. They produce very different code at the agent level, even when the underlying LLM calls are similar.

### A. Graph / state-machine (LangGraph, Koog)

You declare *nodes* (functions, agents, tool calls) and *edges* (transitions, possibly conditional). The framework runs the graph: entering nodes, executing them, picking outgoing edges, possibly cycling back. State is explicit and passed between nodes.

**Strengths.** Topology is visible upfront. State is explicit. Checkpointing and rollback are natural. Easy to reason about "what happens next." Excellent for workflows whose structure is known.

**Weaknesses.** When the agent's behavior is genuinely dynamic (the *next step depends on what just happened in non-trivial ways*), graphs become a forest of conditional edges and dispatcher-nodes. The graph fights you instead of helping.

**Fit for Pythia.** *Pythia's plan DAG IS a graph.* But it's a custom graph with a hypothesis layer, sticky-affinity scheduling, typed PlanNode variants, and Postgres checkpointing — and we've decided to build that ourselves. Buying a generic graph framework would force us to map our PlanDag onto its node abstraction. **We'd use a graph framework's LLM-call layer, not its graph runtime.**

### B. GOAP / Action Planning (Embabel)

Goal-Oriented Action Planning. You declare *goals* (desired world states), *actions* with *preconditions* and *effects*, and the framework uses A* search to find a plan dynamically at runtime. The plan is *derived*, not authored. Adding a new action with appropriate effects can change the plans the framework produces, automatically — without modifying any orchestration code.

**Strengths.** Genuinely dynamic agents — the framework discovers paths the developer never explicitly programmed. Modular: actions are isolated; the planner composes them. Great for agents in unstructured environments where flow can't be pre-specified.

**Weaknesses.** Less explicit. Hard to reason about "what will happen" — you reason about goals and trust the planner. The framework is doing real work that you don't see. Different debugging mental model.

**Fit for Pythia.** **Conceptually conflicting.** Pythia's planner is an LLM that produces a typed PlanDag — we *are not* deriving plans from declared actions. If we adopted GOAP, either we'd ignore Embabel's planner (defeats the purpose) or we'd shoehorn the LLM-planner into Embabel's action-planning model (forcing two planning paradigms to coexist). Embabel without GOAP becomes a thin wrapper over Spring AI — at which point we'd use Spring AI directly.

### C. AI Services / Annotation-based (LangChain4j AiServices, Spring AI ChatClient)

You declare a typed Java/Kotlin *interface* with annotations describing the prompt; the framework synthesizes a proxy that handles prompt construction, tool registration, response parsing, returning a typed result. Each LLM call becomes a method call.

```kotlin
interface PlannerService {
    @SystemMessage("You are Pythia's plan composer for analytical investigations.")
    @UserMessage("Plan the investigation for: {{intent}}")
    fun planInvestigation(@V("intent") intent: ResolvedIntent): PlanDag
}
```

**Strengths.** Each LLM call site is a typed function. Cleanly separates "what we want from the LLM" (interface declaration) from "how the LLM is called" (framework). Reusable across call sites. Natural fit for individual LLM operations (planner, evaluator, synthesizer).

**Weaknesses.** Annotation magic — you don't see the request bytes. Customising prompt building or response handling means escaping the abstraction. Doesn't give you anything for *flows* — that's your job.

**Fit for Pythia.** *Excellent fit for the LLM-call layer.* Pythia's planner, evaluator, plan-reviser, synthesizer, suspicion-classifier, NARRATIVE_FRAGMENT generator are each one well-defined typed function. AiServices treats them all uniformly. The Pythia executor calls these as plain Kotlin functions; the framework handles the LLM-call mechanics.

### D. Direct / Imperative (Homemade, also LangChain4j ChatLanguageModel low-level)

You write code that constructs prompts and parses responses. No framework on top of the LLM call.

**Strengths.** Total control. No abstraction leakage. Readable code. Easy to add custom behavior. Easy to debug (you see every byte).

**Weaknesses.** You write the plumbing (prompt templating, tool-use formatting, response parsing, retry-on-parse-failure, streaming). Plumbing for one call site is small; for ten call sites with consistent quality, less small.

**Fit for Pythia.** *Viable — and made easier by the platform.* The LLM Gateway already handles vendor abstraction, caching, pricing. Kotlin gives us serialization + structured concurrency. Ktor gives us streaming HTTP. The "framework" we'd write is a small DSL over these — perhaps 300–500 lines.

### E. Chains (LangChain core)

Compose linear or near-linear pipes of LLM calls and tool invocations. The framework runs the pipeline.

**Fit for Pythia.** Mostly subsumed by the AiServices pattern in LangChain4j; chain-based composition isn't itself the value proposition. Listed for completeness.

---

## 2. Per-framework deep dive

### 2.1 Koog (JetBrains)

**Ideology.** Graph-based (Strategy DAG with nodes and edges) — same paradigm as LangGraph, in pure Kotlin.

**Maturity.** Open-sourced at KotlinConf 2025; v0.7.3 by May 2026. Used internally at JetBrains for **Junie** (AI coding agent) and IntelliJ IDEA AI Assistant — production-tested in big-name products. Active development.

**Pythia-relevant capabilities.**

- **Pure Kotlin Multiplatform.** Idiomatic, structured concurrency, Flow-based streaming. No Java-comfort patterns.
- **Type-safe Kotlin DSL** for prompt construction.
- **Typed tool definitions and structured output** via Kotlin data classes + `kotlinx.serialization`. Auto-generates JSON schemas. Includes `StructureFixingParser` for corrective parsing on validation failure — exactly the pattern Pythia needs for its strict-output planner / evaluator / synthesizer calls.
- **Streaming via `Flow<StreamFrame>`** (Kotlin-idiomatic; not CompletableFuture).
- **Multi-vendor abstraction (`PromptExecutor`)** — but redundant for us (LLM Gateway does this). We'd configure Koog to talk to LLM Gateway as a single endpoint and let it pass-through.
- **MCP support** (`koog-agents-mcp`) — both stdio and SSE transports. Pythia consumes MCP services; this is real value.
- **OpenTelemetry built-in** with auto-instrumentation. Integrates with Langfuse and W&B Weave out-of-box.
- **Modular.** The LLM-call layer (`LLMClient`, `PromptExecutor`) is usable standalone without the graph runtime.
- **Spring optional** — `koog-spring-boot-starter` is composable, not required.

**Pros for Pythia.**
- Most Kotlin-idiomatic option by a wide margin
- Coroutines + Flow throughout (no CompletableFuture bridging)
- StructureFixingParser is exactly the corrective-parsing pattern we need
- MCP client built-in (Pythia consumes MCP services, this is value)
- JetBrains-backed (long-term maintenance signal)
- Modular — we use the LLM-call layer subset and ignore the graph runtime

**Cons for Pythia.**
- Younger than LangChain4j (open-sourced late 2025; ~6 months mature)
- Smaller community than LangChain4j; fewer Stack Overflow answers when stuck
- Graph runtime is Koog's primary paradigm — using only the LLM-call layer goes against the docs' grain (won't fight us, but the "happy path" is graphs)

> **Choose Koog if** you want a Kotlin-idiomatic framework with native coroutine/Flow support, value MCP-client built-in, and trust JetBrains' commitment given that they're using it for Junie and the IDEA AI Assistant.
>
> **Avoid Koog if** maturity / community size is a primary concern, or if you'd resent that the docs lead with graphs (the paradigm we're not buying).

---

### 2.2 Embabel (Rod Johnson, built on Spring AI)

**Ideology.** GOAP — Goal-Oriented Action Planning, with A* search over preconditions/effects. Fundamentally different from the graph paradigm.

**Maturity.** Pre-release (0.3.x snapshots) as of May 2026. Actively maintained. Limited production adoption visible.

**Spring relationship.** Built on Spring Core and Spring AI. Spring's dependency injection is core. Adopting Embabel means deploying Pythia as Spring Boot.

**Kotlin support.** Written in Kotlin (Rod Johnson values Kotlin), but the user-facing API uses Spring annotations (`@Agent`, `@Action`, `@Goal`, `@Condition`, `@Tool`). Code feels like Java-shaped Kotlin, not idiomatic Kotlin.

**Pythia-relevant capabilities.**
- Typed structured output via JSON schema grounding
- `@Tool` annotation on domain methods
- Spring AI's prompt templating, multi-vendor support (since it builds on Spring AI)
- MCP support (consume + expose) via Spring AI's MCP integration
- OpenTelemetry zero-code instrumentation, Spring Actuator-based

**Pros for Pythia.**
- GOAP is genuinely interesting for *highly dynamic* agents — Embabel's pitch is real
- Rod Johnson's involvement signals serious design thinking
- Modular planners (GOAP, Utility AI, state machines can be mixed)

**Cons for Pythia.**
- **GOAP is not Pythia's planning model** — Pythia's planner is an LLM producing a typed PlanDag. Adopting Embabel's action-planning would mean either ignoring its centerpiece (defeats the purpose) or running two paradigms in parallel (forced complexity).
- Embabel-without-GOAP collapses to Spring AI underneath; at that point use Spring AI directly.
- Pre-release maturity (0.3.x snapshots).
- Annotations-driven; Spring-style not Kotlin-style.

> **Choose Embabel if** you want to experiment with GOAP-style dynamic agent planning and don't have your own planner already.
>
> **Avoid Embabel if** you have your own planning model you want to preserve (Pythia's case), or if you'd rather use Spring AI directly without GOAP overhead.

**Practical verdict for Pythia: out.** Not for Spring (Bora is neutral on Spring) — for *design fit*. Pythia has its own planner (LLM-driven) and Embabel's value is its GOAP planner. Without using GOAP, Embabel is just Spring AI plus annotations — and at that point Spring AI is the cleaner choice.

---

### 2.3 LangChain4j

**Ideology.** Hybrid — offers both *low-level imperative* (`ChatLanguageModel`) and *AI Services / annotation-based* (`AiServices`). The AiServices pattern is the recommended path for typed agent operations.

**Maturity.** Started 2023; mature as of 2026. Weekly releases. Large user base; well-documented patterns; many production users.

**Spring coupling.** **None at the core.** `langchain4j-core` is framework-agnostic (~500 KB, minimal dependencies). Spring/Quarkus integrations are *optional* convenience layers. Bare-bones usage is well-supported and idiomatic.

**Kotlin support.** Java-first but pragmatic. AiServices interface-based pattern works fine from Kotlin (interfaces with annotations are normal Kotlin). No Kotlin DSL or coroutines — uses CompletableFuture for streaming, which means bridging from Pythia's coroutine-based executor.

**Pythia-relevant capabilities.**

- **`AiServices` declarative API.** Kotlin interface + annotations → typed function. Each Pythia LLM call site (planner, evaluator, etc.) becomes one interface.
- **`@Tool` annotations** with auto-generated JSON schema from method signatures.
- **Structured output** via `@StructuredPrompt` and POJOs/records — auto-deserialization.
- **PromptTemplate** (Mustache `{{var}}` syntax).
- **Streaming via `StreamingChatLanguageModel` and `TokenStream`** (callback-based). Bridging to Kotlin Flow is straightforward but is bridging.
- **40+ vendor providers** — redundant for us (Gateway handles this); we'd configure LangChain4j to talk only to Gateway.
- **No native MCP client integration** as of May 2026. Custom integration would wrap MCP service calls in `Tool` instances. Real gap.
- **OpenTelemetry, SLF4J, interceptor hooks** for observability.
- **Modular**: provider-per-module dependencies; pull only what you need.

**Pros for Pythia.**
- Most mature option. Largest community. Best documentation. Most production users.
- Framework-agnostic core (Spring optional, fully)
- AiServices pattern fits Pythia's "each call site is a typed function" model elegantly
- Tool-use ergonomics are excellent (auto-schema from method sigs)
- Lightweight footprint
- Vendor-flexible (we don't need this, but it doesn't hurt)

**Cons for Pythia.**
- Java-first idiom; no Kotlin DSL or coroutines support (CompletableFuture for streaming)
- No native MCP client (notable gap; we'd build the wrapping)
- AiServices is annotation-magic — escape hatches exist (low-level API) but cost ergonomics

> **Choose LangChain4j if** you value maturity / community / documentation density, want the AiServices declarative pattern (each LLM call site as a typed function), and accept the Java-comfort idiom from Kotlin and the CompletableFuture-to-Flow bridging.
>
> **Avoid LangChain4j if** you want pure-Kotlin idiom (coroutines, Flow, DSL) or if MCP-native client support is must-have.

---

### 2.4 Spring AI

**Ideology.** AI Services / annotation-based, Spring Boot-native. `ChatClient` (high-level fluent API) wrapping `ChatModel` (provider abstraction).

**Maturity.** Spring AI 1.x by 2026; the de-facto JVM agent framework in Spring shops. Backed by VMware/Broadcom; actively developed; first-party Spring project.

**Spring relationship.** Spring Boot is required — auto-configuration, dependency injection, Spring context are core. Adopting Spring AI means Pythia deploys as Spring Boot rather than Ktor (different from most other platform services, but `llm-gateway` already does this).

**Kotlin support.** Works fine from Kotlin (it's JVM); no Kotlin-specific affordances. Reactor/Flux for streaming (not Kotlin Flow); bridging to coroutines is straightforward via `kotlinx-coroutines-reactor`.

**Pythia-relevant capabilities.**

- **`ChatClient` fluent API** — equivalent in style to LangChain4j AiServices but Spring-native.
- **`@Tool` and `Function` beans** for tool use; tools registered via Spring DI.
- **Structured output** via output converters and schema generation.
- **Streaming via `Flux<ChatResponse>`** (Reactor).
- **Multi-vendor support** — narrower vendor coverage than LangChain4j (~10–15 mature integrations vs LangChain4j's 40+), but covers the major ones (OpenAI, Anthropic, Azure OpenAI, Bedrock, Ollama, Mistral, Google Vertex).
- **MCP support is native** — `spring-ai-mcp` module provides MCP client and server. (Embabel's MCP support is built on this.) **This closes the MCP gap that LangChain4j has.**
- **Observability** — Spring Boot Actuator, Micrometer, OpenTelemetry integration via the Spring stack.

**Pros for Pythia.**
- Mature, actively developed, well-documented
- Native MCP client (parity with Koog; ahead of LangChain4j)
- Idiomatic AiServices / ChatClient pattern fits each Pythia call site as a typed bean
- First-party Spring backing — long-term maintenance signal
- Spring Actuator gives strong production observability defaults

**Cons for Pythia.**
- **Adopting Spring AI means deploying Pythia as Spring Boot** — a different deployment shape from the platform's Kotlin+Ktor services. Not a *blocker* (Bora is neutral on Spring), but worth flagging as an operational consequence.
- No Kotlin DSL or coroutines support (Reactor/Flux for streaming; bridging is fine but adds layers).
- Slightly narrower vendor coverage than LangChain4j (mostly irrelevant since LLM Gateway abstracts vendors).
- Spring DI for tool / bean wiring — extra ceremony compared to direct Kotlin.

> **Choose Spring AI if** you want a mature, MCP-native, AI-Services-style framework with first-party Spring backing, and you're comfortable with Pythia being a Spring Boot service.
>
> **Avoid Spring AI if** you want Pythia to match the platform's Kotlin+Ktor deployment style or prefer Kotlin-idiomatic APIs (coroutines, Flow, DSL).

**Practical verdict for Pythia: viable**. The realistic head-to-head is Spring AI vs LangChain4j as the two AI-Services-pattern frameworks. Spring AI brings native MCP and Spring's observability stack; LangChain4j brings framework-agnosticism (no Spring Boot adoption needed) and a larger vendor ecosystem.

---

### 2.5 Homemade (roll-your-own)

**Ideology.** Imperative. Direct calls to LLM Gateway; thin Kotlin DSL on top.

**Pythia-relevant scope.** What's left to build, given the platform's existing infrastructure:

- **Already provided by platform:** vendor abstraction (LLM Gateway), caching (Gateway), pricing API (Gateway), kotlinx.serialization (Kotlin), Ktor client + Flow streaming, OpenTelemetry (existing `otel-config` library), MCP client patterns (extensible from `mcp-server-base` lib).
- **What we'd write:** typed prompt builder (Kotlin DSL — ~50 lines), tool-use plumbing (request schema generation from Kotlin classes + response parsing — ~100 lines), structured-output helper (call + retry-on-parse-failure with auto-correcting prompt — ~80 lines), streaming Flow wrapper (~30 lines).

Total scope: ~300 lines of code in a focused package, ~500 with tests.

**Pros for Pythia.**
- Total control over prompt construction, error handling, streaming, observability
- Pure Kotlin idiom — coroutines and Flow throughout
- Zero framework lock-in (we can adopt Koog or LangChain4j later if we want, since the seam between Pythia and the LLM Gateway is small)
- Deepest fit with existing platform libraries (otel-config, kotlinx.serialization, Ktor)
- No bridging concerns (CompletableFuture, Spring auto-config, etc.)
- Smallest dependency surface

**Cons for Pythia.**
- 300–500 lines of code to write
- Re-invents structured-output retry logic, tool-use schema generation, streaming idioms — these are well-trodden patterns and we won't get them perfect on first pass
- Maintenance burden: this code is ours forever
- No ecosystem benefits (no community-maintained patterns for new model behaviors, vendor changes, etc.)

> **Choose Homemade if** you want to minimize dependencies and total control, value pure-Kotlin idiom above all else, and have engineering bandwidth for ~500 lines of well-tested boilerplate.
>
> **Avoid Homemade if** you want to leverage well-tested community implementations of structured-output retry and tool-use schema generation, or if you'd rather spend the engineering hours on Pythia's actual investigation logic.

---

## 3. Comparison matrix

| Criterion | Koog | Embabel | LangChain4j | Spring AI | Homemade |
|---|---|---|---|---|---|
| **Ideology** | Graph | GOAP | AI Services + low-level | AI Services | Imperative |
| **Spring Boot required** | No (optional) | Yes | No (optional) | Yes | No |
| **Deployment shape** | Ktor-friendly | Spring Boot | Ktor-friendly | Spring Boot | Ktor-friendly |
| **Kotlin idiom** | Excellent (pure KMP) | Java-shaped | Acceptable | Acceptable | Excellent |
| **Coroutines / Flow** | Native | Spring-Reactor | CompletableFuture | Reactor (bridgeable) | Native |
| **Structured output** | Excellent (StructureFixingParser) | Via Spring AI | Excellent (auto-schema) | Excellent (Spring AI) | We build it |
| **Tool-use ergonomics** | Excellent (typed) | Annotation-driven | Excellent (auto-schema) | Annotation + Spring DI | We build it |
| **Streaming** | Flow | Reactor | TokenStream callback | Reactor | Flow (Ktor) |
| **MCP client** | **Built-in** | Via Spring AI | None (gap) | **Built-in (`spring-ai-mcp`)** | We build it |
| **LLM vendor abstraction** | Yes (redundant for us) | Via Spring AI | 40+ providers (redundant) | ~10–15 vendors (redundant) | LLM Gateway |
| **Observability** | OTel built-in | OTel built-in | OTel + SLF4J | Spring Actuator + Micrometer | otel-config lib |
| **Maturity** | ~6 mo OSS, JetBrains-internal-prod | Pre-release (0.3.x) | 3+ years, mature | Mature in Spring shops | N/A |
| **Modularity (LLM-call layer alone)** | Yes | No (GOAP coupled) | Yes | Partial (need Spring Boot context) | N/A |
| **Footprint** | Modular | Heavy (Spring + GOAP) | ~500 KB core | Spring Boot stack | Tiny |
| **Design fit with Pythia** | Modular subset works | **GOAP conflicts with LLM-planner** | AiServices = each call as typed fn (excellent fit) | AiServices = each call as typed bean (excellent fit) | Custom; we define the shape |

---

## 4. The shape of the decision

One framework is genuinely out, but for *design* reasons, not Spring:

- **Embabel — out for design fit.** Pythia's planner is an LLM producing typed PlanDag; Embabel's value is GOAP-style action planning. Adopting Embabel means either ignoring its centerpiece (defeats the purpose) or running two planning paradigms in parallel. Embabel without GOAP collapses to Spring AI underneath, in which case use Spring AI directly.

That leaves **four real candidates**: **Koog**, **LangChain4j**, **Spring AI**, **Homemade**.

These split along two real axes.

### Axis 1 — Ideology

- **Graph (Koog)** — Pythia's plan DAG is graph-shaped, and we're building the executor ourselves. Koog's graph runtime is what we're *not* buying; its LLM-call subset is what we'd use. Native coroutines/Flow.
- **AI Services / typed functions (LangChain4j AiServices, Spring AI ChatClient)** — Each LLM call site (planner, evaluator, synthesizer, etc.) becomes a typed Kotlin interface or fluent client call. Fits Pythia's pattern of "each LLM call is a typed function" naturally.
- **Imperative (Homemade)** — We write the prompt-construction, structured-output retry, tool-use plumbing. ~300–500 lines.

### Axis 2 — Deployment shape and ecosystem

- **Ktor-shape** (Koog, LangChain4j, Homemade) — Pythia stays Ktor-native; matches most other platform services.
- **Spring Boot shape** (Spring AI) — Pythia becomes Spring Boot; matches `llm-gateway`. Brings Spring Actuator / Micrometer / Spring observability stack out-of-box. Bora is neutral on this; it's a deployment-style note not a veto.

### Pairwise comparisons that matter

**Spring AI vs LangChain4j** — both AI-Services-style frameworks of comparable capability. Differentiators:

| | Spring AI | LangChain4j |
|---|---|---|
| MCP client | Native | Gap (we wrap) |
| Spring DI / Actuator integration | Native | Optional add-on |
| Vendor coverage | ~10–15 | 40+ |
| Framework-agnostic core | No | Yes |
| Pythia deployment shape | Spring Boot | Ktor (or anything) |

If MCP-native matters and you don't mind Spring Boot, Spring AI is ahead. If Ktor-deployment-consistency matters, LangChain4j is ahead.

**Koog vs Spring AI** — Koog is more Kotlin-idiomatic and graph-shaped; Spring AI is more mature and AI-Services-shaped. Both have native MCP. Koog's runtime model (graph-first) is something we're partially ignoring; Spring AI's runtime model (Spring DI for typed beans) is something we'd lean into.

**Homemade vs the others** — Homemade is the only option that gives us pure-Kotlin idiom + tightest platform fit + zero framework lock-in. Cost is ~500 lines of well-tested code we own. The trade-off is "leverage community-tested patterns" vs "own the abstraction surface."

### What's NOT load-bearing

The Pythia design has been deliberately positioned so the framework choice is small:

- **Vendor abstraction:** LLM Gateway handles vendors; we don't depend on the framework's multi-provider story.
- **Flow primitives:** Pythia's DAG executor is custom; we don't depend on the framework's chain/graph runtime.
- **Observability:** Platform's `otel-config` library; framework-agnostic.
- **Caching:** LLM Gateway handles it.

So the framework choice is mostly about *LLM-call ergonomics* — typed prompt construction, structured-output retry, tool-use schema generation, streaming — which is real but not architectural.

---

## 5. Three things to discuss

(i) **Ideology preference at the *code-style* level.** Four real options now, three distinct philosophies:
- **Graph (Koog)** — we use the LLM-call subset; pure Kotlin
- **AI Services (LangChain4j or Spring AI)** — each call site as a typed interface or fluent client
- **Imperative (Homemade)** — we own the abstraction

LangChain4j and Spring AI converge on the AI-Services pattern; the differences between them are operational (deployment shape, MCP, vendor coverage) more than philosophical. So the *philosophical* choice is really three-way: graph (Koog), AI Services (LangChain4j or Spring AI), imperative (Homemade). Which feels right at the *style of code* level?

(ii) **Spring AI vs LangChain4j as the two AI-Services candidates.** If you lean toward the AI-Services pattern, the question becomes: do you prefer Spring AI (native MCP, Spring observability stack, Spring Boot deployment) or LangChain4j (framework-agnostic, larger vendor ecosystem, Ktor-friendly, no MCP)? Spring AI's MCP-native story closes a real gap LangChain4j has; the cost is committing Pythia to Spring Boot deployment.

(iii) **Maturity / community vs Kotlin idiom.** If you lean toward graph-style or imperative, you're choosing between Koog (Kotlin-idiomatic, ~6 months OSS, JetBrains-backed-and-using-internally) and Homemade (we write the code, total control, no ecosystem benefit). The trade-off here is the same as before — leverage community implementations vs own the surface — just now Spring AI is also on the table as the "AI-Services + maturity + Spring backing" option.

I have a leaning that's now genuinely different from before — Spring AI's native MCP support + maturity makes it a real contender that I had wrongly excluded. After we discuss your weighting on these axes, I'll propose a specific pick.

---

## Sources

- [Koog GitHub (JetBrains/koog)](https://github.com/JetBrains/koog)
- [Koog docs overview](https://docs.koog.ai/)
- [Koog graph-based agents](https://docs.koog.ai/agents/graph-based-agents/)
- [Koog structured output](https://docs.koog.ai/structured-output/)
- [Koog MCP](https://docs.koog.ai/model-context-protocol/)
- [JetBrains blog — The Kotlin AI Stack](https://blog.jetbrains.com/kotlin/2025/09/the-kotlin-ai-stack-build-ai-agents-with-koog-code-smarter-with-junie-and-more/)
- [Embabel GitHub](https://github.com/embabel/embabel-agent)
- [Rod Johnson — How and why Embabel plans (GOAP)](https://medium.com/@springrod/ai-for-your-gen-ai-how-and-why-embabel-plans-3930244218f6)
- [Rod Johnson — Embabel vs LangGraph](https://medium.com/@springrod/build-better-agents-in-java-vs-python-embabel-vs-langgraph-f7951a0d855c)
- [GOAP technical primer](https://medium.com/@vedantchaudhari/goal-oriented-action-planning-34035ed40d0b)
- [Embabel Spring coupling notes](https://medium.com/@emedinam/embabel-a-modern-framework-for-spring-boot-modern-enterprise-development-in-kotlin-a7f63f1357ee)
- [InfoQ — Introducing Embabel](https://www.infoq.com/news/2025/06/introducing-embabel-ai-agent/)
- [LangChain4j docs](https://docs.langchain4j.dev)
- [Spring AI reference](https://docs.spring.io/spring-ai/reference/)
