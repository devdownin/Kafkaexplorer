# SPDX-License-Identifier: AGPL-3.0-or-later
import numpy as np
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from kex_timesfm.app import create_app
from kex_timesfm.contract import ForecastRequest, MAX_BODY_BYTES, named_predictions
from kex_timesfm.worker import InvalidOutput, WorkerBusy, WorkerTimeout, WorkerUnavailable

TOKEN = "test-token-" + "x" * 32
HEADERS = {"Authorization": "Bearer " + TOKEN}


def payload(n=1, horizon=2):
    return {"schemaVersion": 1, "requestId": "12345678-1234-1234-1234-123456789abc", "horizonPoints": horizon,
            "series": [{"seriesId": f"{i:064x}", "values": [0.0] * 512} for i in range(n)]}


def outputs(n=1, horizon=2):
    full = np.broadcast_to(np.arange(10), (n, horizon, 10)).astype(float).copy()
    full[:, :, 0] = 777  # the first channel is NOT Q10, and central is NOT channel zero
    return full[:, :, 5].copy(), full


class StubWorker:
    ready = True
    error = None
    calls = 0

    def forecast(self, request):
        self.calls += 1
        if self.error:
            raise self.error()
        return named_predictions(request, *outputs(len(request.series), request.horizonPoints)), 1


def test_explicit_upstream_mapping_and_distinct_series():
    request = ForecastRequest.model_validate(payload(n=2))
    central, full = outputs(n=2)
    full[1] += 100
    central[1] += 100
    predictions = named_predictions(request, central, full)
    assert predictions[0]["central"] == [5, 5]
    assert predictions[0]["q10"] == [1, 1]
    assert predictions[0]["q50"] == [5, 5]
    assert predictions[0]["q90"] == [9, 9]
    assert predictions[1]["q50"] == [105, 105]
    assert predictions[1]["seriesId"] == f"{1:064x}"


@pytest.mark.parametrize("defect", ["shape", "nan", "crossed", "hidden_crossed", "wrong_median"])
def test_invalid_model_output_is_never_repaired(defect):
    central, full = outputs()
    if defect == "shape":
        full = full[:, :, :9]
    elif defect == "nan":
        full[0, 0, 0] = np.nan
    elif defect == "crossed":
        full[0, 0, 1] = 1000
    elif defect == "hidden_crossed":
        full[0, 0, 3] = 4.5  # Q30 > Q40, although the three exposed quantiles are ordered
    else:
        central[0, 0] = 0
    with pytest.raises(ValueError):
        named_predictions(ForecastRequest.model_validate(payload()), central, full)


@pytest.mark.parametrize("field,value", [("horizonPoints", 0), ("horizonPoints", 61), ("horizonPoints", True),
                                          ("checkpoint", "arbitrary"), ("schemaVersion", 2)])
def test_closed_request_limits(field, value):
    data = payload()
    data[field] = value
    with pytest.raises(ValidationError):
        ForecastRequest.model_validate(data)


@pytest.mark.parametrize("defect", ["short", "long", "nan", "infinity", "overflow", "bool", "duplicate", "too_many", "labels"])
def test_context_admissibility(defect):
    data = payload()
    if defect == "short":
        data["series"][0]["values"] = [0] * 127
    elif defect == "long":
        data["series"][0]["values"] = [0] * 513
    elif defect in ("nan", "infinity", "overflow", "bool"):
        data["series"][0]["values"][0] = {"nan": float("nan"), "infinity": float("inf"), "overflow": 1e31, "bool": True}[defect]
    elif defect == "duplicate":
        data["series"] *= 2
    elif defect == "too_many":
        data = payload(n=5)
    else:
        data["series"][0]["labels"] = {"topic": "sensitive"}
    with pytest.raises(ValidationError):
        ForecastRequest.model_validate(data)


def test_a_partial_context_is_admitted_and_lengths_may_differ_within_a_batch():
    data = payload(n=2)
    data["series"][0]["values"] = [0.0] * 128
    request = ForecastRequest.model_validate(data)
    assert [len(s.values) for s in request.series] == [128, 512]


def test_live_ready_auth_and_successful_http_contract():
    worker = StubWorker()
    with TestClient(create_app(worker, TOKEN, manage_worker=False)) as client:
        assert client.get("/health/live").status_code == 200
        assert client.get("/health/ready").status_code == 200
        assert client.post("/v1/forecast", json=payload()).status_code == 401
        result = client.post("/v1/forecast", headers=HEADERS, json=payload(n=2))
        assert result.status_code == 200
        body = result.json()
        assert body["centralStatistic"] == "MEDIAN"
        assert body["requestId"] == payload()["requestId"]
        assert len(body["series"]) == 2
        assert worker.calls == 1
        worker.ready = False
        assert client.get("/health/ready").status_code == 503
        assert client.get("/health/live").status_code == 200


def test_validation_is_sanitized_and_large_chunked_body_rejected_before_inference():
    worker = StubWorker()
    with TestClient(create_app(worker, TOKEN, manage_worker=False)) as client:
        bad = payload()
        bad["sql"] = "SECRET"
        response = client.post("/v1/forecast", headers=HEADERS, json=bad)
        assert response.status_code == 422
        assert "SECRET" not in response.text
        stream = (b"x" * 4096 for _ in range(MAX_BODY_BYTES // 4096 + 1))
        response = client.post("/v1/forecast", headers={**HEADERS, "Content-Type": "application/json"}, content=stream)
        assert response.status_code == 413
        assert worker.calls == 0


@pytest.mark.parametrize("error,status", [(WorkerBusy, 429), (WorkerTimeout, 504), (WorkerUnavailable, 503), (InvalidOutput, 502)])
def test_dependency_errors_have_no_fake_success(error, status):
    worker = StubWorker()
    worker.error = error
    with TestClient(create_app(worker, TOKEN, manage_worker=False)) as client:
        response = client.post("/v1/forecast", headers=HEADERS, json=payload())
        assert response.status_code == status
        assert "series" not in response.json()


def test_empty_token_fails_closed():
    with pytest.raises(ValueError):
        create_app(StubWorker(), "")
