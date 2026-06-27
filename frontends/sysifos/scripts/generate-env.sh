#!/bin/sh
# Regenerate /usr/share/nginx/html/env.js from the container's VITE_* env at
# start, so window.APP_CONFIG carries the deploy-time config (auth, Keycloak,
# BFF base) without a rebuild. Empty values fall back to in-code defaults.
cat > /usr/share/nginx/html/env.js <<EOF
window.APP_CONFIG = {
  VITE_BFF_BASE: "${VITE_BFF_BASE:-}",
  VITE_AUTH_ENABLED: "${VITE_AUTH_ENABLED:-}",
  VITE_KEYCLOAK_URL: "${VITE_KEYCLOAK_URL:-}",
  VITE_KEYCLOAK_REALM: "${VITE_KEYCLOAK_REALM:-}",
  VITE_KEYCLOAK_CLIENT_ID: "${VITE_KEYCLOAK_CLIENT_ID:-}",
  VITE_TENANT_CLAIM: "${VITE_TENANT_CLAIM:-}"
};
EOF
