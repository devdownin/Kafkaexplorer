# Agent development entry point

This repository's development source of truth is [CLAUDE.md](CLAUDE.md) and the
area-specific implementation notes linked from it. Read them before changing code,
regardless of the agent client in use.

Project Agent Skills live at [`.agents/skills/`](.agents/skills/README.md) and are opt-in
operational playbooks, not instructions to run automatically. See
[docs/mcp-skills.md](docs/mcp-skills.md) for their prerequisites and the MCP
resources/prompts catalog.

Default to read-only cluster access. Do not bypass KafkaExplorer MCP scopes,
coverage reporting, tool allow/deny rules, DLP or approval gates.
Before claiming a PR is green, check the required GitHub Actions runs for its
current head SHA. Do not merge or publish without explicit user authorization.
