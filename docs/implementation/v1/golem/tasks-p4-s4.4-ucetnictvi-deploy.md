# Golem Phase 4 · Stage 4.4 — Golem-ucetnictvi bundle + chart (the build deliverable)

> **Arc.** Golem Phase 4 — **the closing stage of the Golem (build) arc.** **Branch.** `feat/p4-s4.1-area-rename`.
> **Companions.** [`plan.md`](./plan.md) §6 Stage 4.4 + the §6 Handoff, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6, [`agents/golem/shems/golem-ucetnictvi/`](../../../../agents/golem/shems/golem-ucetnictvi/) (the bundle).
> **Goal.** Ship the in-repo, build-time deliverable for the first Kantheon Golem: the assembled **golem-ucetnictvi** Shem bundle reconciled against the Stage 4.3 parser, and the golem Helm chart's **Shem-mount** capability. **The live legs moved out — see "Handoff" below.**
>
> **Re-scoped 2026-06-25 (Golem-arc close).** The original Stage 4.4 (deploy / register / route / latency) + Stages 4.5 (soak) / 4.6 (cutover) required a live cluster + a live Iris + ai-platform PRs — **not Golem-arc development.** They moved to **Stream T** (live verification + soak) and a **release checklist**. This stage keeps only what builds in-repo.

## Tasks

- [x] **T1 — reconcile the Shem bundle against the 4.3 parser.** `GolemUcetnictviBundleSpec` parses the real `shems/golem-ucetnictvi/shem.yaml` via `ShemOverlayParser` (apiVersion/kind/source.id/label/areas/visibility_roles) + asserts the `prompts/{cs,en}/{intent,free-sql,chip-topup}.yaml` set `PromptStore` expects. *(Landed with Stage 4.3.)*
- [x] **T2 — golem Helm chart Shem-mount.** `shem.configMapName` → ConfigMap volume + read-only mount at `GOLEM_SHEM_DIR` (default `/etc/golem/shem`) + the env; `helm template` validated empty (skeleton boot) and set (mounted). The ConfigMap built from the bundle dir + per-context values are the deploying context's (olymp).

## DONE

The assembled golem-ucetnictvi Shem + bundle + chart-mount ship in-repo, green. **Phase 4 DONE — the Golem (build) arc is closed.**

## Handoff (NOT this arc)

- **Live verification → Stream T** ([`../testing/tasks-p3-s3.1-contexts.md`](../testing/tasks-p3-s3.1-contexts.md), the golem context — `golem-erp`, to be renamed `golem-ucetnictvi`): deploy from the bundle + readiness gate; **registration** visible in capabilities-mcp (`AREA_QA`, `kantheon-area-accounting`); **Themis Layer-1 routing** joint test; **latency/cost** measured + the Shem hints filled; **side-by-side soak** (golem-v2 vs golem-ucetnictvi flag, one-week divergence log, Bora's cs prompt review, perf vs v2, go/no-go). Gated on Ariadne/Prometheus charts + a live Iris + the native iris-bff `GolemClient`.
- **Release / cutover (Bora-owned, post-soak go):** Iris default-flip + `dispatch/golemv2/` adapter delete + `iris_v2_threads` (Iris-arc PR); ai-platform `agents/golem` deprecation (ai-platform PR); `golem-template-design.md` reality-note fold + kantheon-architecture §11 waypoints 7–8; tags `ariadne/v0.2.0` + `golem/v0.1.0`/`v0.2.0` + `golem/v1.0.0`. **⚠ Pre-deploy:** migrate the live ai-models model to 0.7.0 TTR syntax (`binding:` / `schema binding`).
