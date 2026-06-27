#!/usr/bin/env bash
# Verify that ~/.gradle/gradle.properties contains a GitHub PAT with read:packages
# scope so that Gradle can resolve ai-platform Maven artifacts from GitHub Packages.
# See AGENTS.md §2 (Bootstrap) and docs/implementation/v1/aip-v1-gap-closure-plan.md Gap 1.

set -euo pipefail

PROPS_FILE="${HOME}/.gradle/gradle.properties"

# In CI, GITHUB_ACTOR + GITHUB_TOKEN are injected by the runner; skip the file check.
if [[ -n "${CI:-}" || (-n "${GITHUB_ACTOR:-}" && -n "${GITHUB_TOKEN:-}") ]]; then
    echo "check-pat: CI environment detected — using GITHUB_ACTOR / GITHUB_TOKEN."
    exit 0
fi

if [[ ! -f "${PROPS_FILE}" ]]; then
    cat >&2 <<EOF
check-pat: ${PROPS_FILE} not found.

Create it with your GitHub PAT (classic, scope: read:packages):

    cat >> "${PROPS_FILE}" <<EOM
    gpr.user=<your-github-handle>
    gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
    EOM

See AGENTS.md §2 for the full bootstrap.
EOF
    exit 1
fi

missing=0
if ! grep -q "^gpr.user=" "${PROPS_FILE}"; then
    echo "check-pat: gpr.user missing in ${PROPS_FILE}" >&2
    missing=1
fi
if ! grep -q "^gpr.token=" "${PROPS_FILE}"; then
    echo "check-pat: gpr.token missing in ${PROPS_FILE}" >&2
    missing=1
fi

if (( missing )); then
    echo >&2
    echo "Add the missing entries per AGENTS.md §2." >&2
    exit 1
fi

echo "check-pat: gpr.user + gpr.token present in ${PROPS_FILE}"
