# Kleio/DocWH — cross-mart leakage security note (P4 Stage 4.2)

> **Sign-off (T6).** The mitigation for the "cross-mart leakage (RAG over my
> sources)" risk (architecture §14, `kantheon-security.md`). Evidence: the
> negative RLS tests (no-overlap denial) + the edge-before-store enforcement.

## The risk

RAG retrieval (`getContext`) and browse run over **marts** (notebooks). A caller
must never receive chunks/pages from a mart they may not read — neither directly
nor laundered through a citation, drilldown, or graph traversal.

## The mitigation (defence in depth)

1. **Callers forward the user OBO bearer, never service identity.** Golem,
   Pythia, Kleio, and the browse FE all call `library.*` with the end user's
   bearer (security §2/§3.6). The `kallimachos-mcp` edge reads the caller's roles
   from that bearer (Argos `bearer` source) — `IdentityResolver` parses `sub` +
   `realm_access.roles`.

2. **RLS at the MCP edge, BEFORE the store is touched.** `MartRlsGuard` resolves
   the caller identity, fetches the target notebook's ACL (`GET /notebooks/{id}`
   → owner + `visibility_roles`), and applies the predicate (`MartRls.canRead`):
   read mart `N` iff `N.owner == caller` **OR** `N.visibility_roles ∩ caller_roles
   ≠ ∅`. The `"*"` admin scope requires `kantheon-admin`. A denial returns
   `PERMISSION_DENIED` and increments `kallimachos_mart_rls_denied_total` —
   **the store endpoint is never called.** Write ops (`createNotebook`/
   `addToNotebook`) are ops/admin-gated.

3. **Store defence-in-depth.** Even past the edge, every retrieval is
   mart-scoped: `getContext`/`query` resolve `notebook_id` to its member source
   ids and filter to those, so a non-member mart yields nothing. Pages are
   reached only via `DERIVED_FROM` from in-mart parts. (The store-side bearer-role
   re-check via Argos is the integration backstop; the mart-membership scoping is
   the v1 in-store guarantee.)

4. **Audit.** Each ingest/retrieval writes a `request_log` row (node, actor,
   notebook, action, ts) — the receipts trail (security §4). Wired with the live
   PG store.

## Evidence

- `MartRlsSpec` — owner access, role-overlap access, **no-overlap denial**,
  anonymous (blank-bearer) denial, admin-scope gating.
- `MartRlsGuardSpec` — an authorised caller is **allowed** (RAG GA); an
  unauthorised caller is **denied at the edge** (the store is never touched); write
  ops + `"*"` scope require admin.

## Residual / deferred

- Node-level RLS on `getPage`/`getSource`/`traverse` (a page reachable across
  marts) — v1 browses within an already-authorised mart; finer node-RLS is v1.x.
- Live Keycloak token signature verification — the wrapper trusts the OBO bearer
  the authenticated edge minted; full verification is the integration suite.

**Signed off:** Bora (cross-mart-leakage mitigation, P4 S4.2).
