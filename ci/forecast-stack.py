#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""CI-only smoke: real Kafka collection, synthetic past buckets, real TimesFM and MCP.

Run exclusively against the disposable `forecast-ci` Compose project after bootstrap.
The generated history is a test fixture, never a claim of realised model accuracy.
"""
import hashlib
import json
import math
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '-p', 'forecast-ci', '--env-file', '.forecast-stack/.env',
           '-f', 'docker-compose.yml', '-f', 'compose/forecasts.yml', '-f', 'compose/image.yml', '-f', 'compose/ci.yml']
BASE = 'http://127.0.0.1:8080'
TOOLS = {'kex_list_forecastable_metrics', 'kex_metric_history', 'kex_forecast_metric',
         'kex_list_predicted_threshold_breaches', 'kex_get_forecast_quality'}


def compose(*args, stdin=None):
    return subprocess.run(COMPOSE + list(args), cwd=ROOT, input=stdin, text=True,
                          stdout=subprocess.PIPE, check=True, timeout=120).stdout


def api(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wait_for(read, accept, label, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = read()
            if accept(value):
                print(label + ': OK', flush=True)
                return value
        except (urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise AssertionError(label + ' did not complete within its deadline')


def fixture_sql(observation, cutoff):
    """Preserve real collection provenance; fill 512 completed minute buckets explicitly."""
    assert re.fullmatch('[a-f0-9]{64}', observation['seriesId'])
    assert observation['qualityState'] == 'OBSERVED' and observation['component'] == 'value'
    rows = []
    for index in range(512):
        point = dict(observation)
        point['observedAt'] = cutoff - (512 - index) * 60000 + 30000
        point['collectorRunId'] = 'ci-synthetic-history-' + str(index)
        point['observationId'] = hashlib.sha256(json.dumps([point['seriesId'], point['collectorRunId']], separators=(',', ':')).encode()).hexdigest()
        point['value'] = 1000.0 + 100.0 * math.sin(index / 12.0)
        payload = json.dumps(point, separators=(',', ':')).replace("'", "''")
        rows.append(f"('{point['observationId']}','{point['seriesId']}',{point['observedAt']},{point['value']},'{payload}')")
    return ('INSERT INTO kex_metric_observation_v1 (observation_id,series_id,observed_at,metric_value,payload) VALUES\n'
            + ',\n'.join(rows) + '\nON CONFLICT (observation_id) DO NOTHING;\n')


def rpc_json(raw):
    text = raw.decode()
    if text.startswith('event:') or text.startswith('data:'):
        events = [json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith('data:')]
        assert events, 'MCP response contained no data'
        return events[-1]
    return json.loads(text)


def verify_mcp(series_id, record):
    # Read the private bootstrap token without echoing it or putting it in process arguments.
    env = dict(line.split('=', 1) for line in (ROOT / '.forecast-stack/.env').read_text().splitlines()
               if '=' in line and not line.startswith('#'))
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
               'Authorization': 'Bearer ' + env['EXPLORER_MCP_AUTH_TOKEN']}
    def call(method, params=None, identifier=1):
        payload = {'jsonrpc': '2.0', 'method': method}
        if identifier is not None:
            payload['id'] = identifier
        if params is not None:
            payload['params'] = params
        with urllib.request.urlopen(urllib.request.Request(BASE + '/mcp', json.dumps(payload).encode(), headers), timeout=45) as response:
            if response.headers.get('Mcp-Session-Id'):
                headers['Mcp-Session-Id'] = response.headers['Mcp-Session-Id']
            raw = response.read()
        result = rpc_json(raw) if raw else {}
        assert 'error' not in result, 'MCP RPC failed'
        return result.get('result', {})
    initialized = call('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                                     'clientInfo': {'name': 'forecast-ci', 'version': '1'}})
    headers['MCP-Protocol-Version'] = initialized['protocolVersion']
    call('notifications/initialized', identifier=None)
    tools = call('tools/list', identifier=2)
    announced = {tool['name'] for tool in tools['tools']}
    cursors = set()
    while tools.get('nextCursor'):
        cursor = tools['nextCursor']
        assert cursor not in cursors and len(cursors) < 10, 'MCP catalogue pagination is unbounded'
        cursors.add(cursor)
        tools = call('tools/list', {'cursor': cursor}, 2)
        announced.update(tool['name'] for tool in tools['tools'])
    assert TOOLS <= announced, 'Forecast tools absent'
    result = call('tools/call', {'name': 'kex_forecast_metric', 'arguments': {'seriesId': series_id}}, 3)
    assert not result.get('isError'), 'Forecast MCP read refused'
    envelope = result.get('structuredContent')
    if envelope is None:
        envelope = json.loads(next(item['text'] for item in result['content'] if item['type'] == 'text'))
    measured = envelope['data']
    assert measured['measured'] and measured['value']['key'] == record['key']
    print('Five MCP tools and persisted forecast read: OK', flush=True)


def main():
    wait_for(lambda: api('/actuator/health/liveness'), lambda value: value['status'] == 'UP', 'Explorer startup')
    kafka = ['exec', '-T', 'kafka', '/opt/kafka/bin/']
    compose(*kafka[:-1], kafka[-1] + 'kafka-topics.sh', '--bootstrap-server', 'localhost:9092',
            '--create', '--if-not-exists', '--topic', 'forecast.ci.orders', '--partitions', '1', '--replication-factor', '1')
    compose(*kafka[:-1], kafka[-1] + 'kafka-console-producer.sh', '--bootstrap-server', 'localhost:9092',
            '--topic', 'forecast.ci.orders', stdin='{"order":1}\n{"order":2}\n{"order":3}\n')
    compose(*kafka[:-1], kafka[-1] + 'kafka-console-consumer.sh', '--bootstrap-server', 'localhost:9092',
            '--topic', 'forecast.ci.orders', '--group', 'forecast-ci', '--from-beginning', '--max-messages', '1',
            '--consumer-property', 'enable.auto.commit=true', '--consumer-property', 'auto.commit.interval.ms=100')
    api('/api/metrics', {'id': 'forecast-ci-lag', 'name': 'CI consumer time lag', 'type': 'GAUGE',
                        'templateType': 'CONSUMER_TIME_LAG', 'executionMode': 'TEMPLATE_BOUNDED_SCAN',
                        'templateParams': {'topic': 'forecast.ci.orders', 'group': 'forecast-ci', 'aggregation': 'MAX'}})
    api('/api/metrics/forecast-ci-lag/refresh', {})
    candidate = next(metric for metric in api('/api/forecasts/candidates')['metrics'] if metric['metricId'] == 'forecast-ci-lag')
    assert candidate['eligible'] and candidate['unit'] == 'milliseconds', 'Real metric is not eligible'
    draft = api('/api/forecasts/configuration', {'metricId': candidate['metricId'], 'definitionVersion': candidate['definitionVersion'],
                'environment': 'local', 'unit': candidate['unit'], 'clusterId': 'local', 'collectorId': 'forecast-ci',
                'topics': candidate['topics'], 'groups': candidate['groups'], 'stepMillis': 60000, 'horizon': 10,
                'threshold': None, 'direction': 'ABOVE', 'confirmed': True})
    configuration = draft['configuration']
    assert configuration.count('interval: "PT5M"') == 1
    (ROOT / '.forecast-stack/config/forecasts.yml').write_text(configuration.replace('interval: "PT5M"', 'interval: "PT1M"'))
    compose('restart', 'explorer')
    wait_for(lambda: api('/actuator/health/liveness'), lambda value: value['status'] == 'UP', 'Restart with reviewed series')
    wait_for(lambda: api('/api/metrics'), lambda rows: any(row['id'] == 'forecast-ci-lag' for row in rows), 'Persisted metric restored')
    api('/api/metrics/forecast-ci-lag/refresh', {})
    series_id = draft['seriesId']
    assert re.fullmatch('[a-f0-9]{64}', series_id)
    query = f"SELECT payload FROM kex_metric_observation_v1 WHERE series_id='{series_id}' ORDER BY observed_at DESC LIMIT 1;"
    def observation():
        output = compose('exec', '-T', 'forecast-postgres', 'psql', '-U', 'forecasts', '-d', 'forecasts', '-At', '-c', query).strip()
        return json.loads(output) if output else None
    real = wait_for(observation, lambda value: value is not None and value['qualityState'] == 'OBSERVED', 'Real collection persisted')
    assert real['unit'] == 'milliseconds' and real['metricId'] == candidate['metricId']
    sql = fixture_sql(real, int(time.time() * 1000) // 60000 * 60000)
    compose('exec', '-T', 'forecast-postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'forecasts', '-d', 'forecasts', stdin=sql)
    print('Inserted 512 synthetic past buckets for CI inference only', flush=True)
    readiness = api('/api/forecasts/preparation/probe', {})
    assert {check['id']: check['state'] for check in readiness['checks'] if check['id'] in ('postgres', 'timesfm')} == {'postgres': 'READY', 'timesfm': 'READY'}
    def forecast():
        return next((row['result'] for row in api('/api/forecasts')['series'] if row['seriesId'] == series_id), None)
    record = wait_for(forecast, lambda value: value is not None and value['state'] == 'READY' and value['strategy'] == 'TIMESFM', 'Real TimesFM result', 240)
    assert record['visibility'] == 'SHADOW' and len(record['forecast']['points']) == 10
    assert record['context']['status'] == 'READY' and len(record['context']['points']) == 512
    assert record['forecast']['seriesId'] == series_id
    verify_mcp(series_id, record)
    print('Forecast stack smoke passed; forecast quality and activation are outside this test', flush=True)


if __name__ == '__main__':
    main()
