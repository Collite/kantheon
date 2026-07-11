# ai-platform → kantheon/tatrman — Extraction Inventory (Audit 2026-07-10)

> Produced during the ecosystem strategy review (2026-07-10) as the component-by-component
> inventory behind the November refactor goal: **ai-platform ends the DF Partner engagement
> consuming published `org.tatrman:*` OSS artifacts; DFP-specific content stays client-side.**
> Sources: kantheon `docs/architecture/fork/architecture.md` (2026-06-12, Phases 1–5 complete
> as of 2026-06-24), `ttr-metadata-adoption.md` + `ttr-translator-extraction.md` (2026-07-05/06),
> ai-platform `tasks-stage-grounding-*.md`. Audited by a subagent against directory/proto/doc
> evidence; items marked "open/unverified" need a direct diff before acting.

## Summary

The vast majority of ai-platform's v1 pipeline (query-runner, dispatcher, translator, validator,
fuzzy-matcher, nlp, metadata, llm-gateway) plus the technical services (whois, health, backstage,
landing) were **copy-paste forked into kantheon on 2026-06-12** and renamed into the pantheon,
with zero remaining runtime/build coupling back to ai-platform as of Phase 5 (2026-06-24). Two of
those are mid-way through a **second-order extraction** into tatrman as publishable OSS libraries:
Ariadne's core → `org.tatrman:ttr-metadata(-git)` (code landed; **publish-to-GitHub-Packages gate
still open**, pinned to `0.0.1-LOCAL`), and Proteus's `query-translator` core →
`org.tatrman:ttr-translator` + `ttr-plan-proto` (**planned, not executed** — 73 files, the largest
remaining locally-vendored core in ai-platform). The grounding stack (chrono/geo/money,
grounding-mcp, Golem's GroundEntities node) is confirmed **absent from kantheon** (postdates the
fork by ~3–4 weeks). `agents/golem` was not forked (kantheon hand-rewrites in Kotlin/Koog);
`agents/resolver` has **no kantheon counterpart at all**. The entire v0 ERP-SQL family stays
ai-platform-only and sunsets.

## Component table

| Component | ai-platform location | kantheon/tatrman counterpart | Status | Notes |
|---|---|---|---|---|
| Metadata service | `infra/metadata` + `tools/meta-mcp` | `services/ariadne` + `tools/ariadne-mcp`; core → `org.tatrman:ttr-metadata{,-git}` | EXTRACTED; library sub-extraction in progress | ai-platform already mid-swap onto the published tatrman library (task A1; 6 drift fixes upstreamed; gated on grounding-03). Ariadne grew a `GetPrompts` RPC ai-platform's metadata lacks. |
| Resolver | `agents/resolver` (`cz.dfpartner.resolver`) | none | MISSING-IN-KANTHEON | Explicitly "still not forked". Live bugfix churn (NameTag/CNEC, task A2) lands only in ai-platform. |
| Grounding — chrono/geo/money | `services/{chrono,geo,money}` (`cz.dfpartner.grounding.v1`) | none | MISSING-IN-KANTHEON | Net-new post-fork work. |
| grounding-mcp (+ Golem GroundEntities node) | `tools/grounding-mcp` | none | MISSING-IN-KANTHEON | Thin wrapper over chrono/geo/money; kantheon's Golem rewrite predates it. |
| query-mcp | `tools/query-mcp` | `tools/theseus-mcp` | EXTRACTED | |
| meta-mcp | `tools/meta-mcp` | `tools/ariadne-mcp` | EXTRACTED | |
| fuzzy-mcp / fuzzy-matcher | `tools/fuzzy-mcp`, `services/fuzzy-matcher` | `tools/echo-mcp`, `services/echo` (`org.tatrman.echo.v1`) | EXTRACTED | |
| nlp / nlp-mcp | `infra/nlp`, `tools/nlp-mcp` | `services/kadmos`, `tools/kadmos-mcp` | EXTRACTED | Python kept (spaCy/Stanza/MorphoDiTa moat). |
| erp-data-mcp | `tools/erp-data-mcp` | none | V0-LEGACY | |
| translator | `services/translator` | `services/proteus` (`org.tatrman.proteus.v1`) | EXTRACTED (service); TOOLCHAIN sub-extraction PLANNED | `query-translator` lib move + `plan.v1`/`transdsl.v1`/`dfdsl.v1` ownership transfer to tatrman `ttr-plan-proto` = Phase B, not executed. |
| validator + sql-security | `services/validator`, `infra/sql-security` | `services/argos` (merged) | EXTRACTED (consolidated) | Identity model DIVERGED: kantheon default bearer-token roles; whois demoted to opt-in enrichment. |
| query-runner | `services/query-runner` | `services/theseus` | EXTRACTED | |
| dispatcher | `services/dispatcher` | `services/kyklop` | EXTRACTED | |
| workers/mssql | `workers/mssql` | `workers/brontes` | EXTRACTED | `org.tatrman.worker.v1`. |
| workers/polars | `workers/polars` | `workers/steropes` | EXTRACTED | |
| (postgres worker) | — | `workers/arges` | KANTHEON-NATIVE | New; gates the Midas domain. |
| llm-gateway | `infra/llm-gateway` (`org.tatrman.llmgateway.v1`!) | `services/prometheus` (`org.tatrman.prometheus.v1`) | EXTRACTED; NAMING COLLISION | Same lineage, two org.tatrman names in the wild. |
| whois | `infra/whois` | `infra/whois` | EXTRACTED | Usage diverged (see argos row). |
| health / backstage / landing | `infra/health`, `infra/backstage`, `frontends/landing` | same names | EXTRACTED | Phase 5. |
| agents-fe | `frontends/agents-fe` | `frontends/iris` (+ `agents/iris-bff`) | DIVERGED | Rewrite basis, not a fork. |
| golem | `agents/golem` (Python/LangGraph) | `agents/golem` rewrite (Kotlin/Koog, ports v2 semantics) | DIVERGED (deliberate twin) | The two-reference-implementations pattern. |
| v0 sql-* family | `services/erp-sql*`, `services/sql-*-service`, `infra/sql-{metadata,validator}`, `frontends/erp-sql-fe`, `shared/libs/kotlin/erp-sql-*` | none | V0-LEGACY | Sunsetting; not part of the November push. |
| query-translator lib | `shared/libs/kotlin/query-translator` (73 files) | → `org.tatrman:ttr-translator` (PLANNED) | DIVERGED / mid-migration | Largest remaining vendored core. |
| ttr-parser / ttr-writer | vestigial (`ttr-parser` has no `src/`, only stale `build/`) | `org.tatrman:ttr-parser` / `ttr-writer` (tatrman) | TOOLCHAIN (already adopted) | Delete stale dirs after confirming swap. |
| otel-config, data-formatter, db-common, fuzzy-common, ktor-configurator, logging-config, whois-common | `shared/libs/kotlin/*` | same names in kantheon (vendored copies) | EXTRACTED | Forked in-repo Phase 1. |
| kantheon-only libs | — | ariadne-client, bff-base, capabilities-client, component-testkit, envelope-render, integration-harness, keycloak-auth, llm-gateway-client, pattern-params | KANTHEON-NATIVE | keycloak-auth carved from erp-sql-common's token-provider (self-contained). |
| aip_pattern_params, aip_security (Python) | `shared/libs/python/*` | none | DFP-SPECIFIC | Golem/agent-registry-adjacent. |
| otel-config (Python) | `shared/libs/python/otel-config` | same | EXTRACTED | |
| DFP models/prompts/agent registry | `DFPartner/ai-models` repo; secrets/env/overlays | (mechanism only: Ariadne `GetPrompts`) | DFP-SPECIFIC | Content stays client-owned. |

## Proto namespace map

**ai-platform (`cz.dfpartner.*`):** dfdsl.v1 · dispatcher.v1 · erp.v1 + erp.sql.v1 · fuzzy.v1 ·
grounding.v1 · metadata.v1 (41 KB, largest) · nlp.v1 · plan.v1 (context/parameters/plan) ·
resolver.v1 · runner.v1 · security.v1 · transdsl.v1 · translator.v1 · validator.v1 · worker.v1 —
plus one pre-emptively OSS-namespaced package: `org.tatrman.llmgateway.v1`.

**kantheon:** `org.tatrman.*` (platform): argos.v1 · ariadne.v1 (40 KB) · charon.v1 · echo.v1 ·
kadmos.v1 · kallimachos.v1 · kyklop.v1 · metis.v1 · pinakes.v1 · prometheus.v1 · proteus.v1 ·
security.v1 · theseus.v1 · worker.v1. `org.tatrman.kantheon.*` (agent/product, reserved):
capabilities · common · envelope · golem · hebe · iris · kleio · midas · pythia (29 KB) · report ·
sysifos · themis.

**Twins:** metadata.v1→ariadne.v1 · dispatcher.v1→kyklop.v1 · runner.v1→theseus.v1 ·
translator.v1→proteus.v1 · fuzzy.v1→echo.v1 · nlp.v1→kadmos.v1 · validator.v1+security.v1→argos.v1
(2→1 merge) · worker.v1→worker.v1 (kantheon's 2.3 KB larger — possible inlining, needs diff) ·
llmgateway.v1→prometheus.v1 (collision). **No kantheon home found for:** plan.v1 / transdsl.v1 /
dfdsl.v1 (ownership transfer to tatrman `ttr-plan-proto` staged but unexecuted — OPEN QUESTION
whether kantheon vendors or inlines them today), resolver.v1, grounding.v1, erp*.v1 (legacy, correct).

## Gaps & collisions

1. **Missing in kantheon:** resolver (+ its live A2 fixes with no home to land), chrono, geo,
   money, grounding-mcp, GroundEntities node.
2. **ai-platform ahead of its twins:** grounding stack; resolver fixes; `infra/metadata`
   resident-only files (`YamlImportSource.kt`, `MetadataExportRoutes.kt`) + the 6 drift fixes that
   had to be upstreamed.
3. **kantheon ahead (deliberate product change, not regression):** Ariadne `GetPrompts`; Argos
   bearer-first identity with whois-as-enrichment; prometheus rename.
4. **Kantheon-native surface (nothing to verify):** charon, metis, arges, pinakes, kallimachos,
   report-renderer, capabilities-mcp, the hebe/kleio/midas/pythia/sysifos/themis agent layer.
5. **Open/unverified:** plan/transdsl/dfdsl proto fate inside kantheon (direct proto diff needed);
   ttr-writer swap completeness.

## Recommended rewrite directions

- **ttr-metadata publish gate** — finish GitHub Packages publishing (drop `0.0.1-LOCAL`); it blocks
  CI-reproducibility for ai-platform AND kantheon. **First item.**
- **query-translator → ttr-translator + ttr-plan-proto** — execute the planned arc; transfer
  plan.v1/transdsl.v1/dfdsl.v1 ownership to tatrman with FQCNs/wire format frozen (TR-3).
- **Grounding** — after DFP validation, run a "Fork Phase 6" (copy-paste, rename, `org.tatrman.*`
  repackage) so ai-platform can stop vendoring before November.
- **Resolver** — rewrite kantheon-native (Kadmos capability or Golem-rewrite node) rather than
  forking; ai-platform keeps its Resolver as client-specific until Golem cutover. NOTE (strategy):
  the OSS spine needs *an* entity-resolution story — this is core to the two-call thesis, so the
  rewrite is spine work, not satellite work.
- **llmgateway/prometheus collision** — ai-platform repoints to `org.tatrman.prometheus.v1`
  (kantheon's deliberate OSS name wins); serves the "ai-platform consumes, not defines" goal.
- **whois divergence** — adopt kantheon's `argos.roleSource` toggle pattern (bearer|whois) rather
  than diverging further; DFP keeps whois as its configured role source.
- **Vestigial ttr-parser/ttr-writer dirs in ai-platform** — confirm swap, delete.
- **v0 sql-*** — no action; sunsets with the engagement.

## November end-state (definition of done)

ai-platform builds against published `org.tatrman:*` artifacts (metadata, translator, plan protos,
parser/writer) and published platform services/protos where applicable; every remaining in-repo
component is either DFP-specific (models, prompts, agent registry, config, whois-as-role-source,
resolver-until-cutover) or v0-legacy awaiting retirement. No `cz.dfpartner` proto has an
unacknowledged `org.tatrman` twin; no `org.tatrman` name is defined by ai-platform.
