#!/usr/bin/env python3
"""Audit release follow-ups and CI failures. Closing is opt-in and narrowly scoped.

Uses the GitHub CLI already installed on GitHub-hosted runners. No third-party
dependencies; never checks out or executes code from an untrusted pull request.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FOLLOWUP = re.compile(r"^chore/release-followup-(\d+\.\d+\.\d+)$")
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
ALLOWED = {".env.example", "CHANGELOG.md", "compose/image.yml", "compose/spectra-hub.yml"}


def gh(*args: str, input_data: str | None = None):
    proc = subprocess.run(["gh", "api", *args], input=input_data,
                          text=True, capture_output=True, check=False)
    if proc.returncode:
        raise RuntimeError(f"gh api {' '.join(args)} failed: {proc.stderr.strip()}")
    return json.loads(proc.stdout) if proc.stdout.strip() else None


def version_tuple(value: str) -> tuple[int, int, int]:
    if not VERSION.fullmatch(value):
        raise ValueError(f"invalid release version: {value}")
    return tuple(map(int, value.split(".")))


def repository_version() -> str:
    match = re.search(r"(?m)^EXPLORER_IMAGE_TAG=(\d+\.\d+\.\d+)\s*$",
                      (ROOT / ".env.example").read_text())
    if not match:
        raise RuntimeError("Cannot determine the published Explorer pin")
    return match.group(1)


def candidate(pr: dict, current: str, files: set[str], repo: str) -> bool:
    head = pr.get("head") or {}
    base = pr.get("base") or {}
    match = FOLLOWUP.fullmatch(head.get("ref", ""))
    if not match or base.get("ref") != "main":
        return False
    if (head.get("repo") or {}).get("full_name") != repo:
        return False
    if pr.get("title") != f"Catch the repository up to {match.group(1)}":
        return False
    return (version_tuple(match.group(1)) < version_tuple(current)
            and bool(files) and files <= ALLOWED
            and {"CHANGELOG.md", ".env.example"} <= files)


def summary(message: str) -> None:
    print(message)
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(message + "\n")


def cleanup(repo: str, close: bool) -> None:
    current = repository_version()
    prs = gh(f"repos/{repo}/pulls?state=open&per_page=100")
    # Use pagination even when the repository grows beyond its current number of PRs.
    page = 2
    while len(prs) >= (page - 1) * 100:
        batch = gh(f"repos/{repo}/pulls?state=open&per_page=100&page={page}")
        if not batch:
            break
        prs.extend(batch)
        page += 1

    summary(f"### Release follow-up audit (current pin: {current})")
    obsolete = 0
    for pr in prs:
        head_ref = (pr.get("head") or {}).get("ref", "")
        if not FOLLOWUP.fullmatch(head_ref):
            continue
        number = pr["number"]
        files = set()
        idx = 1
        while True:
            batch = gh(f"repos/{repo}/pulls/{number}/files?per_page=100&page={idx}")
            files.update(item["filename"] for item in batch)
            if len(batch) < 100:
                break
            idx += 1
        if not candidate(pr, current, files, repo):
            summary(f"- #{number}: retained (not a proven superseded release-only PR)")
            continue
        obsolete += 1
        if close:
            gh(f"repos/{repo}/issues/{number}/comments", "--method", "POST",
               "-f", f"body=Superseded by the repository's {current} Explorer pin. Closing this release-only follow-up; no source branch is deleted.")
            gh(f"repos/{repo}/pulls/{number}", "--method", "PATCH", "-f", "state=closed")
        summary(f"- #{number}: {'closed' if close else 'would close'} (release is older than {current})")
    if not obsolete:
        summary("- No demonstrably obsolete release follow-up PRs.")


def diagnose(repo: str, run_id: str) -> None:
    run = gh(f"repos/{repo}/actions/runs/{run_id}")
    if run.get("head_repository", {}).get("full_name") != repo:
        summary("Skipping workflow run from a different repository.")
        return
    summary(f"### CI diagnosis: {run.get('name', 'workflow')} — {run.get('html_url', '')}")
    summary(f"- Workflow outcome: {run.get('conclusion', 'unknown')}")
    page = 1
    failed = 0
    while True:
        result = gh(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100&page={page}")
        jobs = result.get("jobs", [])
        for job in jobs:
            if job.get("conclusion") not in {"failure", "timed_out", "cancelled"}:
                continue
            failed += 1
            steps = [s.get("name", "unknown") for s in job.get("steps", [])
                     if s.get("conclusion") == "failure"]
            summary(f"- {job['name']}: {job.get('conclusion')} — {', '.join(steps) or 'no failed step identified'} — {job.get('html_url', '')}")
        if len(jobs) < 100:
            break
        page += 1
    if not failed:
        summary("- No failed jobs were returned by the Actions API.")
    summary("- No jobs were automatically rerun; examine logs before classifying a failure as transient.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--close", action="store_true", help="Close proven-obsolete release-only PRs")
    parser.add_argument("--run-id", help="Report failed jobs for an Actions run")
    args = parser.parse_args()
    if not args.repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("provide --repo OWNER/REPO")
    if args.run_id:
        diagnose(args.repo, args.run_id)
    else:
        cleanup(args.repo, args.close)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError) as exc:
        print(f"release guardian: {exc}", file=sys.stderr)
        sys.exit(1)
