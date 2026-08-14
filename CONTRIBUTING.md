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

- JDK 21 (Flink 2.x supports Java 17 and 21, **not** 25)
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
