// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useState, type FC, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { copyText } from '../clipboard';
import { Badge, Button, Card, PageHeader } from '../components/ui';
import {
  EXPLORER_MCP, EXPLORER_SKILLS, GRAPH_EDGES, GRAPH_ORDER,
  HARNESS_ARCHITECTURE, HARNESS_CONTROLS, HARNESS_SKILLS,
  SCENARIOS,
  type GraphNodeId, type GraphNodeKind, type ScenarioId,
} from './mcp/mcpGuideGraph';

/** No runtime API and no simulated Kafka calls: a documented, cited architecture explorer. */
const CONCEPTS = [
  {
    name: 'Skill', icon: 'auto_stories', label: 'La méthode',
    description: 'Une procédure réutilisable : objectif, étapes, garde-fous et résultat vérifiable.',
    example: 'kafka-incident-triage',
    when: 'Enquêter sur un incident ou mener un travail multi-étapes.',
  },
  {
    name: 'Tool', icon: 'construction', label: 'La capacité',
    description: 'Un appel précis exposé par un serveur MCP. L’application effectue la mesure réelle.',
    example: 'kex_trace_key',
    when: 'Lire, calculer, diagnostiquer ou agir sur un périmètre contrôlé.',
  },
  {
    name: 'Resource', icon: 'inventory_2', label: 'Le contexte',
    description: 'Un document ou instantané adressable par URI, lu quand il est utile.',
    example: 'kex://skills',
    when: 'Lire un contrat, un catalogue, une référence ou un SKILL.md.',
  },
  {
    name: 'Prompt', icon: 'format_quote', label: 'Le cadrage',
    description: 'Un gabarit paramétré qui prépare l’investigation ; il n’exécute rien par lui-même.',
    example: 'kex_incident_triage',
    when: 'Démarrer une démarche ou homogénéiser un compte rendu.',
  },
] as const;

const POSITION: Record<GraphNodeId, { x: number; y: number }> = {
  request: { x: 28, y: 113 },
  entry: { x: 251, y: 113 },
  agent: { x: 474, y: 113 },
  policy: { x: 697, y: 113 },
  client: { x: 920, y: 113 },
  server: { x: 1143, y: 113 },
  backend: { x: 1143, y: 326 },
  evidence: { x: 697, y: 326 },
  answer: { x: 474, y: 326 },
};
const WIDTH = 195;
const HEIGHT = 110;

/** All edges are directional and stable across scenarios; content is mapped in mcpGuideGraph.ts. */
const PATHS: Record<string, string> = {
  'request-entry': 'M 223 168 L 251 168',
  'entry-agent': 'M 446 168 L 474 168',
  'agent-policy': 'M 669 168 L 697 168',
  'policy-client': 'M 892 168 L 920 168',
  'client-server': 'M 1115 168 L 1143 168',
  'server-backend': 'M 1241 223 L 1241 326',
  'backend-evidence': 'M 1143 381 L 892 381',
  'evidence-answer': 'M 697 381 L 669 381',
  'answer-request': 'M 474 381 L 134 381 Q 110 381 110 352 L 110 223',
};

const NODE_VISUALS: Record<GraphNodeKind, { icon: string; border: string; dot: string }> = {
  human: { icon: 'person', border: 'border-primary/35', dot: 'bg-primary' },
  agent: { icon: 'smart_toy', border: 'border-sky-500/35', dot: 'bg-sky-500' },
  guard: { icon: 'shield', border: 'border-amber-500/45', dot: 'bg-amber-500' },
  transport: { icon: 'swap_horiz', border: 'border-cyan-500/40', dot: 'bg-cyan-500' },
  service: { icon: 'dns', border: 'border-violet-500/35', dot: 'bg-violet-500' },
  evidence: { icon: 'verified', border: 'border-emerald-500/45', dot: 'bg-emerald-500' },
};

const SectionHeading: FC<{
  id: string; eyebrow: string; title: string; description?: string;
}> = ({ id, eyebrow, title, description }) => (
  <header className="space-y-2">
    <p className="text-[11px] uppercase tracking-[0.16em] font-semibold text-primary">{eyebrow}</p>
    <h2 id={id} className="scroll-mt-24 text-[23px] sm:text-[27px] tracking-tight font-semibold text-on-surface">{title}</h2>
    {description && <p className="text-[13px] leading-relaxed text-on-surface-variant max-w-3xl">{description}</p>}
  </header>
);

const Token: FC<{ children: ReactNode }> = ({ children }) => (
  <code className="rounded-md px-1.5 py-1 bg-surface-container-high font-mono text-[11px] text-on-surface break-all">{children}</code>
);

const ProcessGraph: FC<{ scenario: ScenarioId; onScenarioChange: (scenario: ScenarioId) => void }> = ({
  scenario, onScenarioChange,
}) => {
  const [selected, setSelected] = useState<GraphNodeId>('server');
  const current = SCENARIOS[scenario];
  const node = current.nodes.find((item) => item.id === selected) ?? current.nodes[0];
  const selectedIndex = GRAPH_ORDER.indexOf(selected);

  return (
    <section className="space-y-5" aria-labelledby="graphe">
      <SectionHeading id="graphe" eyebrow="02 / Observer le fonctionnement"
        title="Le graphe d’appel — de la question à la preuve"
        description="Architecture documentée de Kex-anHarness, reliée aux capacités de KafkaExplorer.
          Sélectionnez un scénario puis un nœud pour comprendre sa responsabilité et son garde-fou." />
      <div className="rounded-2xl border border-outline-variant/70 bg-surface-container-low overflow-hidden shadow-sm">
        <div className="flex flex-col gap-3 px-4 sm:px-6 py-4 border-b border-outline-variant/70 bg-surface-container/70">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="material-symbols-outlined text-primary text-[19px]" aria-hidden="true">account_tree</span>
              <span className="text-[13px] font-semibold text-on-surface">Explorateur d'architecture</span>
              <Badge tone="neutral">Référence, pas trace live</Badge>
            </div>
            <span className="text-xs text-on-surface-variant">9 nœuds · chemin dirigé · lecture seule</span>
          </div>
          <div className="flex flex-wrap gap-2" role="group" aria-label="Choisir un scénario">
            {(Object.keys(SCENARIOS) as ScenarioId[]).map((key) => (
              <button key={key} type="button" aria-pressed={scenario === key}
                className={`rounded-full px-3.5 py-2 text-[12px] font-medium border transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary ${scenario === key
                  ? 'border-primary/45 bg-primary/12 text-primary'
                  : 'border-outline-variant bg-surface text-on-surface-variant hover:text-on-surface hover:border-primary/35'}`}
                onClick={() => { onScenarioChange(key); setSelected('server'); }}
              >{SCENARIOS[key].title}</button>
            ))}
          </div>
          <p className="text-[12px] text-on-surface-variant">{current.intro}</p>
        </div>

        <div className="relative overflow-x-auto overscroll-x-contain p-3 sm:p-5"
          aria-label="Graphe d'appel défilable horizontalement">
          <div className="relative min-w-[1390px] h-[495px] rounded-xl border border-outline-variant/40"
            style={{ background: 'radial-gradient(ellipse at 50% 8%, rgb(var(--p) / 0.09), transparent 58%)' }}>
            <div className="absolute inset-0 opacity-[0.13] pointer-events-none"
              style={{ backgroundImage: 'radial-gradient(currentColor 0.65px, transparent 0.65px)', backgroundSize: '18px 18px' }} />
            <div className="absolute left-7 top-7 right-7 flex justify-between text-[10px] font-semibold tracking-[.12em] uppercase text-on-surface-variant">
              <span>Utilisateur</span>
              <span>Kex-anHarness · orchestrateur et politiques</span>
              <span>Transport MCP</span>
              <span>KafkaExplorer</span>
            </div>
            <svg className="absolute inset-0 h-full w-full pointer-events-none" viewBox="0 0 1390 495"
              preserveAspectRatio="xMinYMin meet" aria-hidden="true">
              <defs>
                <marker id="mcp-guide-call-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" className="fill-primary" />
                </marker>
                <marker id="mcp-guide-return-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
                </marker>
              </defs>
              {GRAPH_EDGES.map(([from, to, kind]) => {
                const focus = from === selected || to === selected;
                return (
                  <path key={`${from}-${to}`} d={PATHS[`${from}-${to}`]} fill="none"
                    stroke="currentColor" strokeWidth={focus ? 2.6 : 1.5}
                    strokeDasharray={kind === 'return' ? '5 5' : undefined}
                    className={focus ? 'text-primary' : 'text-on-surface-variant/30'}
                    markerEnd={kind === 'call' ? 'url(#mcp-guide-call-arrow)' : 'url(#mcp-guide-return-arrow)'}
                  />
                );
              })}
            </svg>
            {current.nodes.map((item, index) => {
              const { x, y } = POSITION[item.id];
              const style = NODE_VISUALS[item.kind];
              const active = selected === item.id;
              return (
                <button key={item.id} type="button" aria-pressed={active}
                  style={{ left: x, top: y, width: WIDTH, height: HEIGHT }}
                  onClick={() => setSelected(item.id)}
                  className={`absolute z-10 text-left rounded-xl border p-3 shadow-sm transition-all duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary
                    ${active ? 'border-primary bg-primary/10 ring-2 ring-primary/20 -translate-y-1 shadow-md' :
                      `bg-surface-container-high/95 hover:bg-surface-container-high hover:-translate-y-0.5 ${style.border}`}`}
                  aria-label={`Étape ${index + 1} : ${item.label}. ${item.subtitle}`}
                >
                  <span className="flex items-center justify-between gap-2 text-[10px] text-on-surface-variant">
                    <span className="inline-flex items-center gap-1.5">
                      <span className={`w-1.5 h-1.5 rounded-full ${style.dot}`} />
                      {String(index + 1).padStart(2, '0')}
                    </span>
                    <span aria-hidden="true" className="material-symbols-outlined text-[15px]">{style.icon}</span>
                  </span>
                  <span className="mt-2 block font-semibold text-[12px] text-on-surface leading-tight">{item.label}</span>
                  <span className="mt-1 block text-[10.5px] text-on-surface-variant leading-tight line-clamp-2">{item.subtitle}</span>
                </button>
              );
            })}
            <div className="absolute bottom-5 right-7 flex items-center gap-4 text-[10px] text-on-surface-variant">
              <span className="flex items-center gap-1.5"><span className="w-5 border-t-2 border-primary" /> Appel</span>
              <span className="flex items-center gap-1.5"><span className="w-5 border-t-2 border-dashed border-on-surface-variant/50" /> Retour</span>
            </div>
            <div className="absolute bottom-5 left-7 text-[10px] text-on-surface-variant">Sélectionner un bloc pour inspecter son contrat</div>
          </div>
        </div>

        <div className="px-4 sm:px-6 py-4 bg-surface-container/60 border-t border-outline-variant/70 grid gap-5 lg:grid-cols-[1.4fr_1fr]">
          <div aria-live="polite" aria-atomic="true" className="space-y-2">
            <p className="text-[10px] uppercase tracking-[.13em] text-primary font-semibold">Étape {selectedIndex + 1} / {GRAPH_ORDER.length}</p>
            <h3 className="text-[18px] font-semibold text-on-surface">{node.label}</h3>
            <p className="text-[13px] text-on-surface-variant leading-relaxed">{node.description}</p>
            <a href={node.source} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 text-[12px] font-medium text-primary hover:underline">
              Consulter la source <span aria-hidden="true" className="material-symbols-outlined text-[15px]">open_in_new</span>
            </a>
          </div>
          <div className="rounded-xl border border-outline-variant bg-surface-container-low p-4 space-y-2">
            <div className="flex items-center gap-2 text-[12px] font-semibold text-on-surface">
              <span className="material-symbols-outlined text-amber-600 text-[18px]" aria-hidden="true">shield</span>
              Contrat et garde-fou
            </div>
            <p className="text-[12px] text-on-surface-variant leading-relaxed">{node.guardrail}</p>
            <div className="flex gap-2 pt-1">
              <Button size="sm" variant="outline" onClick={() => setSelected(GRAPH_ORDER[(selectedIndex + GRAPH_ORDER.length - 1) % GRAPH_ORDER.length])}>
                Précédent
              </Button>
              <Button size="sm" variant="outline" onClick={() => setSelected(GRAPH_ORDER[(selectedIndex + 1) % GRAPH_ORDER.length])}>
                Suivant
              </Button>
            </div>
          </div>
        </div>
        <div className="px-4 sm:px-6 py-3 border-t border-outline-variant/60 bg-surface-container-low text-[11px] text-on-surface-variant leading-relaxed">
          <strong className="text-on-surface">Périmètre de vérité :</strong> le tracé illustre les responsabilités
          documentées par Kex-anHarness et KafkaExplorer. Il n'est pas calculé à partir d'un run, ne fournit
          ni latence mesurée ni trace OpenTelemetry, et ne prouve aucun appel effectif.
          Les skills internes de Kex (revue + read_skill) ne sont pas automatiquement installés depuis les URI <Token>skill://</Token>.
        </div>
      </div>
      <div className="rounded-xl border border-outline-variant/70 bg-surface-container px-4 py-3 flex gap-3">
        <span className="material-symbols-outlined text-primary shrink-0 text-[20px]" aria-hidden="true">forum</span>
        <div className="space-y-1">
          <p className="text-[12px] font-semibold text-on-surface">Exemple de question : {current.userQuestion}</p>
          <p className="text-[12px] text-on-surface-variant">{current.outcome}</p>
          <p className="text-[12px] text-on-surface-variant">{current.warning}</p>
        </div>
      </div>
    </section>
  );
};

const FAQ = [
  {
    q: 'Un Skill exécute-t-il automatiquement un Tool ?',
    a: 'Non. Un Skill est une procédure. L’hôte ou le modèle peut en suivre les étapes, mais chaque appel d’outil reste soumis au serveur MCP, au périmètre et aux permissions de l’agent.',
  },
  {
    q: 'Quelle différence entre Prompt et Skill ?',
    a: 'Un Prompt cadre une demande ou une réponse paramétrée. Un Skill décrit un processus complet, ses préconditions, ses contrôles et son critère de réussite. Les deux peuvent se compléter.',
  },
  {
    q: 'Kex-anHarness charge-t-il directement les skills de KafkaExplorer ?',
    a: 'Il ne faut pas le supposer. Kex possède son propre système de skills approuvés avec lecture read_skill. KafkaExplorer publie ses SKILL.md via des Resources MCP ; l’import ou l’activation relève du client.',
  },
  {
    q: 'Peut-on conclure qu’un message est absent après un scan partiel ?',
    a: 'Non. Une couverture partielle établit les éléments trouvés, pas l’absence dans ce qui n’a pas été inspecté. Lire coverage, stopReason, topicsNotReached et les valeurs Measured.',
  },
  {
    q: 'Ce graphe reflète-t-il un historique d’exécution réel ?',
    a: 'Non. C’est une vue de référence documentée, issue des composants et des séquences publiés par Kex-anHarness et KafkaExplorer. Les exécutions réelles sont à vérifier dans leurs journaux et traces corrélées.',
  },
];

const McpGuide: FC = () => {
  const [scenario, setScenario] = useState<ScenarioId>('incident');
  return (
  <div className="space-y-10 pb-12">
    <nav aria-label="Fil d'Ariane" className="flex items-center gap-2 text-[12px] text-on-surface-variant">
      <Link to="/mcp" className="text-primary hover:underline">MCP</Link>
      <span aria-hidden="true">/</span>
      <span>Guide Skills & Services</span>
    </nav>

    <div className="relative overflow-hidden rounded-3xl border border-outline-variant/60 bg-surface-container-low p-6 sm:p-8 lg:p-10">
      <div className="absolute -top-40 -right-36 h-80 w-80 rounded-full pointer-events-none blur-3xl bg-primary/10" />
      <div className="absolute -bottom-40 left-1/3 h-72 w-72 rounded-full pointer-events-none blur-3xl bg-sky-500/10" />
      <div className="relative z-10 flex flex-col lg:flex-row lg:items-end lg:justify-between gap-6">
        <div className="space-y-4 max-w-2xl">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="neutral">GUIDE PRATIQUE</Badge>
            <span className="text-[11px] text-on-surface-variant">MCP · Skills · Kex-anHarness</span>
          </div>
          <PageHeader kicker="Architectures agentiques vérifiables"
            title="Skills & Services via MCP"
            description="Bien choisir entre une procédure, un outil, une ressource et un prompt.
              Comprendre leur coopération, visualiser le chemin d'un appel et reconnaître les erreurs d'architecture." />
          <div className="flex flex-wrap gap-2">
            <a href="#graphe"
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-[12px] font-semibold text-on-primary hover:bg-primary-dim focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
              <span className="material-symbols-outlined text-[17px]" aria-hidden="true">account_tree</span>
              Explorer le graphe
            </a>
            <a href="#bonnes-pratiques"
              className="inline-flex items-center gap-2 rounded-lg border border-outline-variant bg-surface-container px-4 py-2.5 text-[12px] font-semibold text-on-surface hover:bg-surface-container-high">
              Voir les bonnes pratiques
            </a>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-2 min-w-[210px] max-w-xs">
          {[['4', 'primitives à distinguer'], ['3', 'parcours expliqués'], ['9', 'étapes du graphe'], ['0', 'appel réel déclenché']].map(([number, label]) => (
            <div key={label} className="rounded-xl border border-outline-variant/70 bg-surface-container-high/80 px-3 py-3">
              <p className="text-2xl font-semibold tracking-tight text-on-surface tabular-nums">{number}</p>
              <p className="text-[11px] text-on-surface-variant leading-tight">{label}</p>
            </div>
          ))}
        </div>
      </div>
    </div>

    <nav aria-label="Sommaire du guide" className="sticky top-2 z-20 flex flex-wrap items-center gap-1.5 rounded-xl border border-outline-variant/60 bg-surface-container-high/95 p-2 shadow-sm backdrop-blur-sm text-[12px]">
      {[
        ['#fondations', 'Fondations'], ['#graphe', 'Graphe d’appel'],
        ['#ateliers', 'Exemples'], ['#bonnes-pratiques', 'Bonnes pratiques'], ['#questions', 'FAQ'],
      ].map(([href, label]) => (
        <a key={href} href={href}
          className="rounded-lg px-3 py-2 text-on-surface-variant font-medium hover:text-primary hover:bg-primary/8 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary">{label}</a>
      ))}
    </nav>

    <section className="space-y-5" aria-labelledby="fondations">
      <SectionHeading id="fondations" eyebrow="01 / Modèle mental"
        title="Quatre concepts, quatre responsabilités"
        description="MCP est le protocole d’échange. Un Skill n’est pas un nouveau privilège :
          il décrit comment employer des capacités disponibles pour parvenir à un résultat." />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {CONCEPTS.map((concept, index) => (
          <Card key={concept.name} className="p-5 space-y-3 border border-outline-variant/55 bg-surface-container-low">
            <div className="flex items-center justify-between">
              <span className="inline-flex items-center justify-center rounded-xl bg-primary/10 h-10 w-10 text-primary">
                <span aria-hidden="true" className="material-symbols-outlined text-[22px]">{concept.icon}</span>
              </span>
              <span className="text-xs font-mono text-on-surface-variant">0{index + 1}</span>
            </div>
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-wider text-primary">{concept.label}</p>
              <h3 className="text-[19px] font-semibold text-on-surface">{concept.name}</h3>
            </div>
            <p className="text-[12px] leading-relaxed text-on-surface-variant min-h-[66px]">{concept.description}</p>
            <Token>{concept.example}</Token>
            <p className="text-[11px] leading-relaxed text-on-surface-variant"><strong className="text-on-surface">Quand : </strong>{concept.when}</p>
          </Card>
        ))}
      </div>
      <div className="overflow-x-auto rounded-xl border border-outline-variant/65">
        <table className="w-full text-[12px] min-w-[680px]">
          <caption className="sr-only">Quand utiliser chaque primitive MCP ou un Skill</caption>
          <thead className="text-left bg-surface-container-high text-on-surface-variant">
            <tr>{['Question', 'Réponse recommandée', 'Pourquoi'].map((h) => <th key={h} className="p-3 font-semibold">{h}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-outline-variant/50 text-on-surface">
            {[
              ['« Comment mener une investigation complète ? »', 'Skill', 'Étapes, garde-fous et livrable cohérent'],
              ['« Quel est le lag de ce groupe maintenant ? »', 'Tool', 'Mesure bornée avec provenance et permissions'],
              ['« Où trouver le contrat de mesure ? »', 'Resource', 'Document adressable et réutilisable'],
              ['« Comment lancer un triage structuré ? »', 'Prompt', 'Cadre paramétrable, sans exécution'],
            ].map(([question, answer, why]) => (
              <tr key={answer} className="bg-surface-container-low/50">
                <td className="px-3 py-3">{question}</td>
                <td className="px-3 py-3 font-semibold text-primary">{answer}</td>
                <td className="px-3 py-3 text-on-surface-variant">{why}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>

    <ProcessGraph scenario={scenario} onScenarioChange={setScenario} />

    <section className="space-y-5" aria-labelledby="ateliers">
      <SectionHeading id="ateliers" eyebrow="03 / Mise en pratique"
        title="Trois parcours à reproduire"
        description="Les exemples sont des séquences conseillées, pas des appels lancés par cette page. Commencez par découvrir les outils autorisés sur votre installation." />
      <div className="grid gap-4 lg:grid-cols-3">
        {[
          {
            title: 'Incident Kafka',
            icon: 'troubleshoot', skill: 'kafka-incident-triage',
            steps: ['Délimiter clé, fenêtre et topics', 'Tracer la clé et mesurer le lag / la DLQ', 'Rapporter Coverage et les segments non lus'],
            outcome: 'Un diagnostic avec preuves, sans replay automatique.',
            id: 'incident' as ScenarioId,
          },
          {
            title: 'Projection de capacité',
            icon: 'monitoring', skill: 'kafka-forecast-analyst',
            steps: ['Vérifier que la série est approuvée', 'Lire historique, prévision et qualité', 'Distinguer risque projeté et état actuel'],
            outcome: 'Un verdict prudent, sans seuil inventé.',
            id: 'forecast' as ScenarioId,
          },
          {
            title: 'Découverte de Skill',
            icon: 'auto_stories', skill: 'kex://skills',
            steps: ['Lire le catalogue et le manifeste SHA-256', 'Lire le SKILL.md correspondant', 'Faire revoir et activer côté client seulement'],
            outcome: 'Une procédure choisie explicitement, sans privilège implicite.',
            id: 'skill' as ScenarioId,
          },
        ].map((item) => (
          <Card key={item.title} className="border border-outline-variant/60 p-5 space-y-4">
            <div className="flex items-center gap-3">
              <span className="h-9 w-9 rounded-lg bg-primary/10 flex items-center justify-center text-primary">
                <span className="material-symbols-outlined text-xl" aria-hidden="true">{item.icon}</span>
              </span>
              <h3 className="font-semibold text-[15px] text-on-surface">{item.title}</h3>
            </div>
            <Token>{item.skill}</Token>
            <ol className="space-y-2">
              {item.steps.map((step, i) => (
                <li key={step} className="flex gap-2 text-[12px] text-on-surface-variant">
                  <span className="font-mono font-semibold text-primary">{i + 1}.</span>
                  {step}
                </li>
              ))}
            </ol>
            <p className="text-[12px] text-on-surface leading-relaxed">{item.outcome}</p>
            <a href="#graphe" onClick={() => setScenario(item.id)}
              className="text-[12px] font-medium text-primary hover:underline">
              Parcourir le graphe correspondant
            </a>
          </Card>
        ))}
      </div>
    </section>

    <section className="space-y-5" aria-labelledby="bonnes-pratiques">
      <SectionHeading id="bonnes-pratiques" eyebrow="04 / Production"
        title="Des règles simples, des garanties réelles"
        description="La qualité d’une orchestration se vérifie dans les contrats et les refus, pas dans la fluidité du texte produit." />
      <div className="grid gap-4 md:grid-cols-2">
        <Card className="border border-emerald-500/25 p-5 space-y-3">
          <h3 className="flex items-center gap-2 text-[14px] font-semibold text-on-surface">
            <span className="material-symbols-outlined text-emerald-600" aria-hidden="true">check_circle</span>
            À privilégier
          </h3>
          <ul className="space-y-3 text-[12px] text-on-surface-variant leading-relaxed">
            <li><strong className="text-on-surface">Skill :</strong> décrire un objectif, les préconditions, la preuve attendue et les critères d’arrêt.</li>
            <li><strong className="text-on-surface">Tool :</strong> choisir une fonction mesurable, bornée et autorisée, au plus près du service métier.</li>
            <li><strong className="text-on-surface">Resource :</strong> lire un contexte versionné, contrôler son URI et sa fraîcheur.</li>
            <li><strong className="text-on-surface">Prompt :</strong> guider l’analyse et demander explicitement preuves, limites et incertitudes.</li>
            <li><strong className="text-on-surface">Gouvernance :</strong> conserver permissions, approbations et audit à chaque étape du parcours.</li>
          </ul>
        </Card>
        <Card className="border border-rose-500/25 p-5 space-y-3">
          <h3 className="flex items-center gap-2 text-[14px] font-semibold text-on-surface">
            <span className="material-symbols-outlined text-rose-600" aria-hidden="true">block</span>
            Anti-patterns à éviter
          </h3>
          <ul className="space-y-3 text-[12px] text-on-surface-variant leading-relaxed">
            <li><strong className="text-on-surface">Le prompt qui fait tout :</strong> cacher des règles critiques dans une instruction libre.</li>
            <li><strong className="text-on-surface">Le Skill super-utilisateur :</strong> considérer un SKILL.md comme un droit d’accéder à Kafka.</li>
            <li><strong className="text-on-surface">La Resource illimitée :</strong> annoncer un inventaire borné comme exhaustif.</li>
            <li><strong className="text-on-surface">Le retour « zéro » :</strong> transformer des valeurs non mesurées en absence de problème.</li>
            <li><strong className="text-on-surface">Le graphe fictif :</strong> présenter une architecture comme une trace temporelle réelle.</li>
          </ul>
        </Card>
      </div>
      <div className="rounded-xl border border-outline-variant/70 bg-surface-container-low p-5 space-y-3">
        <p className="text-[12px] font-semibold text-on-surface">Checklist avant de confier un processus à un agent</p>
        <ol className="grid gap-2 sm:grid-cols-2 text-[12px] text-on-surface-variant">
          <li>1. Quelle connexion MCP et quels tools sont réellement disponibles ?</li>
          <li>2. Quels droits restent applicables au serveur et au client ?</li>
          <li>3. Quel budget, quelle fenêtre et quelle couverture seront prouvés ?</li>
          <li>4. Que se passe-t-il si le tool échoue ou refuse l’appel ?</li>
          <li>5. Qui approuve les écritures ou un nouveau Skill ?</li>
          <li>6. Comment vérifier le résultat avec une nouvelle mesure ?</li>
        </ol>
      </div>
    </section>

    <section className="space-y-4" aria-labelledby="questions">
      <SectionHeading id="questions" eyebrow="05 / Questions fréquentes"
        title="Ce qu’il faut clarifier avant d’aller plus loin" />
      <div className="divide-y divide-outline-variant/70 rounded-2xl border border-outline-variant/70 bg-surface-container-low overflow-hidden">
        {FAQ.map(({ q, a }) => (
          <details key={q} className="group px-5 py-4 open:bg-surface-container-high/40">
            <summary className="cursor-pointer list-none flex items-center justify-between gap-3 text-[13px] font-semibold text-on-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary">
              {q}
              <span className="material-symbols-outlined shrink-0 text-on-surface-variant group-open:rotate-180 transition-transform" aria-hidden="true">expand_more</span>
            </summary>
            <p className="mt-3 text-[12px] leading-relaxed text-on-surface-variant max-w-4xl">{a}</p>
          </details>
        ))}
      </div>
    </section>

    <section className="space-y-3" aria-label="Documentation source">
      <h2 className="text-[16px] font-semibold text-on-surface">Sources et suite de lecture</h2>
      <p className="text-[12px] leading-relaxed text-on-surface-variant">
        Graphe construit à partir des composants et des séquences documentés, pas d’un enregistrement de production.
        Les dépôts peuvent évoluer indépendamment : vérifier les contrats du serveur et les capacités exposées avant utilisation.
      </p>
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-[12px]">
        {[
          [HARNESS_ARCHITECTURE, 'Kex-anHarness · Architecture'],
          [HARNESS_CONTROLS, 'Kex-anHarness · Tool controls'],
          [HARNESS_SKILLS, 'Kex-anHarness · Skills approuvés'],
          [EXPLORER_MCP, 'KafkaExplorer · MCP'],
          [EXPLORER_SKILLS, 'KafkaExplorer · Distribution Skills'],
        ].map(([href, label]) => (
          <a key={href} href={href} target="_blank" rel="noreferrer"
            className="text-primary hover:underline inline-flex items-center gap-1">
            {label} <span className="material-symbols-outlined text-[14px]" aria-hidden="true">open_in_new</span>
          </a>
        ))}
      </div>
      <div className="flex flex-wrap gap-2 pt-3">
        <Link to="/mcp" className="text-[12px] font-semibold text-primary hover:underline">
          Retour au catalogue MCP
        </Link>
        <button type="button" className="text-[12px] font-semibold text-on-surface hover:underline"
          onClick={() => void copyText(window.location.href)}>
          Copier le lien de ce guide
        </button>
      </div>
    </section>
  </div>
  );
};

export default McpGuide;
