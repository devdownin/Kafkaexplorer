#!/usr/bin/env python3
"""Reject obsolete GitHub source URLs after the agent repository rename."""
import re
import subprocess
from pathlib import Path

root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
obsolete = re.compile(r"github\.com/devdownin/kex-agent-(?:ai|ia)(?![a-z0-9_-])", re.IGNORECASE)
failures = []
for name in filter(None, paths):
    path = root / name
    if not path.is_file() or path.is_symlink():
        continue
    try:
        content = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError:
        continue
    for number, line in enumerate(content.splitlines(), 1):
        if obsolete.search(line):
            failures.append(f"{name}:{number}: obsolete GitHub URL; use devdownin/Kex-anHarness")
if failures:
    print("\n".join(failures))
    raise SystemExit(1)
print("GitHub source links use the renamed agent repository.")
