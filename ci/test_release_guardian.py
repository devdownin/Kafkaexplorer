"""Guard against accidentally closing PRs that are not superseded release-only follow-ups."""
from pathlib import Path
import runpy
import unittest

guardian = runpy.run_path(str(Path(__file__).with_name("release-guardian.py")))
candidate = guardian["candidate"]

REPO = "devdownin/Kafkaexplorer"
FILES = {".env.example", "CHANGELOG.md", "compose/image.yml", "compose/spectra-hub.yml"}


def pr(version="2.7.1", *, branch=None, title=None, owner=REPO, base="main"):
    return {
        "head": {"ref": branch or f"chore/release-followup-{version}",
                 "repo": {"full_name": owner}},
        "base": {"ref": base},
        "title": title or f"Catch the repository up to {version}",
    }


class ReleaseGuardianTest(unittest.TestCase):
    def test_only_older_release_only_pull_requests_are_closed(self):
        self.assertTrue(candidate(pr(), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr("2.8.2"), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr("2.9.0"), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr(), "2.8.2", FILES | {"src/main/App.java"}, REPO))

    def test_title_source_and_base_are_restricted(self):
        self.assertFalse(candidate(pr(title="Feature: also bump image"), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr(owner="someone/another-fork"), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr(base="feature"), "2.8.2", FILES, REPO))
        self.assertFalse(candidate(pr(branch="my-branch"), "2.8.2", FILES, REPO))

    def test_required_files_cannot_be_omitted(self):
        self.assertFalse(candidate(pr(), "2.8.2", {".env.example"}, REPO))
        self.assertFalse(candidate(pr(), "2.8.2", {"CHANGELOG.md"}, REPO))


if __name__ == "__main__":
    unittest.main()
