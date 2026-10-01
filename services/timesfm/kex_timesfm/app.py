# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import hmac
import json
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .contract import ADAPTER_VERSION, MAX_BODY_BYTES, MAX_RESPONSE_BYTES, MODEL_ID, MODEL_REVISION, ForecastRequest
from .worker import InvalidOutput, ModelWorker, WorkerBusy, WorkerSettings, WorkerTimeout, WorkerUnavailable


def create_app(worker, token: str, *, manage_worker: bool = True):
    if len(token) < 32 or len(token) > 256 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("TIMESFM_TOKEN must contain 32–256 non-whitespace ASCII characters")

    @asynccontextmanager
    async def lifespan(app):
        async def boot():
            try:
                await asyncio.to_thread(worker.start)
            except WorkerUnavailable:
                pass  # process stays live, readiness remains false; restart after fixing deployment
        task = asyncio.create_task(boot()) if manage_worker else None
        yield
        if task:
            await task
            await asyncio.to_thread(worker.close)

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # FastAPI's default includes the offending input, which may contain business values.
        return JSONResponse({"error": "INVALID_REQUEST"}, status_code=422)

    @app.middleware("http")
    async def bounded_private_contract(request: Request, call_next):
        if request.url.path not in ("/health/live", "/health/ready"):
            supplied = request.headers.get("authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + token).encode()):
                return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
        if request.url.path == "/v1/forecast" and request.method == "POST":
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

    @app.post("/v1/forecast")
    async def forecast(request: ForecastRequest):
        try:
            predictions, duration = await asyncio.to_thread(worker.forecast, request)
        except WorkerBusy:
            return JSONResponse({"error": "BUSY"}, status_code=429, headers={"Retry-After": "5"})
        except WorkerTimeout:
            return JSONResponse({"error": "WORKER_TIMEOUT"}, status_code=504)
        except InvalidOutput:
            return JSONResponse({"error": "INVALID_MODEL_OUTPUT"}, status_code=502)
        except WorkerUnavailable:
            return JSONResponse({"error": "MODEL_UNAVAILABLE"}, status_code=503)
        result = {"schemaVersion": 1, "requestId": request.requestId, "modelId": MODEL_ID,
                  "modelRevision": MODEL_REVISION, "adapterVersion": ADAPTER_VERSION,
                  "centralStatistic": "MEDIAN", "durationMillis": duration, "series": predictions}
        encoded = json.dumps(result, allow_nan=False).encode()
        if len(encoded) > MAX_RESPONSE_BYTES:
            return JSONResponse({"error": "INVALID_MODEL_OUTPUT"}, status_code=502)
        return JSONResponse(result)

    return app


def application():
    settings = WorkerSettings(cache_dir=os.environ.get("TIMESFM_CACHE_DIR", "/models"),
                              threads=int(os.environ.get("TIMESFM_THREADS", "4")))
    return create_app(ModelWorker(settings), os.environ.get("TIMESFM_TOKEN", ""))
