#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""The screenshot fixtures against the frontend contracts they claim to satisfy.

`docs/screenshots/README.md` names this failure mode itself, and then nothing checked it:

    The fixtures are shaped against the frontend's own TypeScript contracts
    (`TopicSearchResponse`, `SchemaInfo`, `AuditReport`, `ParsedFlow`…). When one of those
    contracts changes, the screenshot is where it shows: a missing field renders as `NaN` or
    an empty panel rather than failing loudly.

"Rather than failing loudly" is the whole problem. `fixtures.mjs` is plain JavaScript with no
type behind it, so a field renamed in `api/types.ts` leaves the stub answering the old name, the
page renders the gap as an empty panel, and **the screenshot ships that panel** — into README.md
and onto the Pages site. Worse, `layout-probe --check` measures those same pages and gates a pull
request on budgets taken against content that never rendered. That is the same defect class as an
unstubbed route (`docs/screenshots/unstubbed.mjs`), one level down: there the route answered 404,
here it answers 200 with the wrong shape, which no HTTP status can reveal.

WHAT THIS CHECKS, AND WHAT IT DELIBERATELY DOES NOT. Field **names**, both directions, at the top
level of each mapped export:

  * a required interface field the fixture omits — the page reads `undefined`;
  * a fixture key the interface does not declare — a stub answering a shape nobody asked for,
    which is how a fixture goes on describing an endpoint that has since changed.

It does **not** check types, and that is a scope decision rather than an omission: types would
need a TypeScript checker over a file that is not in the `src` the tsconfig compiles, and the
failure the README describes is a name-level one. Nor does it recurse into nested objects — the
top level is where a renamed response field lands, and a check that pretends to more coverage
than it has is worse than one that says what it covers.

THE MAPPING IS DECLARED, NOT DISCOVERED, because nothing in the tree links the two: `server.mjs`
routes a path to a fixture, and the interface the SPA casts the answer to lives at the `axios.get`
call site. Guessing that link from the route name would be a heuristic that fails silently in
exactly the cases worth catching. Every export must therefore appear in CONTRACTS or in
UNCONTRACTED with a reason, and a new export in neither **fails** — the rule `layout-probe.mjs`
applies to a measured page with no budget, so a fixture cannot be added without someone saying
what it must satisfy.

Exit code 1 and what drifted, or 0 and a count.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TYPES = ROOT / "src/main/webapp/src/api/types.ts"
FIXTURES = ROOT / "docs/screenshots/fixtures.mjs"

# fixture export → the interface in api/types.ts its shape must satisfy.
CONTRACTS = {
    "dashboard": "DashboardResponse",
    "topicDetail": "TopicDetailResponse",
    "topicSearch": "TopicSearchResponse",
    "queryInit": "QueryInitResponse",
    "queryResult": "QueryResult",
    "auditReport": "AuditReport",
    "auditHistory": "AuditHistory",
    "metrics": "MetricConfig",
    "metricSuggestions": "MetricSuggestions",
    "dataModel": "DataModelResponse",
    "dataModelLimits": "DataModelLimits",
    "ddlPreview": "DdlPreviewResponse",
    "topicActivity": "TopicActivityResponse",
}

# Exports with no interface to check against, each with the reason. A reason rather than a bare
# list: "there is no contract" and "nobody has written one down yet" are different facts, and the
# first is a decision while the second is a gap someone should close.
UNCONTRACTED = {
    "NOW": "the fixed reference instant, a number rather than a response",
    "TOPICS": "the topic-name catalogue the other fixtures are derived from",
    "QUERY_SQL": "the SQL string the editor screenshot opens with",
    "topicSizes": "a bare `Record<string, number>` the dashboard merges; no named interface",
    "topicLastMessages": "same shape and same reason as topicSizes",
    "querySchema": "a `Record<table, column[]>` declared at the axios call site",
    "streamFlow": "ParsedFlow lives in the Stream Flow page module, not in api/types.ts",
    "cluster": "the cluster response is typed at its call site, not in api/types.ts",
    "config": "same — GET /api/config is read into a locally declared shape",
    "metricTemplates": "the template descriptors are declared inside Metrics.tsx",
    "labelPreview": "MetricLabelPreview is declared inside Metrics.tsx, not in api/types.ts",
    "lineage": "the lineage response is typed at its call site",
}

FIELD = re.compile(r"^  (\w+)(\??):", re.MULTILINE)


def interface_fields(text: str, name: str) -> tuple[set[str], set[str]] | None:
    """Top-level field names of `export interface <name>`, as (all, required)."""
    start = re.search(rf"^export interface {name}\s*\{{", text, re.MULTILINE)
    if not start:
        return None
    # The interface ends at the first `}` in column 0 — the file's own formatting, and the same
    # assumption check-api-types.py makes about this file.
    end = text.index("\n}", start.end())
    body = text[start.end():end]
    fields = FIELD.findall(body)
    return {f for f, _ in fields}, {f for f, opt in fields if opt != "?"}


def fixture_shapes() -> dict[str, list[str]]:
    """Import fixtures.mjs and report the top-level keys of each export.

    Through node rather than by parsing the file: the exports are computed (`Object.fromEntries`,
    a spread, a function of the URL), so reading the source would describe the literal rather than
    the object the server actually answers with — which is the thing under test.
    """
    script = """
      import * as F from './docs/screenshots/fixtures.mjs';
      const url = new URL('http://127.0.0.1/api/x?topic=demo.orders.5.shipped&window=24h');
      const shape = (v) => {
        if (typeof v === 'function') v = v(url);
        if (Array.isArray(v)) v = v[0];
        return v && typeof v === 'object' ? Object.keys(v) : null;
      };
      const out = {};
      for (const [name, value] of Object.entries(F)) out[name] = shape(value);
      console.log(JSON.stringify(out));
    """
    proc = subprocess.run(
        ["node", "--input-type=module", "-e", script],
        cwd=ROOT, capture_output=True, text=True)
    if proc.returncode != 0:
        print(f"  ✗ could not import fixtures.mjs:\n{proc.stderr.strip()}")
        sys.exit(1)
    return json.loads(proc.stdout)


def main() -> int:
    text = TYPES.read_text()
    shapes = fixture_shapes()
    problems: list[str] = []

    for name in sorted(shapes):
        if name in UNCONTRACTED or name in CONTRACTS:
            continue
        problems.append(
            f"fixtures.mjs exports `{name}`, which is in neither CONTRACTS nor UNCONTRACTED — "
            f"name the interface it must satisfy, or say why it has none")

    for export, interface in sorted(CONTRACTS.items()):
        if export not in shapes:
            problems.append(f"CONTRACTS names `{export}`, which fixtures.mjs no longer exports")
            continue
        keys = shapes[export]
        if keys is None:
            problems.append(f"`{export}` is not an object, so it cannot satisfy `{interface}`")
            continue
        declared = interface_fields(text, interface)
        if declared is None:
            problems.append(f"`{interface}` (for `{export}`) is not an interface in api/types.ts")
            continue
        all_fields, required = declared
        for missing in sorted(required - set(keys)):
            problems.append(
                f"`{export}` omits `{missing}`, which `{interface}` declares as required — "
                f"the page reads undefined and renders an empty panel")
        for extra in sorted(set(keys) - all_fields):
            problems.append(
                f"`{export}` carries `{extra}`, which `{interface}` does not declare — "
                f"a stub answering a shape nobody asked for")

    for export in sorted(UNCONTRACTED):
        if export not in shapes:
            problems.append(f"UNCONTRACTED names `{export}`, which fixtures.mjs no longer exports")

    if problems:
        print("The screenshot fixtures have drifted from the frontend contracts:\n")
        for p in problems:
            print(f"  ✗ {p}")
        print(f"\n{len(problems)} fixture problem(s).")
        return 1

    print(f"{len(CONTRACTS)} fixture(s) match their interface, "
          f"{len(UNCONTRACTED)} exemption(s) audited")
    print("All match.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
