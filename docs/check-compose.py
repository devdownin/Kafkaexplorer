#!/usr/bin/env python3
"""Resolve what the bundled compose stacks claim against what they actually set.

Three questions, all answerable offline, none of them answerable by `docker compose
config` — which validates syntax and says nothing about whether a file agrees with the
documentation beside it or with itself.

1.  Every `${VAR:-default}` a stack reads is documented in `.env.example`, with a default
    the stacks really use.  That file exists so changing where the stack is published does
    not mean editing six compose files, which only works if it lists them all: six
    variables had a default in compose and no line there, and nothing noticed, because
    `check-config-table.py` reads `application.yml` and the Dockerfiles rather than the
    compose files.

2.  Nothing in `.env.example` is dead.  A documented knob no stack reads is worse than an
    undocumented one: it invites an operator to set a value that changes nothing.

3.  The prompt budget fits the window the stack serves.  `PROCESS_MINING_PROMPT_CHAR_BUDGET`
    and the engine's context length are two halves of one setting, written in three files,
    each carrying a comment saying it is "kept in step" with the others — which is the
    shape this repository keeps removing, since nothing executes a comment.  What a model
    cannot see it does not say it missed: Ollama drops the oldest messages until the prompt
    fits and logs it at DEBUG, so exceeding the window is silent by construction.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_EXAMPLE = ROOT / '.env.example'

# Characters per token. Deliberately crude and deliberately optimistic: the real ratio for
# JSON-ish payloads is worse than 4, so a budget this says fits may still not, while one it
# rejects certainly does not. The check is a floor, not a calibration.
CHARS_PER_TOKEN = 4

# A variable a compose file reads that `.env.example` is not expected to carry. Empty, and
# an entry here must earn its place — see `stale_exemptions`.
EXTERNAL: set[str] = set()


def compose_files() -> list[Path]:
    return sorted(ROOT.glob('docker-compose*.yml'))


def interpolations(text: str) -> list[tuple[str, str | None]]:
    """Every compose interpolation in `text`, as (name, default-or-None).

    Hand-rolled rather than a regex because the forms that matter here defeat one: `$${…}`
    is a literal `$` for the shell inside an entrypoint and is NOT an interpolation, and a
    default can itself be an interpolation (`${A:-${B:-c}}`), which `[^}]*` would truncate
    at the first brace and report the wrong default for the wrong variable.
    """
    found: list[tuple[str, str | None]] = []
    i = 0
    while True:
        i = text.find('${', i)
        if i < 0:
            return found
        if i > 0 and text[i - 1] == '$':      # `$${…}` — escaped, the shell's, not ours
            i += 2
            continue
        depth, j = 0, i
        while j < len(text):
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        else:
            return found                       # unbalanced; nothing useful left to read
        body = text[i + 2:j]
        i = j + 1
        name, sep, default = body.partition(':-')
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
            continue
        found.append((name, default if sep else None))


def documented() -> dict[str, str]:
    """`.env.example` as {NAME: value}, commented-out lines included — a knob shown as a
    comment is still documented, and that is how the optional ones are presented.

    At most ONE space after the `#`, which is the difference between a declaration a reader
    can uncomment (`# LLM_CHAT_EXTRA_ARGS=…`) and an indented example inside the file's
    prose (`#     EXPLORER_PORT=9080 docker compose up -d`). Reading the second as a
    declaration made the check report the whole shell line as that variable's default.
    """
    out: dict[str, str] = {}
    for line in ENV_EXAMPLE.read_text(encoding='utf-8').splitlines():
        m = re.match(r'^(?:# ?)?([A-Z][A-Z0-9_]*)=(.*)$', line)
        if m:
            out.setdefault(m.group(1), m.group(2).strip())
    return out


def number(raw: str) -> int | None:
    return int(raw) if re.fullmatch(r'\d+', raw or '') else None


def budget_vs_window(path: Path, text: str) -> tuple[str | None, int]:
    """(problem, checks-made) for one stack's prompt budget against its served window."""
    defaults = {name: default for name, default in interpolations(text) if default is not None}

    def setting(env_key: str) -> int | None:
        """The value a stack ends up with for `KEY=${VAR:-n}` — the default, since that is
        what an operator who sets nothing gets, and the only value this file can know."""
        m = re.search(re.escape(env_key) + r'=\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}', text)
        if m:
            return number(m.group(2)) if m.group(2) is not None else None
        m = re.search(re.escape(env_key) + r'=(\d+)', text)
        return number(m.group(1)) if m else None

    chars = setting('PROCESS_MINING_PROMPT_CHAR_BUDGET')
    if chars is None:
        return None, 0

    # Ollama serves one request against the whole window; llama-server splits it across
    # `--parallel` slots, so the window a single request gets is the quotient. A stack that
    # names no window (the developer SpectraLLM one) takes it from a checkout this file
    # cannot read — not checkable rather than assumed fine.
    window = setting('OLLAMA_CONTEXT_LENGTH')
    slots = 1
    if window is None:
        window = setting('LLM_CONTEXT')
        slots = number(defaults.get('LLM_PARALLEL', '')) or 1
    if window is None:
        return None, 0

    per_request = window // slots
    tokens = chars // CHARS_PER_TOKEN
    if tokens > per_request:
        return (f'{path.name}: PROCESS_MINING_PROMPT_CHAR_BUDGET={chars} is ~{tokens} tokens, '
                f'more than the {per_request} a request gets ({window} context'
                + (f' / {slots} slots' if slots > 1 else '')
                + ') — the excess is dropped in silence, not refused'), 1
    return None, 1


def stale_exemptions(read_by_compose: set[str]) -> list[str]:
    """An escape hatch that no longer suppresses anything is a standing permit on a claim
    nothing checks. Same rule the other doc checks apply to their own exemption lists."""
    return [f'.env.example: `{var}` is exempted as EXTERNAL but the compose files do read it '
            f'— drop the exemption' for var in sorted(EXTERNAL & read_by_compose)]


def check() -> list[str]:
    problems: list[str] = []
    checked = 0

    used: dict[str, set[str]] = {}
    used_in: dict[str, set[str]] = {}
    for path in compose_files():
        text = path.read_text(encoding='utf-8')
        for name, default in interpolations(text):
            used_in.setdefault(name, set()).add(path.name)
            if default is not None:
                used.setdefault(name, set()).add(default)
            else:
                used.setdefault(name, set())

    docs = documented()

    for name in sorted(used):
        if name in EXTERNAL:
            continue
        checked += 1
        if name not in docs:
            where = ', '.join(sorted(used_in[name])[:3])
            problems.append(
                f'.env.example: `{name}` is read by {where} and documented nowhere — an '
                f'operator cannot set what no file mentions')
            continue
        # A default that is itself an interpolation (`${A:-${B:-c}}`) names where the
        # value comes from rather than being one; there is nothing to compare it against,
        # and `.env.example` rightly leaves such a knob empty so the fallback applies.
        stated = docs[name]
        actual = {d for d in used[name] if '${' not in d}
        # "One of the defaults the stacks use", not "the default": a variable can carry a
        # different default in an overlay on purpose (LLM_EMBED_EXTRA_ARGS is empty in the
        # base and `--n-gpu-layers 99` in the GPU one). Where a variable has a single
        # default — the ordinary case — this is an exact comparison.
        if actual and stated not in actual:
            problems.append(
                f'.env.example: `{name}` is documented as `{stated}`, the compose files '
                f'default it to {" / ".join("`%s`" % d for d in sorted(actual))}')

    for name in sorted(docs):
        if name not in used and name not in EXTERNAL:
            checked += 1
            problems.append(
                f'.env.example: `{name}` is documented but no compose file reads it — '
                f'setting it changes nothing')

    for path in compose_files():
        problem, made = budget_vs_window(path, path.read_text(encoding='utf-8'))
        checked += made
        if problem:
            problems.append(problem)

    problems.extend(stale_exemptions(set(used)))

    print(f'{checked} compose settings resolved against .env.example and against each '
          f'other, across {len(compose_files())} files')
    return problems


if __name__ == '__main__':
    found = check()
    for problem in found:
        print(f'  ✗ {problem}', file=sys.stderr)
    if found:
        print(f'\n{len(found)} compose claim(s) that do not hold.', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
