# MCP Enterprise · OAuth protected resource, per-tool authorization and Tasks readiness

This page describes the **implemented** HTTP security boundary, followed by the precise
compatibility limit for the new MCP Tasks extension. It is **not** a claim that KafkaExplorer
has become a generic OAuth authorization server or supports native asynchronous MCP Tasks.

## 1. Deployment and trust boundaries

KafkaExplorer continues to serve MCP at `/mcp` using Spring AI 2.0.2 and the
**stateful streamable-HTTP** transport. Existing installs keep their
`EXPLORER_MCP_AUTH_TOKEN` static bearer; the OAuth profile is an explicit opt-in.
The OAuth profile requires an **external OAuth 2.1-compliant authorization server**.
KafkaExplorer is only the protected resource: it **does not** implement authorization
code/PKCE, client registration, login, refresh tokens or a token endpoint.

Production prerequisites:

- Trusted external IdP capable of issuing **RS256 signed JWT access tokens**, with a stable
  `iss`, `sub`, `exp`, and `aud` identifying the exact public MCP resource.
- An operator-selected HTTPS JWK Set URI; never derive a JWKS URL or issuer from token claims,
  an untrusted request header or a Kafka record.
- A public HTTPS endpoint **at the root path** `https://explorer.example.org/mcp`.
  Set `server.forward-headers-strategy` safely for a trusted ingress that terminates TLS.
- A distinct audience per resource server. Tokens for other services are rejected, including
  JWTs signed by the same identity provider.
- Explicit IdP scope grants. No scope is inferred from a subject, group, role string, or tool
  description.

Example configuration:

```sh
export EXPLORER_MCP_OAUTH_ENABLED=true
export EXPLORER_MCP_OAUTH_ISSUER_URI=https://idp.example.org/realms/operations
export EXPLORER_MCP_OAUTH_JWK_SET_URI=https://idp.example.org/realms/operations/protocol/openid-connect/certs
export EXPLORER_MCP_OAUTH_RESOURCE_URI=https://explorer.example.org/mcp
```

An administrator can optionally restrict individual tools (exact names, not wildcards) in
`application.yml`:

```yaml
explorer:
  mcp:
    oauth:
      tool-scopes:
        "[kex_run_audit]": "mcp:audit"
        "[kex_sql_query]": "mcp:sql"
```

**Scopes are not OAuth configuration by themselves**: the IdP must grant the named claims
to the intended callers. The implementation reads the OAuth `scope` string or the
`scp` string list **only after verifying the JWT signature, issuer, timestamps and audience**.
Never send an IdP client secret or long-lived refresh token to the Explorer MCP endpoint.

## 2. Authorization decisions

| Entry point | Required scope | Rules |
| --- | --- | --- |
| `/mcp`, tool discovery, read tools, resources, prompts | `mcp:read` | Valid JWT + scope required before MCP dispatch |
| `GET /api/mcp/**` (except audit replay) | `mcp:read` | The enterprise console catalogue, clients and live call data are private |
| `GET /api/mcp/calls/replay` | `mcp:admin` | Historical audit data is privileged |
| Non-GET `/api/mcp/**` | `mcp:admin` | Even newly added mutations are covered by default |
| A mutating MCP tool (if registered) | `mcp:read` + `mcp:write` | Read-only registration guard and approval tokens remain applicable |
| A tool named in `oauth.tool-scopes` | The base scope(s) + configured extra scope | Enforced **before** approval-token spending and before business I/O |

The tool's category is read from the existing **introspected MCP catalogue**, not guessed
from its name. A newly introduced WRITE tool cannot silently inherit read access.
A missing catalogue or missing OAuth identity refuses the invocation.
The current SDK exposes **one global tool discovery list**. A tool may therefore
appear in `tools/list` to a client that cannot invoke it; the **permission is enforced on
`tools/call`**, not by pretending discovery is personalized.

Scope refusal does not bypass KafkaExplorer's topic/group allowlists, DLP, execution budgets,
quarantine, rate limiting, approval tokens or read-only registration.
These controls remain enforced at their original boundaries.

**Identity:** rate-limit/quarantine/audit use a nonreversible digest of the verified
issuer and subject. A JWT refresh does not reset identity by creating a new MCP session.
The raw access token, JWT claims and IdP subject are not placed in the MCP call ring.

**Legacy mode:** with `explorer.mcp.oauth.enabled=false` the existing static bearer
and the existing public console reads remain unchanged. The OAuth profile has
**no fallback** to `EXPLORER_MCP_AUTH_TOKEN` if a JWT is absent, invalid or expired.

## 3. RFC 9728 discovery and OAuth challenges

When OAuth is enabled, this application publishes:

```http
GET /.well-known/oauth-protected-resource/mcp
```

An example (replace these illustrative URLs with the deployed issuer and resource):

```json
{
  "resource": "https://explorer.example.org/mcp",
  "authorization_servers": ["https://idp.example.org/realms/operations"],
  "bearer_methods_supported": ["header"],
  "scopes_supported": ["mcp:read", "mcp:write", "mcp:admin"]
}
```

The metadata document is public, safe to cache briefly and **does not** use `Host` or
`X-Forwarded-Host` to construct URLs. A missing/invalid token receives HTTP 401 with
`WWW-Authenticate: Bearer`, a `resource_metadata` pointer and the required scope.
A correctly validated token lacking the relevant scope receives HTTP **403** with
`insufficient_scope`. These responses do not disclose the invalid JWT or the user's identity.

A native OAuth client should follow its authorization-server metadata and PKCE rules;
KafkaExplorer only validates access tokens. A browser operator may paste a short-lived
access token into **MCP → Supervision**: it remains in the tab's `sessionStorage`,
not in a query string or `localStorage`. Use the IdP's authorized flow to obtain it.

The implementation supports trusted RS256 JWTs only; it does **not** introspect opaque
tokens, support all JWS algorithms, manage downstream authorization, or replace an enterprise
API gateway. OAuth mode presently requires an explicitly configured JWKS URI and exact
public-root `/mcp` audience. Treat unknown or missing claims as authorization failures.

## 4. MCP Tasks: protocol compatibility decision

The MCP specification changed after the experimental 2025-11-25 Tasks introduction.
As of the **2026-07-28 MCP specification**, Tasks are a separate extension identified
by `io.modelcontextprotocol/tasks` (SEP-2663). It uses `tasks/get`,
`tasks/update` and `tasks/cancel`, with a server-directed task handle;
the earlier experimental `tasks/result` and `tasks/list` no longer describe
this extension.

**Status in this PR: not implemented.** KafkaExplorer currently hosts the legacy
Spring AI 2.0.2 MCP server. It does **not** advertise
`io.modelcontextprotocol/tasks`, does not return `CreateTaskResult` and
does not implement the extension's persistent task lifecycle. Existing long-running
operations remain bounded synchronous MCP tools. Exposing proprietary REST polling
as if it were the MCP Tasks extension would mislead clients and cannot be used.

For a future, separately validated Tasks implementation:

1. Introduce a compatible modern Java MCP transport and negotiate the Tasks
   extension through `server/discover`; do not advertise it before support exists.
2. Persist task identity, origin/subject, tool and arguments, status, idempotency key,
   timestamps, TTL and terminal result **durably** before returning a handle.
   In-memory maps are not sufficient in a replicated deployment.
3. Bind all `tasks/get`, `tasks/update`, `tasks/cancel` calls to the **same
   authenticated identity, resource and required scopes**. Do not allow task IDs
   to serve as bearer capabilities or provide a cross-tenant list.
4. Carry topic/group scopes, budgets, quota, cancellation, structured errors and
   audit correlation through asynchronous execution. Reverify authorization and
   revocation policy before processing mutation requests.
5. Test two clients, key rotations, refresh, replay, cancellation races, restarts,
   replicas, expiry and unauthorized cross-identity access against a real MCP wire client.
6. Only then publish a Tasks extension descriptor and enabled UX controls.

References:

- [MCP authorization / Protected Resource Metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
- [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728)
- [SEP-2663 Tasks extension](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/seps/2663-tasks-extension.md)
- [Tasks extension specification](https://modelcontextprotocol.github.io/ext-tasks/specification/2026-07-28/tasks.html)
- [Kex-anHarness governance](https://github.com/devdownin/Kex-anHarness/blob/main/docs/GOVERNED-GENERAL-AGENT.md)
- [KafkaExplorer MCP Skills guide](mcp-skills.md)

## 5. Security verification

```sh
./mvnw test -Dtest=McpOAuthAuthenticationTest,McpHttpAuthFilterTest,McpToolInterceptorTest
./mvnw -B verify -P build-frontend
```

An end-to-end deployment test must use a **real IdP-issued JWT** with the configured
audience, rotating JWKS and expired, wrong-issuer and wrong-scope tokens.
Without that environment, this PR's tests exercise fail-closed filtering and deterministic
claim/scope decisions; they do not certify integration with a specific IdP.

The default legacy MCP transport probe remains relevant and should continue to pass with
OAuth disabled. Do not run it against the OAuth endpoint using a static development token.
