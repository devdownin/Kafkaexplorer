#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# What mcp-probe.sh decides, against a stub MCP server.
#
# The probe exists to separate "the server is not answering" from "the model reasoned badly"
# when an agent scenario fails, so the cases that matter are its FAILURES: a probe that reports
# OK against a server that answered nothing useful is worse than no probe, because it moves the
# blame onto the model. Each case below therefore fixes one shape of answer and asserts both the
# exit status and the sentence — the sentence is the whole product of a diagnostic.
#
# Two shapes of a correct answer are covered rather than one: streamable HTTP may reply with an
# SSE frame or with plain JSON, the server chooses, and reading only one of them is a probe that
# works until the day the transport is reconfigured.
#
# The server is stubbed rather than started, on the same argument as seed-demo-once.test.sh: what
# is under test is how a shell script reads an answer, so a real MiniCluster behind a real broker
# would cost minutes and prove nothing extra. `McpServerBootTest` is where a claim about what
# Spring AI actually registers belongs.
#
# Run it directly: ./mcp-probe.test.sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROBE="${PROBE:-$HERE/mcp-probe.sh}"
PORT="${MCP_PROBE_TEST_PORT:-8137}"
WORK=$(mktemp -d)
STUB_PID=""
trap 'if [ -n "$STUB_PID" ]; then kill "$STUB_PID" 2>/dev/null || true; fi; rm -rf "$WORK"' EXIT INT TERM

FAILURES=0

cat > "$WORK/stub.py" <<'PY'
"""One stub, one MODE per case. Everything it varies is a shape of answer the probe must read."""
import json, os, sys, http.server, socketserver

MODE = os.environ["MODE"]
PORT = int(os.environ["PORT"])
TOOLS = ["kex_list_topics", "kex_describe_topic", "kex_sql_query"]
COVERED = {"topics": [{"name": "demo.orders.1.received"}],
           "coverage": {"stopReason": "EXHAUSTED", "topicsNotReached": []}}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def do_POST(self):
        if MODE == "not-bound":          # the overlay was not layered: /mcp does not exist
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Not Found")
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        method = request.get("method")
        if method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
            return
        self.send_response(200)
        if method == "initialize":
            self.send_header("Mcp-Session-Id", "probe-test-session")
            result = {"protocolVersion": "2025-06-18",
                      "serverInfo": {"name": "kafka-explorer-mcp", "version": "0.1.0"}}
        elif method == "tools/list":
            names = [] if MODE == "no-tools" else TOOLS
            result = {"tools": [{"name": n, "inputSchema": {}} for n in names]}
        elif MODE == "tool-error":
            result = {"content": [{"type": "text", "text": "refused"}], "isError": True}
        elif MODE == "no-coverage":      # an answer that cannot say what it did not read
            result = {"content": [{"type": "text", "text": json.dumps({"topics": []})}],
                      "isError": False}
        else:
            result = {"content": [{"type": "text", "text": json.dumps(COVERED)}], "isError": False}
        body = json.dumps({"jsonrpc": "2.0", "id": request.get("id"), "result": result})
        if MODE == "plain-json":
            raw, content_type = body.encode(), "application/json"
        else:
            raw, content_type = ("event: message\ndata: " + body + "\n\n").encode(), "text/event-stream"
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


socketserver.TCPServer.allow_reuse_address = True
server = socketserver.TCPServer(("127.0.0.1", PORT), Handler)
sys.stderr.write("ready\n")
sys.stderr.flush()
server.serve_forever()
PY

start_stub() {
    MODE="$1" PORT="$PORT" python3 "$WORK/stub.py" 2>"$WORK/stub.err" &
    STUB_PID=$!
    _tries=0
    until curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; do
        _tries=$((_tries + 1))
        [ "$_tries" -gt 50 ] && { echo "the stub never came up" >&2; exit 1; }
        sleep 0.2
    done
}

stop_stub() {
    [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null || true
    wait "$STUB_PID" 2>/dev/null || true
    STUB_PID=""
}

# expect <name> <mode> <expected-status> [substring the output must contain]
expect() {
    _name="$1"; _mode="$2"; _want="$3"; _needle="${4:-}"
    start_stub "$_mode"
    set +e
    MCP_BASE_URL="http://127.0.0.1:$PORT" MCP_PROBE_WAIT_SECONDS=10 \
        sh "$PROBE" >"$WORK/out.txt" 2>&1
    _got=$?
    set -e
    stop_stub
    if [ "$_got" -ne "$_want" ]; then
        echo "FAIL  $_name: exit $_got, expected $_want"
        sed 's/^/      /' "$WORK/out.txt"
        FAILURES=$((FAILURES + 1))
        return
    fi
    if [ -n "$_needle" ] && ! grep -qF "$_needle" "$WORK/out.txt"; then
        echo "FAIL  $_name: the output never mentions '$_needle'"
        sed 's/^/      /' "$WORK/out.txt"
        FAILURES=$((FAILURES + 1))
        return
    fi
    echo "ok    $_name"
}

# A server that answers correctly, in each of the two shapes streamable HTTP allows.
expect "an SSE answer is read"                    ok         0 "3 tools listed"
expect "a plain JSON answer is read"              plain-json 0 "3 tools listed"
# The coverage envelope is the contract, so the probe reports what it found rather than only OK.
expect "the stop reason is reported"              ok         0 "stopReason: EXHAUSTED"
expect "the session id is reported"               ok         0 "probe-test-session"

# The failures, which are the point.
expect "MCP off is named as such"                 not-bound  1 "EXPLORER_MCP_ENABLED=true"
expect "an empty tool list names the deny-list"   no-tools   1 "EXPLORER_MCP_TOOLS_DENIED"
expect "a tool that answered an error fails"      tool-error 1 "answered an error"
expect "an answer with no coverage fails"         no-coverage 1 "no coverage envelope"

# Nothing listening at all: the wait is bounded and says what it waited for.
set +e
MCP_BASE_URL="http://127.0.0.1:$((PORT + 1))" MCP_PROBE_WAIT_SECONDS=2 \
    sh "$PROBE" >"$WORK/out.txt" 2>&1
_got=$?
set -e
if [ "$_got" -eq 1 ] && grep -qF "did not answer within" "$WORK/out.txt"; then
    echo "ok    an unreachable application is a bounded wait"
else
    echo "FAIL  an unreachable application is a bounded wait: exit $_got"
    sed 's/^/      /' "$WORK/out.txt"
    FAILURES=$((FAILURES + 1))
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "All cases pass."
else
    echo "$FAILURES case(s) failed."
    exit 1
fi
