# Model licences carried by the `offline` image

The `offline` target of `services/timesfm/Dockerfile` ships the two checkpoints inside the image, so
they are redistributed here and each licence's notice travels with them (`/licenses` in the image).

| Model | Revision | Licence |
|---|---|---|
| `google/timesfm-2.5-200m-pytorch` | `1d952420fba87f3c6dee4f240de0f1a0fbc790e3` | Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0> |
| `mistralai/Timestral-28M` (shadow) | `49b26409ac87b5935539a5d8ffe7f397b720db47` | Modified MIT, Mistral AI — `TIMESTRAL-28M-LICENSE.txt` |

**Timestral's licence is not an open-source licence.** It requires the attribution and permission
notice to accompany every copy, and it withdraws every right from a company whose consolidated
monthly revenue exceeded USD 20 million in the preceding month (a commercial licence is Mistral
AI's to grant). Running an image that carries these weights means accepting that condition;
`ghcr.io/devdownin/kex-forecast` carries it, and the Apache-2.0 TimesFM weights do not
need it. The stack's default image (`compose/timesfm.yml`) downloads the weights at first start
instead and leaves that acceptance to the operator.
