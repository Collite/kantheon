# Kantheon v1 — Master Plan

> **What this is.** The single cross-arc orchestration plan for v1. It owns sequencing, the three parallel streams, the mergepoints between them, and the live status board. It does **not** restate phase/stage/task detail — each arc's `plan.md` remains the source of truth for that, and is linked from here.
>
> **What it replaces.** The former cross-cutting layer (`next-steps.md`, `aip-v1-*`, the 2026-06-12 handover/review notes) is archived under [`_archive/`](./_archive/). This doc is the new resumption pointer.
>
> **How to use it.** Returning to the project? Read §1 (streams) and §6 (status board), then jump to the arc `plan.md` you're executing. Planning a new push? Check §4 (mergepoints) for what must converge before it.
>
> *Owner: Bora. Created 2026-06-14. Last major revision **2026-06-27** (status sweep — **Pythia P1–P5, Kleio P1–P5, Hebe P1–P4 all now code-complete**; the only unbuilt arc left is Midas P3; remaining v1 work is Stream T releases + a tag-cut sweep + Golem live cutover). Prior revision 2026-06-24 (three-stream split — Testing promoted to its own Stream T; Stream B reordered to forks → Hebe → Kleio). Update on every arc phase-exit or mergepoint crossing.*

---

## 1. The three streams

v1 runs as **three parallel streams** that meet at defined mergepoints. The split is **Spine vs Body vs Testing**.

**Stream A — the Spine** (user-facing intelligence; the critical path). The chain that turns a user question into a rendered, routed, investigated answer.

> **Fork (P1–P4) → Themis → Iris → Golem → Pythia**

**Stream B — the Body** (platform services, domain, personal agent; feeds the Spine at mergepoints). Everything the Spine stands on but that can be built without blocking on it.

> **Charon · Metis · Midas · Sysifos · Arges · Fork Phase 5 (infra) · Hebe · Kleio**
>
> **Body status (2026-06-27): the entire Body is code-complete except Midas P3.** Charon, Metis, Arges and Sysifos are code-complete; Midas P1 is done (P3 still gated/unbuilt); **Fork Phase 5 (infra) landed, Hebe P1–P4 landed, and Kleio P1–P5 landed + tagged** — the 2026-06-24 "Fork P5 → Hebe → Kleio" remaining-work order is now fully consumed. The only Body feature work left is **Midas P3** (gated on M3 + Iris P4.2 + the Arges tag); everything else awaits a Stream-T release/tag, not more code.

**Stream T — the Testing harness** (cross-cutting verification; promoted to its own stream 2026-06-24). Everything beyond the in-stage mocked unit/component specs: the **component tier** (real-dep Testcontainers), the **integration tier** (full-constellation nightly on an olymp cluster), and the **live deploy + smoke** stages that bring a user-facing stack up on `bp-dsk` and gate its release tags. Stream T does not *write* product code — it *verifies and releases* what A and B produce, and so it feeds tags back into both.

The three streams are not independent — **B** delivers the data plane Pythia needs; **A** delivers the routing + UI the domain (Midas) and personal agent (Hebe) need; **T** delivers the deploy/smoke gates that cut the Iris and Sysifos release tags (so it sits on the critical path to M3 and to Sysifos completion, even though its component/integration tiers do not). Those crossings are the mergepoints in §4. Between mergepoints, each stream advances on its own.

---

## 2. Arc inventory

Phase counts are authoritative; for exact stage/task breakdowns follow the `plan.md` link. Version ladders show the *target* tag at each phase exit. **Tag reality (2026-06-27):** the cut-tag set now badly trails the code — many code-complete arcs are **un-tagged** because their release tags are deliberately gated on a Stream-T deploy/smoke stage (Iris ← T S3.3, Sysifos ← T S3.4), on PR merge (Arges, Hebe P3/P4, Fork P5), or were simply never cut after the phase merged (Pythia, Golem). Actual cut tags in this clone: `capabilities-mcp/v0.1.0`, `charon/v0.1.0…v0.3.0` (+ `charon-mcp/v0.1.0`), `metis/v0.1.0…v0.3.1` (+ `metis-mcp/v0.1.1`), `themis/v0.1.0`+`v0.2.0`, `midas-arc/phase-1-foundation-v1`, `hebe/v0.1.0`+`v0.2.0`, `kallimachos/v0.1.0…v0.4.0`, `pinakes/v0.1.0…v0.2.0`, `kallimachos-mcp/v0.1.0`, `kleio/v0.1.0`. **Un-cut despite code-complete:** all `pythia/*`, all `golem/*` (incl. `envelope-render/v0.1.0`), all `iris*`, all `sysifos*`, `arges/v0.1.0`, `hebe/v0.3.0`+`v0.4.0`, `capabilities-mcp/v0.2.0`, and the four Fork-P5 tags (`whois`/`health`/`backstage`/`v0.1.0`, `argos/v0.2.0`). See §7 for the tag-reconciliation item.

| Arc | Stream | Phases | Version ladder (targets) | Live status | Plan |
|---|---|---|---|---|---|
| **Fork** | A (P1–P4) / B (P5) | 5 | `*/v0.1.0` per module · `themis/v0.1.0` (P2) · `theseus`+`steropes`/v0.1.0 (P3) · whois/health/backstage v0.1.0 + argos/v0.2.0 (P5) | **P1–P5 DONE — fork complete (P5 2026-06-24, branch `fork-5`)**; ai-platform switch-off-able | [`fork/plan.md`](./fork/plan.md) |
| **Themis** | A | 3 | `capabilities-mcp/v0.1.0` (P1) · `themis/v0.1.0` (P2) · `themis/v0.2.0` (P3) | **P1–P3 done (`themis/v0.2.0`, 2026-06-21)** — arc complete | [`themis/plan.md`](./themis/plan.md) |
| **Iris** | A | 4 | `iris-bff/v0.1.0`→`v0.4.0` · `iris/v0.1.0`→`v0.3.0` | **P1–P4 code-complete (2026-06-23)** — tags gated on **Stream T S3.3** (Iris deploy+smoke) | [`iris/plan.md`](./iris/plan.md) |
| **Golem** | A | 4 | `envelope-render/v0.1.0` (P1) · `golem/v0.1.0` (P2) · `v0.2.0` (P3) · `v1.0.0` (P4 cutover) | **P1–P4 build arc CODE-COMPLETE (2026-06-27)** — build arc formally closed (area rename, `ResolveArea`, Shem assembly, Helm Shem-mount). **Un-tagged.** Live deploy/route/soak/**cutover → Stream T + release** (`golem/v1.0.0`). | [`golem/plan.md`](./golem/plan.md) |
| **Pythia** | A | 5 | `pythia/v0.1.0`→`v0.4.0` · `v1.0.0` (P5) | **P1–P5 CODE-COMPLETE (2026-06-27)** — all 16 stages merged incl. data plane (Charon/Metis), master-of-Golems, Iris integration, eval gates + observability. **Un-tagged** (no `pythia/*` cut). | [`pythia/plan.md`](./pythia/plan.md) |
| **Charon** | B | 3 | `charon/v0.1.0`→`v0.3.0` · `charon-mcp/v0.1.0` (P3) | **P1–P3 done · `charon/v0.3.0` + `charon-mcp/v0.1.0`** — B-side of M4 met (tags cut) | [`charon/plan.md`](./charon/plan.md) |
| **Metis** | B | 3 | `metis/v0.1.0`→`v0.3.0` · `metis-mcp/v0.1.0` (P3) | **P1–P3 done · `metis/v0.3.1`** — B-side of M4 met | [`metis/plan.md`](./metis/plan.md) |
| **Midas** | B | 3 | `midas-arc/phase-1-foundation-v1` · per-phase arc tags | **P1 done (2026-06-21)**; P3 gated (M3 + Iris P4.2 + Arges) | [`midas/plan.md`](./midas/plan.md) |
| **Sysifos** | B | 2 | `sysifos-arc/phase-{1,2}-*-v1` | **P1–P2 code-complete (2026-06-23)** — tags gated on **Stream T S3.4** (Sysifos deploy+smoke) | [`sysifos/plan.md`](./sysifos/plan.md) |
| **Arges** | B | 1 | `arges/v0.1.0` (P1) | **P1 (S1.1–1.3) code-complete (2026-06-23)** — tag on merge; gates Midas P3 S3.2 | [`arges/plan.md`](./arges/plan.md) |
| **Hebe** | B | 4 | `hebe/v0.1.0`→`v0.4.0` · (`capabilities-mcp/v0.2.0` at P3) | **P1–P4 CODE-COMPLETE (2026-06-27)** — PG memory/workspace/receipts + registration (P3), iris-bff client + routine delivery (P4). Tagged `hebe/v0.1.0`+`v0.2.0`; **P3/P4 tags + `capabilities-mcp/v0.2.0` un-cut** (on merge). | [`hebe/plan.md`](./hebe/plan.md) |
| **Kleio** | B | 5 | `kallimachos`/`pinakes`/`kleio` per-phase tags | **P1–P5 DONE + TAGGED (2026-06-27)** — `kleio/v0.1.0` (+ `kallimachos/v0.1.0…v0.4.0`, `pinakes/v0.1.0…v0.2.0`, `kallimachos-mcp/v0.1.0`). RAG GA (P4) + Kleio agent (P5) shipped — arc complete. | [`kleio/plan.md`](./kleio/plan.md) |
| **Testing** | **T** | 3 | no service tag (test infra) — cuts the **deploy-gated release tags** for Iris (S3.3) + Sysifos (S3.4) | **Planned** · S3.1 contexts code-started (ahead-of-cluster); S3.3 (Iris) + S3.4 (Sysifos) are the near-term critical-path stages | [`testing/plan.md`](./testing/plan.md) |

---

## 3. The dependency spine

The strict ordering inside each stream, drawn so the cross-stream gates (= mergepoints, §4) are visible. Stream T is drawn last because it consumes deployable assets from both A and B and feeds release tags back.

```
            in-flight trunk         ──M0──>              ──M1──>            ──M2──>      ──M3──>           ──M5──>       ──M7──>
STREAM A    Fork P1✓─Fork P2✓ ┐               Fork P3✓─Fork P4✓                                                          v1
(Spine)     Themis P1✓─Themis P2 ┴(switchover)─Themis P3✓ ─────────────────────────────────────┐                        SHIP
                                  themis/v0.1.0       theseus+steropes✓     themis/v0.2.0✓       │                         ▲
                                                          │                      │              │                         │
                                       Iris P1✓─Iris P2✓─Iris P3✓─Iris P4✓ ──────┼──────────────┼───────────┐             │
                                       (code-complete; tag via T S3.3)           │              │           │             │
                                       Golem P1~ ─────── Golem P2~ ─ Golem P3 ─ Golem P4 (cutover, golem/v1.0.0) ──┤       │
                                       (envelope-render)                         │              │           │             │
                                       Pythia P1 ─ Pythia P2 ─ Pythia P3 ─ Pythia P4 ─ Pythia P5 (pythia/v1.0.0) ─┤       │
                                           ▲                       ▲ (data plane)                             │           │
STREAM B    Charon P1✓─P2✓─P3✓ (code) ────────────────────────────┘                                          │           │
(Body)      Metis P1✓─P2✓─P3✓ (metis/v0.3.1) ─────────────────────┘ ── M4                                    │           │
            Golem-P1 envelope-render (early-startable) ──┘                                                   │           │
            Midas P1✓ ─ (Sysifos P1✓ ─ Sysifos P2✓ code) ─ Midas P3 ──────────────────────── M6 ──────────┤           │
            Arges P1✓ (code; tag on merge) ──────────────────┘ (→ Midas P3 S3.2)                            │           │
            ── next Body push, in order ──                                                                   │           │
            Fork P5 (whois/health/landing/backstage) ─→ Hebe P1─P2─P3─P4 ─→ Kleio P1─P2─P3─P4─P5 ───────────┘           │
                                              Hebe P4 needs M3 ┘     Kleio P5 needs M3 ┘                                  │
                                                                                                                         │
STREAM T    Component tier (T P1) ──────────────────────────────────────────────────────────────────── (continuous PR/merge gate)
(Testing)   Integration harness (T P2) ─ Contexts (T S3.1) ─ Hardening (T S3.2) ── nightly ─── needs olymp Phase A/B
            Iris deploy+smoke (T S3.3) ─→ cuts iris/v0.1.0 = the remaining half of M3 ──────────────────────────────────┘
            Sysifos deploy+smoke (T S3.4) ─→ cuts sysifos tags = Sysifos arc complete (feeds M6 readiness)
```

Within-arc rule (inherited from every `plan.md`): **phases are strictly sequential**; some stages parallelise inside a phase but the conservative default is sequential.

---

## 4. Mergepoints (cross-stream checkpoints)

A mergepoint is a named gate where work converges and a set of version tags must exist before downstream phases may start. Cross at a mergepoint only when **every** "requires" tag is cut. **Stream-T gates** (deploy+smoke release stages) are called out where they sit on a mergepoint's critical path.

### M0 — Forked stack live *(trunk → branch)* — **CROSSED 2026-06-17**
- **Requires:** Fork P2 exit **∧** Themis switchover (Fork Stage 2.6) → **`themis/v0.1.0`**.
- **Means:** Themis runs entirely on the in-repo forked stack; `rg cz.dfpartner` matches only Phase-3 modules.
- **Unblocks:** Fork P3 (query path); the streams branch cleanly.
- **CROSSED (2026-06-17)** — formal `themis/v0.1.0` followed 2026-06-20.

### M1 — Fork complete *(A internal; B contributes one mini-gate)* — **CROSSED 2026-06-17**
- **Requires:** Fork P3 exit (**`theseus`+`steropes/v0.1.0`**, e2e query under OBO RLS) **∧** Fork P4 exit (zero ai-platform coupling). **Both met.**
- **Mini-gate (B→A):** Fork Stage 3.4 needs **Charon Phase 1 landed**. **Met.**
- **Unblocks:** Iris execution; `theseus`+`steropes` for the Pythia P4 data plane (consumed at M4).

### M2 — Envelope contracts ready — **CROSSED**
- **Requires:** Iris P1 (**`envelope/v1`** locked + `iris-bff/v0.1.0`) **∧** Golem P1 (**`envelope-render/v0.1.0`**).
- **Status (2026-06-27):** Iris P1 code-complete (envelope/v1 + iris-bff); Golem P1 **complete** (both S1.1 + S1.2). **Crossed for all downstream consumers** (Golem P2–P4 + Pythia P1–P5 all built on these contracts). Only the formal `envelope-render/v0.1.0` tag remains un-cut — fold into the release sweep.
- **Unblocks:** Golem P2 (imports `envelope/v1`) and Pythia P1 (imports both).

### M3 — Iris usable + Themis routing  *(major A → B; now T-gated)*
- **Requires:** Iris through Phase 2 (**`iris/v0.1.0`** — BFF + FE live on `bp-dsk`) **∧** ~~Themis P3 (`themis/v0.2.0`)~~ **— Themis side MET (2026-06-21).**
- **Remaining gate is now a Stream-T gate:** Iris is **code-complete through Phase 4**, so the only thing between here and M3 is **Testing Stage 3.3 (Iris deploy + session-smoke on bp-dsk)**, which cuts `iris/v0.1.0` and closes Iris Phase 2. **M3 crosses when T S3.3 lands.**
- **Unblocks:** Golem P2 start · Iris P3 native dispatch (code already in place) · **Hebe P4** (iris-bff ≥ P2) · **Midas P3** routing · Pythia P2.

### M4 — Data plane ready  *(major B → A)* — **B-side met**
- **Requires:** **`charon/v0.3.0`** + `charon-mcp/v0.1.0` **∧** **`metis/v0.3.x`** + `metis-mcp/v0.1.x` **∧** `theseus`+`steropes/v0.1.0` (from M1).
- **Status:** Charon + Metis code-complete; Metis tagged `v0.3.1`. **B-side met** (modulo Charon's later version tags — §7).
- **Unblocks:** Pythia P4 (Charon/Metis integration). P4.1 gates on Charon; P4.2 on Metis.
- **Note:** Charon P3.1 activates `WorkerKind.METIS` against Metis P1's workspace.

### M5 — Investigation surface
- **Requires:** Pythia P1 (**`pythia/v0.1.0`** — `ListInvestigations` + `pythia.lifecycle.{user_id}` live).
- **Status (2026-06-27):** Pythia P1–P5 **code-complete** (un-tagged). The contract side of this mergepoint is met in code; it crosses for real once `pythia/v0.1.0` is cut and Pythia runs on a live cluster.
- **Unblocks:** Iris P4 Stage 4.1 (investigation inbox — FE/BFF code already landed; lights up when Pythia is live).

### M6 — Domain online
- **Requires:** ~~Midas-core (Midas P1)~~ **— MET (2026-06-21)** **∧** M3 (routing) **∧** Iris P4 Stage 4.2 (artifact system — **code-complete**, tag via T S3.3) **∧** **Arges** (`arges/v0.1.0` — code-complete, tag on merge).
- **Sysifos readiness feeds here:** Sysifos P1–P2 are code-complete; **Testing Stage 3.4** cuts the Sysifos tags and brings the back-office workbench up on bp-dsk. Sysifos P2 is the data-entry surface that populates the Midas domain.
- **Remaining gates:** M3 (via T S3.3), Arges tag, Midas P3 itself.
- **Unblocks:** Midas P3 (Q&A + reports + dashboards).

### M7 — v1 constellation complete *(ship)*
- **Requires:** **`golem/v1.0.0`** (Python golem retired, waypoint 8) **∧** **`pythia/v1.0.0`** **∧** Midas P3 **∧** Sysifos P2 (tagged via T S3.4) **∧** **`hebe/v0.4.0`** **∧** **Kleio** (at least P4 — RAG GA; P5 Kleio-agent may trail) **∧** Fork P5 (total ai-platform independence) **∧** Stream T nightly run-set green across the constellation contexts (T S3.1).
- **Status (2026-06-27):** of these, **Kleio is done & tagged** and **Fork P5 is code-complete**; **Hebe P4, Pythia, Golem(build), Sysifos** are code-complete and need only their tags (`hebe/v0.4.0`, `pythia/v1.0.0`, the Golem cutover tag, the Sysifos tags). **Midas P3 is the only unbuilt requirement.** The remaining gates are therefore: build Midas P3, run the Stream-T deploy/smoke + nightly, and cut the outstanding tags (incl. the `golem/v1.0.0` live cutover).

---

## 4a. Stream T — how the Testing harness gates A and B

Stream T has two faces with different gating character:

- **Component + integration tiers (T P1, P2, S3.1, S3.2)** are *continuous quality gates*, not phase blockers: the component tier runs on every PR/merge; the integration nightly runs the constellation contexts. They **do not gate** any Spine or Body phase exit — they catch regressions. Their own dependency is **olymp** (cross-repo): T P2/S3.1/S3.2 need olymp test-harness Phase A/B (cluster mode, `infra-up/down`, context registry, warm registry + reaper). Tracked in olymp [`docs/test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md) (D23).
- **Live deploy + smoke stages (T S3.3 Iris, T S3.4 Sysifos)** ARE phase blockers: they are the GitOps bring-up + smoke that close Iris Phase 2 / Sysifos Phase 2 and **cut their release tags**. These sit on the critical path:
  - **T S3.3 → M3** (Iris usable). The single remaining step to cross M3.
  - **T S3.4 → Sysifos complete** (feeds M6 readiness / the data-entry surface).
  - Both are GitOps deploy + browser/REST smoke (mirroring iris-bff Stage 1.4), **not** `@RequiresContext` nightly specs, so they depend only on the arc's chart/image assets (done) + a live bp-dsk (S3.4 also on a live Midas-core + Excel loader) — **not** on the Phase 2 integration harness.

**Net:** of the whole Testing arc, only **S3.3 and S3.4 are near-term critical-path** (they release Iris and Sysifos). The component/integration build-out proceeds in parallel and is olymp-gated.

---

## 5. Stream timelines (ordered)

### Stream A — the Spine
1. ~~Fork P2 + Themis P2 → **M0**.~~ **Done.** ~~Themis P3 → `themis/v0.2.0`.~~ **Done — Themis arc complete.**
2. ~~Fork P3 → P4 → **M1**.~~ **Done.**
3. ~~**Golem P1–P4 (build)**~~ **code-complete** — envelope-render + template core + conversational surface + golem-ucetnictvi Shem assembly all landed; build arc closed. **Remaining = live cutover → `golem/v1.0.0`** (Stream T + release; needs a live Iris / M3 + the v2-parity soak corpus).
4. **Iris** — P1–P4 **code-complete**. Remaining: **Testing S3.3** cuts `iris/v0.1.0…v0.3.0` and crosses **M3** *(near-term critical path)*.
5. ~~**Pythia P1–P5**~~ **code-complete** — all 16 stages (lifecycle/HITL, RCA, forecast/sim, data plane, master-of-Golems, Iris integration, eval gates). **Remaining = cut `pythia/v0.1.0…v1.0.0`** + light up the Iris UX on a live cluster.

### Stream B — the Body  *(2026-06-24 order Fork P5 → Hebe → Kleio is fully consumed; only Midas P3 remains)*
1. ~~Charon P1–P3~~ **done (`charon/v0.3.0`)**. ~~Metis P1–P3~~ **done (`metis/v0.3.1`)**. (B-side of M4 met.)
2. ~~Midas P1~~ **done.** ~~Sysifos P1–P2~~ **code-complete** (tags via T S3.4). ~~Arges P1~~ **code-complete** (tag on merge; gates Midas P3 S3.2).
3. ~~**Fork P5**~~ **code-complete** (whois / health / landing / backstage + Argos whois role source) — ai-platform switch-off-able. Tags un-cut (on merge).
4. ~~**Hebe P1 → P4**~~ **code-complete** — tagged through `hebe/v0.2.0`; P3/P4 tags + `capabilities-mcp/v0.2.0` un-cut. P4 routine→Iris turn lights up at M3.
5. ~~**Kleio P1 → P5**~~ **DONE + TAGGED (`kleio/v0.1.0`)** — arc complete (RAG GA + Kleio agent).
6. **Midas P3** (Q&A + reports + dashboards) — **the only remaining Body build.** Slots in when its gates clear: **M3** (via T S3.3) + Iris P4.2 (artifacts, code-complete) + **Arges** tag. S3.1 (Golem-Investment Shem) can begin once M3.

### Stream T — the Testing harness
1. **T Phase 1 — component tier** — startable now (no cluster, no olymp dep); S1.2 done (Brontes; Charon spec deferred to Charon DB edges).
2. **T Phase 2 — integration harness + first context** — needs olymp Phase A; specs for `theseus-runquery` code-complete ahead-of-cluster.
3. **T S3.1/S3.2 — contexts + hardening** — needs olymp Phase A/B; `golem-erp`/`themis-routing`/`pythia-rca` contexts.
4. **T S3.3 — Iris deploy + session-smoke** → cuts `iris/v0.1.0`, **crosses M3.** *(near-term critical path)*
5. **T S3.4 — Sysifos deploy + workbench-smoke** → cuts Sysifos tags, completes the Sysifos arc. *(near-term critical path; needs live Midas-core + Excel loader)*

---

## 6. Status board

| Arc | Stream | Status | Next action |
|---|---|---|---|
| Fork | A/B | **P1–P5 DONE — fork complete (P5 2026-06-24)** | All five phases shipped. P5 (whois/health/landing/backstage + Argos whois role source) landed on branch `fork-5`; technical wave forked in-repo, ai-platform switch-off-able. **Next Body push is now Hebe** (then Kleio). Tags on merge: whois/health/backstage v0.1.0, argos/v0.2.0 |
| Themis | A | **P1–P3 DONE — `themis/v0.2.0`** — arc complete | Routing layer shipped. Themis half of **M3 met**. No further Themis work until cross-arc consumers (Iris native dispatch — code already in place) |
| Iris | A | **P1–P4 CODE-COMPLETE (2026-06-23)** — reviewed (#49) | Engineering done across all 14 stages (sessions, FE cutover, routing UX + typed actions + observability, inbox/artifacts/discovery/feedback). **Only remaining step: Testing Stage 3.3** (deploy + session-smoke on bp-dsk) which cuts `iris/v0.1.0…v0.3.0` and **crosses M3** |
| Golem | A | **P1–P4 BUILD ARC CODE-COMPLETE (2026-06-27)** — build arc formally closed | P2 (parametrization rail + Proteus bridge restore + selection + typed tables), P3 (format pipeline/clarification-resume/parity diff-harness), and P4 (domain→area + `AREA_QA`, Ariadne `ResolveArea`, Shem assembled from four sources at boot, Helm Shem-mount) all landed. New shared lib `pattern-params`. **Un-tagged** (`envelope-render/v0.1.0`, `golem/*` not cut). **Remaining = Stream T:** live deploy/route/soak/**cutover → `golem/v1.0.0`** (retires Python golem) needs a live Iris (M3) + the v2-parity soak corpus. See golem/plan.md §6 Handoff + §10 |
| Charon | B | **P1–P3 done · `charon/v0.3.0` + `charon-mcp/v0.1.0`** — tags cut | Closed for the Spine; B-side of M4 met. POLARS stage-in gap closed |
| Metis | B | **P1–P3 done · `metis/v0.3.1`** | Closed — B-side of M4 met |
| Pythia | A | **P1–P5 CODE-COMPLETE (2026-06-27)** | All 16 stages merged: lifecycle/HITL, procedural + RCA + forecast + simulation, Charon/Metis data plane, master-of-Golems, Iris integration, eval gates + observability. **Un-tagged** — cut `pythia/v0.1.0…v1.0.0` as the release sweep (§7); P5 Iris UX lights up once Iris is live (M3 / M5) |
| Midas | B | **P1 DONE (2026-06-21)** · `midas-arc/phase-1-foundation-v1` | **Next Midas work = Phase 3** — gated on **M3** (via T S3.3), **Iris P4.2** (artifacts, code-complete), **Arges** tag (S3.2). S3.1 (Golem-Investment Shem) can begin once M3 |
| Sysifos | B | **P1–P2 CODE-COMPLETE (2026-06-23)** (#48) | Engineering done (bff edge, shell+draft, sync CRUD, bulk grid, import, balance, reconcile, audit). **Only remaining step: Testing Stage 3.4** (deploy + workbench-smoke) which cuts the Sysifos tags. Needs live Midas-core + Excel loader + the `sysifos-bff` Keycloak service account |
| Arges | B | **P1 (S1.1–1.3) CODE-COMPLETE (2026-06-23)** | Postgres worker; mirrors Brontes + `SET LOCAL app.tenant_id` RLS. **Tag `arges/v0.1.0` on merge** → opens Midas P3 S3.2. Watch: Proteus PG unparse + Midas `midas_app_readonly` role (live path) |
| Hebe | B | **P1–P4 CODE-COMPLETE (2026-06-27)** | All four phases merged (axis/preset model, llm-gateway, security split, offline tolerance, PG memory/workspace/receipts, instance deploy, cap-mcp registration, iris-bff client, routine delivery). Tagged through `hebe/v0.2.0`; **cut `hebe/v0.3.0`+`v0.4.0` + `capabilities-mcp/v0.2.0`** (on merge). P4 routine→Iris turn lights up once Iris is live (M3) |
| Kleio | B | **P1–P5 DONE + TAGGED — `kleio/v0.1.0`** | Arc complete. Warehouse core, Pinakes pipelines + LLM wiki-compile, RAG GA (P4), Kleio agent (P5, `KNOWLEDGE` routing + eval gate). Soft-deps (Themis P3, Iris notebook surface) and the Prometheus `EmbedText` pre-flight all satisfied |
| Testing | **T** | **Planned** · S3.1 contexts code-started ahead-of-cluster | **Near-term critical path: S3.3 (Iris) + S3.4 (Sysifos)** deploy/smoke — release Iris and Sysifos. **Phase 1 (component tier)** startable independently; Phase 2 nightly + S3.1/S3.2 need olymp Phase A/B. Pairs with olymp [`test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md) (D23) |

**Frontier (2026-06-27): all product code is complete — both Streams A and B are code-complete except Midas P3. The remaining v1 work is the release-and-verify layer: Stream T (deploy/smoke + integration), a tag-cut sweep, the Golem live cutover, and the one unbuilt arc, Midas P3.**

*Stream A (Spine).* Fork (P1–P4) + Themis (P1–P3) done. **Iris (P1–P4), Golem build arc (P1–P4), and Pythia (P1–P5) are all code-complete.** The critical path to **M3** ("Iris usable + Themis routing"; Themis half already met) is the **Stream-T** step **Testing Stage 3.3** (Iris GitOps deploy + session-smoke on the olymp `bp-dsk` cluster — **no ai-platform**), which cuts `iris/v0.1.0` and crosses M3. M3 then unblocks the **Golem live cutover** (`golem/v1.0.0`, retires the Python golem) and lights up the already-coded Pythia Iris UX. Pythia and Golem are **un-tagged** — fold their tags into the release sweep.

*Stream B (Body).* The Body is code-complete bar one arc: **Charon + Metis** done & tagged (M4 B-side met), **Midas P1** done, **Sysifos P1–P2** code-complete (awaiting T S3.4 to tag), **Arges P1** code-complete (tag on merge), and the former "next push" — **Fork P5 (infra), Hebe P1–P4, Kleio P1–P5** — has fully landed (Kleio tagged `kleio/v0.1.0`; Hebe tagged through `v0.2.0` with P3/P4 un-cut; Fork-P5 tags un-cut). The **only remaining Body feature work is Midas P3** (Q&A + reports + dashboards), which slots in when M3 (via T S3.3) + Iris P4.2 (done) + the Arges tag clear.

*Stream T (Testing).* Promoted to its own stream 2026-06-24. **S3.3 (Iris) and S3.4 (Sysifos)** are the near-term critical-path stages — they release the two code-complete user-facing stacks. The **component tier** (Phase 1) is startable independently of any cluster; the **integration nightly** (Phase 2 + S3.1/S3.2) is olymp-gated (olymp Phase A/B) and is a continuous regression gate, not a phase blocker.

### Next push (updated 2026-06-27) — "release and verify what's built"

> **The 2026-06-24 push below is consumed.** Golem's build arc (P1–P4) and Fork P5 both landed, and Pythia/Kleio/Hebe completed alongside. The build-everything-on-fixtures phase is over. The next push is now the **release-and-verify layer**: stand up the live stacks, cross M3, cut over Golem, build the last arc (Midas P3), sweep the un-cut tags, and open the nightly rounds.
>
> **Ordered (fastest route to "v1 live"):** (1) confirm olymp Phase A/B (gates only the nightly rounds, not the deploy/smoke releases); (2) **T S3.3** — deploy Iris + smoke → **cross M3** (highest leverage; cascades into Golem cutover, Midas P3, the coded Hebe P4 + Pythia Iris UX); (3) **Golem live cutover → `golem/v1.0.0`** against the live Iris; (4) **T S3.4** — deploy Sysifos + smoke; (5) **tag-cut sweep** (Pythia, Golem/envelope-render, Arges, Hebe P3/P4 + cap-mcp v0.2.0, Fork-P5); (6) **Midas P3** — the last build arc; (7) open the nightly integration contexts → **M7**. The pre-2026-06-27 plan-of-record below is retained for history.

---

#### (Historical) Next push as decided 2026-06-24 — "build Golem + fork the rest, then open the full rounds"

Two tracks run in parallel, then converge on a testing pass that brings the whole constellation up:

1. **Stream A = Golem** (against fixtures/mocks — needs no live services). P1 S1.2 (charts → `envelope-render/v0.1.0`, closes M2) → P2 S2.4 (**leads with the Proteus T7 parameter-bridge restoration**, then the rail/guard/selection/tabledetails → `golem/v0.1.0`) → P3 (conversational surface) → **P4.1 Golem-ERP Shem content** (Bora-owned; required for the `golem-erp` integration context). Pythia stays parked this push.
2. **Stream B = Fork P5** (whois / health / landing / backstage) — fully parallel, off the critical path. Doesn't gate testing; its payoff is that after P5 ai-platform is **switch-off-able**, so the full rounds run against a self-contained kantheon.
3. **Then Stream T = the full testing pass / "complete rounds."** Mind the **two tiers with different gates**: the **deploy/smoke** stages (S3.3 Iris, S3.4 Sysifos) need only a live **bp-dsk**; the **integration nightly contexts** (the multi-service rounds) additionally need **olymp Phase A/B** (confirm before counting on them). **Interleave, don't strictly sequence:** S3.3 (deploy Iris — its turn-leg smoke also wants a deployed Golem) crosses **M3** and makes Iris live → **Golem P4 live cutover** (`golem/v1.0.0`) runs against the live Iris → then open the `theseus-runquery` / `golem-erp` / `themis-routing` (/ later `pythia-rca`) nightly contexts.

**Shape:** `Golem P1–P3 + Golem-ERP Shem (on fixtures)  ∥  Fork P5  →  T S3.3 deploy (Iris + Golem live, crosses M3) + T S3.4 (Sysifos)  →  Golem P4 cutover  →  open the nightly context rounds (olymp-gated).`

**Two hard prerequisites baked into "Golem in place":** the **Proteus T7 fix** (inside S2.4 — every parametrized-query round depends on it) and the **Golem-ERP Shem** (P4.1 — the `golem-erp` round can't run without it).

---

## 7. Open items & risks

- **~~Golem plan refresh (2026-06-24)~~ — CONSUMED (2026-06-27).** The five folded-in feature deltas all shipped in Golem P2–P3; retained below for history. The Golem arc plan was a faithful **2026-06-12 snapshot** and predated five ai-platform Golem feature arcs: **pattern parametrization** (typed-parameter rail + `aip_pattern_params` shared lib + `PATTERN_PARAM_UNFILLED` fail-fast guard), **`param_fill` missing-param clarification/resume** (the 4th `PendingClarification.kind`), **pin-by-id entity resume** (a Themis/Resolver RESUME contract dependency), **row-detail selection** (`resolve_selection` node + `selection_context`), and **typed table formatting + client-side paging** (the `typed_action_handler.py` sort/filter/paginate path was **removed** — FE owns paging now). The plan + task lists have been updated; largest edits land in **Golem S2.4** (parametrization rail — a near-new stage), **S3.2** (typed-actions task mostly re-scoped; add param_fill + pin-by-id + select-row-as-selection), and **S1.1/S3.1** (TableDetails + chart-on-compare kind-inference). Also dropped: any live-log SSE port (deleted in ai-platform Golem Phase 8). Added: an outbound-OTel-traceparent-propagation task. See [`golem/plan.md`](./golem/plan.md).
- **Tag reconciliation (2026-06-27 — the backlog grew).** Many code-complete arcs are **un-tagged** in this clone. The §2 ladder lists *targets*; treat the actual cut-tag set as authoritative for "shipped". The backlog now: **deploy-gated** — Iris (← T S3.3), Sysifos (← T S3.4); **merge-gated** — Arges (`arges/v0.1.0`), Hebe P3/P4 (`hebe/v0.3.0`+`v0.4.0` + `capabilities-mcp/v0.2.0`), Fork P5 (`whois`/`health`/`backstage`/`v0.1.0`, `argos/v0.2.0`); **never cut after phase merge** — all of Pythia (`pythia/v0.1.0…v1.0.0`) and all of Golem incl. `envelope-render/v0.1.0` (`golem/v1.0.0` is the cutover, separate). Charon's later tags (`charon/v0.2.0`/`v0.3.0`) and Kleio's full ladder **are now cut**. Fold the deploy-gated tags as their Stream-T stage closes; cut the merge-gated + never-cut ones in a deliberate sweep.
- **~~Proteus parameter-bridge regression~~ — RESOLVED (2026-06-27).** The fork-dropped orchestrator wiring was restored in Golem S2.4 (`feat(golem): close P2 S2.4 — parametrization rail + selection`): the Proteus-side `toSqlParams` + `parseToRelNode(parameters=…)` + `Resolve.apply(…, preparedSql)` are back, and the `pattern-params` shared Kotlin lib landed. `{name}`-style named-parameter SQL is rewritten correctly. No open Proteus arc needed.
- **Postgres worker (Arges).** Code-complete (P1 S1.1–1.3). Two **live-path** follow-ons remain (not arc gates) tracked in [`arges/plan.md`](./arges/plan.md): the **Proteus PostgreSQL unparse** gap and the Midas-side **`midas_app_readonly`** role — both needed for Midas P3 S3.2's live `pg-midas` path. Tag `arges/v0.1.0` on merge → opens Midas P3 S3.2.
- **~~Charon P3 worker read-out~~ — RESOLVED.** `steropes` workspace read-out / stage-in gap closed (`feat(charon,steropes): close POLARS worker stage-in gap — worker.v1 ImportDataFrame`).
- **Stream-T ↔ olymp cross-repo gate.** T Phase 2 + S3.1/S3.2 depend on olymp test-harness **Phase A/B**. T S3.3/S3.4 depend only on a live bp-dsk + arc assets. Keep the olymp side (cluster mode, `infra-up/down`, context registry, warm registry, reaper) tracked in olymp `docs/test-harness.md`; do not let an olymp slip stall the S3.3/S3.4 releases (they don't need it).
- **⚠ Confirm olymp Phase A/B readiness before opening the full nightly rounds (Next-push gate).** The "complete rounds" (the multi-service integration contexts — `theseus-runquery` / `golem-erp` / `themis-routing` / `pythia-rca`) are the **only** part of the next push gated on an external repo: they need olymp test-harness **Phase A** (cluster mode + `infra-up/down` + context registry + WireMock component) for the first context and **Phase B** (additional contexts + warm registry + reaper) for the rest. **Action:** verify olymp's Phase A/B status (olymp [`docs/test-harness.md`](../../../../collite-gh/olymp/docs/test-harness.md), D23) at the start of the testing pass. If olymp lags, the deploy/smoke releases (S3.3 Iris, S3.4 Sysifos) and Golem's live cutover can still proceed on bp-dsk — only the automated nightly contexts wait. Owner: olymp-side (cross-repo); kantheon owns the specs+fixtures+context-names.
- **Bora-owned content fills** (now gate the *release/nightly* steps, not the code): Themis routing eval corpus, Pythia `AgentManifest` content, the **golem-ucetnictvi `ShemManifest`** content (assembled-Shem path), and the Golem **v2-parity soak corpus** (needed for the live cutover gate). Surface before the Golem cutover (Step 2) and the nightly rounds (Step 5).
- **Deferred-item ledger.** Anything pushed to v1.1/v1.x lands in [`../kantheon-v1.1.md`](../kantheon-v1.1.md), not here.

---

## 8. Conventions & maintenance

- **Hierarchy** (task → stage → phase) and naming (`Phase N` / `Stage N.M` / `Tn`; branches `feat/<phase>-<stage>-<short>`; tags `<module>/vX.Y.Z`) per [`../planning-conventions.md`](../planning-conventions.md).
- **Three artefacts before any task list:** `architecture.md` + `contracts.md` (under `docs/architecture/<arc>/`) + `plan.md` (here). All arcs have these.
- **This doc tracks only the cross-arc layer.** When an arc crosses a phase exit or a mergepoint is reached, update §6 (status board) and the relevant §4 mergepoint, and re-tag. Per-stage progress stays in the arc `plan.md` and its task lists.
- **Three streams (since 2026-06-24):** A = Spine, B = Body (order: Fork P5 → Hebe → Kleio), T = Testing (component + integration + the S3.3/S3.4 deploy-gated releases). Stream T cuts the deploy-gated release tags for A and B.
- **Up / across:** [`README.md`](./README.md) · architecture: [`../../architecture/`](../../architecture/) · design: [`../../design/`](../../design/).
