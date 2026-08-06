#!/usr/bin/env sh
# Télécharge l'agent jmx_prometheus dans ./jmx-exporter/.
#
# Sans ce jar, le broker ne démarre pas : la JVM échoue sur -javaagent avant
# même de lire sa configuration ("Error opening zip file or JAR manifest
# missing"), et le conteneur sort en code 1.
#
# Usage :  ./fetch-jmx-agent.sh [version]        (défaut : 0.20.0)
#          MAVEN_BASE=https://nx-repo…/maven ./fetch-jmx-agent.sh 1.4.0

set -eu

VERSION="${1:-0.20.0}"
MAVEN_BASE="${MAVEN_BASE:-https://repo1.maven.org/maven2}"
DIR="$(cd "$(dirname "$0")" && pwd)/jmx-exporter"
JAR="jmx_prometheus_javaagent-${VERSION}.jar"
URL="${MAVEN_BASE}/io/prometheus/jmx/jmx_prometheus_javaagent/${VERSION}/${JAR}"

if [ -f "${DIR}/${JAR}" ]; then
  echo "Déjà présent : jmx-exporter/${JAR}"
  exit 0
fi

echo "Téléchargement de ${URL}"
curl -fsSL --output "${DIR}/${JAR}.part" "${URL}"
mv "${DIR}/${JAR}.part" "${DIR}/${JAR}"
echo "OK : jmx-exporter/${JAR}"

if [ "${VERSION}" != "0.20.0" ]; then
  cat <<EOF

Version non standard : renseigner la ligne complète dans .env, sinon le compose
continuera de pointer sur le jar 0.20.0 —

  KAFKA_JMX_AGENT_OPTS=-javaagent:/usr/share/jmx_exporter/${JAR}=9200:/usr/share/jmx_exporter/kafka-broker.yml
EOF
fi
