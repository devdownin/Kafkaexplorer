# MCP P1 hardening

This PR follows the P0 MCP fixes and closes two P1 operational/security gaps found in the same review.

## 1. TLS is now required by default

The bearer credential added by the P0 fix must not be transported over cleartext HTTP. `McpHttpAuthFilter` therefore rejects non-secure requests with `426 Upgrade Required` when `explorer.mcp.require-tls=true` (the default).

The local Docker MCP overlay explicitly sets `EXPLORER_MCP_REQUIRE_TLS=false` because it uses the loopback development HTTP endpoint. Production deployments must remove that override and terminate TLS at the application or at a trusted ingress configured to preserve the request's secure state.

The TLS check happens before credential processing, so a cleartext request cannot probe the validity of the bearer token.

## 2. Rate-limiter state is bounded

The P0 authentication boundary makes caller identity explicit. A rate limiter backed by an unbounded `ConcurrentHashMap` would consequently become a memory-exhaustion vector as identities accumulate.

The limiter now uses the application's existing Caffeine dependency with:

- maximum 10,000 identities;
- 15-minute expiry after last access;
- the same per-identity token-bucket semantics and error contract.

This keeps the protection honest under many identities instead of replacing request amplification with unbounded JVM state.

## Tests

- HTTP MCP rejects cleartext transport by default.
- Explicit development mode accepts cleartext.
- TLS + valid bearer still succeeds.
- Rate-limiter identity cardinality stays at or below the hard bound.
- Explicit identity reset still works.
