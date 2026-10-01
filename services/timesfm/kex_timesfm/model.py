# SPDX-License-Identifier: AGPL-3.0-or-later
import hashlib
import os
from pathlib import Path

from .contract import CONTEXT_POINTS, MAX_BATCH, MAX_HORIZON, MODEL_ID, MODEL_REVISION, WEIGHTS_SHA256


def checkpoint(cache_dir: str, *, download: bool = False) -> str:
    from huggingface_hub import hf_hub_download
    path = hf_hub_download(MODEL_ID, "model.safetensors", revision=MODEL_REVISION,
                           cache_dir=cache_dir, local_files_only=not download)
    with Path(path).open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
    if digest != WEIGHTS_SHA256:
        raise ValueError("checkpoint integrity failure")
    return path


class TimesFmModel:
    def __init__(self, cache_dir: str, threads: int):
        # Set before importing torch/timesfm. This delivery uses the locked CPU wheel.
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
        import torch
        import timesfm
        torch.set_num_threads(threads)
        torch.set_num_interop_threads(1)
        if torch.cuda.is_available():
            raise RuntimeError("CPU-only service required")
        self.model = timesfm.TimesFM_2p5_200M_torch(torch_compile=False)
        self.model.load_checkpoint(checkpoint(cache_dir), torch_compile=False)
        self.model.compile(timesfm.ForecastConfig(
            max_context=CONTEXT_POINTS, max_horizon=MAX_HORIZON, per_core_batch_size=MAX_BATCH,
            normalize_inputs=True, use_continuous_quantile_head=True,
            force_flip_invariance=True, infer_is_positive=False, fix_quantile_crossing=False,
            return_backcast=False,
        ))

    def forecast(self, request):
        import numpy as np
        # Upstream pads/mutates this list: make a fresh numeric list for every call.
        return self.model.forecast(horizon=request.horizonPoints,
                                   inputs=[np.asarray(s.values, dtype=np.float32) for s in request.series])
