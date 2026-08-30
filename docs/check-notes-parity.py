#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""CLAUDE.md is the map; `docs/notes/` holds the rationale. Neither repeats the other.

CLAUDE.md says so in its own second paragraph — "This file is the map. The rationale lives in
`docs/notes/`" — and #316 split it on that rule, 505 KB down to 21 KB. Nothing enforced it, and it
grew back: measured before this check existed, **382 KB of CLAUDE.md's 487 KB were lines byte-
identical to a note**, 272 of them, and the file was within 3 % of its pre-split size. The split
had not been reversed by a decision; it had simply eroded, one paragraph at a time, because
writing into both files is the path of least resistance when you are editing both.

Two costs, and the second is why this is a check rather than a habit. CLAUDE.md is loaded into
every session's context and the notes are not — that asymmetry is the entire point of the split,
so duplication is paid on every session forever. And prose maintained by hand in two places
drifts: at the time of writing, seventeen substantial lines existed in a note with no counterpart
in the map, including a paragraph #323 had added to both. A conflict resolution on #325 also
deleted a paragraph from both files while its code stayed, and nothing would have reported it —
content that lives in one place cannot be dropped from one copy.

The rule is one-directional and deliberately narrow: **no substantial line of CLAUDE.md may be
byte-identical to a line of `docs/notes/`.** The reverse is not checked, because a note is
*supposed* to hold more than the map. Short lines are ignored — headings, list scaffolding, code
fences and command lines legitimately repeat, and comparing them would produce noise rather than
findings. What is caught is a paragraph of rationale living in both files, which is the only shape
this erosion has ever taken.

Fixing a finding means deleting the line from CLAUDE.md, not from the note. If the map genuinely
needs to say something, it should say it in its own words — a pointer at the note that expands it.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP = ROOT / "CLAUDE.md"
NOTES = ROOT / "docs/notes"

# Below this, a repeated line is scaffolding rather than prose. Measured: at 200 the finding set
# is exactly the duplicated rationale; lowering it sweeps in shared code fences and bullet stubs.
SUBSTANTIAL = 200


def main() -> int:
    if not MAP.exists() or not NOTES.is_dir():
        print("check-notes-parity: CLAUDE.md or docs/notes/ is missing", file=sys.stderr)
        return 1

    owner: dict[str, str] = {}
    for note in sorted(NOTES.glob("*.md")):
        for line in note.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if len(s) >= SUBSTANTIAL:
                owner.setdefault(s, note.name)

    if not owner:
        print("check-notes-parity: no substantial prose found in docs/notes/ — has it moved?",
              file=sys.stderr)
        return 1

    dupes, examined = [], 0
    for n, line in enumerate(MAP.read_text(encoding="utf-8").splitlines(), 1):
        s = line.strip()
        if len(s) < SUBSTANTIAL:
            continue
        examined += 1
        if s in owner:
            dupes.append((n, owner[s], s))

    for n, note, s in dupes[:20]:
        print(f"  ✗ CLAUDE.md:{n} repeats docs/notes/{note} verbatim — "
              f"delete it from the map, or say it in the map's own words")
        print(f"      {s[:120]}…")
    if len(dupes) > 20:
        print(f"  … and {len(dupes) - 20} more")

    if dupes:
        wasted = sum(len(s) + 1 for _, _, s in dupes)
        print(f"\n{len(dupes)} duplicated line(s), {wasted} bytes carried in both files "
              f"and loaded into every session's context.")
        return 1

    print(f"{examined} substantial line(s) in CLAUDE.md, none repeating docs/notes/")
    print(f"CLAUDE.md is {MAP.stat().st_size} bytes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
