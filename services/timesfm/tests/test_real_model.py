# SPDX-License-Identifier: AGPL-3.0-or-later
"""Opt-in smoke test for the pinned TimesFM checkpoint; never part of default CI."""
import json
import os
import resource
import time

import pytest

pytestmark = pytest.mark.real_model


@pytest.mark.skipif(os.environ.get("TIMESFM_RUN_REAL_MODEL") != "1",
                    reason="set TIMESFM_RUN_REAL_MODEL=1 to download and execute the checkpoint")
def test_pinned_checkpoint_loads_and_forecasts_within_budget(capsys):
    from kex_timesfm.contract import ForecastRequest
    from kex_timesfm.model import TimesFmModel

    started = time.monotonic()
    model = TimesFmModel(os.environ.get("TIMESFM_CACHE_DIR", "/models"),
                         int(os.environ.get("TIMESFM_THREADS", "4")))
    request = ForecastRequest.model_validate({
        "schemaVersion": 1,
        "requestId": "00000000-0000-0000-0000-000000000001",
        "horizonPoints": 60,
        "series": [{"seriesId": "0" * 64, "values": [float(i) for i in range(512)]}],
    })
    central, quantiles = model.forecast(request)
    elapsed = time.monotonic() - started
    peak_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    assert central.shape == (1, 60)
    assert quantiles.shape == (1, 60, 10)
    assert central[-1, -1] == quantiles[-1, -1, 5]
    assert elapsed <= float(os.environ.get("TIMESFM_SMOKE_MAX_SECONDS", "300"))
    assert peak_mb <= float(os.environ.get("TIMESFM_SMOKE_MAX_RSS_MB", "6144"))
    print(json.dumps({"elapsedSeconds": elapsed, "peakRssMb": peak_mb,
                      "modelId": "google/timesfm-2.5-200m-pytorch"}))
