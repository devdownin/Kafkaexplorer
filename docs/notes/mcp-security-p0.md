# MCP P0 security fixes

This change closes the two P0 findings from the MCP audit.

## HTTP authentication

Spring AI's HTTP MCP transport does not authenticate requests by itself. When `explorer.mcp.enabled=true`, Kafka Explorer now installs a fail-closed bearer-token boundary:

- `POST/GET /mcp` requires `Authorization: Bearer <token>`.
- privileged console actions under `/api/mcp/**` (`try`, runtime toggles, quarantine, approval minting) require the same token.
- audit replay requires the token because it can expose historical call data.
- missing credentials return `401`.
- an enabled MCP server without `explorer.mcp.auth-token` returns `503` rather than silently opening the endpoint.
- the raw token is never used as an identity or written to the audit trail; the caller identity is a SHA-256 fingerprint.

The bundled MCP compose overlay requires `EXPLORER_MCP_AUTH_TOKEN` explicitly with Docker Compose's `:?` interpolation. There is no development default credential.

## Resume-token isolation

`McpTraceStore` now binds every paused trace to the authenticated caller fingerprint. A token created by principal A cannot be redeemed by principal B, even while it is still valid. Cross-principal and expired/unknown tokens deliberately produce the same result so the store does not become a token-existence oracle.

The token remains single-use and the fingerprint is the only caller data retained with the paused trace.

## Deployment

For the bundled stack:

```bash
export EXPLORER_MCP_AUTH_TOKEN="$(openssl rand -hex 32)"
docker compose -f docker-compose.yml -f compose/mcp.yml up -d
```

The smoke probe uses the same token through `MCP_AUTH_TOKEN` and now exercises the authenticated `/mcp` endpoint.
