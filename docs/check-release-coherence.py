#!/usr/bin/env python3
"""Check the pinned Explorer release against Compose, env and changelog links.

No registry call or newest-tag comparison: a freshly published tag legitimately
precedes its follow-up PR, and that should not break unrelated pull requests.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = [ROOT / "compose/image.yml", ROOT / "compose/spectra-hub.yml"]
PIN = re.compile(r"EXPLORER_IMAGE_TAG:-([0-9]+\.[0-9]+\.[0-9]+)")
VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def check() -> list[str]:
    errors = []
    env = (ROOT / ".env.example").read_text()
    pins = re.findall(r"(?m)^EXPLORER_IMAGE_TAG=(\S+)$", env)
    if len(pins) != 1 or not VERSION.fullmatch(pins[0]):
        return ["expected exactly one semver EXPLORER_IMAGE_TAG in .env.example"]
    current = pins[0]
    for file in COMPOSE:
        values = PIN.findall(file.read_text())
        if len(values) != 1 or values[0] != current:
            errors.append(f"{file.relative_to(ROOT)}: expected exactly one Explorer pin {current}, got {values}")
    changelog = (ROOT / "CHANGELOG.md").read_text()
    sections = re.findall(r"(?m)^## \[(\d+\.\d+\.\d+)\](?:\s|$)", changelog)
    if not sections or sections[0] != current:
        errors.append(f"CHANGELOG.md: latest released section must be [{current}]")
    unreleased_links = re.findall(r"(?m)^\[Unreleased\]:\s+\S+/compare/v(\d+\.\d+\.\d+)\.\.\.HEAD\s*$", changelog)
    if unreleased_links != [current]:
        errors.append(f"CHANGELOG.md: [Unreleased] comparison must start at v{current}")
    current_links = re.findall(rf"(?m)^\[{re.escape(current)}\]:\s+\S+", changelog)
    if len(current_links) != 1:
        errors.append(f"CHANGELOG.md: [{current}] must have exactly one comparison link")
    return errors


if __name__ == "__main__":
    problems = check()
    for issue in problems:
        print(f"release coherence: {issue}", file=sys.stderr)
    if problems:
        sys.exit(1)
    print("release coherence: Compose, env, latest changelog section and comparison agree")
