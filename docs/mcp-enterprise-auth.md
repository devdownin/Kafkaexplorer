# Enterprise MCP authentication — OAuth JWT resource server (Phase 1)

KafkaExplorer supports **two mutually exclusive HTTP MCP authentication modes**:

- Default: a deployment-specific static bearer token (`explorer.mcp.auth-token`).
- Opt-in: signed JWT access tokens validated against a **pinned HTTPS JWKS endpoint**, with issuer, audience, expiry and route-specific scopes. Static tokens are **not** accepted in OAuth mode.

This is an **OAuth 2.1 resource-server integration**, not an authorization server or a complete MCP authorization specification implementation. KafkaExplorer does not issue access tokens, implement authorization-code/PKCE, client registration, OAuth metadata discovery, or RFC 9728 protected-resource metadata. Configure your trusted identity provider separately.

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
      audience: kafkaexplorer-mcp
      read-scope: mcp.read
      admin-scope: mcp.admin
      audit-scope: mcp.audit
```

The issuer, JWKS endpoint, audience and scopes are **mandatory**. Both URLs must be HTTPS; no caller-provided discovery URL is followed. Signature validation uses the configured JWKS, and standard JWT validation checks expiry, not-before and issuer. The audience is checked independently. Deploy behind TLS and restrict egress to the trusted identity provider. Rotate signing keys at the provider.

## Permission matrix

| Route | Required scope | Notes |
| --- | --- | --- |
| `/mcp` (GET/POST) | `mcp.read` | MCP tool calls are multiplexed on this route; see limitation below |
| `/api/mcp/**` (non-GET) | `mcp.admin` | Includes Try It, approval and runtime switches |
| `GET /api/mcp/calls/replay` | `mcp.audit` | Historical audit data |
| Other `GET /api/mcp/**` | Application's existing access control | The browser console's ordinary reads remain unchanged |

The authenticated identity used for MCP guards is a **SHA-256 fingerprint of verified issuer and subject** (not the raw token). It remains stable across access-token rotation for the same principal. An invalid or missing token yields 401, insufficient scope yields 403, and non-TLS access yields 426 when required.

**Fail-closed write limitation:** OAuth mode requires `explorer.mcp.readonly=true`. MCP mutating tools share the `/mcp` endpoint and cannot be distinguished by HTTP route. Until the MCP tool interceptor enforces principal-to-tool RBAC, allowing OAuth in write-enabled mode would let `mcp.read` invoke exposed write tools. Startup therefore refuses this combination. No OAuth scopes are claimed to grant per-tool permissions.

**Trust boundary:** Token claims such as `scope` and `scp` are only consulted **after JWT signature, issuer, audience and time validation**. The endpoint never accepts arbitrary unsigned bearer payloads or a token as a URL query parameter.

## Next enterprise milestones

1. Add tool-level RBAC to the MCP interceptor with explicit deny-by-default mutating permissions and principal-scoped approvals.
2. Implement OAuth 2.1/MCP protected-resource metadata and complete client discovery/PKCE interoperability, with IdP integration tests.
3. Introduce MCP Tasks only after protocol/SDK support and design for durable state, owner isolation, cancellation, expiry, quotas and audit. **No Tasks protocol is exposed by this phase.**

## Verification

Run `./mvnw test -Dtest=McpHttpAuthFilterTest,McpOAuthFilterTest`, then `./mvnw verify` and the authenticated `mcp-probe.sh` smoke check. Test with an actual IdP before enabling in production; unit tests use an injected decoder and do not prove JWKS network interoperability.
