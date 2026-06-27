# Kantheon v1 — Next Steps (2026-06-27)

> **What this is.** A point-in-time status review of the whole v1 codebase against the arc plans, plus the ordered remaining work to reach the M7 "v1 constellation complete" ship. Written 2026-06-27 by reading every arc `plan.md`, the git log, and the cut-tag set.
>
> **Headline.** Your instinct is right: **essentially all product code for v1 is complete.** Every Spine arc (Stream A) and every Body arc (Stream B) is code-complete. What stands between here and v1 is no longer feature work — it is the **release-and-verify layer**: the Testing/deploy harness (Stream T), a large batch of un-cut release tags, the one unbuilt domain arc (**Midas P3**), and the **Golem live cutover** that retires the Python golem.
>
> **The master plan is stale.** [`docs/implementation/v1/master-plan.md`](docs/implementation/v1/master-plan.md) was last revised 2026-06-24 and still lists **Pythia as "Planned"** and **Hebe/Kleio as future Body pushes**. Since then, **Pythia P1–P5, Kleio P1–P5, and Hebe P3–P4 all landed.** Updating the master plan is itself an action item (§5).

---

## 1. The one-paragraph state of the world

Streams A and B are done as *code*. Pythia (P1–P5), Kleio (P1–P5) and Hebe (P1–P4) all merged after the master plan's last revision; Golem's build arc closed (`docs(golem): close the Golem (build) arc`); Fork Phase 5 landed (`infra/whois`, `infra/health`, `infra/backstage`, `frontends/landing` are all present). The only substantive arc **not yet built** is **Midas Phase 3** (domain Q&A + reports + dashboards). Everything else that remains is **Stream T** — component/integration testing and the deploy+smoke stages that *cut the release tags* — plus the mechanical work of actually cutting the many tags that are gated behind those deploy stages, plus the **Golem live cutover** to `golem/v1.0.0`. In short: the constellation is built; it now needs to be released and verified end-to-end on a live cluster.

---

## 2. Per-arc reality (2026-06-27) vs. the 06-24 master plan

Legend: **✅ done & tagged** · **🟢 code-complete, tag pending** · **🔧 not built**

### Stream A — the Spine

| Arc | Code state | Tags cut | Reality vs. master plan |
|---|---|---|---|
| **Fork P1–P5** | ✅ complete | `themis/v0.1.0` (P2); **P5 tags `whois`/`health`/`backstage`/`v0.1.0` + `argos/v0.2.0` NOT cut** | Matches (P5 done 06-24). P5 release tags still pending. |
| **Themis P1–P3** | ✅ complete | `themis/v0.2.0` | Matches — arc complete. |
| **Iris P1–P4** | 🟢 code-complete | **none** (`iris*`/`iris-bff*` gated on Testing S3.3) | Matches. |
| **Golem P1–P4 (build)** | 🟢 build arc closed | **none** (`envelope-render/v0.1.0`, `golem/v0.1.0…`) | **Advanced past master plan** — P4 (area rename, `ResolveArea`, Shem assembly, Helm Shem-mount) all merged; build arc formally closed. Live cutover → Stream T. |
| **Pythia P1–P5** | 🟢 code-complete | **none** (no `pythia/*` tags despite "Phase 5 done") | **Major change** — master plan still says "Planned". All 16 stages merged incl. eval gates + observability + review fixes. |

### Stream B — the Body

| Arc | Code state | Tags cut | Reality vs. master plan |
|---|---|---|---|
| **Charon P1–P3** | ✅ complete | `charon/v0.1.0…v0.3.0` + `charon-mcp/v0.1.0` | **Tags now cut** (master plan flagged them pending). POLARS stage-in gap closed. |
| **Metis P1–P3** | ✅ complete | `metis/v0.3.1` + `metis-mcp/v0.1.1` | Matches. |
| **Midas P1** | ✅ done | `midas-arc/phase-1-foundation-v1` | Matches. **P3 still 🔧 not built.** |
| **Sysifos P1–P2** | 🟢 code-complete | **none** (gated on Testing S3.4) | Matches. |
| **Arges P1** | 🟢 code-complete | **none** (tag on merge) | Matches. |
| **Hebe P1–P4** | 🟢 code-complete | `hebe/v0.1.0`, `hebe/v0.2.0` (P1, P2); **P3/P4 tags `hebe/v0.3.0`+`v0.4.0` + `capabilities-mcp/v0.2.0` NOT cut** | **Advanced past master plan** — P3 (PG memory/workspace/receipts, registration) + P4 (iris-bff client, routine delivery) merged. |
| **Kleio P1–P5** | ✅ complete | `kallimachos/v0.1.0…v0.4.0`, `pinakes/v0.1.0…v0.2.0`, `kallimachos-mcp/v0.1.0`, `kleio/v0.1.0` | **Major change** — master plan says "Planned, 3rd Body push". Entire arc shipped incl. the Kleio agent (P5). |

### Stream T — the Testing / deploy / release harness

| Stage | State | Note |
|---|---|---|
| **P1 — component tier** (Testcontainers) | 🔧 not done | Startable now; no cluster dependency. |
| **P2 — integration harness + first context** | 🔧 not done | olymp-gated (Phase A). `theseus-runquery` specs code-started ahead-of-cluster. |
| **S3.1 contexts / S3.2 hardening** | 🔧 not done | olymp-gated (Phase A/B). Continuous regression gate, not a phase blocker. |
| **S3.3 — Iris deploy + session-smoke** | 🔧 not done | **Critical path.** Cuts `iris/*` tags, crosses **M3**. Needs only live bp-dsk. |
| **S3.4 — Sysifos deploy + workbench-smoke** | 🔧 not done | **Critical path.** Cuts `sysifos/*` tags. Needs live Midas-core + Excel loader + `sysifos-bff` Keycloak SA. |

**Stream T is the dominant remaining work.** No `iris/*`, `sysifos/*`, `pythia/*`, `golem/*`, `arges/*`, `whois/*`, `health/*`, `backstage/*` tags exist — they are all deliberately deploy- or merge-gated, and that gate is Stream T.

---

## 3. What is genuinely *not built* (as opposed to un-released)

1. **Midas Phase 3** — domain Q&A + reports + dashboards (Golem-Investment Shem, report-renderer, dashboards). The single substantive product arc still to build. Gated on M3 (via T S3.3) + Iris P4.2 (artifacts — code-complete) + the Arges tag.
2. **The entire Stream T harness** — component tier, integration nightly, and the two deploy+smoke release stages.
3. **Golem live cutover** (`golem/v1.0.0`, waypoint 8) — deploy/route/soak/cutover that retires the Python golem. Deferred out of the Golem build arc into Stream T + a Bora-owned release checklist; needs a live Iris (M3).

Everything else is *written and reviewed* — it just hasn't been deployed, smoke-tested, or tagged.

---

## 4. The remaining critical path to M7 (ship)

The ordering follows the master plan's mergepoints, updated for what's now code-complete. The spine of remaining work is **release the two live stacks → cross M3 → cutover Golem → build Midas P3 → final tag sweep + nightly rounds → M7.**

**Step 1 — Bring up the live cluster releases (Stream T deploy stages).** These need only a live `bp-dsk`, *not* the olymp integration harness.
- **T S3.3** — deploy Iris (BFF + FE, with a deployed Golem behind it for the turn-leg smoke) + session-smoke → cuts `iris/v0.1.0…v0.3.0` (+ `iris-bff/*`) → **crosses M3** ("Iris usable + Themis routing"). This is the single highest-leverage step: M3 unblocks Golem cutover, Midas P3, Hebe P4 (already coded), and Pythia's Iris UX.
- **T S3.4** — deploy Sysifos + layered workbench-smoke → cuts `sysifos-bff/v0.1.0` + `sysifos/v0.1.0` + `sysifos-arc/phase-2-data-entry-v1`. Pre-flight: live Midas-core + Excel loader + the `sysifos-bff` Keycloak service account.

**Step 2 — Golem live cutover.** With a live Iris (post-M3), run the deploy/register/route/soak/cutover checklist → `golem/v1.0.0`, retiring the Python golem (waypoint 8). Pre-req content: the **golem-ucetnictvi Shem** (assembled at boot; confirm the bundle + Ariadne `ResolveArea` live path) and the **v2-parity corpus** for the soak gate.

**Step 3 — Midas P3.** Build the domain surface (Q&A + reports + dashboards). Gates now clearing: M3 (Step 1), Iris P4.2 (done), **Arges tag** (cut on merge — do this). Watch the two live-path follow-ons: **Proteus PostgreSQL unparse** and the **`midas_app_readonly`** role.

**Step 4 — Cut the deferred release tags.** A mechanical but real sweep (see §5 tag reconciliation): Pythia (`pythia/v0.1.0…v1.0.0`), Golem (`envelope-render/v0.1.0`, `golem/*`), Arges (`arges/v0.1.0`), Hebe (`hebe/v0.3.0`, `v0.4.0`, `capabilities-mcp/v0.2.0`), Fork P5 (`whois`/`health`/`backstage`/`v0.1.0`, `argos/v0.2.0`).

**Step 5 — Open the nightly integration rounds (olymp-gated).** Once olymp test-harness Phase A/B is confirmed, light up the `theseus-runquery` / `golem-erp` (golem-ucetnictvi) / `themis-routing` / `pythia-rca` contexts. This is a continuous quality gate, not a hard ship blocker, but M7 wants the run-set green.

**M7 requires:** `golem/v1.0.0` ∧ `pythia/v1.0.0` ∧ Midas P3 ∧ Sysifos tagged (T S3.4) ∧ `hebe/v0.4.0` ∧ Kleio (P4 RAG GA — already shipped) ∧ Fork P5 independence (done) ∧ Stream T nightly green. **Of these, only Midas P3 is unbuilt; the rest are code-complete and waiting on release + verification.**

### Suggested near-term sequence

The fastest route to "v1 is live and demoable" is **deploy before you build more**:

1. **Confirm olymp Phase A/B status** (cross-repo gate; decides whether the nightly rounds can run). Doesn't block the deploy/smoke releases.
2. **T S3.3 (Iris deploy + smoke) → cross M3.** Highest leverage.
3. **Golem live cutover → `golem/v1.0.0`** against the now-live Iris.
4. **T S3.4 (Sysifos deploy + smoke).**
5. **Tag sweep** (Step 4) — cheap, removes ambiguity about "what shipped".
6. **Midas P3** — the last build arc.
7. **Open nightly contexts** (if olymp ready) → M7.

---

## 5. Cross-cutting actions & open items

- **Update the master plan.** [`master-plan.md`](docs/implementation/v1/master-plan.md) §2/§6 must move Pythia (P1–P5 code-complete), Kleio (complete, tagged), and Hebe (P1–P4 code-complete) out of "Planned". The "next Body push is Fork P5 → Hebe → Kleio" framing is fully consumed — the remaining Body work is now just **Midas P3** + the Stream-T releases.
- **Tag reconciliation (the big one).** The cut-tag set is the authoritative "what shipped" — and it badly trails the code. Un-cut despite being code-complete: **all of Pythia, all of Golem (incl. envelope-render), Iris, Sysifos, Arges, Hebe P3/P4 + capabilities-mcp/v0.2.0, and all four Fork-P5 services.** Decide which are deploy-gated (Iris, Sysifos — fold as their T stage closes) vs. merge-gated (Arges, Hebe P3/P4, Fork P5, Kleio's siblings — cuttable now).
- **Proteus parameter-bridge** — the verified fork regression that gated Golem S2.4 was **fixed** (`feat(golem): close P2 S2.4 — parametrization rail + selection`). Closed.
- **Proteus PostgreSQL unparse** + **`midas_app_readonly` role** — Arges live-path follow-ons; needed for Midas P3 S3.2's live `pg-midas` path, not for the Arges arc gate itself.
- **Charon POLARS worker read-out** — closed (`close POLARS worker stage-in gap — worker.v1 ImportDataFrame`).
- **Bora-owned content fills (gate the eval/soak gates):** Themis routing eval corpus, Pythia `AgentManifest` content, the golem-ucetnictvi Shem content, and the Golem v2-parity soak corpus. These gate the *release/nightly* steps, not the code — surface them before Step 2/Step 5.
- **olymp cross-repo gate** — T P2 + S3.1/S3.2 (the nightly rounds) depend on olymp test-harness Phase A/B. The deploy/smoke releases (S3.3/S3.4) and the Golem cutover do **not** — don't let an olymp slip stall the live releases.
- **Deferred-to-v1.1 items** stay in [`kantheon-v1.1.md`](docs/implementation/kantheon-v1.1.md); none of them block v1 ship.

---

## 6. Bottom line

v1 is **built**. The work left is to **release and verify** it: stand up the live stacks (Stream T S3.3/S3.4), cross M3, cut over Golem to `v1.0.0`, build the one remaining domain arc (Midas P3), sweep the deferred tags, and run the integration rounds. The biggest single unlock is **T S3.3 (Iris deploy → M3)** — it cascades into the Golem cutover, Midas P3, and the already-coded Hebe P4 and Pythia Iris UX. The only thing that still requires real feature engineering is **Midas P3**.

*Prepared 2026-06-27. Pair with [`master-plan.md`](docs/implementation/v1/master-plan.md) (which needs the Pythia/Kleio/Hebe status sweep described in §5).*
