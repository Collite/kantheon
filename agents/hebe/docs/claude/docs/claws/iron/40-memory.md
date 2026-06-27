# Memory (Workspace)

Inspired explicitly by OpenClaw (`src/workspace/README.md:3`).

## Mental model

> **"Memory is database, not RAM."** If you want to remember something, write it.

`Workspace` is a virtual filesystem where every file is a markdown document, every document is chunked, every chunk is FTS- and vector-indexed. Whole tree lives in a relational DB (Postgres or libSQL). The "filesystem" abstraction is a query convention, not literal disk paths.

## Layout

`src/workspace/README.md:14-36`:

```
workspace/
├── README.md            ← root runbook
├── MEMORY.md            ← long-term curated memory
├── HEARTBEAT.md         ← periodic checklist (read by heartbeat task)
├── IDENTITY.md, SOUL.md, AGENTS.md, USER.md, TOOLS.md
├── BOOTSTRAP.md         ← deleted after onboarding
├── context/             ← vision.md, priorities.md
├── daily/2026-04-30.md  ← daily logs
├── projects/<name>/...  ← arbitrary
└── .system/settings/**  ← dual-writes from SettingsStore
```

The "identity files" (`IDENTITY_PATHS` in `src/workspace/document.rs`) are special: they're injected into the system prompt every turn.

## API

`src/workspace/mod.rs:43-100`:

```rust
let workspace = Workspace::new("user_123", pool)
    .with_embeddings(Arc::new(OpenAiEmbeddings::new(api_key)));   // wraps in default LRU cache
// or .with_embeddings_uncached(...) for tests with mocks

workspace.read("projects/alpha/notes.md").await?;
workspace.write("context/priorities.md", body).await?;
workspace.append("daily/2026-04-30.md", "Completed task X").await?;
workspace.append_memory("User prefers dark mode").await?;
workspace.append_daily_log("Session note").await?;
workspace.list("projects/").await?;
workspace.search("dark mode preference", 5).await?;
workspace.system_prompt().await?;             // assembles identity files
```

`WriteResult` (`mod.rs:78-87`) records whether a write was redirected to a different layer (e.g. sensitive content from a shared scope to private).

## Memory tools (LLM-callable)

Four tools (`src/tools/builtin/memory.rs`):

- `memory_search(query, limit)` — hybrid search; **must** be called before answering questions about prior work (per agent prompt conventions).
- `memory_write(target, content)` — writes to memory, daily_log, or any path.
- `memory_read(path)` — read by path.
- `memory_tree(depth)` — view structure as a tree (default depth 1).

## Hybrid search (RRF)

`src/workspace/search.rs`. Combines FTS and vector via Reciprocal Rank Fusion:

```
score(d) = Σ 1 / (k + rank(d))   for each method where d appears
```

Default `k=60`. Documents in both lists get boosted. Backend differences (`README.md:90-93`):

- **PostgreSQL**: `ts_rank_cd` for FTS; `pgvector` cosine distance for vectors; full RRF.
- **libSQL**: FTS5 + `libsql_vector_idx`. Vector dimension is set dynamically by `ensure_vector_index()` during startup so the same code supports any embedding model size.

## Chunking

`src/workspace/chunker.rs`:

- 800 words per chunk (~800 tokens for English)
- 15% overlap between chunks
- Minimum chunk size: 50 words; tiny trailing chunks merge with the previous

`ChunkConfig` is exposed for tests/tools that need different splits.

## Embedding providers (`src/workspace/embeddings.rs`)

- **NEAR AI** — reuses session auth path
- **OpenAI** — `OPENAI_API_KEY`, `text-embedding-3-small` family
- **Ollama** — local
- **Bedrock** — Titan Text Embeddings V2 (feature-gated)
- **Mock** — for tests, returns deterministic vectors

`CachedEmbeddingProvider` (`src/workspace/embedding_cache.rs`) wraps any provider with an LRU cache to avoid recomputation.

## Multi-scope reads (`src/workspace/README.md:94-114`)

A workspace can have additional read scopes (`with_additional_read_scopes`). E.g. user with scopes `["alice", "shared"]` reads from both. **Identity files are exempt** — they always read from the primary scope only (`read_primary()`):

| File | Method | Reason |
|---|---|---|
| AGENTS, SOUL, USER, IDENTITY, TOOLS, BOOTSTRAP | `read_primary()` | Per-user authority |
| MEMORY.md, daily/*.md | `read()` | Sharing is a feature |

Without this, a user with read access to another scope would silently inherit that scope's identity if their own copy were missing. Documented as a correctness+security issue.

## Workspace as system prompt

`workspace.system_prompt()` assembles identity files in a fixed order. Group-chat detection: if `metadata.chat_type` is `group`/`channel`/`supergroup`, `MEMORY.md` is **excluded** to prevent leaking personal context (`src/agent/CLAUDE.md:46`).

## Hygiene

`src/workspace/hygiene.rs` runs sanitization checks on inbound writes — files injected into the system prompt are scanned for prompt-injection patterns (`Sanitizer`, `Severity` from `ironclaw_safety`). High-severity matches are rejected. Configurable via `HygieneConfig`.

## Compaction (writes from agent loop)

When `ContextMonitor` detects token pressure approaching the model's context window, the dispatcher chooses one of three strategies (`src/agent/compaction.rs`, `src/agent/CLAUDE.md:80-93`):

- **MoveToWorkspace** (80–85%): write full transcript to today's `daily/YYYY-MM-DD.md`, keep 10 recent turns.
- **Summarize** (85–95%): LLM summarizes old turns; summary written to daily log; old turns dropped.
- **Truncate** (>95%): drop oldest without summary (fast path).

If the LLM call for summarization fails, the error propagates; turns are **not** truncated on failure (data preservation).

Token estimation is `word_count × 1.3 + 4 overhead per message`. Default context limit 100k tokens; threshold 80%. Manual `/compact` available.

## Heartbeat (proactive memory loop)

`src/agent/heartbeat.rs` + `src/workspace/README.md:121-138`:

```rust
let cfg = HeartbeatConfig::default()
    .with_interval(Duration::from_secs(30 * 60))
    .with_notify("user_123", "telegram");

spawn_heartbeat(cfg, workspace, llm, response_tx);
```

Every 30 min:
1. Read `HEARTBEAT.md`.
2. Run an agent turn with the checklist as prompt.
3. If the agent replies anything other than literal `HEARTBEAT_OK`, notify via the configured channel.
4. Otherwise, silent.

Uses `cheap_llm` if available (falls back to main LLM, `agent_loop.rs:438-441`).

`spawn_multi_user_heartbeat` exists for multi-tenant deploys (iterates users from the system store); requires `SystemScope`.

## Persistence model

Workspace data lives in DB tables (defined in `src/workspace/schema.rs`, migrated under `migrations/`). The "in-memory cache → DB" rule applies (`CLAUDE.md:74-77`): cleanup of terminal threads evicts caches, never deletes rows.

`Repository` (`src/workspace/repository.rs`, postgres-only currently) implements the heavy lifting; libSQL has a parallel implementation. New persistence features must support both backends.

## v2 mapping

In engine v2, `MemoryDoc` (`crates/ironclaw_engine/src/types/memory.rs`) replaces "workspace memory blob". `DocType` variants: `Summary`, `Lesson`, `Skill`, `Issue`, `Spec`, `Note`. Project-scoped via `ProjectId`. `RetrievalEngine` (`engine/memory/retrieval.rs`) does keyword-based context retrieval over project docs. `SkillTracker` (`engine/memory/skill_tracker.rs`) maintains confidence scores and versioned updates with rollback.

## Take-aways for the Kotlin port

- The "identity files always read from primary scope" rule is genuinely subtle and security-relevant. Mirror it.
- RRF is one screen of code. Don't reach for a vector DB for a personal agent — Postgres+pgvector or SQLite+FTS5 with vector index is enough.
- The chunking parameters (800 words, 15% overlap, min 50) are sensible defaults; expose them but don't over-engineer.
- The `CachedEmbeddingProvider` LRU is a classic perf win; build it in from the start.
- Compaction with three strategies driven by usage% is the right shape. Refusing to truncate on summarization failure is a quality choice — keep it.
- The "memory writes don't disappear" invariant should be a hard rule in your DB layer, not a comment.
