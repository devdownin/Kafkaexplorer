# Getting help

Thanks for using Kafka SQL Explorer. Here is where each kind of question goes.

## Before opening anything

- **The in-app Help page** (`/help` in a running instance) is written as a course rather than a
  reference card: the mental model, a graded path of examples that each run against the bundled
  demo dataset, and — the part worth jumping to — a table mapping the **real error messages** to
  their cause and their fix.
- [`docs/QUERY-EXAMPLES.md`](docs/QUERY-EXAMPLES.md) — runnable queries.
- [`docs/FEATURES.md`](docs/FEATURES.md) — what each screen does.
- [`README.md`](README.md) — quick start, configuration and the environment variables.

## Where to ask

| Situation | Where |
| --- | --- |
| Something is broken or behaves wrongly | [Open an issue](https://github.com/devdownin/Kafkaexplorer/issues/new/choose) with the **Bug report** template |
| You want a feature or an improvement | [Open an issue](https://github.com/devdownin/Kafkaexplorer/issues/new/choose) with the **Feature request** template |
| A usage question | Open a blank issue — they are enabled for exactly this |
| **A security vulnerability** | **Never a public issue** — see [SECURITY.md](SECURITY.md) |

## What to include

The three things that decide whether a report can be acted on at all:

1. **Version and how you run it** — the release tag or commit, and whether it is the JAR, the
   Docker image, or one of the bundled compose stacks.
2. **What you did and what happened**, with the exact error text. Where a query is involved,
   include the SQL and which engine answered — `QueryResult.engine()` reports `FLINK` or
   `KAFKA_DIRECT`, and the two have genuinely different capabilities.
3. **The environment** — Kafka version and mode (KRaft or ZooKeeper), whether Schema Registry is
   in play, and the connection mode (`PLAIN`, `SSL`, `CONFLUENT_CLOUD`).

**Redact credentials before pasting.** Generated DDL embeds Kafka client properties, and that
includes SSL keystore passwords and the Confluent `sasl.jaas.config` secret. The application
masks them in its own responses; a copy-paste out of a terminal or a log file does not.

## What is supported

Only the latest release — see [SECURITY.md](SECURITY.md#supported-versions). If you are on an
older tag, please reproduce on the
[latest release](https://github.com/devdownin/Kafkaexplorer/releases/latest) first.

## Response expectations

This is an open-source project maintained by [Compagnons du dev](https://compagnonsdudev.com)
alongside other work. Issues are read, but there is no response-time commitment — except for
security reports, which are acknowledged within 48 hours.

Pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
