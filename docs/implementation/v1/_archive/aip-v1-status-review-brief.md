# AIP v1 Status Review — Brief for Claude Code

> **Audience:** Claude Code, running against the `ai-platform` repository at `/Users/bora/Dev/ai-platform`.
>
> **Output:** a single Markdown report saved to `/Users/bora/Dev/kantheon/docs/v1/aip-v1-status-report-<DATE>.md`.
>
> **Style:** dispassionate inventory. Bias toward "what's actually in the code right now" over "what the doc says should be there." Where reality and doc diverge, the report should call that out — that drift is a primary discovery target.

---

## 0. Mission

The Kantheon agent constellation (Iris, Themis, Pythia, Golem) depends on capabilities that live in `ai-platform`. Most of the Kantheon design assumes those capabilities work as documented. **Before any Kantheon-side implementation kicks off, we need an honest snapshot of where `ai-platform` actually is** — what's shipped, what's WIP, what's planned, what's documented-but-not-built, what's drifted from doc.

The result drives two follow-up decisions:

1. The Kantheon roadmap (sequencing of Iris BFF / Themis extraction / Golem rewrite) — what can we count on, what needs to be built or worked around first?
2. The `aip-v1-impl` distribution doc — Bora has parked a "review and re-assess" of the existing roadmap in `/Users/bora/Dev/golem/docs/aip-v1-impl/`. This status report is the input.

You are **not** designing the future. You are **inventorying the present**.

---

## 1. Working context

### Repos involved

- **Primary:** `/Users/bora/Dev/ai-platform` — the repo under review. **Run from here.**
- **Secondary reference:** `/Users/bora/Dev/kantheon/docs/v1/` — the design docs that describe what Kantheon expects from ai-platform. Read the relevant sections to understand what to verify against.
- **Tertiary:** `/Users/bora/Dev/golem/docs/aip-v1/` — the V1-era requirements doc (`Analytical Agent on V1.md`) which enumerates platform gaps G1–G7. Some are now closed; verify which.

### Reading list before starting (in this order, ~30 min total)

1. `ai-platform/CLAUDE.md` — repo conventions (build commands, structure rules, Kotlin/Python patterns, just task runner, Jib for Kotlin services, Docker for Python).
2. `ai-platform/AGENTS.md` — agent conventions.
3. `ai-platform/docs/v1/v1-architecture.md` — V1 platform architecture as designed.
4. `ai-platform/docs/v1/requirements.md` — platform requirements.
5. `ai-platform/resolver-design.md` + `ai-platform/tasks-resolver-stage-01-infra-nlp.md` through `tasks-resolver-stage-06-consumer-migration.md` — what's planned for the Resolver / Themis path.
6. `ai-platform/progress-stage-04.md` and `ai-platform/fwd-stage-04.md` — these filenames suggest in-flight Stage 04 progress notes; read them.
7. `kantheon/docs/v1/kantheon-architecture.md` §10 ("Cross-repo coupling with ai-platform") + the per-agent design docs' "depends on" sections — what Kantheon assumes ai-platform provides.
8. `golem/docs/aip-v1/Analytical Agent on V1.md` — §4 (requirement traceability) and §5 (G1–G7 platform gaps).

### What you have access to

- File system: full read access to all three repos.
- Shell: run any read-only command (git log, find, grep, build inspection). **Do not modify code in ai-platform.** Write only the report and your scratch notes.
- Network: WebFetch / WebSearch are available if you need to look up library versions or external docs, but most answers should come from the code.

---

## 2. What to check — service inventory

### 2.1 Service inventory by directory

For **every** directory under `ai-platform/services/`, `ai-platform/tools/`, `ai-platform/infra/`, and `ai-platform/agents/`, produce a row in the inventory table. Known directories as of this brief:

- `services/`: `dispatcher`, `erp-sql`, `erp-sql-dispatcher`, `fuzzy-matcher`, `query-runner`, `sql-entity-service`, `sql-formatter`, `sql-free-service`, `sql-named-service`, `sql-pattern-service`, `translator`, `validator`.
- `tools/`: `erp-data-mcp`, `fuzzy-mcp`, `meta-mcp`, `nlp-mcp`, `query-mcp`.
- `infra/`: `backstage`, `health-check-service`, `llm-gateway`, `metadata`, `nlp`, `sql-metadata`, `sql-security`, `sql-validator`, `starters`, `whois`.
- `agents/`: `erp-agent`, `erp-agent-2`, `office-agent`, `resolver`.

For **each module**, capture:

| Column | What to find |
|---|---|
| Module path | e.g. `services/fuzzy-matcher` |
| Language / stack | Kotlin+Ktor / Kotlin+Spring Boot / Python+FastAPI / other |
| Role (one line) | What it does, drawn from its README or top-level source. |
| Status | `implemented` / `partial` / `scaffolded` / `not started` / `archived`. Heuristics below. |
| Has K8s manifests? | `deployment/k8s/...` or `<module>/k8s/{base,overlays/local}/` present? |
| In `justfile`? | Is there a recipe targeting this module (`just deploy-kt <name>` / `just deploy-py <name>`)? |
| Tests present? | Unit / integration tests directory exists and contains tests? |
| Recent activity | Date of last commit touching this directory (from `git log -1 --format=%cd -- <path>`). |
| Notes | Anything that doesn't fit the columns — TODO markers, in-progress branch names, etc. |

**Status heuristics** (apply in order; first that matches wins):

- `archived` — directory contains a stub README pointing elsewhere, or contains `.gitkeep` only, or all source files are 0-byte or last modified > 1 year ago with no recent commits.
- `not started` — directory exists but has only README / build files, no `src/main/...` or equivalent.
- `scaffolded` — has the framework code (Ktor app entry, FastAPI app, etc.) but the core domain logic is empty / TODOs / placeholders.
- `partial` — domain logic exists and runs, but documented features are missing (compare against the module's own README or the relevant Kantheon design doc's expectations).
- `implemented` — feature-complete vs documented scope; tests + K8s manifests + justfile recipe present.

### 2.2 Specific capabilities Kantheon depends on

For each of these, dig deeper than a directory inventory — verify the *capability* is real, not just the directory.

#### `tools/query-mcp`

Pythia §6.1 expects: `compile`, `query`, TransDSL stackable composition, `pipeline_warnings` in structured response.

Check:
- [ ] `compile` MCP tool exposed?
- [ ] `query` MCP tool exposed?
- [ ] TransDSL stack composition (Filter / Project / Sort over a `queryRef` core)?
- [ ] `pipeline_warnings` in the structured response (this is G7 from the AA-on-V1 doc)?
- [ ] Sticky-routing infrastructure for session DFs (Pythia §6.1 expects this for Polars Worker integration)?

Concrete signals: search proto definitions in `shared/proto/`, REST/MCP endpoint definitions in the module source, integration tests that exercise these.

#### `tools/meta-mcp`

> Note on naming: Kantheon docs call this `metadata-mcp` but ai-platform's directory is `meta-mcp`. Flag any other naming drift you find.

Pythia §6.1 expects: entity / attribute / relation lookup; `list_queries`; query catalog with `label_cs`, `label_en`, `chip_example_values`; eventually `cnc.role` schema (G2).

Check:
- [ ] `list_queries(kind="named")` returns query catalog with localised labels?
- [ ] `get_entity(<entityId>)` returns entity definition?
- [ ] `cnc.role` schema support (G2)?
- [ ] `value_labels` on Model attributes (G4)?
- [ ] `display_label` on Model attributes (G5)?

#### `tools/fuzzy-mcp` + `services/fuzzy-matcher`

Pythia §6.1 expects: Czech-aware matching with NFD diacritic-fold + inflection trim + Levenshtein composition (G1).

Check:
- [ ] Czech tokenization / NFD diacritic-fold pipeline implemented?
- [ ] Inflection trimming present?
- [ ] Levenshtein matching exposed?
- [ ] What languages other than Czech are supported (English fallback, Slovak, etc.)?
- [ ] What's the namespace API (Themis design assumes `fuzzyMatcherNamespace` per `EntityTypeSpec`)?

#### `infra/llm-gateway`

Pythia §6.1 expects: tier-based routing (modality × tier), embeddings endpoint, pricing API, Redis-backed cache.

Check:
- [ ] Modality × tier routing API (`(CHAT, STRONG, task_kind)` style)?
- [ ] Embeddings endpoint exposed?
- [ ] Pricing API or `cached: bool` flag on responses (Pythia's Budget Tracker needs this)?
- [ ] Redis cache wired up?
- [ ] Which model vendors are configured (Anthropic, OpenAI, Azure)?
- [ ] Spring Boot framework? (the `bora_jvm_stack` memory notes this is the one Spring service)

#### `services/sql-formatter` (or wherever `data-formatter` lives)

Pythia §6.1 expects: table rendering (markdown / csv / tsv / json) with localisation, `value_labels`, `hide_columns_matching` (G3).

Check:
- [ ] Where does `data-formatter` actually live? (services/sql-formatter? a library? embedded in query-mcp?)
- [ ] `hide_columns_matching` parameter (G3)?
- [ ] Markdown / CSV / TSV / JSON output modes?
- [ ] Localised column headers and value labels?

#### `infra/sql-security` and `infra/sql-validator`

Pythia §6.1 expects: row-level security wrapping (sql-security), SQL validation (sql-validator). Both transparent to Pythia — query-mcp uses them internally.

Check:
- [ ] sql-security implemented?
- [ ] sql-validator implemented?
- [ ] Both invoked from query-mcp's pipeline?

#### `services/dispatcher` + `services/query-runner` (Worker layer)

Pythia §6.1 expects: query execution against MS SQL today; Polars Worker with session DataFrame management is Phase 2.2.

Check:
- [ ] mssql Worker present?
- [ ] Polars Worker — present? scaffolded? not started?
- [ ] Sticky-routing on the Dispatcher (data locality for session DFs)?
- [ ] Session-scoped DataFrame management (does `query-mcp` propagate a `session_id`)?

#### `infra/nlp` + `tools/nlp-mcp` (Themis prerequisite)

The Resolver Stage 01 / Stage 02 deliverables.

Check:
- [ ] `infra/nlp` exists with FastAPI + engine plugin system (`NlpEngine` protocol)?
- [ ] Which engines are wired? (Stanza? spaCy? NameTag via UFAL HTTP? langid? MorphoDiTa?)
- [ ] NORMAL mode operational?
- [ ] COMPARE mode (Stage 03)?
- [ ] `tools/nlp-mcp` — Kotlin/Ktor thin wrapper present?
- [ ] Trace propagation Python ↔ Kotlin verified anywhere?

#### `agents/resolver` (Themis itself)

Where Stage 04's Koog graph lands.

Check:
- [ ] Directory exists? What's in it — scaffolded, partial, in-flight?
- [ ] Koog dependency wired in `gradle/libs.versions.toml`?
- [ ] Any nodes implemented (`detectLang+parse`, `extractUniversal`, etc.)?
- [ ] Proto package `cz.dfpartner.resolver.v1` present in `shared/proto/`?
- [ ] Read `ai-platform/progress-stage-04.md` and `ai-platform/fwd-stage-04.md` for in-flight progress notes.

#### `agents/erp-agent`, `agents/erp-agent-2`, `agents/office-agent`

Background context — these are pre-Themis agents. Need to understand their role and status.

Check:
- [ ] What does each do? (best-effort from README / source)
- [ ] Which is the "current Golem" referenced in `/Users/bora/Dev/golem/`? (Likely `erp-agent-2` per Kantheon docs, but verify.)
- [ ] Status of `office-agent` (mentioned nowhere in Kantheon docs — what is it?).

#### Persistence + transport layer

Pythia §6.1 expects: Seaweed (Arrow IPC blob storage), Redis (hot blobs + LLM cache), NATS JetStream (event streaming), Postgres (structured state).

Check, for each:
- [ ] Is the infrastructure declared in `deployment/` (K8s manifests, ArgoCD, etc.)?
- [ ] Is there a Kotlin / Python client library that other services use to talk to it?
- [ ] If used in production: which services are wired to which?

#### `infra/whois` + Keycloak

Pythia §6.1 expects: identity propagation through query-mcp; user-scoped row security.

Check:
- [ ] `infra/whois` — Keycloak-to-ERP-user mapping service. Implemented?
- [ ] Keycloak deployment present in `deployment/`?
- [ ] How does auth flow from caller → query-mcp → ERP today?

---

## 3. What to check — gap status (G1–G7)

The `golem/docs/aip-v1/Analytical Agent on V1.md` enumerates platform gaps G1–G7. For each, determine current status: `open` / `partial` / `closed` / `obsolete-or-renamed`.

Read the AA-on-V1 doc's §5 ("Requirements for Platform V1") to get the precise definition of each. Then verify in code:

- **G1** — Czech-aware fuzzy matching in `fuzzy-mcp` (NFD diacritic-fold + inflection trim + Levenshtein).
- **G2** — `cnc.role` schema in the Model / metadata-mcp.
- **G3** — `hide_columns_matching` parameter in `data-formatter`.
- **G4** — `value_labels` on Model attributes (code-list values rendered as labels).
- **G5** — `display_label` on Model attributes (entity names in localised display form).
- **G6** — read the doc; capture what G6 is.
- **G7** — `pipeline_warnings` surfaced through `query-mcp.structuredContent`.

For each, capture in the report: status, where the implementation is (file path), what's missing if partial, and whether it's blocking any Kantheon work.

---

## 4. What to check — Resolver / Themis progress

Resolver Stages 01–04 are the critical-path gate for everything Kantheon-side. The status of each is the most operationally important section of this review.

For each of the six Resolver stages (01–06):

- Read the corresponding `ai-platform/tasks-resolver-stage-NN-*.md` to understand what was planned.
- Verify against code: which task-list checkboxes can be marked done?
- Identify in-flight branches (`git branch -a | grep -i resolver` or similar).
- Read `progress-stage-04.md` and `fwd-stage-04.md` for any progress / blocker notes Bora wrote down.

Report shape per stage:

| Stage | % complete (rough) | What's done | What's in flight | What's blocked / not started | Estimated remaining work (if knowable) |
|---|---|---|---|---|---|
| 01 — `infra/nlp` foundation | ?? | … | … | … | … |
| 02 — `nlp-mcp` wrapper | ?? | … | … | … | … |
| 03 — eval + COMPARE + MorphoDiTa | ?? | … | … | … | … |
| 04 — Resolver agent (Koog) | ?? | … | … | … | … |
| 05 — parallel deployment | not started | — | — | reframe pending under kantheon split | — |
| 06 — consumer migration | not started | — | — | reframe pending | — |

**% complete** is approximate — based on the proportion of task-list checkboxes that look done from code. Honest "no idea" is fine where it's hard to tell.

---

## 5. What to check — build / CI / Maven publishing

Kantheon's design (`kantheon/docs/v1/kantheon-architecture.md` §10) assumes ai-platform publishes `shared/proto` and `shared/libs/kotlin/*` as versioned Maven artifacts. This is a hard prerequisite.

Check:

- [ ] **Maven publishing infrastructure.** Is `shared/proto` configured to publish? (Look in `shared/proto/build.gradle.kts` for `maven-publish` plugin, publishing block, repository URL.)
- [ ] **What's the target Maven repo?** Artifactory / GitHub Packages / OSS Sonatype / private? Look for repository URLs in build configs.
- [ ] **Are there CI workflows that publish on release tags?** Check `.github/workflows/` and any release-related scripts.
- [ ] **`shared/libs/kotlin/*` modules** — which exist (`otel-config`, `fuzzy-common`, `mcp-server-base` per Kantheon docs; possibly others)? Each is a publishable artifact?
- [ ] **Build-convention plugins** — `id("my.kotlin-ktor")` and `id("my.kotlin-spring")` are referenced in `CLAUDE.md`. Where are they defined? Are they publishable / consumable by Kantheon?
- [ ] **`justfile` accuracy** — does the documented just command set in `CLAUDE.md` match what the `justfile` actually offers? Any drift?
- [ ] **`just init` runnable?** (Don't actually run — just verify the recipe is sensible.)
- [ ] **`just proto-all` runnable?** Generates Kotlin + Python + JS bindings?

---

## 6. What to check — cross-cutting infrastructure

Quick coverage — one or two sentences each unless something's broken.

- **OpenTelemetry plumbing.** `shared/libs/kotlin/otel-config` + `shared/libs/python/otel-config` per Kantheon docs. Confirm they exist and which services consume them. Alloy → Tempo / Prometheus / Loki stack present in `deployment/`?
- **Backstage catalog.** `infra/backstage` exists. Catalog entries (`catalog-info.yaml`) per service — how many services have them? Which don't?
- **ArgoCD app-of-apps.** Pattern from `CLAUDE.md`. Verify `deployment/` has the structure.
- **Local K3s + `just debug-tunnel`.** Forwards which services to localhost? Confirm DB and Wiremock are in the list.
- **Database migrations.** Flyway (per `CLAUDE.md`) — which services have migrations?
- **`local.env` + per-service `.secrets.env`.** Local-dev secrets pattern — captured in `CLAUDE.md`. Any obvious drift?

---

## 7. What to check — drift between doc and code

This is where the most valuable discoveries usually hide.

- **`docs/v1/v1-architecture.md` vs reality.** Find at least three statements in the architecture doc that are no longer accurate vs the code (renamed services, deferred-but-now-built features, built-but-now-deferred features). Don't fabricate — only report drift you can prove.
- **`resolver-design.md` vs `agents/resolver/` directory.** What's in the design that hasn't been built? What's been built differently from the design?
- **Pythia §6.1 dependency table (in `kantheon/docs/v1/pythia/Pythia-v1-Design.md`) vs reality.** Each entry there asserts what Pythia uses ai-platform for. Which assertions are wrong?
- **G1–G7 status vs what `Analytical Agent on V1.md` claimed** (some gaps may have been closed since that doc was written, or new gaps may have surfaced).
- **Resolver Stage 04 task list vs `progress-stage-04.md` / `fwd-stage-04.md`.** Which tasks are progressed vs what the task doc shows?

---

## 8. Report format

Save to `/Users/bora/Dev/kantheon/docs/v1/aip-v1-status-report-<YYYY-MM-DD>.md` (use today's date). Use this exact structure:

```markdown
# AI Platform v1 — Status Report

**Date:** YYYY-MM-DD
**Reviewer:** Claude Code
**Repo head:** `git rev-parse HEAD` (in ai-platform)
**Branch:** `git branch --show-current`

## TL;DR

[3–5 bullets. The most important things Bora needs to know. Examples:
- "Resolver Stage 04 is X% done; the Koog graph nodes A/B/C are scaffolded, D/E/F not started."
- "G1 (Czech fuzzy) is closed; implementation in services/fuzzy-matcher/.../CzechAnalyzer.kt."
- "shared/proto is NOT set up for Maven publishing; this blocks Kantheon's cross-repo proto consumption."
- "v1-architecture.md drift: services X and Y were renamed, see §7."
Be specific — no "looks roughly complete," instead "X commits in last 30 days, primary classes implemented, integration tests passing."]

## 1. Service inventory

[Table per §2.1. One row per module across services/, tools/, infra/, agents/. Sorted alphabetically by path.]

## 2. Capability deep-dives

[One subsection per capability covered in §2.2: query-mcp, meta-mcp, fuzzy-mcp, llm-gateway, data-formatter, sql-security/validator, Worker layer, infra/nlp + nlp-mcp, agents/resolver, persistence/transport, identity/auth. For each: what's there, what's missing, blocking-or-not for Kantheon.]

## 3. Gap status (G1–G7)

[Table — Gap, status, location-in-code, blocking-for-Kantheon, notes.]

## 4. Resolver progress

[Table per §4 plus narrative paragraph per stage if there are interesting details. Cross-reference progress-stage-04.md and fwd-stage-04.md findings.]

## 5. Build / CI / Maven publishing readiness

[Yes/no checklist per §5 plus narrative on what would need to change for Kantheon's Maven-from-ai-platform consumption to work today.]

## 6. Cross-cutting infrastructure

[Short paragraphs per §6.]

## 7. Doc-vs-code drift

[Itemised list of drifts found. Each: which doc, what it says, what's actually true, why it matters.]

## 8. Risks and unknowns

[Things that couldn't be determined from the code alone — point Bora at what to investigate further. Examples:
- "Spring Boot version of llm-gateway not pinned in libs.versions.toml — couldn't verify it's current."
- "No evidence of integration tests for fuzzy-mcp Czech path — might be tested manually."
- "Several services have no recent commits but it's unclear if they're abandoned or stable."]

## 9. Recommended next steps for Bora

[3–7 concrete actions, ordered by priority. Examples:
- "Set up Maven publishing for shared/proto + shared/libs/kotlin/* — blocks Kantheon bootstrap."
- "Close out G7 (pipeline_warnings) — referenced by Themis Stage 4.5 routing eval."
- "Decide what to do with office-agent — directory exists but undocumented; possibly archive."
- "Resolver Stage 04 currently estimates remaining: X weeks of work."]

## Appendix A — commands run

[Optional: list the shell commands used during the review for reproducibility.]

## Appendix B — files read

[Optional: list of key files inspected.]
```

---

## 9. How long this should take

Rough budget:
- Reading list: 30 min.
- Service inventory (§2.1): 1–2 hours.
- Capability deep-dives (§2.2): 2–3 hours.
- Gap status (§3): 30 min once you know the codebase.
- Resolver progress (§4): 1 hour.
- Build / CI / Maven (§5): 30–60 min.
- Cross-cutting (§6): 30 min.
- Drift (§7): 30–60 min.
- Writing the report: 60–90 min.

**Total: half a day to a full day.** If you're spending dramatically more, you're either going too deep (focus on the table-fillable signals, not theory) or the codebase has more drift than expected (in which case, more is better — note it).

---

## 10. Style notes for the report

- **Be specific.** File paths, class names, commit dates, line counts. Avoid "looks complete" / "seems to be." If you can't tell, say "no evidence either way" or "couldn't determine."
- **Don't editorialise.** If something looks abandoned, say "no commits in 9 months, no K8s manifests, no tests" — don't say "this is dead code." Bora gets to decide.
- **Don't recommend code changes.** Recommendations belong in §9, scoped to "decisions Bora needs to make" or "infrastructure Bora needs to set up." Not "rewrite this class."
- **Prefer signals over guesses.** "X has 3,200 lines of Kotlin across 28 files, with 14 unit tests" is more useful than "X looks substantial."
- **Use tables for inventories, prose for analysis.** §1, §3, §4, §5 are tables. §2, §7, §8, §9 are prose-with-bullets.
- **Don't fabricate.** If you can't find something, say so. False positives in a status report cost more than missing items.

---

## 11. What's explicitly out of scope

- **Don't propose Kantheon design changes.** This review is about ai-platform status; Kantheon design is locked.
- **Don't run tests, builds, or services.** Read-only inspection. No `just deploy-kt` runs, no `pytest`, no test containers.
- **Don't modify any code in ai-platform.** Read-only.
- **Don't dig into the legacy `golem` repo.** That's a separate review (and it's a different problem — golem is the consumer, not the platform).
- **Don't try to estimate "how long until ai-platform is done."** Out of scope; we want a snapshot, not a forecast.
- **Don't propose new architecture for ai-platform.** This is an inventory.
- **Don't audit code quality** (security, style, architecture-fit). Just status.

---

## 12. When you're done

Write the report at the path in §8. Mention in chat that you've completed the review with a one-paragraph summary of the most important finding. **Do not** narrate the review as you go — generate the report, save it, summarize once at the end.

If you discover a blocking unknown that needs Bora's input mid-review (e.g. "Which Maven repo is supposed to host the artifacts?"), capture it in the report's §8 "Risks and unknowns" rather than stalling.
