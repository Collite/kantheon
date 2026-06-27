# Stage 01 — `infra/nlp` foundation

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: Clone the NER PoC from `/Users/bora/Dev/ner` into the platform as
`infra/nlp/`, add the operations the Resolver brief requires (POS_TAG,
DEP_PARSE, DETECT_LANGUAGE), generalise the engine plugin contract, and ship a
proto-aligned JSON API. NORMAL mode only in this stage; COMPARE and MorphoDiTa
land in Stage 03.

## Entry criteria

- `docs/v1/resolver-design.md` reviewed and approved.
- Bora confirms the PoC at `/Users/bora/Dev/ner` is the right starting point.
- `services/fuzzy-matcher`, `tools/fuzzy-mcp`, and `infra/llm-gateway` are
  reachable in the local K3s cluster (no Stage 01 work depends on them, but
  they are needed for downstream stages and should not be broken by anything
  here).

## Exit criteria

- `just deploy-py nlp` deploys `infra/nlp` to local K3s.
- `POST /v1/analyze` with a Czech sentence returns a proto-shaped JSON
  response containing tokens (with lemma, UD POS, xpos, feats, depHead,
  depRelation), sentence and paragraph spans, NER entities from NameTag, and
  the detected language.
- The same endpoint returns equivalent results for English (Stanza handles
  parsing; Stanza or spaCy provides NER).
- OTEL traces appear in Tempo via `just debug-tunnel` → `http://grafana.local`.
- All tests pass in CI (`just test-py nlp`, `just lint-all`).
- `infra/nlp/README.md` documents the plugin contract, supported ops per
  engine, and the YAML config schema.

## Tasks

### 1. Project scaffolding
- [ ] Create `infra/nlp/` directory with `pyproject.toml` using `uv`. Match
      the layout of existing Python services in `agents/`.
- [ ] Add dependencies: `fastapi`, `uvicorn`, `stanza`, `spacy`, `httpx`,
      `lingua-language-detector`, `pydantic`, `pyyaml`, plus
      `shared/libs/python/otel-config` and platform's `aip_security`.
- [ ] Initial Python package layout under `src/nlp_service/`:
      `engines/`, `pipeline/`, `api/`, `config/`, `eval/`.

### 2. Clone PoC source (without venv/IDE artifacts)
- [ ] Copy `/Users/bora/Dev/ner/src/ner_service/` into
      `infra/nlp/src/nlp_service/`, excluding `__pycache__/`.
- [ ] Drop `app/templates/`, `app/vis/` initially. (Visualization can be
      restored as a dev-only feature behind `enable_dev_visualization` config
      flag in a follow-up.)
- [ ] Keep `shared/`, `stanza/`, `spacy/`, `nametag/`, `app/config.py`,
      `app/service.py` as starting points.

### 3. Define NLP proto
- [ ] Create `shared/proto/src/main/proto/cz/dfpartner/nlp/v1/nlp.proto` with
      messages from `docs/v1/resolver-design.md` §Wire formats.
- [ ] Include `repeated ResponseMessage messages = 99;` per wire-format Rule 6.
- [ ] Run `just proto-py` and `just proto-kt`. Generated Python types land in
      `libs/shared-proto/build/python-package`.
- [ ] Add the package to `infra/nlp/pyproject.toml` dependencies.

### 4. Engine plugin contract
- [ ] In `src/nlp_service/engines/base.py`, define the `NlpEngine` Protocol,
      `NlpOp` enum, and dataclasses `Token`, `NerEntity`, `EngineResult` per
      design doc.
- [ ] Refactor `stanza/pipeline.py` → `engines/stanza_engine.py` implementing
      `NlpEngine`. Add Stanza processors `pos` and `depparse` (PoC currently
      uses only `tokenize,lemma,ner`).
- [ ] Refactor `spacy/pipeline.py` → `engines/spacy_engine.py` implementing
      `NlpEngine`. Initial language support: English NER and tokenize.
- [ ] Refactor `nametag/client.py` → `engines/nametag_engine.py` implementing
      `NlpEngine`. Op support: NER for cs, en. Keep the existing rate-limiter,
      retry, and timeout logic.
- [ ] Create `engines/langid_engine.py`. Op support: `DETECT_LANGUAGE` only.
      Backend: `lingua-language-detector` (preferred for short text; better
      accuracy than `langdetect`).

### 5. Stanza POS/DEP wiring
- [ ] Update `_get_stanza_pipeline` in the new Stanza engine to request
      `tokenize,lemma,pos,depparse,ner` (NER only for languages with models).
- [ ] Map Stanza output to `Token`: `text`, `char_start`, `char_end`, `lemma`,
      `upos`, `xpos`, `feats` (Stanza `feats` parsed from
      `Word.feats` string into a dict), `dep_head`, `dep_relation`.
- [ ] Verify on a short Czech and English sentence with a unit test.

### 6. Pipeline orchestrator (NORMAL mode)
- [ ] In `src/nlp_service/pipeline/orchestrator.py`, implement engine
      selection: per-op-per-language routing based on YAML config.
- [ ] Detect language first if `request.language` is empty and
      `DETECT_LANGUAGE` is in ops.
- [ ] Call selected engine(s) and merge per-engine outputs into a single
      `AnalyzeResponse`. Use the PoC's `union_entities` for entity merging
      across engines (e.g., NameTag NER + Stanza NER).
- [ ] Honour `engineHints` overrides per op when present in the request.

### 7. YAML configuration
- [ ] Define `infra/nlp/config.yaml` with the structure shown in the design
      doc §Engine routing config. Include `engines:` and `op_routing:`
      sections.
- [ ] Update `app/config.py` to load and validate the new schema (Pydantic
      models). Maintain backward-compatible defaults where possible.

### 8. JSON API
- [ ] Replace the existing `POST /analyze` (text/plain body) with
      `POST /v1/analyze` accepting JSON aligned with the proto.
- [ ] Use Pydantic models with `alias_generator=to_camel` to produce camelCase
      JSON on the wire (matches proto3 default JSON encoding and wire-format
      Rule 2).
- [ ] Response includes `messages` field (initially always empty list).
- [ ] `415` for non-JSON content-type, `413` for oversize payloads, `400` for
      malformed JSON, `503` if a required engine is unavailable.

### 9. Strip DictIndex
- [ ] Remove `stanza/dictionaries.py`, `app/service.py` `DictIndex` plumbing,
      `dict_index` from `app.state`, and the keyword-loading code path.
- [ ] Remove `union_entities(result.entities, dict_matches)` paths.
- [ ] Confirm with `grep -r "DictIndex\|dict_matches\|dictionaries"` that no
      references remain.

### 10. Health and version endpoints
- [ ] Keep `/healthz`, `/readyz`, `/version` from the PoC.
- [ ] Update `/version` to enumerate configured engines, the languages each
      supports, and the ops each implements. Replace PoC's separate
      `stanza:`/`spacy:` blocks with a generic `engines:` map.
- [ ] `/readyz` returns `ready` only when at least one engine for each
      configured language is ready.

### 11. Observability
- [ ] Wire OTEL via `shared/libs/python/otel-config`. The lib provides a
      `setup_otel(app, service_name="infra-nlp")` helper analogous to the
      Kotlin side.
- [ ] Each engine call is a span with attributes `engine.name`, `engine.ops`,
      `engine.language`.
- [ ] Add metrics: `nlp_request_total{engine,op,lang,status}`,
      `nlp_request_duration_seconds`, `nlp_engine_unavailable_total`.

### 12. Deployment
- [ ] Dockerfile under `infra/nlp/Dockerfile` (Python services use Docker per
      CLAUDE.md; Kotlin services use Jib).
- [ ] K8s manifests under `infra/nlp/k8s/{base,overlays/local}/` using
      Kustomize.
- [ ] `imagePullPolicy: Never` in the local overlay.
- [ ] Add to `Justfile` so `just deploy-py nlp` builds and loads into K3s.
- [ ] Add to ArgoCD app-of-apps if the platform's pattern requires it.

### 13. Tests
- [ ] Unit tests per engine in `tests/engines/` with golden parses for a
      handful of fixed Czech and English inputs.
- [ ] Component tests for the orchestrator covering: language auto-detect,
      engineHints overrides, per-op-per-language routing, missing-engine
      fallback.
- [ ] Component test: `POST /v1/analyze` with a Czech sentence returns the
      expected proto-shaped JSON. Use a Wiremock-style stub for the NameTag
      UFAL endpoint (per the testing policy, planning-conventions.md §4:
      mocked unit tests only — no Testcontainers; the stub keeps the real
      service out of CI). Real-service confirmation moves to the separate
      integration-test suite.
- [ ] Run `just test-py nlp` in CI per `ci.yml`.

### 14. Documentation
- [ ] `infra/nlp/README.md`: overview, supported engines and ops, YAML config
      schema, how to add a new engine plugin, local development workflow.
- [ ] Cross-link from `docs/v1/resolver-design.md` and update the platform's
      service catalogue in `docs/overview/Architecture-v1.md` if such a
      catalogue exists.

## Risks and mitigations

**Stanza model downloads at startup are slow.** PoC defers downloads to
on-demand. Keep that pattern; document the warm-up step for production
deployments.

**spaCy CPU-only is slow on long sentences.** Not blocking for v1; user-facing
inputs are short questions, not paragraphs. Flag in observability metrics so
we can revisit if real workloads contain long text.

**NameTag UFAL API rate limits.** The public endpoint allows ~60 req/min per
client. PoC's in-memory rate limiter preserves this. For higher throughput,
local NameTag is the path forward (Stage 03 onwards, contingent on UFAL
license).

**Czech NER quality from Stanza is poor.** Stanza's Czech NER model is
limited or absent depending on version. Route Czech NER to NameTag in the
default `op_routing` config. Stanza serves only as fallback when NameTag is
unavailable.

**Proto generation timing.** `just proto-py` must run before `uv sync` after
proto changes; this is documented in CLAUDE.md but worth re-confirming when a
new dev runs through Stage 01 setup.

## Out of scope for Stage 01

- COMPARE mode (Stage 03)
- MorphoDiTa engine (Stage 03)
- Eval corpus and harness (Stage 03)
- `tools/nlp-mcp` wrapper (Stage 02)
- gRPC server (REST first; gRPC may be added in Stage 02 alongside the MCP
  wrapper, or deferred to a follow-up if HTTP/JSON is enough for the
  Resolver agent's needs)
- Visualization mount (deferred; can be reintroduced behind a dev flag in a
  follow-up if useful)
