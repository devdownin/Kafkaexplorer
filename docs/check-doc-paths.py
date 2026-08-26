#!/usr/bin/env python3
"""Resolve every repository path named in prose in CLAUDE.md and CONTRIBUTING.md.

`check-links.py` resolves markdown *links*. These two files barely use any: they refer to
the codebase in backticks, in running prose — `pages/streamFlow.ts`, `compose/ci.yml`,
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
EXTERNAL list. A path the tree deliberately no longer HAS, named by prose explaining why it
went, belongs in RETIRED instead: both expire, but on opposite conditions, and calling a
deleted file "not a path" would be false about a file that was one.

Exit code 1 and a list of what is unresolved, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# CLAUDE.md and CONTRIBUTING.md are read by maintainers; the three below are what a
# stranger reads first, and until now NOTHING checked a path in them. Measured before
# adding them: seventy-seven backticked path-shaped tokens across the public docs, zero
# verified — which is the gap that let a stale command and a duplicated section survive
# in the README long enough that nobody could date them.
#
# docs/DOCKERHUB.md is the strongest case of the three: it is rendered OUTSIDE the
# repository, as the Docker Hub overview, so a wrong path there is invisible to anyone
# reading the repo and is the first thing a newcomer sees.
DOCS = ['CLAUDE.md', 'CONTRIBUTING.md', 'README.md', 'README.fr.md', 'docs/DOCKERHUB.md']

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

# Tokens that look like repository paths and are not. Each is here as a decision — and each
# is checked for still being needed, see unused_exemptions() below: an exemption nobody can
# reach is a silent licence, and the list only ever grows if nothing prunes it.
NOT_A_PATH = {
    # Container images and registry references.
    'apache/kafka', 'compagnonsdudev/kafkaexplorer', 'docker.io/compagnonsdudev/kafkaexplorer',
    'ghcr.io/devdownin/kafkaexplorer', 'unknown/unknown',
    # Build platforms. `linux/arm64` alone was enough while only CLAUDE.md and
    # CONTRIBUTING.md were read — the note that used to sit here said the amd64 form was
    # always written with a comma and therefore already rejected. That stopped being true
    # the moment the READMEs and the Docker Hub page joined DOCS: all three name it on its
    # own. A comment asserting why an entry is unnecessary is exactly the kind that decays.
    'linux/amd64', 'linux/arm64',
    # An OpenRouter model identifier written as a shape rather than a name, beside the
    # `openai/gpt-4o-mini` example below.
    'vendor/model',
    # Files this application CREATES at runtime under its `data/` volume, which is
    # gitignored — so they are real paths on a running deployment and never in a checkout.
    # The Docker Hub page documents them because an operator has to know what the volume
    # holds; resolving them against the repository would require running the app first.
    'data/settings.json', 'data/flink-tables.json', 'flink-jobs.json',
    # An OpenRouter model identifier, which that gateway writes `vendor/model`. Le seul jeton
    # de cette forme que la prose nomme hors d'un bloc clôturé.
    'openai/gpt-4o-mini',
    # GitHub Action references, which are owner/repo on GitHub, not paths here. Only the ones
    # the prose still names *bare* need an entry — where a SHA pin is quoted with the name,
    # the `@` in the token is enough for looks_like_path to reject it.
    'actions/attest-build-provenance', 'actions/upload-artifact', 'actions/download-artifact',
    # Generated, gitignored, or created at runtime — correctly absent from a clean checkout.
    'target/', 'target/surefire-reports/', 'dist/', 'data/', 'logs/', 'node_modules/',
    # The log file itself, written at runtime under the `logs/` entry above.
    'logs/kafkaexplorer.log',
    # Figures the prose writes with a slash — a ratio and a rate, not paths.
    '0/0', '620/h',
    # CodeQL query ids — `language/query-name`, which looks exactly like a path. Only the ones
    # CLAUDE.md still names in prose: this list expires its own unused entries.
    'java/sensitive-log',
    'java/log-injection',
    'src/main/resources/static/', 'src/main/webapp/node_modules',
    # Shipped inside the Kafka image, not in this repository.
    'kafka-broker-api-versions.sh', 'kafka-consumer-groups.sh',
    # A file of the *SpectraLLM* repository, named because its absence here is the reason
    # compose/spectra-hub.yml starts llama-server from arguments instead of mounting it.
    'scripts/llm-chat-entrypoint.sh',
    # A path *inside* a built Spring Boot jar, which is the whole point of naming it: it is
    # where the dependencies sit once packaged, and therefore where the system class loader
    # does not look. Nothing in a checkout can resolve it.
    'BOOT-INF/lib',
    # Deliberately named as *deleted*, with the commit that removed them. The prose exists to
    # tell a reader the document is gone and where its reasoning lives; removing the names
    # would remove the only pointer back to it.
    'AUDIT.md',                   # deleted in 31767bd
    'CONSUMER-GROUPS-AUDIT.md',   # deleted in d643f23
    'deploy/kraft-platform/',     # deleted in 5b090df
}

# A path this tree DELIBERATELY no longer has, named by prose that explains why it went.
# Distinct from NOT_A_PATH, which says "this token is not a repository path at all" — that
# would be a lie about a file which was one until it was deleted, and the lie matters: the
# two lists expire on opposite conditions. A NOT_A_PATH entry goes stale when nothing cites
# it; a RETIRED entry goes stale when nothing cites it *or* when the file comes back, at
# which point the prose is describing a deletion that was undone. Written as (path, why).
RETIRED = {
    'docker-compose-kafka4.yml': 'was renamed to compose/schema-registry.yml (every stack is Kafka 4)',
    'docker-compose-llm.yml': 'was renamed to compose/ollama.yml',
    'docker-compose.release.yml': 'was replaced by compose/image.yml',
    'docker-compose-spectra.yml': 'was deleted: it needed a sibling SpectraLLM checkout',
    'docker-compose-spectra-hub.small.yml': 'was deleted: it is four .env lines, not a file',
}

TOKEN = re.compile(r'`([^`\n]+)`')
# Anchored at the start of a line, and that is the whole point rather than tidiness: CLAUDE.md
# names a Markdown fence *in prose* ("whatever prose or ``` fence came around it"), which makes
# the count of ``` odd, so an unanchored non-greedy pair matched from that mention to the next
# real fence and stripped 225 266 characters — everything from the LLM section to the audit
# references. The file was 419 KB and this check was reading a third of it, silently, including
# the paragraph naming SQL-EDITOR-AUDIT.md. Coverage went 99 → 202 tokens when this was fixed.
FENCE = re.compile(r'^```.*?^```', re.DOTALL | re.MULTILINE)


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


def unused_exemptions(cited: set[str]) -> list[str]:
    """NOT_A_PATH entries that no longer do anything, with which kind of nothing.

    An entry earns its place only if a checked document still cites it *and*
    `looks_like_path` would put it to the test. Two ways to stop earning it: the prose that
    named it is gone, or the token is one `looks_like_path` already rejects — in which case
    the `or` in the loop below short-circuits and the entry is never even consulted.

    Deliberately **not** reported: an entry whose token would resolve as a real path. Half of
    this list is generated or gitignored (`target/`, `dist/`, `node_modules/`, `logs/`), so
    that verdict would depend on whether a build had run — green on a clean checkout, red on
    a developer's tree, which is the one thing a check must never be.
    """
    stale: list[str] = []
    for token, why in sorted(RETIRED.items()):
        if token not in cited:
            stale.append(
                f'RETIRED: `{token}` is cited by no checked document any more — '
                f'remove it, the prose that explained its removal ({why}) is gone')
        elif any((ROOT / base / token.rstrip('/')).exists() for base in BASES):
            stale.append(
                f'RETIRED: `{token}` exists again — the entry records that it {why}, so either '
                f'the prose describing its removal is now wrong or the entry is')
    for token in sorted(NOT_A_PATH):
        if token not in cited:
            stale.append(
                f'NOT_A_PATH: `{token}` is cited by no checked document any more — '
                f'remove it, the prose it covered is gone')
        elif not looks_like_path(token):
            stale.append(
                f'NOT_A_PATH: `{token}` is unreachable — looks_like_path already rejects it, '
                f'so the entry is never consulted; remove it')
    return stale


def check() -> list[str]:
    names = basename_index()
    unresolved: list[str] = []
    cited: set[str] = set()
    checked = 0

    for doc in DOCS:
        path = ROOT / doc
        if not path.exists():
            unresolved.append(f'{doc}: listed for checking but missing from the repository')
            continue

        text = FENCE.sub('', path.read_text(encoding='utf-8'))
        for token in sorted({t.strip() for t in TOKEN.findall(text)}):
            cited.add(token)
            if not looks_like_path(token) or token in NOT_A_PATH or token in RETIRED:
                continue
            checked += 1
            bare = token.rstrip('/')
            if any((ROOT / base / bare).exists() for base in BASES):
                continue
            # No directory component: accept it if the tree holds a file so named.
            if '/' not in bare and bare in names:
                continue
            # A citation that names the module rather than the file — `components/ui/Switch`
            # for `Switch.tsx`. The prose refers to code by import path throughout, and a
            # check that reports a file which is plainly there is one people learn to ignore.
            if any((ROOT / base / (bare + suffix)).exists()
                   for base in BASES for suffix in SOURCE_SUFFIXES):
                continue
            unresolved.append(
                f'{doc}: `{token}` resolves to nothing — fix the reference, or add it to '
                f'NOT_A_PATH if it is not a repository path')

    unresolved.extend(unused_exemptions(cited))
    print(f'{checked} documented paths resolved across {len(DOCS)} files, '
          f'{len(NOT_A_PATH)} exemptions audited')
    return unresolved


if __name__ == '__main__':
    problems = check()
    for problem in problems:
        print(f'  ✗ {problem}', file=sys.stderr)
    if problems:
        print(f'\n{len(problems)} unresolved path reference(s).', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
