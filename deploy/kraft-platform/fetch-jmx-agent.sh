#!/usr/bin/env sh
# Télécharge l'agent jmx_prometheus dans ./jmx-exporter/.
#
# Sans ce jar, le broker ne démarre pas : la JVM échoue sur -javaagent avant
# même de lire sa configuration, et le conteneur sort en code 1 sans un seul
# log Kafka. Le service `kafka-00-preflight` refuse d'ailleurs de laisser
# démarrer le broker dans ce cas.
#
# Usage :  ./fetch-jmx-agent.sh [version]          (défaut : 1.6.0)
#          AGENT_BASE_URL=https://nx-repo…/raw ./fetch-jmx-agent.sh
#
# Où va-t-il chercher le jar :
#   * versions 1.1.0+ -> GitHub Releases. Depuis que l'agent est shadé (1.2.0),
#     c'est le seul canal : sur Maven Central, io.prometheus.jmx:
#     jmx_prometheus_javaagent s'arrête à 1.0.1 (le reste du projet, lui,
#     continue d'y être publié — d'où la confusion).
#   * versions 0.x / 1.0.x -> Maven Central.
#   * AGENT_BASE_URL       -> miroir interne, le nom du jar est ajouté au bout.

set -eu

DEFAULT_VERSION=1.6.0
VERSION="${1:-$DEFAULT_VERSION}"
DIR="$(cd "$(dirname "$0")" && pwd)/jmx-exporter"
JAR="jmx_prometheus_javaagent-${VERSION}.jar"

if [ -n "${AGENT_BASE_URL:-}" ]; then
  URL="${AGENT_BASE_URL%/}/${JAR}"
else
  case "$VERSION" in
    0.*|1.0.*)
      URL="https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${VERSION}/${JAR}" ;;
    *)
      URL="https://github.com/prometheus/jmx_exporter/releases/download/${VERSION}/${JAR}" ;;
  esac
fi

if [ -f "${DIR}/${JAR}" ]; then
  echo "Déjà présent : jmx-exporter/${JAR}"
  exit 0
fi

echo "Téléchargement de ${URL}"
curl -fsSL --output "${DIR}/${JAR}.part" "${URL}"
mv "${DIR}/${JAR}.part" "${DIR}/${JAR}"
echo "OK : jmx-exporter/${JAR}"

if [ "${VERSION}" != "${DEFAULT_VERSION}" ]; then
  cat <<EOF

Version non standard : renseigner la ligne complète dans .env, sinon le compose
continuera de pointer sur le jar ${DEFAULT_VERSION} —

  KAFKA_JMX_AGENT_OPTS=-javaagent:/usr/share/jmx_exporter/${JAR}=9200:/usr/share/jmx_exporter/kafka-broker.yml
EOF
fi
