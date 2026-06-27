# agents/midas/shem

Home of the **Golem-Investment `ShemManifest`** (`shem-investment.yaml`), landing
in Phase 3 Stage 3.1 (see [`contracts.md`](../../../docs/architecture/midas/contracts.md) §9.1).

This is **not a Gradle module** — it holds a YAML manifest mounted into the Golem
template pod via ConfigMap. No `build.gradle.kts`, no `settings.gradle.kts` entry.
