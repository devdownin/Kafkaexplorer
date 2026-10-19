# SPDX-License-Identifier: AGPL-3.0-or-later
from functools import partial

import numpy as np
import pytest
from fastapi.testclient import TestClient

from kex_timesfm import app as service
from kex_timesfm import timestral
from kex_timesfm.app import create_app
from kex_timesfm.contract import MAX_BODY_BYTES, ForecastRequest, ShadowForecastRequest
from kex_timesfm.timestral import (QUANTILE_LEVELS, TimestralPin, context_matrix, covariate_tensor,
                                   shadow_predictions)
from kex_timesfm.worker import InvalidOutput, ModelWorker, WorkerBusy, WorkerSettings, WorkerTimeout, WorkerUnavailable
from tests.test_contract import HEADERS, TOKEN, StubWorker, payload

PIN = TimestralPin("a" * 40, "b" * 64)


def surface(n=1, horizon=2):
    """Each quantile carries its own level, so a wrong index shows up as a wrong number."""
    return np.broadcast_to(np.asarray(QUANTILE_LEVELS), (n, 1, horizon, len(QUANTILE_LEVELS))).copy()


class ShadowStub(StubWorker):
    busy = False
    received = None

    def forecast(self, request):
        self.calls += 1
        self.received = request
        if self.error:
            raise self.error()
        return shadow_predictions(request, surface(len(request.series), request.horizonPoints)), 3


class PrimaryStub(StubWorker):
    busy = False


def client(primary=None, shadow=None):
    primary = primary or PrimaryStub()
    return TestClient(create_app(primary, TOKEN, shadow_worker=shadow, shadow_pin=PIN if shadow else None,
                                 manage_worker=False))


def test_no_pin_means_no_shadow_and_half_a_pin_fails_closed():
    assert TimestralPin.from_environment({}) is None
    assert TimestralPin.from_environment({"TIMESTRAL_REVISION": "a" * 40, "TIMESTRAL_SHA256": "b" * 64}) == PIN
    for environ in ({"TIMESTRAL_REVISION": "a" * 40}, {"TIMESTRAL_SHA256": "b" * 64},
                    {"TIMESTRAL_REVISION": "main", "TIMESTRAL_SHA256": "b" * 64},
                    {"TIMESTRAL_REVISION": "a" * 40, "TIMESTRAL_SHA256": "B" * 64}):
        with pytest.raises(ValueError):
            TimestralPin.from_environment(environ)


def test_shorter_contexts_are_left_padded_as_missing_not_as_zero():
    data = payload(n=2)
    data["series"][0]["values"] = [float(i) for i in range(128)]
    matrix = context_matrix(ForecastRequest.model_validate(data))
    assert matrix.shape == (2, 512)
    assert np.isnan(matrix[0, :384]).all()
    assert matrix[0, 384:].tolist() == [float(i) for i in range(128)]
    assert not np.isnan(matrix[1]).any()


def test_named_quantiles_come_from_their_levels():
    request = ForecastRequest.model_validate(payload(n=2))
    full = surface(n=2)
    full[1] += 100
    rows = shadow_predictions(request, full)
    assert rows[0]["q10"] == [0.1, 0.1]
    assert rows[0]["q50"] == rows[0]["central"] == [0.5, 0.5]
    assert rows[0]["q90"] == [0.9, 0.9]
    assert rows[1]["q50"] == [100.5, 100.5]
    assert rows[1]["seriesId"] == f"{1:064x}"


@pytest.mark.parametrize("defect", ["shape", "variates", "nan", "hidden_crossed"])
def test_invalid_shadow_output_is_never_repaired(defect):
    full = surface()
    if defect == "shape":
        full = full[..., :20]
    elif defect == "variates":
        full = np.concatenate([full, full], axis=1)
    elif defect == "nan":
        full[0, 0, 0, 7] = np.nan
    else:
        full[0, 0, 0, 0] = 0.06  # Q01 > Q05, outside the three exposed quantiles
    with pytest.raises(ValueError):
        shadow_predictions(ForecastRequest.model_validate(payload()), full)


def test_disabled_shadow_answers_404_and_leaves_the_primary_alone():
    primary = PrimaryStub()
    with client(primary) as http:
        assert http.get("/health/shadow/ready").status_code == 404
        assert http.post("/v1/shadow/forecast", headers=HEADERS, json=payload()).json() == {"error": "SHADOW_DISABLED"}
        assert http.post("/v1/forecast", headers=HEADERS, json=payload()).status_code == 200
        assert primary.calls == 1


def test_shadow_answers_under_its_own_identity_behind_the_same_boundary():
    primary, shadow = PrimaryStub(), ShadowStub()
    with client(primary, shadow) as http:
        assert http.post("/v1/shadow/forecast", json=payload()).status_code == 401
        body = http.post("/v1/shadow/forecast", headers=HEADERS, json=payload(n=2)).json()
        assert body["modelId"] == "mistralai/Timestral-28M"
        assert body["modelRevision"] == PIN.revision
        assert body["adapterVersion"] == timestral.ADAPTER_VERSION
        assert body["centralStatistic"] == "MEDIAN"
        assert body["series"][0]["q90"] == [0.9, 0.9]
        primary_body = http.post("/v1/forecast", headers=HEADERS, json=payload()).json()
        assert primary_body["modelId"] == "google/timesfm-2.5-200m-pytorch"
        assert (primary.calls, shadow.calls) == (1, 1)


def test_shadow_yields_while_the_primary_is_computing():
    primary, shadow = PrimaryStub(), ShadowStub()
    primary.busy = True
    with client(primary, shadow) as http:
        response = http.post("/v1/shadow/forecast", headers=HEADERS, json=payload())
        assert response.status_code == 429
        assert response.headers["Retry-After"] == "5"
        assert shadow.calls == 0


def test_shadow_readiness_is_separate_from_the_primary():
    shadow = ShadowStub()
    shadow.ready = False
    with client(shadow=shadow) as http:
        assert http.get("/health/ready").status_code == 200
        assert http.get("/health/shadow/ready").status_code == 503


@pytest.mark.parametrize("error,status", [(WorkerBusy, 429), (WorkerTimeout, 504), (WorkerUnavailable, 503), (InvalidOutput, 502)])
def test_shadow_errors_have_no_fake_success(error, status):
    shadow = ShadowStub()
    shadow.error = error
    with client(shadow=shadow) as http:
        response = http.post("/v1/shadow/forecast", headers=HEADERS, json=payload())
        assert response.status_code == status
        assert "series" not in response.json()


def test_shadow_body_is_bounded_before_inference():
    shadow = ShadowStub()
    with client(shadow=shadow) as http:
        stream = (b"x" * 4096 for _ in range(MAX_BODY_BYTES // 4096 + 1))
        response = http.post("/v1/shadow/forecast", headers={**HEADERS, "Content-Type": "application/json"},
                             content=stream)
        assert response.status_code == 413
        assert shadow.calls == 0


def test_a_shadow_worker_without_its_pin_is_refused():
    with pytest.raises(ValueError):
        create_app(PrimaryStub(), TOKEN, shadow_worker=ShadowStub())


def test_application_wires_the_shadow_only_from_a_complete_pin(monkeypatch):
    monkeypatch.setenv("TIMESFM_TOKEN", TOKEN)
    monkeypatch.delenv("TIMESTRAL_REVISION", raising=False)
    monkeypatch.delenv("TIMESTRAL_SHA256", raising=False)
    assert TestClient(service.application()).get("/health/shadow/ready").status_code == 404
    monkeypatch.setenv("TIMESTRAL_REVISION", PIN.revision)
    with pytest.raises(ValueError):
        service.application()
    monkeypatch.setenv("TIMESTRAL_SHA256", PIN.weights_sha256)
    # Lifespan not entered: the worker is built but not started, so it reads as unavailable.
    assert TestClient(service.application()).get("/health/shadow/ready").status_code == 503


class ProcessShadowModel:
    def forecast(self, request):
        return (surface(len(request.series), request.horizonPoints),)


def process_shadow_factory(pin, settings):
    assert pin == PIN
    return ProcessShadowModel()


def test_shadow_worker_runs_in_its_own_process_with_its_own_decoder():
    worker = ModelWorker(WorkerSettings(startup_seconds=10, threads=1),
                         factory=partial(process_shadow_factory, PIN), decode=shadow_predictions)
    try:
        worker.start()
        assert worker.ready and not worker.busy
        predictions, _ = worker.forecast(ForecastRequest.model_validate(payload()))
        assert predictions[0]["q10"] == [0.1, 0.1]
    finally:
        worker.close()


def with_covariates(data, *rows):
    for series in data["series"]:
        series["pastCovariates"] = [list(r) if r is not None else [2.0] * len(series["values"]) for r in rows]
    return data


def test_covariates_share_the_targets_left_padded_grid():
    data = with_covariates(payload(n=2), None)
    data["series"][0]["values"] = [1.0] * 128
    data["series"][0]["pastCovariates"] = [[float(i) for i in range(128)]]
    tensor = covariate_tensor(ShadowForecastRequest.model_validate(data))
    assert tensor.shape == (2, 1, 512)
    assert np.isnan(tensor[0, 0, :384]).all()
    assert tensor[0, 0, 384:].tolist() == [float(i) for i in range(128)]
    assert (tensor[1, 0] == 2.0).all()
    assert covariate_tensor(ShadowForecastRequest.model_validate(payload())) is None


@pytest.mark.parametrize("defect", ["short", "nan", "too_many", "uneven_batch"])
def test_inadmissible_covariates_are_refused(defect):
    data = with_covariates(payload(n=2), None)
    if defect == "short":
        data["series"][0]["pastCovariates"] = [[1.0] * 511]
    elif defect == "nan":
        data["series"][0]["pastCovariates"][0][3] = float("nan")
    elif defect == "too_many":
        data = with_covariates(payload(n=2), None, None, None, None, None)
    else:
        data["series"][1]["pastCovariates"] = []
    with pytest.raises(ValueError):
        ShadowForecastRequest.model_validate(data)


def test_only_the_shadow_route_accepts_covariates():
    shadow = ShadowStub()
    with client(shadow=shadow) as http:
        assert http.post("/v1/forecast", headers=HEADERS, json=with_covariates(payload(), None)).status_code == 422
        response = http.post("/v1/shadow/forecast", headers=HEADERS, json=with_covariates(payload(), None))
        assert response.status_code == 200
        assert len(shadow.received.series[0].pastCovariates) == 1
