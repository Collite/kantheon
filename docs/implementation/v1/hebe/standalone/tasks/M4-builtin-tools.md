# M4 — Built-in tools

Every v1 tool implementing the `Tool` interface, registered with `ToolRegistry`, exercising the dispatcher pipeline. Each tool has a unit test, a receipts entry on success, a `ToolResult.Err` on policy violation, and is enumerable via `hebe tool list`.

**Done when:** every v1 tool has a unit test, a receipts entry on success, a `ToolResult.Err` on policy violation, and `hebe tool list` enumerates them with their risk.

References: [`../v1-architecture.md`](../v1-architecture.md) §22; [`../v1-specs.md`](../v1-specs.md) §2.6.

> **Convention for every tool**:
> - Lives under `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/<group>/<Tool>.kt`.
> - JSON Schema for `args` written using `kotlinx-serialization` to keep it type-safe (`buildJsonObject { … }`).
> - `ToolSpec.name` is `snake_case`.
> - Unit tests at `modules/tools/builtin/src/test/kotlin/...`.
> - Risk + `requiresApproval` declared as documented in this file.
> - Sensitive-arg keys must be in the redactor's denylist (M3.T7).

---

## M4.T1 — `file_system` (read/write/list/glob)

**Status**: pending  
**Size**: M  
**Depends on**: M3.T2 (workspace boundary), M3.T10  
**Blocks**: M5.T6 (web memory browser uses this)

### Goal

A workspace-bounded filesystem tool. Markdown/json/yaml/html-aware metadata in responses. Splits read-only and write operations into separate tool entries so the autonomy gate works cleanly.

### Files to create

- `modules/tools/builtin/build.gradle.kts` (edit)
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemReadTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemListTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemGlobTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemWriteTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemAppendTool.kt`
- Tests

### Detailed work

1. Module deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:memory"))         // for WorkspaceFs
       implementation(project(":modules:security"))       // for ArgsRedactor
       implementation(project(":modules:observability"))
   }
   ```

2. **`FileSystemReadTool`** — `risk = Low`, `readOnly = true`, `pathScope = WorkspaceOnly`.

   ```kotlin
   class FileSystemReadTool(private val fs: WorkspaceFs) : Tool {
       override val spec = ToolSpec(
           name = "file_system_read",
           description = "Read a file from the workspace.",
           schema = jsonSchema {
               type = "object"
               required("path")
               property("path", "string", "Workspace-relative path")
               property("encoding", "string", "utf-8 (default) or base64")
           },
       )
       override val risk = RiskLevel.Low
       override fun isReadOnly() = true
       override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult { … }
   }
   ```

   Body: validate `path` (`WorkspacePath`); read; return `ToolResult.Ok(JsonObject(content + metadata))`. Markdown frontmatter parsing reuses `MarkdownInferrer` from M1.T4.

3. **`FileSystemListTool`** — `risk = Low`, `readOnly = true`. Returns array of names + sizes.

4. **`FileSystemGlobTool`** — `risk = Low`. Args: `pattern` (e.g. `**/*.md`). Returns matching paths.

5. **`FileSystemWriteTool`** — `risk = Medium`, `requiresApproval = false` at `Supervised`+ for new files in workspace. Hygiene-scan (M1.T12) the content before writing; on `Reject`, return `Err`. Writes are atomic (delegates to `WorkspaceFs.write`).

6. **`FileSystemAppendTool`** — `risk = Medium`. Same checks as write.

7. Register all five in the registry under `tools/dispatch`'s bootstrap (the wiring step happens in `cli-app` AppComponents — M9.T2 finalises).

### Tests / verification

- Read existing file → `Ok(content)`.
- Read traversal-attack path → `Err("path: outside workspace")`.
- Write triggers hygiene scan: a benign content writes; an injection sample is rejected.
- Glob with `**/*.md` returns expected paths.

### Acceptance criteria

- ✅ Five tools registered.
- ✅ Risk levels match this doc.
- ✅ Hygiene applied to writes.
- ✅ Receipts written on each call.

### Pitfalls

- Don't expose binary file content as raw bytes in JSON; the `encoding=base64` parameter handles binary; otherwise, refuse to read a non-text file.

### References

- `v1-specs.md` §2.6 (file_system)
- `v1-architecture.md` §6 (workspace)

---

## M4.T2 — `shell`

**Status**: pending  
**Size**: M  
**Depends on**: M3.T3 (command policy), M3.T10  
**Blocks**: M4.T8 (git push uses shell), M4.T10 (kubectl)

### Goal

Run a shell command via `ProcessBuilder`. Subprocess sandboxing is v2; v1 uses allow/deny lists + the validator + receipts.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/shell/ShellTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/shell/ProcessRunner.kt`
- Tests

### Detailed work

1. `ShellTool` — `risk = High`, `requiresApproval = true`.

   ```kotlin
   override val spec = ToolSpec(
       name = "shell",
       description = "Run a shell command.",
       schema = jsonSchema {
           required("cmd"); property("cmd", "string")
           property("cwd", "string"); property("timeout_ms", "integer")
       }
   )
   ```

2. `ProcessRunner.run(cmd, cwd, timeoutMs, env): ProcessResult`:
   - `ProcessBuilder` with `cmd` split via a basic shell-words parser (or pass to `bash -c` — pick one and document; `bash -c` is simpler but ties us to bash).
   - Capture stdout + stderr (cap at 1 MB; truncate with `[truncated]` marker).
   - Timeout default 60 s; max 600 s.
   - Register the `Process` with `EmergencyStop` (M3.T11) for cancellation.

3. Returns `Ok({stdout, stderr, exitCode})` on `exitCode == 0`, else `Err("exit ${code}: ${stderr.lastN}")`. Either way the receipt records the full args (redacted).

4. `cwd` defaults to the workspace root; the workspace-boundary validator (M3.T2) rejects paths outside.

### Tests / verification

- `cmd: "echo hello"` → `Ok` with stdout "hello\n".
- `cmd: "rm -rf /"` → `Deny` from validator.
- Timeout: a `cmd: "sleep 10"` with `timeout_ms: 100` returns `Err("timeout")`.

### Acceptance criteria

- ✅ Approval required at `Supervised`.
- ✅ Output capped + cancellable.
- ✅ Subprocess registered with EmergencyStop.

### Pitfalls

- `Process.destroy()` is not always immediate; `destroyForcibly()` after a 1-second grace.
- On Windows, `bash -c` requires WSL; use `cmd /C` instead. v1 may target macOS/Linux only — document.

### References

- `v1-specs.md` §2.6
- `v1-architecture.md` §11

---

## M4.T3 — `http`

**Status**: pending  
**Size**: M  
**Depends on**: M3.T4 (domain matcher), M3.T10  
**Blocks**: M4.T4 (web search), M4.T9 (github), M6.T4 (PluginHost.http())

### Goal

Generic HTTP client tool for RESTful APIs. Domain-allowlisted, SSRF-safe, with auth-header injection from secrets.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/http/HttpTool.kt`
- Tests

### Detailed work

1. `risk = Medium`. Args: `method` (GET/POST/PUT/DELETE/PATCH), `url`, `headers` (object), `body` (string|object|null), `secret_header_name` (optional), `secret_name` (optional — resolves from secrets and injects into header).

2. Uses Ktor client with the same `HttpClientFactory` as the LLM provider.

3. Auth: if `secret_name` and `secret_header_name` set, fetch via `ctx.secretLookup` and add as a header. The raw value never appears in args/receipts.

4. Returns `{status, headers, body}`. Body capped at 1 MB; encoded as text if `Content-Type` indicates text/JSON, else base64.

5. SSRF: leverages `DomainAllowlistValidator` from M3.T4; this tool just calls.

### Tests / verification

- GET allowed domain → `Ok`.
- GET disallowed domain → `Deny`.
- Secret injection: assert `secret_value` doesn't appear in receipts; `Authorization: Bearer …` header sent.

### Acceptance criteria

- ✅ All 5 verbs.
- ✅ Secret injection.
- ✅ Domain allowlist enforced.
- ✅ Body cap with truncation.

### References

- `v1-specs.md` §2.6
- `v1-architecture.md` §11

---

## M4.T4 — `web_search` (Brave + DuckDuckGo)

**Status**: pending  
**Size**: M  
**Depends on**: M4.T3  
**Blocks**: nothing

### Goal

`WebSearchProvider` trait + Brave (default with API key) + DuckDuckGo (free fallback). Closed in `hebe-brainstorming-responses.md` §1.10.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/WebSearchProvider.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/BraveSearchProvider.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/DuckDuckGoSearchProvider.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/WebSearchTool.kt`
- Tests with recorded fixtures

### Detailed work

1. Trait:

   ```kotlin
   interface WebSearchProvider {
       val name: String
       suspend fun search(query: String, k: Int = 10): List<SearchHit>
   }
   data class SearchHit(val title: String, val url: String, val snippet: String, val rank: Int)
   ```

2. `BraveSearchProvider`:
   - GET `https://api.search.brave.com/res/v1/web/search?q=…&count=K`.
   - Header `X-Subscription-Token: ${BRAVE_API_KEY}` (resolved from secrets).
   - Parse `web.results[].{title, url, description}`.

3. `DuckDuckGoSearchProvider`:
   - GET `https://html.duckduckgo.com/html?q=…` (no API key needed).
   - HTML scrape: `.result__title a` for title/url, `.result__snippet` for snippet.
   - Marked as best-effort; documented in the tool's description.

4. `WebSearchTool` (`risk = Low`):
   - Args: `query` (required), `k` (default 10).
   - Selects provider: Brave if `brave_api_key` secret present, else DuckDuckGo.
   - Returns `[{title, url, snippet, rank, source}]`.

### Tests / verification

- Recorded fixture for each provider.
- Provider selection: Brave key present → Brave; absent → DDG.
- Empty result → empty array, not error.

### Acceptance criteria

- ✅ Both providers implemented.
- ✅ Auto-selection works.
- ✅ Recorded-fixture tests stable.

### Pitfalls

- DuckDuckGo HTML can change shape; the test should be flexible (extract `<a class="result__a">` etc. and tolerate minor DOM drift).
- Brave requires a paid API tier above free quotas; document the tier in the setup guide.

### References

- `hebe-brainstorming-responses.md` §1.10

---

## M4.T5 — `memory_search`, `memory_read`, `memory_write`, `memory_tree`

**Status**: pending  
**Size**: M  
**Depends on**: M1.T10, M1.T4, M3.T10  
**Blocks**: M5.T6 (web memory browser)

### Goal

Tool surface around `MemoryStore` so the agent can do hybrid search, read docs, write to memory (with hygiene), and inspect the workspace tree.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemorySearchTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryReadTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryWriteTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryTreeTool.kt`
- Tests

### Detailed work

1. `memory_search` — `risk = Low`, `readOnly = true`. Args: `query`, `k` (default 10), `categories` (optional). Returns hits per `MemoryHit`.

2. `memory_read` — `risk = Low`, `readOnly = true`. Args: `path`. Returns `{path, content, ts, scope, category}`.

3. `memory_write` — `risk = Medium`. Args: `path`, `content`, `category` (optional). Calls `MemoryStore.appendDoc`; hygiene scanner blocks injection samples.

4. `memory_tree` — `risk = Low`, `readOnly = true`. Args: `prefix` (optional). Returns nested structure of paths.

### Tests / verification

- Search returns hydrated snippets.
- Write hygiene: injection sample → `Err`.
- Tree under `daily/` → returns dated files.

### Acceptance criteria

- ✅ Four tools registered.
- ✅ Hygiene wired on writes.

### References

- `v1-architecture.md` §13

---

## M4.T6 — `wiki_read`, `wiki_write`

**Status**: pending  
**Size**: S  
**Depends on**: M4.T5  
**Blocks**: nothing

### Goal

A convention layer over `file_system` and `memory_*` for "wiki-style" notes under `workspace/wiki/`. Adds links + auto-`title`-from-`H1` behaviour. v1 keeps it minimal; the heavy lifting happens via `file_system`.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/wiki/WikiReadTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/wiki/WikiWriteTool.kt`
- Tests

### Detailed work

1. `wiki_read` — `risk = Low`. Args: `slug` (e.g. `architecture`). Maps to `workspace/wiki/<slug>.md`. Returns content + parsed `[[wikilinks]]` as outgoing-link list.

2. `wiki_write` — `risk = Medium`. Args: `slug`, `content`. Writes through `MemoryStore.appendDoc(path = "wiki/<slug>.md", category = Document)`.

3. Wikilinks: `[[Other Page]]` syntax — parsed but resolution (cross-references) is informational only in v1.

### Tests / verification

- Wiki round-trip.
- Wikilink extraction.

### Acceptance criteria

- ✅ Two tools registered.
- ✅ Convention enforced (paths under `wiki/`).

### References

- `v1-specs.md` §2.6

---

## M4.T7 — `git` (JGit)

**Status**: pending  
**Size**: M  
**Depends on**: M3.T10  
**Blocks**: M4.T8 (push uses shell)

### Goal

In-process Git operations via JGit: clone, status, diff, log, branch, commit. **Push is a separate tool** (M4.T8) using shell-out so SSH/credential helpers work without re-implementing them.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/git/GitTool.kt` (multi-verb)
- Tests against a temp Git repo

### Detailed work

1. Deps:

   ```kotlin
   implementation(libs.jgit)
   ```

2. Single tool with a `verb` arg:

   ```
   git { verb: clone | status | diff | log | branch | commit, … }
   ```

   - `clone`: `risk = High`, `requiresApproval = true`. Args: `url`, `dir`. `pathScope = ConfiguredRoots`.
   - `status`, `diff`, `log`, `branch list`: `risk = Low`, `readOnly = true`.
   - `commit`, `branch create`: `risk = Medium`. Args: `message`, `paths` (optional).

3. JGit API: `Git.open(repo)`, `git.status().call()`, `git.diff().call()`, `git.log().call()`, `git.branchList().call()`, `git.commit().setMessage(...).call()`.

### Tests / verification

- Init temp repo → status returns clean.
- Add a file via `file_system` → status shows it untracked.
- Commit → log includes the new commit.

### Acceptance criteria

- ✅ Six verbs.
- ✅ Read-only verbs are Low.
- ✅ Write verbs are Medium with approval.

### References

- `v1-specs.md` §2.6

---

## M4.T8 — `git push` (shell-out)

**Status**: pending  
**Size**: S  
**Depends on**: M4.T2, M4.T7  
**Blocks**: nothing

### Goal

A separate tool because push needs the user's credential helpers (`osxkeychain`, `gpg`, etc.) which JGit doesn't always cover. Always-approve.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/git/GitPushTool.kt`
- Tests

### Detailed work

1. `risk = High`, `requiresApproval = true`. Args: `dir` (repo path), `remote` (default `origin`), `branch` (default current).

2. Uses `ShellTool`'s `ProcessRunner` to invoke `git push <remote> <branch>`. Captures stdout/stderr.

3. Validation: `dir` must be a Git repo (delegate to JGit `RepositoryBuilder.findGitDir`).

### Tests / verification

- Push to a local bare repo → succeeds.
- Push to non-existent remote → `Err`.

### Acceptance criteria

- ✅ Always-approve.
- ✅ Subprocess registered with EmergencyStop.

### References

- `v1-specs.md` §2.6

---

## M4.T9 — `github`

**Status**: pending  
**Size**: M  
**Depends on**: M4.T3, M2.T15 (auth-mode)  
**Blocks**: nothing

### Goal

GitHub API client for issues, PRs, repo metadata. PAT in secrets store; first use triggers auth-mode if no PAT present.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/github/GitHubTool.kt`
- Tests with recorded fixtures

### Detailed work

1. `risk = Medium` for reads, `High + requiresApproval` for writes. Verbs: `issue list/get/create/comment`, `pr list/get/create/merge`, `repo get`.

2. PAT lookup: `ctx.secretLookup("github_pat")`. If null, call `ask_user(purpose="credential", secretName="github_pat")` and return `Pending`.

3. Implementation: thin Ktor client over `https://api.github.com`. Uses `http` tool's domain-allowlist (so `*.github.com` must be in the allowlist).

### Tests / verification

- Recorded fixtures for each verb.
- Missing-PAT path enters auth-mode.

### Acceptance criteria

- ✅ At least 6 verbs.
- ✅ PAT auth-mode handled.
- ✅ Reads/writes risk-tagged correctly.

### Cut-line note

If schedule slips, this tool can defer to v1.1 (per `v1-tasks.md` cut lines). Keep it isolated so dropping it doesn't ripple.

### References

- `v1-specs.md` §2.6

---

## M4.T10 — `kubectl`

**Status**: pending  
**Size**: M  
**Depends on**: M4.T2  
**Blocks**: nothing

### Goal

Wraps `kubectl`. Read-only verbs Medium; mutating verbs High + always-approve, per `v1-architecture.md` §11.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/k8s/KubectlTool.kt`
- Tests

### Detailed work

1. Args: `verb` (string), `args` (array), `kubeconfig` (optional path), `context` (optional).

2. Verb classification:
   - **Read-only (Medium)**: `get`, `describe`, `logs`, `top`, `events`, `version`, `config view`, `auth can-i`.
   - **Mutating (High + always-approve)**: `apply`, `create`, `delete`, `patch`, `replace`, `scale`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`, `annotate`, `exec`, `port-forward`, `set`.

3. Verb classification cached (`ENUMS` const). Unknown verbs → default High.

4. Builds `kubectl <verb> <args>` and runs via `ProcessRunner`. Output captured.

### Tests / verification

- `kubectl get pods` allowed at Supervised.
- `kubectl delete pod x` always asks for approval.
- Unknown verb → High.

### Acceptance criteria

- ✅ Verb table covers all common kubectl subcommands.
- ✅ Mutating verbs always require approval.

### References

- `v1-specs.md` §2.6
- `v1-architecture.md` §11

---

## M4.T11 — `ask_user`

**Status**: pending  
**Size**: S  
**Depends on**: M2.T13  
**Blocks**: M4.T9 (github uses ask_user for PAT)

### Goal

Sends a clarifying question via the originating channel; the next inbound from the operator becomes the answer (or — for `purpose="credential"` — auth-mode kicks in via M2.T15).

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/ask/AskUserTool.kt`
- Tests

### Detailed work

1. `risk = Low`. Args: `question` (string), `purpose` (optional: `"general" | "credential"`), `secretName` (required if `purpose=credential`).

2. Mechanism: returns `ToolResult.NeedsApproval(prompt = question, payload = {purpose, secretName})`. The dispatcher routes through `ApprovalGate` which delivers the prompt via the originating channel; the operator's reply resumes.

3. For `purpose=credential`: the channel adapter sets `metadata.authMode = true` on the inbound that follows. `SubmissionParser` (M2.T4) routes that as `Submission.AuthMode`, which `HebeAgent` (M2.T13) routes through `M2.T15` to store + resume.

### Tests / verification

- Ask + reply round-trip.
- Ask with `purpose=credential` → auth-mode → secret stored.

### Acceptance criteria

- ✅ Sends question via originating channel.
- ✅ Resumes on reply.
- ✅ Credential path stores in secrets.

### References

- `v1-specs.md` §2.6

---

## M4.T12 — `schedule`

**Status**: pending  
**Size**: S  
**Depends on**: M1.T2  
**Blocks**: M8.T3 (the engine reads `routines`)

### Goal

A tool that creates `routines` rows. Tool exists in v1 even though the routines engine lands in M8 — it's just CRUD.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/schedule/ScheduleTool.kt`
- Tests

### Detailed work

1. `risk = Medium`. Verbs: `create`, `list`, `disable`, `enable`, `delete`. Args vary by verb.

2. `create` args: `name`, `cron`, `body_kind` (`skill | tool`), `body_ref` (skill name or tool name), `body_json` (args object).

3. Validates `cron` against the parser when M8.T1 lands; for now, regex-validate as in M0.T8.

### Tests / verification

- Create + list + disable + delete round-trips against a temp DB.

### Acceptance criteria

- ✅ All verbs.
- ✅ Cron validation.

### References

- `v1-architecture.md` §5 (routines table)

---

## M4.T13 — `job_create`, `job_status`, `job_cancel`

**Status**: pending  
**Size**: M  
**Depends on**: M1.T2, M2.T12  
**Blocks**: M8.T2

### Goal

CRUD on the `jobs` table from the agent's perspective. Real execution happens in M8; this task wires the table interface.

### Files to create

- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/jobs/JobCreateTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/jobs/JobStatusTool.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/jobs/JobCancelTool.kt`
- Tests

### Detailed work

1. `job_create` (`risk = Low`): args `kind` (`adhoc | routine | maintenance | heartbeat`), `payload`. Inserts row with `status=pending`. Returns `id`.

2. `job_status` (`risk = Low`, `readOnly = true`): args `id`. Returns `{id, status, started_at, ended_at, result_json}`.

3. `job_cancel` (`risk = Medium`): sets `status=cancelled` if `pending`; if `running`, sends a cancel signal that M8.T2's loop reads. v1: cooperative cancellation (the running job may take up to 60 s to notice).

### Tests / verification

- Create → status `pending`. After M8 lands, status will progress to `running → done`. For now, assert just the CRUD.
- Cancel pending → status `cancelled`.

### Acceptance criteria

- ✅ Three tools registered.
- ✅ CRUD on `jobs` table.
- ✅ Cancel signal documented.

### References

- `v1-architecture.md` §5
