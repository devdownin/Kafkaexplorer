# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Behaviour of the demo launchers' menu (demo-menu.sh, and demo-menu.ps1 where pwsh exists).

`docs/check-demo-defaults.py` holds the launchers' *defaults* to the compose files. Nothing held the
*menu* to anything: two scripts of ~500 lines each, kept in step by hand, whose only output is the
command line they print. These tests run it for real, against a fake `demo.sh`, a fake `docker` and a
local stand-in for openrouter.ai, so no stack starts and nothing leaves the machine.

Three things are pinned:
  * every preset prints the same `demo.sh` switches, and `demo-menu.ps1` prints the same ones (when
    pwsh is installed: GitHub's Ubuntu runners have it, a laptop may not);
  * the OpenRouter model: where it comes from, what the checks say, and that only an interactive
    choice is remembered;
  * the checks themselves: a model with no tool calling, one the catalogue does not know, one that
    needs credits, and a catalogue that cannot be reached (which is "not checked", never "no").
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
PWSH = shutil.which('pwsh')

PRESETS = {
    'full': '--prebuilt',
    'full-no-spectra': '--no-spectra --prebuilt',
    'forecast': '--forecast --prebuilt',
    'forecast-no-agent': '--forecast --prebuilt --no-agent',
    'forecast-offline': '--forecast --offline',
    'full-build': '',
    'full-no-spectra-build': '--no-spectra',
    'forecast-build': '--forecast',
}

CATALOGUE = {'data': [
    {'id': 'openai/gpt-4o-mini', 'supported_parameters': ['tools', 'temperature']},
    {'id': 'vendor/plain', 'supported_parameters': ['temperature']},
    {'id': 'vendor/broke', 'supported_parameters': ['tools']},
]}


class FakeOpenRouter(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/api/v1/models':
            self._send(200, CATALOGUE)
        elif self.path == '/api/v1/auth/key':
            good = self.headers.get('Authorization') == 'Bearer sk-or-good'
            self._send(200 if good else 401, {})
        else:
            self._send(404, {})

    def do_POST(self):
        asked = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if asked.get('model') == 'vendor/broke':
            # An escape sequence in the provider's words must not reach the terminal.
            self._send(402, {'error': {'message': 'Insufficient credits \x1b[31mred'}})
        else:
            self._send(200, {'choices': []})


class MenuCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), FakeOpenRouter)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f'http://127.0.0.1:{cls.server.server_address[1]}/api/v1'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._dir.cleanup)
        self.work = Path(self._dir.name)
        for name in ('demo-menu.sh', 'demo-menu.ps1'):
            shutil.copy(ROOT / name, self.work / name)
        # What the menu hands over to: records its switches and the model it would have been given.
        fake = self.work / 'demo.sh'
        fake.write_text('#!/usr/bin/env bash\n'
                        'printf "ARGS=%s\\nMODEL=%s\\n" "$*" "${OPENROUTER_MODEL-unset}" > handoff.txt\n')
        fake.chmod(0o755)
        (self.work / 'bin').mkdir()
        docker = self.work / 'bin' / 'docker'
        docker.write_text('#!/bin/sh\nexit 0\n')  # no daemon: no demo is running
        docker.chmod(0o755)

    def env(self, **extra):
        env = {'PATH': f'{self.work / "bin"}{os.pathsep}{os.environ["PATH"]}', 'HOME': str(self.work),
               'TERM': 'dumb', 'NO_COLOR': '1',
               # Nothing listens on the discard port: an unreachable catalogue, quickly.
               'DEMO_MENU_OPENROUTER_URL': 'http://127.0.0.1:9/api/v1'}
        env.update(extra)
        return env

    def menu(self, *args, stdin='', **env):
        done = subprocess.run(['bash', 'demo-menu.sh', *args], cwd=self.work, env=self.env(**env),
                              input=stdin, capture_output=True, text=True, timeout=60)
        return done.returncode, done.stdout + done.stderr

    def menu_on_a_terminal(self, args, answers, **env):
        """The checks that cost quota are offered to a person only: this gives the menu a real tty."""
        import pty
        master, slave = pty.openpty()
        process = subprocess.Popen(['bash', 'demo-menu.sh', *args], cwd=self.work, env=self.env(**env),
                                   stdin=slave, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        os.close(slave)
        os.write(master, ''.join(f'{a}\n' for a in answers).encode())
        try:
            out, _ = process.communicate(timeout=60)
        finally:
            os.close(master)
        return process.returncode, out

    def handoff(self):
        path = self.work / 'handoff.txt'
        return dict(line.split('=', 1) for line in path.read_text().splitlines()) if path.exists() else None

    def last_demo(self):
        path = self.work / '.forecast-stack' / 'last-demo'
        return path.read_text() if path.exists() else ''


class Presets(MenuCase):
    def test_every_preset_prints_its_switches(self):
        for name, switches in PRESETS.items():
            with self.subTest(preset=name):
                code, out = self.menu('--dry-run', '--preset', name)
                self.assertEqual(code, 0, out)
                self.assertIn(f'Command: ./demo.sh {switches}'.rstrip() + '\n', out)

    def test_the_two_menus_list_the_same_presets(self):
        _, listed = self.menu('--presets')
        names = [line.split()[0] for line in listed.splitlines() if line.strip()]
        self.assertEqual(names, list(PRESETS))
        ps1 = (ROOT / 'demo-menu.ps1').read_text()
        block = ps1[ps1.index('function Show-Presets'):ps1.index('if ($Presets)')]
        self.assertEqual(re.findall(r"^\s*'([a-z-]+)\s{2,}", block, re.M), names)

    @unittest.skipUnless(PWSH, 'pwsh is not installed')
    def test_powershell_prints_the_same_switches(self):
        for name, switches in PRESETS.items():
            with self.subTest(preset=name):
                done = subprocess.run([PWSH, '-NoProfile', '-File', str(self.work / 'demo-menu.ps1'),
                                       '-DryRun', '-Preset', name], cwd=self.work, env=self.env(),
                                      capture_output=True, text=True, timeout=120)
                self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
                wanted = ' '.join('-' + ''.join(p.capitalize() for p in s[2:].split('-')) for s in switches.split())
                self.assertIn(f'Command: .\\demo.ps1 {wanted}'.rstrip(), done.stdout.replace('\r', ''))


class Model(MenuCase):
    def recap(self, out):
        return re.search(r'OpenRouter model\s+(.*)', out).group(1)

    def test_a_preset_takes_the_default_and_hands_over_no_model(self):
        code, out = self.menu('--preset', 'forecast', '--yes')
        self.assertEqual(code, 0, out)
        self.assertEqual(self.recap(out), 'openrouter/free (default)')
        self.assertEqual(self.handoff()['MODEL'], 'unset')  # demo.sh's own default applies

    def test_model_flag_wins_over_the_environment_and_is_handed_over(self):
        code, out = self.menu('--preset', 'forecast', '--yes', '--model', 'openai/gpt-4o-mini',
                              OPENROUTER_MODEL='x/from-env')
        self.assertEqual(code, 0, out)
        self.assertEqual(self.recap(out), 'openai/gpt-4o-mini (--model, this run only)')
        self.assertEqual(self.handoff()['MODEL'], 'openai/gpt-4o-mini')

    def test_the_environment_then_dotenv_then_the_default(self):
        (self.work / '.env').write_text('OPENROUTER_MODEL=x/from-dotenv\n')
        _, out = self.menu('--preset', 'forecast', '--dry-run', OPENROUTER_MODEL='x/from-env')
        self.assertEqual(self.recap(out), 'x/from-env (from the environment)')
        _, out = self.menu('--preset', 'forecast', '--dry-run')
        self.assertEqual(self.recap(out), 'x/from-dotenv (from .env)')
        (self.work / '.env').unlink()
        _, out = self.menu('--preset', 'forecast', '--dry-run')
        self.assertEqual(self.recap(out), 'openrouter/free (default)')

    def test_a_malformed_model_is_refused_before_anything_runs(self):
        for bad in ('bad;id', 'a b', '', '-lead', 'x' * 101):
            with self.subTest(model=bad):
                code, out = self.menu('--dry-run', '--preset', 'forecast', '--model', bad)
                self.assertEqual(code, 2, out)
                self.assertIn('--model expects an OpenRouter model id', out)

    def test_a_demo_without_a_model_says_the_flag_is_ignored(self):
        _, out = self.menu('--dry-run', '--preset', 'forecast-no-agent', '--model', 'x/y')
        self.assertIn('This demo calls no model: --model x/y is ignored.', out)
        self.assertNotIn('OpenRouter model', out)

    def test_an_interactive_choice_is_remembered_and_comes_first_next_time(self):
        # start, forecast, published images, with the agent, "another model", its id
        code, out = self.menu('--yes', stdin='1\n3\n1\n1\n3\nvendor/plain\n')
        self.assertEqual(code, 0, out)
        self.assertEqual(self.recap(out), 'vendor/plain (your choice)')
        self.assertEqual(self.handoff()['MODEL'], 'vendor/plain')
        self.assertIn('model=vendor/plain', self.last_demo())
        # start, same demo as last time, the first model offered
        code, out = self.menu('--yes', stdin='1\n1\n1\n')
        self.assertEqual(code, 0, out)
        self.assertIn('vendor/plain, same as last time', out)
        self.assertEqual(self.recap(out), 'vendor/plain (your choice)')
        # A script's preset gets the default, and leaves the remembered choice alone.
        code, out = self.menu('--preset', 'forecast', '--yes')
        self.assertEqual(self.recap(out), 'openrouter/free (default)')
        self.assertEqual(self.handoff()['MODEL'], 'unset')
        self.assertIn('model=vendor/plain', self.last_demo())

    def test_a_one_off_flag_does_not_replace_the_remembered_choice(self):
        self.menu('--yes', stdin='1\n3\n1\n1\n3\nvendor/plain\n')
        self.menu('--preset', 'forecast', '--yes', '--model', 'openai/gpt-4o-mini')
        self.assertIn('model=vendor/plain', self.last_demo())

    def test_a_corrupt_remembered_model_is_ignored(self):
        state = self.work / '.forecast-stack'
        state.mkdir()
        (state / 'last-demo').write_text('mode=forecast\nsource=prebuilt\nagent=yes\nmodel=$(touch pwned)\n')
        _, out = self.menu('--yes', stdin='1\n1\n1\n')
        self.assertNotIn('same as last time, same as last time', out)
        self.assertNotIn('$(touch', out.split('Which OpenRouter model?')[-1])
        self.assertFalse((self.work / 'pwned').exists())

    @unittest.skipUnless(PWSH, 'pwsh is not installed')
    def test_powershell_resolves_the_model_the_same_way(self):
        def recap(*args, **env):
            done = subprocess.run([PWSH, '-NoProfile', '-File', str(self.work / 'demo-menu.ps1'), '-DryRun',
                                   '-Preset', 'forecast', *args], cwd=self.work, env=self.env(**env),
                                  capture_output=True, text=True, timeout=120)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            return re.search(r'OpenRouter model\s+(.*)', done.stdout).group(1).strip()
        self.assertEqual(recap(), 'openrouter/free (default)')
        self.assertEqual(recap('-Model', 'x/y', OPENROUTER_MODEL='x/env'), 'x/y (-Model, this run only)')
        self.assertEqual(recap(OPENROUTER_MODEL='x/env'), 'x/env (from the environment)')


class Checks(MenuCase):
    def verdict(self, model, **env):
        _, out = self.menu('--dry-run', '--preset', 'forecast', '--model', model,
                           DEMO_MENU_OPENROUTER_URL=self.url, OPENROUTER_API_KEY='sk-or-good', **env)
        return out

    def test_a_model_that_declares_tool_calling_is_checked(self):
        self.assertIn('Model checked: openai/gpt-4o-mini declares tool calling.', self.verdict('openai/gpt-4o-mini'))

    def test_a_model_without_tool_calling_warns(self):
        self.assertIn('vendor/plain does not declare tool calling', self.verdict('vendor/plain'))

    def test_an_unknown_model_points_at_the_spelling(self):
        self.assertIn('nope/none is not in the OpenRouter catalogue', self.verdict('nope/none'))

    def test_a_router_is_not_claimed_either_way(self):
        out = self.verdict('openrouter/free')
        self.assertIn('is a router', out)
        self.assertNotIn('catalogue: check', out)

    def test_an_unreachable_catalogue_is_not_checked_rather_than_refused(self):
        _, out = self.menu('--dry-run', '--preset', 'forecast', '--model', 'vendor/plain')
        self.assertIn('Model not checked against the catalogue', out)
        self.assertNotIn('does not declare', out)

    def test_a_dry_run_never_spends_quota_on_the_probe(self):
        _, out = self.menu_on_a_terminal(['--dry-run'], ['1', '3', '1', '1', '3', 'vendor/broke'],
                                         DEMO_MENU_OPENROUTER_URL=self.url, OPENROUTER_API_KEY='sk-or-good')
        self.assertNotIn('tiny request', out)

    def test_the_probe_reports_credits_and_strips_the_providers_escape_codes(self):
        # start, forecast, published images, agent, another model, its id, probe: yes, what now: cancel
        code, out = self.menu_on_a_terminal([], ['1', '3', '1', '1', '3', 'vendor/broke', 'y', '3'],
                                            DEMO_MENU_OPENROUTER_URL=self.url, OPENROUTER_API_KEY='sk-or-good')
        self.assertEqual(code, 0, out)
        self.assertIn('vendor/broke needs credits this account does not have.', out)
        self.assertIn('OpenRouter says: Insufficient credits [31mred', out)
        self.assertNotIn('\x1b[31m', out)
        self.assertIn('Cancelled.', out)
        self.assertIsNone(self.handoff())

    def test_choosing_another_model_after_a_refusal_asks_again(self):
        # ... probe: yes, what now: choose another, the paid model, probe: no, run it: no
        code, out = self.menu_on_a_terminal([], ['1', '3', '1', '1', '3', 'vendor/broke', 'y', '1', '2', 'n', 'n'],
                                            DEMO_MENU_OPENROUTER_URL=self.url, OPENROUTER_API_KEY='sk-or-good')
        self.assertEqual(code, 0, out)
        self.assertEqual(out.count('Which OpenRouter model?'), 2)
        self.assertIn('Model checked: openai/gpt-4o-mini declares tool calling.', out)
        self.assertIsNone(self.handoff())  # "Run it?" was answered no


if __name__ == '__main__':
    unittest.main()
