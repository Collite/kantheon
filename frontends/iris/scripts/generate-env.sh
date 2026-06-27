#!/bin/sh
set -e

# Regenerate the runtime config consumed by the SPA at /env.js.
#
# index.html loads /env.js (a classic script) before the app bundle, so
# window.APP_CONFIG is populated before src/config/index.ts reads it.
# Keys MUST match the VITE_* names read in src/config/index.ts exactly.
#
# Values are passed through verbatim from the container environment (the
# K8s ConfigMap). Unset vars resolve to "" here; the app then falls back to
# the in-code defaults in src/config/index.ts, so defaults live in ONE place.

echo "Generating environment configuration..."

cat <<EOF > /usr/share/nginx/html/env.js
window.APP_CONFIG = {
    VITE_USER_ID: '${VITE_USER_ID}',
    VITE_USER_NAME: '${VITE_USER_NAME}',

    VITE_AUTH_ENABLED: '${VITE_AUTH_ENABLED}',
    VITE_KEYCLOAK_URL: '${VITE_KEYCLOAK_URL}',
    VITE_KEYCLOAK_REALM: '${VITE_KEYCLOAK_REALM}',
    VITE_KEYCLOAK_CLIENT_ID: '${VITE_KEYCLOAK_CLIENT_ID}',

    // The single backend origin since the Stage 2.2 re-point. Defaults to '/bff'
    // so the app calls same-origin and nginx proxies /bff/ → in-cluster iris-bff.
    VITE_BFF_BASE_URL: '${VITE_BFF_BASE_URL}',

    VITE_TELEMETRY_ENABLED: '${VITE_TELEMETRY_ENABLED}',
    VITE_OTEL_EXPORTER_OTLP_HOST: '${VITE_OTEL_EXPORTER_OTLP_HOST}',
    VITE_OTEL_EXPORTER_OTLP_HTTP_PORT: '${VITE_OTEL_EXPORTER_OTLP_HTTP_PORT}',
    VITE_OTEL_EXPORTER_OTLP_HTTPS_PORT: '${VITE_OTEL_EXPORTER_OTLP_HTTPS_PORT}',
    VITE_OTEL_EXPORTER_OTLP_GRPC_PORT: '${VITE_OTEL_EXPORTER_OTLP_GRPC_PORT}',
    VITE_AGENTS_FE_OTEL_PROTOCOL: '${VITE_AGENTS_FE_OTEL_PROTOCOL}',

    VITE_LLM_GTW_SERVER: '${VITE_LLM_GTW_SERVER}',
    VITE_LLM_GTW_SERVER_PORT: '${VITE_LLM_GTW_SERVER_PORT}',
    VITE_LLM_GTW_SERVER_PATH: '${VITE_LLM_GTW_SERVER_PATH}',
    VITE_LLM_GTW_SERVER_PROTOCOL: '${VITE_LLM_GTW_SERVER_PROTOCOL}',

    VITE_GOLEM_SERVER: '${VITE_GOLEM_SERVER}',
    VITE_GOLEM_SERVER_PORT: '${VITE_GOLEM_SERVER_PORT}',
    VITE_GOLEM_SERVER_PATH: '${VITE_GOLEM_SERVER_PATH}',
    VITE_GOLEM_SERVER_PROTOCOL: '${VITE_GOLEM_SERVER_PROTOCOL}',
    // JSON array of agents — wrapped in single quotes, so the JSON's double
    // quotes pass through unescaped. Do NOT use single quotes inside labels.
    VITE_GOLEM_AGENTS: '${VITE_GOLEM_AGENTS}',

    VITE_MCP_ERP_SERVER: '${VITE_MCP_ERP_SERVER}',
    VITE_MCP_ERP_SERVER_PORT: '${VITE_MCP_ERP_SERVER_PORT}',
    VITE_MCP_ERP_SERVER_PATH: '${VITE_MCP_ERP_SERVER_PATH}',
    VITE_MCP_ERP_SERVER_PROTOCOL: '${VITE_MCP_ERP_SERVER_PROTOCOL}',

    VITE_MCP_FUZZY_SERVER: '${VITE_MCP_FUZZY_SERVER}',
    VITE_MCP_FUZZY_SERVER_PORT: '${VITE_MCP_FUZZY_SERVER_PORT}',
    VITE_MCP_FUZZY_SERVER_PATH: '${VITE_MCP_FUZZY_SERVER_PATH}',
    VITE_MCP_FUZZY_SERVER_PROTOCOL: '${VITE_MCP_FUZZY_SERVER_PROTOCOL}',

    VITE_MCP_METADATA_SERVER: '${VITE_MCP_METADATA_SERVER}',
    VITE_MCP_METADATA_SERVER_PORT: '${VITE_MCP_METADATA_SERVER_PORT}',
    VITE_MCP_METADATA_SERVER_PATH: '${VITE_MCP_METADATA_SERVER_PATH}',
    VITE_MCP_METADATA_SERVER_PROTOCOL: '${VITE_MCP_METADATA_SERVER_PROTOCOL}',

    VITE_MCP_LOCAL_METADATA_SERVER: '${VITE_MCP_LOCAL_METADATA_SERVER}',
    VITE_MCP_LOCAL_METADATA_SERVER_PORT: '${VITE_MCP_LOCAL_METADATA_SERVER_PORT}',
    VITE_MCP_LOCAL_METADATA_SERVER_PATH: '${VITE_MCP_LOCAL_METADATA_SERVER_PATH}',
    VITE_MCP_LOCAL_METADATA_SERVER_PROTOCOL: '${VITE_MCP_LOCAL_METADATA_SERVER_PROTOCOL}',

    VITE_TABLE_PAGE_SIZE: '${VITE_TABLE_PAGE_SIZE}',
};
EOF

echo "Environment configuration generated."
