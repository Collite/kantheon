# Agent diff — Claude vs GPT vs Gemini vs MiniMax

A line-by-line comparison of the four drafts the agents produced for hebe's first round of architecture/features/brainstorming. Claude is the reference (because the user picked it as the spine of the synthesis); the other three are compared against it.

The structure:

1. **Overview table** — one-line per-agent summary across the 18-ish dimensions that mattered.
2. **Volume / depth comparison** — how much each agent wrote and where.
3. **Per-agent detailed diff vs Claude**, with what each agent added, what they got right that Claude missed, and what they got wrong.
4. **Where they all converged** — useful signal because four independent attempts agreeing is meaningful.
5. **Where they diverged** — the open architectural decisions.

Source files audited:

- `docs/claude/{hebe-architecture,hebe-features,hebe-brainstorming}.md` + `docs/claude/docs/claws/{stack-notes.md,inspirations.md,iron/*,zero/*}`
- `docs/gpt/{hebe-architecture,hebe-features,hebe-brainstorming}.md` + `docs/gpt/docs/{iron,zero}/*`
- `docs/gemini/{hebe-architecture,hebe-features,brainstorming}.md` + `docs/gemini/docs/claws/{iron,zero}/architecture.md`
- `docs/minimax/{hebe-architecture,hebe-features,hebe-brainstorming}.md` + `docs/minimax/docs/claws/{iron,zero}/README.md`

---

## 1. Overview table

| Dimension | Claude | GPT | Gemini | MiniMax |
|---|---|---|---|---|
| **JVM vs Native recommendation** | JVM (closed, two independent constraints) | JVM-first, portability-conscious | JVM (with KMP path) | **Native** (with JVM fallback) |
| **WASM / plugin sandbox** | Extism on Chicory (WASIp1 + JSON) | Sidecar boundary; "avoid coupling to one in-process WASM engine" | Chicory or GraalVM WASM | Wasmer Kotlin bindings (with process-isolation fallback) |
| **Agent core framework** | Wrap koog behind a hebe facade | "Verify koog surface; wrap it" | Open: bespoke vs framework | Verify koog multiplatform; if not, build own |
| **Kernel ABI surface** | 5 traits (LlmProvider, Channel, Tool, MemoryStore, Observer) | Implicit via module boundaries | Not formally specified | 4 implicit (Provider, Channel, Tool, Memory) |
| **Module layout depth** | 21 modules, named, dep-flow rules | 14 modules, named | 6 modules | 8 KMP modules with commonMain/jvmMain/nativeMain splits |
| **Channel scope for v1** | CLI + Web + Slack + TG + WA + Email (6) — but brainstorming pushes this DOWN to "CLI + Web + ONE chat + email" | Web + CLI + Telegram + Slack + Email (5; WhatsApp v2) | Slack + WA + TG + Email + Webhooks | Web + TG + Slack required; Email/WA/Discord high; Signal/Matrix/Teams medium |
| **Database default** | SQLite v1; Postgres v2 | SQLite v1; Postgres v2 | "Postgres for pgvector OR SQLite + separate vector file" — open | SQLite local; Postgres server (dual-backend) |
| **Memory tiers framing** | Workspace + DB + RRF; mentions "scheduled internal management" | **5 explicit tiers (Live/Transcript/Curated/Derived/Retrieval)** + named maintenance jobs | 3-tier (Short/Long/Workspace) | 4-tier (Cache/Session/Workspace/Long-term) |
| **Approval / autonomy model** | AutonomyLevel + risk + receipts | AutonomyLevel + tool-specific approval hints | Boundary control + user-approval flows + inline buttons | DM pairing + autonomy levels + safety layer |
| **Tool receipts** | Ed25519 chained, on disk | "Store structured tool-call records; tamper-evident receipts later" | Not called out | "Tool execution audit log" |
| **MCP** | First-class server + client, day one | First-class transport, M1 | "MCP Servers" listed but not detailed | High priority client; server not explicit |
| **Single mutation funnel rule** | Yes, explicit, lint-enforced | Implicit | Not called out | Yes (H4: "Everything Goes Through Tools") |
| **SOPs in v1** | Open question, biased to defer | Implicit "background jobs" handles routines | "Event-Driven SOPs" listed without phase | Routines required; SOPs not explicit |
| **Identity files (IDENTITY.md, SOUL.md, etc.)** | Yes, IronClaw-style; v1 ships single IDENTITY.md | Mentioned ("USER.md, runbooks") | Not detailed | Yes, all five (IDENTITY, SOUL, AGENTS, USER) |
| **Compaction threshold** | 60% default, configurable | Not specified | Not specified | "Preemptive history pruning" (H8) |
| **Cron / SOP split** | Yes, routines v1 vs SOPs v2 | Implicit | "Cron-ish + Event-driven SOPs" both listed | Routines + heartbeat + cron separately |
| **Skills selection** | Deterministic prefilter (IronClaw); LLM-free | Markdown-based with metadata | "Skill / prompt system" generic | Deterministic prefilter (ZeroClaw) |
| **Skillforge / self-evolution** | Defer indefinitely | Not in scope | **"Hermes-inspired: agent writes its own .kts tools"** | Defer; medium priority |
| **Multi-tenant / `TenantScope`** | `TenantScope` seam from day one | Single-user first explicitly | Not detailed | Implicit single-user |
| **Web console framework** | HTMX or small Svelte SPA | "Browser UI" agnostic | "Web Dashboard" generic | SSE + WebSocket via Ktor |
| **Loop detector** | Yes, explicit (IronClaw) | Not specified | Not specified | Yes (H7) |
| **OS/process sandbox for shell** | v2 (firejail/bwrap/Docker) | **Central — "dedicated sandbox boundary"** | Open ("WASM Sandbox" generic) | Docker / process isolation listed |
| **Notable original idea** | Single-mutation-funnel rule, lint-enforced | Five-tier memory framing; "MCP-as-sidecar" reframing avant la lettre | **Hermes self-evolution; JSR-223 Kotlin scripting for trusted scripts** | "Everything goes through tools"; preemptive history pruning; multi-agent router |
| **Document length / depth** | Highest — 5 docs incl. inspirations + stack-notes | Second — 3 docs + claws | Lowest — 3 docs, very brief | High — 3 docs, comprehensive features |
| **Opinionated pushbacks** | Many; explicit "where I want pushback most" | A few, structured | A few, focused on tooling | Many; "P1-P4" pushbacks |

---

## 2. Volume / depth comparison

Approximate line counts of the three core docs (architecture / features / brainstorming) per agent:

| Agent | architecture | features | brainstorming | + claws docs | Total |
|---|---|---|---|---|---|
| Claude | 558 | 296 | 329 | 7 sub-docs (~2000+ lines) | **~3200+ lines** |
| MiniMax | 514 | 552 | 210 | 2 sub-docs | ~1400 |
| GPT | 264 | 129 | 145 | 7 sub-docs | ~900 |
| Gemini | 52 | 39 | 45 | 2 sub-docs (brief) | ~250 |

**Reading the table:** Claude wrote 12× the volume of Gemini, and 2.3× the volume of GPT. MiniMax's features list is the single longest per-doc artefact (552 lines) but its architecture is shorter than Claude's. Gemini is unusable as-is for synthesis — it's an outline more than a draft — but contains two original ideas worth folding in (see §3.3 below).

---

## 3. Per-agent detailed diff vs Claude

### 3.1 GPT vs Claude

GPT and Claude landed in the same neighbourhood; GPT's draft is more cautious, Claude's more opinionated. Where GPT diverges, the divergence is usually a useful framing improvement rather than a substantive disagreement.

#### What GPT added that's worth folding in

| GPT idea | Where it lands in the synthesis |
|---|---|
| **5-tier memory framing** (Tier 0 Live / Tier 1 Transcript / Tier 2 Curated / Tier 3 Derived / Tier 4 Retrieval) with explicit names | Adopted into `hebe-architecture.md` §13 as a documentation framing (Claude's draft had the same content but didn't name the tiers). |
| **Named scheduled-maintenance jobs** (transcript summarisation, fact extraction, daily digest, stale cleanup, embedding refresh, failed-job detection) | Adopted into architecture §13 and features §9. |
| **"Sidecar boundary" framing for the sandbox** — "the runtime is JVM-first; avoid coupling the first release to a single in-process WASM engine" | Prescient. The user's revised brief (drop WASM) lands GPT's framing exactly: MCP **is** the sidecar boundary now. |
| **Module-naming convention** (`hebe-domain`, `hebe-runtime`, `hebe-providers-api`, etc.) | Named slightly differently from Claude's `modules/api`, `core/`, `tools/`, `providers/`, but the cuts are the same. We've kept Claude's names; GPT's naming is more verbose. |
| **"Single-user first, not multi-tenant first"** — bold and explicit | Folded into brainstorming §3.2 as the lean. |
| **"Optional sidecar or worker boundary for WASM and risky execution"** | Adopted as the v2 plan for shell/browser/kubectl native exec. |
| **"Recommended first-wave channels: Web, CLI, Telegram, Slack, Email"** with WhatsApp explicitly slipped | Adopted in architecture and features (was already Claude's bias in brainstorming, but GPT was more concrete). |
| **Mermaid runtime diagram with explicit Surfaces / Core / Capabilities lanes** | Useful; could be inserted into the synthesised architecture if a diagram is wanted (Claude's draft uses ASCII boxes). |
| **"Backup and export" and "Health checks and diagnostics"** as named features | Added to features §14. |

#### What GPT got right that Claude missed

- **Naming the tiers explicitly.** Claude's memory architecture is technically the same but you have to read carefully to find the tiers. GPT's table makes the intent legible to a reader.
- **MCP-as-tool-transport rather than MCP-as-product.** GPT's framing — "Treat MCP as another tool transport, not a separate product" — is cleaner than Claude's "first-class server + client" phrasing.
- **"Preserve event fidelity. Full transcript deletion or silent truncation should not be part of normal operation."** Claude has the same rule but as one bullet; GPT made it a section heading.
- **"Architecture should explicitly resist these scope traps in the first phase"** with a concrete list. Claude's brainstorming has similar content but GPT's list is sharper.
- **Conscious about avoiding "perfect parity with every idea in IronClaw or ZeroClaw"** — explicit non-goal.

#### What GPT got wrong (or weaker than Claude)

- **No kernel ABI specification.** Claude proposes 5 traits with code; GPT names "providers-api / channels-api / tools-api" modules but doesn't write the trait shapes. This matters because a kernel ABI is the *first* thing to lock in.
- **No mutation-funnel rule.** Claude picks `ToolDispatcher.dispatch` as the lint-enforced single mutation path; GPT doesn't address this.
- **No tool-receipts model.** Claude specifies Ed25519 hash-chained on-disk receipts; GPT says "tamper-evident receipts later."
- **No skill-selector specifics.** Claude ports IronClaw's deterministic prefilter (with scoring formula); GPT says "Markdown-based skill definitions plus tool and requirement metadata" — leaves the selection algorithm open.
- **No compaction threshold.** Claude picks 60%; GPT doesn't specify.
- **No loop detector.** Claude has it explicitly; GPT doesn't mention it.
- **`MemoryStore` as a trait isn't called out as a kernel-ABI extension point** — GPT discusses `hebe-memory` as a module but not as a pluggable interface in the Hermes sense.
- **Doesn't engage with WASM constraints.** GPT punts ("avoid coupling to a single in-process WASM engine") without working through whether WASM was tractable on JVM in 2026 — Claude's `stack-notes.md` does the actual research.

#### Verdict on GPT

GPT is a strong second to Claude. Its framings are cleaner, but it's missing concrete commitments (kernel traits, dispatcher rule, scoring formula, receipts format, compaction threshold). The synthesis adopts GPT's framings on top of Claude's commitments.

### 3.2 Gemini vs Claude

Gemini's docs are sketches, not drafts. Total volume is ~250 lines vs Claude's ~3200. They serve as a "what stuck out to a quick reader" signal, not a comparable architecture.

#### What Gemini added that's worth folding in

| Gemini idea | Where it lands |
|---|---|
| **JSR-223 Kotlin scripting for trusted scripts vs WASM for untrusted** | Conceptually relevant under the new direction (no WASM): we could ship a "trusted Kotlin script tool" via JSR-223 (`KotlinScriptEngine`) for rapid in-tree scripting. Listed in `hebe-features.md` as a possible follow-up, not v1. |
| **Hermes-style self-evolution: agent writes its own `.kts` tools when it encounters errors** | Captured as a [L] stretch idea in features §4 and stretch-ideas §5. |
| **JGit (Eclipse) for in-process Git operations** + **Fabric8 for K8s** | Adopted as the v1 implementation path for the `git` and `kubectl` tools (architecture §11 and features §5). |
| **Inline `[Approve]` / `[Deny]` buttons on Telegram/Slack for high-risk approval flows** | Added to features §7 as v2. |
| **Ktor + Exposed/Ktorm for persistence** | Adopted partially — sticking with raw JDBC + Flyway per Claude's stack-notes, but Exposed remains an option if we end up with rich queries. |

#### What Gemini got right that Claude missed

- **The JSR-223 angle is genuinely interesting.** It opens a "trusted local Kotlin scripting" path that doesn't need WASM and doesn't need a JVM-plugin JAR — useful for power users who want to write a quick `.kts` tool in their workspace. None of the other agents raised this.
- **Concrete agent diagram with explicit lanes** (Interfaces / Orchestrator / Tool execution / Provider / Memory / Security). Claude's diagram is the same content but Gemini's is the right shape for a one-pager.
- **Naming "Boundary Control"** as the layer between Agent Loop and Tool Execution is a nicer word than Claude's "ToolDispatcher" — same concept.

#### What Gemini got wrong (or weaker than Claude)

- **Recommends Postgres + pgvector as if it's a foregone conclusion.** Misses SQLite + sqlite-vec entirely.
- **Lists 6 modules (`hebe-core`, `hebe-providers`, `hebe-channels`, `hebe-memory`, `hebe-tools`, `hebe-gateway`).** Claude's 21 modules and GPT's 14 are more realistic for a project this size. Gemini's six are too coarse — `hebe-tools` would have to contain the dispatcher, the WASM runtime, the MCP client, and every built-in tool, which is a maintenance disaster.
- **No kernel ABI specification.**
- **No memory tiering.**
- **No tool receipts.**
- **No approval flow specifics.**
- **No SOPs vs routines split.**
- **Open question on koog: "Should we build our own lightweight agent loop or heavily rely on a framework? For maximum flexibility, a bespoke loop around an LLM client might be better than a heavy abstraction."** — this is a *dangerous* open question; Claude's draft argues forcefully for "wrap, don't replace" and that's the right call.
- **Brief, almost notional treatment of memory.** Memory is the load-bearing concern in `req.md` and Gemini gives it 3 bullets.

#### Verdict on Gemini

Gemini is too brief to be a candidate spine, but it contributed two original ideas (JSR-223 trusted scripting, agent self-evolution writing `.kts`) and one nice diagram framing. Folded the ideas in as deferred features; otherwise discarded.

### 3.3 MiniMax vs Claude

MiniMax wrote a comprehensive features list (longer than Claude's), but its architecture recommends Kotlin Native — which the user has now closed in the opposite direction. MiniMax has a few load-bearing original ideas (especially around tool framing and memory mechanics) that are worth borrowing.

#### What MiniMax added that's worth folding in

| MiniMax idea | Where it lands |
|---|---|
| **"Everything Goes Through Tools" (H4)** — explicit pushback that ALL mutations go through `ToolDispatcher` | Same idea as IronClaw's load-bearing rule and Claude's §2.4. MiniMax independently arrived at it — strong signal we should commit. |
| **"Preemptive History Pruning" (H8)** — trim history BEFORE it overflows, not after | Adopted into architecture §13 ("Preemptive history pruning") and features §3. |
| **"Loop Detector" (H7)** — duplicate-tool-call fingerprinting, ZeroClaw-style | Same as Claude's loop-detector spec; reinforces that we should ship it v1. |
| **"Time decay on non-Core memories" (H6)** | Listed as v2 feature in `hebe-features.md` §9. |
| **"Response cache with deterministic key (temperature=0.0 required)" (H5)** | Listed as v1 feature. |
| **Tool execution pipeline diagram** with SafetyLayer.validate → ToolExecutor.execute → SafetyLayer.sanitizeOutput | Same content as Claude's dispatcher pipeline but broken out as a diagram. |
| **`tool_search` builtin tool** for deferred MCP tool discovery | Added to features §5 as v2. |
| **Catchup execution for missed cron runs** | Added to features §3 as v2. |
| **PID file + auto-start + daemon mode + tunneling (Cloudflare/ngrok/Tailscale/custom)** | Added to features §14. |
| **Agent router with rules** for OpenClaw-style multi-agent routing | Added to features as [L]; consistent with Claude's "sub-agents are tools" position (§2.3 of brainstorming). |
| **Encryption at rest with AES-256-GCM, master key in OS keychain** | Same as Claude's; mutual reinforcement. |
| **Heartbeat with `HEARTBEAT.md` + "silence on OK"** behavior | Added to features §3. |
| **"OAuth flow for MCP servers"** | Added to features §8 as v2. |

#### What MiniMax got right that Claude missed

- **Time decay on memories.** Claude has it as v2 too, but MiniMax frames the *mechanism* better (older items score lower, no manual pruning).
- **Tool versioning + rollback.** Claude lists it under "tool dispatcher logging" but doesn't surface it as a feature; MiniMax names it.
- **Catchup execution.** Cron jobs that were due during a hebe downtime: do they run on startup? MiniMax raises this; Claude doesn't.
- **`tool_search` for deferred MCP loading.** When MCP servers advertise hundreds of tools, prompt explosion is real. MiniMax's deferred-discovery tool is a useful complement to the `Always`/`Dynamic` filter in Claude's draft.
- **PID file / daemon mode / auto-start** are operational details that Claude's "service install" subcommand glosses over.
- **Tunneling (Cloudflare/ngrok/Tailscale/custom)** is a real operator concern that Claude only briefly touches.

#### What MiniMax got wrong (or weaker than Claude)

- **Recommends Kotlin Native.** The user closed this. (MiniMax's "with JVM fallback" hedging is at least honest, but the architecture is built around `commonMain/nativeMain/jvmMain` splits that we don't need.)
- **Picks Wasmer Kotlin bindings.** WASM is gone under the new direction.
- **Module structure is KMP-flavoured** (`commonMain` etc.) — overcomplicated for our actual JVM-only target.
- **No kernel ABI surface specified as code.** Mentions `Channel`, `Tool`, `Skill`, `Provider`, `Memory` interfaces but doesn't write them.
- **No mutation funnel rule** (it's mentioned as a hint H4 but not adopted as architecture).
- **Open question "Have you used koog before? Is it multiplatform-ready?"** suggests MiniMax didn't research koog. Claude's `stack-notes.md` did the research and locked in the call.
- **Memory tier model is wrong-shaped.** MiniMax's tiers are Cache (in-memory) / Session (SQLite) / Workspace (filesystem) / Long-term (vector DB). This collapses Tier 1 (Transcript) into Session and Tier 4 (Retrieval) into "vector DB," which is misleading — retrieval projections are derived *over* Tier 1 + Tier 2, not a separate "long-term" store.
- **Compaction threshold not specified.**
- **No skill scoring formula.**
- **Multi-agent routing in v1.** MiniMax pushes it as a high-priority feature; Claude correctly defers it as research-quality.
- **Multi-channel feature list is over-broad** — Discord, WhatsApp, Email, Signal, Matrix, Teams, iMessage, IRC, Mattermost, Nextcloud Talk, Nostr all listed in the priority matrix. This is feature-list-as-marketing, not a v1 plan.

#### Verdict on MiniMax

MiniMax's features list is the most exhaustive of the four, and contains real design-pattern improvements (preemptive pruning, catchup execution, time decay, tool_search). Its architecture is undermined by the Native recommendation and KMP module shape, both now closed. Folded in: the design-pattern improvements above. Discarded: the architecture frame.

---

## 4. Where they all converged (high-signal alignment)

Items where all four agents independently agreed. These are the lowest-risk, highest-confidence design choices.

| Convergent decision | Claude | GPT | Gemini | MiniMax |
|---|:-:|:-:|:-:|:-:|
| **Wrap koog rather than build from scratch** | ✓ | ✓ | open | ✓ |
| **SQLite as default DB** | ✓ | ✓ | ambivalent | ✓ |
| **Markdown workspace (memory-as-FS)** | ✓ | ✓ | implicit | ✓ |
| **Hybrid search (FTS + vector)** | ✓ | ✓ | ✓ | ✓ |
| **MCP as a first-class tool transport** | ✓ | ✓ | listed | ✓ |
| **Channel-trait abstraction (deliver / reply / health)** | ✓ | ✓ | ✓ | ✓ |
| **Web console as M1 deliverable** | ✓ | ✓ | ✓ | ✓ |
| **Approval / autonomy framework** | ✓ | ✓ | ✓ | ✓ |
| **Secrets at rest + injection at boundary (no exposure to tools)** | ✓ | ✓ | ✓ | ✓ |
| **Tool execution audit log** | ✓ | ✓ | implicit | ✓ |
| **Skills as Markdown bundles** | ✓ | ✓ | ✓ | ✓ |
| **Explicit "single-user / small-team first, defer multi-tenant"** | ✓ | ✓ | implicit | implicit |
| **Cron-like scheduling for routines** | ✓ | ✓ | ✓ | ✓ |
| **"Web search needs an external provider" (not crawling)** | ✓ | ✓ | listed | ✓ |
| **Fat-JAR distribution + Docker later** | ✓ | implicit | implicit | ✓ |
| **Onboarding wizard as M1** | ✓ | ✓ | not listed | ✓ |

When four independent agents converge, the choice is almost certainly correct. The synthesis adopts all of these without further debate.

---

## 5. Where they diverged (the real architectural decisions)

Items where the four agents disagreed substantively. These are where the synthesis has to *choose*, and the user's revised brief has resolved most of them.

| Diverging decision | Claude | GPT | Gemini | MiniMax | **User's resolved direction** |
|---|---|---|---|---|---|
| Native vs JVM | JVM | JVM-first | JVM | **Native** | **JVM** |
| WASM in-process | Extism on Chicory | Sidecar boundary | Chicory or GraalVM | Wasmer Kotlin | **Drop WASM. JVM plugins + MCP.** |
| Plugin sandbox | WASM-isolated, signed manifest | Sidecar process | WASM | WASM with process fallback | **JVM URLClassLoader + manifest, MCP for untrusted** |
| Channels for v1 | Pushed back to "CLI + Web + 1 chat + email" | Web + CLI + TG + Slack + Email | Slack + WA + TG + Email + Webhooks | Web + TG + Slack required; rest tiered | **CLI + Web + 1 chat + Email; WhatsApp v1.1** |
| Multi-tenant from day one | `TenantScope` seam, single-user default | Single-user first explicitly | Not detailed | Multi-user implicit | Open (brainstorming §3.6) |
| SOPs in v1 | Defer to v2 | Implicit "background jobs" | Listed as feature | Routines required; SOPs not explicit | Open (brainstorming §3.1; lean: defer) |
| Skill self-evolution / Skillforge | Defer indefinitely | Not in scope | **Pursue (Hermes-inspired)** | Defer | Defer |
| Multi-agent routing | Sub-agents as tools | Subagents v2 | Not detailed | Multi-agent router | Sub-agents as tools (§2.3) |
| Subprocess sandbox for shell | v2 | **Central** | Implicit | Listed | v2 (per §6.5 of brainstorming) |
| OS keychain integration | Yes, with passphrase fallback | Implicit | Not specified | Listed | Yes, with passphrase fallback |
| `koog` reliance level | Wrap behind facade | Wrap; verify surface | **Open: bespoke vs framework** | Verify multiplatform; if not, bespoke | Wrap behind facade |
| Web console framework | HTMX or Svelte | Agnostic | Not specified | Ktor + SSE+WS | HTMX or small Svelte (per Claude) |
| Compaction threshold | 60% default | Not specified | Not specified | "Preemptive" (no number) | 60% default + preemptive pruning |
| Identity files inventory | Single IDENTITY.md v1; rest deferred | Mentioned generically | Not detailed | All 5 in v1 | Single IDENTITY.md v1 |
| MCP server (expose hebe tools) | First-class day one | M1 | Not explicit | Not explicit | First-class day one |
| Tool receipts mechanism | Ed25519 chained, on disk | "Tamper-evident later" | Not called out | "Audit log" | Ed25519 chained, on disk |

---

## 6. Honourable mentions: original ideas the synthesis took from each

A short list of ideas where one specific agent contributed the most useful framing or pattern, even if the agent's overall draft was weaker.

- **Claude:** the kernel-ABI-as-five-traits framing; the single-mutation-funnel rule lint-enforced day one; the `HandleOutcome::Pending` distinction; the trust-ceiling skill-attenuator; the inspirations and stack-notes research docs.
- **GPT:** the five-tier memory naming; "MCP as the sidecar" (avant la lettre); the explicit "scope traps" non-goals list.
- **Gemini:** JSR-223 Kotlin scripting for trusted scripts; agent-writes-its-own-`.kts` self-evolution stretch idea; inline approve/deny buttons on Telegram/Slack.
- **MiniMax:** "everything goes through tools" as an independent reinforcement; preemptive history pruning; time decay on memories; `tool_search` for deferred MCP loading; catchup execution; tunneling support; `PID` file / auto-start operational details.

---

## 7. One-paragraph recommendation

Use Claude as the spine. Adopt GPT's memory-tier naming and "MCP-as-sidecar" framing. Take MiniMax's preemptive-pruning, time-decay, tool_search, catchup, and tunneling features. Take Gemini's JSR-223 idea as a deferred opt-in. Discard MiniMax's KMP module structure and Native recommendation. Discard Gemini's coarse 6-module layout. Discard GPT's "no kernel ABI specification" reticence — write the five traits.

Where the four agents converged (§4), commit. Where they diverged (§5) and the user has decided, follow the user. Where they diverged and the user hasn't decided (multi-tenant, SOPs, BYO-key vs default), surface the question in `hebe-brainstorming.md` and argue for the lean position.
