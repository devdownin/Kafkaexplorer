#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Check the OpenRouter requests this application makes against the live API.

Everything the Explorer sends to OpenRouter's catalogue — the filter and sort parameter names, the
shape of what comes back — was taken from `@openrouter/sdk`'s published zod schemas rather than from
an observed response, because `openrouter.ai` is unreachable from the environment this code was
written in. The parsing is covered by unit tests against handwritten JSON; **the request was not
covered by anything**, and a mistyped filter name does not fail — it is ignored, and the shortlist
quietly stops being a shortlist.

This script closes that gap in one run. It is deliberately **not** named `docs/check-*.py`: those
are discovered and executed by CI, and this one needs the network and a key, so it would fail every
build. It is meant to be run by hand, by somebody who has both.

    OPENROUTER_API_KEY=sk-or-v1-… python3 docs/verify-openrouter-contract.py

What it asserts, and why each one matters:

  1. `GET /models` with the Explorer's filters returns *fewer* models than the unfiltered call.
     This is the assertion the unit tests cannot make: it is the only way to tell a filter that
     works from a filter the gateway does not recognise and silently drops.
  2. Every returned model really satisfies those filters — a filter can be recognised and still
     mean something other than what we assumed.
  3. The fields `LlmModelOption` and `LlmModelCheck` read are present and of the type expected,
     on a real row.
  4. `GET /model/{author}/{slug}` answers for a known-good slug, with the same field names.
  5. `GET /models/user` answers and carries model ids, so the entitlement check has something to
     compare against.

It prints what it checked and exits non-zero on the first disagreement. A network failure is
reported as a network failure, not as a contract violation — the same distinction the code itself
draws between "we asked and the answer is no" and "we could not ask".
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/")
KEY = os.environ.get("OPENROUTER_API_KEY", "")

# The floor the application computes for itself: prompt-char-budget / 4 + max-tokens, on the
# shipped defaults (120 000 / 4 + 4096). Kept here as a literal on purpose — the point is to send
# what the application sends, not to re-derive it.
CONTEXT_FLOOR = 34096
KNOWN_SLUG = os.environ.get("OPENROUTER_KNOWN_SLUG", "openai/gpt-4o-mini")


class Unreachable(RuntimeError):
    """The API could not be asked. Not a contract failure."""


def get(path: str) -> dict:
    request = urllib.request.Request(f"{BASE}/{path}", headers={"Accept": "application/json"})
    if KEY:
        request.add_header("Authorization", f"Bearer {KEY}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise Unreachable(f"HTTP {e.code} for /{path}") from e
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        raise Unreachable(f"{type(e).__name__} for /{path}: {e}") from e


def rows(payload: dict) -> list:
    data = payload.get("data", payload)
    if not isinstance(data, list):
        raise AssertionError(f"expected a list of models, got {type(data).__name__}")
    return data


def main() -> int:
    if not KEY:
        print("OPENROUTER_API_KEY is not set — this script needs a real key.", file=sys.stderr)
        return 2

    checks: list[str] = []
    try:
        # 1 — the filters actually narrow the catalogue.
        every = rows(get("models"))
        filtered_query = (
            "models?output_modalities=text"
            "&supported_parameters=structured_outputs"
            f"&context={CONTEXT_FLOOR}"
            "&sort=pricing-low-to-high&limit=20"
        )
        filtered = rows(get(filtered_query))
        if not filtered:
            raise AssertionError(
                "the filtered call returned nothing at all — either every filter name is wrong, "
                "or no model satisfies them, and those are worth telling apart by hand"
            )
        if len(every) <= len(filtered):
            raise AssertionError(
                f"the filters did not narrow anything: {len(every)} unfiltered against "
                f"{len(filtered)} filtered. A parameter name the gateway does not recognise is "
                "ignored rather than refused, which is exactly what this looks like."
            )
        checks.append(f"filters narrow {len(every)} models to {len(filtered)}")

        # 2 — and they mean what we assumed.
        for model in filtered:
            slug = model.get("id", "?")
            modalities = model.get("architecture", {}).get("output_modalities", [])
            if modalities and "text" not in modalities:
                raise AssertionError(f"{slug} came back but its output modalities are {modalities}")
            params = model.get("supported_parameters", [])
            if params and "structured_outputs" not in params:
                raise AssertionError(f"{slug} came back without structured_outputs in {params}")
            context = model.get("context_length")
            if isinstance(context, int) and context < CONTEXT_FLOOR:
                raise AssertionError(f"{slug} came back with a {context}-token window")
        checks.append(f"all {len(filtered)} rows satisfy the filters they were selected by")

        # 3 — the fields the records read are there, on a real row.
        first = filtered[0]
        pricing = first.get("pricing", {})
        for field, holder, kind in (
            ("id", first, str),
            ("context_length", first, (int, type(None))),
            ("prompt", pricing, str),
            ("completion", pricing, str),
        ):
            if field not in holder:
                raise AssertionError(f"{first.get('id')} has no {field}")
            if not isinstance(holder[field], kind):
                raise AssertionError(
                    f"{first.get('id')}.{field} is {type(holder[field]).__name__}, expected {kind}"
                )
        checks.append(f"row fields present and typed as expected on {first.get('id')}")

        # 4 — the single-model path, which the Test button uses.
        author, _, name = KNOWN_SLUG.partition("/")
        one = get(f"model/{author}/{name}")
        entry = one.get("data", one)
        if entry.get("id") != KNOWN_SLUG:
            raise AssertionError(f"/model/{KNOWN_SLUG} answered for {entry.get('id')!r}")
        if "architecture" not in entry or "supported_parameters" not in entry:
            raise AssertionError(f"/model/{KNOWN_SLUG} is missing architecture or "
                                 "supported_parameters")
        checks.append(f"single-model lookup resolves {KNOWN_SLUG}")

        # 5 — the entitlement list the failure branch consults.
        mine = rows(get("models/user?limit=1000"))
        if mine and not any("id" in m for m in mine):
            raise AssertionError("/models/user returned rows carrying no id")
        checks.append(f"per-key model list answers with {len(mine)} models")

    except Unreachable as e:
        print(f"Could not ask OpenRouter: {e}", file=sys.stderr)
        print("That is a network or credentials problem, not a contract failure.", file=sys.stderr)
        return 3
    except AssertionError as e:
        for line in checks:
            print(f"  ok   {line}")
        print(f"  FAIL {e}", file=sys.stderr)
        return 1

    for line in checks:
        print(f"  ok   {line}")
    print(f"{len(checks)} contract assertions hold against {BASE}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
