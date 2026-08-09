#!/usr/bin/env python3
"""Resolve every environment variable and version the documentation advertises.

`docs/check-links.py` exists because a rotten link on the Docker Hub overview page is
invisible until a visitor clicks it. The configuration tables on that same page have
exactly the same exposure and had no such check: a variable that is renamed, or a base
image that is bumped, leaves the page confidently describing something that no longer
exists — and the page is the only documentation most people ever read. That is not
hypothetical. DOCKERHUB.md advertised `eclipse-temurin:21-jre-alpine` for two major
versions of the actual base image, and the error was found by reading, not by CI.

Two things are checked, both offline and both against the code rather than against a
second copy of the documentation:

  1. Every `VAR` in a documented table is a real, bindable property. The mapping is
     derived forwards, from the sources of truth to the variable name, because Spring's
     relaxed binding is many-to-one and cannot be inverted: `explorer.search-max-scan`
     yields exactly `EXPLORER_SEARCH_MAX_SCAN`, while that name alone could stand for
     several properties. The sources are application.yml (its keys and its `${VAR}`
     placeholders), the `@ConfigurationProperties` classes — which is where a property
     absent from the YAML, such as `explorer.stream-flow-max-topics`, actually lives —
     and the `ENV` lines of the runtime Dockerfiles.

  2. The documented default matches application.yml, where the YAML declares one. A
     default is a promise about behaviour, and a wrong one is worse than none: it is
     acted upon. Rows whose default is an em dash ("no default") are skipped, as are
     properties that only carry a default in Java — those are not read here, since
     parsing a field initialiser reliably is a bigger job than it is worth.

Plus the base-image line, matched against the `FROM` of both runtime images.

Nothing reaches the network, and there is no `needs` on the CI job: this is path and
string resolution, so it reports in seconds and can never go red for someone else's
outage.

Exit code 1 and a list of what is wrong, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The Docker Hub page is the reason this exists, but the READMEs quote the same variables
# and drift exactly as quietly. A file with no configuration table simply contributes no
# rows — the first cell of a row has to be a code span holding an ALL_CAPS name, which a
# prose table never is.
DOCS = ['docs/DOCKERHUB.md', 'README.md', 'README.fr.md']
YAML = ROOT / 'src/main/resources/application.yml'
CONFIG_DIR = ROOT / 'src/main/java/com/yourcompany/kafkasqlexplorer/config'
DOCKERFILES = [ROOT / 'Dockerfile', ROOT / 'Dockerfile.release']

# A table row: | `VAR` / `_SUFFIX` | default | meaning |
ROW = re.compile(r'^\|\s*(`[^|]+`(?:\s*/\s*`[^|]+`)*)\s*\|([^|]*)\|')
CODE = re.compile(r'`([^`]+)`')
ENV_NAME = re.compile(r'^[A-Z][A-Z0-9_]*$')
# ${VAR:default} placeholders in application.yml
PLACEHOLDER = re.compile(r'\$\{([A-Z][A-Z0-9_]*)[:}]')
# ENV KEY="value" / ENV KEY=value in a Dockerfile
DOCKER_ENV = re.compile(r'^\s*ENV\s+([A-Z][A-Z0-9_]*)=', re.MULTILINE)
PREFIX = re.compile(r'@ConfigurationProperties\(\s*prefix\s*=\s*"([^"]+)"')
FIELD = re.compile(r'^\s*private\s+(?:static\s+|final\s+)*[\w.<>,\[\]\s]+?\s(\w+)\s*(?:=|;)', re.MULTILINE)
# eclipse-temurin:25-jre-alpine, with or without a digest
BASE_IMAGE = re.compile(r'eclipse-temurin:([\w.-]+)')

# Variables that are neither Spring properties nor set by the Dockerfiles: they are read
# from the ambient environment by something else, and each is named here on purpose so
# that adding one is a decision rather than a hole in the check.
EXTERNAL = {
    # Read by the JVM itself, not by the application.
    'JAVA_TOOL_OPTIONS',
}


def env_name(prop: str) -> str:
    """The canonical environment-variable form of a property, per Spring relaxed binding."""
    return re.sub(r'[^A-Za-z0-9]', '_', prop).upper()


def kebab(camel: str) -> str:
    return re.sub(r'(?<!^)(?=[A-Z])', '-', camel).lower()


def yaml_properties() -> dict[str, str]:
    """Flatten application.yml into dotted paths -> scalar value, indentation-only.

    A real YAML parser is deliberately not used: PyYAML is not in the runner's default
    image, and this file is a plain indented mapping with no anchors or flow collections.
    Blocks whose value is a list or empty are recorded with an empty value, which the
    default comparison then skips.
    """
    props: dict[str, str] = {}
    stack: list[tuple[int, str]] = []
    for raw in YAML.read_text(encoding='utf-8').splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if line.startswith('- ') or ':' not in line:
            continue
        key, _, value = line.partition(':')
        key = key.strip()
        value = value.split('#', 1)[0].strip().strip('"\'')
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = '.'.join([p for _, p in stack] + [key])
        stack.append((indent, key))
        if value:
            props[path] = value
    return props


def java_properties() -> set[str]:
    """Every `<prefix>.<kebab-field>` declared by a @ConfigurationProperties class."""
    props: set[str] = set()
    for path in sorted(CONFIG_DIR.glob('*.java')):
        text = path.read_text(encoding='utf-8')
        match = PREFIX.search(text)
        if not match:
            continue
        for field in FIELD.findall(text):
            props.add(f'{match.group(1)}.{kebab(field)}')
    return props


def documented_rows(text: str) -> list[tuple[str, str]]:
    """(VAR, documented default) for every table row whose first cell is a code span.

    `KAFKA_TRUSTSTORE_PATH` / `_PASSWORD` is one cell describing two variables, the second
    written as a suffix replacing the last segment of the first. Expanding it is what makes
    the row checkable rather than skipped.
    """
    rows: list[tuple[str, str]] = []
    for line in text.splitlines():
        match = ROW.match(line)
        if not match:
            continue
        names = CODE.findall(match.group(1))
        default = match.group(2).strip().strip('`').strip()
        previous = None
        for name in names:
            if name.startswith('_') and previous:
                name = previous.rsplit('_', 1)[0] + name
            if not ENV_NAME.match(name):
                continue
            previous = name
            rows.append((name, default))
    return rows


def check() -> list[str]:
    problems: list[str] = []
    checked = 0

    yaml_props = yaml_properties()
    known: dict[str, str | None] = {env_name(p): v for p, v in yaml_props.items()}
    for prop in java_properties():
        known.setdefault(env_name(prop), None)
    for var in PLACEHOLDER.findall(YAML.read_text(encoding='utf-8')):
        known.setdefault(var, None)
    for dockerfile in DOCKERFILES:
        for var in DOCKER_ENV.findall(dockerfile.read_text(encoding='utf-8')):
            known.setdefault(var, None)
    for var in EXTERNAL:
        known.setdefault(var, None)

    for name in DOCS:
        path = ROOT / name
        if not path.exists():
            problems.append(f'{name}: listed for checking but missing from the repository')
            continue
        text = path.read_text(encoding='utf-8')

        for var, documented in documented_rows(text):
            checked += 1
            if var not in known:
                problems.append(
                    f'{name}: `{var}` binds to no property — not in application.yml, not a '
                    f'@ConfigurationProperties field, not an ENV of either Dockerfile')
                continue
            actual = known[var]
            # An em dash means "no default"; a Java-only property has none to compare.
            if actual is None or documented in ('', '—', '-'):
                continue
            if documented != actual:
                problems.append(
                    f'{name}: `{var}` is documented as `{documented}`, '
                    f'application.yml says `{actual}`')

        # The base image is a claim about the shipped artefact, in prose rather than in a
        # table, and it is the one this check was written for.
        from_lines = {
            m for f in DOCKERFILES for m in BASE_IMAGE.findall(f.read_text(encoding='utf-8'))
        }
        for documented in set(BASE_IMAGE.findall(text)):
            checked += 1
            if documented not in from_lines:
                problems.append(
                    f'{name}: base image `eclipse-temurin:{documented}` is not what the '
                    f'Dockerfiles use ({", ".join(sorted(from_lines))})')

    print(f'{checked} documented settings resolved against the code')
    return problems


if __name__ == '__main__':
    found = check()
    for problem in found:
        print(f'  ✗ {problem}', file=sys.stderr)
    if found:
        print(f'\n{len(found)} documentation claim(s) the code does not support.', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
