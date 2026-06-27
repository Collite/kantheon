# Stage 3.2 — Image supply + cluster hygiene

> **Phase 3, Stage 3.2.** Make the nightly cost-bounded and self-cleaning so it runs indefinitely. Mostly cross-repo (olymp Stage 7.7 owns the cluster-side reaper + registry); kantheon owns gating, flake policy, and the main-branch policy doc.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §5 (image supply) + §8 (gating + the ratified red-nightly-is-an-issue policy), olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md) Stage 7.7.

## Goal

A cold nightly completes within a defined wall-clock budget (warm image supply), leaked namespaces are swept automatically, release tags gate on integration, and flaky integration tests are quarantined rather than blocking — with the main-branch policy written down (ratified: nightly-tracked, red = issue).

## Pre-flight

- [ ] Stage 3.1 has ≥2 contexts running nightly.
- [ ] olymp Stage 7.7 scheduled (warm registry + reaper CronJob).
- [ ] Branch `feat/p3-s3.2-hardening`.

## Tasks

- [ ] **T1 — Image supply optimisation (verify build-skip on unchanged SHA).**

  Coordinate with olymp Stage 7.7's warm local registry / image cache on the test server so nightly doesn't rebuild ~15 images cold. Kantheon side: ensure image tags are content/SHA-addressable so an unchanged service is pulled from cache, not rebuilt; verify Jib/Docker build is skipped when the SHA is unchanged. **Acceptance:** a nightly on an unchanged tree pulls cached images; only changed services rebuild. Record the cold vs warm wall-clock delta.

- [ ] **T2 — Namespace reaper backstop.**

  Olymp owns the reaper CronJob (label/age sweep of `olymp.collite/run` namespaces older than N hours); kantheon's contribution is ensuring every bring-up is labelled (contracts §6) so the sweep is safe. Add a nightly post-step (or rely on the reaper) asserting no `kantheon-*-<old-run>` namespaces linger. **Acceptance:** an intentionally-leaked namespace (kill the job mid-run) is swept within the reaper window; labels present on every ns.

- [ ] **T3 — Timing budget + alert threshold.**

  Define a nightly wall-clock budget; alert (issue comment / notification) when exceeded. If the run-set's total exceeds budget, split across parallel jobs (one job per context, or shard the run-set). **Acceptance:** budget documented; an over-budget run raises an alert; sharding option proven on the multi-context run-set.

- [ ] **T4 — Release-tag gating.**

  Confirm the `push: tags: ["*/v*"]` trigger (Stage 2.3 T2) runs the **full** context set and **blocks the release** on red — distinct from the nightly schedule, which only opens an issue. Document the difference. **Acceptance:** a service tag push triggers the full integration set; a red run blocks (fails the tag's workflow); a green run passes.

- [ ] **T5 — Flake policy: quarantine + retry-once (integration tier only).**

  Add a `@Tag("flaky")` quarantine lane for known-flaky integration specs (excluded from the gating run, tracked for fix) and a **retry-once-then-fail** for the integration tier (cluster timing is genuinely non-deterministic). **Never** retry unit or component specs — those must be deterministic. **Acceptance:** a quarantined spec doesn't block; a transient integration failure retries once; a component/unit spec gets zero retries.

- [ ] **T6 — Main-branch policy doc (ratified).**

  Write the ratified policy into `docs/architecture/testing/architecture.md` §8 (or a short ADR-style note): integration runs **nightly + on release tags**; a **red nightly opens a tracked issue** and does **not** retroactively block the window's merges; release-tag red **does** block the release. Note the rejected alternative (merge-queue-gated heavy run). **Acceptance:** the policy is unambiguous and matches the implemented gating (T4/T6 consistent with the workflow).

## DONE criteria

- [ ] Cold nightly within budget; warm image supply verified (build-skip on unchanged SHA).
- [ ] Leaked namespaces swept by the reaper; every bring-up labelled.
- [ ] Release-tag gating blocks on red; nightly opens an issue on red (ratified policy, documented).
- [ ] Flake quarantine + integration-only retry-once in place; unit/component never retried.

## Notes for the executor

- The reaper + warm registry are **olymp's** deliverables (Stage 7.7); kantheon's job is the labels, the gating, the flake/retry policy, and the written policy. Don't reimplement cluster lifecycle on the kantheon side.
- Keep the integration retry strictly tier-scoped — retries masking real bugs in unit/component would erode the fast gate's value.
- This stage closes the testing arc: the constellation has self-maintaining nightly integration coverage. Add it to the master-plan status board on completion.
