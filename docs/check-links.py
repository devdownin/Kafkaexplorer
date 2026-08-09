#!/usr/bin/env python3
"""Resolve every documentation link that points back into this repository.

`docs/DOCKERHUB.md` is the reason this exists. It is rendered by Docker Hub, *outside*
the repository, so all of its links must be absolute and none of them can be verified by
looking at the page: a link that rots is invisible until a visitor clicks it and lands on
a 404 — on the page that is supposed to sell the image. The same goes for its screenshots,
which are served from GitHub Pages rather than from the repository.

The READMEs and the feature tour are checked too. GitHub resolves their relative links, so
rot there is at least visible in-repo, but the check costs nothing once it is written.

Only links *into this repository* are resolved. Nothing reaches the network: a link checker
that makes HTTP calls fails on someone else's outage, and a CI step that goes red for
reasons outside the repository is a step people learn to ignore.

Exit code 1 and a list of what is broken, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Absolute forms that address this repository's own content.
BLOB = re.compile(r'https://github\.com/devdownin/Kafkaexplorer/blob/main/([^)\s"\'#]+)')
PAGES = re.compile(r'https://devdownin\.github\.io/Kafkaexplorer/([^)\s"\'#]+)')
# Markdown link and image targets: ](target)
MD_TARGET = re.compile(r'\]\(\s*([^)\s]+)')
# HTML asset references in the landing page.
HTML_SRC = re.compile(r'(?:src|href)="([^"]+)"')
# Comments are not rendered, so nothing inside one is a link. Stripping them is not
# cosmetic: DOCKERHUB.md opens with a note to maintainers that spells out the Pages URL
# shape as `…/img/…`, and that ellipsis was reported as a broken image.
COMMENT = re.compile(r'<!--.*?-->', re.DOTALL)

MARKDOWN = ['docs/DOCKERHUB.md', 'README.md', 'README.fr.md', 'docs/FEATURES.md',
            'docs/screenshots/README.md']
HTML = ['docs/index.html']


def is_local(target: str) -> bool:
    """A relative path into the repository — not a URL, an anchor or a mail link."""
    return not (target.startswith(('http://', 'https://', '#', 'mailto:', 'data:')))


def check() -> list[str]:
    broken: list[str] = []
    checked = 0

    for name in MARKDOWN + HTML:
        path = ROOT / name
        if not path.exists():
            broken.append(f'{name}: listed for checking but missing from the repository')
            continue
        text = COMMENT.sub('', path.read_text(encoding='utf-8'))
        targets = MD_TARGET.findall(text) if name.endswith('.md') else HTML_SRC.findall(text)

        for raw in targets:
            if not is_local(raw):
                continue
            # A trailing anchor addresses a heading, not a different file.
            rel = raw.split('#', 1)[0]
            if not rel:
                continue
            checked += 1
            if not (path.parent / rel).resolve().exists():
                broken.append(f'{name}: relative link "{raw}" resolves to nothing')

        for repo_path in BLOB.findall(text):
            checked += 1
            if not (ROOT / repo_path).exists():
                broken.append(f'{name}: github.com/…/blob/main/{repo_path} is not in the repository')

        for pages_path in PAGES.findall(text):
            checked += 1
            # GitHub Pages publishes ./docs, so /Kafkaexplorer/img/x.png is docs/img/x.png.
            if not (ROOT / 'docs' / pages_path).exists():
                broken.append(f'{name}: Pages URL /{pages_path} has no docs/{pages_path} behind it')

    print(f'{checked} repository links checked across {len(MARKDOWN) + len(HTML)} files')
    return broken


if __name__ == '__main__':
    problems = check()
    for problem in problems:
        print(f'  ✗ {problem}', file=sys.stderr)
    if problems:
        print(f'\n{len(problems)} broken link(s).', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
