# Fork — Stage 5.6: Total-independence sweep

> Branch: `feat/fork-p5-s5.6-independence-sweep`. Pre-flight: Stages 5.1–5.5. Plan: [`plan.md`](./plan.md) Stage 5.6. Tracker: [`tasks.md`](./tasks.md).
>
> The closing stage of the whole fork: after this, ai-platform has **zero** operational or build role and can be switched off. Mirrors the Phase-4 sweep idiom, extended to the technical wave and to branding strings.

- [x] **T1 — Greppable independence.**
  Verified **zero live coupling roots**: no `package com.platform` / `import com.platform` (health's old root), no `package/import infra.{whois,health}` (whois/health's old roots), no live `import infra.erp.sql.common` (the cut auth coupling). The broad `cz.dfpartner|com.platform.|df-partner|ai-platform` matches that remain across the repo are all **allowed categories** — provenance headers (`forked-from: ai-platform@…`), guard tests that assert *against* the patterns (`NoErpSqlCommonImportSpec`, `ConfigLoaderTest`, `rebrand.spec.ts`), the rename-map (`fork/contracts.md`), the guard scripts (`fork-docs-check.sh`, `verify-forked-proto-layout.sh`), and historical doc notes. The two live whois-fixture emails on `@dfpartner.cz` were de-branded to `@kantheon.example` (test assertion updated; 18 whois specs still green).

- [x] **T2 — CLAUDE.md.**
  §7 header + body flipped to "technical wave: Phase 5 complete 2026-06-24 — independence TOTAL"; §7.4 retitled "removed in fork Phase 5 (done 2026-06-24)" with the shipped detail + tags. (Roster/§2, repo-layout/§3 `infra/` tree, and the §9 "infra keeps its functional name" vocabulary rule were already authored ahead and remain accurate.)

- [x] **T3 — kantheon-architecture.md.**
  Coupling section: the "only remaining tie … Phase 5 not yet executed" line flipped to "technical wave landed 2026-06-24 … ai-platform can be switched off — the fork is complete." (§10 "Everything forks" decision row + the §2.d/§3 technical-wave descriptions were already present.) Only standing external Maven dep remains `Collite/modeler` (TTR), not ai-platform.

- [x] **T4 — Local bring-up.**
  `deployment/local/README.md` gains a **technical-wave** section: whois/health are Helm-chart deployables (`helm template` renders clean) brought up via the olymp/ArgoCD GitOps path (not the Kustomize `deploy-fork`); landing/backstage are Node/Nginx images (CI-gated builds). Per-service charts/images/ports tabulated. (Live clean-K3s bring-up is the integration suite's confirmation, per the testing policy.)

- [x] **T5 — Independence assertion.**
  Extended the Phase-4 egress assertion in `fork/architecture.md` §9 to **TOTAL** (pipeline AND technical wave): no kantheon pod egresses to an ai-platform-hosted whois/health/landing/backstage — the forked in-repo instances serve every path. The live "ai-platform down, all green" confirmation rides the integration suite on `bp-dsk`.

- [x] **T6 — Sign-off.**
  Sign-off note added to `plan.md` Phase 5 (all six stages summarised + tags). Memory `kantheon_platform_fork.md` + `MEMORY.md` index flipped to "fork COMPLETE (Phases 1–5)". Final tags to cut on merge: `whois`/`health`/`backstage`/v0.1.0 + `argos`/v0.2.0. Stage 5.6 checked in [`tasks.md`](./tasks.md).

**DONE means:** ai-platform can be switched off without breaking a single kantheon path. **One Kantheon.** **✅ Met 2026-06-24 — the fork is done.**
