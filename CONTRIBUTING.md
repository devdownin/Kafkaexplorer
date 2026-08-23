# Contributing to Kafka SQL Explorer

Thank you for your interest in Kafka SQL Explorer! We welcome contributions from the community.

By participating in this project you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## How to Contribute

### Reporting Bugs

- Use the [issue tracker](https://github.com/devdownin/Kafkaexplorer/issues), and the **Bug report** template.
- Provide a clear and descriptive title.
- Include steps to reproduce, expected behaviour and actual behaviour.
- Say which version you are running (release tag, or commit) and how — JAR, Docker image, or one of the bundled compose stacks.
- Attach screenshots or logs where they help. **Redact credentials**: generated DDL and connection settings can contain SSL passwords and Confluent secrets.

Found a security vulnerability? Do **not** open a public issue — see [SECURITY.md](SECURITY.md).

### Suggesting Enhancements

- Open an issue with the **Feature request** template.
- Explain why the enhancement would be useful to other users, not only to your setup.

### Pull Requests

1. Fork the repository and create a branch (`git checkout -b feature/my-new-feature`).
2. Make your changes, with tests.
3. **Run the full gate locally — `mvn verify`** (see below). This is what CI runs; `mvn test` alone is not enough.
4. Keep the commit history readable; explain *why* in the commit body, not only *what*.
5. Open a pull request and fill in the template.

New source files carry the licence header — see [Licence headers](#licence-headers).

## Development Setup

### Prerequisites

- JDK 25 (`java.version` in `pom.xml`; the enforcer plugin refuses anything older)
- Docker and Docker Compose
- Maven — or just use the checked-in wrapper, `./mvnw`

### Running the project locally

**1. Typical local dev workflow (hot reload on both sides)**

```bash
docker compose -f docker-compose-kafka4.yml up kafka   # broker only
./mvnw spring-boot:run                                 # backend on :8080
cd src/main/webapp && npm run dev                      # frontend on :5173
```

**2. Full dev stack in containers**

```bash
docker compose -f docker-compose-dev.yml up --build
```

- Frontend UI: `http://localhost:5173`
- Backend API: `http://localhost:8080`

**3. No local toolchain?** `docker-compose-build.yml` runs the same commands in containers — always with `run --rm`, these are one-shot services:

```bash
docker compose -f docker-compose-build.yml run --rm verify    # the full gate
docker compose -f docker-compose-build.yml run --rm frontend  # ESLint + Vitest only
```

**4. Production image**

```bash
docker build -t kafka-sql-explorer:latest .
```

## Running the checks

**`mvn verify` is the only command that runs everything.** ESLint and Vitest are bound to the
`verify` phase of the `build-frontend` profile, deliberately not to `test` — that keeps
`mvn test -Dtest=SomeClass` a fast Java-only loop.

```bash
mvn verify                        # the complete gate: Java tests + ESLint + Vitest. What CI runs.
mvn test                          # Java tests only — fast backend loop, no npm involved
mvn test -Dtest=AuditServiceTest  # a single test class
```

Frontend checks on their own:

```bash
cd src/main/webapp
npm run lint    # ESLint, flat config, --max-warnings 0
npm test        # Vitest; npm run test:watch to iterate
```

### The checks `mvn verify` does not run

CI runs a family of documentation and compose checks that the Maven build knows nothing about,
so a change to a `.md`, to a compose file or to `api/types.ts` can be green locally and red on
the pull request. They need no network, no daemon and no build — running them takes seconds.
Run the lot the way CI does, so a check added later is picked up without editing anything:

```bash
for check in docs/check-*.py; do python3 "$check"; done
```

Or one at a time, which is what you want while fixing one:

```bash
python3 docs/check-links.py        # every repository link in the docs resolves
python3 docs/check-doc-paths.py    # every path CLAUDE.md and this file name in prose exists
python3 docs/check-config-table.py # documented variables and defaults match the code
python3 docs/check-api-types.py    # api/types.ts still matches the Java records it mirrors
python3 docs/check-image-pins.py   # compose images: pinned, consistent, and the current release
python3 docs/check-compose.py      # compose vars documented in .env.example; prompt budget fits the window
```

```bash
# Every stack and every overlay layered onto its base — an overlay alone is invalid by design.
docker compose -f docker-compose.yml config -q
docker compose -f docker-compose.yml -f docker-compose.limits.yml config -q
```

The combinations are **generated** in the `compose-lint` job of `.github/workflows/ci.yml`,
from one declaration naming every base and the overlays it accepts. That job also **fails on a
compose file named in neither** — add a stack, add it to the declaration.

`check-image-pins.py` needs tags (`git fetch --tags`) and fails rather than skipping without
them. Run this way it checks that nothing floats, that the llama.cpp CPU and CUDA images name
the same build, and that the Explorer pin does not name a release nobody has published yet.
Whether that pin has gone *stale* is a question for the registry, not for the git tags — a tag
exists the moment it is pushed and the image only when the release workflow finishes — so it is
`--published`, which asks Docker Hub and runs in the `spectra-hub-stack` job, where the network
is already a dependency.

### When `packages.confluent.io` is unreachable

`io.confluent:kafka-avro-serializer` and `io.confluent:kafka-schema-registry-client` are published
only on `packages.confluent.io`, never on Maven Central. Behind a proxy that blocks that host, Maven
cannot even resolve the dependency graph and every goal fails before compiling anything.

`./verify-offline.sh` gives back a compile-and-test loop in that situation. It accepts extra
JUnit ConsoleLauncher arguments:

```bash
./verify-offline.sh "--include-classname=.*LineageServiceTest"
```

Use `--include-classname` (a filter), **not** `--select-class` — the script always passes
`--scan-classpath`, and the JUnit launcher refuses both at once. Note that Avro and Schema Registry
paths run against stubs there, so those results are indicative only. **CI is the authority**: it
builds against the real Confluent jars.

## Coding standards

- Follow standard Java conventions; match the style of the code around you.
- Use meaningful names, and comment *why* rather than *what*.
- Every new feature needs tests. Backend: JUnit 5 + Mockito, no broker required — a test that
  genuinely needs one starts it via Testcontainers (see `KafkaClusterIntegrationTest`), never a
  workflow-level service, so it also works on a developer machine.
- Frontend: keep pure logic in its own module with unit tests, and build UI from the design-system
  components in `src/main/webapp/src/components/ui/`.
- ESLint runs with `--max-warnings 0` and `--report-unused-disable-directives`. Suppress a rule at
  its own call site with a reason, never repo-wide.

More architectural context — the load-bearing decisions and the traps they came from — lives in
[`CLAUDE.md`](CLAUDE.md).

### Licence headers

Every Java, TypeScript and TSX source file starts with:

```java
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
```

## Licence

By contributing to Kafka SQL Explorer, you agree that your contributions will be licensed under
the GNU Affero General Public License, Version 3.0.

## Verifying the OpenRouter contract against the live API

`docs/check-*.py` are discovered and run by CI, need no network, and gate every pull request.
`docs/verify-openrouter-contract.py` is deliberately **not** one of them: it needs the network and
an API key, so CI cannot run it, and it is named differently so the discovery loop does not pick it
up.

Run it by hand when you touch anything the Explorer sends to OpenRouter's catalogue:

```bash
OPENROUTER_API_KEY=sk-or-v1-… python3 docs/verify-openrouter-contract.py
```

It exists because the filter and sort parameter names were taken from `@openrouter/sdk`'s published
schemas rather than from an observed response — `openrouter.ai` is unreachable from some build
environments. The parsing has unit tests; the *request* had nothing, and a parameter name the
gateway does not recognise is ignored rather than refused, which means the model shortlist would
quietly stop being a shortlist. The script's first assertion is precisely that the filters narrow
the catalogue.

It exits `0` when the contract holds, `1` on a real disagreement, `2` with no key, and `3` when the
API could not be reached at all — a network failure is reported as a network failure, not as a
contract violation.
