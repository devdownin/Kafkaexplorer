#!/bin/sh
# Entrypoint for the `llm-embed` service of compose/spectra-hub.yml.
#
# It lives here rather than inline in compose/spectra-hub.yml because compose
# interpolates `${…}` inside a YAML entrypoint, so every shell variable had to be written
# `$${…}` — around forty escapes across the three entrypoints, each one a chance to write a
# single `$` and get an empty string at runtime rather than an error. `.gitattributes` pins
# `*.sh` to LF on every platform, so a Windows checkout still mounts something this image's
# /bin/sh can run.
#
# The cost, stated where it is paid: the hub stack now needs the repository checked out. It
# already did — it mounts setup-demo.sh and seed-demo-once.sh — but this makes it definitive.

set -u
MODEL="/app/data/models/${LLM_EMBED_MODEL_FILE}"
while [ ! -f "${MODEL}" ]; do
  echo "[llm-embed] waiting for ${MODEL} — the spectra-models one-shot fetches it (~81 MB)"
  sleep 5
done
SERVER=""
for c in /app/llama-server /llama-server /usr/local/bin/llama-server; do
  if [ -x "${c}" ]; then SERVER="${c}"; break; fi
done
if [ -z "${SERVER}" ]; then echo "[llm-embed] no llama-server binary in this image"; exit 1; fi
echo "[llm-embed] serving ${MODEL}"
exec "${SERVER}" --host 0.0.0.0 --port 8082 --model "${MODEL}" --embedding \
  -c 8192 -b 2048 -ub 2048 --parallel "${LLM_EMBED_PARALLEL}" ${LLM_EMBED_EXTRA_ARGS}
