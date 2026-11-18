#!/usr/bin/env bash
# Run before opening a pull request. Mirrors the frontend and documentation
# gates from GitHub Actions; a successful TypeScript build alone is insufficient.
set -euo pipefail
cd "$(dirname "$0")/.."
echo '==> Documentation contract checks'
for check in docs/check-*.py; do python3 "$check"; done
echo '==> Frontend install, types, bundle, lint, tests'
cd src/main/webapp
npm ci
npm run build
npm run lint
npm test -- --run
echo '==> PR preflight passed'
