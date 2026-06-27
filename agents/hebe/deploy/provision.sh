#!/usr/bin/env bash
# Provision a new Hebe instance (contracts §4.4 runbook). Idempotent where practical.
#
#   ./provision.sh <instance_id>
#
# Steps:
#   1. CREATE SCHEMA hebe_<id> + a dedicated role limited to that schema, with NO
#      UPDATE/DELETE on the append-only tables (receipts, messages, llm_calls,
#      tool_calls) and NO cross-schema access (instance isolation, architecture §5.1).
#   2. flyway -schemas=hebe_<id> migrate  (the shared db/migration-pg/ set).
#   3. Create the K8s Secret hebe-<id> skeleton (PG creds, Keycloak client creds +
#      bound user, llm-gateway key, Telegram bot token, receipts signing key).
#   4. Apply the Kustomize overlay (see `just deploy-hebe`).  [T3]
#   5. Register/verify the manifest in capabilities-mcp.       [Stage 3.4]
#
# Env (override as needed):
#   PG_ADMIN_URL   admin JDBC/psql conn string (default: postgres://postgres@localhost:5432/hebe)
#   K8S_NAMESPACE  default: kantheon
set -euo pipefail

ID="${1:?usage: provision.sh <instance_id>}"
SCHEMA="hebe_${ID}"
ROLE="hebe_${ID}_app"
PG_ADMIN_URL="${PG_ADMIN_URL:-postgres://postgres@localhost:5432/hebe}"
K8S_NAMESPACE="${K8S_NAMESPACE:-kantheon}"
APP_PASSWORD="${APP_PASSWORD:-$(openssl rand -base64 24)}"

echo "==> [1/3] schema + grant-limited role: ${SCHEMA} / ${ROLE}"
psql "${PG_ADMIN_URL}" <<SQL
CREATE SCHEMA IF NOT EXISTS ${SCHEMA};
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${ROLE}') THEN
    CREATE ROLE ${ROLE} LOGIN PASSWORD '${APP_PASSWORD}';
  END IF;
END \$\$;
-- pgvector must exist DB-wide for V2 (vector column + HNSW); created once per DB.
CREATE EXTENSION IF NOT EXISTS vector;
-- Instance isolation: usage limited to this schema only.
GRANT USAGE ON SCHEMA ${SCHEMA} TO ${ROLE};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${SCHEMA} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${ROLE};
SQL

echo "==> [2/3] flyway migrate (schemas=${SCHEMA})"
# The shared PG migration set lives in :agents:hebe:modules:memory db/migration-pg/.
flyway -url="jdbc:${PG_ADMIN_URL#postgres:}" \
  -schemas="${SCHEMA}" \
  -locations="filesystem:agents/hebe/modules/memory/src/main/resources/db/migration-pg" \
  migrate

echo "==> [2b/3] append-only grants (no UPDATE/DELETE on the audit tables)"
psql "${PG_ADMIN_URL}" <<SQL
SET search_path TO ${SCHEMA};
REVOKE UPDATE, DELETE ON receipts, messages, llm_calls, tool_calls FROM ${ROLE};
SQL

echo "==> [3/3] K8s Secret skeleton: hebe-${ID} (fill in the real values)"
kubectl -n "${K8S_NAMESPACE}" create secret generic "hebe-${ID}" \
  --from-literal=pg \
  --from-literal=keycloak-client-secret= \
  --from-literal=llm-gateway-key= \
  --from-literal=telegram= \
  --from-literal=receipts.signing_key= \
  --dry-run=client -o yaml | kubectl apply -f -

cat <<NEXT
==> provisioned hebe_${ID}.
    app role:     ${ROLE}  (no UPDATE/DELETE on receipts/messages/llm_calls/tool_calls)
    next:         just deploy-hebe ${ID}      # step 4 — apply the overlay
                  (capabilities-mcp registration is automatic at boot — Stage 3.4)
NEXT
