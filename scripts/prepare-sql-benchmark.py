#!/usr/bin/env python3
"""Provision an isolated Kafka benchmark fixture in the repository's Compose stack.

Creates one populated topic and enough empty topics to reach the requested catalogue size.
Never deletes topics and refuses a prefix already present in Kafka. Requires Docker Compose
with the repository's `kafka` service running and the Explorer HTTP endpoint available.
"""

import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path


BROKER = "kafka:29092"
BIN = "/opt/kafka/bin/"


def kafka(command, *args):
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "kafka", BIN + command,
         "--bootstrap-server", BROKER, *args],
        check=True, text=True, capture_output=True,
    ).stdout.strip()


def offsets(topic, at):
    lines = kafka("kafka-get-offsets.sh", "--topic", topic, "--time", str(at)).splitlines()
    found = {}
    for line in lines:
        parts = line.strip().split(":")
        if len(parts) != 3 or parts[0] != topic:
            raise ValueError(f"Unexpected offset reply: {line!r}")
        found[int(parts[1])] = int(parts[2])
    if found.keys() != {0}:
        raise ValueError(f"Expected one partition for {topic}, got {found}")
    return found[0]


def post_ddl(base, ddl):
    token = os.environ.get("KEX_BENCH_TOKEN")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(base.rstrip("/") + "/api/query/run-sync",
        json.dumps({"sql": ddl, "maxRows": 1, "timeout": 30000}).encode(),
        headers, method="POST")
    with urllib.request.urlopen(request, timeout=45) as response:
        result = json.load(response)
    if result.get("error"):
        raise RuntimeError(f"Flink DDL rejected: {result['error']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", required=True, help="Unique name, letters/digits/underscore only")
    parser.add_argument("--records", required=True, type=int, choices=(10000, 100000))
    parser.add_argument("--topics", required=True, type=int, choices=(100, 1000))
    parser.add_argument("--url", required=True, help="Explorer base URL")
    parser.add_argument("--app-bootstrap", default="kafka:29092",
                        help="Bootstrap address reachable from the Explorer application")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{2,40}", args.prefix):
        parser.error("prefix must start with a letter and contain 3–41 ASCII letters/digits/underscores")
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        parser.error("output directory must be absent or empty")

    topic = args.prefix + "_data"
    names = [topic] + [f"{args.prefix}_{i:04d}" for i in range(1, args.topics)]
    existing = set(kafka("kafka-topics.sh", "--list").splitlines())
    if any(name.startswith(args.prefix + "_") for name in existing):
        parser.error("prefix already has topics in this cluster; choose another (nothing was deleted)")
    print(f"Creating {args.topics} topics; no existing topics will be modified", flush=True)

    def create(name):
        kafka("kafka-topics.sh", "--create", "--topic", name,
              "--partitions", "1", "--replication-factor", "1")

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(create, names))
    beginning = offsets(topic, -2)
    if beginning != 0:
        raise RuntimeError(f"New topic has nonzero beginning offset: {beginning}")

    producer = subprocess.Popen(
        ["docker", "compose", "exec", "-T", "kafka", BIN + "kafka-console-producer.sh",
         "--bootstrap-server", BROKER, "--topic", topic],
        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        text=True,
    )
    try:
        for index in range(args.records):
            producer.stdin.write(json.dumps({"id": index, "marker": "BENCH"}) + "\n")
        producer.stdin.close()
        if producer.wait(timeout=180) != 0:
            raise RuntimeError("Kafka producer failed; inspect broker and Docker Compose logs")
    except BaseException:
        producer.kill()
        producer.wait()
        raise

    end = offsets(topic, -1)
    actual = set(kafka("kafka-topics.sh", "--list").splitlines())
    if end - beginning != args.records or set(names) - actual:
        raise RuntimeError(f"Fixture mismatch: offsets [{beginning}, {end}), "
                           f"{len(set(names) & actual)}/{args.topics} topics present")

    table = topic
    ddl = (f"CREATE TABLE {table} (id BIGINT, marker STRING) WITH ("
           f"'connector' = 'kafka', 'topic' = '{topic}', "
           f"'properties.bootstrap.servers' = '{args.app_bootstrap}', "
           "'value.format' = 'json', 'scan.startup.mode' = 'specific-offsets', "
           f"'scan.startup.specific-offsets' = 'partition:0,offset:{beginning}', "
           "'scan.bounded.mode' = 'specific-offsets', "
           f"'scan.bounded.specific-offsets' = 'partition:0,offset:{end}')")
    post_ddl(args.url, ddl)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    sql = f"SELECT id FROM {table} WHERE marker = 'NEVER'\n"
    (args.output_dir / "flink.sql").write_text(sql)
    (args.output_dir / "direct.sql").write_text(sql)
    (args.output_dir / "explain.sql").write_text("EXPLAIN " + sql)
    (args.output_dir / "fixture.json").write_text(json.dumps({
        "topic": topic, "table": table, "topics": args.topics,
        "records": args.records, "startOffset": beginning, "endOffsetExclusive": end,
        "sql": sql.strip(), "note": "New isolated prefix; exact Kafka offsets verified after production."
    }, indent=2) + "\n")
    print(f"Verified {args.records} records and {args.topics} topics: {args.output_dir}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, ValueError, RuntimeError) as error:
        sys.exit(f"Benchmark setup failed: {error}")
