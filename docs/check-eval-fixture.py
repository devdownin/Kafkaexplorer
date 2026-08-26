#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Resolve the Process Mining eval fixture against the seeder it claims to describe.

`src/test/resources/eval/demo-order-pipeline.json` is the golden dataset the eval is written
against: the order pipeline `setup-demo.sh` seeds, as records. Its whole value rests on one claim —
that it is what the seeder produces — and nothing executed that claim. A fixture that has drifted
from the dataset it names does not fail; it evaluates the wrong thing, confidently, which is the
exact failure mode the eval exists to remove.

So every topic, every id and every corrupt payload the fixture carries is resolved here against
`setup-demo.sh`, and the pipeline order is compared with the seeder's own `STEP_TOPICS` array. No
network, no broker, no build: same shape as the other `docs/check-*.py`, and discovered by the same
`for check in docs/check-*.py` loop in `ci.yml`.

What it deliberately does *not* check is that the fixture holds every record the seeder writes. It
is a fixture, not a mirror: it carries the order pipeline and the records that make its findings
(the drop-off, the redeliveries, the corrupt pair, the header-only correlations), and the eval
states what it covers. Demanding completeness would make every addition to the demo cluster a
failing build for a reason nobody could act on.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "src/test/resources/eval/demo-order-pipeline.json"
SEEDER = ROOT / "setup-demo.sh"


def shell_literal(value: str) -> str:
    """The form a JSON payload takes inside setup-demo.sh: double quotes are backslash-escaped."""
    return value.replace('"', '\\"')


def step_topics(seeder: str) -> list[str]:
    """The seeder's own STEP_TOPICS array — the pipeline order, from the source of truth."""
    match = re.search(r"^STEP_TOPICS=\((.*?)\)$", seeder, re.MULTILINE | re.DOTALL)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def main() -> int:
    if not FIXTURE.exists():
        print(f"  ✗ the eval fixture is missing: {FIXTURE.relative_to(ROOT)}")
        return 1
    if not SEEDER.exists():
        print(f"  ✗ the seeder is missing: {SEEDER.relative_to(ROOT)}")
        return 1

    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    seeder = SEEDER.read_text(encoding="utf-8")
    records = fixture.get("records", [])
    problems: list[str] = []
    checked = 0

    if not records:
        print("  ✗ the eval fixture carries no record at all")
        return 1

    # 1. Every topic the fixture names is one the seeder creates.
    topics = sorted({record["topic"] for record in records})
    for topic in topics:
        checked += 1
        if f'"{topic}' not in seeder:
            problems.append(f"topic {topic} is in the fixture and in no line of setup-demo.sh")

    # 2. The order pipeline is in the seeder's order. The eval asserts an edge per consecutive
    #    pair, so a fixture that reordered them would assert a pipeline nobody seeds.
    seeded_steps = step_topics(seeder)
    fixture_steps = [t for t in topics if re.match(r"^demo\.orders\.\d+\.", t)]
    if not seeded_steps:
        problems.append("STEP_TOPICS could not be read from setup-demo.sh — this check is blind")
    else:
        checked += 1
        expected = [t for t in seeded_steps if t in set(fixture_steps)]
        if expected != sorted(fixture_steps):
            problems.append(
                f"the fixture's order topics {sorted(fixture_steps)} are not the seeder's "
                f"pipeline order {expected}")

    # 3. Every order id the fixture uses is one the seeder writes, in the field the fixture maps.
    correlation = fixture.get("correlationIdPath", "id")
    for record in records:
        key = record.get("key")
        if key is None or not record["topic"].startswith("demo.orders."):
            continue
        checked += 1
        needle = shell_literal(f'"{correlation}":"{key}"')
        if needle not in seeder:
            problems.append(
                f"{key} is in the fixture and no payload in setup-demo.sh carries "
                f"{correlation}={key}")

    # 4. A key the fixture writes twice into one topic is a redelivery, and the seeder has to make
    #    it one — that is what the audit's duplicate detection and the model's repeats rest on.
    seen: dict[tuple[str, str], int] = {}
    for record in records:
        if record.get("key") is not None:
            pair = (record["topic"], record["key"])
            seen[pair] = seen.get(pair, 0) + 1
    for (topic, key), count in sorted(seen.items()):
        if count < 2:
            continue
        checked += 1
        produced = seeder.count(shell_literal(f'"{correlation}":"{key}"'))
        if produced < 2:
            problems.append(
                f"the fixture redelivers {key} on {topic}, but setup-demo.sh writes it "
                f"{produced} time(s) — the seeder no longer produces that duplicate")

    # 5. The corrupt payloads are matched verbatim: the eval's sharpest assertion is that a
    #    truncated record does not become a case, and it is only about this dataset if these are
    #    the bytes the seeder writes.
    for record in records:
        if record.get("key") is not None:
            continue
        checked += 1
        if shell_literal(record["value"]) not in seeder:
            problems.append(
                f"a corrupt payload on {record['topic']} is in the fixture and not in "
                f"setup-demo.sh: {record['value'][:60]}…")

    # 6. The header-correlated topics must not carry the order id in their payload — that property
    #    is the whole reason those records fall outside the event log, and the eval asserts it.
    order_ids = {r["key"] for r in records
                 if r.get("key") and r["topic"].startswith("demo.orders.")}
    for record in records:
        if record["topic"].startswith("demo.orders."):
            continue
        checked += 1
        leaked = [oid for oid in order_ids if oid in record["value"]]
        if leaked:
            problems.append(
                f"{record['topic']} carries {', '.join(sorted(leaked))} in its payload — the "
                "fixture's header-only correlation is not header-only any more")

    for problem in problems:
        print(f"  ✗ {problem}")
    if problems:
        print(f"\n{len(problems)} eval fixture problem(s).")
        return 1
    print(f"{checked} eval fixture claims resolved against setup-demo.sh")
    print("All resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
