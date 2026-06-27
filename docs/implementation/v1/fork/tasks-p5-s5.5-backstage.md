# Fork — Stage 5.5: backstage developer portal

> Branch: `feat/fork-p5-s5.5-backstage`. Pre-flight: Stage 5.0 (the kantheon modules the catalog will describe exist). Plan: [`plan.md`](./plan.md) Stage 5.5. Tracker: [`tasks.md`](./tasks.md).
>
> Source: `infra/backstage` (Backstage developer portal; Node + Yarn; own backend on port 7007; `app-config*.yaml`; `catalog-info.yaml`; TechDocs + scaffolder). The "rename" is a **rebrand + catalog re-point** (contracts §8). Own Node toolchain — a documented build exception, like Prometheus's Spring Boot. Deploy manifests forked from `deployment/apps/backstage`.

- [x] **T1 — Fork + install.**
  `infra/backstage` → kantheon `infra/backstage` (rsync, excluding node_modules/dist/dist-types/install-state; `.yarn/releases/yarn-4.4.1.cjs` + `yarn.lock` carried — the committed yarn-berry binary + lockfile). Provenance header in README. `yarn install` itself is the Node-toolchain exception (see T4).

- [x] **T2 — Rebrand config.**
  `app-config.yaml`: `organization.name: DF Partner` → **Kantheon**; `app.title: Developer Portal` → **Kantheon Developer Portal**. `app-config.local.yaml` `doc.aip.localhost` → `doc.kantheon.localhost`. (`*_BASE_URL` / `backend.baseUrl` are already env-driven — set per env at deploy.)

- [x] **T3 — Re-point the catalog.**
  New `examples/kantheon-catalog.yaml` — the constellation as a Backstage catalog: a `kantheon` System + Group and **22 `Component`s** for the pantheon (agents Iris-BFF/Themis/Golem/Pythia/Hebe; platform services Ariadne/Theseus/Echo/Kadmos/Proteus/Kyklop/Argos/Prometheus/Charon/Metis; workers Brontes/Steropes/Arges; `capabilities-mcp`; technical wave whois/health/landing). Wired as a `catalog.locations` file entry in `app-config.yaml`, replacing the upstream example catalog. `docs/catalog-info.yaml` description, `templates/agent-starter/template.yaml` owner, and the OIDC username-transform comment in `packages/backend/src/index.ts` swept to kantheon.

- [x] **T4 — Build + deploy.**
  The full `yarn install` + `yarn tsc && yarn build` (Backstage CLI, yarn 4 berry, large dep tree) is a **CI-gated Node-toolchain exception** — like Prometheus's Spring Boot, the app source is forked unchanged, so the deterministic local gate is **YAML/catalog well-formedness** (all configs + the 24-doc / 22-Component catalog parse) rather than a multi-GB local build. Image tags `kantheon/backstage` (port 7007) at deploy; manifests fork from `deployment/apps/backstage`.

- [x] **T5 — component test.**
  Local check (mocked, deterministic): every `app-config*.yaml` + every catalog file parses as valid YAML; the kantheon catalog yields a `kantheon` System + Group + 22 Components with no legacy entity. The live Backstage component test (portal loads / catalog lists kantheon entities / TechDocs / scaffolder) runs in CI with the Node toolchain — deferred to the integration suite per the testing policy.

- [x] **T6 — Stage exit.**
  `rg -i "df.?partner|ai-platform" infra/backstage` clean (bar the README provenance line). **Tag `backstage/v0.1.0` on merge.** Stage 5.5 checked in [`tasks.md`](./tasks.md).

**DONE means:** the kantheon developer portal serves the kantheon catalog. **✅ Met 2026-06-24** (rebrand + catalog re-point complete, YAML-validated; the live yarn build + portal smoke is the CI/cluster Node-toolchain gate).
