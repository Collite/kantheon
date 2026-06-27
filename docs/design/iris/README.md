# Iris — Design

Iris is the user-facing frontend of the Kantheon constellation: a Vue 3 SPA plus a Kotlin/Ktor backend-for-frontend service. It owns the conversation as the user sees it (turn log, EntityContext, snapshot history, edit-and-resend), dispatches each turn through Themis, and streams the chosen agent's response back to the SPA via SSE.

The name comes from Greek mythology — Iris is the messenger goddess and rainbow bridge between worlds. Fitting: Iris bridges the user and the constellation.

## Files

| File | What |
|---|---|
| [`iris-design.md`](./iris-design.md) | Locked design: physical composition, responsibilities, conversation state lifecycle, FormatEnvelope rendering, dispatch flow, module map (BFF + SPA), heritage from current `golem/frontend/`, resolved decisions. |
| [`iris-brainstorming.md`](./iris-brainstorming.md) | Process record: how the Iris-evolution brainstorm (2026-05-10) reframed the existing single-Analytical-Agent into Iris (FE+BFF) + Themis (router) + Golem (per-domain template) + Pythia (peer agent). Locks the Kotlin BFF, dispatch shape, and "Iris owns the conversation as the user sees it" decisions. |

## What's elsewhere

- Implementation architecture for the Iris BFF lands under [`../../architecture/iris/`](../../architecture/iris/) when the Iris arc starts.
- The phased Iris BFF plan and per-stage task lists land under [`../../implementation/v1/iris/`](../../implementation/v1/iris/) when the Iris arc starts.
- The current `golem/frontend/` (the v2/v2.1 work that is Iris's direct prototype) lives at `/Users/bora/Dev/golem/frontend/`.

## Up / across

- Up: [`../README.md`](../README.md) — design entry point.
- Across: [`../themis/`](../themis/) — Themis is the routing dependency Iris calls on every turn.
