#!/usr/bin/env bash
# Compile and test the backend when packages.confluent.io is unreachable.
#
# The two io.confluent artifacts (kafka-avro-serializer, kafka-schema-registry-client)
# are published only on packages.confluent.io — they are NOT on Maven Central. Behind a
# proxy that blocks that host, Maven cannot even *collect* the dependency graph, so it
# downloads nothing and `mvn test` fails before compiling a single file.
#
# This script works around that for local verification only:
#   1. resolves dependencies from a temporary pom with the Confluent artifacts removed,
#   2. generates minimal stubs for the handful of Confluent types the code references,
#   3. compiles main + test sources with javac,
#   4. runs the JUnit suite with the console launcher.
#
# CI still builds the real thing with the real Confluent jars — see .github/workflows/ci.yml.
# Prefer plain `./mvnw verify` whenever packages.confluent.io is reachable.
#
# Limitation: Avro / Schema Registry code paths compile and run against the stubs below,
# not against the real Confluent client, so results for those are indicative only.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${TMPDIR:-/tmp}/kse-offline-verify"
# Fallback only. The real value is derived from the resolved test classpath below:
# a hand-written pin here silently drifts the day Spring Boot bumps its JUnit, and the
# harness would then run a launcher of a different version from the engines it loads.
CONSOLE_VERSION="6.0.3"

cd "$ROOT"

if ./mvnw -q -o help:evaluate -Dexpression=maven.version -DforceStdout >/dev/null 2>&1; then
  :  # maven wrapper is usable
fi

echo "==> Preparing $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/depsonly" "$WORK/stubs" "$WORK/classes" "$WORK/testclasses"

# 1. Temporary pom without the artifacts that live on the blocked host.
#    flink-avro-confluent-registry is dropped too: it pulls kafka-schema-registry-client
#    transitively, which is enough to abort the whole dependency collection.
python3 - "$ROOT/pom.xml" "$WORK/depsonly/pom.xml" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src).read()
s = re.sub(r'\s*<dependency>\s*<groupId>io\.confluent</groupId>.*?</dependency>', '', s, flags=re.DOTALL)
s = re.sub(r'\s*<dependency>\s*<groupId>org\.apache\.flink</groupId>\s*'
           r'<artifactId>flink-avro-confluent-registry</artifactId>.*?</dependency>', '', s, flags=re.DOTALL)
open(dst, 'w').write(s)
PY

echo "==> Resolving dependencies (Confluent excluded)"
( cd "$WORK/depsonly" && mvn -q dependency:go-offline )
( cd "$WORK/depsonly" && mvn -q dependency:build-classpath \
    -Dmdep.outputFile="$WORK/cp.txt" -DincludeScope=test )
CP="$(cat "$WORK/cp.txt")"

# The console launcher must match the junit-platform on the classpath, so take the version
# from there rather than trusting the pin above. Platform and Jupiter share one version from
# JUnit 6 on, and Spring Boot's BOM manages both, so whatever resolved is the right answer.
DERIVED_VERSION="$(tr ':' '\n' <<< "$CP" \
  | sed -n 's|.*/junit-platform-commons-\([0-9][^/]*\)\.jar$|\1|p' | head -1)"
if [ -n "$DERIVED_VERSION" ]; then
  if [ "$DERIVED_VERSION" != "$CONSOLE_VERSION" ]; then
    echo "==> JUnit launcher: using $DERIVED_VERSION from the classpath (pin says $CONSOLE_VERSION)"
  fi
  CONSOLE_VERSION="$DERIVED_VERSION"
else
  echo "==> Could not read junit-platform from the classpath; falling back to $CONSOLE_VERSION"
fi

echo "==> Generating Confluent stubs"
mkdir -p "$WORK/stubs/io/confluent/kafka/schemaregistry/client" \
         "$WORK/stubs/io/confluent/kafka/serializers"

cat > "$WORK/stubs/io/confluent/kafka/schemaregistry/client/SchemaRegistryClient.java" <<'EOF'
package io.confluent.kafka.schemaregistry.client;
public interface SchemaRegistryClient {
    SchemaMetadata getLatestSchemaMetadata(String subject) throws Exception;
}
EOF
cat > "$WORK/stubs/io/confluent/kafka/schemaregistry/client/CachedSchemaRegistryClient.java" <<'EOF'
package io.confluent.kafka.schemaregistry.client;
public class CachedSchemaRegistryClient implements SchemaRegistryClient {
    public CachedSchemaRegistryClient(String baseUrl, int identityMapCapacity) {}
    public SchemaMetadata getLatestSchemaMetadata(String subject) throws Exception { return null; }
}
EOF
cat > "$WORK/stubs/io/confluent/kafka/schemaregistry/client/SchemaMetadata.java" <<'EOF'
package io.confluent.kafka.schemaregistry.client;
public class SchemaMetadata {
    private final String schema;
    public SchemaMetadata(int id, int version, String schema) { this.schema = schema; }
    public String getSchema() { return schema; }
}
EOF
cat > "$WORK/stubs/io/confluent/kafka/serializers/KafkaAvroDeserializer.java" <<'EOF'
package io.confluent.kafka.serializers;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.util.Map;
public class KafkaAvroDeserializer {
    public KafkaAvroDeserializer(SchemaRegistryClient client, Map<String, ?> props) {}
    public Object deserialize(String topic, byte[] bytes) { return null; }
}
EOF

echo "==> Compiling main sources"
javac -proc:none -nowarn -d "$WORK/classes" -cp "$CP" \
  $(find "$WORK/stubs" -name '*.java') \
  $(find src/main/java -name '*.java')

echo "==> Compiling test sources"
javac -proc:none -nowarn -d "$WORK/testclasses" -cp "$WORK/classes:$CP" \
  $(find src/test/java -name '*.java')

CONSOLE="$HOME/.m2/repository/org/junit/platform/junit-platform-console-standalone/${CONSOLE_VERSION}/junit-platform-console-standalone-${CONSOLE_VERSION}.jar"
if [ ! -f "$CONSOLE" ]; then
  echo "==> Fetching the JUnit console launcher"
  mvn -q dependency:get -Dartifact="org.junit.platform:junit-platform-console-standalone:${CONSOLE_VERSION}"
fi

# NOTE: the launcher must go on the real system classpath (java -cp ... ConsoleLauncher),
# never `java -jar`. With -jar the system classpath holds only the launcher, and Flink's
# job-graph deserialization then cannot see flink-table-runtime: every SELECT fails to
# submit, the planner circuit breaker trips, and a dozen tests fail for no real reason.
echo "==> Running tests"
java -cp "$CONSOLE:$WORK/classes:$WORK/testclasses:src/main/resources:src/test/resources:$CP" \
  org.junit.platform.console.ConsoleLauncher execute \
  --scan-classpath="$WORK/testclasses" \
  --details=summary \
  "$@"
