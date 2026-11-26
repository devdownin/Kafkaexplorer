# Model licences carried by the `offline` image

The `offline` target of `services/timesfm/Dockerfile` ships the two checkpoints inside the image, so
they are redistributed here and each licence's notice travels with them (`/licenses` in the image).

| Model | Revision | Licence |
|---|---|---|
| `google/timesfm-2.5-200m-pytorch` | `1d952420fba87f3c6dee4f240de0f1a0fbc790e3` | Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0> |
| `mistralai/Timestral-28M` (shadow, plain tags) | `49b26409ac87b5935539a5d8ffe7f397b720db47` | Modified MIT, Mistral AI — `TIMESTRAL-28M-LICENSE.txt` |
| `mistralai/Timestral-5M` (shadow, `-timestral-5m` tags) | `bdd20cfc15a3fea0e4cb5ccef37f8d3ca7aa335f` | the same |
| `mistralai/Timestral-123M` (shadow, `-timestral-123m` tags) | `5a147287addda85f6acd8da3fb8858fe9af7e8f7` | the same |
| `mistralai/Timestral-209M` (shadow, `-timestral-209m` tags) | `9874d252ab0aae16f7f7c999f98f98d6ad7d8365` | the same |

Each image carries TimesFM and **one** Timestral checkpoint. The four Timestral repositories publish
the same licence text, byte for byte, so `TIMESTRAL-28M-LICENSE.txt` is the notice for all of them.

**Timestral's licence is not an open-source licence.** It requires the attribution and permission
notice to accompany every copy, and it withdraws every right from a company whose consolidated
monthly revenue exceeded USD 20 million in the preceding month (a commercial licence is Mistral
AI's to grant). Running an image that carries these weights means accepting that condition;
`ghcr.io/devdownin/kex-forecast` carries it, and the Apache-2.0 TimesFM weights do not
need it. The stack's default image (`compose/timesfm.yml`) downloads the weights at first start
instead and leaves that acceptance to the operator.
