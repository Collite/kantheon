# Stage 03 — Eval corpus v0 + COMPARE mode + MorphoDiTa

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: build the empirical foundation for quality decisions. Add `COMPARE`
mode to `infra/nlp`, integrate UFAL MorphoDiTa as a fourth engine for Czech,
seed an eval corpus of ~50 Czech questions, and ship an eval harness that
produces per-engine quality metrics.

This stage runs in parallel with Stage 04 once Stage 02 closes.

## Entry criteria

- Stage 01 complete: `infra/nlp` NORMAL mode working with Stanza, spaCy,
  NameTag.
- `infra/nlp` has the engine plugin contract in place.
- Bora has reviewed and approved the seed corpus selection criteria
  (covering: simple lookups, multi-entity, ambiguity, follow-up phrasings,
  Czech-specific morphology challenges).

## Exit criteria

- `POST /v1/analyze` with `mode=COMPARE` returns per-engine results in
  `byEngine` map alongside the fused result.
- MorphoDiTa engine (`engines/morphodita_engine.py`) is implemented, calls
  UFAL HTTP API, and supports `TOKENIZE`, `LEMMATIZE`, `POS_TAG` for Czech.
- Eval corpus seed file `infra/nlp/eval/corpus/seed.jsonl` contains at least
  50 hand-curated Czech questions with expected parses and entity bindings.
- Eval harness `infra/nlp/eval/run_eval.py` produces per-engine F1 metrics on
  tokens, lemmas, POS, and NER against the corpus.
- A Stanza-vs-MorphoDiTa+NameTag comparison report is committed to
  `infra/nlp/eval/reports/` and provides the input for the UFAL licensing
  decision.

## Tasks

### 1. COMPARE mode in the orchestrator
- [ ] Extend `pipeline/orchestrator.py` to support `mode=COMPARE`.
- [ ] In COMPARE mode, fan out to all engines that support `(language, op)`
      for each requested op. Run in parallel; collect with per-engine timeout.
- [ ] Populate `AnalyzeResponse.byEngine` map keyed by engine name.
- [ ] Top-level fields (`tokens`, `entities`, etc.) reflect the configured
      primary engine in COMPARE mode just as in NORMAL.
- [ ] Preserve per-engine errors in `EngineResult.error` rather than failing
      the whole request — partial COMPARE results are valuable.

### 2. MorphoDiTa engine
- [ ] `engines/morphodita_engine.py` implementing `NlpEngine`.
- [ ] HTTP client to UFAL endpoint (similar pattern to `nametag_engine.py`):
      configurable endpoint, timeout, retries, in-memory rate limiter.
- [ ] Op support: `TOKENIZE`, `LEMMATIZE`, `POS_TAG` for Czech (`cs`).
- [ ] Output mapping: UFAL MorphoDiTa returns lemmas and PDT/UD tagsets;
      normalize to UD POS for the `Token.upos` field, retain PDT in
      `Token.xpos`. Parse morphological features into `Token.feats` map.
- [ ] Disabled by default in `config.yaml`; enabled in eval runs and
      explicitly when `engineHints` selects it.

### 3. Eval corpus schema and seeding
- [ ] Define corpus schema in `infra/nlp/eval/SCHEMA.md`. Fields per question:
      `question`, `lang`, and `expected` (with `tokens`, `lemmas`,
      `entities`, `functionId`, `args`).
- [ ] Mine seed examples from:
      - `docs/v1/ai-ag-vs-erp/02-entity-detection.md` and `03-pattern-matching.md`
        (existing tribal knowledge in tribal docs).
      - `agents/erp-agent-2` test fixtures, if any contain natural-language
        Czech questions.
      - Hand-crafted examples covering known ambiguities (Shell as
        customer/supplier, "order" as sales/purchase, plural/singular and
        gender variants typical for Czech).
- [ ] Target 50+ questions in `infra/nlp/eval/corpus/seed.jsonl`.
- [ ] Annotate each question with at least: expected tokens, expected lemmas,
      expected universal-NER entities, expected domain entities (with
      placeholder `<resolvedId>` if the actual ID isn't yet known to the
      seeder), and a target `functionId` if applicable.

### 4. Eval harness
- [ ] `infra/nlp/eval/run_eval.py`: script that reads the corpus, calls
      `POST /v1/analyze` in `COMPARE` mode for each entry, and computes
      metrics per engine.
- [ ] Metrics: token-level F1 (tokenization), lemma accuracy, UD POS accuracy,
      NER span F1 (per universal label), language-detection accuracy.
- [ ] Output: machine-readable JSON for trends + a markdown summary for
      review.
- [ ] Tokenization-mismatch handling: when two engines tokenize differently,
      align spans by character offsets before computing F1; report alignment
      ambiguity rate as its own metric.

### 5. Comparison report
- [ ] Run the harness with all engines enabled.
- [ ] Commit the report at `infra/nlp/eval/reports/2026-MM-stanza-vs-morphodita.md`.
- [ ] Report sections: methodology, per-engine F1, examples where MorphoDiTa
      beats Stanza, examples where Stanza ties or wins, recommendation on
      UFAL licensing.

### 6. CI integration
- [ ] Add `just eval-nlp` recipe that runs the harness against a deployed
      local K3s `infra/nlp`.
- [ ] Nightly job runs `just eval-nlp`, archives the JSON metrics, and
      surfaces trend in a Grafana panel (or similar). Defer the panel if
      ai-platform's monitoring story doesn't yet include this kind of
      time-series.

### 7. Documentation
- [ ] `infra/nlp/eval/README.md`: corpus structure, how to add new fixtures,
      how to run the harness, how to interpret metrics, how the corpus grows
      across Resolver stages.
- [ ] Cross-link from `docs/v1/resolver-design.md`.

## Risks and mitigations

**UFAL MorphoDiTa rate limits.** UFAL's public service caps free use at a
modest req/min. The eval corpus is small (~50 calls per run) and runs nightly,
so this is unlikely to bite. Local NameTag/MorphoDiTa is the path forward post
licensing — note explicitly in the comparison report.

**Single-author ground truth.** The seed corpus is curated by a single
author; inter-annotator agreement is not measured. Acceptable for v0 but
flag in the report. Stage 05 grows the corpus from real-traffic
disagreements, which provides a natural diversification.

**Tokenization differences across engines.** Stanza, MorphoDiTa, and spaCy
will tokenize the same input slightly differently (clitic splitting in Czech,
multiword expressions). The harness's character-offset alignment is the
mitigation; alignment ambiguity rate is itself a quality metric to track.

**Czech UD POS vs PDT POS.** MorphoDiTa is PDT-native; Stanza Czech is
UD-trained. The mapping is well-defined but lossy in places. Decision: store
both (`upos` UD-mapped, `xpos` raw) and compute metrics on `upos` for
cross-engine comparability; track `xpos` separately for use cases that need
PDT precision.

## Out of scope for Stage 03

- Cross-replica caching / Redis (deferred per design doc).
- Resolver-level eval (`agents/resolver/eval/`) — that lands in Stage 04
  alongside the agent itself; this stage covers parse-quality eval only.
- Pinning corpus growth targets beyond v0 (50 questions). Stage 05's diff
  harness drives organic growth; explicit targets per quarter come later.
- Local-licensed MorphoDiTa/NameTag — license decision follows from this
  stage's report; embedding the libraries comes in a later stage if
  approved.
