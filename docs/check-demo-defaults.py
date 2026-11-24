#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Hold the demo launchers' defaults to the compose files' and to each other.

`demo.sh` and `demo.ps1` used to restate nineteen defaults the compose files already carry, and
three of the copies went stale: the full demo ran `kex-agent-ai` 0.10.0 and SpectraLLM 0.12.3 for a
month after the compose files — which every CI job exercises — had moved to 0.20.0. Nothing noticed,
because a launcher's value silently wins over a compose `${VAR:-default}`: the compose file looks
right and the stack runs something else.

Three rules, all offline, on files already in the repository.

1. **A launcher default is an override or something the launcher reads — never a copy.** A variable
   whose compose default is the very value the launcher sets, and which neither launcher uses
   itself (for a message, a wait, a request), is a second copy that can only drift. Drop it: the
   compose file already says it.
2. **A launcher default that differs from a compose default is declared.** `OVERRIDES` below names
   each one with the value it must have and why. A new difference fails until somebody writes the
   reason down, which is the whole point: the stale tags would have failed here the day the compose
   pins moved.
3. **The twins agree.** `demo.sh` and `demo.ps1` set the same variables to the same values. An empty
   default is left unset on Windows (`$env:X = ''` removes the variable), so `demo.sh` may carry one
   that `demo.ps1` does not.

An `OVERRIDES` entry that no longer overrides anything — the launcher dropped it, or the compose
default now agrees — fails as well: a standing exemption nothing checks is a permit that outlives
its reason.

Usage: `python3 docs/check-demo-defaults.py [ROOT]` (ROOT defaults to the repository).
"""
import collections
import glob
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent

# variable -> (the value the launcher sets, why the compose default is not good enough for the demo)
OVERRIDES: dict[str, tuple[str, str]] = {
    "KEX_AGENT_LLM_PROVIDER": ("openai", "the demo's model is OpenRouter, which the agent reaches as `openai`; compose defaults to Anthropic"),
    "SCHEMA_REGISTRY_PORT": ("8085", "8081 belongs to the agent in the shared port plan, so Schema Registry moves aside"),
    "EXPLORER_ASSISTANT_ENABLED": ("false", "the forecast demo has no model for the assistant unless --agent brings one; compose defaults it on"),
    "EXPLORER_MCP_AUTH_TOKEN": ("dev-only-mcp-token", "forecasts.yml leaves the bearer empty (503); the demo needs the one the agent presents"),
    "SPECTRA_API_KEY": ("dev-only-spectra-key", "SpectraLLM's MCP server refuses to run without a key, and a file that defaults it empty would leave it down"),
    "TIMESTRAL_SHADOW_ENABLED": ("false", "the demo scores no Timestral shadow; forecast-offline.yml would turn it on"),
    "EXPLORER_IMAGE_TAG": ("latest", "the prebuilt forecast demo follows the published images; every other stack keeps the compose pin"),
    "FORECAST_IMAGE_TAG": ("latest", "same: the prebuilt forecast demo follows the published image"),
    "KEX_AGENT_IMAGE_TAG": ("latest", "same: the prebuilt forecast demo with --agent follows the published image"),
}

# present in one launcher only, with the reason
TWIN_EXCEPTIONS: dict[str, str] = {
    "DEMO_FORECAST_READY_TIMEOUT": "demo.sh inlines the 900 s default where it waits; demo.ps1 names it once",
}

COMPOSE_FILES = ["docker-compose.yml"] + sorted(
    str(Path(p).relative_to(ROOT)) for p in glob.glob(str(ROOT / "compose" / "*.yml")))


def compose_defaults() -> dict[str, set[str]]:
    """Every `${VAR:-default}` a compose file carries, by variable, as the set of its defaults.

    A default holding another `${…}` is skipped: it names a chain, not a value.
    """
    found: dict[str, set[str]] = collections.defaultdict(set)
    for name in COMPOSE_FILES:
        for line in (ROOT / name).read_text().splitlines():
            if line.lstrip().startswith("#"):
                continue
            for m in re.finditer(r"\$\{(\w+):-", line):
                i, depth, j = m.end(), 1, m.end()
                while j < len(line) and depth:
                    if line.startswith("${", j):
                        depth, j = depth + 1, j + 2
                        continue
                    depth -= 1 if line[j] == "}" else 0
                    j += 1
                value = line[i:j - 1]
                if "${" not in value:
                    found[m.group(1)].add(value)
    return found


class Launcher:
    """One launcher's defaults: `values[var]` is the literal values it sets (None = computed)."""

    def __init__(self, name: str, pattern: str):
        self.name = name
        self.lines = (ROOT / name).read_text().splitlines()
        self.values: dict[str, list[str | None]] = collections.defaultdict(list)
        self.default_lines: dict[str, set[int]] = collections.defaultdict(set)
        for n, line in enumerate(self.lines, 1):
            if line.lstrip().startswith("#"):
                continue
            m = re.search(pattern, line)
            if m:
                self.values[m.group(1)].append(m.group(2))
                self.default_lines[m.group(1)].add(n)

    def reads(self, var: str) -> bool:
        """Whether the launcher uses `var` anywhere but the line(s) that default it."""
        return any(
            re.search(rf"\b{var}\b", line) and not line.lstrip().startswith("#")
            for n, line in enumerate(self.lines, 1) if n not in self.default_lines.get(var, ()))


def main() -> int:
    sh = Launcher("demo.sh", r"export (\w+)=\$\{\1:-([^}]*)\}")
    ps = Launcher("demo.ps1", r"(?<![\w-])Set-Default (\w+) (?:'([^']*)'|\S)")
    compose = compose_defaults()
    problems: list[str] = []

    # 1 and 2: each launcher value against the compose defaults.
    used_overrides: set[str] = set()
    for launcher in (sh, ps):
        for var, values in sorted(launcher.values.items()):
            for value in {v for v in values if v is not None}:
                theirs = compose.get(var)
                if theirs is None:
                    continue  # the launcher's own variable (DEMO_*, COMPOSE_PROJECT_NAME)
                if theirs != {value}:
                    declared = OVERRIDES.get(var)
                    if declared and declared[0] == value:
                        used_overrides.add(var)
                    elif declared:
                        problems.append(f"{launcher.name}: `{var}` is set to `{value}` but its declared override is "
                                        f"`{declared[0]}` — fix the launcher or the declaration")
                    else:
                        problems.append(
                            f"{launcher.name}: `{var}` is set to `{value}` while the compose files default it to "
                            f"{sorted(theirs)} — if the demo means to override it, declare it in OVERRIDES with the "
                            f"reason; if not, drop it from the launcher and let the compose default stand")
                elif not (sh.reads(var) or ps.reads(var)):
                    problems.append(
                        f"{launcher.name}: `{var}` repeats the compose default `{value}` and neither launcher "
                        f"reads it — a second copy that can only drift; drop it from both launchers")

    for var, (value, _) in sorted(OVERRIDES.items()):
        if var not in used_overrides:
            problems.append(f"OVERRIDES: `{var}` is declared as `{value}` but no launcher overrides a compose "
                            f"default with it any more — drop the entry")

    # 3: the twins.
    for var in sorted(set(sh.values) ^ set(ps.values)):
        only_sh = var in sh.values
        if var in TWIN_EXCEPTIONS:
            continue
        if only_sh and set(sh.values[var]) == {""}:
            continue  # an empty default stays unset on Windows
        problems.append(f"`{var}` is defaulted in {'demo.sh' if only_sh else 'demo.ps1'} only — the twins are edited "
                        f"together (an empty default may stay out of demo.ps1)")
    for var in sorted(set(sh.values) & set(ps.values)):
        left = {v for v in sh.values[var] if v is not None}
        right = {v for v in ps.values[var] if v is not None}
        if left and right and left != right:
            problems.append(f"`{var}` differs between the twins: demo.sh {sorted(left)}, demo.ps1 {sorted(right)}")
    for var in sorted(TWIN_EXCEPTIONS):
        if (var in sh.values) == (var in ps.values):
            problems.append(f"TWIN_EXCEPTIONS: `{var}` is now in both launchers or neither — drop the exception")

    if problems:
        for problem in problems:
            print(f"  ✗ {problem}")
        print(f"\n{len(problems)} demo default problem(s).")
        return 1
    checked = len(set(sh.values) | set(ps.values))
    print(f"{checked} demo launcher defaults checked against {len(COMPOSE_FILES)} compose files: "
          f"{len(used_overrides)} declared overrides, no copies, the twins agree.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
