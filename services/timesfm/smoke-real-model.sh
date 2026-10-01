#!/usr/bin/env sh
set -eu
: "${TIMESFM_RUN_REAL_MODEL:=1}"
export TIMESFM_RUN_REAL_MODEL
uv run --group test pytest -q -m real_model tests/test_real_model.py
