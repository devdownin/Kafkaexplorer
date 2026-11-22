# KafkaExplorer project Agent Skills

Each directory in this tree contains a portable `SKILL.md` with a name and description,
a task procedure, safeguards and acceptance checks.

See [MCP and Skills guide](../../docs/mcp-skills.md) for supported MCP tools,
setup, testing and client installation. The `kafka-*` skills use the scoped,
read-only KafkaExplorer MCP server; `kafkaexplorer-*` skills operate on the
authorized repository and its CI. No skill makes cluster writes by default.

Use client-native discovery of `.agents/skills/` where supported; otherwise
copy/symlink the selected skill folder to the client's supported skill directory.

## Additional reviewed Kafka skills

- `kafka-security-review` — evidence-based topic policy and MCP deployment posture review; it is not an ACL audit or security certification.
- `kafka-schema-evolution` — compare observed schema samples to an operator-provided baseline; registry compatibility is unverified unless a real registry check is attached.

Both are read-only and distributed through the same explicit MCP Skills manifest.
