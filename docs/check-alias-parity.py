#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""The `@/` import alias says the same thing in the three files that define it.

The alias exists because components installed from a shadcn registry (React Bits Pro) import as
`@/components/...`. Nothing in the SPA used it when it was added, which is precisely why it needs
a guard: an alias that no existing import exercises is one nothing would notice going wrong.

It is declared three times, once per tool that resolves imports, and none of the three reads the
others:

  * `tsconfig.json`   → `compilerOptions.paths`   — read by `tsc`, so the *typecheck*
  * `vite.config.ts`  → `resolve.alias`           — read by Vite **and** Vitest, so the *build*
                                                     and the *tests*
  * `components.json` → `aliases`                 — read by the shadcn CLI, so where a freshly
                                                     installed component is *written*

The failure mode is quiet in every direction, and that is the point. Drop the Vite entry and
`tsc` still passes while the build cannot resolve the module. Drop the tsconfig entry and the app
builds and runs while the typecheck fails on a path Vite is perfectly happy with. Point
`components.json` somewhere the other two do not describe and `shadcn add` writes a file to a
directory neither the compiler nor the bundler can see — the component is on disk, the import is
red, and nothing names the alias as the cause. None of the three is wrong on its own; they are
only wrong *relative to each other*, so only a check that reads all three can see it.

What this does not do is validate the alias against how components import. That is what the
typecheck and the build already are, and a grep pretending to answer it would be worse than one
that says what it covers.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEBAPP = ROOT / "src/main/webapp"

TSCONFIG = WEBAPP / "tsconfig.json"
VITE = WEBAPP / "vite.config.ts"
COMPONENTS = WEBAPP / "components.json"


def strip_jsonc(text: str) -> str:
    """tsconfig.json is JSONC by specification — the repo's own carries a comment."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    return text


def normalise(path: str) -> str:
    """`src/*`, `./src/*` and `./src` all name one directory; compare the directory."""
    path = path.strip().replace("\\", "/")
    path = re.sub(r"/\*$", "", path)
    path = re.sub(r"^\./", "", path)
    return path.rstrip("/")


def main() -> int:
    problems = []

    # ── tsconfig.json: compilerOptions.paths ────────────────────────────────────────────────
    ts_target = None
    if not TSCONFIG.exists():
        problems.append(f"{TSCONFIG.relative_to(ROOT)} is missing")
    else:
        try:
            ts = json.loads(strip_jsonc(TSCONFIG.read_text()))
        except json.JSONDecodeError as exc:
            problems.append(f"{TSCONFIG.relative_to(ROOT)} is not valid JSONC: {exc}")
            ts = {}
        paths = ts.get("compilerOptions", {}).get("paths", {})
        entry = paths.get("@/*")
        if not entry:
            problems.append(
                "tsconfig.json declares no `compilerOptions.paths['@/*']` — `tsc` cannot resolve "
                "an import from a shadcn registry component")
        else:
            ts_target = normalise(entry[0] if isinstance(entry, list) else entry)
            # `paths` without `baseUrl` requires a relative pattern, and `baseUrl` is deprecated
            # (TS5101) in the TypeScript this repo pins — so the leading `./` is load-bearing.
            raw = entry[0] if isinstance(entry, list) else entry
            if "baseUrl" not in ts.get("compilerOptions", {}) and not raw.startswith("./"):
                problems.append(
                    f"tsconfig.json maps `@/*` to `{raw}` with no `baseUrl`; TypeScript refuses a "
                    f"non-relative target there (TS5090). Write it as `./{raw}`")

    # ── vite.config.ts: resolve.alias ───────────────────────────────────────────────────────
    vite_target = None
    if not VITE.exists():
        problems.append(f"{VITE.relative_to(ROOT)} is missing")
    else:
        body = VITE.read_text()
        # `'@': fileURLToPath(new URL('./src', import.meta.url))` — the URL argument is the target.
        match = re.search(
            r"""["']@["']\s*:\s*fileURLToPath\(\s*new URL\(\s*["']([^"']+)["']""", body)
        if not match:
            match = re.search(r"""["']@["']\s*:\s*[^,\n]*?["']([^"']*src[^"']*)["']""", body)
        if not match:
            problems.append(
                "vite.config.ts declares no `resolve.alias` entry for `@` — Vite and Vitest "
                "cannot resolve an import from a shadcn registry component")
        else:
            vite_target = normalise(match.group(1))

    # ── components.json: aliases ────────────────────────────────────────────────────────────
    if not COMPONENTS.exists():
        problems.append(f"{COMPONENTS.relative_to(ROOT)} is missing")
    else:
        try:
            comp = json.loads(COMPONENTS.read_text())
        except json.JSONDecodeError as exc:
            problems.append(f"components.json is not valid JSON: {exc}")
            comp = {}
        aliases = comp.get("aliases", {})
        if not aliases:
            problems.append("components.json declares no `aliases` block")
        for name, value in sorted(aliases.items()):
            if not value.startswith("@/"):
                problems.append(
                    f"components.json aliases.{name} is `{value}`, which does not start with "
                    f"`@/` — the shadcn CLI would write outside the aliased tree")
        # `utils` must point at a module that exists: it is what every generated component
        # imports `cn` from, and a wrong path there fails at the first install, not at review.
        utils = aliases.get("utils")
        if utils and utils.startswith("@/") and ts_target:
            rel = utils[len("@/"):]
            candidates = [WEBAPP / ts_target / f"{rel}{ext}" for ext in (".ts", ".tsx", "")]
            if not any(c.exists() for c in candidates):
                problems.append(
                    f"components.json aliases.utils points at `{utils}`, which resolves to no "
                    f"file under {ts_target}/ — generated components import `cn` from there")

    # ── the three must agree ────────────────────────────────────────────────────────────────
    if ts_target and vite_target and ts_target != vite_target:
        problems.append(
            f"`@/` resolves to `{ts_target}` for tsc but `{vite_target}` for Vite/Vitest. The "
            f"typecheck and the build would disagree about the same import")

    for problem in problems:
        print(f"  ✗ {problem}")

    if problems:
        print(f"\n{len(problems)} alias parity problem(s). The `@/` alias is declared in "
              f"tsconfig.json, vite.config.ts and components.json, and the three must move "
              f"together.")
        return 1

    print(f"`@/` → {ts_target}/ agrees across tsconfig.json (tsc), vite.config.ts "
          f"(Vite + Vitest) and components.json (shadcn CLI)")
    print("All three agree.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
