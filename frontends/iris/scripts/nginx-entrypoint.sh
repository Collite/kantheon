#!/bin/sh
# Render nginx.conf.template, substituting only the BFF-upstream variables.
#
# The FE talks to iris-bff same-origin via the /bff proxy (Stage 2.4), so the
# only template vars are the in-cluster BFF upstream. Defaults target the
# co-deployed iris-bff Service on bp-dsk; override via the chart ConfigMap.

export BFF_UPSTREAM_PROTOCOL="${BFF_UPSTREAM_PROTOCOL:-http}"
export BFF_UPSTREAM_HOST="${BFF_UPSTREAM_HOST:-iris-bff}"
export BFF_UPSTREAM_PORT="${BFF_UPSTREAM_PORT:-7410}"

VARS='${BFF_UPSTREAM_PROTOCOL}
${BFF_UPSTREAM_HOST}
${BFF_UPSTREAM_PORT}
'

envsubst "$VARS" < /etc/nginx/nginx.conf.template > /etc/nginx/conf.d/default.conf
