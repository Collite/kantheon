# RFCs

An RFC (Request for Comments) is a short design document that proposes a substantive change to hebe and invites feedback before implementation begins.

---

## When is an RFC needed?

An RFC is required for any change that:

- **Changes the kernel ABI** — adds, removes, or renames types in `api` or `plugin-api` in a way that breaks existing plugins or callers.
- **Introduces a new permission** — any new entry in the `Permission` sealed interface or a new capability name.
- **Adds a new channel category** — a new `Channel` implementation that affects the `ChannelManager` contract.
- **Changes the security model** — autonomy levels, sandbox posture, receipts format, signing scheme, or secret storage.
- **Changes the MCP server/client contract** — transport, tool naming, filter group semantics.
- **Deprecates or removes a feature** — any feature that external users or plugin authors depend on.

An RFC is NOT needed for:
- Bug fixes.
- Performance improvements that don't change observable behaviour.
- Adding a new built-in tool that uses only the existing `Tool` interface.
- Documentation updates.
- Refactoring that preserves all external contracts.

If you are unsure, err on the side of writing a short RFC. A rejected RFC is better than a surprise breaking change.

---

## How to propose an RFC

1. Copy `0000-template.md` and rename it `<next-number>-<short-title>.md` (e.g. `0001-postgres-memory-backend.md`).
2. Fill in all sections of the template.
3. Open a pull request adding only the new RFC file (no code yet).
4. Discussion happens in the PR.
5. The RFC is merged when there is consensus; implementation begins after merge.
6. When implementation is complete, update the RFC's `Status` field to `Implemented` and link to the relevant PR.

---

## RFC numbering

Numbers are assigned in PR order. Use `0000` as the placeholder while authoring; the final number is assigned when the PR is opened (use the PR number if that is easier to track).

---

## RFC index

| Number | Title | Status |
|---|---|---|
| (none yet) | | |
