# Enterprise MCP authentication — OAuth JWT resource server (Phase 1)

KafkaExplorer supports **two mutually exclusive HTTP MCP authentication modes**:

- Default: a deployment-specific static bearer token (`explorer.mcp.auth-token`).
- Opt-in: signed JWT access tokens validated against a **pinned HTTPS JWKS endpoint**, with issuer, audience, expiry and route-specific scopes. Static tokens are **not** accepted in OAuth mode.

This is an **OAuth 2.1 resource-server integration**, not an authorization server or a complete MCP authorization specification implementation. KafkaExplorer does not issue access tokens, implement authorization-code/PKCE, client registration, the identity provider's OAuth metadata discovery, or its authorization-code/PKCE flow. KafkaExplorer now publishes its own RFC 9728 protected-resource metadata for client discovery. Configure your trusted identity provider separately.

## Example configuration

```yaml
explorer:
  mcp:
    enabled: true
    readonly: true
    require-tls: true
    oauth:
      enabled: true
      issuer-uri: https://identity.example.com/realms/kafka
      jwk-set-uri: https://identity.example.com/realms/kafka/protocol/openid-connect/certs
      resource-uri: https://kafka.example.com/mcp
      audience: https://kafka.example.com/mcp
      read-scope: mcp.read
      admin-scope: mcp.admin
      audit-scope: mcp.audit
```

The issuer, JWKS endpoint, **canonical public MCP resource URI**, audience and scopes are **mandatory**. Both URLs must be HTTPS; no caller-provided discovery URL is followed. Signature validation uses the configured JWKS, and standard JWT validation checks expiry, not-before and issuer. The audience is checked independently. Deploy behind TLS and restrict egress to the trusted identity provider. Rotate signing keys at the provider.

## OAuth protected-resource discovery (RFC 9728)

In OAuth mode KafkaExplorer publishes unauthenticated metadata at both
`/.well-known/oauth-protected-resource` and the **path-specific** endpoint
`/.well-known/oauth-protected-resource/mcp` (or with the public resource's
configured path replacing `/mcp`). The response includes a canonical
`resource` identifier, `authorization_servers`, the configured scopes and
`bearer_methods_supported: ["header"]`.

The public `resource-uri` is **operator-configured**, must be HTTPS and end in
`/mcp`, and is **never derived from `Host` or proxy forwarding headers**.
OAuth 401 responses advertise the path-specific resource metadata URL in the
`WWW-Authenticate` header; a 403 also advertises the missing route-level scope.
Ensure that your reverse proxy serves the well-known endpoint at the advertised
public URL and forwards it to KafkaExplorer. If you use an external path prefix,
map the path-specific discovery URL explicitly. The IdP must independently
publish its authorization-server metadata and support the client's required
authorization flow (including PKCE); KafkaExplorer does **not** issue tokens.

## Permission matrix

| Route | Required scope | Notes |
| --- | --- | --- |
| `/mcp` (GET/POST) | `mcp.read` | MCP tool calls are multiplexed on this route; see limitation below |
| `/api/mcp/**` (non-GET) | `mcp.admin` | Includes Try It, approval and runtime switches |
| `GET /api/mcp/calls/replay` | `mcp.audit` | Historical audit data |
| Other `GET /api/mcp/**` | Application's existing access control | The browser console's ordinary reads remain unchanged |

### Per-tool scopes (phase 2)

You may require a **second, tool-specific OAuth permission** for any exposed MCP tool:

```yaml
explorer:
  mcp:
    oauth:
      tool-scopes:
        kex_list_topics: kafka.topics.list
        kex_describe_topic: kafka.topics.inspect
```

A token invoking `kex_describe_topic` must carry **both** `mcp.read` (the configurable
HTTP read scope) **and** `kafka.topics.inspect`. The tool-level permission is checked by
`McpToolInterceptor` **before execution and before consuming one-time approvals**.
A denied call is recorded and returns the KIP-1318 `POLICY_DENIED` code (`-32044`).
Only cryptographically verified scopes can satisfy these rules; an absent verified
OAuth context is denied even for local tool invocation. Unlisted tools retain the
normal `mcp.read` permission for backwards-compatible read-only operation. This is
an **additional restriction**, not a way to register or authorize write tools.

The `tool-scopes` keys are exact MCP tool names, not patterns; typos must be corrected
by an operator. An identity provider must be configured to issue the requested scopes.

The authenticated identity used for MCP guards is a **SHA-256 fingerprint of verified issuer and subject** (not the raw token). It remains stable across access-token rotation for the same principal. An invalid or missing token yields 401, insufficient scope yields 403, and non-TLS access yields 426 when required.

**Fail-closed write limitation:** OAuth mode requires `explorer.mcp.readonly=true`. MCP mutating tools share the `/mcp` endpoint and cannot be distinguished by HTTP route. Although the interceptor now enforces per-tool scopes for read tools, write-capable toolsets lack a reviewed authorization policy. Startup therefore refuses OAuth in write-enabled mode. Tool scopes do not grant write permissions.

**Trust boundary:** Token claims such as `scope` and `scp` are only consulted **after JWT signature, issuer, audience and time validation**. The endpoint never accepts arbitrary unsigned bearer payloads or a token as a URL query parameter.

## Next enterprise milestones

1. Extend existing per-tool JWT scopes to write-capable toolsets, with explicit deny-by-default mutating permissions and principal-scoped approvals. OAuth mode remains read-only until this is reviewed and implemented.
2. Validate the shipped RFC 9728 protected-resource metadata against real MCP clients and an IdP, including authorization-code/PKCE interoperability, issuer discovery and token audience configuration.
3. Introduce MCP Tasks only after protocol/SDK support and design for durable state, owner isolation, cancellation, expiry, quotas and audit. **No Tasks protocol is exposed by this phase.**

## Verification

Run `./mvnw test -Dtest=McpHttpAuthFilterTest,McpOAuthFilterTest,McpOAuthResourceMetadataTest,McpToolInterceptorTest`, then `./mvnw verify` and the authenticated `mcp-probe.sh` smoke check. Test with an actual IdP before enabling in production; unit tests use an injected decoder and do not prove JWKS network interoperability.
