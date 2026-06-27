# Memory

ZeroClaw has the most elaborate memory subsystem of the two claws. Worth studying for ideas, but the surface area is large enough that a Kotlin port should pick a subset rather than mirror it.

## Layout

`crates/zeroclaw-memory/src/`:

| File | Role |
|---|---|
| `traits.rs` | The `Memory` trait — kernel-side ABI |
| `backend.rs` | Backend dispatch / multiplex |
| `sqlite.rs` | Default backend; FTS5 + vector |
| `postgres.rs` | Postgres + pgvector |
| `qdrant.rs` | Qdrant vector DB backend |
| `none.rs` | No-op backend (testing / minimal builds) |
| `embeddings.rs` | Embedding-provider abstraction |
| `chunker.rs` | Document → chunk splitting |
| `vector.rs` | Vector index plumbing |
| `retrieval.rs` | Hybrid retrieval (FTS + vector) |
| `decay.rs` | Older items lose importance over time |
| `importance.rs` | Importance scoring on entries |
| `consolidation.rs` | Merges related fragments into stable memories |
| `conflict.rs` | Detects/resolves contradictions |
| `hygiene.rs` | Sanitization, prompt-injection scrubbing |
| `policy.rs` | Per-namespace policies (e.g. encryption, TTL) |
| `audit.rs` | Memory mutation audit log |
| `namespaced.rs` | Wraps a backend with a namespace prefix |
| `markdown.rs` | Markdown-flavored memory (per-document) |
| `lucid.rs` | (purpose not entirely clear from name; possibly "lucid dreams" — async background reflection) |
| `knowledge_graph.rs` | Entity/relation extraction |
| `knowledge_graph_pg.rs` | KG persistence on Postgres |
| `response_cache.rs` | Cache for repeated identical questions |
| `snapshot.rs` | Point-in-time memory snapshots |

## The `Memory` trait

The exact shape lives in `traits.rs`. Functionally:

```rust
trait Memory: Send + Sync {
    async fn append(&self, conversation_id: &str, msg: ConversationMessage) -> Result<()>;
    async fn load_context(&self, conversation_id: &str, limit: usize) -> Result<Vec<...>>;
    async fn search(&self, query: &str, k: usize) -> Result<Vec<MemoryHit>>;
    async fn category(&self, cat: MemoryCategory, ...) -> Result<...>;
    // + decay/consolidation/snapshot hooks
}

enum MemoryCategory { Conversation, Fact, Preference, Skill, Document, ... }
```

The agent (`agent/agent.rs:43`) holds an `Arc<dyn Memory>`. Conversation history is loaded via `MemoryLoader` (`agent/memory_loader.rs`), then passed into the chat request.

## Persistence options

### SQLite (default)

`sqlite.rs` — single file at `~/.zeroclaw/memory.db`. FTS5 for keyword search. Vector columns via SQLite's vector extension or `libsql_vector_idx`.

### Postgres

`postgres.rs` — pgvector for embeddings, full-text index for keyword. Recommended for shared/multi-tenant deployments and for the knowledge graph (`knowledge_graph_pg.rs`).

### Qdrant

`qdrant.rs` — high-volume vector retrieval. Useful when memory grows past what SQLite/PG can index efficiently for vector search. The KG, audit, and response cache stay on the relational store.

### None

`none.rs` — no-op backend. Useful for `--minimal` builds or fully ephemeral agents.

## Retrieval

`retrieval.rs` — hybrid keyword + vector retrieval. Likely RRF-style fusion (similar to ironclaw); the exact algorithm is best confirmed in the file. The `Memory.search()` API is what the agent calls; backend specifics are hidden.

## Background processes

ZeroClaw runs several background memory maintainers:

- **Decay** (`decay.rs`) — periodically reduces importance scores of older entries; falls below a threshold → eligible for consolidation or eviction.
- **Consolidation** (`consolidation.rs`) — merges fragments. E.g. five separate "user prefers X" mentions → one canonical preference entry.
- **Hygiene** (`hygiene.rs`) — scrubs new entries for prompt-injection patterns before they enter the searchable index.
- **Conflict** (`conflict.rs`) — when a new entry contradicts an existing one (e.g. "user uses dark mode" vs old "user uses light mode"), the conflict is recorded; resolution policy decides which wins.

These run as scheduled tasks (cron-style or interval-driven in `runtime/cron`).

## Knowledge graph

`knowledge_graph.rs` + `knowledge_graph_pg.rs`. Extracts entities (people, projects, dates, locations) and relations from conversation. Used for structured retrieval ("what did Alice say about the Q2 deadline?") that pure vector search misses.

This is heavyweight. Most personal-agent ports won't need it for v1.

## Response cache

`response_cache.rs` — caches LLM responses to identical (or near-identical) prompts. The agent (`agent.rs:69`) optionally holds an `Arc<ResponseCache>`. Useful for SOPs that re-issue the same prompts or for development loops.

## Snapshots

`snapshot.rs` — point-in-time captures of memory state. Useful for backups, debugging, "rollback to yesterday".

## Memory in the agent loop

Per-turn flow (`agent/agent.rs`):

1. `memory_loader.load_context(conversation_id)` — load prior conversation messages.
2. Optionally augment with retrieval: `memory.search(user_message, k)` → top-k facts inserted into the system prompt or as a separate "recall" message.
3. After the turn: `memory.append(conversation_id, message)` for each new message + tool call + tool result.
4. `auto_save` flag (default on for messages ≥ `AUTOSAVE_MIN_MESSAGE_CHARS = 20` chars) decides whether non-trivial user messages get committed.

Memory categories (`MemoryCategory`):
- `Conversation` — chat history
- `Fact` — extracted/saved facts
- `Preference` — user preferences
- `Skill` — agent-learned playbooks (skillforge)
- `Document` — ingested files
- … (others; check `zeroclaw-memory::lib.rs`)

## Hygiene policy

Inbound memory writes pass through `hygiene.rs`. Patterns flagged include:
- Prompt-injection attempts ("ignore prior instructions", role-redefining strings)
- Known credential shapes (API key formats, private-key headers)
- Excessive length (truncated)

A flagged entry is either rejected, sanitized, or annotated for human review depending on `policy.rs` settings.

## Take-aways for the Kotlin port

- Pick **one** persistence backend for v1: SQLite or Postgres. Don't ship Qdrant on day one.
- The `Memory` trait pattern (one ABI, swappable backends) is right.
- Include hygiene from the start — prompt injection is a real attack surface against memory writes, and bolting it on later is annoying because every backend has to implement it.
- Background maintainers (decay, consolidation, conflict) are great features but optional. v1 can ship without them; add when the corpus grows.
- The knowledge graph is overkill for v1 personal agents. Defer.
- Response cache is cheap and visible-perf-win; ship it.
- Snapshots: easy to add later. Skip for v1.
- A `none` backend is genuinely useful for tests and minimal builds — write the trait so this is trivial to implement.
