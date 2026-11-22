---
name: kafkaexplorer-ci-repair
description: Diagnose and repair KafkaExplorer pull-request CI failures with minimal targeted changes and verified build gates.
---

# KafkaExplorer CI repair

Use when a pull request fails build, lint, unit tests, docs checks, snapshots or packaging.
Repository: `devdownin/Kafkaexplorer`.

## Procedure

1. Read the failing check run, commit SHA, job and the **first causal error** (not only the final exit code). Distinguish source failures, infrastructure/network failures and a flaky external dependency.
2. Check whether the failing PR head is current. Work on a feature branch or isolated worktree; never overwrite a changed branch head without reconciling it.
3. Read `CLAUDE.md` and the relevant `docs/notes/ci-and-checks.md`, frontend or MCP notes. Reproduce only the affected test/class or workflow check. On a machine where Confluent repositories are unreachable, `verify-offline.sh` is indicative, not a substitute for real CI.
4. Make the smallest correct change. Do not paper over failure with skipped tests, a looser matcher, an unsupported dependency bump, a silent fallback or an altered GitHub required-check rule.
5. Run the affected verification: Java `./mvnw test -Dtest=ClassName`; frontend `npm run build`, `npm run lint`, `npm test`; docs `docs/check-*.py`; Docker `docker compose config`. Run `./mvnw verify` before declaring a complete local gate green when available.
6. Push a commit and inspect **checks belonging to the new head SHA**. Iterate only when new failures or unresolved checks justify it. Do not confuse a historical green commit with this PR.
7. Report file/line root cause, changed behavior, checks actually run, check URL and remaining blockers. Keep the PR draft when verification is incomplete.

## Rules / failure modes

- Preserve change scope; do not delete unrelated functionality or rewrite user work.
- Use tests that validate behavior, not tests copied from the implementation.
- Do not auto-merge, force-push, delete branches or change protected workflows without explicit authorization.
- A pending check is not green. A transient GitHub outage is not a software regression.
- If a failure is environmental, identify the dependency and suggest a bounded retry instead of modifying unrelated code.

## Acceptance

The targeted failure is addressed, relevant local checks pass if available, and the PR head's required checks are green before a merge recommendation.
