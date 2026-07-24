# packages/ — content packages (domain cartridges)

Home for Tatrman **domain packages** (content cartridges), colocated in kantheon for the foreseeable
future (Bora, 2026-07-21 — "let's not over-repo"). A package is not runtime code; it is a loadable
cartridge of content + plugins:

```
<package>/
  package.yaml        # manifest — FO contracts §15
  model/              # TTR-M
  canon/              # TTR-P: <table>-entry-apply programs (derivation-as-canon)
  forms/              # ttrl authored overrides
  plugins/            # P-3: proposal-source parsers + canon-functions (pure, versioned)
  golem/config.yaml   # slot — CONTENTS schema Kantheon-owned (seam C-3)
  recon.yaml          # reconciliation config
```

Born by **FO-P4** (Domain-package SDK + investment package). Planning corpus + contracts live in the FO
effort tracker: `project/common/frontends-offering/implementation/package-investment/`.

## Dependency discipline (load-bearing — do not break)

- A package builds against the **published open SDK only** (`@tatrman/package-sdk`, Apache) — never
  kantheon internals. This is the FO-23 **certification lever**: a third party must be able to author a
  package from the open SDK alone. Colocation here is a convenience, not a coupling.
- The commercial **loader** (tatrman-platform) and the **entry face** (Studio Data Entry) consume a
  package as **runtime content + resolved plugin artifacts**, never a build/compile dependency on
  kantheon — the platform↔kantheon P2 dependency rule (platform never depends on kantheon) stands.
- The **golem face** (Golem-Investment) is kantheon-side and reads `golem/config.yaml` from here.

## Packages

- **`investment/`** — the first package (FO-24); proves the SDK. Investment domain: clients, portfolios,
  assets, transactions (ledger, cash-leg derivation-as-canon), positions, prices; Conseq/Excel proposal
  parsers; TWR/MWR/FIFO canon-functions; broker-statement reconciliation. Supersedes the Midas app
  (FO-12) — Golem-Investment is its agentic face.
