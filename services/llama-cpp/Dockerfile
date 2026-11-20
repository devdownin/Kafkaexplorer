# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# The local LLM of compose/llama-cpp.yml with its weights inside the image, so a deployment with
# no route to the Hub starts it with `docker compose up`. The model is the one EMBEDDED-LLM-SCOPE.md
# retained for both Process Mining and the SQL assistant: Gemma 4 E4B, Q4_K_M, Apache-2.0.
#
# Built from the REPOSITORY ROOT (`-f services/llama-cpp/Dockerfile .`), because the entrypoint is
# scripts/llama-cpp/serve.sh — the very file compose/llama-cpp.yml mounts — and copying it here
# would be a second copy to keep in step. Dockerfile.dockerignore trims that context to the two
# paths this file reads.
#
# The download happens here, once, at build time, and is checked against a SHA-256 taken from the
# Hub at the pinned revision: a file that does not match fails the build, not a start. The base is
# the build compose/llama-cpp.yml pins (`LLAMA_CPP_IMAGE_TAG`), by digest; change them together.
FROM ghcr.io/ggml-org/llama.cpp:server-b9828@sha256:565c0f60c9357967aa2af3d3b90d9905bc04a3b5ff469bc322aa801086bc3f5e

ARG MODEL_FILE=gemma-4-E4B-it-Q4_K_M.gguf
ARG MODEL_URL=https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/bfc15c382204943c3a8fff0c750b94ae2364d7a3/gemma-4-E4B-it-Q4_K_M.gguf
ARG MODEL_SHA256=85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87

RUN mkdir -p /models \
 && curl -fL --retry 5 --connect-timeout 30 -o "/models/${MODEL_FILE}" "${MODEL_URL}" \
 && echo "${MODEL_SHA256}  /models/${MODEL_FILE}" | sha256sum -c - \
 && chmod -R a+rX /models

COPY scripts/llama-cpp/serve.sh /opt/llama-cpp/serve.sh
COPY services/llama-cpp/licenses /licenses

# What compose/llama-cpp.yml sets from the same variables. The image runs on its own with them.
ENV LLAMA_CPP_MODEL_FILE=${MODEL_FILE} \
    LLAMA_CPP_MODEL_NAME=gemma-4-e4b-it \
    LLAMA_CPP_CONTEXT=16384 \
    LLAMA_CPP_PARALLEL=1 \
    LLAMA_CPP_EXTRA_ARGS=

LABEL org.opencontainers.image.title="kex-llm" \
      org.opencontainers.image.description="Gemma 4 E4B (Q4_K_M, Apache-2.0) on llama.cpp, with the weights baked in; starts with no network access." \
      org.opencontainers.image.licenses="AGPL-3.0-or-later AND Apache-2.0 AND MIT"

# Not root (METRICS-FORECAST-AUDIT-2.md S1): the server only reads /models and its own binary, and
# `kex-forecast` already runs as 10001. CI's `llm-offline` job boots this exact user with no network.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/opt/llama-cpp/serve.sh"]
