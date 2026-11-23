# Schema Registry Explorer

**Explore → Schema Registry** (route `/schema-registry`) is a **read-only** view of a
Confluent-compatible Schema Registry. It is independent of the Kafka broker connection:
the rest of KafkaExplorer remains available when the registry is disabled or down.

## What you can inspect

- Browse subjects and filter by name. A subject is a registry contract, **not**
  necessarily a Kafka topic; `-key` and `-value` are naming conventions.
- Select any two stored versions. The diff normalizes Avro/JSON schema text before
  comparing line by line; Protobuf and other plain-text definitions keep their source layout.
- Read the schema's ID, type (`AVRO`, `PROTOBUF`, `JSON`) and references.
- Copy or expand the complete candidate schema.
- **Check compatibility** by submitting a *previously registered* candidate version
  against the selected baseline version through Confluent's non-mutating
  `POST /compatibility/subjects/{subject}/versions/{version}?verbose=true`.
  The response includes the actual Registry verdict and its diagnostic messages.
  The effective subject/global policy is displayed when the registry provides it.

Compatibility is **directional**, and is not the same thing as a textual diff.
A diff showing only an added field does not prove a compatible evolution; the
configured BACKWARD/FORWARD/FULL compatibility mode is authoritative.
A compatibility check never registers the candidate schema.

## Enable the demo registry

Run the standard stack with the optional registry overlay:

```bash
docker compose -f docker-compose.yml -f compose/schema-registry.yml up -d
```

That overlay starts Schema Registry on `127.0.0.1:8081`, sets the Explorer's
`KAFKA_SCHEMA_REGISTRY_URL=http://schema-registry:8081`, and seeds two Avro
subjects with `setup-demo-avro.sh`:

- `demo.avro.orders-value`
- `demo.avro.customers-value`

For an external registry, set `KAFKA_SCHEMA_REGISTRY_URL` to its reachable
HTTP(S) URL **from the backend process**. No registry is required for JSON/XML
sampling, metrics, forecasts, data model or the Strata 3D scene. An unreachable
registry is shown as an actionable error on this page, not a startup dependency.

## REST API

All endpoints are scoped to the KafkaExplorer server's configured registry;
the browser cannot choose an arbitrary upstream URL.

| Method | Path | Response |
| --- | --- | --- |
| GET | `/api/schema-registry/subjects` | `{subjects, total, truncated}` |
| GET | `/api/schema-registry/subjects/{subject}/versions` | Numeric version list |
| GET | `/api/schema-registry/subjects/{subject}/versions/{version}` | Schema, type, ID, references |
| POST | `/api/schema-registry/subjects/{subject}/compatibility` | Verdict, effective policy and messages |

The POST body is `{"baselineVersion":1,"candidateVersion":2}`; it only checks
versions that already exist in the registry. The UI does **not** offer any schema
registration, deletion or compatibility-setting mutation.

### Operational bounds

- The browser shows up to **1,000 subjects** (with a truncation warning).
- A line diff processes up to **350 lines per version**; full schema text stays accessible.
- Each upstream response is capped at **2 MiB**, with a 3-second connect timeout
  and 6-second request timeout. The upstream is never followed through redirects.
- Unreachable registry, missing subjects and invalid versions return explicit JSON
  errors from the API. The app never exposes upstream HTML errors or credentials.

For protected Schema Registry deployments, use a network-accessible endpoint
supported by the existing server configuration. Schema Registry-specific
authentication headers are **not configured by this view**; secure deployments
may need a gateway until dedicated registry credentials are supported.
