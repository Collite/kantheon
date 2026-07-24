# investment — domain package (FO-24)

The first Tatrman domain package. FO-P4 S3 — **being populated**. Supersedes the Midas app (FO-12);
same model/canon/plugins power two faces: the deterministic **entry** face (Studio Data Entry, FO-P3)
and the agentic **Golem-Investment** face (from `golem/config.yaml`).

## Status (2026-07-23) — **S3 v0 complete, Verify PASS**

```
investment/
  package.yaml        # ✅ manifest (§15) — VALIDATES against @tatrman/package-sdk's schema (real file)
  model/              # ✅ TTR-M book: parties (client·portfolio scd2, consultant canon) ·      (S3.T2)
  │                   #    instruments (asset·asset_price scd1) · book (transaction ledger, position scd2)
  canon/              # ◑ transaction-entry-apply: cash-leg derivation ✅ PROVEN live (S3.T3); ledger corrections + cash cascade pending
  forms/              # ✅ transaction·position authored ttrl overrides                          (S3.T6)
  plugins/            # ✅ @investment/parsers (conseq-distrinfo·excel-book·RFC-4180 csv, 14/14) ·      (S3.T4/T5)
  │                   #    @investment/calc (twr·mwr·fifo·cash-operation·cash-ref, 22/22) — home RESOLVED (kantheon pnpm workspace, 2026-07-22)
  golem/config.yaml   # ✅ slot placeholder (C-3 contents schema Kantheon-owned, requested)
  recon.yaml          # ✅ broker-statement reconciliation config (§14)
```

**S3 Verify (2026-07-23, senior2):** package CI green (parsers 3/3 · calc 11/11 · SDK 10/10 · loader 8/8
incl. third-party cert-lever · reconciliation BUILD SUCCESSFUL · cash-leg round-trip 2/2); the real
`package.yaml` validates against the built SDK schema; B-F6 §5 decomposition fully accounted. Only `canon/`
stays ◑ — the cash-leg derivation is proven; ledger-correction cash-cascade awaits the leg-correlation
decision (below). Loader→live-registry wiring is seam-level (in-memory fakes) until S4 + the ⚑2 publish cut.

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
  (tatrman-platform `EntryCashLegRoundTripSpec`, on `fo`). The `cash-operation`/`cash-ref` functions the
  canon calls are now **implemented + declared** (below). **Remaining:** ledger-correction cash-cascade
  needs a leg-correlation decision for the simplified `transaction` shape (legacy used `correlation_id`).
- **Canon-functions (T5):** ✅ **DONE.** TWR/MWR/FIFO **and the cash-leg pair `cash-operation`/`cash-ref`**
  (called by `canon/transaction-entry-apply.ttrp`) ship as `@investment/calc` `CanonFunction` SPI impls
  (pure, versioned P-3) and are declared in `package.yaml` (pinned at `@investment/calc@0.1.0`).
  Property tests vs Midas-known results pending a Midas fixture corpus.
- **Plugin package home (T4/T5):** ✅ **RESOLVED (Bora, 2026-07-22).** `@investment/parsers` / `@investment/calc`
  live in-kantheon at `packages/investment/plugins/*` under the new `kantheon/pnpm-workspace.yaml` (globs
  `packages/*/plugins/*` — content-package TS plugins only, Vue frontends excluded). Cert-lever discipline
  preserved: each plugin builds against the open SDK via a local `sdk-shim.ts` (RO-6), no kantheon-internal dep.

**Provenance:** the Conseq DistrInfo loader (`../../features/midas/conseq`, ⚑R-2) re-homes here as the
`conseq-distrinfo` proposal-source parser once the plugin home is decided; entity mapping + corpus carry over.

**Design corpus (authoritative):** `project/common/frontends-offering/implementation/package-investment/`
(architecture · contracts · plan · STATUS) in the checkout root. Build discipline: **open SDK only**
(certification lever), no kantheon-internal deps — see `../README.md`.
