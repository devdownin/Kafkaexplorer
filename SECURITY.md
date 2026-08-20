# Security Policy

## Supported Versions

Kafka SQL Explorer is developed on `main` and released as `vMAJOR.MINOR.PATCH` tags.
Security fixes land on `main` and ship in the next release; there are no long-lived
maintenance branches, so **only the latest release is supported**.

| Version                  | Supported          |
| ------------------------ | ------------------ |
| Latest `v1.x` release    | :white_check_mark: |
| Any earlier release      | :x:                |

If you are running an older tag, upgrade to the [latest release](https://github.com/devdownin/Kafkaexplorer/releases/latest)
before reporting a vulnerability — it may already be fixed.

## Reporting a Vulnerability

We take the security of Kafka SQL Explorer seriously. If you believe you have found a
security vulnerability, please report it as described below.

**Please do not report security vulnerabilities through public GitHub issues.**

Either of these works:

- **Preferred** — [open a private security advisory](https://github.com/devdownin/Kafkaexplorer/security/advisories/new)
  through GitHub, which keeps the report and the discussion private until a fix is published.
- Or email **contact@compagnonsdudev.com**.

### What to include in your report

Please include as much information as possible to help us reproduce and understand the
vulnerability:

- The version (or commit) of Kafka SQL Explorer you are using, and how you run it
  (JAR, Docker image, one of the bundled compose stacks).
- A description of the vulnerability and its potential impact.
- Steps to reproduce it.
- Any proof-of-concept, logs or configuration that help — with credentials redacted.

### What happens next

After you submit your report, we will:

- Acknowledge receipt within 48 hours.
- Investigate and determine severity.
- Work on a fix and release it in a timely manner.
- Keep you informed of our progress, and credit you in the advisory unless you prefer otherwise.

## Verifying a release

Every release is signed. Nothing here requires a key from us, or trusting a key distribution:
the signatures are keyless, made through [Sigstore](https://www.sigstore.dev/) against a
short-lived identity that names this repository, this workflow and the commit it ran on, and
recorded in a public transparency log.

**The JAR** — checks that it was built by this repository's release workflow, not merely that it
downloaded intact:

```bash
gh attestation verify kafka-sql-explorer-<version>.jar --repo devdownin/Kafkaexplorer
```

`SHA256SUMS.txt` is still attached, and still worth checking, but note what it does *not* do: it
sits on the same Release page as the file it describes, so anyone able to replace one can replace
both. It answers "did this arrive intact", never "did this come from here".

**The container image** — the same question, for the artefact most people actually run:

```bash
gh attestation verify oci://ghcr.io/devdownin/kafkaexplorer:<tag> --repo devdownin/Kafkaexplorer
```

The image additionally carries a full SLSA provenance and an SBOM, pushed as part of its index:

```bash
docker buildx imagetools inspect ghcr.io/devdownin/kafkaexplorer:<tag>
```

**Pin by digest in production.** A tag is mutable — that we do not move ours is a promise about
our behaviour, not a property of the tag. The digest published in each Release's notes is a
property:

```bash
docker run ghcr.io/devdownin/kafkaexplorer@sha256:<digest>
```

## Deployment note — this app has no authentication

This is a property of the product rather than a vulnerability, and it is worth stating
plainly because it decides how the app must be deployed:

- Kafka SQL Explorer ships with **no authentication and no authorization**.
- `POST /api/config` can **repoint the application at another Kafka cluster at runtime**,
  and it accepts credentials for that cluster.
- Those settings are **kept across restarts**, in `data/settings.json`, and that includes the
  credentials: the SSL keystore and truststore passwords, the Confluent Cloud secret and the
  LLM API key. The file is created readable by its owner alone (mode `0600`) and the values
  are never returned by the API — `GET /api/config` reports whether a key is configured, not
  what it is. Set `explorer.settings-store-secrets=false` to keep them out of the file
  entirely, at the cost of re-entering them after each restart; the fields left out are then
  named in the save's answer and in the startup log rather than silently dropped. Set
  `explorer.settings-persistence=false` to store nothing at all.
- A hand-written `CREATE TABLE` is kept the same way, in `data/flink-tables.json`, and Flink
  DDL embeds Kafka client properties — so that file can hold credentials too, and is written
  with the same permissions. This is the store's own copy: everything that leaves through the
  API still passes through `DdlGeneratorService.maskSensitiveProperties()`.
- Every bundled compose stack therefore publishes its ports on `${BIND_ADDR:-127.0.0.1}`,
  the loopback interface, rather than Docker's usual `0.0.0.0`.

Run it on a trusted network, and put an authenticating reverse proxy in front of it before
exposing it any further. Reports that amount to "an unauthenticated user can use the
application" describe the documented design; reports that a deployed instance leaks
credentials, escalates beyond the configured cluster, or bypasses the masking applied to
generated DDL are very much in scope.

Thank you for helping us keep Kafka SQL Explorer secure!
