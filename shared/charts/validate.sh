#!/usr/bin/env bash
# Chart render golden harness (deploy-test WS-D Stage 1, tasks-d1-chart-library.md T1).
#
# For every deployable module chart (`<module>/k8s/Chart.yaml`), render it with
# `helm template` using the chart's own default values and compare the output to
# a checked-in golden under shared/charts/.golden/<name>.yaml.
#
#   ./shared/charts/validate.sh capture   # (re)write the goldens from current charts
#   ./shared/charts/validate.sh check     # render + diff against goldens (CI gate; default)
#
# The goldens are the regression oracle for the kantheon-service library-chart
# migration: after migrating a chart onto the library, its render must be
# BYTE-EQUIVALENT to the pre-migration golden (fix the library, not the golden).
set -euo pipefail

MODE="${1:-check}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"
GOLDEN_DIR="shared/charts/.golden"
mkdir -p "$GOLDEN_DIR"

# Discover every module chart. shared/charts/* are library charts (type: library),
# not deployables — exclude them (they render nothing on their own).
mapfile -t CHARTS < <(find agents services workers tools infra frontends -type f -name Chart.yaml \
  -path '*/k8s/Chart.yaml' | sort)

chart_name() { awk '/^name:/{print $2; exit}' "$1/Chart.yaml"; }

# Normalize inter-document whitespace so the goldens are HELM-VERSION-AGNOSTIC:
# helm v4 emits a blank line before each `---` doc separator that helm v3 does not,
# which would otherwise drift every chart depending on the runner's helm version.
# Drop a blank line immediately before a `---` separator, and trim trailing blanks;
# intra-resource blank lines (if any) are preserved.
normalize() {
  awk '
    { L[NR] = $0 }
    END {
      n = NR
      while (n > 0 && L[n] ~ /^[[:space:]]*$/) n--          # trim trailing blanks
      for (i = 1; i <= n; i++) {
        if (L[i] ~ /^[[:space:]]*$/ && L[i+1] == "---") continue   # drop blank-before-separator
        print L[i]
      }
    }'
}

render() {
  # $1 = chart dir. Deterministic: release name = the chart's `name:` (unique;
  # avoids dir-basename collisions like midas/core -> core), fixed namespace.
  local dir="$1" name
  name="$(chart_name "$dir")"
  # Resolve the file:// library dependency if the chart declares one (no-op otherwise).
  if grep -q '^dependencies:' "$dir/Chart.yaml" 2>/dev/null; then
    helm dependency build "$dir" >/dev/null 2>&1 || {
      echo "helm dependency build failed for $dir" >&2; return 1; }
  fi
  helm template "$name" "$dir" --namespace "$name" | normalize
}

fail=0
for chart in "${CHARTS[@]}"; do
  dir="$(dirname "$chart")"
  name="$(chart_name "$dir")"
  golden="$GOLDEN_DIR/$name.yaml"
  out="$(render "$dir")" || { echo "RENDER FAIL: $name"; fail=1; continue; }
  if [[ "$MODE" == "capture" ]]; then
    printf '%s\n' "$out" > "$golden"
    echo "captured: $name"
  else
    if [[ ! -f "$golden" ]]; then
      echo "MISSING GOLDEN: $name (run: ./shared/charts/validate.sh capture)"; fail=1; continue
    fi
    if ! diff -u "$golden" <(printf '%s\n' "$out") > /tmp/chartdiff.$name 2>&1; then
      echo "DRIFT: $name"; cat /tmp/chartdiff.$name; fail=1
    else
      echo "ok: $name"
    fi
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo; echo "chart validation FAILED"; exit 1
fi
echo; echo "all charts render byte-equivalent to goldens"
