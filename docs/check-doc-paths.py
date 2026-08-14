#!/usr/bin/env python3
"""Resolve every repository path named in prose in CLAUDE.md and CONTRIBUTING.md.

`check-links.py` resolves markdown *links*. These two files barely use any: they refer to
the codebase in backticks, in running prose — `pages/streamFlow.ts`, `docker-compose.ci.yml`,
`AUDIT.md` — and nothing ever checked that those still exist. They did not. CLAUDE.md
described `AUDIT.md` and `CONSUMER-GROUPS-AUDIT.md` as documents to read before refactoring,
and pointed at `deploy/kraft-platform/` in a rule about container names; all three had been
deleted, two of them months earlier. A reader following that file was sent to nothing.

That is the same failure the repository keeps re-learning — a file nobody executes rots
quietly — applied to the file that is supposed to explain the repository.

Resolution is *forward* and deliberately generous, because these documents name paths
relative to whatever they are discussing: `pages/help.ts` means
`src/main/webapp/src/pages/help.ts`. A token resolves if it exists under any known base, or
— when it carries no directory at all — if any file in the tree has that basename. Being
generous is the point: a false positive here trains people to ignore the check, and the
thing worth catching is a path that resolves *nowhere*.

Anything that is not a repository path (a container image, an action reference, a build
directory that is generated rather than committed) must be listed in NOT_A_PATH by name, so
that it is a decision and not a hole — the same rule `check-config-table.py` applies to its
EXTERNAL list.

Exit code 1 and a list of what is unresolved, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DOCS = ['CLAUDE.md', 'CONTRIBUTING.md']

# Where a path named in prose may be rooted. Ordered widest first for no reason but reading.
BASES = [
    '',
    'src/main/webapp/src/',
    'src/main/webapp/',
    'src/main/resources/',
    'src/main/java/com/compagnonsdudev/kafkasqlexplorer/',
    'src/test/java/com/compagnonsdudev/kafkasqlexplorer/',
    '.github/',
    '.github/workflows/',
    'docs/',
    'docs/screenshots/',
]

SOURCE_SUFFIXES = ('.md', '.yml', '.yaml', '.sh', '.java', '.ts', '.tsx', '.py', '.xml',
                   '.bat', '.ps1', '.json', '.js', '.mjs')

# Characters that mean the token is not a path: an assignment, a template placeholder, a
# glob, an ellipsis, a registry tag, a shell expansion.
NOT_PATH_CHARS = set('={}<>…:~@,*$|()[]"\'')

# Tokens that look like repository paths and are not. Each is here as a decision.
NOT_A_PATH = {
    # Container images and registry references.
    'apache/kafka', 'apache/kafka:4.3.1', 'apache/kafka-native:4.3.1',
    'compagnonsdudev/kafkaexplorer', 'docker.io/compagnonsdudev/kafkaexplorer',
    'ghcr.io/devdownin/kafkaexplorer', 'eclipse-temurin', 'unknown/unknown',
    # Build platforms.
    'linux/arm64', 'linux/amd64', 'linux/amd64,linux/arm64',
    # GitHub Action references, which are owner/repo on GitHub, not paths here.
    'actions/attest-build-provenance', 'actions/upload-artifact', 'actions/download-artifact',
    'actions/checkout', 'actions/setup-java', 'actions/cache',
    'ossf/scorecard-action', 'aquasecurity/trivy-action', 'softprops/action-gh-release',
    'peter-evans/dockerhub-description', 'trufflesecurity/trufflehog', 'github/codeql-action',
    # Generated, gitignored, or created at runtime — correctly absent from a clean checkout.
    'target/', 'target/surefire-reports/', 'dist/', 'data/', 'logs/', 'node_modules/',
    'src/main/resources/static/', 'src/main/webapp/node_modules',
    # Shipped inside the Kafka image, not in this repository.
    'kafka-broker-api-versions.sh', 'kafka-console-producer',
    # A UI string ("0/0 members"), not a path.
    '0/0',
    # Deliberately named as *deleted*, with the commit that removed them. The prose exists to
    # tell a reader the document is gone and where its reasoning lives; removing the names
    # would remove the only pointer back to it.
    'AUDIT.md',                   # deleted in 31767bd
    'CONSUMER-GROUPS-AUDIT.md',   # deleted in d643f23
    'deploy/kraft-platform/',     # deleted in 5b090df
}

TOKEN = re.compile(r'`([^`\n]+)`')
FENCE = re.compile(r'```.*?```', re.DOTALL)


def basename_index() -> set[str]:
    """Every filename in the tree, so a bare `helpers.ts` resolves wherever it lives."""
    names: set[str] = set()
    skip = {'.git', 'node_modules', 'target', 'dist', '.mvn'}
    for path in ROOT.rglob('*'):
        if any(part in skip for part in path.parts):
            continue
        if path.is_file():
            names.add(path.name)
    return names


def looks_like_path(token: str) -> bool:
    if not token or ' ' in token or NOT_PATH_CHARS & set(token):
        return False
    if token.startswith(('http', '/', '-', '.')):
        return False
    return '/' in token or token.endswith(SOURCE_SUFFIXES)


def check() -> list[str]:
    names = basename_index()
    unresolved: list[str] = []
    checked = 0

    for doc in DOCS:
        path = ROOT / doc
        if not path.exists():
            unresolved.append(f'{doc}: listed for checking but missing from the repository')
            continue

        text = FENCE.sub('', path.read_text(encoding='utf-8'))
        for token in sorted({t.strip() for t in TOKEN.findall(text)}):
            if not looks_like_path(token) or token in NOT_A_PATH:
                continue
            checked += 1
            bare = token.rstrip('/')
            if any((ROOT / base / bare).exists() for base in BASES):
                continue
            # No directory component: accept it if the tree holds a file so named.
            if '/' not in bare and bare in names:
                continue
            unresolved.append(
                f'{doc}: `{token}` resolves to nothing — fix the reference, or add it to '
                f'NOT_A_PATH if it is not a repository path')

    print(f'{checked} documented paths resolved across {len(DOCS)} files')
    return unresolved


if __name__ == '__main__':
    problems = check()
    for problem in problems:
        print(f'  ✗ {problem}', file=sys.stderr)
    if problems:
        print(f'\n{len(problems)} unresolved path reference(s).', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
