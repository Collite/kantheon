#!/usr/bin/env bash
# Stage 1.5 T7 — Excel-loader end-to-end smoke.
#
# Deploys are out of scope here; run AFTER:
#   just local-infra-up
#   just deploy-kt core            # midas-core (REST :7310, MCP :7311)
#   just deploy-kt excel           # midas-excel-loader (:7315)
#
# Then port-forward both (separate shells) and run this:
#   kubectl -n kantheon port-forward svc/midas-core 7310:7310
#   kubectl -n kantheon port-forward svc/midas-excel-loader 7315:7315
#   ./agents/midas/loaders/excel/k8s/smoke.sh
#
# Flow: create client+portfolio (midas-core) → upload the alpha fixture (loader) →
# preview → commit → assert the transactions landed in midas-core. Auth is
# validate-only in v1 (no signature), so we mint an unsigned JWT with a tenant claim.
#
# Requires: curl, jq, uuidgen, base64.
set -euo pipefail

CORE="${MIDAS_CORE_URL:-http://localhost:7310}"
LOADER="${LOADER_URL:-http://localhost:7315}"
FIXTURE="${FIXTURE:-agents/midas/loaders/excel/src/test/resources/fixtures/alpha_sample.xlsx}"
TENANT="${TENANT:-$(uuidgen | tr 'A-Z' 'a-z')}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
HEADER=$(printf '{"alg":"none"}' | b64url)
PAYLOAD=$(printf '{"sub":"smoke","tenant":"%s"}' "$TENANT" | b64url)
JWT="${HEADER}.${PAYLOAD}."
AUTH=(-H "Authorization: Bearer ${JWT}" -H "X-Tenant-Id: ${TENANT}")

echo "▸ tenant=${TENANT}"

echo "▸ create client"
CLIENT_ID=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"client":{"name":"Smoke Co"}}' "${CORE}/api/v1/clients" | jq -r '.client.clientId')
echo "  clientId=${CLIENT_ID}"

echo "▸ create portfolio (track_cash)"
PORTFOLIO_ID=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"portfolio\":{\"clientId\":\"${CLIENT_ID}\",\"name\":\"Smoke PF\",\"baseCurrency\":\"USD\",\"trackCash\":true}}" \
  "${CORE}/api/v1/portfolios" | jq -r '.portfolio.portfolioId')
echo "  portfolioId=${PORTFOLIO_ID}"

echo "▸ upload ${FIXTURE}"
RUN_ID=$(curl -fsS "${AUTH[@]}" \
  -F "file=@${FIXTURE};type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  -F "broker_id=alpha" -F "portfolio_id=${PORTFOLIO_ID}" \
  "${LOADER}/api/v1/uploads" | jq -r '.loader_run_id')
echo "  runId=${RUN_ID}"

echo "▸ preview"
curl -fsS "${AUTH[@]}" "${LOADER}/api/v1/runs/${RUN_ID}/preview" | jq '.summary'

echo "▸ commit (skip_existing)"
curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"skip_existing":true,"confirm":true}' "${LOADER}/api/v1/runs/${RUN_ID}/commit" | jq '.'

echo "▸ verify transactions in midas-core"
COUNT=$(curl -fsS "${AUTH[@]}" "${CORE}/api/v1/transactions?portfolio_id=${PORTFOLIO_ID}&size=100" \
  | jq '.transactions | length')
echo "  transactions=${COUNT}"
# 4 security legs + derived cash legs (track_cash) → expect > 4.
if [ "${COUNT}" -lt 4 ]; then echo "❌ expected ≥4 transactions, got ${COUNT}"; exit 1; fi

echo "▸ re-upload + re-commit is idempotent (inserts nothing new)"
RUN_ID2=$(curl -fsS "${AUTH[@]}" \
  -F "file=@${FIXTURE};type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  -F "broker_id=alpha" -F "portfolio_id=${PORTFOLIO_ID}" \
  "${LOADER}/api/v1/uploads" | jq -r '.loader_run_id')
[ "${RUN_ID2}" = "${RUN_ID}" ] || { echo "❌ re-upload should return the same run (${RUN_ID2} != ${RUN_ID})"; exit 1; }
INSERTED=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"skip_existing":true,"confirm":true}' "${LOADER}/api/v1/runs/${RUN_ID}/commit" | jq '.insertedCount')
[ "${INSERTED}" = "0" ] || { echo "❌ re-commit inserted ${INSERTED}, expected 0"; exit 1; }

echo "✅ smoke passed (tenant ${TENANT}): upload → preview → commit → verified; re-run idempotent"
