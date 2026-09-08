#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Resolve every MCP agent scenario against the seeder and against the tools that exist.

`src/test/resources/eval/agent/*.yaml` describes what an agent is asked to do and what the harness
then asserts. Two of those descriptions are claims about the world, and both decay silently.

1.  **`fixture.requires` claims the seeded cluster holds a topic and a key.** This is the exact
    argument `check-eval-fixture.py` was written for, one directory over: a fixture that has
    drifted from the dataset it names does not fail, it evaluates the wrong thing, confidently. A
    scenario asking about `ORD-107` after the seeder stopped producing it still runs, still calls a
    model, and still renders a verdict — about nothing.

2.  **`trace.mustCall` / `mustNotCall` name MCP tools.** A tool that was renamed leaves a
    `mustCall` nobody satisfies, which reads as "the agent never called it" — a red scenario
    blaming a model for a rename. `mustNotCall` decays the other way and is worse: a forbidden tool
    that no longer exists can never be called, so the assertion passes for ever having stopped
    asserting. The tool names are resolved against the `@McpTool(name = "…")` declarations in
    `src/main/java/.../mcp/tools/`, which is where they are actually defined.

A third check is about the scenarios as a set: **`expectRefusal` and `serverConfig` have to agree**.
A scenario that expects `-32041` without narrowing a scope prefix is expecting a guard nothing
armed, and it fails as "the guard did not fire" — a true sentence about a scenario that was never
capable of firing it.

No network, no broker, no build: the same shape as its eleven neighbours, discovered by the same
`for check in docs/check-*.py` loop in `ci.yml`.

Exit code 1 and what is wrong, or 0 and a count.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "src/test/resources/eval/agent"
SEEDER = ROOT / "setup-demo.sh"
TOOL_SOURCES = ROOT / "src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/tools"

# Which setting arms which refusal. The harness cannot infer this and the scenario author can get
# it wrong in a way that stays green-adjacent: an unarmed guard reports "it did not fire", which
# reads as a server defect rather than a scenario defect.
GUARD_SETTINGS = {
    -32041: ("explorer.mcp.allowed-topic-prefixes", "explorer.mcp.allowed-group-prefixes"),
    -32042: ("explorer.mcp.approval-required-tools",),
    -32029: ("explorer.mcp.rate-limit.calls-per-minute", "explorer.mcp.rate-limit.burst"),
    -32044: ("explorer.mcp.tools.denied",),
}


def scenarios() -> list[pathlib.Path]:
    return sorted(SCENARIOS.glob("*.yaml")) if SCENARIOS.is_dir() else []


def declared_tools() -> set[str]:
    """Every `@McpTool(name = "kex_…")` in the tools package — where the names are defined."""
    names: set[str] = set()
    for source in TOOL_SOURCES.rglob("*.java"):
        names.update(re.findall(r'@McpTool\(\s*name\s*=\s*"([a-z0-9_]+)"',
                                source.read_text(encoding="utf-8")))
    return names


def read_list(body: str, key: str) -> list[str]:
    """The quoted entries of a flow list (`key: ["a", "b"]`) or of a block list under `key:`.

    Hand-rolled rather than a YAML parse, and deliberately: this check must run with nothing
    installed, exactly like its eleven neighbours, and the scenario format uses one shape per key.
    A scenario written in a shape this cannot read is caught by the loader instead, which parses
    the file for real and refuses an unknown key.
    """
    flow = re.search(rf"^\s*{re.escape(key)}\s*:\s*\[(.*?)\]\s*$", body, re.MULTILINE)
    if flow:
        return re.findall(r'"([^"]+)"', flow.group(1))
    block = re.search(rf"^(\s*){re.escape(key)}\s*:\s*$\n((?:\1[ ]+-.*\n)+)", body, re.MULTILINE)
    if block:
        return [m.strip().strip('"') for m in re.findall(r"^\s*-\s*(.+)$", block.group(2),
                                                         re.MULTILINE)]
    return []


def main() -> int:
    files = scenarios()
    if not files:
        # Not a failure: a harness under construction legitimately has no scenario yet, and a check
        # that fails on an empty directory is one somebody deletes rather than satisfies.
        print("  · no agent scenario to resolve yet "
              f"({SCENARIOS.relative_to(ROOT)} is empty or absent)")
        return 0
    if not SEEDER.exists():
        print(f"  ✗ the seeder is missing: {SEEDER.relative_to(ROOT)}")
        return 1

    seeder = SEEDER.read_text(encoding="utf-8")
    tools = declared_tools()
    if not tools:
        print(f"  ✗ no @McpTool name found under {TOOL_SOURCES.relative_to(ROOT)} — this check "
              "would pass by resolving every scenario against an empty set")
        return 1

    problems: list[str] = []
    resolved = 0

    for path in files:
        body = path.read_text(encoding="utf-8")
        name = path.name

        for topic in read_list(body, "topics"):
            resolved += 1
            if topic not in seeder:
                problems.append(f"{name}: fixture topic '{topic}' is not seeded by setup-demo.sh")

        for key in read_list(body, "keys"):
            resolved += 1
            if key not in seeder:
                problems.append(f"{name}: fixture key '{key}' is not produced by setup-demo.sh")

        for called in read_list(body, "mustCall") + read_list(body, "mustNotCall"):
            resolved += 1
            if called not in tools:
                problems.append(f"{name}: '{called}' is not a registered MCP tool "
                                f"(known: {', '.join(sorted(tools))})")

        resume = re.search(r"^\s*onResumeTokenReturned\s*:\s*mustCall\((\w+)\)\s*$",
                           body, re.MULTILINE)
        if resume:
            resolved += 1
            if resume.group(1) not in tools:
                problems.append(f"{name}: onResumeTokenReturned names '{resume.group(1)}', "
                                "which is not a registered MCP tool")

        refusal = re.search(r"^expectRefusal\s*:\s*(-?\d+)\s*$", body, re.MULTILINE)
        if refusal:
            resolved += 1
            code = int(refusal.group(1))
            settings = GUARD_SETTINGS.get(code)
            if settings is None:
                problems.append(f"{name}: expectRefusal {code} is not a code this check knows how "
                                "to see armed — add it to GUARD_SETTINGS with the setting that "
                                "arms it")
            elif not any(setting in body for setting in settings):
                problems.append(f"{name}: expects {code} but serverConfig arms none of "
                                f"{', '.join(settings)}, so the guard cannot fire")

    if problems:
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1

    print(f"{resolved} scenario claim(s) resolved across {len(files)} agent scenario(s)")
    print("All resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
