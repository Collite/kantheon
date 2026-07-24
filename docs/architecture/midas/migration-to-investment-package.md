# Midas → investment-package migration runbook (LF-5 handoff)

> **Status: DRAFT (FO-P4 S4.T4, 2026-07-23).** The transitional plan for retiring the Midas estate onto
> the Tatrman **investment domain package**. This is the *drafted* runbook the **LF-5 handoff** wake
> executes — it is intentionally ahead of some of its own preconditions (see §0). Cutover does NOT begin
> until LF-5 fires; until then the Midas arc keeps running (FO-12 no-rug-pull). Owner at execution:
> Kantheon lane (LF-5), with FO consulted on the package/entry surfaces.

## What is being replaced

| Midas-arc component | Successor | Where |
|---|---|---|
| Sysifos (BFF + FE) | **Studio Data Entry** (generic md-derived entry, one journal door FO-8) | `tatrman-platform` `data-entry` + `entry-substrate` |
| Midas book schema (clients/portfolios/assets/transactions/positions) | **TTR-M model** | `packages/investment/model/*.ttrm` |
| `CashLegDerivation.kt` + ledger corrections (hardcoded Kotlin, contracts §1.1.A) | **TTR-P canon** (reviewable, deterministic) | `packages/investment/canon/transaction-entry-apply.ttrp` |
| Midas loaders (Conseq DistrInfo, Excel book) | **§13 proposal-source parsers** | `packages/investment/plugins/parsers` (`@investment/parsers`) |
| TWR/MWR/FIFO calc | **canon-functions** (pure, P-3) | `packages/investment/plugins/calc` (`@investment/calc`) |
| Midas-core agent | **Golem-Investment** (agentic face, journal client — no privileged write, seam C-1) | `packages/investment/golem/config.yaml` |
| Reconciliation (broker statements) | **reconciliation organ** (§14) + per-package config | `tatrman-platform` `reconciliation` + `packages/investment/recon.yaml` |
| Report renderer / dashboards | out of scope for this handoff — tracked separately (Iris) | — |

## 0. Preconditions (gates before cutover — not all met at draft time)

Cutover MUST NOT start until all hold. Each is tracked outside this doc; this list is the go/no-go.

1. **Package installs on the live estate.** The package loader wires to the *live* FO-P2 `ProgramRegistry`
   / form source / §13 registry — at draft time the loader installs into in-memory seams only ("real
   wiring lands…", FO-P4 S1/S4). **Gate: loader live-registry wiring.**
2. **SDK published.** `@tatrman/package-sdk` cut to the registry so the package + its plugins resolve
   without workspace refs (cert lever). **Gate: ⚑2 publish (Bora's go).**
3. **Live substrate + auth.** Studio Data Entry ↔ entry-substrate over HTTP with real bearer auth.
   **Gate: `Collite/project#4` (BYO-OIDC + live-substrate integration).**
4. **Golem-config contents schema.** The Kantheon-owned `golem/config.yaml` CONTENTS schema (seam C-3)
   delivered; the slot is an envelope-only placeholder until then. **Gate: C-3 seam.**
5. **Model + canon parity signed off.** The `.ttrm` model and the cash-leg/ledger canon reproduce the
   Midas estate's semantics on a copy of production data (see §2, §3).

## 1. Prepare (no estate impact)

- Install the investment package on a **staging** estate (loader → live registries).
- Confirm the manifest validates and every declared part loads: model registered, canon deployed as
  door-programs, forms served, parsers registered as proposal sources, canon-functions resolvable
  (versions pinned).
- Stand up Studio Data Entry pointed at the staging entry-substrate; smoke-test one form round-trip.

## 2. Data model parity

- Diff the `packages/investment/model` entities against the Midas book schema (parties, instruments,
  book: `transaction` ledger, `position` scd2). Reconcile any column/role gaps.
- **md declarations:** confirm `management` / `change-semantics` (ledger for `transaction`, scd2 dims,
  scd1 refs) and the `valid-from`/`valid-to`/`reversal-link` roles match Midas history semantics.
- **Grain/decimal note:** the v0 model carries money as BIGINT (§5.1 wave set TEXT/BIGINT/DATE); if the
  live estate needs DECIMAL, that wave must land first (tracked as future work).

## 3. Canon parity (the load-bearing step)

- **Cash leg:** verify `transaction-entry-apply.ttrp` reproduces `CashLegDerivation` on a sample of real
  trades — propose the security leg, assert the derived cash leg matches Midas's booked counter-leg
  (operation mapping, amount magnitude, currency). Note the **v0 ruling**: no cash-account modelling
  (`asset_ref`/`quantity` null on the cash leg) — confirm the estate tolerates this or schedule the
  cash-account revisit first.
- **Ledger corrections:** verify reverse-and-replace semantics + the id scheme; the cash-leg **cascade**
  on a correction needs the leg-correlation decision (legacy `correlation_id`) settled for the simplified
  `transaction` shape — **blocking for estates that correct trades** (open item, see §7).
- **Calc:** property-test TWR/MWR/FIFO from `@investment/calc` against Midas-known results on the estate's
  own history.

## 4. Data migration

- Migrate reference + dimension data first (scd1/scd2), then the ledger (`transaction`) preserving
  external ids and history. Prefer **re-proposing through the entry door** (one-door discipline, FO-8) over
  a raw table copy, so every migrated row gets an entry record + lineage. For bulk history, a governed
  bulk-load path may be needed (tracked with the loader work).
- Re-point the Conseq DistrInfo / Excel loaders at `@investment/parsers`; run one statement import through
  Studio Data Entry (upload → diff preview → correct → commit) and confirm the batch lands via §13.
- Load `recon.yaml`; run a broker-statement reconciliation; confirm outcomes re-enter as
  `source.type="reconciliation"` batches (no direct writes, §14).

## 5. Agentic face

- Deliver the C-3 golem-config; instantiate **Golem-Investment** over the same package. Verify (seam C-1,
  proven in `GolemJournalClientSpec`) that every golem-proposed change exits as a `source.type="agent"`
  batch through the same door — no privileged write path — and is journaled/auditable.

## 6. Cutover & decommission

- Freeze writes on the Midas estate; final reconcile; flip users to Studio Data Entry + Golem-Investment.
- Run the FO-P4 DoD (`fo-p4-dod`) against the migrated estate as the acceptance gate.
- Decommission Sysifos (BFF + FE) and Midas-core **only after** a soak period; keep read-only access to
  the old estate for audit. Update this doc's supersession banners from *pending* to *retired*.

## 7. Rollback & open items

- **Rollback:** because cutover freezes then flips (no destructive migration until decommission), rollback
  = re-point users to the still-running Midas estate. Keep it warm through the soak period.
- **Open items blocking specific estates:**
  - Ledger-correction **cash cascade** leg-correlation decision (§3) — blocks estates that correct trades.
  - **DECIMAL** money support (§2) — blocks estates needing sub-unit precision.
  - Cash-account modelling revisit (v0 omits it) — only if positions need a cash line.
- **Gates (from §0):** loader live-registry wiring · ⚑2 publish · project#4 · C-3 golem schema · parity
  sign-off. LF-5 fires when these are green.
