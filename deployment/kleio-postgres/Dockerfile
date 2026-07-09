# Custom CloudNativePG operand image for Kleio's dedicated Postgres (WS-D3 wave 6).
#
# Kleio runs all four RAG planes in one DB (docs/architecture/kleio): relational +
# full-text (tsvector/GIN — core) + vector (pgvector) + graph (Apache AGE). The stock
# CNPG operand image ships neither pgvector nor AGE, so this image adds both on top of
# the CNPG PG18 base (which keeps the CNPG contract: barman-cloud, the postgres uid 26,
# the entrypoint). pg_trgm is core contrib (already present).
#
# Build + push (from repo root):
#   docker build -t ghcr.io/boraperusic/kleio-postgres:testing deployment/kleio-postgres
#   docker push  ghcr.io/boraperusic/kleio-postgres:testing
# The olymp Cluster `kleio-pg` references this via spec.imageName.
#
# PIN the base tag deliberately — CNPG operand tags float. `18-standard-bookworm` is the
# PG18 line; bump in lockstep with the operator's supported operand version.

ARG PG_MAJOR=18
ARG AGE_REF=master   # AGE branch/tag supporting PG18 (1.6.x line; pin to a release tag once cut)

# ── Stage 1: compile Apache AGE against the matching PG dev headers ──────────
FROM ghcr.io/cloudnative-pg/postgresql:${PG_MAJOR}-standard-bookworm AS age-build
ARG PG_MAJOR
ARG AGE_REF
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential git flex bison \
        postgresql-server-dev-${PG_MAJOR} \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${AGE_REF} https://github.com/apache/age.git /tmp/age \
    && cd /tmp/age \
    && make PG_CONFIG=/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config \
    && make install PG_CONFIG=/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config

# ── Stage 2: the runtime image = CNPG base + pgvector (apt) + AGE (copied) ────
FROM ghcr.io/cloudnative-pg/postgresql:${PG_MAJOR}-standard-bookworm
ARG PG_MAJOR
USER root
# pgvector from PGDG (the CNPG bookworm base has the PGDG apt source configured).
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-${PG_MAJOR}-pgvector \
    && rm -rf /var/lib/apt/lists/*
# AGE artifacts (the .so + the SQL/control files) from the build stage.
COPY --from=age-build /usr/lib/postgresql/${PG_MAJOR}/lib/age.so           /usr/lib/postgresql/${PG_MAJOR}/lib/
COPY --from=age-build /usr/share/postgresql/${PG_MAJOR}/extension/age*      /usr/share/postgresql/${PG_MAJOR}/extension/
# Back to the CNPG-required non-root uid.
USER 26
