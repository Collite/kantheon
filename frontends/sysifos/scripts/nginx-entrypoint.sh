#!/bin/sh
# Render nginx.conf.template, substituting only the BFF-upstream variables. The
# FE talks to sysifos-bff same-origin via the /bff proxy, so the only template
# vars are the in-cluster BFF upstream. Defaults target the co-deployed
# sysifos-bff Service; override via the chart ConfigMap.

export BFF_UPSTREAM_PROTOCOL="${BFF_UPSTREAM_PROTOCOL:-http}"
export BFF_UPSTREAM_HOST="${BFF_UPSTREAM_HOST:-sysifos-bff}"
export BFF_UPSTREAM_PORT="${BFF_UPSTREAM_PORT:-7601}"

VARS='${BFF_UPSTREAM_PROTOCOL}
${BFF_UPSTREAM_HOST}
${BFF_UPSTREAM_PORT}
'

envsubst "$VARS" < /etc/nginx/nginx.conf.template > /etc/nginx/conf.d/default.conf
