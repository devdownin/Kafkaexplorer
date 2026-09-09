#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Every `explorer.*` key shipped in application.yml has something that reads it.

`check-config-table.py` resolves the settings the *documentation* names against the code. It
therefore cannot see a setting nobody wrote down — and that is exactly where one rots unnoticed:
`explorer.inference-poll-timeout-ms` shipped in `application.yml` with a value, carried a getter
and a setter on `ExplorerConfig`, was read by nothing in `src/main` or `src/test`, and was absent
from every documentation table. A settable, shipped, silently inert knob, invisible to the one
check that exists to keep settings honest.

This asks the other half of the question, from the YAML rather than from the prose: for each key
under `explorer:`, is there any code that reads the property it binds to?

**Field reads count, not only getter calls.** `consumer-group-prefix` and `internal-topic-prefix`
are applied from `ExplorerConfig`'s own `@PostConstruct`, which touches the field directly and
never calls the getter; a check looking only for `getX()` would report both as dead and would be
wrong twice. That false positive is the reason this reads both forms.

**A sub-tree may bind to a class of its own.** `explorer.mcp.*` is bound by `McpProperties`, a
`@ConfigurationProperties(prefix = "explorer.mcp")` class, not by a getter on `ExplorerConfig` —
so the getter-or-field test would report the whole sub-tree dead and be wrong about every key
under it. A key is therefore also read when some class declares that prefix *and* something
injects that class; the second half matters, since a properties class nobody injects is exactly
the dead knob this check exists to find, one level down.

**And then the sub-tree is descended, because the rule above was itself a blind spot.** Treating
`explorer.mcp` as one answered key made every leaf under it invisible to the check written to find
invisible leaves: `explorer.mcp.tools.kip1318-aliases` shipped with a value, a field, a getter and
a setter, was read by nothing, and passed — the exact defect that produced this file, one level
further down and hidden by its own fix. So a sub-tree bound by its own class is now resolved leaf
by leaf against that class's accessors, and a leaf whose getter nothing calls is reported like any
other dead key. The class still has to be injected: a properties class nobody injects fails whole,
before any leaf is looked at.

The leaf pass resolves on the **accessor name**, unqualified, which is deliberately imprecise in
one direction only. A leaf called `mode` passes when any `getMode()` exists anywhere, so a dead
`dlp.mode` could hide behind an unrelated one — that is a false negative, and the alternative
(matching a receiver) is a type resolution this file has no business attempting. What it must
never do is the other error: a false positive here would be a correct setting reported dead, and
a check people learn to ignore is worth less than the one it replaced. Today it resolves every
leaf of the tree, and the imprecision is named rather than hidden.

What it deliberately does **not** do is reachability. `explorer.max-concurrent-jobs` has a reader —
`FlinkSqlService.refuseIfTooManyJobsAreHeld` — that no HTTP path can currently reach, so this
check passes it and should: knowing a code path cannot be entered is a different and much larger
tool, and a grep that pretended to answer it would be worse than one that says what it covers.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
YAML = ROOT / "src/main/resources/application.yml"
CONFIG = ROOT / "src/main/java/com/compagnonsdudev/kafkasqlexplorer/config/ExplorerConfig.java"
SOURCES = [ROOT / "src/main/java", ROOT / "src/test/java"]
JAVA_ROOT = ROOT / "src/main/java"

# A key whose reader is not Java at all. Name it here, with the reason, so the exemption is a
# decision rather than a hole — and see `unused_exemptions` below, which expires it.
EXTERNAL: dict[str, str] = {}


def subtree_leaves(text: str, key: str) -> list[str]:
    """The dotted leaf paths under `explorer.<key>:`, in order.

    A leaf is a line carrying a value; a line ending in `:` opens a nested block and is a binder's
    nested class rather than a setting. Indentation alone decides the nesting, which is enough here
    because `application.yml` is written with two spaces per level throughout and this only ever
    reads one sub-tree of it.
    """
    leaves: list[str] = []
    stack: list[tuple[int, str]] = []
    inside = False
    base_indent = None
    for line in text.splitlines():
        if re.match(rf"^  {re.escape(key)}:\s*$", line):
            inside = True
            continue
        if not inside:
            continue
        if line.strip() and not line.startswith("    "):
            break                                   # back out to another `explorer:` key
        m = re.match(r"^(\s+)([a-z0-9-]+):(.*)$", line)
        if not m:
            continue                                # a comment, a blank line, or a list item
        indent, name, rest = len(m.group(1)), m.group(2), m.group(3).strip()
        if base_indent is None:
            base_indent = indent
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if rest and not rest.startswith("#"):
            leaves.append(".".join([p for _, p in stack] + [name]))
        else:
            stack.append((indent, name))
    return leaves


def yaml_keys(text: str) -> list[str]:
    """The keys nested directly under `explorer:`, in order."""
    keys, inside = [], False
    for line in text.splitlines():
        if re.match(r"^explorer:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line[0].isspace():
                break
            m = re.match(r"^  ([a-z0-9-]+):", line)
            if m:
                keys.append(m.group(1))
    return keys


def nested_properties_classes() -> dict[str, str]:
    """`explorer.<key>` -> the class binding it, for sub-trees bound outside ExplorerConfig."""
    bound = {}
    if not JAVA_ROOT.exists():
        return bound
    for f in JAVA_ROOT.rglob("*.java"):
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'@ConfigurationProperties\s*\(\s*(?:prefix\s*=\s*)?"explorer\.([a-z0-9-]+)"', text):
            bound[m.group(1)] = f.stem
    return bound


def camel(key: str) -> str:
    head, *rest = key.split("-")
    return head + "".join(w[:1].upper() + w[1:] for w in rest)


def dead_leaves(key: str, binder: str, corpus: str) -> tuple[list[str], int]:
    """The leaves of `explorer.<key>` whose accessor nothing outside the binder class calls.

    The binder's own file is removed from the corpus first: every leaf has a getter and a setter
    *there* by construction, so leaving it in would make each leaf read itself.
    """
    binder_file = next((f for f in JAVA_ROOT.rglob(f"{binder}.java")), None)
    if binder_file is None:
        return [], 0
    outside = corpus.replace(binder_file.read_text(encoding="utf-8", errors="replace"), "")
    unread, seen = [], 0
    for leaf in subtree_leaves(YAML.read_text(encoding="utf-8"), key):
        seen += 1
        prop = camel(leaf.rsplit(".", 1)[-1])
        accessor = rf"\.(get|is){prop[:1].upper()}{prop[1:]}\s*\("
        if not re.search(accessor, outside):
            unread.append(f"{key}.{leaf}")
    return unread, seen


def main() -> int:
    if not YAML.exists() or not CONFIG.exists():
        print("check-config-yaml: application.yml or ExplorerConfig.java is missing", file=sys.stderr)
        return 1

    keys = yaml_keys(YAML.read_text(encoding="utf-8"))
    if not keys:
        print("check-config-yaml: no keys found under `explorer:` — has the file moved?", file=sys.stderr)
        return 1

    corpus = "\n".join(
        f.read_text(encoding="utf-8", errors="replace")
        for root in SOURCES if root.exists()
        for f in root.rglob("*.java")
    )
    config_body = CONFIG.read_text(encoding="utf-8")
    nested = nested_properties_classes()

    dead, checked, leaves = [], 0, 0
    for key in keys:
        if key in EXTERNAL:
            continue
        checked += 1
        # A sub-tree with a properties class of its own, injected somewhere. Both halves are
        # required: a class nobody injects binds a sub-tree nobody reads.
        binder = nested.get(key)
        if binder:
            if not re.search(rf"(?<![\w.]){binder}\s+\w+\s*[,)=;]", corpus):
                dead.append(key)                    # a properties class nobody injects
                continue
            found, count = dead_leaves(key, binder, corpus)
            dead.extend(found)
            leaves += count
            continue
        prop = camel(key)
        getter = re.search(rf"\.(get|is){prop[:1].upper()}{prop[1:]}\s*\(", corpus)
        # A field read inside ExplorerConfig itself, past its declaration and its own accessors.
        field = re.search(rf"(?<![\w.]){prop}(?![\w(])", re.sub(
            rf"(private [\w<>, .]+ {prop}\b[^;]*;)|(this\.{prop} =)|(return {prop};)", "", config_body))
        if not getter and not field:
            dead.append(key)

    for key in dead:
        print(f"  ✗ explorer.{key}: shipped in application.yml, and nothing reads it — "
              f"remove the key and its accessors, or wire it up (add it to EXTERNAL if its "
              f"reader is not Java)")

    stale = [k for k in EXTERNAL if k not in keys]
    for key in stale:
        print(f"  ✗ EXTERNAL names explorer.{key}, which application.yml no longer ships — "
              f"drop the exemption")

    if dead or stale:
        print(f"\n{len(dead) + len(stale)} setting(s) with no reader.")
        return 1
    print(f"{checked} shipped explorer.* settings resolved to a reader, plus {leaves} leaf "
          f"setting(s) of a sub-tree bound by its own class, "
          f"{len(EXTERNAL)} exemption(s) audited")
    print("All read.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
