# Fork — Stage 5.4: landing frontend

> Branch: `feat/fork-p5-s5.4-landing`. Pre-flight: Stage 5.2 (health roll-up to read). Plan: [`plan.md`](./plan.md) Stage 5.4. Tracker: [`tasks.md`](./tasks.md).
>
> Source: `frontends/landing` (Vue 3 + Vite + TS + vue-i18n; multilingual landing / service dispatcher; Nginx image). No JVM. The "rename" is a **rebrand** (contracts §8). Locales: `en/de/cs/sk/hu`. `.env` carries link + Keycloak vars.

- [x] **T1 — Fork + install.**
  `frontends/landing` → kantheon `frontends/landing` (rsync, excluding node_modules/dist/cache); provenance header in README; `npm install` clean (lockfile carried).

- [x] **T2 — Rebrand i18n.**
  `src/i18n/locales/*.json` `header.title` (the actual key — contracts §8 said `app.title`) → **Kantheon** across all five locales (`en/de/cs/sk/hu`). Also swept: `index.html` `<title>`, `src/config.ts` health-URL example host, `docs/technical/requirements.md`, README.

- [x] **T3 — Re-point links + auth + catalog.**
  `.env` / `.env.example`: Keycloak realm `myrealm` → `kantheon` (client `landing` kept); added the `VITE_HEALTH_URL` var the runtime `generate-env.sh` template already referenced; link vars stay deploy-injected (point at kantheon ingress at deploy). **`public/services.json` rewritten** to the kantheon estate (agents/services/workers/MCP/technical) + retained fabric-infra; its `tech` keys match the Stage-5.2 health `technologies.*` keys exactly; legacy ai-platform / erp-sql tiles dropped.

- [x] **T4 — Build + deploy.**
  `npm run build` green (vue-tsc type-check + `vite build`, 702 modules). Nginx `Dockerfile` + `nginx.conf` forked unchanged; image tags `kantheon/landing` at deploy; `generate-env.sh` writes `env.js` from `VITE_*` at container start. (K3s deploy rides the cluster bring-up.)

- [x] **T5 — component test.**
  `src/__tests__/rebrand.spec.ts` (vitest + jsdom, mocked): Kantheon brand title in all five locales (and no `DF Partner`/`ai-platform` string in any locale); the dispatcher catalog targets the kantheon estate with no legacy `sql-*`/`metadata`/`fuzzy-*`/`erp-*`/`llm-gateway` tile and every tile carries a non-empty `tech` key; the runtime `config` resolves links + the health roll-up URL + realm from the injected `APP_CONFIG`. **3 tests green.** In-browser e2e deferred to the integration suite.

- [x] **T6 — Stage exit.**
  `npm run lint` (oxlint + eslint) + `npm run build` + vitest green; `rg -i "df.?partner|ai-platform" frontends/landing` clean except the README provenance/fork-description notes and the guard test's own assertion patterns. Stage 5.4 checked in [`tasks.md`](./tasks.md).

**DONE means:** the kantheon landing page serves multilingual, on-brand, with working links. **✅ Met 2026-06-24** (build/test/lint green; live-on-K3s rides the cluster deploy).
