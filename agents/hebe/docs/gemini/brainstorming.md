# Koklyp Brainstorming & Specifications

This document captures assumptions, questions, hints, and architectural decisions to kick off our brainstorming session.

## 1. Native vs. JVM: The Architectural Question

**Recommendation:** **Stay on JVM (for now, with a path to KMP)**

*Why not Kotlin Native?*
- **Ecosystem:** While Kotlin Native is great for CLI tools, the ecosystem for web servers (Ktor Native is still maturing), mature Database ORMs/Drivers (Exposed is JVM only), and complex WASM isolation runtimes is sparse.
- **WASM Isolation:** Integrating a WASM runtime like Wasmtime or WasmEdge in Kotlin Native requires complex C-Interop.
- **Concurrency:** Kotlin Native's memory model has improved, but building a heavily concurrent, multi-channel agent loop is still vastly easier and more performant on the JVM.

*Why JVM?*
- **Libraries:** Ktor (server/client), Exposed (DB), `kotlinx.serialization` all work flawlessly. We can use robust JVM libraries for Git (JGit) and Kubernetes (Fabric8).
- **Sandboxing:** We can use **Chicory** (a pure-Java WASM runtime) or GraalVM's WASM engine to execute untrusted tools safely.
- **Flexibility:** If we architect cleanly (separating pure logic from IO), we can migrate core modules to Kotlin Multiplatform (KMP) later if we truly need a Native standalone binary.

## 2. Assumptions
- We are building a personal, self-hosted agent (not a multi-tenant SaaS... yet).
- We prioritize safety (WASM sandbox, user approval flows) over pure autonomy.
- The user will interact with the agent mostly via external channels (Slack/Telegram) rather than exclusively a web dashboard, meaning rich text/markdown parsing and channel specific formatting is important.
- The database is likely a single Postgres instance (for pgvector) or local SQLite + a separate vector DB file.

## 3. Pushbacks & Challenges
- **Memory Management Multi-tier:** The requirement mentions "scheduled internal management". Doing RAG (Retrieval-Augmented Generation) well is notoriously difficult. We should rely on standard embedding models, but we need a clear strategy for "forgetting" or summarizing old context so the prompt window doesn't overflow.
- **WASM in Kotlin:** Tool building in WASM means users have to write tools in Rust/Go/AssemblyScript and compile to WASM. If we want users to write tools in *Kotlin*, we need to ensure Kotlin -> WASM compilation works well for simple standalone scripts, which is currently focused on Kotlin/Wasm (for browser). We might need to support JSR-223 (Kotlin Scripting) for internal, trusted scripts, and WASM only for external, untrusted ones.

## 4. Hermes Inspiration: Self-Evolution
Hermes features a "learning loop". What if Koklyp could:
1. Encounter an error when trying to parse a file or run a command.
2. Search the web/docs for a solution.
3. Automatically write a new Kotlin Script (`.kts`) tool to handle this specific edge case.
4. Save the tool to its workspace and use it in the future.
This would make it truly autonomous and "self-expanding".

## 5. Open Questions for Brainstorming
1. **Target Deployment:** Is the expected primary deployment a Docker container on a VPS, or a local app running on a user's Macbook? This changes how we approach filesystem access and sandbox constraints.
2. **Tooling Language:** Do we expect users to write custom skills in Kotlin, Rust, Python, or just connect MCP servers? 
3. **Database:** Should we mandate PostgreSQL to get native `pgvector`, or default to SQLite (which requires extensions for vectors that can be tricky to bundle)?
4. **Approval Flow:** How do we want to handle "Supervised Mode"? Should the agent send a Telegram message with inline buttons `[Approve]` / `[Deny]` for high-risk actions (like `kubectl delete pod`)?
5. **Agent Framework:** You mentioned `koog`. Should we build our own lightweight agent loop (to have absolute control over routing and tool calls), or heavily rely on a framework? For maximum flexibility, a bespoke loop around an LLM client might be better than a heavy abstraction.

Let's discuss these points to refine the specification!
