# B — Capability coverage map

Status: 🟢 **converged 2026-07-09** — B-1 classes ratified as drafted; B-2 = α single Golem (+ Pythia + one deliberate gap question); B-3 free-SQL stays in the spine (SHOULD). See control-room log; note Q-8 (governance-cameo mechanics under the single-Golem constraint) opened for F/D. Classification: **MUST** (the story breaks without it) · **SHOULD** (in the spine, cut-safe under time pressure) · **SAT** (satellite — detachable) · **SUB** (substrate — works invisibly, surfaces only via provenance/lore) · **OUT** (explicitly not in this demo).

## The arc, beat by beat (Hartland Stores, "we just closed 2025")

### Beat 1 — Cold open: the Monday briefing
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| Hebe scheduled turn → iris-bff (`TurnOrigin.SCHEDULED`) | **MUST** | a briefing that *arrived by itself* overnight | Hebe P4 (iris-bff client), D3 wave 6 |
| Iris inbox (badge + panel) | **MUST** | the entry point of the whole demo | Iris P4 S4.1 |
| Envelope blocks in the briefing (table + chart + markdown) | **MUST** | "Marketplace revenue −11% H2 YoY" KPI + trend chart | envelope-render (Golem P1, done) |

### Beat 2 — Orient: conversational Q&A with Golem
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| Themis intent classification (PROCEDURAL) + routing to golem-hartland | **MUST** | one input box; the right agent just answers | Themis P3, D3 wave 4 |
| Golem pattern plans (preferred queries from the TTR-M model) | **MUST** | instant, correct answers on the scripted beats (P-2) | Golem P2-P4, D-2 query set |
| Drill + amend chips ("by channel", "by category", "only Marketplace", "2024 vs 2025") | **MUST** | conversation refines like a thought process | Golem P3 |
| ChartIntent → Vega-Lite render | **MUST** | charts, not just tables | envelope-render (done) |
| Provenance ⓘ + SQL expander on every block | **MUST** | P-3: click any number → the SQL that made it | PD-9 (BlockProvenance, stamped free by envelope-render) |
| Free-SQL plan source (one unscripted-looking question) | SHOULD | "it's not a decision tree" moment | Golem P2; rehearse the gate |
| Clarification round-trip (one deliberately ambiguous question) | SAT | graceful "which year did you mean?" chips | Golem P3 S3.2 |

### Beat 3 — Escalate: "investigate why"
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| InvestigateChip → routing_hint re-route (never Golem→Pythia direct) | **MUST** | the handoff: context travels, nothing is re-asked | PD-1/PD-4 (HandoffContext), Iris P3 |
| Pythia investigation start + budget visible | **MUST** | "this is a different kind of work" — cost/steps on screen | Pythia P1-P3, D3 wave 4 |
| **Hypothesis-tree pane, live** | **MUST** | branches spawn, gather evidence, die; one survives — *the* δ-lite machinery screen | Pythia + Iris P4 (PD-2 pane) |
| Cross-fact evidence queries (sales/returns/inventory joins) | **MUST** | branches die on real numbers (flat web kills cannibalization, etc.) | TTR-M model breadth (workstream D) |
| Conclusion + LooseEnds + hypothesis_id-linked provenance | **MUST** | a findable, defensible answer: "Memphis DC, weeks 31–47" | Pythia P3 |
| Halt/cancel-with-partials | OUT (rehearsal-only fallback) | — | (PD-2; exists, not staged) |

### Beat 4 — Act: pin it
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| Pin envelope → dashboard artifact (with provenance + display state) | **MUST** | the finding becomes a living tile | Iris P4 S4.2 (PD-6) |
| Replay vs reproduce (one presenter sentence) | SHOULD | "this tile re-executes; this evidence is frozen" | same |

### Beat 5 — Look forward: forecast + what-if
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| Themis FORECAST intent → Pythia → Metis (Fit/Project) | **MUST** | "holiday 2026 by channel" with intervals — rides F-2 seasonality | Metis arc + Pythia P4 S4.2 |
| SimulateScenario ("Memphis fixed + win-back promo") | SHOULD | side-by-side scenario curves | same |
| Charon staging under the hood | SUB | only in provenance ("source: worker session df") | Charon P3 |

### Beat 6 — Close the loop
| Capability | Class | What the audience sees | Depends on |
|---|---|---|---|
| Create the Hebe routine from chat ("brief me every Monday") | **MUST** | the demo ends where it began — the loop closes | Hebe P4 |
| Pythia lifecycle → inbox event | SHOULD | the scheduled brief lands as an inbox item (PD-2) | Iris P4 |

## Satellites (γ shape — include/cut live)
| Satellite | Class | Shows | Depends on |
|---|---|---|---|
| Governance cameo (2nd login) | SAT (first to include) | visibility_roles: the CFO-only agent is *invisible, not forbidden* to Maya; same question, different world (PD-8, OBO/RLS in one sentence) | kantheon-security in place; 2 personas in Keycloak |
| Compound question → Themis SPLIT | SAT | "Compare Web vs Marketplace for 2025 — and why did returns spike in Q3?" → two agents, one turn (PD-13) | Themis P3 S3.2 |
| Discover page (DomainCards + example chips) | SAT (closer) | "what else can I ask" — the self-serve tease (PD-7) | Iris P4 S4.3 |
| Feedback 👍/👎 + reask_agent | SAT | the product learns; the user re-routes (PD-3/PD-14) | Iris P4 S4.3 |
| ε audience coda (throwaway session) | SAT (still undecided, F decides) | unscripted question; Themis gap handling is the net — "we don't have supplier data" done gracefully (GapKind) | rehearsed refusal paths |

## Substrate (never named on stage; surfaces only as provenance/lore)
Theseus→Proteus→Argos→Kyklop→Arges query path (visible **only** inside the ⓘ SQL expander — which is the point: the whole fork works and nobody has to say so) · Prometheus LLM gateway · Ariadne model serving + ResolveArea · capabilities-mcp registry · OBO token flow + RLS (named once, in the governance cameo) · NATS/SSE streaming (felt as "it's live", never named).

## OUT (recorded so nobody re-litigates at rehearsal)
Midas / Sysifos / Kleio-Kallimachos (other arcs' demos) · audit-log walkthrough (mention-only during governance cameo) · budget *enforcement* ceilings (v1.1) · Hebe console/admin · plan-DAG pane (deferred v1.1) · multi-language (demo is English-only per FI-4; the cs/en Shem prompt duality is a talking point, not a beat).

## Convergence record (2026-07-09)
- ~~B-1~~ — classes ratified as drafted (Bora).
- ~~B-2~~ — **α: single golem-hartland + Pythia**; routing visibility = correct dispatch + one deliberate gap question. Implies **D-1 = one Golem, one area**. RoutingPickChip demo dropped.
- ~~B-3~~ — free-SQL stays in the spine (SHOULD; rehearse the confidence gate).
- **Q-8 (new, → F/D)**: governance-cameo mechanics under single-Golem: the cameo needs *something* visibility-gated to contrast personas. Options: a second thin CFO-only Shem existing **only as satellite scenery** (compatible with B-2α — it never routes in the spine), vs. reducing the cameo to the gap moment (weaker: no invisible-not-forbidden contrast). Decide in F.
