# SPDX-License-Identifier: AGPL-3.0-or-later
"""One warm model in a child process; timeouts kill the calculation, not just HTTP."""
import multiprocessing
import threading
import time
from dataclasses import dataclass

from .contract import ForecastRequest, named_predictions


class WorkerUnavailable(Exception):
    pass


class WorkerBusy(Exception):
    pass


class WorkerTimeout(Exception):
    pass


class InvalidOutput(Exception):
    pass


@dataclass(frozen=True)
class WorkerSettings:
    cache_dir: str = "/models"
    threads: int = 4
    startup_seconds: float = 120
    deadline_seconds: float = 30

    def __post_init__(self):
        if not 1 <= self.threads <= 4 or not 0 < self.deadline_seconds <= 30 or not 0 < self.startup_seconds <= 300:
            raise ValueError("invalid worker bounds")


def _model_factory(settings):
    from .model import TimesFmModel
    return TimesFmModel(settings.cache_dir, settings.threads)


def _serve(connection, settings, factory):
    try:
        model = factory(settings)
        warmup = ForecastRequest(schemaVersion=1, requestId="00000000-0000-0000-0000-000000000000",
                                 horizonPoints=1, series=[{"seriesId": "0" * 64, "values": [1.0] * 512}])
        named_predictions(warmup, *model.forecast(warmup))
        connection.send(("ready", None))
        while True:
            request = connection.recv()
            start = time.monotonic()
            try:
                predictions = named_predictions(request, *model.forecast(request))
                connection.send(("ok", (predictions, int((time.monotonic() - start) * 1000))))
            except ValueError:
                connection.send(("invalid", None))
            except Exception:
                # Do not leak paths, data or native exception messages to the HTTP caller.
                connection.send(("failed", None))
    except (EOFError, BrokenPipeError):
        pass
    except Exception:
        try:
            connection.send(("failed", None))
        except (EOFError, BrokenPipeError):
            pass
    finally:
        connection.close()


class ModelWorker:
    def __init__(self, settings=WorkerSettings(), factory=_model_factory):
        self.settings, self.factory = settings, factory
        self._lock = threading.Lock()
        self._process = self._connection = None
        self._ready = False

    @property
    def ready(self):
        return self._ready and self._process is not None and self._process.is_alive()

    def start(self):
        with self._lock:
            self._stop()
            ctx = multiprocessing.get_context("spawn")
            self._connection, child = ctx.Pipe()
            self._process = ctx.Process(target=_serve, args=(child, self.settings, self.factory), daemon=True)
            self._process.start()
            child.close()
            try:
                if not self._connection.poll(self.settings.startup_seconds) or self._connection.recv()[0] != "ready":
                    raise WorkerUnavailable()
                self._ready = True
            except (EOFError, OSError, WorkerUnavailable):
                self._stop()
                raise WorkerUnavailable() from None

    def forecast(self, request):
        if not self._lock.acquire(blocking=False):
            raise WorkerBusy()
        try:
            if not self.ready:
                raise WorkerUnavailable()
            self._connection.send(request)
            if not self._connection.poll(self.settings.deadline_seconds):
                self._stop()
                raise WorkerTimeout()
            status, payload = self._connection.recv()
            if status == "invalid":
                raise InvalidOutput()
            if status != "ok":
                self._stop()
                raise WorkerUnavailable()
            return payload
        except (EOFError, OSError):
            self._stop()
            raise WorkerUnavailable() from None
        finally:
            self._lock.release()

    def _stop(self):
        self._ready = False
        if self._process is not None:
            self._process.terminate()
            self._process.join(timeout=1)
            if self._process.is_alive():
                self._process.kill()
                self._process.join(timeout=1)
            self._process.close()
            self._process = None
        if self._connection is not None:
            self._connection.close()
            self._connection = None

    def close(self):
        with self._lock:
            self._stop()
