# Sysifos — Brief

> The original ask. Non-technical statement of why Sysifos exists. Promised by [`../../architecture/midas/midas-brief.md`](../../architecture/midas/midas-brief.md) §"Manual inputs" — "we will create a new application … a rich (web) application for data entry and management … called Sysifos. This app will have its own design and brief in this repository." This is that brief.

## What it is

Sysifos is the **data-entry and data-management workbench** for the Midas brokerage product. Where Iris is the chat surface for *asking* questions, Sysifos is the operational surface for *getting the data right* — the place a back-office user opens to enter trades, correct mistakes, import broker statements, set balances, and reconcile what the system thinks against what the broker says.

The name fits the work: keeping a book of positions correct is a boulder that never quite stays at the top of the hill. Statements arrive, trades settle, corrections land, balances drift. Sysifos is the tool that makes pushing that boulder fast, auditable, and hard to get wrong.

## Why it's separate from Iris

Iris is chat-shaped: turns, context, streamed envelopes, analytical answers. Sysifos is forms-shaped: grids, drafts, validation, optimistic edits, import wizards. The two share auth, tenant scoping, and the envelope rendering library, but their state models diverge sharply. Forcing data entry into a chat shell would make both worse. (Locked as Midas D4; this design keeps that boundary and makes Sysifos its own arc.)

## What users do in Sysifos

- **Manage master data** — clients, portfolios, the asset dictionary.
- **Enter transactions manually** — single trades through a form, and blocks of trades through a spreadsheet-style grid. The system derives the cash side of every trade so the book stays internally consistent.
- **Set balances** — "this position should be N units as of date D"; the system derives the adjusting transaction to close the gap.
- **Import statements** — upload a broker's Excel statement, preview what would change, fix the rows that don't map cleanly, and commit. Imports are idempotent — re-uploading the same statement changes nothing.
- **Reconcile** — see where the system and the statement disagree, and record a decision on each difference (expected / investigate / resolved).
- **Watch the loaders** — what imported when, what failed, what's scheduled.
- **Audit** — every write is logged; support engineers can see who changed what, before and after.

## The two levels of manual input (the heart of the brief)

The Midas brief names two:

1. **Transaction level** — the user enters a buy / sell / dividend / fee directly.
2. **Account / asset balance level** — the user enters a *target state* ("portfolio X holds 120 AAPL as of today"), and the system **derives the difference transaction** needed to reach it. This is the boulder-pushing shortcut: you don't have to reconstruct history, you state where things should be and Sysifos figures out the entry.

Both feed the same append-only transaction log owned by Midas-core. Sysifos never writes the book directly — it proposes, Midas-core derives and commits, and the result comes back for the user to see.

## Imports

v1 supports **Excel statements against predefined broker templates** (the brief's first loader). The interactive flow — upload, preview-with-diff, inline correction, commit — is Sysifos's; the parsing/mapping/commit pipeline is the Midas Excel loader's. Google Finance and the other sources in the Midas brief (API, SFTP, Yahoo) are later loaders that surface in Sysifos's loader-status screen but need no new entry UX.

## What Sysifos is not

- Not an analytics tool. No charts, no narrative answers — Iris owns those. Sysifos shows numbers in tables for context while you work.
- Not a writer of record. Midas-core is the only writer to the operational database; Sysifos goes through its REST write API.
- Not multi-product. v1 is the brokerage/investment domain only.

## Companions

- [`sysifos-design.md`](./sysifos-design.md) — the locked design: surface, semantics, screens, write model.
- [`sysifos-brainstorming.md`](./sysifos-brainstorming.md) — the decision record from this session.
- [`../../architecture/midas/`](../../architecture/midas/) — the Midas arc that owns Midas-core, the loaders, and the operational DB that Sysifos writes through.
