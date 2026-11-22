#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Smoke probe for the MCP server: the JSON-RPC handshake, tools/list, and one real tool call.
# Entrypoint of the `mcp-probe` service in compose/mcp.yml, and runnable from a host too:
#
#     MCP_BASE_URL=http://localhost:8080 MCP_AUTH_TOKEN=... sh mcp-probe.sh
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
AUTH_TOKEN="${MCP_AUTH_TOKEN:-}"

[ -n "$AUTH_TOKEN" ] || { echo "FAIL: MCP_AUTH_TOKEN is required" >&2; exit 1; }

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
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -w '%{http_code}' \
        -D "$_headers" -o "$_out" --data-binary "$_body"
    [ -n "${SESSION:-}" ] && set -- "$@" -H "Mcp-Session-Id: $SESSION"
    # The body went to -o, so stdout carries the status alone. It is kept because every way this
    # endpoint can refuse has a different remedy, and one message naming all of them sends an
    # operator to check three settings when the server already said which.
    curl "$@" > "$WORK/status" || true
}

# Why the endpoint refused, in the operator's terms rather than the protocol's.
refusal_hint() {
    case "$(cat "$WORK/status" 2>/dev/null)" in
        401|403) echo "the credential was refused: check EXPLORER_MCP_AUTH_TOKEN on both sides." ;;
        426)     echo "the endpoint requires TLS: terminate TLS in front of it, or set" \
                      "EXPLORER_MCP_REQUIRE_TLS=false for a loopback development stack." ;;
        503)     echo "the application has no MCP token configured: set EXPLORER_MCP_AUTH_TOKEN." ;;
        404|405) echo "nothing is bound at $ENDPOINT: is EXPLORER_MCP_ENABLED=true and the" \
                      "compose/mcp.yml overlay layered?" ;;
        *)       echo "is EXPLORER_MCP_ENABLED=true, the compose/mcp.yml overlay layered, and" \
                      "EXPLORER_MCP_AUTH_TOKEN correct?" ;;
    esac
}

# A streamable-HTTP answer may arrive as an SSE frame. Unwrap it to the JSON payload; a plain
# JSON answer passes through untouched.
payload() {
    if grep -q '^data:' "$1"; then sed -n 's/^data: \{0,1\}//p' "$1"; else cat "$1"; fi
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
    echo "   response: $(payload "$WORK/init.json")" >&2
    fail "initialize was refused (HTTP $(cat "$WORK/status" 2>/dev/null)). $(refusal_hint)"
}

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

case "$RESULT" in
    *'"isError":true'* | *'"isError": true'*) echo "   $RESULT" >&2; fail "kex_list_topics answered an error" ;;
    *'"error"'*)        echo "   $RESULT" >&2; fail "tools/call was refused at the protocol level" ;;
esac

PLAIN=$(printf '%s' "$RESULT" | sed 's/\\"/"/g')
echo "$PLAIN" | grep -q '"coverage"' || fail "the response carries no coverage envelope"
echo "   stopReason: $(echo "$PLAIN" | grep -o '"stopReason" *: *"[A-Z_]*' | sed 's/.*"//' | head -1)"
echo "   $(printf '%s' "$RESULT" | wc -c | tr -d ' ') bytes returned"

echo "→ resources/list"
call '{"jsonrpc":"2.0","id":4,"method":"resources/list"}' "$WORK/resources.json" "$WORK/resources.headers"
RESOURCES=$(payload "$WORK/resources.json")
printf '%s' "$RESOURCES" | grep -q 'kex://contract' || fail "resources/list is missing kex://contract"
printf '%s' "$RESOURCES" | grep -q 'kafka://topics' || fail "resources/list is missing kafka://topics"

echo "→ resources/templates/list"
call '{"jsonrpc":"2.0","id":5,"method":"resources/templates/list"}' "$WORK/templates.json" "$WORK/templates.headers"
TEMPLATES=$(payload "$WORK/templates.json")
printf '%s' "$TEMPLATES" | grep -q 'kafka://topics/{topic}' || fail "resources/templates/list is missing kafka://topics/{topic}"

echo "→ resources/read kex://contract"
call '{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"kex://contract"}}' "$WORK/read.json" "$WORK/read.headers"
READ_RESULT=$(payload "$WORK/read.json")
printf '%s' "$READ_RESULT" | grep -q 'measurement' || fail "resources/read is missing the measurement contract"
printf '%s' "$READ_RESULT" | grep -q '"error"' && fail "resources/read was refused"

echo "→ prompts/list"
call '{"jsonrpc":"2.0","id":7,"method":"prompts/list"}' "$WORK/prompts.json" "$WORK/prompts.headers"
PROMPTS=$(payload "$WORK/prompts.json")
for name in kex_incident_triage kex_dlq_postmortem kex_onboard_domain kex_schema_drift_check kex_flow_health kex_forecast_analysis; do
    printf '%s' "$PROMPTS" | grep -q "$name" || fail "prompts/list is missing $name"
done

echo "→ prompts/get kex_incident_triage"
call '{"jsonrpc":"2.0","id":8,"method":"prompts/get","params":{"name":"kex_incident_triage","arguments":{"key":"probe-correlation","window":"15m"}}}' "$WORK/prompt.json" "$WORK/prompt.headers"
PROMPT=$(payload "$WORK/prompt.json")
printf '%s' "$PROMPT" | grep -q 'probe-correlation' || fail "prompts/get did not interpolate the correlation key"
printf '%s' "$PROMPT" | grep -q '"error"' && fail "prompts/get was refused"

echo
echo "OK — tools, resources, templates and prompts answer at $ENDPOINT"
