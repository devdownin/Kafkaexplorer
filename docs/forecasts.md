# Guided forecasts

The Metrics page's **Metrics Forecast** panel offers preparation diagnostics, candidate metrics,
a configuration assistant, history progress and a forecast summary. The pilot and read-only MCP
tools are enabled by default, with an empty approved catalogue. No forecast runs until sources,
durable history and TimesFM are explicitly configured.

## Companion agent: Kex-anHarness

[Kex-anHarness](https://github.com/devdownin/Kex-anHarness), formerly Kex Agent AI, reads
KafkaExplorer’s forecast tools through MCP. See its [forecast guide](https://github.com/devdownin/Kex-anHarness/blob/main/docs/TIMESFM.md)
for the agent’s Forecasts page and MCP diagnostics. The GitHub repository URL and published
`kex-agent-ai` image names retain their existing identifiers.

## Start the complete local stack

From a repository checkout with Docker Compose v2 and OpenSSL installed:

```sh
bin/forecast-stack.sh
```

This builds KafkaExplorer and the pinned CPU TimesFM adapter, starts the bundled Kafka broker and
PostgreSQL, and generates random database, inference and MCP credentials in a private local file.
Existing credentials and configuration are retained on subsequent runs. After the stack starts,
the script restarts KafkaExplorer so changes to the mounted approval file take effect. The default TimesFM
allocation is 4 CPUs and 8 GiB RAM; the first startup downloads the pinned checkpoint and verifies
its SHA-256. The one-shot prefetch service has outbound access; the model worker runs on an
internal network. PostgreSQL and TimesFM have no host ports. Named volumes retain Kafka data,
PostgreSQL observations/results and model weights. `docker compose down -v` removes those volumes.

The web UI is at http://localhost:8080 by default. This is a local development stack: MCP uses an
explicit cleartext exception and a random bearer credential; review TLS and existing topic/group
scope policy for shared deployments. The local forecast environment allowlist starts at `local`.

```sh
# Generate configuration directories and secrets without starting containers:
bin/forecast-stack.sh --prepare-only
# Inspect health and logs without displaying interpolated secrets:
docker compose --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml ps
docker compose --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml logs timesfm forecast-postgres
```

The tracked `.env.example` documents overlay settings. The bootstrap script uses the generated
private environment file rather than overwriting an existing repository environment file.
History stays disabled until the operator approves and exports metric enrollment. Database and
model readiness alone do not make a metric forecastable.

## Configure and approve

1. Open **Metrics Forecast** and read the preparation checklist. **Test PostgreSQL and TimesFM**
   runs bounded connection/readiness probes, cached for 30 seconds. It does not create database
   tables or run inference. Disabled dependencies are reported as configuration requirements.
   MCP tool availability comes from the server's actual published catalogue; agent connectivity
   must be verified from the agent itself.
2. Choose **Configure a forecast**, or **Prepare a forecast** on a metric card: both open the same
   assistant on the Metrics page. The candidate catalogue shows metadata eligibility and history
   enrollment separately. Unknown units, raw SQL scope, labelled values, non-scalar types, managed
   jobs and collection errors block the assistant. Correct the metric before proceeding. SQL,
   DDL and credentials are not returned by this catalogue. The first 100 metrics are listed in
   deterministic ID order; truncation is explicit.
3. Declare environment, sampling interval (30 s to 15 min), horizon — shown as a duration — and
   **all** source topics/groups, edited as removable chips (topics are suggested from the cluster
   catalogue; a name outside it is still accepted). The history cluster/collector identities are prefilled from the
   running collector and folded under **Advanced**; they open by themselves when one is invalid. Structured suggestions are not a SQL dependency analysis. Unit and series
   identity must match the actual collector; units are read-only and stale metric definitions are
   rejected. Counters use rates per second; optional thresholds use forecast output units.
4. Review and tick the source attestation, then choose **Validate and export**; the attestation is
   the confirmation, with no second dialog. Server validation enforces
   source completeness for known template resources, bounded horizons/cadences, history retention,
   configured series budgets and existing collection identities. The YAML preserves existing
   runtime-approved series, enrollment, interval, retention and series budget. It includes no
   database credentials or inference token. Export changes no running configuration.
5. The export lists the next steps in order and offers **Copy configuration** beside the download.
   Save the file as `.forecast-stack/config/forecasts.yml`, review its merge with your
   deployment, then rerun `bin/forecast-stack.sh`. For an existing non-Docker deployment, merge the
   file into the application's external Spring configuration and restart. Configure PostgreSQL
   credentials and inference separately using the [history runbook](notes/timesfm-history.md).
   Review MCP environment, topic and group scope policies; the export does not broaden them.

## Wait for a usable result

The selected approved series shows observed points against the required 512, missing/imputed
points, preparation state and rejection reason. With a 60-second cadence the context spans
512 minutes; irregular, stale, scope-changed, invalid or reset data can keep it inadmissible.
Database failure shows unknown progress rather than a zero-valued measurement. The next cycle is
a scheduler estimate, not a promised first-result date: a rotating bounded queue and shared lease
can defer a series. Progress reads are restricted to declared series and never request inference.

The summary leads with trend, horizon, predicted threshold breach and realised quality. Trend is
last predicted Q50 minus the last measured value. Breaches use an explicit operator threshold and
a conservative Q10/Q90 bound; unavailable, unconfigured and unevaluated policies are distinct.
A breach from another result is not presented for the displayed forecast. Sources, history window
and baseline comparisons remain available in expandable details; the raw persisted result is a
**Copy raw result (JSON)** button rather than a page of JSON.

New predictions start in SHADOW, labelled **Observing** on the page (**Approved** for ACTIVE; the
pilot's term is the badge's tooltip). Approval still requires separate operator confirmation,
measured realised quality and all baseline comparisons, and the page states what still blocks it
before the button is pressed. Forecasts send no alerts. Nominal quantiles
are not guaranteed confidence. See the [pilot runbook](notes/timesfm-pilot.md) for quality gates,
source authorization, persistence and failover behavior.

## Operator API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/forecasts/preparation` | Configuration and actual MCP catalogue checks; no remote probe |
| `POST /api/forecasts/preparation/probe` | Cached bounded PostgreSQL connection and TimesFM health probes |
| `GET /api/forecasts/candidates` | Bounded metadata catalogue without SQL/DDL/secrets |
| `POST /api/forecasts/configuration` | Validate confirmed declaration and export YAML; no runtime mutation |
| `GET /api/forecasts/{seriesId}/progress` | Fresh bounded history preparation for an approved source |

These endpoints use the application's existing operator REST boundary. No setup, enrollment,
configuration write or inference-on-read MCP tool has been added.

### Starting from a metric card

Select **Prepare a forecast** on the Metrics page. The assistant selects that metric
from the current candidate catalogue and prepopulates the captured unit and known
source topics/groups. Eligibility blockers still apply; source attestation and export
confirmation are required. No runtime configuration is applied by the card action.

### CI stack smoke

The `forecast-stack` CI job builds the verified release JAR and the pinned CPU
TimesFM image, then starts an isolated `forecast-ci` Compose project with PostgreSQL.
`ci/forecast-stack.py` creates a real consumer time-lag metric, exports reviewed
configuration, restarts Explorer and requires a real observation in PostgreSQL.
It then inserts **512 synthetic historical buckets** to exercise TimesFM immediately,
and requires a READY/TIMESFM/SHADOW result readable through authenticated MCP.
This verifies integration, not forecasting accuracy, realised quality or activation.
The ephemeral project's volumes are removed at the end. Run this driver only against
an isolated disposable stack, never against an existing deployment.
