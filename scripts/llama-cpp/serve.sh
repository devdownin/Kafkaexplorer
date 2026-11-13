#!/bin/sh
# Entrypoint for the `llama-cpp` service of compose/llama-cpp.yml.
#
# Out of the YAML for the reason scripts/spectra-hub/llm-chat.sh gives: compose interpolates
# `${…}` inside an entrypoint, so every shell variable there has to be written `$${…}`, and one
# forgotten `$` is an empty string at runtime rather than an error.
#
# It waits for the weights instead of failing: `llama-cpp-model` downloads several gigabytes on
# a first boot, and a server that exited on a missing file would crash-loop for the length of
# that download.

set -u
FILE="/models/${LLAMA_CPP_MODEL_FILE}"
while [ ! -f "${FILE}" ]; do
  echo "[llama-cpp] waiting for ${FILE} - llama-cpp-model is downloading it; follow it with: docker compose -f docker-compose.yml -f compose/llama-cpp.yml logs -f llama-cpp-model"
  sleep 10
done
SERVER=""
for c in /app/llama-server /llama-server /usr/local/bin/llama-server; do
  if [ -x "${c}" ]; then SERVER="${c}"; break; fi
done
if [ -z "${SERVER}" ]; then echo "[llama-cpp] no llama-server binary in this image"; exit 1; fi
echo "[llama-cpp] serving ${LLAMA_CPP_MODEL_FILE} as ${LLAMA_CPP_MODEL_NAME}, ${LLAMA_CPP_CONTEXT} tokens, ${LLAMA_CPP_PARALLEL} slot(s)"
exec "${SERVER}" --host 0.0.0.0 --port 8080 --model "${FILE}" --alias "${LLAMA_CPP_MODEL_NAME}" \
  -c "${LLAMA_CPP_CONTEXT}" --parallel "${LLAMA_CPP_PARALLEL}" ${LLAMA_CPP_EXTRA_ARGS}
