#!/usr/bin/env python3
"""Check tracked Markdown and documentation HTML repository links and anchors without network access.

Docker Hub and Pages render outside the repository, so absolute links back to this
repository are resolved too. External availability is checked separately: an outage
on another site must not turn the repository's documentation CI red.
"""
from __future__ import annotations

import html
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
COMMENT = re.compile(r'<!--.*?-->', re.DOTALL)
MD_TARGET = re.compile(r'\]\(\s*(<[^>]+>|[^\s)]+)')
HTML_TARGET = re.compile(r'(?:src|href)=["\']([^"\']+)["\']')
REFERENCE = re.compile(r'^\s{0,3}\[([^]]+)\]:\s*(<[^>]+>|\S+)', re.MULTILINE)
REFERENCE_USE = re.compile(r'\[([^]\n]+)\](?:\[([^]\n]*)\])?(?![\[(])')
HTML_ID = re.compile(r'(?:id|name)=["\']([^"\']+)["\']')


def rendered_text(path: Path) -> str:
    """Remove comments and fenced code, preserving Markdown heading order."""
    text = COMMENT.sub('', path.read_text(encoding='utf-8'))
    if path.suffix.lower() != '.md':
        return text
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r'^\s*(`{3,}|~{3,})', line)
        if fence:
            if match and match[1][0] == fence[0] and len(match[1]) >= len(fence):
                fence = None
        elif match:
            fence = match[1]
        else:
            lines.append(line)
    return '\n'.join(lines)


def anchors(path: Path) -> set[str]:
    text = rendered_text(path)
    result = set(HTML_ID.findall(text))
    if path.suffix.lower() != '.md':
        return result
    seen: Counter[str] = Counter()
    lines = text.splitlines()
    headings = []
    for i, line in enumerate(lines):
        match = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*$', line)
        if match:
            headings.append(match[1])
        elif i and re.fullmatch(r'\s{0,3}(?:=+|-+)\s*', line) and lines[i - 1].strip():
            headings.append(lines[i - 1].strip())
    for heading in headings:
        heading = re.sub(r'<[^>]*>', '', heading)
        heading = re.sub(r'\[([^]]+)\]\([^)]*\)', r'\1', heading)
        heading = html.unescape(heading).lower().replace('`', '').replace('*', '')
        slug = ''.join(c for c in heading if c in '-_ ' or unicodedata.category(c)[0] in 'LN')
        slug = slug.replace(' ', '-')
        # GitHub de-duplicates against all previously allocated anchors, including
        # headings that already end in a numeric suffix.
        candidate = slug
        while candidate in result:
            seen[slug] += 1
            candidate = f'{slug}-{seen[slug]}'
        result.add(candidate)
    return result


def targets(text: str) -> list[str]:
    result = MD_TARGET.findall(text) + HTML_TARGET.findall(text)
    definitions = {key.casefold(): value for key, value in REFERENCE.findall(text)}
    result.extend(definitions.values())
    for label, ref in REFERENCE_USE.findall(REFERENCE.sub('', text)):
        value = definitions.get((ref or label).casefold())
        if value:
            result.append(value)
    return [html.unescape(value.strip('<>')) for value in result]


def resolve(path: Path, raw: str) -> tuple[Path, str] | None:
    url = urlsplit(raw)
    fragment = unquote(url.fragment)
    if url.scheme or url.netloc:
        if url.netloc == 'github.com' and url.path.startswith('/devdownin/Kafkaexplorer/blob/main/'):
            dest = ROOT / unquote(url.path.split('/blob/main/', 1)[1])
        elif url.netloc == 'raw.githubusercontent.com' and url.path.startswith('/devdownin/Kafkaexplorer/main/'):
            dest = ROOT / unquote(url.path.split('/main/', 1)[1])
        elif url.netloc == 'devdownin.github.io' and url.path.startswith('/Kafkaexplorer/'):
            dest = ROOT / 'docs' / unquote(url.path[len('/Kafkaexplorer/'):])
        else:
            return None
    else:
        dest = path.parent / unquote(url.path) if url.path else path
    if dest.is_dir():
        dest = dest / 'index.html'
    return dest, fragment


def check() -> list[str]:
    broken = []
    checked = 0
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    docs = [ROOT / name for name in names
            if Path(name).suffix.lower() == '.md'
            or (name.startswith('docs/') and Path(name).suffix.lower() == '.html')]
    hub = ROOT / 'docs/DOCKERHUB.md'
    if hub.exists() and hub.stat().st_size > 25_000:
        broken.append(f'docs/DOCKERHUB.md: {hub.stat().st_size} bytes exceeds the Docker Hub 25,000-byte limit')
    cache = {}
    for path in docs:
        name = path.relative_to(ROOT)
        if not path.exists():
            broken.append(f'{name}: tracked document missing')
            continue
        for raw in targets(rendered_text(path)):
            resolved = resolve(path, raw)
            if resolved is None:
                continue
            dest, fragment = resolved
            checked += 1
            if not dest.exists():
                broken.append(f'{name}: link "{raw}" resolves to nothing')
            elif fragment and dest.suffix.lower() in ('.md', '.html'):
                if dest not in cache:
                    cache[dest] = anchors(dest)
                if fragment not in cache[dest]:
                    broken.append(f'{name}: link "{raw}" has no matching anchor')
    print(f'{checked} repository links and anchors checked across {len(docs)} files')
    return broken


if __name__ == '__main__':
    problems = check()
    for problem in problems:
        print(f'  ✗ {problem}', file=sys.stderr)
    if problems:
        print(f'\n{len(problems)} broken link(s).', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
