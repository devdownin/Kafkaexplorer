# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_CACHE_DIR=/tmp/uv-cache
WORKDIR /app

RUN pip install --no-cache-dir uv==0.9.17 \
 && addgroup --system --gid 10001 timesfm \
 && adduser --system --uid 10001 --gid 10001 --home /app timesfm \
 && mkdir -p /models /tmp/uv-cache \
 && chown -R timesfm:timesfm /app /models /tmp/uv-cache

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY kex_timesfm ./kex_timesfm
RUN chown -R timesfm:timesfm /app

USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["uv run python -m kex_timesfm.prefetch && exec uv run uvicorn kex_timesfm.app:application --factory --host 0.0.0.0 --port 8000"]
