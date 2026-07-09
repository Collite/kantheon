# F — Script, rehearsal & fallbacks

Status: 🟢 **converged 2026-07-09** (F-1..F-4 in the control-room log; this doc is the deliverable).
This is the **presenter script** for the live demo — the third build-phase input alongside `05-d` (model) and `06-e` (cluster). Persona: **Maya Chen, Senior Category Manager, Hartland Stores** (A-2) — her remit spans categories across all three channels, which is exactly why a cross-category channel drop lands on her desk. Actor: Bora. Demo-present: **a Monday in mid-January 2026** — "we just closed 2025" (C-1a).

Converged forks (session S8):

- **F-1 (resolves Q-8)** — governance cameo = **α thin CFO-only Shem**: `golem-hartland-finance` over the *same* hartland model/area, `visibility_roles: [kantheon-role-finance]`; satellite scenery only — structurally unroutable in Maya's spine. Ripples: `05-d` addendum (second Shem overlay), `06-e` E-3 (CFO persona carries `kantheon-role-finance`).
- **F-2 (resolves ε)** — audience coda = **γ conditional-go**: fully rehearsed, staged after the applause point, Bora decides live (room + clock). Skipping is invisible.
- **F-3 (confirms C-3a residue)** — **S5 stays OFF**. Cannibalization dies on genuinely flat Web; lore covers demand loss ("unfulfilled Marketplace buyers bought elsewhere — off-platform"). Parked: revisit after the first full rehearsal if the kill feels thin.
- **F-4** — timing budget & default cut as tabled below.

Notation: **[BORA]** spoken framing (paraphrasable — meaning is fixed, wording is not) · **[MAYA]** typed/clicked input (**exact** — these must hit their plan source, P-2) · **[SCREEN]** expected envelope · **[FALL]** the beat's fallback move. Query refs `#n` = the D-2 preferred set. On-screen numbers marked ⟨…⟩ pend Q-4 seed calibration — freeze them into this script after the post-seed recon (see Rehearsal, R0).

---

## Timing budget (F-4)

| Slot | Content | Box | Cum. |
|---|---|---|---|
| Beat 1 | Cold open: the Monday briefing | 2′ | 2′ |
| Beat 2 | Orient: Q&A with Golem | 6′ | 8′ |
| Beat 3 | Investigate: Pythia RCA | 8–9′ | 17′ |
| Beat 4 | Act: pin it | 1.5′ | 18.5′ |
| Beat 5 | Look forward: forecast + what-if | 4.5′ | 23′ |
| Beat 6 | Close the loop: the Monday routine | 1.5′ | 24.5′ |
| Sat-G | Governance cameo (CFO profile) | 2′ | 26.5′ |
| Sat-D | Discover closer | 1.5′ | 28′ |
| — | Slack / transitions | ~2′ | 30′ |

**If-time pocket** (only if ahead at the B3 checkpoint): Sat-S (Themis SPLIT compound) 2′ · Sat-F (feedback/reask) 1′. **ε coda**: after Sat-D, only if ≤27′ elapsed and the room is warm (F-2 protocol below). **Cut ladder** under time pressure (in order): pocket → Sat-G → Sat-D. The spine (B1–B6) is never cut.

---

## Pre-show (T-60 → showtime)

Assumes E-5 items 1–6 green and the freeze window declared. All steps are the E-4 checklist, sequenced:

- **T-60** · `demo-reset` (session state truncated; standing fixtures preserved: Channel Health dashboard, Monday routine, Keycloak users, `hartland` DB untouched). Verify the Rehearsal dashboard (fallback Layer 2) is intact.
- **T-45** · Warm the estate: one throwaway Golem turn + one throwaway Pythia turn in a scratch session (kills cold JVM + first-token latency); delete the scratch session.
- **T-30** · **Fire the Monday routine manually** (Hebe) → the briefing lands in Maya's inbox. Verify the inbox badge shows.
- **T-15** · LLM provider reachability + latency probe; Metis worker alive; SSE stream check.
- **T-10** · Browser A logged in as `maya@hartland.example`, Iris open, **inbox badge visible but unopened**; Browser B (separate profile) logged in as Dan Whitaker (CFO), minimized. Stage zoom/font set; notifications off.

---

## Beat 1 — Cold open: the Monday briefing (2′)

**[BORA]** "This is Maya. She runs category P&L at Hartland Stores — six stores in Tennessee, nationwide Web shop, and a Marketplace where third-party sellers sell through Hartland's platform and Hartland does the fulfillment. It's Monday morning, mid-January. Maya hasn't asked anything yet — but something is waiting for her."

**[MAYA]** Click the inbox badge → open **"Monday channel health brief"** (TurnOrigin.SCHEDULED, arrived overnight).

**[SCREEN]** Briefing envelope: markdown summary + KPI table + trend chart (#2 `channel_revenue_yoy`, #1 `channel_revenue_monthly`): Stores ⟨+0.4%⟩, Web ⟨+1.2%⟩, **Marketplace H2-2025 ⟨−11%⟩ YoY**, worst month November ⟨−12%⟩. Every block carries ⓘ.

**[BORA]** "Nobody wrote this. A routine Maya set up watches channel health every week and *this* week it flagged something: Marketplace — a billion-dollar channel — lost eleven percent, half-year on half-year. Let's find out why. And note — every number on this screen can be clicked open down to the SQL that produced it. We'll use that."

**[FALL]** Briefing missing/malformed → re-fire the routine from Browser B admin (10 s, narratable as "let me pull this morning's brief"), or open the same brief pinned on the Rehearsal dashboard. Seam: beat 2 does not depend on the inbox item — Maya's first question stands alone.

---

## Beat 2 — Orient: conversational Q&A with Golem (6′)

Five turns. Turns 1–3 are pattern plans (instant, P-2); turn 4 is the free-SQL moment; turn 5 is the deliberate gap.

**T1 · the trend.**
**[MAYA]** `How did Marketplace revenue develop in 2025?` *(Shem example_question; #1, channel=Marketplace)*
**[SCREEN]** Monthly line/bar chart: normal seasonal base Jan–Jul, then the autumn ramp visibly *missing its step* from August; table beneath. Chips: *by category* · *compare with 2024* · *only Q4* · **Investigate**.
**[BORA]** "One input box. Behind it, the platform classified the question, routed it to the right agent, compiled it against a governed model of Hartland's warehouse, and executed it — " *(click ⓘ on the chart, open the SQL expander)* " — and here's the proof. That SQL, that database, that timestamp. Nothing on this stage is a mock-up."

**T2 · isolate the channel.**
**[MAYA]** `Compare Web and Marketplace revenue, 2024 vs 2025` *(example_question; #1/#2 two-channel cut)*
**[SCREEN]** Grouped chart: Web flat ⟨+1%⟩, Marketplace down ⟨−6% FY / −11% H2⟩.
**[BORA]** "So it's not e-commerce softness — Web is fine. Something is eating exactly one channel."

**T3 · the category reflex.**
**[MAYA]** `Which categories drove the H2 2025 drop?` *(example_question; #3, channel=Marketplace, 2025)*
**[SCREEN]** Category table/bars: the drop is ⟨spread evenly across all ten categories⟩.
**[BORA]** "Maya's a category manager — her reflex is 'which of my categories broke?' Answer: *all of them, evenly*. That's the moment your mental model fails. A product problem hits products. This hit a channel."

**T4 · the free-SQL moment.**
**[MAYA]** `What was the average order value on Marketplace vs Web in December 2025?`
**[SCREEN]** Small comparison table — AOV ⟨roughly unchanged YoY⟩. ⓘ shows **generated SQL** (plan source: free_sql), visibly not a canned pattern.
**[BORA]** "That one is *not* in any playbook — there's no prepared query for it. The agent wrote that SQL itself, inside the same governed model, and it shows its work the same way. It's not a decision tree; it's an analyst."

**T5 · the deliberate gap.**
**[MAYA]** `What did the slump cost us in profit?`
**[SCREEN]** Graceful gap: profitability isn't part of the governed model; offers what it *can* do (revenue, quantities, returns).
**[BORA]** "And when it doesn't know — it says so. Profitability isn't modeled in this deployment, so it won't invent a number. In a room full of AI demos, I'd argue this is the most important answer you'll see today." *(P-3 anchor line.)*

**[FALL]** Any turn misses its plan or answers oddly → re-ask with the exact rehearsed phrasing (the Shem's example_questions are verbatim-safe); prefer chips over typing on retry. T4 gate goes sideways → skip it (SHOULD, cut-safe: "I'll come back to unscripted questions at the end" — feeds ε). T5 never fails by construction (the gap *is* the answer).

---

## Beat 3 — Investigate: Pythia RCA (8–9′) — the centerpiece

**[MAYA]** Click **Investigate** on the T3 category answer. *(InvestigateChip → routing_hint re-route; context travels — nothing is re-asked.)*

**[SCREEN]** Pythia investigation opens: question restated, **budget visible** (cost/steps), and the **hypothesis-tree pane** — the δ-lite machinery screen.

**[BORA]** "This is a different kind of work, so the platform hands it to a different agent — an investigator. Two things to watch: the meter — this work has a *budget*, visible, not a runaway bill — and this pane. This is the investigator's actual working memory, live. Not a slide."

**[SCREEN → BORA, narrating the tree as branches resolve]** Expected choreography (order may vary — narrate whatever resolves, the *evidence* is deterministic):

1. **"Customers shifted to Web"** → evidence #10 overlap + #1 Web trend → ⟨overlap constant, Web flat⟩ → **dies**. "If they'd migrated, we'd see them on Web. We don't."
2. **"Demographics shifted"** → #12 → ⟨buyer age flat at ~45⟩ → **dies**.
3. **"Promotions starved the channel"** → #13 → ⟨promo share dense as always⟩ → **dies**.
4. **"It's regional"** → #11 → ⟨even across states⟩ → **dies**. "Everywhere at once, all categories at once — whatever this is, it's *upstream* of customers."
5. **"Fulfillment/operations"** → #6 returns by reason → ⟨"Did not get it on time" spikes Q3–Q4⟩ → **survives, corroborated**; → #4 revenue by DC → ⟨four DCs flat, **Memphis collapses**⟩; → #8/#9 inventory → ⟨**zero-on-hand streak, weeks 31–47**, Memphis only⟩.

**[SCREEN]** **Conclusion block**: *Memphis DC stopped fulfilling for ~17 weeks (late July–mid November 2025); Marketplace orders are warehouse-pinned, so its volume collapsed through the autumn ramp; Web rerouted around it.* Full provenance per evidence block (hypothesis_id-linked) + **LooseEnds** ⟨e.g. residual unexplained delta; recommend ops post-mortem on Memphis WMS⟩.

**[BORA]** "Branches died on real numbers — you watched it happen. What survived: the Memphis distribution center went dark for seventeen weeks. Web reroutes across DCs; Marketplace fulfillment is pinned to its warehouse — so one building's failure surfaced as a *channel* problem. And notice the investigator tells you what it's *still not sure about*." *(point at LooseEnds)*

**T-confirm · the human hand back on the wheel.**
**[MAYA]** `Which items were out of stock at Memphis DC in October 2025?` *(example_question; #8/#9)*
**[SCREEN]** The streak, item-level, October slice.
**[BORA]** "And Maya can keep pulling the thread herself — the investigation isn't a black box she has to accept."

**[FALL]** The heavyweight beat gets the heavyweight net. (a) Tree stalls / one branch hangs → narrate LooseEnds culture, move on when conclusion lands. (b) Budget exhausts or Pythia errors → **open the completed rehearsal investigation** (kept post-rehearsal, surfaced via the Rehearsal dashboard pin) and narrate the finished tree — say it plainly: "this is the same investigation from our dress rehearsal — I'll show you the recorded reasoning rather than make you watch a retry" (P-3: honesty over theater). (c) Wrong branch survives (data drift — should be impossible on frozen data) → hard stop, switch to (b). Seam out: beat 4 pins the *rehearsal* conclusion identically; the audience experience is continuous.

**⏱ Checkpoint** at conclusion: target ≤17′ elapsed. >19′ → drop the pocket; >21′ → drop Sat-G; >23′ → drop Sat-D.

---

## Beat 4 — Act: pin it (1.5′)

**[MAYA]** Pin the conclusion block + the T1 monthly channel chart to the **Channel Health** dashboard.

**[SCREEN]** Dashboard with both tiles; provenance and display state carried.

**[BORA]** "Two clicks: the finding and the chart she'll keep watching. One sentence on how these tiles live: the *chart* re-executes on refresh — it's a living view; the *conclusion's evidence* is frozen — it's a record. Replay versus reproduce. Your auditors will care about that distinction even if nobody else does."

**[FALL]** Pin fails → the same two tiles already exist on the Rehearsal dashboard; open it, narrate the same sentence. Seam: beat 5 doesn't reference the dashboard.

---

## Beat 5 — Look forward: forecast + what-if (4.5′)

**T1 · forecast.**
**[MAYA]** `What should we expect for holiday 2026, by channel?`
**[SCREEN]** Themis FORECAST → Pythia → Metis (Fit/Project on #1 series): per-channel curves with **confidence intervals**, seasonal shape (F-2) clearly learned. ⓘ on the forecast block names the model fit (source: worker session — Charon surfaces only here, as lore).
**[BORA]** "Different intent, different machinery: a statistical engine fits the five-year seasonal pattern and projects the holiday. The shaded bands are honesty again — a forecast without uncertainty is a guess."

**T2 · what-if (SHOULD — cut-safe).**
**[MAYA]** `Assume Memphis is back to normal from June and we run a win-back promotion on Marketplace in November — what does Q4 look like then?`
**[SCREEN]** Scenario curve side-by-side with baseline; delta quantified ⟨Marketplace Q4 recovering to ~trend + promo lift⟩.
**[BORA]** "Same engine, counterfactual inputs. This is the meeting where 'what happened' becomes 'what do we do' — and it's the same conversation window."

**[FALL]** Metis slow/fails → T1's forecast is pinned on the Rehearsal dashboard (narrate intervals from the tile); skip T2 (its status is SHOULD precisely for this). Seam: beat 6 stands alone.

---

## Beat 6 — Close the loop (1.5′) — the applause point

**[MAYA]** `Set this up as my Monday morning brief — channel health, flag anything unusual.`

**[SCREEN]** Hebe routine created from chat: schedule visible (Mondays), scope = channel health.

**[BORA]** "And that is the routine that wrote the briefing we opened with. The story you just watched — detection, diagnosis, decision, watch — isn't a workflow someone wired together. It's one conversation, and it just closed its own loop." *(Beat. This is the ending — land it.)*

**[FALL]** Creation fails → open the *existing* Monday routine's config ("here's the one that's been running") — the loop-closing line still lands verbatim.

---

## Satellite G — Governance cameo (2′) — F-1/Q-8α

Placement: after beat 6, as "one more thing". Detachable — no beat references it.

**[BORA]** "One more thing. Everything you saw was Maya's world. Kantheon builds a *per-user* world. This is Dan Whitaker — Hartland's CFO." → switch to **Browser B (Dan Whitaker, CFO — S-13)**.

**[SCREEN]** CFO's Discover page: **two** domain cards — Hartland Analytics *and* **Hartland Finance**.

**[CFO]** `What is our total returns exposure in dollars for 2025?` → routes to `golem-hartland-finance`, answers with ⓘ as always.

**[BORA]** switch back to Browser A, open Maya's Discover: **one card**. "For Maya, the finance agent isn't locked, greyed out, or 'access denied'. It *does not exist* in her world. Identity travels with every request down to the database — the security model isn't in the UI, so there's nothing to click around. Same platform, different worlds." *(Audit trail gets its one mention here.)*

**Rehearsed Q&A** (the sharp question *will* come): "What does Finance see that Maya's agent doesn't?" → "In this demo, a finance-scoped view over the same data — we deliberately excluded profitability from the whole deployment. In a real one, the CFO agent carries the restricted models and data: margin, payroll, contracts. The point is the *mechanism* — you just watched it."

**[FALL]** Browser B broken → cut the satellite silently (it's scenery; nothing depends on it).

---

## Satellite D — Discover closer (1.5′)

On Maya's profile (natural continuation after G's switch-back).

**[SCREEN]** Discover page: the Hartland Analytics DomainCard + example-question chips (the Shem's six).

**[BORA]** "Last thing — how does anyone *start*? They open this page. Here's what Hartland's world can answer, with live examples — every chip is a real question you watched work today. No training course, no query language. That's the pitch: your data, answering in sentences, showing its work."

**[FALL]** Discover down → deliver the same close verbally over the beat-6 screen. Cut-safe.

---

## If-time pocket (only if ahead at the B3 checkpoint)

- **Sat-S · Themis SPLIT (2′):** **[MAYA]** `Compare Web and Marketplace for 2025 — and why did returns spike in Q3?` → one turn, two agents (Golem comparison + Pythia mini-investigation), composed answer. **[BORA]** "It decomposed the question itself — routing isn't one-question-one-agent."
- **Sat-F · Feedback (1′):** 👍 the forecast block; one sentence on the learning loop + reask affordance.

## ε coda — live audience questions (conditional-go, F-2)

**Go-criteria (all three):** base cut + intended satellites done · ≤27′ elapsed · the room is engaged (questions already shouted, energy high). Any doubt → skip; beat 6/Sat-D already ended the show.

**Protocol:** open a **fresh throwaway session** (never Maya's arc session — pins and history stay clean). Invite **one or two** questions, repeat each aloud (mic + rephrase-to-model buffer — Bora may normalize phrasing while repeating, which is honest presenter craft, not deception). Hard stop at 30′.

**The net is the feature:** in-model questions ride pattern plans or free-SQL with visible provenance; out-of-model questions ("what about competitor pricing?", "supplier terms?") hit the counter_example gaps — narrate: "and *that's* the correct answer — it doesn't have supplier data, and it tells you so instead of hallucinating." A graceful refusal in front of a live room is the strongest trust beat available; an invented number is the only fatal outcome, and the gap machinery is exactly what prevents it.

**[FALL]** Any response goes weird → "that one deserves more than 30 seconds — come find me after" (rehearsed exit line), close on Sat-D's pitch line.

---

## Fallback architecture (summary)

Layered; each beat lists its own [FALL] above.

- **L0 · Prevention** — freeze window + pre-show T-60 sequence (above) + E-5 bar. Most failures are prevented, not handled.
- **L1 · In-beat retry** — rehearsed verbatim phrasings; chips over typing; one retry max, then escalate a layer.
- **L2 · The Rehearsal dashboard** — a standing pinned mirror of every key envelope (briefing, T1 chart, conclusion + evidence, forecast), captured during the final dress rehearsal, plus the completed rehearsal *investigation*. Survives `demo-reset` (standing fixture). Move: open the tile, narrate identically, return to live at the next seam. Always announced honestly ("from our dress rehearsal") — P-3 applies to the presenter too.
- **L3 · Seams** — every beat's entry stands alone (B2's first question, B3's chip, B5's forecast, B6's routine); satellites drop silently. The show can lose any single beat's *liveness* without losing the story.
- **L4 · The recording** — a full-run screen recording from the final rehearsal (same script, same data), cued on a second machine. Used only on total-estate failure: "the cluster gods have spoken — let me show you this morning's run" and narrate live over it. The script is written so the narration works identically over the recording.

---

## Rehearsal protocol

**R0 · Freeze the numbers.** After seed calibration (Q-4 + r13 + post-seed recon): replace every ⟨placeholder⟩ in this script with the actual on-screen values; sync the Shem `example_questions` (05-d) with the final T-phrasings **verbatim**; new goldens where D-2 requires. *This script is not rehearsable while placeholders remain.*
**R1 · Table read.** Script only, no cluster. Timing by hand against the F-4 boxes; tighten [BORA] lines (target: every framing ≤3 sentences spoken).
**R2 · Beat drills.** Each beat 3× clean in isolation on the showcase, **including one deliberately broken run per beat** (kill the pod / drop the plan) to drill its [FALL] move until the switch is smooth (<15 s, no visible fluster).
**R3 · Full runs, stopwatch.** Record per-beat actuals vs boxes; adjust boxes or cut lines here, not on stage. Capture the L2 pins + the rehearsal investigation + the L4 recording on the best run.
**R4 · The bar (E-5 item 7).** `demo-reset` → full arc, twice consecutively, zero operator intervention, inside 30′. Not demo-ready until this passes.
**R5 · Freeze-window smoke.** Daily until show day: pre-show T-60 sequence + beat 1 + an abbreviated beat 3 (tree opens, first branch dies) + teardown reset.

---

## Open / build ripples out of F

- **R0 placeholder freeze** is a build-phase gate (needs seeded `hartland` + recon variants — E-5 item 2).
- `05-d` addendum: `golem-hartland-finance` overlay (Shem #2: description_for_router, 2–3 preferred-query subset, `visibility_roles: [kantheon-role-finance]`, counter_examples shared) — carried there.
- `06-e` E-3: CFO persona = `cfo@hartland.example` with `kantheon-role-finance` (+ area role); E-4 pre-show gains the T-60→T-10 sequence above; E-5 item 6 verifies the two-cards-vs-one Discover contrast.
- LooseEnds exact contents + tree-choreography variance: observe in R2/R3, then freeze the narration lines that reference them.
