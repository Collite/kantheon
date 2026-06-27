# M1 — Memory

SQLite + Flyway, sqlite-vec, markdown workspace, chunker, embeddings, indexer, hybrid retrieval (RRF), hygiene scanner, response cache, integration tests.

**Done when:** writing/reading workspace + memory works end-to-end; `MemoryStore.search` returns sensible results on a 100-doc corpus; hygiene blocks a known prompt-injection sample.

References: [`../v1-architecture.md`](../v1-architecture.md) §§5, 6, 13.

---

## M1.T1 — SQLite open + Flyway runner

**Status**: pending  
**Size**: M  
**Depends on**: M0.T8  
**Blocks**: every later memory task

### Goal

`Db.open(path)` returns a configured `javax.sql.DataSource` and runs Flyway migrations to head. Idempotent on re-open.

### Files to create

- `modules/memory/build.gradle.kts` (edit)
- `modules/memory/src/main/kotlin/com/hebe/memory/db/Db.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/db/Migrations.kt` (new — wraps Flyway)
- `modules/memory/src/main/resources/db/migration/.gitkeep` (new — V*.sql land here in M1.T2)
- Tests against an in-memory SQLite

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:observability"))
       implementation(libs.sqlite.jdbc)
       implementation(libs.flyway.core)
   }
   ```

2. `Db.kt`:

   ```kotlin
   class Db(val dataSource: DataSource, val isInMemory: Boolean)

   object DbFactory {
       fun open(path: Path, observer: Observer): Db { /* … */ }
       fun openInMemory(): Db { /* for tests */ }
   }
   ```

   - JDBC URL: `jdbc:sqlite:${path}?journal_mode=WAL&busy_timeout=5000&foreign_keys=on`.
   - Use `SQLiteDataSource` from `org.sqlite`.
   - Set sensible PRAGMAs after open: `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `temp_store=MEMORY`, `mmap_size=268435456`.

3. `Migrations.kt`:

   ```kotlin
   fun migrate(ds: DataSource): MigrationInfo {
       val flyway = Flyway.configure()
           .dataSource(ds)
           .locations("classpath:db/migration")
           .baselineOnMigrate(false)
           .load()
       val result = flyway.migrate()
       return MigrationInfo(version = result.targetSchemaVersion?.version, applied = result.migrationsExecuted)
   }
   ```

4. `Db.open` calls `migrate(ds)` and observer-events `MemoryDbReady(version)`.

5. Tests:
   - Open in-memory DB, ensure migrations succeed (will be a no-op until M1.T2 lands; pre-condition is just "no exception").
   - Close + reopen against the same file, assert migrations are no-ops the second time.

### Acceptance criteria

- ✅ `Db.open` returns a working `DataSource`.
- ✅ Flyway runs migrations from `db/migration/`.
- ✅ Re-opening the same file is a no-op for migrations.
- ✅ WAL mode confirmed by querying `PRAGMA journal_mode`.

### Pitfalls

- SQLite WAL mode requires the file to live on a writable filesystem; in-memory DBs ignore the PRAGMA.
- Flyway >= 10 dropped Java 8 support; Java 21 toolchain is fine.

### References

- `v1-architecture.md` §5 (schema)
- `v1-architecture.md` §19 (boot sequence — migrations before anything else)

---

## M1.T2 — Migration files V1–V5

**Status**: pending  
**Size**: M  
**Depends on**: M1.T1  
**Blocks**: every later memory task, M2.T6 (dispatcher needs `tool_calls`), M2.T8 (cost guard needs `llm_calls`), M2.T5 (`pending_approvals`)

### Goal

Translate the SQL DDL from `v1-architecture.md` §5 into Flyway migration files V1–V5.

### Files to create

- `modules/memory/src/main/resources/db/migration/V1__core.sql` (new)
- `modules/memory/src/main/resources/db/migration/V2__memory.sql` (new)
- `modules/memory/src/main/resources/db/migration/V3__settings_jobs_routines.sql` (new)
- `modules/memory/src/main/resources/db/migration/V4__llm_calls_tool_calls.sql` (new)
- `modules/memory/src/main/resources/db/migration/V5__pending_approvals.sql` (new)
- Smoke test verifying each table exists post-migration

### Detailed work

1. Copy the DDL **verbatim** from `v1-architecture.md` §5 into the matching files. Do not rename columns.

2. After all V*.sql files land, write a smoke test:

   ```kotlin
   class MigrationSmokeTest : FunSpec({
       test("all tables present after migration") {
           val db = DbFactory.openInMemory()
           db.dataSource.connection.use { c ->
               val tables = c.metaData.getTables(null, null, "%", arrayOf("TABLE"))
               val names = mutableSetOf<String>()
               while (tables.next()) names += tables.getString("TABLE_NAME")
               names shouldContainAll setOf(
                   "conversations", "messages",
                   "memory_docs", "memory_chunks",
                   "settings", "jobs", "routines",
                   "llm_calls", "tool_calls",
                   "pending_approvals",
               )
           }
       }
   })
   ```

3. **Migration immutability rule**: once V1–V5 ship in `main`, their content **never changes**. New schema lands as V6+. Add a CI check that fails if a shipped migration's file content (hash) differs from a previously-committed version. (See cross-cutting X.T8.)

### Acceptance criteria

- ✅ V1–V5 land matching arch §5 exactly.
- ✅ Smoke test passes.
- ✅ Tables include the FTS5 + sqlite-vec virtual tables (their creation depends on extension loading; M1.T3).

### Pitfalls

- **The `memory_chunks_vec` virtual table will fail to create unless the sqlite-vec extension is loaded** before Flyway runs the migration. Sequence: open DataSource → load sqlite-vec extension → run Flyway. Alternatively, split V2 into V2a (regular tables + FTS5) and V2b (vec table created lazily after extension load), but that adds complexity. **Recommended**: load the extension during `DbFactory.open` *before* Flyway, so V2 succeeds.
- The FTS5 `content='memory_chunks'` reference creates a contentless FTS table; you need triggers to keep them in sync, OR you populate via `INSERT INTO memory_chunks_fts(rowid, …)` manually in the indexer (M1.T9). Pick the trigger path now to avoid bugs:

  ```sql
  CREATE TRIGGER memory_chunks_ai AFTER INSERT ON memory_chunks BEGIN
    INSERT INTO memory_chunks_fts(rowid, doc_path, chunk_idx, content)
    VALUES (new.rowid, new.doc_path, new.chunk_idx, new.content);
  END;

  CREATE TRIGGER memory_chunks_ad AFTER DELETE ON memory_chunks BEGIN
    INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, doc_path, chunk_idx, content)
    VALUES('delete', old.rowid, old.doc_path, old.chunk_idx, old.content);
  END;

  CREATE TRIGGER memory_chunks_au AFTER UPDATE ON memory_chunks BEGIN
    INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, doc_path, chunk_idx, content)
    VALUES('delete', old.rowid, old.doc_path, old.chunk_idx, old.content);
    INSERT INTO memory_chunks_fts(rowid, doc_path, chunk_idx, content)
    VALUES (new.rowid, new.doc_path, new.chunk_idx, new.content);
  END;
  ```

  Add this to V2.

### References

- `v1-architecture.md` §5

---

## M1.T3 — sqlite-vec extension loader

**Status**: pending  
**Size**: M  
**Depends on**: M1.T1  
**Blocks**: M1.T2 (must run before V2's vec table), M1.T9 (indexer)

### Goal

Load the sqlite-vec native extension before any migration or query that touches `memory_chunks_vec`. Bundled binaries for macOS (arm64+x86_64) and Linux (x86_64+aarch64) extracted to a temp dir and registered via JDBC.

### Files to create

- `modules/memory/build.gradle.kts` (edit — add sqlite-vec native dep)
- `modules/memory/src/main/kotlin/com/hebe/memory/db/SqliteVecExtension.kt` (new)
- `modules/memory/src/main/resources/native/sqlite-vec/` (new — binaries placed here at build time)
- Tests that insert + query a vec row

### Detailed work

1. The sqlite-vec project ships native libraries on GitHub releases. Two options:
   - **Option A (recommended)**: download `sqlite-vec` artifacts during the Gradle build via a custom task and unpack to `modules/memory/src/main/resources/native/sqlite-vec/<os>-<arch>/`. License-compatible (Apache 2.0).
   - **Option B**: use a published Maven artifact if one exists at the time you implement. (Verify on the day; sqlite-vec is young.)

2. `SqliteVecExtension.kt`:

   ```kotlin
   object SqliteVecExtension {
       fun load(connection: Connection) {
           val (os, arch) = detectPlatform()
           val resource = "/native/sqlite-vec/$os-$arch/${libName(os)}"
           val tmp = extractToTemp(resource)
           connection.unwrap(SQLiteConnection::class.java).enableLoadExtension(true)
           connection.createStatement().use { st ->
               st.execute("SELECT load_extension('${tmp.toAbsolutePath()}')")
           }
       }
   }
   ```

3. Wire into `DbFactory.open`: open one connection, load the extension, then run Flyway. SQLite needs the extension loaded **per connection**; for a connection pool you'd hook `onAcquire`. v1 keeps a single shared connection or uses a tiny pool — register a connection-init callback.

4. Connection pooling: HikariCP is overkill for SQLite single-writer. Use the simpler `SQLiteDataSource` directly + a single connection guarded by a `Mutex`. Document the choice. (We can switch to Hikari later if measurement says so.)

### Tests / verification

- After `Db.open`, `SELECT vec_version()` returns a non-null string.
- Insert a vec row + cosine query returns it.

### Acceptance criteria

- ✅ Extension loads on macOS arm64 and Linux x86_64 minimum.
- ✅ `memory_chunks_vec` virtual table creates successfully in V2.
- ✅ Vec extension load fails loudly with a remediation hint if the platform isn't supported.

### Pitfalls

- `enableLoadExtension(true)` on Xerial JDBC is a non-standard method; you must `unwrap(SQLiteConnection::class.java)` first.
- The sqlite-vec library name varies by platform: `vec0.dylib` (mac), `vec0.so` (linux), `vec0.dll` (windows). Windows support is best-effort in v1.
- If a connection pool reuses connections, the extension is loaded once but stays loaded.

### References

- `v1-architecture.md` §5 (vec table)
- [sqlite-vec docs](https://github.com/asg017/sqlite-vec) (verify URL at PR time)

---

## M1.T4 — `WorkspaceFs` (read/write/list/append, workspace-bounded)

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5, M0.T8  
**Blocks**: M1.T5 (seeding), M4.T1 (file_system tool), M2.T9 (compaction), M3.T2 (workspace boundary)

### Goal

A type-safe filesystem façade for the workspace. All paths are validated against the workspace root; any attempt to escape (`..`, absolute paths, symlinks pointing outside) throws `HebeException.Security`.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/workspace/WorkspaceFs.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/workspace/WorkspacePath.kt` (new — value class)
- `modules/memory/src/main/kotlin/com/hebe/memory/workspace/MarkdownInferrer.kt` (new)
- Tests: golden cases for both happy and traversal-attack paths

### Detailed work

1. `WorkspacePath`:

   ```kotlin
   @JvmInline value class WorkspacePath(val value: String) {
       init { require(!value.contains("..") && !value.startsWith("/")) }
       fun resolve(child: String): WorkspacePath = …
   }
   ```

2. `WorkspaceFs`:

   ```kotlin
   class WorkspaceFs(private val root: Path) {
       fun read(path: WorkspacePath): String? = …
       fun write(path: WorkspacePath, content: String) = …
       fun append(path: WorkspacePath, content: String) = …
       fun list(prefix: WorkspacePath): List<WorkspacePath> = …
       fun exists(path: WorkspacePath): Boolean = …
       fun delete(path: WorkspacePath) = …                  // for tests + onboarding only
   }
   ```

3. Resolution:
   - Compute `resolved = root.resolve(path.value).toRealPath()`.
   - Validate `resolved.startsWith(root.toRealPath())`. If not, throw `HebeException.Security("escape attempt: $path")`.
   - Symlink-following is the default, but the start-with check on the *real* path catches symlink escapes.

4. `write` uses atomic-write semantics: write to `path.tmp` then `Files.move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`. Mitigates "kill -9 mid-write" workspace corruption (an NFR in `v1-specs.md` §4).

5. `MarkdownInferrer.metadata(path, content)` returns `{ extension, title?, headings, frontmatter? }` for tools like `memory_tree` and the workspace browser. Use a tiny YAML-frontmatter parser (regex is fine for `---\n...\n---` blocks at top of file).

### Tests / verification

- Happy path: `write(MEMORY.md, …) → read(MEMORY.md)` round-trip.
- Traversal: `read(WorkspacePath("../etc/passwd"))` (assuming we let it past the constructor — we don't) is impossible; the constructor `require(...)` rejects.
- Symlink escape: create a symlink `~/.hebe/workspace/escape -> /etc`; `read(WorkspacePath("escape/passwd"))` throws `HebeException.Security`.
- Atomic write: simulate a crash during write (drop the move step) and assert the original file is intact.

### Acceptance criteria

- ✅ All paths are workspace-bounded.
- ✅ Atomic writes (move from tmp).
- ✅ Tests cover symlink-escape attempt.
- ✅ `MarkdownInferrer` returns sensible metadata for `MEMORY.md` and a daily log.

### Pitfalls

- `toRealPath()` resolves symlinks but throws if the file doesn't exist; for `write` you must `toRealPath()` the parent directory and join the leaf manually.
- On macOS, `~/.hebe/workspace` is often under `/Users/<name>/...` which is also a symlink target on some configurations. Test on both APFS and case-insensitive HFS.

### References

- `v1-architecture.md` §6 (workspace layout)

---

## M1.T5 — Workspace seeding (BOOTSTRAP/IDENTITY/MEMORY/HEARTBEAT/README)

**Status**: pending  
**Size**: S  
**Depends on**: M1.T4  
**Blocks**: M9.T4 (onboarding wizard)

### Goal

On first run, populate the workspace with the canonical layout from `v1-architecture.md` §6. Idempotent: re-running doesn't overwrite user edits.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/workspace/WorkspaceSeeder.kt` (new)
- `modules/memory/src/main/resources/workspace-seeds/README.md` (new)
- `modules/memory/src/main/resources/workspace-seeds/IDENTITY.md` (new)
- `modules/memory/src/main/resources/workspace-seeds/MEMORY.md` (new — empty/templated)
- `modules/memory/src/main/resources/workspace-seeds/HEARTBEAT.md` (new — example checklist)
- `modules/memory/src/main/resources/workspace-seeds/BOOTSTRAP.md` (new — onboarding-only)
- Tests: seed → verify file presence, idempotency

### Detailed work

1. `WorkspaceSeeder.seedIfMissing(fs: WorkspaceFs)`:
   - For each seed file in `resources/workspace-seeds/`, `if (!fs.exists(path)) fs.write(path, seedContent)`.
   - Create empty directories: `daily/`, `context/`, `projects/`, `.system/settings/`.

2. `BOOTSTRAP.md` content suggests the user run `hebe onboard` and lists what onboarding does. After onboarding completes (M9.T4), it deletes this file.

3. `IDENTITY.md` template: a paragraph or two; user edits to set the agent's persona.

4. `MEMORY.md` template: a heading with sub-sections (`## Preferences`, `## Long-term facts`).

5. `HEARTBEAT.md` template: an example checklist that the heartbeat routine reads (M8.T9). Comments inline explain the silence-on-OK rule.

### Tests / verification

- Empty workspace → seed → all seed files present.
- Edit `MEMORY.md` → re-seed → edit preserved.
- `BOOTSTRAP.md` is removed by a separate `WorkspaceSeeder.completeOnboarding(fs)` call.

### Acceptance criteria

- ✅ Layout matches arch §6.
- ✅ Idempotent.
- ✅ Resource files committed under `workspace-seeds/`.

### References

- `v1-architecture.md` §6

---

## M1.T6 — Chunker (800 words, 15% overlap, min 50)

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: M1.T9 (indexer)

### Goal

A pure function that splits text into chunks suitable for embedding. Property-tested; deterministic.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/chunker/Chunker.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/chunker/ChunkerConfig.kt` (new)
- Tests with property + golden cases

### Detailed work

1. `ChunkerConfig`:

   ```kotlin
   data class ChunkerConfig(
       val targetWords: Int = 800,
       val overlapPercent: Double = 0.15,
       val minWords: Int = 50,
   )
   ```

2. `Chunker.chunk(text: String, cfg: ChunkerConfig = ChunkerConfig()): List<Chunk>`:
   - Tokenise on whitespace; preserve original positions for round-trip.
   - Walk the token list creating chunks of `targetWords`, sliding by `targetWords * (1 - overlapPercent)`.
   - Drop the trailing chunk if it's < `minWords` (or merge it into the previous).
   - For markdown input, prefer to break on heading boundaries when a chunk boundary lands within `± 50 tokens` of one.

3. `data class Chunk(val index: Int, val content: String, val tokenCount: Int)`.

4. Properties (Kotest):
   - `chunks.flatMap { it.content }` covers the input (modulo overlap).
   - `chunks.size <= ceil(words / (targetWords * (1 - overlapPercent)))`.
   - For texts < `minWords`, returns one chunk (the whole text).

### Tests / verification

- Golden: a 4000-word fixture chunks to ~5–6 chunks with 120-word overlaps.
- Property: random texts produce non-empty chunks; reassembly via concatenation (with overlap dedup) recovers the original.
- Unicode: emoji and CJK characters don't break the word counter.

### Acceptance criteria

- ✅ Pure function.
- ✅ Property tests pass.
- ✅ Heading-aware breaking on markdown.

### Pitfalls

- "Word" is fuzzy. Default to whitespace-split; the embedding model's tokeniser is the real measure but we don't have access to it here. Document that `tokenCount` is a *word* count, not a *token* count, and the caller can re-estimate if needed.

### References

- `v1-architecture.md` §13 (chunking defaults)

---

## M1.T7 — `EmbeddingProvider` trait + mock + OpenAI-compat impl

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5, M0.T9  
**Blocks**: M1.T8 (cache wrapper), M1.T9 (indexer), M2.T1 (uses the same Ktor client setup)

### Goal

Pluggable embedding provider. Mock returns deterministic vectors for tests. OpenAI-compat impl reads `embedding_model` from config and BYO key from secrets; works against the user's gateway, OpenAI, Ollama.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/embeddings/EmbeddingProvider.kt` (new — interface)
- `modules/memory/src/main/kotlin/com/hebe/memory/embeddings/MockEmbeddingProvider.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/embeddings/OpenAiCompatEmbeddingProvider.kt` (new)
- Tests using the mock + a recorded fixture for OpenAI-compat

### Detailed work

1. Interface:

   ```kotlin
   interface EmbeddingProvider {
       val model: String
       val dim: Int
       suspend fun embed(texts: List<String>): List<FloatArray>
   }
   ```

2. `MockEmbeddingProvider(seed = 0L, dim = 1536)`: returns deterministic per-text vectors based on `text.hashCode()`-seeded RNG. Same input → same output.

3. `OpenAiCompatEmbeddingProvider(client: HttpClient, baseUrl: String, apiKey: String, model: String, dim: Int)`:
   - POST to `${baseUrl}/embeddings` with body `{"input": [...], "model": "..."}`.
   - Parse response `{"data": [{"embedding": [...]}, ...]}`.
   - Batches: cap at 32 inputs per call (configurable). Iterate if more.

4. Don't bake retries here; M2.T1 introduces the shared Ktor retry plugin and we reuse.

### Tests / verification

- Mock determinism: `embed("foo") == embed("foo")`.
- OpenAI-compat: against a recorded JSON fixture (use the cross-cutting X.T1 record/replay infra).
- Batching: a 100-input call results in 4 HTTP calls (batches of 32).

### Acceptance criteria

- ✅ Interface in `embeddings/` matches the documented shape.
- ✅ Mock and OpenAI-compat both implement it.
- ✅ Recorded-fixture test for OpenAI-compat.

### Pitfalls

- Different OpenAI-compat providers vary on the embeddings endpoint shape (`/v1/embeddings` vs `/embeddings`); the `baseUrl` config decides. Don't hardcode `/v1`.
- Float vs Double: response JSON has doubles; convert with `toFloat()` and store as `FloatArray` in BLOB.

### References

- `v1-architecture.md` §7 (config: `embedding_model`, `embedding_dim`)

---

## M1.T8 — LRU `CachedEmbeddingProvider`

**Status**: pending  
**Size**: S  
**Depends on**: M1.T7  
**Blocks**: M1.T9

### Goal

Wrap any `EmbeddingProvider` with an LRU cache to avoid re-embedding identical chunk text.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/embeddings/CachedEmbeddingProvider.kt` (new)
- Tests covering hit/miss/eviction

### Detailed work

1. Use `java.util.LinkedHashMap` with `accessOrder=true`, override `removeEldestEntry`.

2. Cache key: `sha256(text)` (16 bytes from prefix is enough). Store the float array.

3. Default capacity: 4096 entries. Configurable.

4. Thread-safety: wrap with a `Mutex` (coroutine-friendly) — embeddings are async and the cache is on the hot path of the indexer.

### Tests / verification

- Hit: same text twice → second call doesn't hit the underlying provider (verified via mock/spy).
- Eviction: capacity-1 inserts followed by one extra evicts the oldest.

### Acceptance criteria

- ✅ Decorates any `EmbeddingProvider`.
- ✅ LRU semantics verified.
- ✅ Thread-safe under concurrent access.

### References

- `v1-architecture.md` §13 (response cache + embedding cache mentions)

---

## M1.T9 — Indexer: doc → chunks → FTS + vec rows

**Status**: pending  
**Size**: L  
**Depends on**: M1.T2, M1.T6, M1.T7  
**Blocks**: M1.T10 (search), M8.T7 (embedding refresh routine)

### Goal

Given a workspace doc, write its chunks into `memory_chunks` (FTS auto-syncs via triggers from V2) and `memory_chunks_vec`. Idempotent: re-indexing the same doc replaces old chunks for that path.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/indexer/Indexer.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/indexer/IndexerOps.kt` (new — SQL bits)
- Tests writing + reading back

### Detailed work

1. `Indexer.indexDoc(path: WorkspacePath, content: String, scope: MemoryScope = Default)`:
   - Compute `hash_sha256` of `content`.
   - Upsert into `memory_docs` (`INSERT ... ON CONFLICT(path) DO UPDATE`).
   - If hash unchanged from existing row, return early — nothing to do.
   - Delete existing `memory_chunks` for the path (FTS + vec triggers cascade).
   - Chunk the content via `Chunker`.
   - Embed all chunks in one (or batched) `EmbeddingProvider.embed()`.
   - Insert chunks: `memory_chunks` row with the embedding BLOB; the `memory_chunks_vec` row inserted explicitly (its virtual-table triggers don't fire from `memory_chunks`).

2. Embedding BLOB serialisation: `FloatArray` → byte buffer (little-endian, 4 bytes per float). Reverse on read.

3. Vec row insert: `INSERT INTO memory_chunks_vec(doc_path, chunk_idx, embedding) VALUES (?, ?, ?)` — sqlite-vec accepts the FloatArray as a JSON array string `"[0.1, 0.2, ...]"` or a raw blob (depending on version; verify).

4. Transactional: all inserts for a doc happen in one transaction so the doc/chunks/fts/vec stay consistent.

### Tests / verification

- Index a 4000-word doc → 5–6 chunk rows.
- Re-index unchanged content → no DB writes (verify via row count + ts unchanged).
- Re-index with edits → old chunks gone, new ones inserted, FTS + vec consistent.
- Stress test: 100 docs indexed → row counts match expectations.

### Acceptance criteria

- ✅ Idempotent on identical content.
- ✅ Replaces chunks on edits.
- ✅ FTS + vec stay in sync (triggers do FTS; explicit insert does vec).
- ✅ Single transaction per doc.

### Pitfalls

- The FTS triggers from V2 (M1.T2) sync `memory_chunks` → `memory_chunks_fts`. They do NOT sync to `memory_chunks_vec` because that's a different virtual table. Insert vec rows explicitly.
- Re-running with "same content but different scope" — decide: same doc (overwrite) or different doc (different path)? Recommend same doc, scope is just a column update.

### References

- `v1-architecture.md` §5, §13

---

## M1.T10 — RRF retrieval (`MemoryStore.search`)

**Status**: pending  
**Size**: M  
**Depends on**: M1.T9  
**Blocks**: M4.T5 (memory_search tool), M5.T6 (web memory browser)

### Goal

Implement `MemoryStore.search(query, k)` per `v1-architecture.md` §13 — FTS5 + vector cosine, fused with Reciprocal Rank Fusion at `k₀ = 60`.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/search/Searcher.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/search/Rrf.kt` (new — pure function)
- `modules/memory/src/main/kotlin/com/hebe/memory/SqliteMemoryStore.kt` (edit if exists, else new)
- Tests

### Detailed work

1. `Searcher.search(query: String, k: Int): List<MemoryHit>`:
   1. Embed `query` once.
   2. FTS query: `SELECT doc_path, chunk_idx, snippet(memory_chunks_fts, 2, '<b>', '</b>', '...', 32) FROM memory_chunks_fts WHERE content MATCH ? ORDER BY rank LIMIT k*4`.
   3. Vec query: `SELECT doc_path, chunk_idx, distance FROM memory_chunks_vec WHERE embedding MATCH ? ORDER BY distance LIMIT k*4` (sqlite-vec syntax; verify).
   4. Fuse via RRF: `score(d) = Σ 1/(k₀ + rank(d))` across the two ranked lists.
   5. Take top `k`.

2. `Rrf.fuse(lists: List<List<Hit>>, k0: Int = 60, k: Int): List<Hit>` is a pure function with property tests.

3. `MemoryHit.source` set per how it was found: `Fts | Vector | Both`.

4. Hydrate snippet text: from FTS we get a snippet directly; for vector-only hits, fetch the chunk content from `memory_chunks` and trim to the first 200 chars.

### Tests / verification

- Empty corpus → empty result.
- Index 3 docs (one matches keywords, one matches semantically, one neither) → both relevant docs appear; the third doesn't.
- RRF property: a doc that ranks #1 in both lists outranks a doc that ranks #1 in only one.

### Acceptance criteria

- ✅ Hybrid search runs both queries, fuses with RRF.
- ✅ Returns at most `k` hits.
- ✅ `source` field populated correctly.
- ✅ Performance: 100-doc corpus, k=10 query in < 100 ms on a dev laptop.

### Pitfalls

- FTS5 query syntax requires user input to be safe. Either escape (`"` quoting) or restrict to alphanumeric input. For agent-controlled queries this is usually fine, but document the rule.
- sqlite-vec MATCH syntax varies by version; pin and test.

### References

- `v1-architecture.md` §13

---

## M1.T11 — Identity-files loader for `systemPrompt()`

**Status**: pending  
**Size**: S  
**Depends on**: M1.T4  
**Blocks**: M2.T11 (ChatDelegate uses `systemPrompt()`)

### Goal

`MemoryStore.systemPrompt()` assembles `IDENTITY.md`, `MEMORY.md`, and `HEARTBEAT.md` (in that order) into a single string, prefixed with markers so the agent can tell sections apart.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/SystemPromptAssembler.kt` (new)
- Tests

### Detailed work

1. Read each identity file from the **Default scope** only (per arch §13: "Identity files always read from primary scope; multi-scope reads only for shared content").

2. Output template:

   ```
   <identity>
   {IDENTITY.md}
   </identity>

   <memory>
   {MEMORY.md content, OR empty in group-chat contexts}
   </memory>

   <heartbeat>
   {HEARTBEAT.md content}
   </heartbeat>
   ```

3. Group-chat detection: `SystemPromptAssembler.assemble(isGroup: Boolean)` — when `isGroup = true`, omit the `<memory>` block. Default `isGroup = false`. (Wired by the channel adapter; v1 Telegram is 1:1 so it's always false.)

4. Cache the assembled prompt for a configurable TTL (e.g. 60 s) since these files don't change frequently.

### Tests / verification

- All three files present → all three sections in output.
- `MEMORY.md` missing → empty `<memory>` section but markers present.
- Group-chat mode → `<memory>` omitted entirely.

### Acceptance criteria

- ✅ Output format exactly as documented.
- ✅ Group-chat behaviour tested.
- ✅ Cache invalidation on file mtime change.

### References

- `v1-architecture.md` §13

---

## M1.T12 — Hygiene scanner

**Status**: pending  
**Size**: M  
**Depends on**: M1.T9  
**Blocks**: M4.T5 (memory_write rejects malicious input)

### Goal

Pattern-scan content destined for `memory_docs` writes. High-severity hits reject the write; low-severity logs a warning.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/hygiene/HygieneScanner.kt` (new)
- `modules/memory/src/main/kotlin/com/hebe/memory/hygiene/HygieneRules.kt` (new — pattern set)
- Tests

### Detailed work

1. `HygieneScanner.scan(content: String): HygieneVerdict` returns a sealed result:

   ```kotlin
   sealed interface HygieneVerdict {
       data object Clean : HygieneVerdict
       data class Warn(val findings: List<Finding>) : HygieneVerdict
       data class Reject(val findings: List<Finding>) : HygieneVerdict
   }
   data class Finding(val rule: String, val severity: Severity, val excerpt: String)
   enum class Severity { Low, Medium, High }
   ```

2. Initial rule set (regex; case-insensitive):
   - `\b(ignore|disregard).{0,30}(previous|above)\s+(instructions|prompts|rules)` → High
   - `\bpretend\s+(you|to\s+be)\b.+\b(developer|admin|root)\b` → High
   - `<\s*system\s*>` (HTML-ish system tag) → Medium
   - `\bcurl\s+.+\|\s*(bash|sh)\b` → Medium (command injection in stored content)
   - `\bexec\s*\(\s*['"]?(rm|sudo)\b` → Medium
   - Basic prompt-injection markers (`### ASSISTANT:`, `### SYSTEM:`) → Low

3. Wire into `MemoryStore.appendDoc`: on `Reject`, throw `HebeException.Memory("rejected: ${finding.rule}")`. On `Warn`, append the warning to the observer.

4. Make the rule set externalisable: `HygieneRules.fromConfig(toml)` so users can extend.

### Tests / verification

- Synthetic injection samples reject.
- Benign content (e.g. a blog post quoting a prompt-injection example as a *cautionary tale*) — accepted with `Warn`. Document this trade-off; aggressive blocking would lock out legitimate writes about security.

### Acceptance criteria

- ✅ At least 6 patterns shipping.
- ✅ Severity ladder respected.
- ✅ Externally configurable rule set.

### Pitfalls

- Regex performance on large inputs; cap input length to 200 KB before scanning, or compile once and reuse.
- False positives are inevitable; document the workaround (`// hygiene-exempt: <reason>` is a footgun in markdown — instead, use a fenced code block that the scanner skips).

### References

- `v1-architecture.md` §13 (hygiene)
- `v1-specs.md` §2.3 (hygiene scanner)

---

## M1.T13 — Group-chat detection stub

**Status**: pending  
**Size**: S  
**Depends on**: M1.T11  
**Blocks**: M5.T8 (Telegram passes the flag once group support exists)

### Goal

A hook so the channel adapter can declare "this conversation is a group" and have `systemPrompt()` omit `MEMORY.md`. v1 ships the seam; Telegram in v1 is 1:1 so it always flags `isGroup = false`.

### Files to create / modify

- `modules/memory/src/main/kotlin/com/hebe/memory/SystemPromptAssembler.kt` (edit — already added in M1.T11; just ensure the parameter is exposed)
- Tests

### Detailed work

1. Confirm `SystemPromptAssembler.assemble(isGroup: Boolean = false)` signature.

2. In `MemoryStore.systemPrompt()` add an optional `context: ContextHints` parameter:

   ```kotlin
   data class ContextHints(val isGroup: Boolean = false)
   suspend fun systemPrompt(context: ContextHints = ContextHints()): String
   ```

3. The dispatcher (M2.T6) reads channel + conversation metadata to construct `ContextHints`.

### Tests / verification

- `assemble(isGroup = true)` omits MEMORY block.
- Default behaviour unchanged.

### Acceptance criteria

- ✅ Seam exposed.
- ✅ Tests pass.

### References

- `v1-architecture.md` §13

---

## M1.T14 — Memory category enum + storage

**Status**: pending  
**Size**: S  
**Depends on**: M1.T2, M1.T9  
**Blocks**: optional filtering in M4.T5

### Goal

Add a `category` column to `memory_docs` (or reuse `scope`) so we can filter `Conversation | Fact | Preference | Skill | Document`.

### Files to create / modify

- `modules/memory/src/main/resources/db/migration/V6__memory_categories.sql` (new — additive)
- `modules/memory/src/main/kotlin/com/hebe/memory/MemoryCategory.kt` (new — already in `api`? if so, just extend)
- Edit `MemoryStore.appendDoc` to accept a `category` parameter

### Detailed work

1. V6 migration:

   ```sql
   ALTER TABLE memory_docs ADD COLUMN category TEXT NOT NULL DEFAULT 'Document';
   CREATE INDEX idx_memory_docs_category ON memory_docs(category);
   ```

2. `appendDoc(path, content, category = MemoryCategory.Document, scope = Default)`.

3. Search filter: `MemoryStore.search(query, k, categories: Set<MemoryCategory>? = null)` adds a SQL `WHERE` clause when set.

### Tests / verification

- Migration upgrades a populated DB without data loss.
- Filter test: `search(q, k, setOf(Fact))` returns only Fact rows.

### Acceptance criteria

- ✅ Categorisation working end-to-end.
- ✅ Backward-compatible: existing rows default to `Document`.

### Pitfalls

- Once V6 ships in `main`, do not edit it (cross-cutting X.T8 enforces).

---

## M1.T15 — LRU response cache

**Status**: pending  
**Size**: S  
**Depends on**: M0.T7  
**Blocks**: optional perf win in M2

### Goal

Cheap caching layer for identical LLM prompts in deterministic mode (`temperature=0`). Hit rate is low in real chat but high in batch / replay.

### Files to create

- `modules/memory/src/main/kotlin/com/hebe/memory/cache/ResponseCache.kt` (new)
- Tests

### Detailed work

1. Key: `sha256(model + system + messagesJson + toolsJson + temperature.toString())`. Only enabled when `temperature == 0.0`.

2. `interface ResponseCache { fun get(key: String): CachedResponse?; fun put(key: String, value: CachedResponse) }` with an in-memory LRU impl (capacity 256).

3. Cached value: full assistant message (text + tool calls + token counts), serialisable to JSON.

4. Wire into `KoogLlmProvider` adapter (M2.T3) as an opt-in interceptor (default off; on for tests).

### Tests / verification

- Same prompt twice → second call short-circuits; verified via spy on the underlying provider.
- Different temperature → no caching.

### Acceptance criteria

- ✅ Optional, off by default.
- ✅ Deterministic key.

### References

- `v1-architecture.md` §13 (response cache mention)

---

## M1.T16 — `MemoryStore` integration tests

**Status**: pending  
**Size**: L  
**Depends on**: M1.T10, M1.T12  
**Blocks**: nothing direct, but is the milestone gate

### Goal

End-to-end test demonstrating: write doc → index → search retrieves → hygiene rejects an injection sample. Uses a real SQLite + sqlite-vec, not mocks.

### Files to create

- `modules/memory/src/test/kotlin/com/hebe/memory/integration/MemoryRoundTripTest.kt` (new)
- `modules/memory/src/test/kotlin/com/hebe/memory/integration/HygieneIntegrationTest.kt` (new)
- Test fixture corpus: 10 small markdown files

### Detailed work

1. `MemoryRoundTripTest`:
   - Open a temp-dir backed `Db`.
   - Seed 10 docs.
   - For each doc, `indexDoc`.
   - Run a search whose top result is doc 4 (semantically) and another whose top result is doc 7 (keyword-matched).
   - Assert `hits.first().docPath` matches expectations.

2. `HygieneIntegrationTest`:
   - Attempt to write a doc with a known injection pattern.
   - Verify `HebeException.Memory` is thrown.

3. Tag both `@Tag("integration")`. Run by default in CI; can be excluded locally with `-Dexclude=integration`.

### Tests / verification

- Both tests run from a clean checkout in CI.

### Acceptance criteria

- ✅ Integration tests pass against real SQLite + sqlite-vec.
- ✅ Documented as the milestone gate.
