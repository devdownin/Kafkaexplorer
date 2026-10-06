# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Contract checks for the CI fixture and MCP response parser; no Docker simulation."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('forecast_stack', Path(__file__).with_name('forecast-stack.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeContract(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
