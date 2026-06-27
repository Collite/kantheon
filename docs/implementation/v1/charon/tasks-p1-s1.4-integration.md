# Charon P1 Stage 1.4 — SUPERSEDED → see [`tasks-p1-s1.4-closeout.md`](./tasks-p1-s1.4-closeout.md)

> **Renamed + realigned 2026-06-26.** This stage was originally framed as a
> live-K3s **integration pass** that gated the `charon/v0.1.0` re-tag. Under the
> testing policy ([`../../planning-conventions.md`](../../planning-conventions.md) §4,
> locked 2026-06-14) live-K3s / Testcontainers / e2e are a **separate integration
> suite** and do not gate a stage. Stage 1.4 is therefore now a **Phase 1
> closeout** whose re-tag gate is mocked-unit + CI + code only; the live-K3s
> items move to integration-suite carry-overs.
>
> **The live document is [`tasks-p1-s1.4-closeout.md`](./tasks-p1-s1.4-closeout.md).**
> Part A there is the `charon/v0.1.0` re-tag gate (and the Pythia 4.1 CG1 unblock);
> Part B preserves the live-K3s round-trip / fault-injection detail as tracked
> integration-suite carry-overs.
>
> Authoritative R1–R8 closeout (done in code, re-review 2026-06-15):
> [`tasks-review-006.md`](../../../tasks-review-006.md).
