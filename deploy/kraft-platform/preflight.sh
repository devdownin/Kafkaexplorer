#!/usr/bin/env sh
# Exécuté par le service `kafka-00-preflight`, avant le broker.
#
#  1. Le volume nommé est créé vide et appartenant à root par Docker, alors que
#     l'image CP tourne en `appuser` (uid 1000).
#  2. Le jar de l'agent JMX doit exister : le broker le charge via -javaagent,
#     et la JVM meurt avant d'écrire la moindre ligne de log s'il manque —
#     un « fail to start » sans aucun message Kafka pour l'expliquer.
#
# Volontairement dans un fichier plutôt qu'en `entrypoint:` inline : les `$` d'un
# script embarqué dans le YAML doivent y être échappés en `$$`, ce que personne
# ne relit correctement, et qui casse en silence (le test serait sauté).

set -eu

DATA_DIR="${DATA_DIR:-/var/lib/kafka/data}"

# ── 1. Propriété du répertoire de données ────────────────────────────────────
if id -u appuser >/dev/null 2>&1; then
  chown -R appuser:appuser "$DATA_DIR"
else
  # Repli si le compte `appuser` changeait de nom dans une future image : un
  # `chown: invalid user` ferait échouer ce one-shot, et kafka-00 refuserait
  # de démarrer sur une dépendance en erreur.
  chown -R 1000:1000 "$DATA_DIR"
fi

# ── 2. Agent Prometheus ──────────────────────────────────────────────────────
case "${KAFKA_JMX_AGENT_OPTS:-}" in
  *-javaagent:*)
    jar="${KAFKA_JMX_AGENT_OPTS#*-javaagent:}"   # après -javaagent:
    jar="${jar%%=*}"                             # avant =port:config
    if [ ! -f "$jar" ]; then
      cat >&2 <<EOF

  ERREUR — agent Prometheus introuvable : $jar

  Le broker charge cet agent au demarrage de la JVM. Sans le jar, il sort
  immediatement en code 1, sans aucun log Kafka pour l'expliquer.

  Corriger au choix :
    ./fetch-jmx-agent.sh             deposer le jar dans ./jmx-exporter/
    KAFKA_JMX_AGENT_OPTS=            (vide) dans .env : demarrer sans metriques

EOF
      exit 1
    fi
    echo "preflight: agent JMX present ($jar)"
    ;;
  *)
    echo "preflight: agent JMX desactive (KAFKA_JMX_AGENT_OPTS vide)"
    ;;
esac

echo "preflight: OK"
