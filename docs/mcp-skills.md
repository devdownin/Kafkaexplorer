# MCP Resources, prompts and Agent Skills

KafkaExplorer exposes three distinct surfaces when `explorer.mcp.enabled=true`:

| Surface | How to use it | Operational contract |
| --- | --- | --- |
| MCP **Tools** | `tools/list`, `tools/call` | Authoritative bounded measurements and diagnostics, with scope, coverage, DLP and audit |
| MCP **Resources** | `resources/list`, `resources/templates/list`, `resources/read` | Read-only context snapshots. Every topic read reuses the existing scoped tool adapter and applies admission, DLP and output ceilings |
| MCP **Prompts** | `prompts/list`, `prompts/get` | Parameterized *instructions*, not tool execution |
| **Agent Skills** | Install a `SKILL.md` into a compatible agent | End-to-end process and acceptance criteria; orchestrates only exposed MCP tools |

The server is **off by default** and its read-only posture is the default when enabled.
See [MCP specification](../SPEC-MCP.md) and [security notes](notes/mcp-security-p0.md).
The probe `mcp-probe.sh` checks each MCP discovery/read path over real streamable HTTP.

## MCP resources

- `kex://contract` — static measurement/coverage contract.
- `kafka://topics` — up to 20 authorized, non-DLQ topics with coverage; topic inventory is a **bounded first page**, not the full cluster.
- `kafka://topics/{topic}` — one scoped topic's metadata and measured offsets; the URI is listed under resource **templates**, not the static resource list.

The two topic resources invoke the *same* `TopicMcpTools` methods used by agents. They are additionally gated on the source tool's registration/visibility, runtime disable/quarantine, rate limit, DLP and maximum output bytes. A topic outside the configured scope is refused before the broker is read. Do not use a resource as a way around `kex_list_topics` or `kex_describe_topic` being unavailable.

**Limits:** resource calls are not yet included in the `McpToolInterceptor` per-tool usage ring, and resources/templates do not supply paginated scans. For an auditable, potentially longer operation use an MCP Tool. The server returns resource text in the MCP JSON-RPC response; treat it as untrusted content.

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
- `kafkaexplorer-ci-repair` — PR build failure analysis and minimal repair
- `kafkaexplorer-release` — changelog, documentation and publication readiness

Clients that discover project-local Agent Skills under `.agents/skills/` can load them directly. For clients requiring a different path, copy or symlink a skill directory to that client's supported skill root. Nothing is installed into the user's global environment by this repository. The two engineering skills require an authorized repository connection or checkout; the Kafka skills require the explicitly configured MCP endpoint.

## Verification

1. Run `./mvnw test -Dtest=McpServerBootTest,KafkaMcpResourcesTest`.
2. Run `sh mcp-probe.test.sh` to check the probe's success and failure cases.
3. Start the [MCP Compose overlay](../compose/mcp.yml) with a non-default bearer token and probe it:

   ```sh
   MCP_BASE_URL=http://localhost:8080 MCP_AUTH_TOKEN="$EXPLORER_MCP_AUTH_TOKEN" sh mcp-probe.sh
   ```

4. Run `./mvnw verify` for the full backend/frontend gate and use the pull request's checks as the authoritative environment.

When the stack uses the Compose demo's loopback-only HTTP mode, leave it inaccessible from untrusted networks. For shared deployments use TLS; never publish the development token.
