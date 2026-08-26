#!/usr/bin/env python3
"""Check that README.md and README.fr.md stay structurally parallel.

The French README is a translation, so nothing here compares meaning — it compares the
things a translation must NOT change, and that an edit made on one side only always does:
the shape of the document, the commands it hands the reader, and where it sends them.

Why this exists. The two files drift TOGETHER when they are edited together and silently
apart when they are not, and nothing in the tree could tell the difference. It was found
the way such things are: the whole "Build and Development" section appeared **twice,
verbatim**, in BOTH files — twenty-three duplicated lines each — and had done for long
enough that nobody could say when. No check read the READMEs at all, so a section added on
one side, a command corrected on one side, or a link fixed on one side would have lived
exactly as long.

Four comparisons, each chosen because a translator never changes it and a one-sided edit
always does:

1. **The sequence of heading levels.** Not the text — that is translated — but the shape:
   `##`, `###`, `###`, `##` … A section added, removed or re-nested on one side changes it.
   This is the one that would have caught the duplicated section.
2. **The number of fenced code blocks, and the number of lines in each.** The commands are
   the part of a README a reader actually runs. Line counts rather than text, because the
   comments inside them are legitimately translated (`# the full CI gate` /
   `# le gate complet`) and so is the odd placeholder (`your-broker` / `votre-broker`) —
   what is never legitimate is a command present on one side and absent on the other.
3. **The set of URLs.** A translation keeps them: measured, both files carry the same
   twenty. A link corrected, added or rotted on one side only is the classic half-fix.
4. **The set of repository paths in backticks.** Same argument, for the file names the
   prose points at — which is also what makes this the companion of check-doc-paths.py:
   that one asks whether a path resolves, this one asks whether both languages name it.

What it deliberately does NOT check: word counts, sentence counts, or anything that would
make a good translation fail. A shorter French paragraph is a better translation, not a
defect.

Exit code 1 and what diverged, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EN = ROOT / 'README.md'
FR = ROOT / 'README.fr.md'

# Leading whitespace matters: a fenced block inside a list item is indented, and a regex
# anchored at column 0 misses it — which silently counted 4 of this README's 6 blocks AND
# let the block's own contents be read as prose. Found by measuring against a second
# implementation rather than by reading the code.
FENCE = re.compile(r'^\s*```')
HEADING = re.compile(r'^(#{1,6}) ')
URL = re.compile(r'https?://[^\s)\]<>"`]+')
TOKEN = re.compile(r'`([^`\n]+)`')
SOURCE_SUFFIXES = ('.ts', '.tsx', '.java', '.yml', '.yaml', '.py', '.md', '.sh',
                   '.json', '.html', '.xml', '.ps1', '.bat', '.properties')


def outside_fences(text: str) -> list[str]:
    """The lines that are not inside a fenced code block.

    Headings are only headings outside a fence — a `# comment` in a shell block is not a
    section, and counting it as one would make the shapes diverge for no reason.
    """
    out, inside = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            out.append(line)
    return out


def heading_shape(text: str) -> list[str]:
    return [m.group(1) for line in outside_fences(text) if (m := HEADING.match(line))]


def code_blocks(text: str) -> list[int]:
    """Line count of each fenced block, in order."""
    counts, inside, n = [], False, 0
    for line in text.splitlines():
        if FENCE.match(line):
            if inside:
                counts.append(n)
            inside, n = not inside, 0
            continue
        if inside:
            n += 1
    return counts


def urls(text: str) -> set[str]:
    # A trailing period is sentence punctuation, not part of the address.
    return {u.rstrip('.,;') for u in URL.findall(text)}


def paths(text: str) -> set[str]:
    """Path-shaped words inside backtick spans, including within command lines.

    Splitting each span on whitespace rather than taking it whole is the difference between
    this comparison earning its place and being decoration: a README names its files inside
    the commands it hands you (`docker compose -f compose/ollama.yml up -d`), so a
    whole-span rule saw three tokens in this file, two of which were CPU architectures.

    No allow-list and no filtering of the near-misses, deliberately. This is a PARITY
    check: it compares one file against the other, so a heuristic that also picks up
    `linux/amd64` costs nothing as long as it does so on both sides. Whether a path
    RESOLVES is check-doc-paths.py's question, and that one does need its exemptions.
    """
    found = set()
    for span in TOKEN.findall(text):
        for token in span.split():
            token = token.strip('`,;')
            if not token or token.startswith(('http', '/', '-', '.', '$')):
                continue
            if any(c in token for c in '(),:*<>|="\''):
                continue
            if '/' in token or token.endswith(SOURCE_SUFFIXES):
                found.add(token)
    return found


def compare_sequences(what: str, a: list, b: list) -> list[str]:
    """Report the FIRST divergence with its position, not the whole zip.

    A section inserted on one side shifts everything after it, so listing every mismatch
    would print a wall of noise about one edit. The position is what locates it.
    """
    if a == b:
        return []
    if len(a) != len(b):
        detail = f'README.md has {len(a)}, README.fr.md has {len(b)}'
    else:
        i = next(i for i, (x, y) in enumerate(zip(a, b)) if x != y)
        detail = f'they diverge at #{i + 1}: README.md has {a[i]!r}, README.fr.md has {b[i]!r}'
    return [f'{what}: {detail}']


def compare_sets(what: str, a: set, b: set) -> list[str]:
    problems = []
    for only, where in ((a - b, 'README.md'), (b - a, 'README.fr.md')):
        for item in sorted(only):
            problems.append(f'{what}: `{item}` appears only in {where}')
    return problems


def main() -> int:
    for path in (EN, FR):
        if not path.exists():
            print(f'  ✗ {path.name} is missing from the repository')
            return 1

    en, fr = EN.read_text(encoding='utf-8'), FR.read_text(encoding='utf-8')

    problems: list[str] = []
    problems += compare_sequences('heading shape', heading_shape(en), heading_shape(fr))
    problems += compare_sequences('code blocks (line counts)', code_blocks(en), code_blocks(fr))
    problems += compare_sets('URL', urls(en), urls(fr))
    problems += compare_sets('path', paths(en), paths(fr))

    if problems:
        for problem in problems:
            print(f'  ✗ {problem}')
        print(f'\n{len(problems)} divergence(s) between the two READMEs. A translation may '
              f'change the words; it may not change the structure, the commands or the links.')
        return 1

    print(f'{len(heading_shape(en))} headings, {len(code_blocks(en))} code blocks, '
          f'{len(urls(en))} URLs and {len(paths(en))} paths')
    print('README.md and README.fr.md are structurally parallel.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
