# Sysifos — Phase 1 (Foundation) — task-list overview

> Entry point for Phase 1 executors. Phase goal, stage map, and aggregate progress. Per `planning-conventions.md` §4.
>
> **Status (2026-06-23): READY TO START.** All Midas dependencies met (Midas Phase 1 done). Begin at Stage 1.1. No stages done yet.
>
> **Reads with.** [`plan.md`](./plan.md) §3, [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md).

## Phase 1 goal

`agents/sysifos-bff` deployable in local K3s (auth, tenant forwarding, session/draft/stream/dictionaries, Midas-core client); `frontends/sysifos` shell (nav, login, placeholder routes); `sysifos/v1` published in its new home; `bff-base` extracted or folded; hybrid write model skeletoned (sync proxy live, async draft path proven on `DRAFT_CLIENT`).

## Stage map

| Stage | Title | File | DONE |
|---|---|---|---|
| 1.1 | Arc bootstrap + `sysifos/v1` relocation | [`tasks-p1-s1.1-bootstrap-proto.md`](./tasks-p1-s1.1-bootstrap-proto.md) | ☐ |
| 1.2 | `bff-base` + Sysifos-BFF skeleton | [`tasks-p1-s1.2-bff-skeleton.md`](./tasks-p1-s1.2-bff-skeleton.md) | ☐ |
| 1.3 | Hybrid write skeleton + FE shell | [`tasks-p1-s1.3-write-fe-shell.md`](./tasks-p1-s1.3-write-fe-shell.md) | ☐ |

## Dependencies into Phase 1 — status 2026-06-23

**All Midas dependencies MET** (Midas Phase 1 done, `midas-arc/phase-1-foundation-v1`, 2026-06-21). This phase is startable now.

- Midas arc P1 S1.2 (so `midas/v1` exists to import) — before 1.1. **MET.**
- Midas-core write API live (Midas P1 S1.3) — before 1.3. **MET.**
- `tools/capabilities-mcp` running **(MET)**; Iris-BFF skeleton for the `bff-base` audit **(MET — iris-bff live on bp-dsk)**; Keycloak service account — before 1.2. **Keycloak service account is the one OPEN config item** (not needed for 1.1).

## Phase 1 DONE

- [ ] All three stage DONE criteria checked.
- [ ] `just build-kt sysifos-bff` + `just build-fe sysifos` green; CI passes.
- [ ] Demo: log in → nav shell + tenant name → post `DRAFT_CLIENT` via the draft path → `DraftCommitted` SSE → client present in Midas-core.
- [ ] Tag `sysifos-arc/phase-1-foundation-v1`.
