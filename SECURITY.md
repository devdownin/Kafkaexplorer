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

## Deployment note — this app has no authentication

This is a property of the product rather than a vulnerability, and it is worth stating
plainly because it decides how the app must be deployed:

- Kafka SQL Explorer ships with **no authentication and no authorization**.
- `POST /api/config` can **repoint the application at another Kafka cluster at runtime**,
  and it accepts credentials for that cluster.
- Every bundled compose stack therefore publishes its ports on `${BIND_ADDR:-127.0.0.1}`,
  the loopback interface, rather than Docker's usual `0.0.0.0`.

Run it on a trusted network, and put an authenticating reverse proxy in front of it before
exposing it any further. Reports that amount to "an unauthenticated user can use the
application" describe the documented design; reports that a deployed instance leaks
credentials, escalates beyond the configured cluster, or bypasses the masking applied to
generated DDL are very much in scope.

Thank you for helping us keep Kafka SQL Explorer secure!
