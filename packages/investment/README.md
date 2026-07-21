# investment — domain package (FO-24)

The first Tatrman domain package. Scaffold folder established 2026-07-21 (FO-P4 kickoff); **content is
authored in FO-P4 S3** (this is a home, not yet a built package).

**What it is (target state):** the investment domain cartridge that supersedes the Midas app (FO-12).
Same model/canon/plugins power two faces — the deterministic **entry** face (Studio Data Entry, FO-P3)
and the agentic **Golem-Investment** face (kantheon-side, from `golem/config.yaml`).

**Planned layout** (authored in S3 — see the corpus below):

```
investment/
  package.yaml        # manifest (§15): model/canon/forms/plugins/golem/reconciliation
  model/              # clients·portfolios (scd2) · assets·prices (scd1) · transactions (ledger) · positions
  canon/              # <txn_book>-entry-apply: cash-leg derivation, ledger corrections
  forms/              # authored ttrl overrides
  plugins/            # conseq-distrinfo + excel-book parsers (§13) · twr/mwr/fifo canon-functions
  golem/config.yaml   # slot — Kantheon-owned contents schema (seam C-3)
  recon.yaml          # broker-statement reconciliation config (§14)
```

**Provenance:** the Conseq DistrInfo loader (`../../features/midas/conseq`, ⚑R-2) re-homes here as the
`conseq-distrinfo` proposal-source parser; its entity mapping + corpus carry over.

**Design corpus (authoritative):** `project/common/frontends-offering/implementation/package-investment/`
(architecture · contracts · plan · STATUS) in the checkout root. Build discipline: **open SDK only**
(certification lever), no kantheon-internal deps — see `../README.md`.
