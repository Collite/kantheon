# Runbook — the bp-dsk on-demand integration run-set (WS-C2 T7 → MP-4)

> **What this is.** The developer's on-demand full run of every integration context against the
> **bp-dsk** cluster. It complements — does not replace — the scheduled nightly on **bp-olymp01**
> (`.github/workflows/integration-nightly.yml`, which runs only `theseus-runquery`). WS-R contract:
> the on-demand full run is the developer's; the nightly move stays on bp-olymp01.
>
> **Reads with.** [`tasks-c2-integration-contexts.md`](./tasks-c2-integration-contexts.md) (the T1–T7
> task list), [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md)
> §7 (bp-dsk), [`tasks-r1-bp-dsk-run-mode.md`](./tasks-r1-bp-dsk-run-mode.md) (`--kube dsk`).

## The run-set

Authoritative list: [`deployment/test/bp-dsk-run-set.txt`](../../../../deployment/test/bp-dsk-run-set.txt)
(one `@RequiresContext` name per line). Ordered lean → heavy.

| Context | Drives (module) | Proves | Active tier | Gated tier |
|---|---|---|---|---|
| `pythia-rca` | `agents/pythia` | Pythia deploys + boots (DB-less) + PD-8 admission on the async edge | missing-bearer 403 + cross-user visibility 403/200 | `investigationLive` — full loop to a terminal Status (needs the RCA chain + scripted LLM stubs) |
| `golem-erp` | `agents/golem` | the Golem agent turn (PD-8 Shem admission → LLM MiniPlan → render) | 401 + 403 admission **and** the `STATUS_DONE` render turn (LLM roundtrip) | — (answer-turn already on) |
| `themis-routing` | `agents/themis` | Themis deploys + boots past its capabilities gate + the resolution graph | MCP `resolve` smoke (well-formed terminal outcome) | `routingLive` — REST `/v1/resolve` agent-routing (needs scripted joint/route stubs) |
| `theseus-runquery` | `tools/theseus-mcp` | the MSSQL/Brontes query result path + OBO identity discipline | missing-bearer fail-closed **and** (T6) real rows from MSSQL via the Proteus fixture model | `rlsPolicyContext` — RLS (needs a non-fixture Ariadne-backed Argos) |
| `tpcds-query` | `tools/theseus-mcp` | the Postgres/Arges query result path (Goals 2+4) | the 4 curated SF1 shapes return the exact oracle (12/30/30/3) | — |

## Prerequisites

1. **kubectl context `dsk`** reachable (`kubectl --context dsk get ns`). The kantheon readiness gate
   uses the current kubeconfig context; `it-bp-dsk` passes `-PkubeContext=dsk` so the fabric8 client
   targets the right cluster.
2. **olymp checkout** at `~/Dev/collite-gh/olymp` (or set `OLYMP_DIR`).
3. **`:testing` images published** for every service each context deploys. Publish a missing one with
   `just publish-image <module-path>` (add a third arg when the image name ≠ the module dir, e.g.
   `just publish-image agents/themis testing themis-mcp`). Images the run-set needs:
   - pythia-rca: `pythia`
   - golem-erp: `golem`, `ariadne`, `prometheus`
   - themis-routing: `themis-mcp`, `capabilities-mcp`, `kadmos`, `echo`, `prometheus`
   - theseus-runquery: `theseus`, `theseus-mcp`, `proteus`, `argos`, `kyklop`, `brontes`
   - tpcds-query: the theseus chain + `arges` (Postgres worker), against the **standing** test-pg
   - The `:testing` tag is mutable (`pullPolicy: Always`), so republish after any source change to a
     deployed service (e.g. the T6 Proteus fixture-model edit needs `just publish-image services/proteus`).
4. **Platform members** the contexts assume already stood up on bp-dsk: the `mssql` component (with
   its `mssql-init` seed) for theseus-runquery; the standing `test-pg` (+ `pg-tpcds-ro` external-secret)
   for tpcds-query. See the olymp `test-contexts/<name>/context.yaml` for each.

## Running

```sh
# One context (bring-up → integrationTest → teardown, with a service-log dump on failure):
just it-bp-dsk pythia-rca

# The whole run-set, sequentially (namespace-per-run isolates each; continues past a red context
# and prints a PASS/FAIL summary; exits non-zero if any failed):
just it-bp-dsk-all
```

Each context runs the **root** `integrationTest` task with `-Pcontext=<name>`; only the spec carrying
`@RequiresContext("<name>")` activates — every other spec skips. So `it-bp-dsk-all` walks the five
contexts through five isolated bring-up/teardown cycles.

## On failure

`it-bp-dsk` dumps each deployed service's recent logs **before** teardown (OTEL exporter noise is
pre-filtered), and the kotest assertion failure — with its `withClue` (`httpStatus`/`body`/`status`)
— prints **above** the `BUILD FAILED` line. Read that first: an integration failure is usually a
service-side error the client only sees as a bad envelope. The namespace is torn down on exit
(idempotent), so capture what you need from the dump.

## Definition of done → MP-4

All five contexts green via `just it-bp-dsk-all` on bp-dsk, with the nightly on bp-olymp01
unaffected. Then cut the deferred per-service release tags (contracts §9) — the program finish line.
Gated tiers (`investigationLive`, `routingLive`, `rlsPolicyContext`) stay off; they are their own
arcs' follow-ups, not MP-4 blockers.
