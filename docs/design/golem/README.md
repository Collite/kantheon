# Golem — Design

Golem is the parameterised per-domain Q&A agent template. One codebase; one Kubernetes pod per **Shem**. Each pod loads its `ShemManifest` at boot, registers itself in `capabilities-mcp` under `kind: AGENT, agent_kind: DOMAIN_QA`, and serves Iris's procedural single-domain questions in its domain (Golem-ERP, Golem-HR, Golem-Sales, …).

Adding a new domain means a new ShemManifest YAML + a new pod with the Golem template image and that Shem mounted. **No code change.**

The Golem name is the one non-Greek persona in the constellation — kept for the Hebrew/Yiddish *Shem* (inscription) metaphor: each Golem instance is brought to life with a Shem (domain knowledge + tool curation).

## Files

| File | What |
|---|---|
| [`golem-template-design.md`](./golem-template-design.md) | Locked design: the post-extraction, post-rewrite shape of the Golem template. Vision; the Shem (with the structured-vs-style discipline rule); request/response contracts; mini-plan execution model; streaming protocol; persistence; tool dependencies; module map; the format-catalog Koog spike; what does and doesn't port from today's Python `golem/backend/`. |

## What's elsewhere

- **Implementation architecture** lands under [`../../architecture/golem/`](../../architecture/golem/) when the Golem rewrite arc starts.
- **Phased plan and task lists** land under [`../../implementation/v1/golem/`](../../implementation/v1/golem/) when the Golem rewrite arc starts (sequenced after Themis is live).
- The first ShemManifest content (Golem-ERP) lives at `kantheon/tools/capabilities-mcp/src/main/resources/manifests/agents/golem-erp.yaml` after Phase 1 Stage 1.4; the schema is in [`../../architecture/themis/contracts.md`](../../architecture/themis/contracts.md) §3.2.

## Up / across

- Up: [`../README.md`](../README.md) — design entry point.
- Across: [`../pythia/`](../pythia/) — Pythia reads each Golem's ShemManifest for cross-domain plans (the "master-of-Golems" pattern). [`../themis/`](../themis/) — Themis routes per-domain procedural questions to the matching Golem.
