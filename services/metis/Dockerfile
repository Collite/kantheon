# Metis (model estimation) — multi-stage Dockerfile.
#
# Image-size / build-time strategy: prophet+cmdstan dominate build time, so
# they live in a `prophet_layer` stage that depends ONLY on the deps layer.
# App-source changes invalidate only the final thin COPY layers.
#
# Build from the repo root:
#   just build-py services/metis

FROM python:3.13-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ===== dependencies layer =====
FROM base AS deps

WORKDIR /build/services/metis

COPY shared/libs/python/otel-config /build/shared/libs/python/otel-config
COPY shared/proto/build/python-package /build/shared/proto/build/python-package

COPY services/metis/pyproject.toml ./
# uv.lock is copied separately so it can be optional on first build.
# Run `cd services/metis && uv sync` locally to generate the lockfile,
# then commit it — subsequent Docker builds will use --frozen.
COPY services/metis/uv.lock* ./
RUN pip install --upgrade pip && pip install uv && \
    if [ -f uv.lock ]; then uv sync --frozen; else uv sync; fi

# ===== prophet / cmdstan layer (heavy; cached as long as deps don't change) =====
FROM deps AS prophet_layer

ENV PATH="/build/services/metis/.venv/bin:${PATH}"

# Download and compile cmdstan (Prophet's sampling backend).
RUN python - <<'PY'
import cmdstanpy
print("Downloading cmdstan...")
cmdstanpy.install_cmdstan()
print("cmdstan installed at:", cmdstanpy.cmdstan_path())
PY

# Smoke-test prophet installation
RUN python - <<'PY'
from prophet import Prophet
import pandas as pd
import numpy as np
df = pd.DataFrame({'ds': pd.date_range('2020-01-01', periods=24, freq='MS'), 'y': np.random.rand(24)})
m = Prophet()
m.fit(df)
print("Prophet smoke test passed")
PY

# ===== runtime layer =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser
WORKDIR /app

COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/metis/.venv /build/services/metis/.venv
COPY --from=deps /build/shared /build/shared

# Copy compiled cmdstan from the prophet layer
COPY --from=prophet_layer /root/.cmdstan /tmp/cmdstan
RUN mkdir -p /home/appuser/.cmdstan && \
    cp -r /tmp/cmdstan/* /home/appuser/.cmdstan/ && \
    chown -R appuser:appuser /home/appuser/.cmdstan && \
    rm -rf /tmp/cmdstan

USER appuser

COPY --chown=appuser:appuser services/metis/pyproject.toml ./
COPY --chown=appuser:appuser services/metis/src ./src

ENV PATH="/build/services/metis/.venv/bin:${PATH}"
ENV PYTHONPATH=/app/src
ENV CMDSTAN=/home/appuser/.cmdstan
ENV METIS_GRPC_PORT=7261
ENV METIS_HTTP_PORT=7260

EXPOSE ${METIS_HTTP_PORT}
EXPOSE ${METIS_GRPC_PORT}

HEALTHCHECK --interval=30s --timeout=3s --start-period=120s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${METIS_HTTP_PORT:-7260}/healthz || exit 1

CMD ["python", "-m", "metis.main"]
