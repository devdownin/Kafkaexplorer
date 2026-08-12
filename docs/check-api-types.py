#!/usr/bin/env python3
"""Resolve the frontend's API response types against the Java records they mirror.

`check-links.py` exists because a rotten link is invisible until someone clicks it, and
`check-config-table.py` because a wrong documented default is acted upon. This one exists
because of a crash: `GET /api/topic/{name}` stopped returning value strings and started
returning records, the frontend kept asserting `axios.get<{ samples: string[] }>(…)`, and
nothing failed — TypeScript believes a hand-written annotation. The Compare page rendered a
record as a React child and died with error #31, in production, months later.

A hand-asserted response type is a wish. This makes it a checked claim: every interface in
`src/main/webapp/src/api/types.ts` carrying a `@java <Record>` marker must have exactly the
fields of that Java record, with matching types.

What is compared, and what is deliberately not:

  * **Field names, both directions.** A field the record has and the interface lacks is how a
    frontend silently ignores new data; one the interface has and the record lacks is how it
    reads `undefined` forever. Both are errors.
  * **Types, through a small mapping** (String → string, long/int → number, List<X> → X[],
    Map<K,V> → Record<…>, a record → its interface name, an enum → its union alias). A
    nullable TS type (`string | null`) satisfies the same Java type: Java reference fields are
    all nullable, and the frontend saying so is more honest, not less.
  * **Not** the Javadoc, the ordering, or anything the compiler already enforces.

Java types the mapping does not know are reported rather than skipped — silently accepting an
unknown type is how this check would rot in turn.

Nothing reaches the network: this is parsing and string comparison.

Exit code 1 and a list of what is wrong, or 0 and a count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TYPES = ROOT / 'src/main/webapp/src/api/types.ts'
DOMAIN = ROOT / 'src/main/java/com/yourcompany/kafkasqlexplorer/domain'

# @java <Name> in the doc comment preceding an interface / type alias.
TS_BLOCK = re.compile(
    r'@java\s+(\w+)\s*\*/\s*export\s+(?:interface\s+(\w+)\s*\{(.*?)\n\}|type\s+(\w+)\s*=\s*([^;]+);)',
    re.DOTALL)
# One TS field: name, optional '?', type up to the end of the line.
TS_FIELD = re.compile(r'^\s{2}(\w+)(\??):\s*([^;]+);', re.MULTILINE)
# Java record header + component list.
JAVA_RECORD = re.compile(r'public\s+record\s+(\w+)\s*\((.*?)\)\s*\{', re.DOTALL)
JAVA_ENUM = re.compile(r'public\s+enum\s+(\w+)\s*\{(.*?)\}', re.DOTALL)

SCALARS = {
    'String': 'string',
    'int': 'number', 'Integer': 'number',
    'long': 'number', 'Long': 'number',
    'double': 'number', 'Double': 'number',
    'float': 'number', 'Float': 'number',
    'short': 'number', 'Short': 'number',
    'boolean': 'boolean', 'Boolean': 'boolean',
    'Object': 'unknown',
}


def strip_comments(text: str) -> str:
    """Java comments only — a `/** … */` between components would break the split."""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.DOTALL)
    return re.sub(r'//[^\n]*', ' ', text)


def split_generics(args: str) -> list[str]:
    """Split on commas that are not inside <…>."""
    parts, depth, current = [], 0, ''
    for char in args:
        if char == '<':
            depth += 1
        elif char == '>':
            depth -= 1
        if char == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += char
    if current.strip():
        parts.append(current)
    return [p.strip() for p in parts if p.strip()]


class Unknown(Exception):
    pass


def java_to_ts(java_type: str, known: set[str]) -> str:
    java_type = java_type.strip()
    if java_type in SCALARS:
        return SCALARS[java_type]
    if java_type in known:
        return java_type
    generic = re.fullmatch(r'(\w+)<(.+)>', java_type, re.DOTALL)
    if generic:
        outer, inner = generic.group(1), split_generics(generic.group(2))
        if outer in ('List', 'Set', 'Collection') and len(inner) == 1:
            return f'{java_to_ts(inner[0], known)}[]'
        if outer == 'Map' and len(inner) == 2:
            key = java_to_ts(inner[0], known)
            # A Java map key is a string or a number once it is JSON.
            key = key if key in ('string', 'number') else 'string'
            return f'Record<{key}, {java_to_ts(inner[1], known)}>'
    raise Unknown(java_type)


def normalize(ts_type: str) -> str:
    return re.sub(r'\s+', ' ', ts_type.strip())


NULLABLE = re.compile(r'\s*\|\s*(?:null|undefined)\b')
PRIMITIVES = {'int', 'long', 'double', 'float', 'short', 'boolean', 'byte', 'char'}


def outside_generics(ts_type: str) -> str:
    """The type with every `<…>` payload removed, to look at the top level only."""
    previous = None
    while previous != ts_type:
        previous = ts_type
        ts_type = re.sub(r'<[^<>]*>', '<>', ts_type)
    return ts_type


def accepts(declared: str, expected: str, java_type: str) -> bool:
    """
    A declared TS type satisfies the expected one.

    Nullability is the frontend's business **at any depth**: every Java reference is nullable, and
    a frontend that says so is more honest, not less. `Map<String, String>` really can carry a null
    value — a Kafka header with a null value is legal and the backend puts it through as null — so
    `Record<string, string | null>` is the accurate type, not a sloppy one.

    A Java *primitive* is the exception: JSON serialised from a record can never produce null
    there, so declaring one nullable describes a case that cannot happen.
    """
    declared, expected = normalize(declared), normalize(expected)
    if declared == expected:
        return True
    if NULLABLE.sub('', declared) != NULLABLE.sub('', expected):
        return False
    return java_type.strip() not in PRIMITIVES or not NULLABLE.search(outside_generics(declared))


def parse_java() -> tuple[dict[str, list[tuple[str, str]]], set[str]]:
    records: dict[str, list[tuple[str, str]]] = {}
    enums: set[str] = set()
    for path in sorted(DOMAIN.glob('*.java')):
        source = strip_comments(path.read_text(encoding='utf-8'))
        match = JAVA_RECORD.search(source)
        if match:
            components = []
            for component in split_generics(match.group(2)):
                parts = component.split()
                if len(parts) >= 2:
                    components.append((parts[-1], ' '.join(parts[:-1])))
            records[match.group(1)] = components
            continue
        enum = JAVA_ENUM.search(source)
        if enum:
            enums.add(enum.group(1))
    return records, enums


def parse_ts() -> list[tuple[str, str, dict[str, tuple[str, bool]], str | None]]:
    """(java name, ts name, {field: (type, optional)}, alias body) per @java-marked declaration."""
    source = TYPES.read_text(encoding='utf-8')
    out = []
    for match in TS_BLOCK.finditer(source):
        java_name = match.group(1)
        if match.group(2):
            fields = {
                name: (normalize(ts_type), bool(optional))
                for name, optional, ts_type in TS_FIELD.findall(match.group(3))
            }
            out.append((java_name, match.group(2), fields, None))
        else:
            out.append((java_name, match.group(4), {}, normalize(match.group(5))))
    return out


def main() -> int:
    if not TYPES.exists():
        print(f'{TYPES.relative_to(ROOT)} not found', file=sys.stderr)
        return 1

    records, enums = parse_java()
    declarations = parse_ts()
    if not declarations:
        print('No @java-marked declaration found — the check would pass vacuously.', file=sys.stderr)
        return 1

    known = {ts for _, ts, _, _ in declarations}
    problems: list[str] = []
    checked = 0

    for java_name, ts_name, fields, alias in declarations:
        if alias is not None:
            if java_name not in enums:
                problems.append(f'{ts_name}: @java {java_name} is not an enum in domain/')
            checked += 1
            continue

        if java_name not in records:
            problems.append(f'{ts_name}: @java {java_name} matches no record in domain/')
            continue

        components = dict(records[java_name])
        for name in components:
            if name not in fields:
                problems.append(f'{ts_name}.{name} is missing — the record has it, the frontend ignores it')
        for name in fields:
            if name not in components:
                problems.append(f'{ts_name}.{name} does not exist on {java_name} — it will always read undefined')

        for name, java_type in components.items():
            if name not in fields:
                continue
            declared, _ = fields[name]
            try:
                expected = java_to_ts(java_type, known | enums)
            except Unknown as unknown:
                problems.append(f'{ts_name}.{name}: Java type {unknown} is not in the mapping — '
                                f'extend check-api-types.py rather than leaving it unchecked')
                continue
            if not accepts(declared, expected, java_type):
                problems.append(f'{ts_name}.{name}: declared {declared!r}, but {java_name}.{name} '
                                f'is {java_type} → {expected!r}')
        checked += 1

    if problems:
        print(f'{len(problems)} problem(s) between the frontend API types and the Java records:\n')
        for problem in problems:
            print(f'  - {problem}')
        return 1

    print(f'{checked} API types resolved against their Java records')
    print('All match.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
