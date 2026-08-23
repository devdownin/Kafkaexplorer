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

3. **The Explorer pin names an image that can exist.** `EXPLORER_IMAGE_TAG`'s default is
   written by hand, and Dependabot cannot read a `${VAR:-1.8.8}` form, so nothing moves it on
   its own. Offline, all that can be decided is that the pin is not *ahead* of the newest `v*`
   git tag — an image nobody has published yet.

4. **`--published` only: the pin is not behind what is actually on Docker Hub.** That is the
   staleness reminder, and it deliberately asks the registry rather than the git tags, because
   a tag is not an image. Two ways that gap bites: for the ten to twenty minutes a release
   workflow takes, the tag exists and the image does not — this check demanded a bump to
   `1.8.9` while `1.8.9` was still building, and taking it would have pointed the stack at a
   manifest that did not exist; and a release whose publication *failed* (which has happened
   here — a refused Docker Hub login took `v1.4.0` down) would leave a tag with no image
   behind it for ever, blocking every pull request on a bump that could never be made.

That is why 4 is a flag and not the default: the three offline checks gate every pull request,
where being wrong costs somebody else's afternoon, and the registry question is asked where the
network is already a dependency — the `spectra-hub-stack` job, which pulls those very images.
A registry that cannot be reached is reported and does not fail that job: "we asked and it is
stale" and "we could not ask" are different answers, and only the first is a defect here.

Checks 1–3 require tags to be present (`git fetch --tags`); they fail rather than skipping if
they are not, because a check that quietly does nothing is worse than no check.

Exit code 1 and what is wrong, or 0 and a count.
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request

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


def version_of(tag: str | None) -> tuple[int, ...] | None:
    """(major, minor, patch) for an `X.Y.Z` tag, or None for anything else."""
    parts = (tag or "").split(".")
    if len(parts) == 3 and all(p.isdigit() for p in parts):
        return tuple(int(p) for p in parts)
    return None


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


DOCKER_HUB_TAGS = ("https://hub.docker.com/v2/repositories/"
                   "compagnonsdudev/kafkaexplorer/tags?page_size=100")


def newest_published() -> tuple[str | None, str | None]:
    """The newest `X.Y.Z` tag on Docker Hub, or (None, why-not).

    The registry is the authority on what an `up` can pull; the git tags are not. A tag exists
    the moment it is pushed, the image only once the release workflow has finished — and only
    if it finished at all.
    """
    try:
        with urllib.request.urlopen(DOCKER_HUB_TAGS, timeout=20) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
        return None, f"{type(error).__name__}: {error}"
    versions = []
    for entry in payload.get("results", []):
        parts = str(entry.get("name", "")).split(".")
        if len(parts) == 3 and all(p.isdigit() for p in parts):
            versions.append((tuple(int(p) for p in parts), entry["name"]))
    if not versions:
        return None, "the registry listed no X.Y.Z tag"
    return max(versions)[1], None


def main() -> int:
    published_check = "--published" in sys.argv[1:]
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

    # 3. The Explorer pin must not name an image nobody has published (offline).
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
    elif version_of(pinned) and version_of(pinned) > version_of(release):
        problems.append(
            f"the hub stack pulls kafkaexplorer:{pinned}, which is ahead of the newest release "
            f"v{release} — no such image has been published"
        )

    # 4. And, when asked to look, not be behind what the registry actually serves.
    if published_check and pinned and not problems:
        newest, why_not = newest_published()
        if newest is None:
            print(f"::warning::could not ask Docker Hub what it serves ({why_not}) — "
                  f"the Explorer pin (kafkaexplorer:{pinned}) was not checked for staleness")
        elif version_of(pinned) and version_of(newest) > version_of(pinned):
            problems.append(
                f"the hub stack pulls kafkaexplorer:{pinned} while Docker Hub serves "
                f"{newest} — bump EXPLORER_IMAGE_TAG's default in "
                "docker-compose-spectra-hub.yml (and in .env.example)"
            )
        else:
            print(f"Docker Hub serves kafkaexplorer:{newest}; the pin is current.")

    if problems:
        for problem in problems:
            print(f"  ✗ {problem}")
        print(f"\n{len(problems)} image pin problem(s).")
        return 1

    print(f"{len(refs)} image references checked across "
          f"{len(set(p for p, _, _ in refs))} compose files")
    print(f"All pinned; llama.cpp CPU/CUDA agree; kafkaexplorer:{pinned} is not ahead of v{release}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
