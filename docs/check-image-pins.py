#!/usr/bin/env python3
"""Check the container images the compose stacks pull: pinned, consistent, and not stale.

`check-config-table.py` resolves what the documentation *claims*; `compose-lint` in ci.yml
resolves what compose can *parse*. Neither reads the one thing a deployment file is mostly
made of — the image references — and three ways of getting those wrong all end the same way:
somebody runs a stack and gets something other than what this checkout was tested with.

Three checks, all offline, all on facts already in the repository.

1. **Nothing floats.** An image with no tag, or tagged `latest`, serves whatever was published
   the day of the pull. The tree pins everything else — base images by digest in the
   Dockerfiles, `apache/kafka:4.3.1`, `ollama/ollama`, `chromadb/chroma` — and the comment on
   that Ollama pin claimed it was "the only floating tag left in the tree" while
   `curlimages/curl:latest` sat two services below it. A claim about pinning is exactly the
   kind that decays unread.

2. **The llama.cpp CPU and CUDA images are the same build.** `docker-compose-spectra-hub.gpu.yml`
   swaps `server-bNNNN` for `server-cuda-bNNNN`; if the two build numbers drift apart, turning
   on the GPU overlay quietly changes the inference engine's revision underneath a stack that
   pins everything else — which is the whole reason that tag is pinned at all.

3. **The Explorer image the hub stack pulls is the current release.** `EXPLORER_IMAGE_TAG`'s
   default is written by hand, and Dependabot cannot read a `${VAR:-1.8.8}` form, so nothing
   would ever move it: the stack would go on serving an old image to everyone who does not pin,
   silently, for as long as nobody noticed. Compared against the newest `v*` git tag. This is
   the one check that fails on a *release* rather than on a change — that is the point: the
   reminder arrives when the pin becomes stale, not months later.

Requires tags to be present (`git fetch --tags`); it fails rather than skipping if they are
not, because a check that quietly does nothing is worse than no check.

Exit code 1 and what is wrong, or 0 and a count.
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

IMAGE_LINE = re.compile(r"^\s*image:\s*(\S+)\s*$", re.M)
# ${VAR:-default} / ${VAR-default} / ${VAR}
INTERPOLATION = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::?-([^}]*))?\}")

# An image built by a stack rather than pulled: it exists only in the local daemon, and its
# tag names the build, not a version. Named here so it is a decision rather than a hole.
LOCAL_ONLY = {"kafka-sql-explorer:ci"}


def expand(ref: str) -> str:
    """Resolve a compose image reference to what an `up` with no environment would pull."""
    return INTERPOLATION.sub(lambda m: m.group(2) or "", ref)


def image_refs() -> list[tuple[pathlib.Path, str, str]]:
    found = []
    for path in sorted(ROOT.glob("docker-compose*.yml")):
        for raw in IMAGE_LINE.findall(path.read_text(encoding="utf-8")):
            found.append((path, raw, expand(raw)))
    return found


def tag_of(image: str) -> str | None:
    """The tag of an image reference, or None when it carries none.

    Split on the last colon, but only past the last slash: a registry host may carry a port
    (`localhost:5000/foo`), which is not a tag.
    """
    host, _, rest = image.rpartition("/")
    name, sep, tag = rest.rpartition(":")
    return tag if sep and name else None


def newest_release() -> str | None:
    try:
        out = subprocess.run(
            ["git", "tag", "--list", "v*"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.split()
    except (OSError, subprocess.CalledProcessError):
        return None
    versions = []
    for tag in out:
        parts = tag[1:].split(".")
        if len(parts) == 3 and all(p.isdigit() for p in parts):
            versions.append((tuple(int(p) for p in parts), tag[1:]))
    if not versions:
        return None
    return max(versions)[1]


def main() -> int:
    problems: list[str] = []
    refs = image_refs()

    for path, raw, resolved in refs:
        if resolved in LOCAL_ONLY:
            continue
        tag = tag_of(resolved)
        rel = path.relative_to(ROOT)
        if tag is None:
            problems.append(f"{rel}: `{raw}` carries no tag — it would pull `latest`")
        elif tag == "latest":
            problems.append(f"{rel}: `{raw}` is pinned to `latest`")

    # 2. The two llama.cpp variants must name the same build.
    builds = {}
    for path, raw, resolved in refs:
        if "llama.cpp" not in resolved:
            continue
        tag = tag_of(resolved) or ""
        match = re.search(r"(b\d+)$", tag)
        if not match:
            problems.append(
                f"{path.relative_to(ROOT)}: llama.cpp is tagged `{tag}`, which names no build "
                f"number — a floating tag there is rebuilt from master several times a day"
            )
            continue
        builds.setdefault(match.group(1), set()).add(tag)
    if len(builds) > 1:
        listed = ", ".join(f"{b} ({'/'.join(sorted(t))})" for b, t in sorted(builds.items()))
        problems.append(
            "the llama.cpp CPU and CUDA images name different builds: " + listed
            + " — the GPU overlay must not change the engine's revision"
        )

    # 3. The Explorer pin against the newest release.
    pinned = None
    for _, raw, resolved in refs:
        if "kafkaexplorer" in resolved:
            pinned = tag_of(resolved)
            break
    release = newest_release()
    if pinned is None:
        problems.append("no compose file pulls a kafkaexplorer image — has it been renamed?")
    elif release is None:
        problems.append(
            "no `vX.Y.Z` git tag is available, so the Explorer pin cannot be checked. "
            "Run `git fetch --tags` (CI does it in the checkout step)."
        )
    elif pinned != release:
        newer = tuple(int(p) for p in release.split(".")) > tuple(
            int(p) for p in pinned.split(".") if p.isdigit()
        ) if all(p.isdigit() for p in pinned.split(".")) else True
        if newer:
            problems.append(
                f"the hub stack pulls kafkaexplorer:{pinned} while the newest release is "
                f"v{release} — bump EXPLORER_IMAGE_TAG's default in "
                "docker-compose-spectra-hub.yml (and in .env.example)"
            )
        else:
            problems.append(
                f"the hub stack pulls kafkaexplorer:{pinned}, which is ahead of the newest "
                f"release v{release} — that image is not published yet"
            )

    if problems:
        for problem in problems:
            print(f"  ✗ {problem}")
        print(f"\n{len(problems)} image pin problem(s).")
        return 1

    print(f"{len(refs)} image references checked across "
          f"{len(set(p for p, _, _ in refs))} compose files")
    print(f"All pinned; llama.cpp CPU/CUDA agree; kafkaexplorer matches v{release}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
