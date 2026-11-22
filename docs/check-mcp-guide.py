#!/usr/bin/env python3
"""Guard the public MCP guide's HTML-to-JavaScript integration.

The separate graph controller must actually be loaded and all of its controls
must exist. Syntax validation of mcp-guide.js alone cannot detect a detached
script or missing DOM elements.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
html = (ROOT / "mcp-guide.html").read_text(encoding="utf-8")
js = (ROOT / "mcp-guide.js").read_text(encoding="utf-8")

script_tags = re.findall(r"<script\b[^>]*>", html, re.IGNORECASE)
external = [tag for tag in script_tags if re.search(r'\bsrc=["\']mcp-guide\.js["\']', tag)]
assert len(external) == 1, "mcp-guide.html must load mcp-guide.js exactly once"
assert len(script_tags) == 1, "mcp-guide.html still has an obsolete inline graph controller"

html_ids = re.findall(r'\bid="([^"]+)"', html)
assert len(html_ids) == len(set(html_ids)), "Duplicate HTML ids in MCP guide"
required_ids = set(re.findall(r'getElementById\(["\']([^"\']+)["\']\)', js))
missing = required_ids.difference(html_ids)
assert not missing, f"Missing DOM targets required by mcp-guide.js: {sorted(missing)}"

node_ids = re.findall(r'data-node="([^"]+)"', html)
expected_nodes = ["request", "entry", "agent", "policy", "client",
                  "server", "backend", "evidence", "answer"]
assert node_ids == expected_nodes, f"MCP graph nodes differ from the reference path: {node_ids}"

lens_ids = set(re.findall(r'data-lens="([^"]+)"', html))
assert lens_ids == {"all", "human", "agent", "guard", "transport", "service", "evidence"}, (
    f"Unexpected responsibility lenses: {sorted(lens_ids)}"
)
scenario_ids = set(re.findall(r'data-scenario="([^"]+)"', html))
assert scenario_ids == {"incident", "forecast", "skill"}, (
    f"Unexpected scenario buttons: {sorted(scenario_ids)}"
)

print("Public MCP guide: external script and required controls are consistent")
