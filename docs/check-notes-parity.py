#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""CLAUDE.md is the map; `docs/notes/` holds the rationale. No prose is maintained twice.

CLAUDE.md says so in its own second paragraph — "This file is the map. The rationale lives in
`docs/notes/`" — and #316 split it on that rule, 505 KB down to 21 KB. Nothing enforced it, and it
grew back: measured before this check existed, **382 KB of CLAUDE.md's 487 KB were lines byte-
identical to a note**, 272 of them, and the file was within 3 % of its pre-split size. The split
had not been reversed by a decision; it had simply eroded, one paragraph at a time, because
writing into both files is the path of least resistance when you are editing both.

Two costs, and the second is why this is a check rather than a habit. CLAUDE.md is loaded into
every session's context and the notes are not — that asymmetry is the entire point of the split,
so duplication is paid on every session forever. And prose maintained by hand in two places
drifts.

THE UNIT IS A PARAGRAPH, NOT A LINE, AND THAT IS THE WHOLE OF IT. This check compared physical
lines for its first eight weeks, and the omission it left is the one it existed to prevent.
Markdown carries no line semantics: this tree writes some paragraphs as one long line and hard-
wraps others at about a hundred characters, and the two are the same prose. A wrapped paragraph is
therefore a run of lines each *under* any threshold worth setting, so every one of them was
skipped — not judged and passed, never looked at. Measured on the revision that exposed it:
**563 of CLAUDE.md's 820 non-blank lines were byte-identical to a note, and this check reported
zero**, because it was reading 28 unwrapped lines and nothing else. Re-run against that same
revision, joining each paragraph first, it reports **50 findings**. The threshold did not move and
no exemption was added — both would have been the wrong repair, and the docstring below still
forbids them. What changed is that the check now sees the prose the file is actually made of.

So a block is a run of non-blank lines joined into one string with its whitespace collapsed, which
also means a copy that was merely **re-wrapped** no longer escapes pass 1 as "not byte-identical".
Blocks end at a blank line, a heading and a table row, and each list item opens its own — without
that last rule a bullet list collapses into a single unit and one duplicated bullet hides inside
the four beside it. Fenced code is skipped outright: it is shared by construction, and the
threshold below was measured on prose.

Three passes, and the last two exist because the first one has a blind spot that cost real
accuracy twice.

**1. Byte-identical against a note.** The original rule, one-directional and deliberately narrow:
no substantial block of CLAUDE.md may be identical to a block of `docs/notes/`. The reverse is
not checked, because a note is *supposed* to hold more than the map.

**2. Near-identical against a note — a duplicate that has DRIFTED.** Pass 1 goes blind at exactly
the moment the duplication has done its damage. When #325 deleted `GET /api/query/sink-ddl` it
updated the note and left CLAUDE.md's copy still promising the endpoint was "left standing"; the
two texts had by then diverged just enough that a byte-identical comparison no longer paired
them, so nothing reported the contradiction. The same shape was found again on the paragraph
about credential masking: the note carries "that mode and its endpoint have since been removed"
and the map's copy does not, so the map still described a removed route as live. And again the
hour the unit changed: the map's action-pinning rule scored 0.67 against the note's paragraph,
having been produced by editing it down, and had already lost the clause saying Dependabot keeps
the pins current.

**3. Near-identical against ITSELF.** Passes 1 and 2 are map-versus-notes and cannot see the map
repeating itself. Two conflict resolutions landing an hour apart left the `explorer.max-concurrent-jobs`
bullet in CLAUDE.md **twice, adjacent**, at 868 and 1505 characters — the shorter one missing the
clause saying the cap cannot bind on a running deployment. Same defect class, same fix, invisible
to a cross-file comparison.

THE THRESHOLD IS MEASURED, NOT GUESSED. A genuine map-level summary of a note's paragraph shares
vocabulary with it, so the question is whether "summarised" separates cleanly from "copied and
drifted". On this tree it does, with a wide gap and nothing inside it:

    deliberately-written map-level summaries of a note's own content   0.16 - 0.24
    (nothing whatsoever between)
    drifted copies                                                     0.67 - 0.93

0.60 sits in the middle of that gap, which is what makes this safe to fail on rather than merely
warn about. If a future summary lands above it, the answer is to make the map say the thing in
its own words — not to raise the threshold, and not to add an exemption list: there is no
legitimate finding today, and an escape hatch nobody needs is the affordance this repository
keeps removing.

Fixing a finding means deleting the block from CLAUDE.md, not from the note. If the map genuinely
needs to say something, it should say it in its own words — a pointer at the note that expands it.
When a near-duplicate is reported, read the diff it prints: the half that is missing a clause is
usually the stale one, and it is usually the map.
"""
import re
import sys
from difflib import SequenceMatcher
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP = ROOT / "CLAUDE.md"
NOTES = ROOT / "docs/notes"

# Below this, a repeated block is scaffolding rather than prose. Measured: at 200 the finding set
# is exactly the duplicated rationale; lowering it sweeps in shared bullet stubs and one-line
# pointers that both files are supposed to carry.
SUBSTANTIAL = 200

# See the module docstring: summaries measure 0.16-0.24, drifted copies 0.67-0.93, nothing between.
NEAR = 0.60

# A list item opens its own block; without this a bullet list is one unit and a single duplicated
# bullet hides among its neighbours.
BULLET = re.compile(r"^\s*(?:[-*+]\s|\d+[.)]\s)")


def blocks(text: str) -> list[tuple[int, str]]:
    """Substantial paragraphs as (first line number, text with whitespace collapsed).

    Markdown has no line semantics, so neither does this check: a paragraph is whatever survives
    between two boundaries, however the author happened to wrap it.
    """
    found: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 0
    fenced = False

    def flush() -> None:
        nonlocal buf
        if buf:
            found.append((start, re.sub(r"\s+", " ", " ".join(buf)).strip()))
            buf = []

    for number, raw in enumerate(text.splitlines(), 1):
        stripped = raw.strip()
        if stripped.startswith("```"):
            flush()
            fenced = not fenced
            continue
        if fenced:
            continue
        # A blank line, a heading and a table row all end a paragraph and start nothing.
        if not stripped or stripped.startswith("#") or stripped.startswith("|"):
            flush()
            continue
        if BULLET.match(raw):
            flush()
        if not buf:
            start = number
        buf.append(stripped)
    flush()
    return [(n, b) for n, b in found if len(b) >= SUBSTANTIAL]


def ratio(matcher: SequenceMatcher, a: str, b: str) -> float:
    """Ratio, with the cheap upper bounds first so CI does not pay for the full matrix.

    `matcher` already holds `b` as its second sequence: SequenceMatcher indexes that side, and
    reusing the index across every candidate is what keeps this a second rather than a minute.
    """
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    # ratio() is 2*matches/(len(a)+len(b)), so length alone caps it at this. Using the
    # looser-looking shorter/longer here is WRONG and silently drops real findings: the two
    # max-concurrent-jobs bullets (868 and 1505 chars, genuinely 73% alike) score 0.58 by that
    # formula and 0.73 by this one, and they are the pair this pass was written for.
    if 2 * len(shorter) / (len(shorter) + len(longer)) < NEAR:
        return 0.0
    matcher.set_seq1(a)
    if matcher.quick_ratio() < NEAR:
        return 0.0
    return matcher.ratio()


def describe(kind: str, where: str, a: str, b: str, score: float) -> None:
    print(f"  ✗ {where} is {score:.0%} the same as {kind} — one of these two is stale")
    m = SequenceMatcher(None, a, b)
    shown = 0
    for tag, i1, i2, j1, j2 in m.get_opcodes():
        if tag == "equal" or shown >= 2:
            continue
        if (i2 - i1) > 40:
            print(f"      only here:  {a[i1:i2][:150]}…")
            shown += 1
        if (j2 - j1) > 40:
            print(f"      only there: {b[j1:j2][:150]}…")
            shown += 1
    if not shown:
        print("      (they differ only in wording — delete one)")


def main() -> int:
    if not MAP.exists() or not NOTES.is_dir():
        print("check-notes-parity: CLAUDE.md or docs/notes/ is missing", file=sys.stderr)
        return 1

    owner: dict[str, str] = {}
    for note in sorted(NOTES.glob("*.md")):
        for _, block in blocks(note.read_text(encoding="utf-8")):
            owner.setdefault(block, note.name)

    if not owner:
        print("check-notes-parity: no substantial prose found in docs/notes/ — has it moved?",
              file=sys.stderr)
        return 1

    units = blocks(MAP.read_text(encoding="utf-8"))

    # One matcher per note block, each holding that block as its indexed side, built once and
    # reused across every map paragraph. Measured on this tree: ~11 s for 50 map paragraphs
    # against 360 note ones, and ~15 s on the 103 KB revision that exposed the line/paragraph
    # defect. That is the whole of the docs-links job's cost and it sits inside a five-minute
    # budget, so the honest bounds above are kept rather than traded for a looser prefilter.
    candidates = [(SequenceMatcher(None, "", h), h, name) for h, name in owner.items()]

    exact, drifted, selfdup = [], [], []
    for n, s in units:
        if s in owner:
            exact.append((n, owner[s], s))
            continue
        best = max(((ratio(m, s, h), h, name) for m, h, name in candidates), default=(0.0, "", ""))
        if best[0] >= NEAR:
            drifted.append((n, best[2], best[1], s, best[0]))

    for idx, (n, s) in enumerate(units):
        mine = SequenceMatcher(None, "", s)
        for m, t in units[idx + 1:]:
            if s == t:
                selfdup.append((n, m, s, t, 1.0))
            else:
                r = ratio(mine, t, s)
                if r >= NEAR:
                    selfdup.append((n, m, s, t, r))

    if exact:
        print("Identical to a note — the map must not carry the rationale:")
        for n, note, s in exact[:20]:
            print(f"  ✗ CLAUDE.md:{n} repeats docs/notes/{note} — "
                  f"delete it from the map, or say it in the map's own words")
            print(f"      {s[:120]}…")
        if len(exact) > 20:
            print(f"  … and {len(exact) - 20} more")
        print()

    if drifted:
        print("A duplicate that has DRIFTED — the two copies no longer agree:")
        for n, note, h, s, r in drifted[:20]:
            describe(f"docs/notes/{note}", f"CLAUDE.md:{n}", s, h, r)
        if len(drifted) > 20:
            print(f"  … and {len(drifted) - 20} more")
        print()

    if selfdup:
        print("CLAUDE.md repeats ITSELF — say it once:")
        for n, m, s, t, r in selfdup[:20]:
            describe(f"CLAUDE.md:{m}", f"CLAUDE.md:{n}", s, t, r)
        if len(selfdup) > 20:
            print(f"  … and {len(selfdup) - 20} more")
        print()

    total = len(exact) + len(drifted) + len(selfdup)
    if total:
        wasted = sum(len(s) + 1 for _, _, s in exact)
        print(f"{len(exact)} verbatim, {len(drifted)} drifted, {len(selfdup)} self-repeated. "
              f"The verbatim ones alone carry {wasted} bytes in both files, "
              f"loaded into every session's context.")
        return 1

    print(f"{len(units)} substantial paragraph(s) in CLAUDE.md: none repeats docs/notes/, "
          f"none has drifted from it, none repeats another.")
    print(f"CLAUDE.md is {MAP.stat().st_size} bytes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
