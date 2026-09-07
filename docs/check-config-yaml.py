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

    dead, checked = [], 0
    for key in keys:
        if key in EXTERNAL:
            continue
        checked += 1
        # A sub-tree with a properties class of its own, injected somewhere. Both halves are
        # required: a class nobody injects binds a sub-tree nobody reads.
        binder = nested.get(key)
        if binder and re.search(rf"(?<![\w.]){binder}\s+\w+\s*[,)=;]", corpus):
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
    print(f"{checked} shipped explorer.* settings resolved to a reader, "
          f"{len(EXTERNAL)} exemption(s) audited")
    print("All read.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
