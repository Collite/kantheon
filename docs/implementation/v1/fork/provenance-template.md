# Forked modules — README header template

> **Status:** convention template, written 2026-06-12 with the platform fork (Phase 1 Stage 1.1, T2).
> Owner: [`docs/architecture/fork/architecture.md`](../../../architecture/fork/architecture.md) §1.

Every module in kantheon that originates in `ai-platform` (anywhere under `services/`, `workers/`, `tools/`, `shared/libs/`) must carry this header at the top of its `README.md`, verbatim except for the three placeholders:

```markdown
# <module name>

> **forked-from:** `ai-platform@<sha>` (`<original path>`), tag `kantheon-fork-point`, forked <YYYY-MM-DD>.
> Maintained independently since the fork; do not assume parity with the ai-platform original.
```

**Placeholders to substitute:**

| Placeholder   | Source                                                                                                |
|---------------|-------------------------------------------------------------------------------------------------------|
| `<sha>`       | The ai-platform commit SHA at which the tag `kantheon-fork-point` was set (currently `2575b923dca521fea0e3156257e4b779f02a6ed4`; recorded in [`docs/architecture/fork/architecture.md`](../../../architecture/fork/architecture.md) §1). |
| `<original path>` | The path of the source module inside `ai-platform/` (e.g. `services/query-runner`, `tools/query-mcp`, `infra/metadata`, `shared/libs/kotlin/otel-config`). |
| `<YYYY-MM-DD>` | The calendar date the fork copy was committed to kantheon, in ISO 8601.                              |

**Why it matters.** A bug fix landing in the ai-platform original does **not** propagate to the kantheon copy. The header keeps the diff possible (you can `git -C ~/Dev/ai-platform show <sha> -- <original path>` to see what was copied, then `git log` here to see what diverged). It is the *only* provenance — the git history of the kantheon copy starts at the fork, by design.

**Where the rule lives.** `AGENTS.md` §"Forked modules" — every contributor is expected to copy the header from this template when forking a new module. Drift between the kantheon copy and the ai-platform original is an *accepted* fork cost, not a bug to be backported (per `docs/architecture/fork/architecture.md` §1).

**Non-forked modules do not get this header.** Modules that were written in kantheon from the start (capabilities-mcp, themis, pythia, golem, iris-bff, iris FE, envelope-render, capabilities-client, hebe, charon, metis) have no `forked-from` line; if you see a forked-style header on one of those, remove it.
