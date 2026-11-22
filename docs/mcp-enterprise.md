# Enterprise MCP authentication and task lifecycle

## OAuth 2.1 resource-server integration (opt-in)

KafkaExplorer supports **JWT access-token validation** for MCP clients in addition to its existing
single shared bearer-token mode. This is a **resource-server** integration, not an authorization
server or a complete OAuth 2.1 client onboarding implementation. An external identity provider
is responsible for authorization-code + PKCE flows, client registration, consent and token issuance.

Set the following properties (or their Spring environment-variable equivalents):

```yaml
explorer:
  mcp:
    enabled: true
    require-tls: true
    auth-mode: oauth2
    oauth2:
      issuer-uri: https://identity.example.com/realms/kafka
      jwk-set-uri: https://identity.example.com/realms/kafka/protocol/openid-connect/certs
      audience: kafkaexplorer-mcp
```

Do not use these placeholder URLs in a deployment. The JWT decoder verifies signatures
using the configured JWK set and validates the expected issuer, timestamps and audience.
Requests are denied when validation fails. OAuth mode **never falls back** to the shared
`explorer.mcp.auth-token`. A missing issuer, JWK URL or audience prevents the MCP
authentication filter from being configured.

The external identity provider must issue the following access-token scopes:

| Scope | Permitted HTTP surface |
| --- | --- |
| `mcp:invoke` | `/mcp` protocol transport |
| `mcp:admin` | Privileged `/api/mcp/**` operations (non-GET and audit replay) |

The scopes are transport-level admission gates, **not per-tool authorization**.
Tool exposure still obeys the server's read-only registration, allow/deny lists, topic
and group scopes, runtime quarantine, rate limits, approvals and DLP. An OAuth
`mcp:invoke` token does not grant an otherwise hidden tool. For distinct tool
permissions per OAuth principal, a dedicated policy evaluator at tool dispatch is
still required; do not advertise this implementation as fine-grained RBAC.

OAuth caller identity is a SHA-256 pseudonym of issuer and subject, not the raw JWT,
subject or email. Approvals, rate limits and trace isolation continue to use the
authenticated caller identity.

### Production boundaries

- Enforce TLS at the public edge and configure trusted proxy headers correctly.
  The application's `require-tls` check examines the servlet request's secure flag.
- Restrict the JWK URL to a trusted HTTPS identity-provider endpoint; avoid internal
  metadata endpoints or user-controlled JWK URLs.
- Keep console read endpoints behind the application's own access control; the
  MCP bearer filter intentionally does not authenticate ordinary console GET requests.
- The server does not implement OAuth authorization-server discovery metadata,
  dynamic client registration, token exchange or opaque-token introspection.
- Existing `auth-mode: bearer` is the default for backward compatibility.

## MCP Tasks — planned protocol work, not yet available

Long-running MCP Tasks require SDK support for task creation, polling, cancellation,
expiry and result retrieval with a stable protocol-level contract. KafkaExplorer
**does not currently expose** `tasks/create`, `tasks/get`, `tasks/result`
or `tasks/cancel`. The existing forecasting and audit operations are not
automatically converted into protocol Tasks.

Before enabling Tasks, implement a bounded persistent task store with:

1. Immutable owner identity, authorized tool, scoped arguments, creation time and TTL.
2. Explicit `queued → running → succeeded|failed|cancelled|expired` transitions.
3. Per-owner quotas, admission budgets and server-enforced cancellation.
4. Authenticated polling and result retrieval, including owner isolation and DLP.
5. Restart behavior, audit correlation and integration tests against an MCP client
   that actually supports the negotiated Tasks capability.

Do not invent `tasks/*` JSON-RPC handlers outside the SDK or claim support in
`initialize` before the transport and interoperability tests are green.
