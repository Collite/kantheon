# Kantheon justfile — recipe runner. `just` with no args lists everything.
# Mirrors ai-platform's structure. New recipes land as stages add capabilities.

set shell := ["bash", "-cu"]

default:
    @just --list

# Resolve a bare module name (e.g. `_smoke-test`) to its Gradle project path
# (`:tools:_smoke-test`). Accepts either bare names or full slash paths.
_resolve service:
    @if [[ "{{service}}" == *"/"* ]]; then \
        echo ":$(echo {{service}} | sed 's|/|:|g')"; \
    else \
        path=$(find agents services workers tools frontends infra shared -maxdepth 4 -type d -name "{{service}}" -print -quit 2>/dev/null); \
        if [ -z "$path" ]; then \
            echo "❌ Module '{{service}}' not found under agents/, services/, workers/, tools/, frontends/, infra/, shared/libs/kotlin/" >&2; \
            exit 1; \
        fi; \
        echo ":$(echo "$path" | sed 's|/|:|g')"; \
    fi

# First-time setup: verify GitHub PAT for ai-platform Maven, exercise Gradle, regenerate protos.
init:
    @./scripts/check-pat.sh
    ./gradlew help --no-daemon
    just proto

# Regenerate proto bindings for all languages.
# Emits Kotlin + Python (via `:shared:proto:assemble`, which depends on
# `preparePythonPackage`) AND TypeScript (via `proto-ts`, Iris Stage 1.1).
proto: proto-ts
    ./gradlew :shared:proto:assemble --no-daemon

# Regenerate TypeScript proto bindings for shared/libs/ts/envelope-ts
# (envelope/v1 + iris/v1 + common/v1; Iris Stage 1.1). Self-contained via
# buf + ts-proto — no system protoc (the Gradle protobuf plugin owns the
# Kotlin/Python protoc). `npm ci` keeps the lockfile authoritative in CI;
# falls back to `npm install` on first run / lockfile drift.
proto-ts:
    cd shared/libs/ts/envelope-ts && (npm ci || npm install) && npm run gen
    # Sysifos FE bindings (Stage 1.1): sysifos/v1 + its imports (midas/v1,
    # envelope/v1, common/v1) via --include-imports → frontends/sysifos/src/generated.
    cd frontends/sysifos && (npm ci || npm install) && npm run gen

# Regenerate proto bindings for Python (shared-proto package, org.tatrman.*).
# Lands as part of fork Phase 1 Stage 1.2; the recipe is wired here so the
# py-* recipes can depend on it from the moment any Python module lands.
proto-py:
    ./gradlew :shared:proto:preparePythonPackage --no-daemon

# Sync dependencies for ALL Python modules (uv). Each module has its own
# pyproject.toml + uv.lock. Exits non-zero if any module fails.
py-sync-all: proto-py
    @echo "Syncing Python projects..."
    @find . -name "pyproject.toml" -not -path "*/shared/proto/*" -not -path "*/node_modules/*" -not -path "*/.venv/*" -exec dirname {} \; | sort -u | while read dir; do \
        echo "Syncing $dir..."; \
        (cd "$dir" && uv sync) < /dev/null; \
    done

# Run tests for a specific Python service. Trailing args pass through to pytest,
# so the component tier (testcontainers-python) selects its marker:
#   just test-py services/metis                 # all tests
#   just test-py services/metis -m component     # real-dep component tier only
#   just test-py services/metis -m "not component"  # mocked unit tier only
# Exits 0 on "no tests collected" (pytest exit 5) so empty modules don't fail CI.
test-py service *args: proto-py
    cd {{service}} && (uv run pytest {{args}} || (code=$?; if [ $code -eq 5 ]; then echo "No tests collected. Skipping..."; exit 0; else exit $code; fi))

# Lint a specific Python service. Auto-fixes with ruff.
# Usage: `just lint-py services/kadmos`
lint-py service:
    cd {{service}} && uv run ruff check . --fix

# Build a Python service Docker image (Jib is JVM-only, so Python services
# use a Dockerfile). A service whose Dockerfile pulls in the repo-root
# `shared/` tree (uv path-deps — e.g. services/kadmos) must build from the
# repo-root context; self-contained ones build from their own dir. The image
# is tagged `<name>:dev` to match the k8s `image:` convention (echo:dev,
# ariadne:dev). Usage: `just build-py services/kadmos`
build-py service: proto-py
    cd {{service}} && uv sync                                              # ensure lockfile is fresh
    @if grep -qE '^COPY[[:space:]]+shared' {{service}}/Dockerfile; then \
        echo "↳ repo-root build context (Dockerfile copies shared/)"; \
        docker build -t "$(basename {{service}})":dev -f {{service}}/Dockerfile . ; \
    else \
        docker build -t "$(basename {{service}})":dev {{service}} ; \
    fi

# Build a Python service Docker image and deploy it to local K3s (mirrors
# deploy-kt for the Python lane). Usage: `just deploy-py services/kadmos`
deploy-py service: (build-py service)
    @if [ ! -d "{{service}}/k8s/overlays/local" ]; then \
        echo "no k8s/overlays/local under {{service}} — skipping kubectl apply"; \
    else \
        kubectl create namespace kantheon --dry-run=client -o yaml | kubectl apply -f -; \
        kubectl apply -k "{{service}}/k8s/overlays/local"; \
        kubectl -n kantheon rollout restart "deployment/$(basename {{service}})" || true; \
    fi

# Run the Kadmos NLP eval corpus against the deployed service (local K3s).
# Port-forwards the kadmos pod (7270) and runs eval/run_eval.py in COMPARE
# mode; writes JSON + Markdown reports under services/kadmos/eval/reports/.
# Mirrors ai-platform's `eval-nlp`. Usage: `just eval-kadmos`
eval-kadmos:
    @echo "Running Kadmos NLP evaluation against services/kadmos..."
    @pod=$(kubectl -n kantheon get pods -l app=kadmos -o name 2>/dev/null | head -1); \
    if [ -z "$pod" ]; then \
        echo "❌ kadmos pod not found. Deploy with: just deploy-py services/kadmos"; \
        exit 1; \
    fi; \
    kubectl -n kantheon port-forward "$pod" 7270:7270 &
    @sleep 2
    cd services/kadmos && uv run python eval/run_eval.py \
        --url http://localhost:7270 \
        --output-json eval/reports/metrics.json \
        --output-md eval/reports/report.md

# Golem envelope parity diff-harness (Phase 3 Stage 3.3). Replays the recorded-v2
# corpus (agents/golem/eval/corpus/conversations/) through the Kotlin format
# pipeline in-process and diffs envelopes field-wise; writes the Markdown report
# to agents/golem/build/diff-harness/report.md (untracked) and FAILS on any
# BUG-class divergence. No live services — purely deterministic replay. Usage: `just eval-golem`
eval-golem:
    @echo "Running Golem envelope parity diff-harness... (report → agents/golem/build/diff-harness/report.md)"
    ./gradlew :agents:golem:test --tests "org.tatrman.kantheon.golem.eval.DiffHarnessSpec" --no-daemon

# Pythia investigation eval gate (Phase 5 Stage 5.3). Runs the scripted-LLM corpus
# (agents/pythia/eval/corpus/) through the orchestrator in-process and gates on the
# four architecture §9 metrics: plan-validity rate, verdict accuracy, budget
# adherence, replay determinism. Deterministic — no live services (the small
# live-LLM bucket is the nightly `pythia` context). FAILS below threshold.
# Usage: `just eval-pythia`
eval-pythia:
    @echo "Running Pythia investigation eval gate (scripted corpus)..."
    ./gradlew :agents:pythia:test --tests "org.tatrman.kantheon.pythia.eval.EvalGateSpec" --no-daemon

# Themis routing eval (Phase 3 Stage 3.5) against a deployed Themis (local K3s
# or nightly cluster). Port-forwards themis-mcp (7901) and runs the routing
# corpus through run_routing_eval.py with profile=CHAT_QUICK; writes JSONL +
# Markdown under agents/themis/eval/results/ and enforces eval/thresholds.yaml
# (exit non-zero on breach). The LIVE gate is the nightly `themis-routing`
# context — see agents/themis/eval/README.md. Usage: `just eval-themis-routing`
eval-themis-routing:
    @echo "Running Themis routing eval against agents/themis..."
    @pod=$(kubectl -n kantheon get pods -l app=themis -o name 2>/dev/null | head -1); \
    if [ -z "$pod" ]; then \
        echo "❌ themis pod not found. Deploy with: just deploy-kt themis"; \
        exit 1; \
    fi; \
    kubectl -n kantheon port-forward "$pod" 7901:7901 &
    @sleep 2
    cd agents/themis && python3 eval/run_routing_eval.py \
        --host localhost --port 7901 \
        --corpus eval/corpus/routing-seed.jsonl \
        --thresholds eval/thresholds.yaml \
        --output "eval/results/routing-$(date +%Y%m%d).jsonl" \
        --report "eval/results/routing-$(date +%Y%m%d).md" \
        --verbose

# Routing-eval harness self-test (no cluster, no LLM — dependency-free). Drives
# the full load→call→score→gate pipeline over HTTP against a local fake Themis.
# This is the CI-runnable guard on the harness logic; the live corpus run is the
# nightly `themis-routing` context. Usage: `just eval-themis-routing-selftest`
eval-themis-routing-selftest:
    cd agents/themis && python3 eval/run_routing_eval.py --self-test

# Build one Kotlin service. Usage: `just build-kt _smoke-test`
build-kt service:
    ./gradlew "$(just _resolve {{service}}):build" --no-daemon

# Run tests for one Kotlin service. Usage: `just test-kt _smoke-test`
test-kt service:
    ./gradlew "$(just _resolve {{service}}):test" --no-daemon

# Run all Kotlin tests across the build.
test-all:
    ./gradlew test --no-daemon

# Export iris turn-feedback (PD-3) to per-agent eval/candidates/ JSONL. Needs
# iris.db.enabled=true + IRIS_DB_* config. Optional output dir (default eval/candidates).
#   just feedback-export
#   just feedback-export /tmp/candidates
feedback-export out="eval/candidates":
    ./gradlew :agents:iris-bff:feedbackExport --no-daemon --args="{{out}}"

# Run the real-dependency component tier (Testcontainers; no cluster). Needs a
# running Docker daemon. Optional module arg, mirroring `test-kt`'s resolution:
#   just test-component                  # every module's componentTest
#   just test-component services/charon  # one module
# Specs live in `src/componentTest/kotlin` (own source set, @Tags("component")).
# See docs/architecture/testing/architecture.md §2 (the component tier).
test-component service="":
    @if [ -z "{{service}}" ]; then \
        ./gradlew componentTest --no-daemon; \
    else \
        ./gradlew "$(just _resolve {{service}}):componentTest" --no-daemon; \
    fi

# Run one integration context end-to-end against **bp-dsk**, on demand (WS-R1 T5) —
# the local "run on bp-dsk" leg of the TDD loop. `infra-up` (olymp) stands the context
# up in an isolated `kantheon-<context>-<run-id>` namespace, `:integrationTest` runs the
# `@RequiresContext` spec against it, and a bash `trap` reaps it on exit (success OR
# failure → no leaked namespace). One context / one run-id / one namespace (olymp
# test-harness §8 isolation). The scheduled **nightly stays on bp-olymp01** — this is
# the developer's on-demand full-run, not a nightly move (WS-R contract, §7-D4).
#   just it-bp-dsk theseus-runquery
#   OLYMP_DIR=~/src/olymp just it-bp-dsk theseus-runquery
# Prereqs: kubectl context `dsk` reachable; an olymp checkout ($OLYMP_DIR or the default
# below); the private GHCR images pullable — the run copies `argocd/ghcr-pull` into the
# run namespace via olymp's `--ghcr-from`. The reconcile boundary is verified (R1 T1):
# ArgoCD never selects `kantheon-*` run namespaces.
it-bp-dsk context olymp_dir=env_var_or_default("OLYMP_DIR", "~/Dev/collite-gh/olymp"):
    #!/usr/bin/env bash
    set -euo pipefail
    OLYMP=$(eval echo "{{olymp_dir}}")
    if [ ! -f "$OLYMP/justfile" ]; then
        echo "❌ olymp checkout not found at $OLYMP — set OLYMP_DIR" >&2; exit 1
    fi
    RUN_ID="dsk-$(date +%s)"
    echo "== it-bp-dsk {{context}} (run $RUN_ID) on dsk =="
    trap 'just -f "$OLYMP/justfile" infra-down {{context}} "$RUN_ID" dsk' EXIT
    NS=$(just -f "$OLYMP/justfile" infra-up {{context}} "$RUN_ID" dsk \
            --kantheon "$(pwd)" --ghcr-from argocd/ghcr-pull | sed -n 's/^namespace=//p')
    if [ -z "$NS" ]; then echo "❌ infra-up did not report a namespace" >&2; exit 1; fi
    echo "== context up in ns $NS — running :integrationTest =="
    # On failure, surface the constellation's own logs BEFORE the trap tears the ns down — an
    # integration failure is usually a service-side error the client only sees as a bad envelope.
    if ! ./gradlew integrationTest -Pcontext={{context}} -Pnamespace="$NS" -PkubeContext=dsk --no-daemon; then
        echo "== integrationTest FAILED — dumping service logs from $NS =="
        for svc in prometheus golem pythia themis-mcp theseus-mcp theseus proteus argos kyklop arges brontes ariadne kadmos echo capabilities-mcp; do
            if kubectl --context dsk -n "$NS" get deploy "$svc" >/dev/null 2>&1; then
                echo "---- $svc (tail) ----"
                # Drop OTEL exporter noise first (every service retries localhost:4317 when no
                # collector is present — harmless, but it floods the error grep below).
                kubectl --context dsk -n "$NS" logs "deploy/$svc" --tail=120 2>/dev/null \
                    | grep -viE 'otlp|opentelemetry\.exporter|:4317|Failed to (export|connect)|Transient error.*export' \
                    | grep -iE 'error|fail|exception|chat request|selected model|calling llm|received chat|provider|anthropic|empty|decode|clarification|denied|refus' \
                    | tail -25 || true
            fi
        done
        exit 1
    fi

# Render every module chart (`<module>/k8s`) with `helm template` (chart default
# values) and diff against the checked-in goldens in shared/charts/.golden/. The
# regression gate for the kantheon-service library-chart migration (WS-D S1):
# after a chart moves onto the library its render must stay BYTE-EQUIVALENT.
#   just validate-charts            # check (CI gate)
#   just validate-charts capture    # (re)write goldens from current charts
# Needs Helm ≥ 3.8. See shared/charts/validate.sh + tasks-d1-chart-library.md.
validate-charts mode="check":
    ./shared/charts/validate.sh {{mode}}

# ktlint check across all modules + the Hebe detekt pass (the mutation-funnel
# rule — every state change must flow through ToolDispatcher.dispatch). The
# `detekt` task only exists on the `:agents:hebe:` modules (they alone apply the
# detekt plugin), so this runs the custom rule against the Hebe sources only.
lint-all:
    ./gradlew ktlintCheck detekt --no-daemon

# --- Hebe (personal autonomous agent) ----------------------------------------
# Hebe's 21 modules became kantheon root-build modules in P1 Stage 1.1. These
# recipes mirror the standalone `just` recipes the gradle merge retired.

# Build the standalone `hebe` binary (the cli-app shadowJar → modules/cli-app/build/libs/hebe.jar).
hebe-build:
    ./gradlew :agents:hebe:modules:cli-app:shadowJar --no-daemon

# Run every Hebe module's unit suite (the whole `:agents:hebe:` subtree).
hebe-test:
    ./gradlew --no-daemon \
        :agents:hebe:modules:api:test \
        :agents:hebe:modules:plugin-api:test \
        :agents:hebe:modules:observability:test \
        :agents:hebe:modules:config:test \
        :agents:hebe:modules:memory:test \
        :agents:hebe:modules:security:test \
        :agents:hebe:modules:providers:openai-compat:test \
        :agents:hebe:modules:tools:dispatch:test \
        :agents:hebe:modules:tools:builtin:test \
        :agents:hebe:modules:tools:mcp-client:test \
        :agents:hebe:modules:core:test \
        :agents:hebe:modules:plugins:test \
        :agents:hebe:modules:channels:channel-manager:test \
        :agents:hebe:modules:channels:cli:test \
        :agents:hebe:modules:channels:web:test \
        :agents:hebe:modules:channels:telegram:test \
        :agents:hebe:modules:mcp-server:test \
        :agents:hebe:modules:gateway:test \
        :agents:hebe:modules:scheduler:test \
        :agents:hebe:modules:detekt-rules:test \
        :agents:hebe:modules:cli-app:test

# Run Hebe's cli-app from sources in the `local` profile. Extra args pass through.
#   just hebe-run-local onboard
#   just hebe-run-local run
hebe-run-local *args:
    ./agents/hebe/hebe.sh {{args}}

# Provision a new Hebe instance schema + grant-limited role + Secret skeleton
# (contracts §4.4). Usage: `just hebe-provision dev`
hebe-provision id:
    ./agents/hebe/deploy/provision.sh {{id}}

# Build the Hebe server-mode image (Jib) and apply the K3s overlay (P3 S3.3).
# Usage: `just deploy-hebe dev`  (the local overlay deploys instance `dev`).
deploy-hebe id="dev":
    ./gradlew :agents:hebe:modules:cli-app:jibDockerBuild --no-daemon
    kubectl create namespace kantheon --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -k agents/hebe/k8s/overlays/local
    kubectl -n kantheon rollout restart deployment/hebe-{{id}}

# --- Frontends (Vue/TS) -------------------------------------------------------
# The Iris SPA lives in frontends/iris (extracted from ai-platform agents-fe,
# Iris Phase 2 Stage 2.1 — one-time copy, no ai-platform tie). npm-based (vite +
# vitest + vue-tsc); `npm ci` keeps the lockfile authoritative in CI, falling back
# to `npm install` on first run / lockfile drift — same pattern as proto-ts.

# Run the Sysifos FE dev server (Vite + BFF proxy on /bff → :7420). Generates
# the sysifos/v1 TS bindings first so a fresh checkout resolves imports. Usage:
#   just sysifos-dev
sysifos-dev:
    cd frontends/sysifos && (npm ci || npm install) && npm run codegen && npm run dev

# Build one frontend (type-check + vite build). Usage: `just build-fe iris`
build-fe service:
    cd frontends/{{service}} && (npm ci || npm install) && npm run build

# Run unit tests for one frontend (vitest, non-watch). Usage: `just test-fe iris`
test-fe service:
    cd frontends/{{service}} && (npm ci || npm install) && npx vitest run

# Lint one frontend (oxlint + eslint, non-mutating check). Usage: `just lint-fe iris`
lint-fe service:
    cd frontends/{{service}} && (npm ci || npm install) && npx oxlint . && npx eslint .

# Build + push a frontend's nginx image to GHCR (ghcr.io/boraperusic/<service>:<tag>).
# Frontends are nginx images (NOT Jib) — the Dockerfile `COPY dist`, so we vite-build
# first (dist is gitignored), then `docker build` the static-server image. bp-dsk is
# amd64, so we build `--platform linux/amd64`. Auth via GHCR_USER + GHCR_TOKEN (a PAT
# with write:packages). Usage:
#   GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_… just publish-fe-image iris v0.1.0
publish-fe-image service tag="testing":
    @if [ -z "${GHCR_USER:-}" ] || [ -z "${GHCR_TOKEN:-}" ]; then \
        echo "❌ set GHCR_USER + GHCR_TOKEN (a PAT with write:packages) — e.g. GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_… just publish-fe-image {{service}} v0.1.0"; \
        exit 1; \
    fi
    just build-fe {{service}}
    echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
    docker build --platform linux/amd64 \
        -t "ghcr.io/boraperusic/{{service}}:{{tag}}" frontends/{{service}}
    docker push "ghcr.io/boraperusic/{{service}}:{{tag}}"
    @echo "✅ pushed ghcr.io/boraperusic/{{service}}:{{tag}} (linux/amd64)"

# Create a new version tag for a module and push it to origin.
# Tag format: `<module-name>/v<X.Y.Z>` (per AGENTS.md §11 + ai-platform convention).
#
# Usage:
#   just tag capabilities-mcp                 # bump patch on the latest tag (default)
#   just tag capabilities-mcp minor           # bump minor
#   just tag capabilities-mcp major           # bump major
#   just tag capabilities-mcp set 0.1.0       # set explicit version
#   just tag all patch                        # bump patch on every module
tag module level="patch" version="":
    #!/usr/bin/env bash
    set -euo pipefail

    MODULE="{{module}}"
    LEVEL="{{level}}"
    CUSTOM_VERSION="{{version}}"

    if [[ "$LEVEL" != "major" && "$LEVEL" != "minor" && "$LEVEL" != "patch" && "$LEVEL" != "set" ]]; then
        echo "❌ Level must be 'major', 'minor', 'patch', or 'set'." >&2
        exit 1
    fi

    if [[ "$LEVEL" == "set" && -z "$CUSTOM_VERSION" ]]; then
        echo "❌ 'set' requires a version. Usage: just tag <module> set 1.2.3" >&2
        exit 1
    fi

    # Branch guard — release tags should normally come off main.
    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [[ "$BRANCH" != "main" && "$BRANCH" != "master" ]]; then
        if [[ -t 0 ]]; then
            read -p "⚠️  You are on branch '$BRANCH', not 'main'. Create the release tag anyway? [y/N] " -n 1 -r
            echo ""
            if [[ ! $REPLY =~ ^[Yy]$ ]]; then
                echo "❌ Aborting."
                exit 1
            fi
        else
            echo "❌ Refusing to tag from branch '$BRANCH' in a non-interactive shell." >&2
            echo "    Switch to main, or re-run with --yes: KANTHEON_TAG_FORCE=1 just tag ..." >&2
            if [[ "${KANTHEON_TAG_FORCE:-}" != "1" ]]; then
                exit 1
            fi
            echo "⚠️  KANTHEON_TAG_FORCE=1 set — proceeding from branch '$BRANCH'."
        fi
    fi

    # Refuse to tag if the working tree is dirty.
    if ! git diff --quiet || ! git diff --cached --quiet; then
        echo "❌ Working tree has uncommitted changes. Commit or stash before tagging." >&2
        exit 1
    fi

    tag_module() {
        local mod_path="$1"
        local mod_name
        mod_name=$(basename "$mod_path")

        # Latest existing tag: `<mod-name>/vX.Y.Z` — pick highest by `sort -V`.
        local latest_tag
        latest_tag=$(git tag -l "${mod_name}/v*" | sed "s|^${mod_name}/v||" | sort -V | tail -n 1 || true)

        local new_version
        if [[ "$LEVEL" == "set" ]]; then
            new_version="${CUSTOM_VERSION}"
        else
            local major minor patch
            if [[ -z "$latest_tag" ]]; then
                echo "ℹ️  No existing tags for ${mod_name}. Starting from 0.0.0."
                major=0; minor=0; patch=0
            else
                IFS='.' read -r major minor patch <<< "$latest_tag"
            fi

            case "$LEVEL" in
                major) major=$((major + 1)); minor=0; patch=0 ;;
                minor) minor=$((minor + 1)); patch=0 ;;
                patch) patch=$((patch + 1)) ;;
            esac

            new_version="${major}.${minor}.${patch}"
        fi

        local new_tag="${mod_name}/v${new_version}"

        if git rev-parse -q --verify "refs/tags/${new_tag}" >/dev/null; then
            echo "❌ Tag ${new_tag} already exists. Aborting ${mod_name}." >&2
            return 1
        fi

        echo "🏷️  Creating tag: ${new_tag}"
        git tag "${new_tag}"

        echo "🚀 Pushing tag to origin..."
        git push origin "${new_tag}"

        echo "✅ Tagged and pushed ${new_tag}"
    }

    if [[ "$MODULE" == "all" ]]; then
        echo "📦 Tagging ALL modules under agents/ tools/ frontends/ shared/libs/kotlin/..."
        find agents tools frontends shared/libs/kotlin \
            -mindepth 1 -maxdepth 2 -type d \
            \( -path 'shared/libs/kotlin/*' -o -not -path 'shared/libs/*' \) \
            -print 2>/dev/null | while read -r mod_path; do
            # Skip anything that isn't a Gradle module (no build.gradle.kts).
            [[ -f "$mod_path/build.gradle.kts" ]] || continue
            echo "-----------------------------------------------------------------"
            echo "🎯 Tagging $mod_path..."
            tag_module "$mod_path"
        done
        echo "✅ All eligible modules tagged."
    else
        # Resolve `MODULE` to its on-disk path. Mirrors `_resolve` but returns a path, not a Gradle coord.
        if [[ "$MODULE" == */* ]]; then
            mod_path="$MODULE"
        else
            mod_path=$(find agents tools frontends shared -maxdepth 4 -type d -name "$MODULE" -print -quit 2>/dev/null || true)
        fi
        if [[ -z "${mod_path:-}" || ! -d "$mod_path" ]]; then
            echo "❌ Module '$MODULE' not found under agents/, tools/, frontends/, shared/libs/kotlin/." >&2
            exit 1
        fi
        tag_module "$mod_path"
    fi

# List every module and its latest published tag.
tags:
    @printf "%-36s | %-30s | %-15s\n" "MODULE PATH" "MODULE NAME" "LATEST TAG"
    @echo "-------------------------------------------------------------------------------------"
    @find agents tools frontends shared/libs/kotlin -mindepth 1 -maxdepth 3 -type d 2>/dev/null \
        | while read -r path; do \
            [ -f "$path/build.gradle.kts" ] || continue; \
            name=$(basename "$path"); \
            latest=$(git tag -l "${name}/v*" | sort -V | tail -n 1); \
            printf "%-36s | %-30s | %-15s\n" "$path" "$name" "${latest:-—}"; \
        done

# Build a Jib image and deploy to local K3s.
deploy-kt service:
    ./gradlew "$(just _resolve {{service}}):jibDockerBuild" --no-daemon
    @path=$(find agents services workers tools frontends shared -maxdepth 4 -type d -name "{{service}}" -print -quit); \
    if [ -z "$path" ] || [ ! -d "$path/k8s/overlays/local" ]; then \
        echo "no k8s/overlays/local under $path — skipping kubectl apply"; \
    else \
        kubectl create namespace kantheon --dry-run=client -o yaml | kubectl apply -f -; \
        kubectl apply -k "$path/k8s/overlays/local"; \
        dep=$(kubectl kustomize "$path/k8s/overlays/local" | awk '/^kind: Deployment$/{f=1} f&&/^  name:/{print $2; exit}'); \
        kubectl -n kantheon rollout restart deployment/"${dep:-{{service}}}"; \
    fi

# `jib` (not jibDockerBuild) pushes straight to the registry, and CI=true forces a multi-arch
# (amd64+arm64) manifest so the amd64 bp-olymp01 node can pull. The image name defaults to the
# module basename (theseus-mcp, theseus, brontes, …), matching the chart's image.repository — but
# a few modules publish under a different name than their directory (e.g. `agents/themis` →
# image `themis-mcp`), so an optional third arg overrides the image name. Auth via env: GHCR_USER
# + GHCR_TOKEN (a PAT with write:packages). Python services use the Docker lane, not Jib — publish
# those via build-py + `docker push`. Usage:
#   GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_xxx just publish-image tools/theseus-mcp
#   GHCR_USER=… GHCR_TOKEN=… just publish-image services/theseus v0.1.0            # explicit tag
#   GHCR_USER=… GHCR_TOKEN=… just publish-image agents/themis testing themis-mcp   # image name ≠ dir
# Push a Kotlin module's Jib image to GHCR (ghcr.io/boraperusic/<name>:<tag>, default :testing).
publish-image service tag="testing" image="":
    @if [ -z "${GHCR_USER:-}" ] || [ -z "${GHCR_TOKEN:-}" ]; then \
        echo "❌ set GHCR_USER + GHCR_TOKEN (a PAT with write:packages) — e.g. GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_… just publish-image {{service}}"; \
        exit 1; \
    fi
    img="{{image}}"; if [ -z "$img" ]; then img="$(basename {{service}})"; fi; \
    CI=true ./gradlew "$(just _resolve {{service}}):jib" \
        -Djib.to.image="ghcr.io/boraperusic/$img:{{tag}}" \
        -Djib.to.auth.username="$GHCR_USER" \
        -Djib.to.auth.password="$GHCR_TOKEN" \
        --no-daemon; \
    echo "✅ pushed ghcr.io/boraperusic/$img:{{tag}} (multi-arch amd64+arm64)"

# Bring up the owned local infra (kantheon-architecture §7.1) on the current K3s
# context: the `kantheon` namespace + everything under `deployment/local`
# (MSSQL — Brontes's backing store; Postgres — the shared Kantheon PG holding the
# `midas` database, Midas Stage 1.1; and any seeds). WireMock-LLM upstreams for
# Prometheus are an integration-track concern (Stage 2.5 T4); local dev runs on
# placeholder keys. Idempotent — safe to re-run. See deployment/local/README.md.
local-infra-up:
    kubectl create namespace kantheon --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -k deployment/local
    @echo "↳ waiting for MSSQL to become available…"
    kubectl -n kantheon rollout status deployment/mssql --timeout=180s || \
        echo "⚠ MSSQL not ready yet — check 'kubectl -n kantheon get pods'"
    @echo "↳ waiting for Postgres to become available…"
    kubectl -n kantheon rollout status deployment/postgres --timeout=180s || \
        echo "⚠ Postgres not ready yet — check 'kubectl -n kantheon get pods'"
    @echo "↳ waiting for the postgres-init job (midas database + midas_app role)…"
    kubectl -n kantheon wait --for=condition=complete job/postgres-init --timeout=120s || \
        echo "⚠ postgres-init not complete — check 'kubectl -n kantheon logs job/postgres-init'"

# Deploy the full forked constellation to local K3s in dependency order.
# Prereq: a running K3s + `just local-infra-up`. Builds Jib/Docker images and
# applies each module's overlays/local. Kotlin modules go through deploy-kt,
# the two Python modules (Kadmos, Steropes) through deploy-py. Heavy — it builds
# ~15 images. See deployment/local/README.md for the dependency rationale.
deploy-fork:
    # Registry first, so MCP wrappers can heartbeat on boot.
    just deploy-kt capabilities-mcp
    # Leaf services (no intra-fork runtime deps).
    just deploy-kt prometheus
    just deploy-py services/kadmos
    just deploy-kt echo
    just deploy-kt ariadne
    # Translator, then the workers (Brontes needs MSSQL + Proteus).
    just deploy-kt proteus
    just deploy-kt brontes
    just deploy-py workers/steropes
    # Validation → dispatch → orchestration.
    just deploy-kt argos
    just deploy-kt kyklop
    just deploy-kt theseus
    # Agent-facing MCP edges (register with capabilities-mcp on boot).
    just deploy-kt ariadne-mcp
    just deploy-kt echo-mcp
    just deploy-kt kadmos-mcp
    just deploy-kt theseus-mcp
    # Themis (routing agent) — not a fork service, but the first Spine consumer of
    # the forked stack. fork Stage 2.6 switch-over points it at Kadmos/Echo/Prometheus;
    # deployed here so a local `deploy-fork` brings up an end-to-end resolvable Themis.
    just deploy-kt themis
    @echo "✅ deploy-fork applied. Watch rollout: kubectl -n kantheon get pods -w"
