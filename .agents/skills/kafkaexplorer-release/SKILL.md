---
name: kafkaexplorer-release
description: Prepare KafkaExplorer releases with changelog validation, CI checks, documentation screenshots and bounded publishing verification.
---

# KafkaExplorer release

Use when preparing a release PR, checking readiness, or diagnosing publication on GitHub Pages, GHCR and Docker Hub.

## Procedure

1. Read `CLAUDE.md`, `CHANGELOG.md` and `docs/notes/ci-and-checks.md`. Determine current `main`, intended release version and existing tags; do not guess from the latest PR title.
2. Validate changelog invariants: exactly one `## [Unreleased]` section, every release link `[x.y.z]:` has exactly one `## [x.y.z]` heading, and existing Unreleased entries are preserved when adding a release section.
3. Check version references across Maven, UI, documentation and image tags. Avoid fixed-string replacements that require `[Unreleased]` to contain `Nothing yet.`.
4. Run applicable docs and cross-platform checks; verify that capture jobs regenerate both light and `*-dark.png` screenshots. Do not claim dark captures are fresh based only on light captures.
5. Verify `./mvnw verify`, Docker Compose configuration and image size safeguards when the environment supports them. Report limitations rather than asserting CI success without evidence.
6. Prepare the release PR with a checklist. After approval and merge, inspect the exact GitHub Pages, GHCR and Docker Hub publishing runs; retry only an idempotent failed stage when supported by the workflow.
7. Report artifact names/tags/digests, documentation URLs, workflow conclusions and any registry size error. Never report publication merely because a build succeeded.

## Safety / failure modes

- Never create a version tag, release, publish image, merge PR or delete a branch without explicit authorization.
- Do not overwrite manual changelog notes or reorder existing releases without checking their links.
- Handle secrets only through authorized GitHub Actions or configuration; never log or commit them.
- Do not convert an intermittent deployment failure into a false green by suppressing it.

## Acceptance

All documented version references agree, changelog sections and links match, both screenshot themes are checked, and the release status is based on verified workflow outcomes.
