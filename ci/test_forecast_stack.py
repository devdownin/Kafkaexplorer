# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Contract checks for the CI fixture and MCP response parser; no Docker simulation."""
import importlib.util
import json
from io import BytesIO
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('forecast_stack', Path(__file__).with_name('forecast-stack.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeContract(unittest.TestCase):
    def test_health_wait_survives_connection_reset_and_timeout_during_restart(self):
        for failure in [ConnectionResetError('restart closed the socket'), TimeoutError('startup timeout')]:
            with self.subTest(failure=type(failure).__name__):
                with patch.object(smoke.urllib.request, 'urlopen', side_effect=[failure, BytesIO(b'{"status":"UP"}')]) as request:
                    with patch.object(smoke.time, 'sleep'), patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 1]):
                        # Follow the reset with a real JSON response through api(), not a fake readiness value.
                        self.assertEqual(smoke.wait_for(lambda: smoke.api('/actuator/health/liveness'),
                                                       lambda value: value['status'] == 'UP', 'Restart'), {'status': 'UP'})
                        self.assertEqual(request.call_count, 2)

    def test_repeated_startup_resets_still_exhaust_the_deadline(self):
        with patch.object(smoke.urllib.request, 'urlopen', side_effect=ConnectionResetError('restart')):
            with patch.object(smoke.time, 'sleep'), patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 2]):
                with self.assertRaisesRegex(AssertionError, 'Restart did not complete within its deadline'):
                    smoke.wait_for(lambda: smoke.api('/actuator/health/liveness'), lambda value: True, 'Restart', seconds=1)

    def test_health_contract_errors_fail_immediately(self):
        with patch.object(smoke.time, 'sleep') as sleep:
            with self.assertRaises(KeyError):
                smoke.wait_for(lambda: {}, lambda value: value['status'] == 'UP', 'Restart')
            sleep.assert_not_called()

    def test_waits_for_asynchronous_history_schema_before_querying_rows(self):
        with patch.object(smoke, 'compose', return_value='f\n') as command:
            self.assertIsNone(smoke.read_observation('a' * 64))
            self.assertEqual(command.call_count, 1)
            self.assertIn('to_regclass', command.call_args.args[-1])
        with patch.object(smoke, 'compose', side_effect=['t\n', '{"qualityState":"OBSERVED"}\n']):
            self.assertEqual(smoke.read_observation('a' * 64), {'qualityState': 'OBSERVED'})
        with patch.object(smoke, 'compose', side_effect=['t\n', '']):
            self.assertIsNone(smoke.read_observation('a' * 64))

    def test_schema_errors_remain_failures_instead_of_being_treated_as_empty_history(self):
        with patch.object(smoke, 'compose', return_value='invalid'):
            with self.assertRaises(AssertionError):
                smoke.read_observation('a' * 64)

    def test_fixture_uses_real_identity_and_512_completed_buckets(self):
        real = {'seriesId': 'a' * 64, 'component': 'value', 'qualityState': 'OBSERVED',
                'metricId': "metric'quote", 'definitionVersion': 'canonical', 'unit': 'milliseconds',
                'observedAt': 999999, 'value': 7}
        sql = smoke.fixture_sql(real, 60000 * 1000)
        self.assertEqual(sql.count('ci-synthetic-history-'), 512)
        self.assertIn('canonical', sql)
        self.assertIn("metric''quote", sql)
        self.assertIn('ON CONFLICT (observation_id) DO NOTHING', sql)
        self.assertIn(str(60000 * 488 + 30000), sql)
        self.assertIn(str(60000 * 999 + 30000), sql)
        self.assertEqual(real['value'], 7)

    def test_fixture_rejects_unmeasured_observations(self):
        with self.assertRaises(AssertionError):
            smoke.fixture_sql({'seriesId': 'a' * 64, 'qualityState': 'UNMEASURED', 'component': 'value'}, 60000)

    def test_json_and_sse_rpc_responses(self):
        response = {'jsonrpc': '2.0', 'id': 3, 'result': {'content': []}}
        raw = json.dumps(response).encode()
        self.assertEqual(smoke.rpc_json(raw), response)
        self.assertEqual(smoke.rpc_json(b'event: message\ndata: ' + raw + b'\n\n'), response)
        with self.assertRaises(AssertionError):
            smoke.rpc_json(b'event: message\n\n')

    def test_mcp_sse_accepts_event_id_and_keepalive_before_payload(self):
        response = {'jsonrpc': '2.0', 'id': 2, 'result': {'tools': []}}
        raw = json.dumps(response).encode()
        self.assertEqual(smoke.rpc_json(b'id: session-event\nevent: message\ndata:' + raw + b'\n\n'), response)
        self.assertEqual(smoke.rpc_json(b': keepalive\r\nid: session-event\r\ndata: ' + raw + b'\r\n\r\n'), response)
        with self.assertRaisesRegex(AssertionError, 'no data'):
            smoke.rpc_json(b'id: empty-event\n\n')


if __name__ == '__main__':
    unittest.main()
