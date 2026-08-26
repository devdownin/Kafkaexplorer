#!/bin/sh
# Entrypoint for the `llm-chat` service of compose/spectra-hub.yml.
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
MODELS=/app/data/models
FILE="${LLM_CHAT_MODEL_FILE}"
ALIAS="${LLM_CHAT_MODEL_NAME}"
# The registry pointer spectra-api writes when the active model changes: line 1 is the
# alias, line 2 the GGUF file name. Read once, at start — see the header note.
POINTER="${MODELS}/active-chat-model"
if [ -f "${POINTER}" ]; then
  a=$(head -n 1 "${POINTER}" | tr -d '\r')
  f=$(sed -n '2p' "${POINTER}" | tr -d '\r')
  if [ -n "${a}" ]; then ALIAS="${a}"; fi
  if [ -n "${f}" ]; then FILE="${f}"; fi
fi
while [ ! -f "${MODELS}/${FILE}" ]; do
  echo "[llm-chat] waiting for ${MODELS}/${FILE} — spectra-api is installing it (~4.7 GB on first boot); follow it with: docker compose -f compose/spectra-hub.yml logs -f spectra-api"
  sleep 10
done
SERVER=""
for c in /app/llama-server /llama-server /usr/local/bin/llama-server; do
  if [ -x "${c}" ]; then SERVER="${c}"; break; fi
done
if [ -z "${SERVER}" ]; then echo "[llm-chat] no llama-server binary in this image"; exit 1; fi
# An EMPTY LLM_CONTEXT means "let llama-server decide", which is upstream's
# pass-through convention — so the flag is omitted rather than passed as `-c ""`,
# which llama-server rejects outright.
CTX_ARG=""
if [ -n "${LLM_CONTEXT}" ]; then CTX_ARG="-c ${LLM_CONTEXT}"; fi
echo "[llm-chat] serving ${FILE} as ${ALIAS}"
exec "${SERVER}" --host 0.0.0.0 --port 8081 --model "${MODELS}/${FILE}" \
  --alias "${ALIAS}" ${CTX_ARG} --parallel "${LLM_PARALLEL}" ${LLM_CHAT_EXTRA_ARGS}
