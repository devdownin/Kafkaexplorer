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
    OPENROUTER_API_KEY=sk-or-v1-… python3 docs/verify-openrouter-contract.py --chat

`--chat` adds the completion checks, which make **real, billed calls** (sixteen output tokens on a
cheap model — a fraction of a cent, but not nothing). They are opt-in for that reason and for no
other: they cover the half of the contract that matters most, because the body of a completion is
where this application sends the fields nobody has ever verified are read.

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
  6. (`--chat`) `usage.cost` — is it returned by default, or only when the request asks for it?
     `LlmUsage.costUsd` reads that field and `claude.session-cost-limit-usd` is enforced from it,
     so if it has to be asked for, every cost this application prints on its own default provider
     is null and the live session tells the operator the provider reports none — which would be
     false about OpenRouter rather than merely unknown. This is the one assertion that can change
     what the client sends.
  7. (`--chat`) the `provider` routing object is accepted rather than ignored. It carries the
     data-collection restriction the Settings banner states as a property; an ignored field would
     make that sentence a claim about nothing.

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
# The model the --chat section actually calls. Separate from KNOWN_SLUG on purpose: that one only
# has to exist in the catalogue, this one is billed.
CHAT_SLUG = os.environ.get("OPENROUTER_CHAT_SLUG", KNOWN_SLUG)


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


def post(path: str, body: dict) -> tuple[int, dict]:
    """One POST, returning the status beside the parsed body.

    Unlike `get`, a 4xx is *not* Unreachable here: it is very often the answer under test — whether
    the gateway accepts a field this application sends is exactly what a 400 answers. Only a
    transport failure means we could not ask.
    """
    request = urllib.request.Request(
        f"{BASE}/{path}",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {KEY}",
            # The two attribution headers the client sends, so this exercises the same request.
            "HTTP-Referer": "https://github.com/devdownin/Kafkaexplorer",
            "X-Title": "Kafka SQL Explorer",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except (json.JSONDecodeError, ValueError):
            return e.code, {}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        raise Unreachable(f"{type(e).__name__} for /{path}: {e}") from e


def chat_body(**extra) -> dict:
    """The smallest request shaped like the ones `OpenAiCompatibleLlmClient` sends."""
    body = {
        "model": CHAT_SLUG,
        "messages": [
            {"role": "system", "content": "Reply with the single word ok."},
            {"role": "user", "content": "ok"},
        ],
        "max_tokens": 16,
        "temperature": 0.0,
        "stream": False,
    }
    body.update(extra)
    return body


def verify_chat() -> list[str]:
    """
    The half the catalogue checks do not cover: what the *completion* request may contain.

    Everything the client puts in that body beyond the OpenAI basics — `provider`, `usage`,
    eventually `reasoning` — rides on the same assumption the filters did: that a field the
    gateway does not recognise is refused rather than ignored. It is the wrong way round. An
    ignored field costs nothing and says nothing, so a privacy policy that is not applied and an
    accounting field that is not returned both look exactly like success.

    The most consequential of them is the money. `LlmUsage.costUsd` reads `usage.cost` from the
    response, and `claude.session-cost-limit-usd` is enforced from it — if that field only appears
    when the request asks for it, then on the provider this application ships pointed at, every
    analysis reports no cost and the live session announces that *the provider reports none*, which
    would be false about OpenRouter rather than merely unknown.

    These calls are real completions and they are billed. Sixteen output tokens on a cheap model is
    a fraction of a cent, but it is not nothing, which is why this section is opt-in.
    """
    checks: list[str] = []

    # 1 — cost accounting: is `usage.cost` there by default, or only when asked for?
    status, plain = post("chat/completions", chat_body())
    if status != 200:
        raise AssertionError(
            f"a plain completion on {CHAT_SLUG} answered HTTP {status}: {plain} — nothing below "
            "can be concluded until the simplest request works"
        )
    cost_by_default = "cost" in plain.get("usage", {})

    status, asked = post("chat/completions", chat_body(usage={"include": True}))
    if status != 200:
        raise AssertionError(
            f"`usage: {{include: true}}` was refused with HTTP {status}: {asked}. The client must "
            "not send it."
        )
    cost_when_asked = "cost" in asked.get("usage", {})

    if cost_by_default:
        checks.append("usage.cost is returned by default — the client is right to read it as it does")
    elif cost_when_asked:
        raise AssertionError(
            "usage.cost is returned ONLY when the request carries `usage: {include: true}`, which "
            "OpenAiCompatibleLlmClient does not send. Every cost this application shows on "
            "OpenRouter is therefore null, and claude.session-cost-limit-usd cannot be enforced — "
            "while the live session tells the operator the provider reports no cost. Send the "
            "field for OPENROUTER, beside the `provider` routing object."
        )
    else:
        raise AssertionError(
            "usage.cost was absent both by default and with `usage: {include: true}` — the field "
            "LlmUsage.costUsd reads has moved or is gated some other way. Find where it went "
            "before trusting any money figure this application prints."
        )

    # 2 — the routing policy is accepted, not ignored as an unknown field.
    status, routed = post("chat/completions", chat_body(provider={"data_collection": "deny"}))
    if status not in (200, 404):
        raise AssertionError(
            f"the provider routing object was answered with HTTP {status}: {routed}. This "
            "application sends it on every call, and a privacy restriction that is refused is one "
            "nobody is applying."
        )
    checks.append(
        "provider.data_collection=deny is accepted"
        + (" (and this model routes under it)" if status == 200
           else f" (and {CHAT_SLUG} does not route under it — a 404, exactly as "
                "explainRoutingRefusal describes)")
    )

    # 3 — the price ceiling is accepted too. Same exposure as the policy above, and the same
    #     consequence if it is ignored: a bound nobody is applying, which reads as one that holds.
    status, capped = post("chat/completions", chat_body(
        provider={"max_price": {"prompt": 1000, "completion": 1000}}))
    if status not in (200, 404):
        raise AssertionError(
            f"provider.max_price was answered with HTTP {status}: {capped}. "
            "claude.openrouter-max-price-usd-per-million sends it."
        )
    checks.append("provider.max_price is accepted (a ceiling far above any real price, so it routes)")

    # 4 — and a ceiling below every price refuses the route rather than being ignored. This is the
    #     assertion that separates "the field is read" from "the field is tolerated": a 200 here
    #     would mean the ceiling never binds, whatever it is set to.
    status, floored = post("chat/completions", chat_body(
        provider={"max_price": {"prompt": 0.0000001, "completion": 0.0000001}}))
    if status == 200:
        raise AssertionError(
            "a max_price below every published price still routed — the ceiling is being ignored, "
            "so claude.openrouter-max-price-usd-per-million promises a bound it does not deliver"
        )
    checks.append(f"a max_price below every price refuses the route (HTTP {status}), so it binds")

    # 5 — the model that answered is named, which is what tells a relayed failure from our own.
    if isinstance(plain.get("provider"), str):
        checks.append(f"the response names its upstream provider ({plain['provider']})")

    return checks


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

        # 5 — the entitlement list the failure branch consults, and what it actually means.
        #
        # The code uses it on one failure branch only, to tell "your key cannot reach this model"
        # from "no such model". Whether it could do more — mark every row of the shortlist — turns
        # on a question nothing has asked: is it the models the key is *entitled to*, or some
        # narrower list? Marking rows against the second would label usable models unusable, which
        # is a worse lie than the silence it replaces, so this reports the relationship rather than
        # assuming it.
        mine = rows(get("models/user?limit=1000"))
        if mine and not any("id" in m for m in mine):
            raise AssertionError("/models/user returned rows carrying no id")
        mine_ids = {m.get("id") for m in mine if m.get("id")}
        every_ids = {m.get("id") for m in every if m.get("id")}
        if not mine_ids <= every_ids:
            raise AssertionError(
                "/models/user lists models that are not in /models at all "
                f"({sorted(mine_ids - every_ids)[:3]}…) — it is not the subset the code assumes"
            )
        shape = ("the whole catalogue" if mine_ids == every_ids
                 else f"a subset ({len(mine_ids)} of {len(every_ids)})")
        checks.append(f"per-key model list answers with {len(mine)} models — {shape}")
        if mine_ids == every_ids:
            checks.append(
                "  → this key sees everything, so the shortlist has nothing to mark. Run this "
                "again with a restricted org key before trusting a per-row entitlement marker."
            )

        # 6 — the completion request itself. Opt-in: these are billed calls.
        if "--chat" in sys.argv:
            checks.extend(verify_chat())
        else:
            checks.append("chat/completions not checked (pass --chat; it makes billed calls)")

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
