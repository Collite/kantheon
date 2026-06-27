# Koklyp Brainstorming

Internal document for architectural and feature decisions. We will brainstorm together to arrive at a final specification.

---

## Open Questions

### Q1: Native vs JVM

I've recommended Kotlin Native with JVM fallback. But I need your input:

**Arguments for Native:**
- Single binary deployment, no JVM dependency
- Lower memory footprint
- Faster startup
- Modern Kotlin 2.3 with proper coroutines support

**Arguments for JVM:**
- Mature library ecosystem (JDBC, JMX, etc.)
- Better tooling (debugging, profiling)
- Ktor has full feature set on JVM
- More common in enterprise environments

**Concerns about Native:**
- WASM sandbox via Wasmer (C bindings) — untested in Kotlin Native
- SQLite driver maturity
- HTTP client behavior differences

**My tentative recommendation:** Start with JVM (easier to validate architecture), then port to Native once core is stable. But I'd like your pushback.

### Q2: koog Framework

The req mentions "koog for agents." I haven't deeply analyzed koog — it might be:
- A specific library we should use
- A reference architecture to study
- Just a naming convention

**Questions:**
- Have you used koog before?
- Is it multiplatform-ready?
- Should we implement our own agent loop (using IronClaw/ZeroClaw as reference) or integrate with koog?

### Q3: Database Choice

I've proposed SQLite for local, PostgreSQL for server deployments.

**Alternative:** Use a single database (PostgreSQL) everywhere, even locally. This simplifies the model but adds a dependency.

**Your preference?** Single DB or dual-backend like IronClaw?

### Q4: WASM Isolation

IronClaw uses wasmtime directly. For Kotlin Native:
- Option A: Wasmer Kotlin bindings (C library)
- Option B: Process isolation + JSON-RPC
- Option C: Skip WASM, use only MCP + process isolation

**My concern:** Wasmer on Native is the riskiest part of the Native path. Is WASM isolation a hard requirement for v1, or can we defer it?

---

## Assumptions (Please Challenge)

### A1: Skill System Design

I assume we follow ZeroClaw's model where skills are SKILL.md files that:
- Extend the agent's system prompt
- Are selected deterministically (not via LLM)
- Have trust levels (user-created vs installed)

**Alternative:** Use a more dynamic skill system where the agent can create/modify skills autonomously (Hermes-style).

### A2: Memory Model

I assume hybrid search (FTS + vector) is required. But for v1, we could start with simple keyword search and add vector later.

**Question:** Is hybrid search required at MVP, or can it be deferred?

### A3: Multi-Agent

I assume we'll eventually need multi-agent support (OpenClaw-style routing to different agent instances).

**Question:** Is this a v1 requirement or future?

### A4: Extension System

I assume WASM-based extensions are the right model (following IronClaw).

**Question:** Is MCP sufficient for extension needs, or is native WASM required?

---

## Hints & Ideas

### H1: Borrow from OpenClaw's Channel Model

OpenClaw has 30+ channel implementations. Their channel trait is simple:
- `send(message)`
- `listen(handler)` (callback-based)

This is cleaner than IronClaw's async channel approach and maps well to Kotlin's coroutines.

### H2: Hermes's Self-Improving Skills

Hermes creates skills from experience. After completing a complex task, it:
1. Analyzes the interaction
2. Generates SKILL.md
3. Registers the skill

This could be powerful but adds complexity. Maybe v2 feature?

### H3: ZeroClaw's Tool Filtering

ZeroClaw's `filter_tool_specs_for_turn()` is elegant:
- Built-in tools always pass through
- MCP tools filtered by `groups` (always/dynamic)
- Dynamic mode checks user message for keywords

This gives users fine-grained control without LLM overhead.

### H4: IronClaw's "Everything Goes Through Tools"

IronClaw's rule: ALL mutations go through `ToolDispatcher`. This means:
- No direct database writes from handlers
- No direct workspace access from handlers
- Uniform audit trail

This is a great pattern for security and debugging.

### H5: Response Cache

ZeroClaw's response cache uses a deterministic key (temperature=0.0 required). This is a simple but effective optimization.

### H6: Memory Time Decay

ZeroClaw's time decay on non-Core memories is clever. It keeps memory relevant without manual pruning.

### H7: Loop Detection

ZeroClaw's `LoopDetector` prevents runaway tool calls. Simple but important for reliability.

### H8: Preemptive History Pruning

ZeroClaw trims history BEFORE it overflows context window, not after. This avoids context overflow errors.

---

## Pushbacks & Challenges

### P1: Kotlin Native is Unproven for This Use Case

Most successful agent frameworks (IronClaw, ZeroClaw, OpenClaw, Hermes) are Rust or Python. Kotlin Native for an agent is unusual.

**Counter:** Kotlin Native has been used in production (AWS Lambda, etc.). The key is avoiding platform-specific dependencies.

### P2: Complexity of Multiplatform

Sharing code between Native and JVM adds complexity:
- Different concurrency models
- Different I/O primitives
- Different library availability

**Counter:** Start with JVM, add Native later when core is stable.

### P3: WASM Support is Immature

Wasmer Kotlin bindings exist but aren't widely used. Could be maintenance burden.

**Counter:** Use process isolation instead of WASM for v1. WASM is a nice-to-have.

### P4: koog is Unfamiliar

"koog for agents" — I need to understand what koog actually provides. If it's just a naming convention, we can ignore it.

**Action:** I should investigate koog more before specifying it as a dependency.

---

## Decision Log

| Decision | Outcome | Rationale |
|----------|---------|-----------|
| Language | Kotlin (Native target, JVM fallback) | Type safety, coroutines, multiplatform |
| Web framework | Ktor | Native + JVM support, full-featured |
| Serialization | kotlinx.serialization | Native + JVM, JSON/UTF-8 |
| Database | SQLite (local) + PostgreSQL (server) | Dual-backend like IronClaw |
| Tool isolation | WASM (Wasmer) + Process isolation fallback | IronClaw-style sandboxing |
| Skill system | SKILL.md files, deterministic selection | ZeroClaw-inspired |
| Memory | Hybrid FTS + vector with RRF | IronClaw/ZeroClaw proven |
| Channel model | Trait-based, dependency injection | OpenClaw-inspired |

---

## Topics for Discussion

1. **MVP scope** — What's the minimum viable product? What can be deferred?
2. **Architecture** — Any concerns with the proposed module structure?
3. **Native vs JVM** — Your preference?
4. **WASM** — Is it required at v1?
5. **Database** — Single or dual-backend?
6. **koog** — What is it? Should we use it?
7. **Skills** — Static vs self-improving?
8. **Memory** — Hybrid search at MVP?
9. **Multi-agent** — v1 or future?
10. **Extensions** — WASM vs MCP?

---

*This document will be updated based on our brainstorming session.*