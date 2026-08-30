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

  3. Every **dependency version the documentation states in prose or in a badge** is resolved
     against pom.xml. This is the class of claim that rots most quietly here, and it has been
     caught twice by reading rather than by CI: the docs asserted that Flink 2.x supported
     "Java 17/21, not 25" while the runtime images had run a JRE 25 for releases, and named
     `flink-connector-kafka:4.0.1-2.0` where the pom carried `5.0.0-2.2`. Writing this check
     immediately found a third — a section documenting `anthropic-java 2.16.1` against a pom
     on 2.53.0.

     The claims are enumerated in VERSION_CLAIMS rather than discovered, and resolved forwards
     from the pom, for the reason the env-var pass is: a blind scan for version-shaped numbers
     would flag React 19, JUnit 5, "Kafka 2.1+ brokers" and a dozen others, and a check with
     false positives is one people learn to ignore. What that costs is visible instead of
     hidden — the run prints how many claims it resolved, so a claim absent from the table is
     unchecked rather than silently blessed.

     Abbreviated claims resolve by prefix at a component boundary ("Flink 2.3" against 2.3.0,
     the `Kafka-4.3_KRaft` badge against 4.3.1); a claim about an exact artifact version is
     compared exactly. Badges are checked on **both** halves, the alt text and the URL, since
     they are two copies of one number and either can be edited alone.

     Claims that describe the past on purpose are exempted by name in HISTORICAL — a document
     saying a bug "reproduced on Flink 1.18" is correct and must not be dragged forward.

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
CONFIG_DIR = ROOT / 'src/main/java/com/compagnonsdudev/kafkasqlexplorer/config'
DOCKERFILES = [ROOT / 'Dockerfile', ROOT / 'Dockerfile.release']
POM = ROOT / 'pom.xml'

# Files whose prose states a dependency version. Wider than DOCS: these carry no
# configuration table, but they do make version claims, and CLAUDE.md makes most of them.
# docs/index.html is here for the reason this whole pass exists: it is the landing page, it
# is rendered outside the repository by GitHub Pages, and its hero read "Powered by Flink 2.2"
# for two minor versions of the embedded runtime. Nothing else in the tree looks at it.
# docs/notes/ is globbed for the reason check-doc-paths.py globs it: it holds the prose
# CLAUDE.md used to carry, version claims included, and a note nothing reads drifts silently.
VERSION_DOCS = (['CLAUDE.md', 'CONTRIBUTING.md', 'README.md', 'README.fr.md',
                 'docs/DOCKERHUB.md', 'docs/architecture.md', 'docs/index.html']
                + sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'docs/notes').glob('*.md')))

# How a documented version relates to the pom's.
EXACT = 'exact'    # the claim names a precise artifact version
PREFIX = 'prefix'  # the claim abbreviates it ("Flink 2.3" for 2.3.0)

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

# Version claims to resolve, each naming where the truth lives. Enumerated rather than
# discovered — see the module docstring on why a blind scan would be worse than no check.
# `source` is ('property', name) | ('dependency', artifactId) | ('parent',).
VERSION_CLAIMS = [
    ('Flink', r'(?:Apache )?Flink (\d+\.\d+(?:\.\d+)?)', ('property', 'flink.version'), PREFIX),
    ('Spring Boot', r'Spring Boot (\d+\.\d+(?:\.\d+)?)', ('parent',), PREFIX),
    ('kafka-clients', r'kafka-clients`? (\d+\.\d+\.\d+)', ('property', 'kafka.version'), EXACT),
    ('io.confluent', r'io\.confluent`? (\d+\.\d+\.\d+)', ('property', 'confluent.version'), EXACT),
    ('flink-connector-kafka', r'flink-connector-kafka:([\w.-]+)',
     ('dependency', 'flink-connector-kafka'), EXACT),
    ('anthropic-java', r'anthropic-java (\d+\.\d+\.\d+)', ('dependency', 'anthropic-java'), EXACT),
]

# Static shields.io badges carrying a version: (label, source, mode). The version is
# hand-written text in the URL path, derived from nothing.
BADGES = [
    ('Java', ('property', 'java.version'), EXACT),
    ('Kafka', ('property', 'kafka.version'), PREFIX),
    ('Flink', ('property', 'flink.version'), PREFIX),
]

# Version claims that describe the PAST on purpose, exempted by name so that each is a
# decision rather than a hole — the same discipline as EXTERNAL below. DOCKER-AUDIT.md is
# not in VERSION_DOCS at all for this reason: it is a record of what was fixed, so every
# version it names is historical by construction.
HISTORICAL = {
    # The Calcite metadataHandlerProvider NPE reproduced on Flink 1.18/1.20/2.0, and still
    # reproduces on 2.3 — the workaround in FlinkRuntimeCoordinator is load-bearing.
    ('docs/notes/backend-services.md', 'Flink', '1.18'),
    # And in the paragraph about this check, which quotes that same sentence as its example.
    ('docs/notes/ci-and-checks.md', 'Flink', '1.18'),
    # The paragraph *about this check* cites the two stale claims that motivated it. Prose about
    # drift names the wrong value on purpose; describing them without the numbers would make the
    # paragraph less useful to the next reader. The cost is worth stating: an exemption keyed on
    # the value cannot tell a citation from a genuine regression back to that same value in that
    # same file. It is narrow — one file, one artifact, one version each.
    ('docs/notes/ci-and-checks.md', 'flink-connector-kafka', '4.0.1-2.0'),
    ('docs/notes/ci-and-checks.md', 'anthropic-java', '2.16.1'),
}

# Variables that are neither Spring properties nor set by the Dockerfiles: they are read
# from the ambient environment by something else, and each would be named here on purpose so
# that adding one is a decision rather than a hole in the check.
#
# Empty, and correctly so. It held `JAVA_TOOL_OPTIONS`, which both runtime images set as an
# ENV — so `known` already resolved it and the entry had been suppressing nothing for as long
# as those ENV lines have existed. The slot stays for the next genuinely ambient variable;
# stale_exemptions() is what will say when one stops being needed.
EXTERNAL: set[str] = set()


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


def pom_version(source: tuple) -> str | None:
    """Resolve a VERSION_CLAIMS/BADGES source to the version pom.xml actually declares.

    Read with regexes rather than an XML parser for the same reason application.yml is:
    nothing here may depend on a library the runner's default image does not carry. Each
    shape is anchored on the element that identifies it, so a `<version>` is never picked
    up from the wrong dependency.
    """
    text = POM.read_text(encoding='utf-8')
    kind = source[0]
    if kind == 'property':
        match = re.search(rf'<{re.escape(source[1])}>\s*([^<\s]+)\s*</', text)
    elif kind == 'dependency':
        match = re.search(
            rf'<artifactId>{re.escape(source[1])}</artifactId>\s*<version>\s*([^<\s]+)', text)
    elif kind == 'parent':
        match = re.search(
            r'spring-boot-starter-parent</artifactId>\s*<version>\s*([^<\s]+)', text)
    else:
        raise AssertionError(f'unknown version source {source!r}')
    return match.group(1) if match else None


def version_agrees(claimed: str, actual: str, mode: str) -> bool:
    """Does a documented version agree with the pom's, under the declared mode?

    PREFIX accepts an abbreviation only at a component boundary, so "2.3" resolves against
    2.3.0 while "1.18" does not — otherwise the mode would bless anything sharing a first
    digit, which is the fuzzy matching that makes a check worthless.
    """
    if claimed == actual:
        return True
    return mode == PREFIX and actual.startswith(claimed + '.')


def version_claims(name: str, text: str) -> list[tuple[str, str, str, str]]:
    """(label, claimed, actual, mode, exemption) for every enumerated claim the document makes.

    `exemption` is the HISTORICAL key when one covers this claim, else None.
    """
    found: list[tuple[str, str, str, str, tuple | None]] = []
    for label, pattern, source, mode in VERSION_CLAIMS:
        for claimed in set(re.findall(pattern, text)):
            key = (name, label, claimed)
            # Reported rather than skipped here: check() has to know whether the exemption
            # actually suppressed a disagreement, or is one nobody needs any more.
            found.append((label, claimed, pom_version(source), mode,
                          key if key in HISTORICAL else None))
    for label, source, mode in BADGES:
        badge = re.compile(
            rf'\[!\[{re.escape(label)}\s+([^\]]+)\]\(\s*'
            rf'https://img\.shields\.io/badge/{re.escape(label)}-([^-)\s]+)-')
        for alt, message in badge.findall(text):
            actual = pom_version(source)
            # `Kafka-4.3_KRaft` says 4.3 and then says what it is; compare the version part.
            for half, raw in (('badge alt text', alt), ('badge URL', message)):
                lead = re.match(r'[\d.]+', raw.strip())
                found.append((f'{label} {half}', lead.group(0) if lead else raw.strip(),
                              actual, mode, None))
    return found


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


def stale_exemptions(documented_vars: set[str], resolvable: set[str],
                     historical_needed: set[tuple], historical_moot: set[tuple]) -> list[str]:
    """EXTERNAL and HISTORICAL entries that have stopped doing anything.

    Both lists exist so that stepping around a check is a decision rather than a hole. The
    same reasoning says the decision has to expire: an exemption nobody needs is a standing
    licence for a claim nobody is checking, and a hand-maintained list only grows. This is
    what `--report-unused-disable-directives` does for the ESLint directives in this repo,
    applied to the checks' own escape hatches.

    Judged only on facts derived from files under version control — what the docs state, what
    the pom and the Dockerfiles declare — so the verdict cannot depend on the state of a
    working tree.
    """
    stale: list[str] = []

    for var in sorted(EXTERNAL):
        if var in resolvable:
            stale.append(
                f'EXTERNAL: `{var}` is redundant — it already resolves as a property, a '
                f'placeholder or an ENV of a runtime image; remove it')
        elif var not in documented_vars:
            stale.append(
                f'EXTERNAL: `{var}` is documented in no table any more — remove it')

    for key in sorted(HISTORICAL):
        if key in historical_needed:
            continue
        doc, label, claimed = key
        if key in historical_moot:
            stale.append(
                f'HISTORICAL: {doc} states {label} `{claimed}`, which now agrees with '
                f'pom.xml — the exemption suppresses nothing; remove it')
        else:
            stale.append(
                f'HISTORICAL: {doc} no longer states {label} `{claimed}` — the prose the '
                f'exemption covered is gone; remove it')

    return stale


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
    # Snapshot before EXTERNAL widens it: an EXTERNAL entry is only needed for a variable
    # nothing else resolves, and that is exactly what this set lets us assert afterwards.
    resolvable_without_external = set(known)
    for var in EXTERNAL:
        known.setdefault(var, None)

    documented_vars: set[str] = set()
    historical_needed: set[tuple] = set()
    historical_moot: set[tuple] = set()

    for name in DOCS:
        path = ROOT / name
        if not path.exists():
            problems.append(f'{name}: listed for checking but missing from the repository')
            continue
        text = path.read_text(encoding='utf-8')

        for var, documented in documented_rows(text):
            documented_vars.add(var)
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


    # Dependency versions stated in prose or in a badge, resolved against the pom.
    for name in VERSION_DOCS:
        path = ROOT / name
        if not path.exists():
            problems.append(f'{name}: listed for version checking but missing from the repository')
            continue
        for label, claimed, actual, mode, exemption in version_claims(
                name, path.read_text(encoding='utf-8')):
            agrees = actual is not None and version_agrees(claimed, actual, mode)
            if exemption is not None:
                # An exemption earns its place only by suppressing a real disagreement.
                (historical_needed if not agrees else historical_moot).add(exemption)
                continue
            checked += 1
            if actual is None:
                problems.append(
                    f'{name}: states {label} `{claimed}` but pom.xml declares no version to '
                    f'check it against')
            elif not agrees:
                problems.append(
                    f'{name}: states {label} `{claimed}`, pom.xml declares `{actual}`')

    problems.extend(stale_exemptions(
        documented_vars, resolvable_without_external, historical_needed, historical_moot))

    print(f'{checked} documented settings resolved against the code, '
          f'{len(EXTERNAL) + len(HISTORICAL)} exemptions audited')
    return problems


if __name__ == '__main__':
    found = check()
    for problem in found:
        print(f'  ✗ {problem}', file=sys.stderr)
    if found:
        print(f'\n{len(found)} documentation claim(s) the code does not support.', file=sys.stderr)
        sys.exit(1)
    print('All resolve.')
