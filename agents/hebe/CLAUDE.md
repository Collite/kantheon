# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All interactions go through `just`. **Do not infer build steps — use these commands.**

```bash
just init                # First-time setup: installs Gradle/uv/npm deps & compiles protos
just build <service>     # Compile Kotlin JAR
just proto               # Regenerate protos for KT, PY, and JS
just deploy <service>    # Build with Jib and load into K3s
just test                # Run all tests
just lint                # Lint everything
just debug-tunnel        # Port-forward K3s services (DB, Wiremock) to localhost
```

Single test in Kotlin: `./gradlew :<module>:test --tests "com.example.FooSpec"`

## Architecture




## Key Rules

### Kotlin
- **No Dockerfiles for Kotlin** — use Jib via `just deploy-kt`
- **Never use `mapOf` in Ktor `call.respond()`** — use `buildJsonObject` with `JsonPrimitive` (type erasure issue)
- Never hardcode dependency versions in `build.gradle.kts` — all versions go in `gradle/libs.versions.toml`
- Use JetBrains Exposed DSL (not ORM) for SQL; Flyway for migrations
- Testing: Kotest (StringSpec variant) + Testcontainers/Wiremock for integration, mockk for unit

### Kubernetes
- Local manifests always use `imagePullPolicy: Never`
- Use Kustomize with `base/` and `overlays/` structure; ArgoCD app-of-apps pattern

## Observability

All services use OpenTelemetry with Grafana Alloy as collector. The shared lib `shared/libs/kotlin/otel-config` provides `createOpenTelemetrySdk()`. See `docs/manuals/telemetry.md` for the full OTEL setup snippet.

Stack: Alloy → Tempo (traces), Prometheus (metrics), Loki (logs). Access via `just debug-tunnel` then `http://grafana.local`.

## Versioning & CI

Git tags: `<service-directory-name>/v<major>.<minor>.<patch>` (e.g. `erp-agent/v1.2.0`)

CI pipeline (`ci.yml`): init → lint-check → test-all. It auto-detects Jib vs Docker from Gradle plugins — do not hardcode service lists in GitHub Actions.

## Planning & Implementation

- Unless asked to implement immediately, prepare a detailed plan first and get approval before touching code
- Work one Stage at a time when requirements are staged; track progress in `tasks-stage-xx.md`
- Do only what is asked — no unsolicited refactoring, no deleting "unused" code without approval
- Never merge branches

## Serialization

For multi-type fields, use `sealed interface` with inner classes. See `docs/manuals/Ktor serialization.md` for the full `MetadataValue` example pattern.

## Docs

Full documentation in `docs/plan`: Architecture, specs, tasks
