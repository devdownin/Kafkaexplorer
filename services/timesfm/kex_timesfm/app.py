# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import hmac
import json
import os
from contextlib import asynccontextmanager
from functools import partial

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .contract import ADAPTER_VERSION, MAX_BODY_BYTES, MAX_RESPONSE_BYTES, MODEL_ID, MODEL_REVISION, ForecastRequest
from . import timestral
from .worker import InvalidOutput, ModelWorker, WorkerBusy, WorkerSettings, WorkerTimeout, WorkerUnavailable

FORECAST_PATHS = ("/v1/forecast", "/v1/shadow/forecast")
PUBLIC_PATHS = ("/health/live", "/health/ready", "/health/shadow/ready")
SHADOW_DISABLED = {"error": "SHADOW_DISABLED"}


def create_app(worker, token: str, *, shadow_worker=None, shadow_pin=None, manage_worker: bool = True):
    if len(token) < 32 or len(token) > 256 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("TIMESFM_TOKEN must contain 32–256 non-whitespace ASCII characters")
    if (shadow_worker is None) != (shadow_pin is None):
        raise ValueError("a shadow worker needs its pinned revision, and only then")
    workers = [w for w in (worker, shadow_worker) if w is not None]

    @asynccontextmanager
    async def lifespan(app):
        async def boot():
            # Sequential: the shadow's load must not slow the primary's, and its failure stays its own.
            for each in workers:
                try:
                    await asyncio.to_thread(each.start)
                except WorkerUnavailable:
                    pass  # process stays live, readiness remains false; restart after fixing deployment
        task = asyncio.create_task(boot()) if manage_worker else None
        yield
        if task:
            await task
            for each in workers:
                await asyncio.to_thread(each.close)

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # FastAPI's default includes the offending input, which may contain business values.
        return JSONResponse({"error": "INVALID_REQUEST"}, status_code=422)

    @app.middleware("http")
    async def bounded_private_contract(request: Request, call_next):
        if request.url.path not in PUBLIC_PATHS:
            supplied = request.headers.get("authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + token).encode()):
                return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
        if request.url.path in FORECAST_PATHS and request.method == "POST":
            if request.headers.get("content-type", "").split(";")[0].strip() != "application/json":
                return JSONResponse({"error": "JSON_REQUIRED"}, status_code=415)
            size, chunks = 0, []
            try:
                async with asyncio.timeout(5):
                    async for chunk in request.stream():
                        size += len(chunk)
                        if size > MAX_BODY_BYTES:
                            return JSONResponse({"error": "REQUEST_TOO_LARGE"}, status_code=413)
                        chunks.append(chunk)
            except TimeoutError:
                return JSONResponse({"error": "REQUEST_TIMEOUT"}, status_code=408)
            # Starlette caches this exact bounded body for downstream parsing.
            request._body = b"".join(chunks)
        return await call_next(request)

    @app.get("/health/live")
    def live():
        return {"status": "LIVE"}

    @app.get("/health/ready")
    def ready():
        return JSONResponse({"status": "READY" if worker.ready else "UNAVAILABLE"},
                            status_code=200 if worker.ready else 503)

    @app.get("/health/shadow/ready")
    def shadow_ready():
        if shadow_worker is None:
            return JSONResponse(SHADOW_DISABLED, status_code=404)
        return JSONResponse({"status": "READY" if shadow_worker.ready else "UNAVAILABLE"},
                            status_code=200 if shadow_worker.ready else 503)

    @app.post("/v1/forecast")
    async def forecast(request: ForecastRequest):
        return await answer(worker, request, MODEL_ID, MODEL_REVISION, ADAPTER_VERSION)

    @app.post("/v1/shadow/forecast")
    async def shadow_forecast(request: ForecastRequest):
        if shadow_worker is None:
            return JSONResponse(SHADOW_DISABLED, status_code=404)
        # The shadow yields to the primary: both share the container's CPUs, and only one answer counts.
        if worker.busy:
            return busy()
        return await answer(shadow_worker, request, timestral.MODEL_ID, shadow_pin.revision,
                            timestral.ADAPTER_VERSION)

    return app


def busy():
    return JSONResponse({"error": "BUSY"}, status_code=429, headers={"Retry-After": "5"})


async def answer(worker, request, model_id, revision, adapter):
    try:
        predictions, duration = await asyncio.to_thread(worker.forecast, request)
    except WorkerBusy:
        return busy()
    except WorkerTimeout:
        return JSONResponse({"error": "WORKER_TIMEOUT"}, status_code=504)
    except InvalidOutput:
        return JSONResponse({"error": "INVALID_MODEL_OUTPUT"}, status_code=502)
    except WorkerUnavailable:
        return JSONResponse({"error": "MODEL_UNAVAILABLE"}, status_code=503)
    result = {"schemaVersion": 1, "requestId": request.requestId, "modelId": model_id,
              "modelRevision": revision, "adapterVersion": adapter,
              "centralStatistic": "MEDIAN", "durationMillis": duration, "series": predictions}
    encoded = json.dumps(result, allow_nan=False).encode()
    if len(encoded) > MAX_RESPONSE_BYTES:
        return JSONResponse({"error": "INVALID_MODEL_OUTPUT"}, status_code=502)
    return JSONResponse(result)


def application():
    cache_dir = os.environ.get("TIMESFM_CACHE_DIR", "/models")
    settings = WorkerSettings(cache_dir=cache_dir, threads=int(os.environ.get("TIMESFM_THREADS", "4")))
    pin = timestral.TimestralPin.from_environment(os.environ)
    shadow_worker = None
    if pin is not None:
        shadow_settings = WorkerSettings(cache_dir=cache_dir, threads=int(os.environ.get("TIMESTRAL_THREADS", "1")))
        shadow_worker = ModelWorker(shadow_settings, factory=partial(timestral.shadow_factory, pin),
                                    decode=timestral.shadow_predictions)
    return create_app(ModelWorker(settings), os.environ.get("TIMESFM_TOKEN", ""),
                      shadow_worker=shadow_worker, shadow_pin=pin)
