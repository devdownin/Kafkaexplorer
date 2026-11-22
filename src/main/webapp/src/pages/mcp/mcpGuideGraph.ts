// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Curated, source-backed *reference paths*. They are NOT runtime spans or fabricated
 * recordings. Kex-anHarness publishes its request sequence in docs/ARCHITECTURE.md,
 * tool constraints in docs/TOOL-CONTROLS.md, and reviewed skill loading in
 * docs/GOVERNED-GENERAL-AGENT.md. KafkaExplorer provides the typed MCP adapters.
 *
 * Keep the graph editorially stable: a new implementation is not silently made a
 * "measured call" just because a file or tool exists.
 */
export const HARNESS_ARCHITECTURE =
  'https://github.com/devdownin/Kex-anHarness/blob/main/docs/ARCHITECTURE.md';
export const HARNESS_CONTROLS =
  'https://github.com/devdownin/Kex-anHarness/blob/main/docs/TOOL-CONTROLS.md';
export const HARNESS_SKILLS =
  'https://github.com/devdownin/Kex-anHarness/blob/main/docs/GOVERNED-GENERAL-AGENT.md';
export const EXPLORER_MCP =
  'https://github.com/devdownin/Kafkaexplorer/blob/main/docs/notes/mcp-server.md';
export const EXPLORER_SKILLS =
  'https://github.com/devdownin/Kafkaexplorer/blob/main/docs/mcp-skills.md';

export type ScenarioId = 'incident' | 'forecast' | 'skill';

export type GraphNodeId =
  | 'request' | 'entry' | 'agent' | 'policy' | 'client'
  | 'server' | 'backend' | 'evidence' | 'answer';
export type GraphNodeKind = 'human' | 'agent' | 'guard' | 'transport' | 'service' | 'evidence';
export interface GuideNode {
  id: GraphNodeId;
  label: string;
  subtitle: string;
  description: string;
  guardrail: string;
  kind: GraphNodeKind;
  source: string;
}
export interface GuideScenario {
  id: ScenarioId;
  title: string;
  intro: string;
  userQuestion: string;
  outcome: string;
  warning: string;
  nodes: GuideNode[];
}

const reference = {
  request: HARNESS_ARCHITECTURE, entry: HARNESS_ARCHITECTURE,
  agent: HARNESS_ARCHITECTURE, policy: HARNESS_CONTROLS,
  client: HARNESS_ARCHITECTURE, server: EXPLORER_MCP,
  backend: EXPLORER_MCP, evidence: EXPLORER_MCP,
  answer: HARNESS_ARCHITECTURE,
} satisfies Record<GraphNodeId, string>;

function node(
  id: GraphNodeId, label: string, subtitle: string,
  description: string, guardrail: string, kind: GraphNodeKind,
  source = reference[id],
): GuideNode {
  return { id, label, subtitle, description, guardrail, kind, source };
}

export const GRAPH_ORDER: GraphNodeId[] = [
  'request', 'entry', 'agent', 'policy', 'client',
  'server', 'backend', 'evidence', 'answer',
];

export const SCENARIOS: Record<ScenarioId, GuideScenario> = {
  incident: {
    id: 'incident',
    title: 'Diagnostic d’incident',
    intro: 'Une question métier devient une séquence de mesures bornées et vérifiables.',
    userQuestion: '« Où est passée la commande ORD-1042 ? »',
    outcome: 'Hypothèses, hops observés, offsets, couverture et prochaines vérifications sûres.',
    warning: 'Une absence dans un échantillon partiel ne prouve pas que le message n’existe pas.',
    nodes: [
      node('request', 'Question métier', 'Utilisateur',
        'L’utilisateur fixe la clé de corrélation, le périmètre et la fenêtre de recherche.',
        'Une question ne confère pas de droits supplémentaires.', 'human'),
      node('entry', 'AgentController', 'Kex · API authentifiée',
        'L’API de Kex reçoit la demande et applique son authentification avant la conversation.',
        'La clé API ne doit pas être transmise au modèle.', 'agent'),
      node('agent', 'AgentService', 'ChatClient · choix d’outils',
        'Le modèle choisit les outils disponibles et conduit la recherche. Un skill approuvé peut structurer les étapes.',
        'Le modèle ne peut pas inventer un outil qui n’est pas exposé.', 'agent'),
      node('policy', 'Tool controls', 'Recording callbacks',
        'La sélection et les règles de Kex filtrent arguments, connexions et outils avant l’invocation.',
        'L’interdiction d’un outil ne doit pas être contournée par un autre appel.', 'guard'),
      node('client', 'McpSyncClient', 'Streamable HTTP',
        'Le client MCP de Kex transporte tools/list puis tools/call vers KafkaExplorer, avec le bearer lié à son URL.',
        'Les identifiants de connexion ne sont pas injectés dans les réponses.', 'transport'),
      node('server', 'kex_trace_key', 'Serveur MCP KafkaExplorer',
        'KafkaExplorer expose le diagnostic et contrôle le périmètre, les budgets, la lecture seule et la réduction des données.',
        'Un appel hors scope ou indisponible doit être refusé, pas dégradé silencieusement.', 'service'),
      node('backend', 'Trace & Kafka', 'StreamFlowService',
        'Les adaptateurs réutilisent les services KafkaExplorer pour chercher les hops dans des topics autorisés.',
        'Bornes d’échantillonnage, délais et partitions doivent rester explicites.', 'service'),
      node('evidence', 'Preuves mesurées', 'Coverage · Measured',
        'Le résultat inclut ce qui a été lu, les topics non atteints, stopReason et les valeurs mesurées ou inconnues.',
        'UNKNOWN n’est pas zéro ; TIME_BUDGET n’est pas EXHAUSTED.', 'evidence'),
      node('answer', 'Synthèse Kex', 'Réponse avec limites',
        'L’agent restitue les hops confirmés, les incertitudes et les gestes de vérification sûrs.',
        'Ne conclure « introuvable » qu’avec un périmètre et une couverture réellement suffisants.', 'agent'),
    ],
  },
  forecast: {
    id: 'forecast',
    title: 'Analyse de forecast',
    intro: 'Les projections restent distinctes de l’historique mesuré et de la qualité constatée.',
    userQuestion: '« Notre backlog dépassera-t-il le seuil demain ? »',
    outcome: 'Historique réel, prédiction, bandes d’incertitude et risque conditionné par la qualité.',
    warning: 'Une prévision rassurante ne remplace jamais une mesure actuelle ni une alerte réelle.',
    nodes: [
      node('request', 'Question de risque', 'Utilisateur',
        'La demande identifie la métrique, l’horizon et le seuil sans supposer que le modèle est prêt.',
        'Un seuil non défini doit être présenté comme une limite.', 'human'),
      node('entry', 'AgentController', 'Kex · API authentifiée',
        'La requête arrive dans le flux de conversation habituel de Kex-anHarness.',
        'L’authentification précède la sélection d’outil.', 'agent'),
      node('agent', 'AgentService', 'ChatClient · orchestration',
        'Le modèle choisit les appels adaptés à la métrique et compare historique et forecast.',
        'Une procédure métier sert de guide, pas de permission.', 'agent'),
      node('policy', 'Tool controls', 'Autorisations Kex',
        'Kex réduit le choix aux tools autorisés pour cette conversation et vérifie les arguments.',
        'Le modèle ne doit pas contourner un refus ou un outil absent.', 'guard'),
      node('client', 'McpSyncClient', 'Streamable HTTP',
        'Le client MCP exécute les appels de lecture au serveur KafkaExplorer.',
        'Le serveur doit être initialisé et les outils forecast réellement exposés.', 'transport'),
      node('server', 'kex_forecast_*', 'Outils de prévision',
        'La séquence peut consulter kex_list_forecastable_metrics, kex_metric_history, kex_forecast_metric et kex_get_forecast_quality.',
        'Une série doit être approuvée ; ne pas déclencher d’inférence par une lecture.', 'service'),
      node('backend', 'ForecastPilot', 'Séries et qualité',
        'KafkaExplorer fournit l’historique, les résultats stockés, la qualité réalisée et les seuils configurés.',
        'Les horizons, révisions du modèle et timestamps doivent être conservés.', 'service'),
      node('evidence', 'Historique ≠ futur', 'Bandes + fiabilité',
        'L’agent reçoit des points observés, des points prédits et des mesures de qualité séparées.',
        'Une bande nominale n’est pas une garantie statistique de franchissement.', 'evidence'),
      node('answer', 'Verdict nuancé', 'Action proposée',
        'La réponse distingue danger confirmé, risque prévu et insuffisance de données.',
        'Pas d’activation d’alerte ou de seuil implicite.', 'agent'),
    ],
  },
  skill: {
    id: 'skill',
    title: 'Découverte d’un Skill',
    intro: 'Le manifeste indique quoi charger ; le client décide si et comment utiliser la procédure.',
    userQuestion: '« Aide-moi à investiguer avec une procédure validée. »',
    outcome: 'Un SKILL.md identifié, lu et vérifié ; aucune activation implicite.',
    warning: 'Le read_skill interne à Kex et les Resources skill:// de KafkaExplorer sont deux mécanismes distincts.',
    nodes: [
      node('request', 'Besoin métier', 'Utilisateur',
        'L’utilisateur demande une méthode réutilisable pour une tâche clairement définie.',
        'La demande n’installe et n’exécute aucun skill.', 'human'),
      node('entry', 'AgentController', 'Kex · requête',
        'La conversation de Kex reçoit la demande et conserve son contexte de droits.',
        'La demande ne peut pas se donner le rôle ADMIN.', 'agent'),
      node('agent', 'Skills de Kex', 'Index · read_skill',
        'Kex indexe ses skills de domaine approuvés et peut charger leurs instructions avec read_skill.',
        'Les skills Kex PENDING ne sont pas injectés avant revue nominative.', 'agent', HARNESS_SKILLS),
      node('policy', 'Revue & droits', 'ADMIN · politique',
        'Les permissions par connexion et les règles d’invocation restent indépendantes du texte d’un skill.',
        'Une procédure n’autorise jamais l’appel d’un outil refusé.', 'guard', HARNESS_SKILLS),
      node('client', 'McpSyncClient', 'Resources MCP',
        'Un client compatible peut lire resources/read pour découvrir kex://skills et récupérer skill://…/SKILL.md.',
        'Le support des Resources par Kex n’implique pas l’import automatique dans sa mémoire.', 'transport', HARNESS_ARCHITECTURE),
      node('server', 'kex://skills', 'Manifeste KafkaExplorer',
        'KafkaExplorer publie un catalogue figé de SKILL.md et leurs URI avec empreinte SHA-256.',
        'Ce catalogue n’annonce pas l’extension native skills/list/skills/get.', 'service', EXPLORER_SKILLS),
      node('backend', 'SKILL.md', 'Ressource classpath',
        'Le fichier est lu depuis les ressources empaquetées et la liste autorisée ; pas depuis un chemin fourni par Kafka.',
        'Aucun chargement de fichier arbitraire ni exécution de script.', 'service', EXPLORER_SKILLS),
      node('evidence', 'Manifeste vérifié', 'URI · SHA-256',
        'Le client peut comparer la somme SHA-256 avec le contenu du SKILL.md reçu.',
        'Une empreinte prouve l’intégrité des octets, pas la sûreté de la procédure.', 'evidence', EXPLORER_SKILLS),
      node('answer', 'Choix conscient', 'Activation côté hôte',
        'L’agent ou l’opérateur peut consulter, revoir et choisir une procédure selon les mécanismes de son client.',
        'Ne pas confondre « lu », « approuvé » et « activé ».', 'agent', HARNESS_SKILLS),
    ],
  },
};

/** Reference architecture only; the arrows are not a recorded MCP trace. */
export const GRAPH_EDGES: readonly [GraphNodeId, GraphNodeId, 'call' | 'return'][] = [
  ['request', 'entry', 'call'], ['entry', 'agent', 'call'],
  ['agent', 'policy', 'call'], ['policy', 'client', 'call'],
  ['client', 'server', 'call'], ['server', 'backend', 'call'],
  ['backend', 'evidence', 'return'], ['evidence', 'answer', 'return'],
  ['answer', 'request', 'return'],
];
