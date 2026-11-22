# MCP Resources, prompts and Agent Skills

**New to Skills & MCP?** Start with the [illustrated interactive guide](mcp-guide.html)
or its [text version with a Mermaid call graph](mcp-skills-services-guide.md).
They show how Kex-anHarness orchestrates KafkaExplorer capabilities, without claiming
that an architectural diagram is a recorded trace. Both guides now offer a nine-step
reference journey with explicit invocation/return phases, source-backed node details
and role lenses. Desktop readers can jump between steps, zoom and share deep links;
mobile/tablet readers get a complete vertical list. On GitHub Pages, the guide
requires the adjacent `mcp-guide.js` file; `docs/check-mcp-guide.py` checks its
HTML integration in PR preflight. Neither guide makes Kafka or MCP API calls.

KafkaExplorer exposes three distinct surfaces when `explorer.mcp.enabled=true`:

| Surface | How to use it | Operational contract |
| --- | --- | --- |
| MCP **Tools** | `tools/list`, `tools/call` | Authoritative bounded measurements and diagnostics, with scope, coverage, DLP and audit |
| MCP **Resources** | `resources/list`, `resources/templates/list`, `resources/read` | Read-only context snapshots. Every topic read reuses the existing scoped tool adapter and applies admission, DLP and output ceilings |
| MCP **Prompts** | `prompts/list`, `prompts/get` | Parameterized *instructions*, not tool execution |
| **Agent Skills** | `kex://skills`, `skill://{name}/SKILL.md` and the UI catalogue | Versioned skills with SHA-256 manifests; host-controlled installation and activation |

The MCP screen's Catalogue tab also shows **Resources and Prompts**, discovered from the
actual Spring AI specifications. The read-only `GET /api/mcp/surfaces` endpoint returns their
kind, name, URI and description; it returns an empty list when MCP is disabled.

The server is **off by default** and its read-only posture is the default when enabled.
See [MCP specification](../SPEC-MCP.md) and [security notes](notes/mcp-security-p0.md).
The probe `mcp-probe.sh` checks each MCP discovery/read path over real streamable HTTP.

## MCP resources

- `kex://contract` — static measurement/coverage contract.
- `kafka://topics` — up to 20 authorized, non-DLQ topics with coverage; topic inventory is a **bounded first page**, not the full cluster.
- `kafka://topics/{topic}` — one scoped topic's metadata and measured offsets; the URI is listed under resource **templates**, not the static resource list.

The two topic resources invoke the *same* `TopicMcpTools` methods used by agents. They are additionally gated on the source tool's registration/visibility, runtime disable/quarantine, rate limit, DLP and maximum output bytes. A topic outside the configured scope is refused before the broker is read. Do not use a resource as a way around `kex_list_topics` or `kex_describe_topic` being unavailable.

**Limits:** resource calls are not yet included in the `McpToolInterceptor` per-tool usage ring, and resources/templates do not supply paginated scans. For an auditable, potentially longer operation use an MCP Tool. The server returns resource text in the MCP JSON-RPC response; treat it as untrusted content.

## Skills distribution (Lot E)

The source of truth is `.agents/skills/<name>/SKILL.md`; the reviewed allowlist is
[`catalog.txt`](../.agents/skills/catalog.txt). Maven copies only the explicitly indexed documents
and the index into the executable JAR at `mcp-skills/`, without filtering or duplicating their
contents. The server validates each name and the required YAML frontmatter at startup.
It computes each file's SHA-256 digest over its exact UTF-8 bytes.

For current Java MCP clients, the supported discovery/read flow is:

1. `resources/read` on `kex://skills` returns a JSON list of skill entries containing
   `uri`, `frontmatter`, and a complete file manifest (`uri`, `digest`, `size`).
2. `resources/read` on e.g. `skill://kafka-incident-triage/SKILL.md` returns the
   original markdown bytes, with no rewriting that would invalidate the digest.
   The template is advertised by `resources/templates/list`.
3. The MCP screen has a separate **Agent Skills** catalogue with name, audience,
   description, URI, byte count, SHA-256 digest, lazy markdown preview and copy actions.
   Its read-only API is `GET /api/mcp/skills` and `GET /api/mcp/skills/{name}`.
   The REST catalogue is empty while MCP is disabled. Unknown names return HTTP 404.

**Interoperability limit:** These resources deliberately **do not** declare
`io.modelcontextprotocol/skills` in `server/discover` and do **not** implement
`skills/list` or `skills/get`. This is a compatible Resources-based distribution,
**not full SEP-2640 compliance**. The current Java MCP SDK does not yet offer native
extension negotiation/handlers. Do not claim Skills are auto-installed or auto-activated.
When native support is available, the existing manifest record is designed to back
`skills/list` / `skills/get` without changing the source files.
See [SEP-2640](https://modelcontextprotocol.io/seps/2640-skills-extension)
and the [Java SDK status](https://github.com/modelcontextprotocol/java-sdk/issues/1140).

The runtime source is an immutable reviewed classpath resource, never a path supplied by
a request, an upload, or a Kafka payload. Only `SKILL.md` is packaged in this initial
version; adding supporting files requires explicit manifest expansion, not silent
publication. Resource read is **not** skill activation: the agent host must review and
approve it in its own skill-loading workflow. The Kafka Skills still obey MCP Tool
permissions when they run; this catalogue grants no additional tool rights.

## MCP prompts

| Prompt | Inputs | Intended tool sequence |
| --- | --- | --- |
| `kex_incident_triage` | `key`, `window` | Evidence/trace → lag → DLQ → comparable verification |
| `kex_dlq_postmortem` | `queue`, `window` | DLQ diagnosis → bounded preview → trace |
| `kex_onboard_domain` | `prefix` | Topics → schema → data model → verified joins |
| `kex_schema_drift_check` | `topic`, `baselineSchema` | Schema inference → evidence-based diff |
| `kex_flow_health` | `prefix` | Bounded health/audit → grounded KPI |
| `kex_forecast_analysis` | `metricId` | Approved series → history → stored forecast → quality/thresholds |

Prompts do not start server-side jobs. In particular, `kex_forecast_analysis` does not auto-approve a series or activate alerts. Agents must check `tools/list`: forecast tools exist only when conditions are met.

## Project skills (packages)

The source skills live in [`.agents/skills/`](../.agents/skills/README.md). Each directory has a `SKILL.md` frontmatter (`name`, `description`), a procedure, safety gates and measurable acceptance checks.

- `kafka-incident-triage` — diagnosis and before/after coverage
- `kafka-forecast-analyst` — model-quality and threshold forecasting
- `kafka-data-architect` — schema inference, relation evidence and Flink joins
- `kafka-security-review` — bounded topic policy and MCP posture review; no ACL certification
- `kafka-schema-evolution` — baseline-based drift review; no implied registry compatibility
- `kafkaexplorer-ci-repair` — PR build failure analysis and minimal repair
- `kafkaexplorer-release` — changelog, documentation and publication readiness

Clients that discover project-local Agent Skills under `.agents/skills/` can load them directly. For clients requiring a different path, copy or symlink a skill directory to that client's supported skill root. Nothing is installed into the user's global environment by this repository. The two engineering skills require an authorized repository connection or checkout; the Kafka skills require the explicitly configured MCP endpoint.

## Verification

1. Run `./mvnw test -Dtest=McpServerBootTest,KafkaMcpResourcesTest,McpSkillCatalogTest`.
2. Run `sh mcp-probe.test.sh` to check the probe's success and failure cases.
3. Start the [MCP Compose overlay](../compose/mcp.yml) with a non-default bearer token and probe it:

   ```sh
   MCP_BASE_URL=http://localhost:8080 MCP_AUTH_TOKEN="$EXPLORER_MCP_AUTH_TOKEN" sh mcp-probe.sh
   ```

4. Run `./mvnw verify` for the full backend/frontend gate and use the pull request's checks as the authoritative environment.

When the stack uses the Compose demo's loopback-only HTTP mode, leave it inaccessible from untrusted networks. For shared deployments use TLS; never publish the development token.
