# SPDX-License-Identifier: AGPL-3.0-or-later
"""Timestral as a shadow model: same request contract, its own process, never the primary answer.

The checkpoint is pinned by the operator (revision and weights digest) rather than in code: both
values must be read from the Hub by whoever accepts the weights' licence, and a shadow that starts
on an unpinned `main` would score a model nobody can name afterwards.
"""
import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .contract import ForecastRequest, series_rows

MODEL_ID = "mistralai/Timestral-28M"
ADAPTER_VERSION = "kex-timestral-28m-v1"
QUANTILE_LEVELS = (0.01, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5,
                   0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.99)
Q10, Q50, Q90 = (QUANTILE_LEVELS.index(level) for level in (0.1, 0.5, 0.9))


@dataclass(frozen=True)
class TimestralPin:
    revision: str
    weights_sha256: str

    def __post_init__(self):
        if not re.fullmatch(r"[0-9a-f]{40}", self.revision) or not re.fullmatch(r"[0-9a-f]{64}", self.weights_sha256):
            raise ValueError("TIMESTRAL_REVISION must be a 40-hex commit and TIMESTRAL_SHA256 a 64-hex digest")

    @classmethod
    def from_environment(cls, environ) -> "TimestralPin | None":
        revision = environ.get("TIMESTRAL_REVISION", "").strip()
        digest = environ.get("TIMESTRAL_SHA256", "").strip()
        if not revision and not digest:
            return None
        # Half a pin is a deployment mistake, not a request for an unpinned shadow.
        return cls(revision, digest)


def checkpoint(pin: TimestralPin, cache_dir: str, *, download: bool = False) -> Path:
    from huggingface_hub import hf_hub_download
    options = {"revision": pin.revision, "cache_dir": cache_dir, "local_files_only": not download}
    config = Path(hf_hub_download(MODEL_ID, "config.json", **options))
    weights = Path(hf_hub_download(MODEL_ID, "model.safetensors", **options))
    with weights.open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
    if digest != pin.weights_sha256 or config.parent != weights.parent:
        raise ValueError("checkpoint integrity failure")
    return weights.parent


def context_matrix(request: ForecastRequest) -> np.ndarray:
    """Left-pads shorter contexts with NaN, which Timestral reads as missing rather than as zero."""
    width = max(len(s.values) for s in request.series)
    matrix = np.full((len(request.series), width), np.nan, dtype=np.float32)
    for i, s in enumerate(request.series):
        matrix[i, width - len(s.values):] = s.values
    return matrix


def covariate_tensor(request: ForecastRequest) -> np.ndarray | None:
    """`(B, C, T)` on the same left-padded grid as the targets, or None without covariates."""
    count = len(getattr(request.series[0], "pastCovariates", []))
    if count == 0:
        return None
    width = max(len(s.values) for s in request.series)
    tensor = np.full((len(request.series), count, width), np.nan, dtype=np.float32)
    for i, s in enumerate(request.series):
        for c, row in enumerate(s.pastCovariates):
            tensor[i, c, width - len(row):] = row
    return tensor


def shadow_predictions(request: ForecastRequest, quantiles) -> list[dict]:
    """Validates the whole 21-level surface, as the primary adapter does; nothing is repaired."""
    quantiles = np.asarray(quantiles, dtype=np.float64)
    n, horizon = len(request.series), request.horizonPoints
    if quantiles.shape != (n, 1, horizon, len(QUANTILE_LEVELS)):
        raise ValueError("invalid model output shape")
    quantiles = quantiles[:, 0]
    if not np.isfinite(quantiles).all():
        raise ValueError("nonfinite model output")
    if (np.diff(quantiles, axis=2) < 0).any():
        raise ValueError("crossed model quantiles")
    median = quantiles[:, :, Q50]
    return series_rows(request, median, quantiles[:, :, Q10], median, quantiles[:, :, Q90])


class TimestralShadowModel:
    def __init__(self, pin: TimestralPin, cache_dir: str, threads: int):
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
        import torch
        from timestral import TimestralForecaster
        torch.set_num_threads(threads)
        torch.set_num_interop_threads(1)
        if torch.cuda.is_available():
            raise RuntimeError("CPU-only service required")
        self.forecaster = TimestralForecaster.from_pretrained(checkpoint(pin, cache_dir))
        if tuple(self.forecaster.quantile_levels) != QUANTILE_LEVELS:
            raise ValueError("unexpected quantile levels")
        if not self.forecaster.supports_covariates:
            raise ValueError("checkpoint was not trained with covariates")

    def forecast(self, request):
        result = self.forecaster.forecast(context_matrix(request), prediction_length=request.horizonPoints,
                                          past_covariates=covariate_tensor(request))
        return (result.quantiles.numpy(),)


def shadow_factory(pin: TimestralPin, settings):
    return TimestralShadowModel(pin, settings.cache_dir, settings.threads)
