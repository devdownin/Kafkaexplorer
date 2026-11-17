# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
FROM python:3.12-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_CACHE_DIR=/tmp/uv-cache
WORKDIR /app

# git: the Timestral shadow package is locked to a commit of its repository, not to PyPI.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir uv==0.9.17 \
 && addgroup --system --gid 10001 timesfm \
 && adduser --system --uid 10001 --gid 10001 --home /app timesfm \
 && mkdir -p /models /tmp/uv-cache \
 && chown -R timesfm:timesfm /app /models /tmp/uv-cache

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY kex_timesfm ./kex_timesfm
RUN chown -R timesfm:timesfm /app

# `offline` — the same service with both checkpoints baked in, for a deployment that must start
# with no route to the Hub. Build it with `--target offline`; the default target below is the
# one `compose/timesfm.yml` builds, which downloads at first start. The download happens here,
# once, at build time, through the same `kex_timesfm.prefetch` the runtime uses — so the SHA-256
# checks that guard the volume guard the image too, and a pin that no longer matches fails the
# build, not a container at 3 a.m. The pins are build arguments, but they are also what
# `compose/forecast-offline.yml` defaults to: change them together.
FROM base AS offline
ARG TIMESTRAL_REVISION=49b26409ac87b5935539a5d8ffe7f397b720db47
ARG TIMESTRAL_SHA256=dfc83c6220937ce5fc6687b35a4df94cd25e28a2cfe6fb92a23dad6301b2015d
ENV TIMESFM_CACHE_DIR=/models \
    HF_HOME=/models \
    TIMESTRAL_REVISION=${TIMESTRAL_REVISION} \
    TIMESTRAL_SHA256=${TIMESTRAL_SHA256}
USER 10001:10001
RUN /app/.venv/bin/python -m kex_timesfm.prefetch
# After the download, never before: the prefetch above is the one thing that needs the Hub.
ENV HF_HUB_OFFLINE=1
COPY --chown=10001:10001 licenses /licenses
LABEL org.opencontainers.image.title="kex-forecast" \
      org.opencontainers.image.description="TimesFM 2.5 and Timestral-28M (shadow) forecast service with both checkpoints baked in; starts with no network access. WARNING: includes Timestral-28M (Mistral AI), whose licence excludes companies above USD 20M monthly revenue - see /licenses." \
      org.opencontainers.image.licenses="AGPL-3.0-or-later AND Apache-2.0 AND LicenseRef-Timestral-Modified-MIT"
EXPOSE 8000
# No `uv run` here: it would resolve the environment again at start, which is network access
# the image exists to avoid.
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["exec /app/.venv/bin/uvicorn kex_timesfm.app:application --factory --host 0.0.0.0 --port 8000"]

# The default target stays last, so a plain `docker build` is the image it always was.
FROM base AS runtime
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["uv run python -m kex_timesfm.prefetch && exec uv run uvicorn kex_timesfm.app:application --factory --host 0.0.0.0 --port 8000"]
