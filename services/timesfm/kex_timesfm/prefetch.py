# SPDX-License-Identifier: AGPL-3.0-or-later
import os
from . import timestral
from .model import checkpoint

if __name__ == "__main__":
    cache_dir = os.environ.get("TIMESFM_CACHE_DIR", "/models")
    checkpoint(cache_dir, download=True)
    print("Pinned checkpoint downloaded and SHA-256 verified")
    pin = timestral.TimestralPin.from_environment(os.environ)
    if pin is not None:
        timestral.checkpoint(pin, cache_dir, download=True)
        print("Pinned shadow checkpoint downloaded and SHA-256 verified")
