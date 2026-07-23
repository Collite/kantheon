# investment — domain package (FO-24)

The first Tatrman domain package. FO-P4 S3 — **being populated**. Supersedes the Midas app (FO-12);
same model/canon/plugins power two faces: the deterministic **entry** face (Studio Data Entry, FO-P3)
and the agentic **Golem-Investment** face (from `golem/config.yaml`).

## Status (2026-07-21)

```
investment/
  package.yaml        # ✅ manifest (§15) — VALIDATES against @tatrman/package-sdk's schema
  model/              # ✅ TTR-M book: parties (client·portfolio scd2, consultant canon) ·
  │                   #    instruments (asset·asset_price scd1) · book (transaction ledger, position scd2)
  canon/              # ◑ transaction-entry-apply: cash-leg derivation ✅ PROVEN (S3.T3); ledger corrections + cash cascade pending
  forms/              # ☐ authored ttrl overrides                                          (S3.T6)
  plugins/            # ☐ conseq-distrinfo + excel-book parsers · twr/mwr/fifo             (S3.T4/T5 — home TBD)
  golem/config.yaml   # ✅ slot placeholder (C-3 contents schema Kantheon-owned, requested)
  recon.yaml          # ✅ broker-statement reconciliation config (§14)
```

## Open gaps (flagged to Bora at S3 kickoff)

- **md-grammar toolchain (T2/T3):** the `management` / `change-semantics` declarations + the
  `valid-from`/`valid-to`/`reversal-link` attribute roles are the FO-P2-**ratified** md surface, but their
  md-grammar support is the standing stub-carry (fixture-stubbed through FO-P2/P3). The `model/*.ttrm`
  files author the ratified surface; they parse once the md toolchain half lands.
- **TTR-P canon (T3):** ✅ **UNGATED + DONE 2026-07-23.** The `entry` stdlib lowering (EN arc) + the
  derivation surface (ED arc, incl. the derived-**row** counter-leg) are delivered. `canon/transaction-
  entry-apply.ttrp` authors the cash-leg derivation (propose the security leg → derive the `leg='cash'`
  counter-row: `operation` mapped by `call-fn("cash-operation")`, `external_id` by `cash-ref`, money
  copied, `asset_ref`/`quantity` null per Bora's v0 ruling). **Surface spelling is PLA-2-provisional**;
  the frozen structured form is proven live — round-trips through the real ApplyDoor on PG
  (tatrman-platform `EntryCashLegRoundTripSpec`, on `fo`). **Remaining:** the `cash-operation`/`cash-ref`
  functions land in the plugin home (T4/T5, below); ledger-correction cash-cascade needs a leg-correlation
  decision for the simplified `transaction` shape (legacy used `correlation_id`).
- **Canon-functions (T5):** TWR/MWR/FIFO (exotic calc) remain TTR-P canon-functions, home TBD (below).
- **Plugin package home (T4/T5):** `@investment/parsers` / `@investment/calc` are TS npm packages, but
  kantheon has no pnpm workspace (Gradle + Vue frontends). Where the plugin code lives is a structural
  decision for Bora.

**Provenance:** the Conseq DistrInfo loader (`../../features/midas/conseq`, ⚑R-2) re-homes here as the
`conseq-distrinfo` proposal-source parser once the plugin home is decided; entity mapping + corpus carry over.

**Design corpus (authoritative):** `project/common/frontends-offering/implementation/package-investment/`
(architecture · contracts · plan · STATUS) in the checkout root. Build discipline: **open SDK only**
(certification lever), no kantheon-internal deps — see `../README.md`.
