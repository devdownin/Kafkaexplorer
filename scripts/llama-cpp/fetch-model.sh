#!/bin/sh
# Entrypoint for the `llama-cpp-model` one-shot of compose/llama-cpp.yml: one GGUF, fetched once.
#
# The `.part` name is what keeps a truncated file from ever being served: the server waits on
# the final name, and only a completed transfer takes it. No resume, for the reason
# scripts/spectra-hub/fetch-models.sh gives - appending to a file the server did not range is
# the right size and the wrong bytes - so a failed transfer restarts from zero on the next `up`.

set -u
DEST="/models/${LLAMA_CPP_MODEL_FILE}"
PART="${DEST}.part"
if [ -s "${DEST}" ]; then
  echo "[llama-cpp-model] already present: ${DEST}"
  exit 0
fi
echo "[llama-cpp-model] downloading ${LLAMA_CPP_MODEL_URL}"
if ! curl -fL --retry 5 --connect-timeout 30 -o "${PART}" "${LLAMA_CPP_MODEL_URL}"; then
  echo "[llama-cpp-model] the download FAILED - ${DEST} was not written; re-run this stack to try again"
  exit 1
fi
if [ -n "${LLAMA_CPP_MODEL_SHA256}" ]; then
  got=$(sha256sum "${PART}" | cut -d" " -f1)
  if [ "${got}" != "${LLAMA_CPP_MODEL_SHA256}" ]; then
    echo "[llama-cpp-model] FAILED its checksum (expected ${LLAMA_CPP_MODEL_SHA256}, got ${got}) - ${DEST} was not written"
    exit 1
  fi
fi
mv "${PART}" "${DEST}"
echo "[llama-cpp-model] ready: ${DEST}"
