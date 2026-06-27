#!/usr/bin/env bash
# verify-forked-proto-layout.sh
#
# Phase 1.2 T6 — invariant check on the forked pipeline protos.
#
# This shell check is a cheap textual pre-check over the `*.proto` source
# files. The authoritative wire-contract gate is the Kotest
# `ForkedProtoDescriptorSpec` in `:shared:proto` (a
# `Descriptors.FileDescriptor` walk over the generated types). Both are
# kept: the script is fast and runs without Gradle; the Kotest is the real
# gate in CI.
#
# Wire-level (c) "every messages=99 type is the kantheon stand-in" is
# enforced by `shared/proto/src/test/kotlin/org/tatrman/kantheon/fork/ForkedProtoDescriptorSpec.kt`
# and by protoc itself — protoc refuses to generate two ResponseMessage
# types in the same package.

set -euo pipefail

# Files we forked. Adjust on each new fork / re-fork.
FORKED_DIRS=(
    "shared/proto/src/main/proto/org/tatrman/plan"
    "shared/proto/src/main/proto/org/tatrman/worker"
    "shared/proto/src/main/proto/org/tatrman/transdsl"
    "shared/proto/src/main/proto/org/tatrman/dfdsl"
    "shared/proto/src/main/proto/org/tatrman/ariadne"
    "shared/proto/src/main/proto/org/tatrman/echo"
    "shared/proto/src/main/proto/org/tatrman/kadmos"
    "shared/proto/src/main/proto/org/tatrman/proteus"
    "shared/proto/src/main/proto/org/tatrman/prometheus"
    "shared/proto/src/main/proto/org/tatrman/argos"
    "shared/proto/src/main/proto/org/tatrman/security"
    "shared/proto/src/main/proto/org/tatrman/kyklop"
    "shared/proto/src/main/proto/org/tatrman/theseus"
)

# Check (b): no `cz/dfpartner/` import path in any forked file.
# `rg --no-comment` skips lines that are *only* a comment (`//` prefix)
# — the kantheon stand-in file documents the historical cz/dfpartner
# namespace in its KDoc, and the check should ignore prose.
echo "[fork-proto-layout] Checking forked files for legacy cz/dfpartner references…"
if rg --no-comment -l 'cz/dfpartner/' "${FORKED_DIRS[@]}" 2>/dev/null; then
    echo "❌ Found legacy cz/dfpartner/ imports in forked proto files. Stage 1.2 T2/T3 incomplete." >&2
    exit 1
fi

# Check (b'): no fully-qualified `cz.dfpartner.*` type ref either (catches
# the case where someone forgot to rewrite a body-level type reference).
if rg --no-comment -l 'cz\.dfpartner\.' "${FORKED_DIRS[@]}" 2>/dev/null; then
    echo "❌ Found legacy cz.dfpartner. type refs in forked proto files. Stage 1.2 T2/T3 incomplete." >&2
    exit 1
fi

# Check (a): every forked file declares the org.tatrman.<X>.v1 package.
echo "[fork-proto-layout] Checking forked files declare org.tatrman.* packages…"
declare -A EXPECTED_PACKAGES=(
    ["shared/proto/src/main/proto/org/tatrman/plan/v1/plan.proto"]="org.tatrman.plan.v1"
    ["shared/proto/src/main/proto/org/tatrman/plan/v1/context.proto"]="org.tatrman.plan.v1"
    ["shared/proto/src/main/proto/org/tatrman/plan/v1/parameters.proto"]="org.tatrman.plan.v1"
    ["shared/proto/src/main/proto/org/tatrman/worker/v1/worker.proto"]="org.tatrman.worker.v1"
    ["shared/proto/src/main/proto/org/tatrman/transdsl/v1/transdsl.proto"]="org.tatrman.transdsl.v1"
    ["shared/proto/src/main/proto/org/tatrman/dfdsl/v1/dfdsl.proto"]="org.tatrman.dfdsl.v1"
    ["shared/proto/src/main/proto/org/tatrman/ariadne/v1/ariadne.proto"]="org.tatrman.ariadne.v1"
    ["shared/proto/src/main/proto/org/tatrman/echo/v1/echo_service.proto"]="org.tatrman.echo.v1"
    ["shared/proto/src/main/proto/org/tatrman/kadmos/v1/kadmos.proto"]="org.tatrman.kadmos.v1"
    ["shared/proto/src/main/proto/org/tatrman/proteus/v1/proteus.proto"]="org.tatrman.proteus.v1"
    ["shared/proto/src/main/proto/org/tatrman/prometheus/v1/prometheus_chat.proto"]="org.tatrman.prometheus.v1"
    ["shared/proto/src/main/proto/org/tatrman/argos/v1/argos.proto"]="org.tatrman.argos.v1"
    ["shared/proto/src/main/proto/org/tatrman/security/v1/security.proto"]="org.tatrman.security.v1"
    ["shared/proto/src/main/proto/org/tatrman/kyklop/v1/kyklop.proto"]="org.tatrman.kyklop.v1"
    ["shared/proto/src/main/proto/org/tatrman/theseus/v1/theseus.proto"]="org.tatrman.theseus.v1"
)
fail=0
for file in "${!EXPECTED_PACKAGES[@]}"; do
    if [[ ! -f "$file" ]]; then
        echo "❌ Expected forked file missing: $file" >&2
        fail=1
        continue
    fi
    expected="${EXPECTED_PACKAGES[$file]}"
    if ! grep -qE "^package ${expected//./\\.};" "$file"; then
        actual=$(grep -E "^package " "$file" || echo "<none>")
        echo "❌ $file: expected package '${expected}', got '${actual}'" >&2
        fail=1
    fi
done
if [[ $fail -ne 0 ]]; then
    exit 1
fi

# Check (c, source level): every `= 99` field in the forked files types
# its field as the kantheon stand-in. Wire-level enforcement is the Kotest
# `ForkedProtoDescriptorSpec` in `:shared:proto`; this is a textual
# pre-check.
echo "[fork-proto-layout] Checking Rule-6 messages=99 fields type the kantheon stand-in…"
if rg -nP 'repeated\s+cz\.dfpartner\.[\w.]+\.ResponseMessage\s+messages\s*=\s*99' "${FORKED_DIRS[@]}" 2>/dev/null; then
    echo "❌ Found messages=99 typed against cz.dfpartner.* — Stage 1.2 T3 incomplete." >&2
    exit 1
fi

# Same for fully-bare `ResponseMessage messages = 99;` — must be qualified.
if rg -nP '^\s*repeated\s+ResponseMessage\s+messages\s*=\s*99' "${FORKED_DIRS[@]}" 2>/dev/null; then
    echo "❌ Found bare 'ResponseMessage messages = 99' — must be fully qualified to the kantheon stand-in." >&2
    exit 1
fi

# Repo-wide (since fork Stage 2.6 — the Themis switch-over): NO in-repo proto
# imports or references cz/dfpartner anymore. The former themis.proto allowlist
# is retired. Skip comment-only matches (provenance notes document the history).
echo "[fork-proto-layout] Verifying ALL in-repo protos are cz/dfpartner-free (repo-wide, post-2.6)…"
leaks=$(rg --no-comment -l 'cz/dfpartner/|cz\.dfpartner\.' shared/proto/src/main/proto 2>/dev/null || true)
if [[ -n "$leaks" ]]; then
    echo "❌ Found cz/dfpartner refs in in-repo protos (repo-wide check):" >&2
    echo "$leaks" >&2
    exit 1
fi

echo "[fork-proto-layout] OK — all forked proto layout invariants hold."
