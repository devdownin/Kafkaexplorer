#!/bin/sh
# Entrypoint for the `spectra-models` service of compose/spectra-hub.yml.
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
fetch() {
  url="$1"; dest="$2"; want="$3"; label="$4"
  if [ -z "${url}" ]; then
    echo "[models] no URL given for the ${label} — skipped (it is installed elsewhere, or not wanted)"
    return 0
  fi
  if [ -s "${dest}" ]; then
    echo "[models] ${label} already present: ${dest}"
    return 0
  fi
  echo "[models] downloading the ${label} from ${url}"
  # `wget`, not `curl`. The Spectra image installs both to run the llmfit installer and
  # then ends that same layer with `apt-get purge -y curl`, keeping wget — which is why
  # its own healthcheck is a wget. A curl here therefore found nothing at runtime
  # (`/bin/sh: curl: not found`, and no model fetched), and CI found that the only way
  # such a thing is ever found: by running it.
  #
  # No resume. `wget -c` with `-O` appends blindly when the server ignores a Range
  # request, which produces a file that is the right size and the wrong bytes — and
  # only a pinned digest would catch it. A failed transfer therefore restarts from
  # zero on the next `up`; that is the cost, and it is the argument for pinning
  # SPECTRA_*_MODEL_SHA256 on a large model over a link that drops.
  #
  # The .part name is what keeps a truncated file from ever being served: the
  # llama-server containers wait on the final name, and only a completed — and, when a
  # digest is pinned, verified — transfer takes it.
  rm -f "${dest}.part"
  if ! wget --tries=5 --timeout=30 -O "${dest}.part" "${url}"; then
    rm -f "${dest}.part"
    echo "[models] the ${label} download FAILED — nothing was written; re-run this stack to try again"
    return 1
  fi
  # Verification is skipped rather than faked if the image has no sha256sum — but a
  # digest that was *asked* for and cannot be computed is a refusal, not a detail: the
  # whole point of pinning it is not to serve an unverified file.
  got=""
  if command -v sha256sum >/dev/null 2>&1; then
    got=$(sha256sum "${dest}.part" | cut -d" " -f1)
  elif [ -n "${want}" ]; then
    rm -f "${dest}.part"
    echo "[models] the ${label} carries a pinned digest but this image has no sha256sum — refusing to serve it unverified"
    return 1
  fi
  if [ -n "${want}" ] && [ "${got}" != "${want}" ]; then
    # Deleted, not kept: a file that failed its digest is not a file to serve a model
    # from, and leaving it would only be re-found as "already present" on the next run.
    rm -f "${dest}.part"
    echo "[models] the ${label} FAILED its checksum (expected ${want}, got ${got}) — the file was deleted"
    return 1
  fi
  mv "${dest}.part" "${dest}"
  if [ -n "${want}" ]; then
    echo "[models] ${label} ready and verified: ${dest}"
  elif [ -n "${got}" ]; then
    echo "[models] ${label} ready: ${dest} (sha256 ${got} — copy it into SPECTRA_*_MODEL_SHA256 to pin it)"
  else
    echo "[models] ${label} ready: ${dest} (unverified — no sha256sum in this image)"
  fi
}
rc=0
fetch "${SPECTRA_EMBED_MODEL_URL}" "/app/data/models/${LLM_EMBED_MODEL_FILE}" \
  "${SPECTRA_EMBED_MODEL_SHA256}" "embedding model" || rc=1
fetch "${SPECTRA_CHAT_MODEL_URL}" "/app/data/models/${LLM_CHAT_MODEL_FILE}" \
  "${SPECTRA_CHAT_MODEL_SHA256}" "chat model" || rc=1
exit ${rc}
