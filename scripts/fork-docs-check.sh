#!/usr/bin/env bash
# fork-docs-check.sh
#
# Fork Stage 4.2 T6 — cross-doc consistency gate for the platform fork.
#
# A cheap textual check that the fork docs and the code agree on the three
# things that drift: persona names, package roots, and ports. Plus a guard
# that the dissolved couplings stay dissolved (no live cz.dfpartner / no
# pre-fork query-mcp persona name / no query_mcp_ metric prefix outside the
# allowed survivors).
#
# CI-optional: run locally before closing a fork stage, or wire into ci.yml.
# Exit non-zero on any drift; prints every offender.

set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
note() { printf '  %s\n' "$1"; }
section() { printf '\n== %s ==\n' "$1"; }

# persona | service dir | proto pkg root | HTTP | gRPC(- if none) | mcp wrapper port(- if none)
TABLE='
ariadne|services/ariadne|org.tatrman.ariadne.v1|7260|7261|7262
theseus|services/theseus|org.tatrman.theseus.v1|7305|7306|7307
echo|services/echo|org.tatrman.echo.v1|7265|7266|7267
kadmos|services/kadmos|org.tatrman.kadmos.v1|7270|-|7272
proteus|services/proteus|org.tatrman.proteus.v1|7275|7276|-
prometheus|services/prometheus|org.tatrman.prometheus.v1|7280|9090|-
argos|services/argos|org.tatrman.argos.v1|7285|7286|-
kyklop|services/kyklop|org.tatrman.kyklop.v1|7290|7291|-
brontes|workers/brontes|org.tatrman.worker.v1|7295|7296|-
steropes|workers/steropes|org.tatrman.worker.v1|7300|7301|-
'

section "Per-module: README provenance + HTTP port present in k8s base"
while IFS='|' read -r persona dir pkg http grpc mcp; do
    [ -z "${persona:-}" ] && continue
    readme="$dir/README.md"
    if [ ! -f "$readme" ]; then
        note "MISSING README: $readme"; fail=1
    elif ! grep -qiE "forked-from" "$readme"; then
        note "README has no provenance header: $readme"; fail=1
    fi
    # HTTP port must appear in the module's k8s base manifests
    if ! grep -rqs "$http" "$dir"/k8s/base/ 2>/dev/null; then
        note "port $http not found in $dir/k8s/base/ (table says HTTP=$http)"; fail=1
    fi
done <<< "$TABLE"

# Scope of the coupling guards: CODE + CONFIG only. Docs (design/architecture
# narratives, archives) legitimately carry historical pre-fork names and the
# rename map — purging those is out of scope. We exclude this script itself,
# build output, docs, and markdown.
CODE_GLOBS=(--glob '!**/build/**' --glob '!docs/**' --glob '!*.md'
            --glob '!scripts/fork-docs-check.sh' --glob '!scripts/verify-forked-proto-layout.sh')

section "No live cz.dfpartner refs in code/config (comments + provenance allowed)"
# Live = an import/type/host line, not a comment line.
hits=$(rg -n "cz\.dfpartner" "${CODE_GLOBS[@]}" . 2>/dev/null \
        | rg -v '^[^:]+:[0-9]+:\s*(//|#|\*|/\*)' \
        | rg -v ':[0-9]+:.*(//|#).*cz\.dfpartner' || true)
if [ -n "$hits" ]; then
    note "live cz.dfpartner refs:"; printf '%s\n' "$hits"; fail=1
fi

section "No pre-fork 'query-mcp' persona name in code/config (settings provenance excepted)"
hits=$(rg -n "query-mcp" "${CODE_GLOBS[@]}" . 2>/dev/null \
        | rg -v 'settings\.gradle\.kts' || true)
if [ -n "$hits" ]; then
    note "stray query-mcp refs in code/config (rename to theseus-mcp):"; printf '%s\n' "$hits"; fail=1
fi

section "No pre-fork query_mcp_ metric prefix in code"
hits=$(rg -n "query_mcp_" "${CODE_GLOBS[@]}" . 2>/dev/null || true)
if [ -n "$hits" ]; then
    note "query_mcp_ metric prefix should be theseus_mcp_:"; printf '%s\n' "$hits"; fail=1
fi

section "Kotlin source roots: forked services use org.tatrman.kantheon.<svc>"
# Exceptions: kadmos + steropes are Python (no Kotlin root); prometheus is the
# Spring-Boot forked-as-is service kept on org.tatrman.prometheus (CLAUDE.md §4).
for persona in ariadne theseus echo proteus argos kyklop; do
    root="services/$persona/src/main/kotlin/org/tatrman/kantheon/$persona"
    [ -d "$root" ] || { note "expected Kotlin root missing: $root"; fail=1; }
done
[ -d "workers/brontes/src/main/kotlin/org/tatrman/kantheon/brontes" ] \
    || { note "expected Kotlin root missing: workers/brontes/.../kantheon/brontes"; fail=1; }
[ -d "services/prometheus/src/main/kotlin/org/tatrman/prometheus" ] \
    || { note "prometheus (Spring Boot as-is) expected at org.tatrman.prometheus"; fail=1; }

echo
if [ "$fail" -eq 0 ]; then
    echo "✅ fork-docs-check: persona names, ports, package roots, and dissolved couplings all consistent."
else
    echo "❌ fork-docs-check: drift found (see above)."
fi
exit "$fail"
