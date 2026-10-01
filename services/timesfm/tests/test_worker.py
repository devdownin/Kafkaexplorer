# SPDX-License-Identifier: AGPL-3.0-or-later
import os
import threading
import time

import pytest

from kex_timesfm.contract import ForecastRequest
from kex_timesfm.worker import ModelWorker, WorkerBusy, WorkerSettings, WorkerTimeout, WorkerUnavailable
from tests.test_contract import outputs, payload


class ProcessModel:
    def forecast(self, request):
        marker = request.series[0].values[0]
        if marker == 99:
            time.sleep(60)  # must actually be killed, not left running after HTTP times out
        if marker == 98:
            os._exit(1)
        return outputs(len(request.series), request.horizonPoints)


def process_factory(settings):
    return ProcessModel()


def failed_factory(settings):
    raise RuntimeError("sensitive path or checkpoint failure")


def test_real_process_deadline_kills_worker_and_explicit_restart_recovers():
    worker = ModelWorker(WorkerSettings(startup_seconds=10, deadline_seconds=0.2), factory=process_factory)
    worker.start()
    child = worker._process
    pid = child.pid
    request = ForecastRequest.model_validate(payload())
    request.series[0].values[0] = 99
    start = time.monotonic()
    try:
        with pytest.raises(WorkerTimeout):
            worker.forecast(request)
        assert time.monotonic() - start < 3
        assert not worker.ready
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)
        worker.start()
        assert worker.ready
        predictions, _ = worker.forecast(ForecastRequest.model_validate(payload()))
        assert predictions[0]["q10"] == [1, 1]
    finally:
        worker.close()


def test_no_queue_while_a_real_calculation_is_running():
    worker = ModelWorker(WorkerSettings(startup_seconds=10, deadline_seconds=0.3), factory=process_factory)
    worker.start()
    request = ForecastRequest.model_validate(payload())
    request.series[0].values[0] = 99
    ended = threading.Event()
    def calculate():
        try:
            worker.forecast(request)
        except WorkerTimeout:
            ended.set()
    thread = threading.Thread(target=calculate)
    try:
        thread.start()
        # Wait for the real in-flight slot, without depending on the child process's timing.
        limit = time.monotonic() + 1
        while not worker._lock.locked() and time.monotonic() < limit:
            time.sleep(0.001)
        with pytest.raises(WorkerBusy):
            worker.forecast(ForecastRequest.model_validate(payload()))
        thread.join(3)
        assert ended.is_set()
    finally:
        worker.close()


def test_failed_startup_is_not_ready_and_messages_are_sanitized():
    worker = ModelWorker(WorkerSettings(startup_seconds=10), factory=failed_factory)
    with pytest.raises(WorkerUnavailable) as error:
        worker.start()
    assert not worker.ready
    assert "sensitive" not in str(error.value)
    worker.close()


def test_crash_invalidates_readiness():
    worker = ModelWorker(WorkerSettings(startup_seconds=10), factory=process_factory)
    worker.start()
    request = ForecastRequest.model_validate(payload())
    request.series[0].values[0] = 98
    try:
        with pytest.raises(WorkerUnavailable):
            worker.forecast(request)
        assert not worker.ready
    finally:
        worker.close()
