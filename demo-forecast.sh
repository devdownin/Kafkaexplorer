#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Run the complete live Metrics/Forecast demo using published images only.
# No Maven, Node, Docker build or runtime model download.
#   ./demo-forecast.sh [up|down|reset|ps|logs [service]|probe]
set -euo pipefail
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec bash "$repo_dir/demo.sh" --forecast --prebuilt "$@"
