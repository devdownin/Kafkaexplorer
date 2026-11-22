#!/usr/bin/env python3
"""Validate CHANGELOG.md section headings against released comparison links."""
from collections import Counter
from pathlib import Path
import re
import sys

CHANGELOG = Path(__file__).resolve().parents[1] / "CHANGELOG.md"
HEADINGS = re.compile(r"^## \[([^\]]+)\](?:\s|$)", re.MULTILINE)
LINKS = re.compile(r"^\[(\d+\.\d+\.\d+)\]:[ \t]+\S+", re.MULTILINE)
VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def validate(text: str) -> list[str]:
    headings = [m.group(1) for m in HEADINGS.finditer(text)]
    versions = Counter(h for h in headings if VERSION.fullmatch(h))
    links = Counter(m.group(1) for m in LINKS.finditer(text))
    errors = []
    if headings.count("Unreleased") != 1:
        errors.append(f"expected one ## [Unreleased], found {headings.count('Unreleased')}")
    for version, count in sorted(links.items()):
        if versions[version] != 1:
            errors.append(f"[{version}] link requires exactly one ## [{version}] section (found {versions[version]})")
        if count != 1:
            errors.append(f"duplicate [{version}] link ({count} occurrences)")
    for version, count in sorted(versions.items()):
        if count != 1:
            errors.append(f"duplicate ## [{version}] section ({count} occurrences)")
    return errors


if __name__ == "__main__":
    errors = validate(CHANGELOG.read_text(encoding="utf-8"))
    for error in errors:
        print(f"CHANGELOG.md: {error}", file=sys.stderr)
    if errors:
        sys.exit(1)
    print("CHANGELOG.md: all released links have unique headings; one Unreleased section")
