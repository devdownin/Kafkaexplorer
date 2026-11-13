#!/usr/bin/env python3
"""Check the resolved `compose/local-ai.yml` stack says one thing about each of its two models.

    docker compose -f docker-compose.yml -f compose/forecasts.yml -f compose/llama-cpp.yml \\
      -f compose/local-ai.yml config --format json | python3 docs/local-ai-stack-check.py

That overlay writes each value twice because two services read it: the GGUF's file name on the
downloader and on the server, the alias on the server and on the Explorer, the Timestral pin on
the one-shot that fetches the weights and on the worker that loads them. Each pair disagreeing
is a stack that starts and never answers — a server waiting for a file nobody downloads, a
model asked for under a name it does not serve, a worker refusing weights its prefetch never
verified — and nothing in `config -q` can see it. Read from the *resolved* document, so what is
compared is what would run.
"""
from __future__ import annotations

import json
import re
import sys


def main() -> int:
    services = json.load(sys.stdin)['services']

    def env(service: str, key: str) -> str:
        return str(services[service].get('environment', {}).get(key, ''))

    problems: list[str] = []

    def same(what: str, *pairs: tuple[str, str]) -> None:
        values = {f'{s}.{k}': env(s, k) for s, k in pairs}
        if len(set(values.values())) != 1 or not next(iter(values.values())):
            problems.append(f'{what} differs or is empty: {values}')

    same('the GGUF file', ('llama-cpp', 'LLAMA_CPP_MODEL_FILE'), ('llama-cpp-model', 'LLAMA_CPP_MODEL_FILE'))
    same('the model alias', ('llama-cpp', 'LLAMA_CPP_MODEL_NAME'), ('explorer', 'CLAUDE_MODEL'))
    same('the Timestral revision', ('timesfm', 'TIMESTRAL_REVISION'),
         ('forecast-model-prefetch', 'TIMESTRAL_REVISION'))
    same('the Timestral digest', ('timesfm', 'TIMESTRAL_SHA256'),
         ('forecast-model-prefetch', 'TIMESTRAL_SHA256'))

    if not env('llama-cpp-model', 'LLAMA_CPP_MODEL_URL').endswith('/' + env('llama-cpp', 'LLAMA_CPP_MODEL_FILE')):
        problems.append('LLAMA_CPP_MODEL_URL does not end on the file llama-cpp serves')
    if not re.fullmatch(r'[0-9a-f]{64}', env('llama-cpp-model', 'LLAMA_CPP_MODEL_SHA256')):
        problems.append('the GGUF is not pinned by a SHA-256')
    if not re.fullmatch(r'[0-9a-f]{40}', env('timesfm', 'TIMESTRAL_REVISION')):
        problems.append('TIMESTRAL_REVISION is not a 40-hex commit')
    if not re.fullmatch(r'[0-9a-f]{64}', env('timesfm', 'TIMESTRAL_SHA256')):
        problems.append('TIMESTRAL_SHA256 is not a 64-hex digest')
    if env('explorer', 'EXPLORER_FORECASTING_INFERENCE_SHADOW_ENABLED') != 'true':
        problems.append('the shadow is pinned on the service but not scored by the Explorer')
    if env('explorer', 'CLAUDE_PROVIDER') != 'LLAMA_CPP':
        problems.append('the Explorer does not name LLAMA_CPP as its provider')

    for problem in problems:
        print(f'  ✗ {problem}')
    if problems:
        print(f'\n{len(problems)} problem(s) in the local AI stack.')
        return 1
    print('The local AI stack agrees with itself: one GGUF, one alias, one Timestral pin, shadow scored.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
