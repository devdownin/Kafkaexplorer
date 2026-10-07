# SPDX-License-Identifier: AGPL-3.0-or-later
"""Versioned numeric-only internal contract; no resource names or executable parameters."""
import math
from typing import Annotated, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, StrictFloat, StrictInt, model_validator

MODEL_ID = "google/timesfm-2.5-200m-pytorch"
MODEL_REVISION = "1d952420fba87f3c6dee4f240de0f1a0fbc790e3"
WEIGHTS_SHA256 = "2f776efe6245e42b24bc4153ffdf61810140210e4bd3b01fb21f7aa779ab6ce8"
ADAPTER_VERSION = "kex-timesfm-2.5-v2"
MAX_BODY_BYTES = 128 * 1024
MAX_RESPONSE_BYTES = 256 * 1024
MAX_BATCH = 4
CONTEXT_POINTS = 512
MAX_HORIZON = 60


class ClosedModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class SeriesInput(ClosedModel):
    # Opaque identifiers, never names/labels. Echoed in exactly the supplied order.
    seriesId: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    values: Annotated[list[StrictFloat | StrictInt], Field(min_length=CONTEXT_POINTS, max_length=CONTEXT_POINTS)]

    @model_validator(mode="after")
    def finite_values(self):
        if any(not math.isfinite(v) or abs(v) > 1e30 for v in self.values):
            raise ValueError("invalid numeric context")
        return self


class ForecastRequest(ClosedModel):
    schemaVersion: Literal[1]
    requestId: Annotated[str, Field(pattern=r"^[a-f0-9-]{36}$")]
    horizonPoints: Annotated[StrictInt, Field(ge=1, le=MAX_HORIZON)]
    series: Annotated[list[SeriesInput], Field(min_length=1, max_length=MAX_BATCH)]

    @model_validator(mode="after")
    def unique_series(self):
        if len({s.seriesId for s in self.series}) != len(self.series):
            raise ValueError("duplicate series")
        return self


def named_predictions(request: ForecastRequest, central, quantiles) -> list[dict]:
    """Upstream 2.0.2 returns central=channel 5 (median), full channel 0 then Q10..Q90.

    Validate the entire quantile surface, including unexposed intermediate quantiles.
    No clipping, sorting, or replacing an invalid output with a baseline.
    """
    central = np.asarray(central)
    quantiles = np.asarray(quantiles)
    n, horizon = len(request.series), request.horizonPoints
    if central.shape != (n, horizon) or quantiles.shape != (n, horizon, 10):
        raise ValueError("invalid model output shape")
    if not np.isfinite(central).all() or not np.isfinite(quantiles).all():
        raise ValueError("nonfinite model output")
    if not np.array_equal(central, quantiles[:, :, 5]):
        raise ValueError("central output is not the median")
    if (np.diff(quantiles[:, :, 1:], axis=2) < 0).any():
        raise ValueError("crossed model quantiles")
    return [
        {"seriesId": item.seriesId, "central": central[i].tolist(),
         "q10": quantiles[i, :, 1].tolist(), "q50": quantiles[i, :, 5].tolist(),
         "q90": quantiles[i, :, 9].tolist()}
        for i, item in enumerate(request.series)
    ]
