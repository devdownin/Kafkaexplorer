#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Smoke probe for the MCP server: the JSON-RPC handshake, tools/list, and one real tool call.
# Entrypoint of the `mcp-probe` service in compose/mcp.yml, and runnable from a host too:
#
#     MCP_BASE_URL=http://localhost:8080 sh mcp-probe.sh
#
# WHY IT EXISTS. A scenario that fails against this server has two causes with one symptom — the
# model reasoned badly, or the surface was never there. This separates them before the harness
# runs, which is the only thing that can be checked without a model at all.
#
# curl and POSIX sh only: it runs in `curlimages/curl`, which has no jq and no bash. So the JSON
# is read with grep, which is crude and deliberately so — this asserts that a field is PRESENT,
# never what it contains. What the values mean is the harness's question (SPECAGENT.md), not this
# script's, and a parser written here would be a second, worse one.
set -eu

BASE="${MCP_BASE_URL:-http://localhost:8080}"
ENDPOINT="$BASE/mcp"
PREFIX="${MCP_PROBE_TOPIC_PREFIX:-demo.}"
PROTOCOL_VERSION="2025-06-18"
WAIT_SECONDS="${MCP_PROBE_WAIT_SECONDS:-120}"

WORK="${TMPDIR:-/tmp}/mcp-probe.$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT INT TERM

fail() { echo "FAIL: $*" >&2; exit 1; }

# The MCP spec requires BOTH media types in Accept — a server may answer either, and Spring AI
# refuses the request outright when only one is offered.
call() {
    _body="$1"; _out="$2"; _headers="$3"
    set -- -sS -X POST "$ENDPOINT" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
        -D "$_headers" -o "$_out" --data-binary "$_body"
    [ -n "${SESSION:-}" ] && set -- "$@" -H "Mcp-Session-Id: $SESSION"
    curl "$@"
}

# A streamable-HTTP answer may arrive as an SSE frame. Unwrap it to the JSON payload; a plain
# JSON answer passes through untouched.
payload() {
    if grep -q '^data: ' "$1"; then sed -n 's/^data: //p' "$1"; else cat "$1"; fi
}

echo "→ waiting for $BASE to answer"
_waited=0
until curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; do
    _waited=$((_waited + 2))
    [ "$_waited" -ge "$WAIT_SECONDS" ] && fail "$BASE did not answer within ${WAIT_SECONDS}s"
    sleep 2
done

echo "→ initialize"
call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"kex-compose-probe","version":"1"}}}' \
     "$WORK/init.json" "$WORK/init.headers"

grep -q '"result"' "$WORK/init.json" || {
    # The commonest cause by far, and worth naming rather than dumping the body: the overlay was
    # not layered, so explorer.mcp.enabled is still false and /mcp is not bound at all.
    echo "   response: $(payload "$WORK/init.json")" >&2
    fail "initialize was refused. Is compose/mcp.yml layered (EXPLORER_MCP_ENABLED=true)?"
}

# Header names are case-insensitive on the wire and servers differ on the casing they send.
SESSION=$(tr -d '\r' < "$WORK/init.headers" | sed -n 's/^[Mm][Cc][Pp]-[Ss]ession-[Ii][Dd]: *//p' | tail -1)
echo "   server: $(payload "$WORK/init.json" | grep -o '"serverInfo"[^}]*}' || echo '(unnamed)')"
echo "   session: ${SESSION:-none (stateless)}"

# Required by the protocol before any other request, and it is a notification: no id, no answer.
call '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$WORK/ready.json" "$WORK/ready.headers"

echo "→ tools/list"
call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$WORK/tools.json" "$WORK/tools.headers"
TOOLS=$(payload "$WORK/tools.json" | grep -o '"name" *: *"kex_[a-z_]*"' | sed 's/.*"kex_/kex_/;s/"$//' | sort -u)
[ -n "$TOOLS" ] || {
    echo "   response: $(payload "$WORK/tools.json")" >&2
    fail "tools/list returned no kex_* tool. A deny-list (EXPLORER_MCP_TOOLS_DENIED) can empty it."
}
echo "$TOOLS" | sed 's/^/   /'
echo "   $(echo "$TOOLS" | wc -l | tr -d ' ') tools listed"

echo "→ tools/call kex_list_topics prefix=$PREFIX"
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"kex_list_topics","arguments":{"prefix":"'"$PREFIX"'","limit":5}}}' \
     "$WORK/call.json" "$WORK/call.headers"
RESULT=$(payload "$WORK/call.json")

# An MCP tool reports its own failure INSIDE a successful JSON-RPC response, so a 200 proves
# nothing on its own — `isError` is the field that says whether the tool ran.
case "$RESULT" in
    *'"isError":true'* | *'"isError": true'*) echo "   $RESULT" >&2; fail "kex_list_topics answered an error" ;;
    *'"error"'*)        echo "   $RESULT" >&2; fail "tools/call was refused at the protocol level" ;;
esac

# A tool answer is a JSON document carried inside a JSON string, so every quote in it arrives
# escaped. Unescape once, and the two greps below read the same text a client would.
PLAIN=$(printf '%s' "$RESULT" | sed 's/\\"/"/g')

# The two contracts this whole module exists for. Their ABSENCE is a defect worth failing on: a
# response with no coverage is one that cannot say what it did not read, and the empty list it
# might carry would then be indistinguishable from an exhausted scan.
echo "$PLAIN" | grep -q '"coverage"' || fail "the response carries no coverage envelope"
echo "   stopReason: $(echo "$PLAIN" | grep -o '"stopReason" *: *"[A-Z_]*' | sed 's/.*"//' | head -1)"
echo "   $(printf '%s' "$RESULT" | wc -c | tr -d ' ') bytes returned"

echo
echo "OK — the MCP surface answers at $ENDPOINT"
