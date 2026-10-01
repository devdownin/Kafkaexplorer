# SPDX-License-Identifier: AGPL-3.0-or-later
import os
from .model import checkpoint

if __name__ == "__main__":
    checkpoint(os.environ.get("TIMESFM_CACHE_DIR", "/models"), download=True)
    print("Pinned checkpoint downloaded and SHA-256 verified")
