# E — Showcase cluster spec

Status: 🟢 **converged 2026-07-09** (E-1, E-2 in the control-room log). Like `05-d`, this is a build-phase input — the demo-infrastructure counterpart to the TTR-M spec. Build-time opens: Q-12 (hardware), Q-13 (warehouse PG placement), naming lean.

## E-1 — Host: a dedicated showcase cluster

A **new olymp GitOps cluster** (naming lean: `clusters/showcase` — not "hartland": the cluster is Kantheon's, the retailer lives inside it). Rationale (decision log): full isolation from bp-dsk dev churn; the D1/D2 chart estate (22 charts + shared library) makes bring-up mechanical; bp-dsk stays the test bench. Rejected: bp-dsk-pinned (`:testing` + `pullPolicy: Always` churn is the opposite of demo stability), olymp integration cluster (torn up by design).

- **Versions:** bring-up may start `:testing`, but the showcase flips to **pinned MP-4 release tags** as soon as they cut, and thereafter only ever moves pin-to-pin. **Freeze window** before demo day: no chart/image/model changes; only `demo-reset`.
- **Q-12 (open, Bora/ops):** where the cluster physically runs (second machine / VM / cloud). Sizing note from R1: the constellation idles heavy on CPU *requests* — reuse the bp-dsk request-shrink values from day one.

## E-2 — Data: a separate `hartland` database

**`tpc-ds-1g` stays pristine (1998–2003, canonical TPC-DS).** The demo world is a new database restored from a pristine dump, with all surgery applied only there. This makes the surgery ripple checklist nearly empty — the `tpcds-query` integration context, its year-literal asserts, and the frozen `q.tpcds.*` fixtures never notice anything happened (Q-11 shrinks to "none, by construction").

**Revised data pipeline (supersedes the order in `surgery/README.md` §Order):**

1. `pg_dump -Fc tpc-ds-1g` → **pristine dump** (the canonical artifact; also = disaster recovery for bp-dsk tests).
2. `createdb hartland` + restore the pristine dump (on bp-dsk for the surgery work, or directly on the showcase cluster's warehouse PG).
3. `surgery/run-redate.sh dsk 23 hartland` (script now takes the DB as arg 3, **default `hartland`** — it refuses to run against `tpc-ds-1g` unless explicitly named).
4. Seed scripts (Memphis DC Meltdown) + dim display-name UPDATEs → all against `hartland`.
5. `pg_dump -Fc hartland` → **demo dump** — the versioned artifact the showcase cluster restores from; stored in the `tpcds-staging` Seaweed bucket under the `hartland/` prefix (S-10; no new bucket).

**Connection:** new named connection **`pg-hartland`** in Arges (read-only, no tenant envelope — mirrors `pg-tpcds`) + Kyklop `world.table-connections` mapping the hartland model's tables → `pg-hartland`. The D-2 preferred queries target `pg-hartland`; `pg-tpcds` remains for fixtures. Provenance on stage reads `db=hartland` — no benchmark vocabulary leaks (closes the loop on C-4/GI-6).

- ~~**Q-13**~~ — **RESOLVED 2026-07-09 (SWEEP-1/S-14): dedicated CNPG** for the `hartland` warehouse, mirroring `test-pg` (isolates warehouse scans from agent-state I/O; the §7.1 one-PG rule concerns *agent* DBs, so this is legal).

## E-3 — Estate roster (from the B map + SCOPE-1)

**In:** full query path — theseus (+mcp), proteus, argos, kyklop, **arges** (with `pg-hartland`) · registry/core — capabilities-mcp, **ariadne** (+mcp; **model Git source = `Collite/hartland`**, Q-9), echo, kadmos · **prometheus with REAL LLM provider keys** (ExternalSecrets; WireMock is for integration tests — the demo answers live) · agents — **themis, pythia, golem-hartland, golem-hartland-finance (F-1 cameo Shem — ConfigMap overlay, no new image), hebe** · pythia dependencies — NATS JetStream, Seaweed, Polars worker, **charon** (+mcp), **metis** (+mcp) · FE — **iris + iris-bff at Iris-P4 scope** (inbox, artifacts/pins, discover, feedback) · **keycloak** with the demo realm: **Maya Chen** `maya@hartland.example` (Senior Category Manager, `kantheon-area-hartland` — S-2) + **Dan Whitaker** `cfo@hartland.example` (CFO: `kantheon-area-hartland` + `kantheon-role-finance` — per **F-1**/S-13) · whois/health as the deploy profile requires.

**Out / optional:** brontes (MSSQL fixture — nothing routes to it), steropes (include only if a dependency surfaces), midas\*, sysifos\*, kleio/kallimachos/pinakes, backstage; `frontends/landing` optional cosmetic entry point.

## E-4 — Demo operations

- **`demo-reset`** (just recipe, showcase-only): truncate *session* state — iris sessions/SSE, Pythia investigations (except the pinned rehearsal investigation), feedback — while **preserving** standing fixtures (S-4): the **"Channel Health"** dashboard, the **"Rehearsal"** fallback dashboard + its pinned rehearsal investigation (07-f L2), the **"Monday channel health brief"** Hebe routine, Keycloak users, and the `hartland` warehouse (read-only, never dirtied). Beat 1 depends on the routine surviving reset; the *briefing inbox item* is re-generated per rehearsal (fire the routine manually = the pre-show step).
- **Pre-show checklist:** all pods warm (no cold JVM/first-token on stage), one throwaway Golem turn + one Pythia turn to warm caches, LLM provider reachability + latency check, browser session logged in as Maya, second browser profile logged in as the CFO persona.
- **State backup:** the demo dump (data) + an export of the standing fixtures (routine, pins) so a broken rehearsal restores in minutes.

## E-5 — Definition of demo-ready (the E exit bar, checked in F rehearsals)

1. Showcase estate green on pinned tags; freeze window declared.
2. `hartland` DB restored from the demo dump; seed sanity: r01/r08 recon variants show the Meltdown exactly as spec'd (−10..12% Marketplace H2-2025; 17-week Memphis streak).
3. `ResolveArea("hartland")` + Shem assembly green; golem-hartland registered and routable; every `example_question` hits a pattern plan through the **full live path** (theseus→…→arges→hartland).
4. All 15 preferred queries return correct rows live (the showcase's own `hartland-query` run-set, sibling of `tpcds-query`).
5. Themis routes the scripted beats correctly incl. the gap question; Pythia RCA finds Memphis DC unaided in ≤ rehearsed budget; Metis forecast renders with intervals.
6. Both personas log in; visibility difference verified per **F-1**: CFO's Discover shows two DomainCards (Analytics + Finance), Maya's shows one; the finance agent routes for the CFO and never for Maya.
7. `demo-reset` → full arc rehearsal passes twice consecutively without operator intervention.
