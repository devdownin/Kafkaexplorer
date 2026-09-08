// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { useSearchParams } from 'react-router-dom';
import axios from 'axios';
import { copyText } from '../clipboard';
import {
  Badge, Button, Card, EmptyState, ErrorPanel, Field, Input, MeasuredValue, PageHeader, Select,
  Stat, Textarea, Tooltip,
} from '../components/ui';
import type {
  McpApprovalResult, McpCallView, McpCatalogView, McpClientConfig, McpClientRow, McpOverrideView,
  McpReplay, McpStatsView, McpStatusView, McpSwitchResult, McpToolRow, McpTryResult,
} from '../api/types';
import {
  DEFAULT_WINDOW, EMPTY_FILTERS, WINDOWS, callsToCsv, coverageLabel, deniedShare, filtersFromParams,
  filtersToParams, formatBytes, formatDuration, formatNumber, historyNotice, isWindow,
  explainDenial, filterTools, firstSentence, hasMoreThanFirstSentence, outcomeLabel,
  overrideBanner, replaySummary, sortTools, windowCaveat,
} from './mcp/mcpConsoleLogic';
import type { FeedFilters, McpWindow } from './mcp/mcpConsoleLogic';

/**
 * L'écran MCP : ce que ce serveur offre à un agent, et ce que les agents en ont fait.
 *
 * C'est la contrepartie visible de la décision de brancher un LLM sur un cluster. Aucun autre
 * serveur MCP Kafka n'offre cela : la surface s'y découvre par un `--list-tools` en ligne de
 * commande et l'usage par des logs.
 *
 * **Deux règles gouvernent chaque chiffre affiché ici**, et elles sont celles du module appliquées
 * à l'écran qui le supervise — lui-même une mesure. Une valeur non mesurée se rend « non mesuré »
 * avec sa raison (`<MeasuredValue>`), jamais un tiret ni un zéro. Et toute agrégation porte la
 * fenêtre réellement couverte : l'anneau est borné, donc une carte titrée « 24 h » sur un anneau
 * qui a évincé est un chiffre faux sous une étiquette juste.
 */
const REFRESH_MS = 5000;

type Tab = 'catalog' | 'supervision';

const Mcp: FC = () => {
  const [params, setParams] = useSearchParams();

  const tab: Tab = params.get('tab') === 'supervision' ? 'supervision' : 'catalog';
  const windowParam = params.get('window');
  const window_: McpWindow = isWindow(windowParam) ? windowParam : DEFAULT_WINDOW;
  const filters = useMemo(() => filtersFromParams(params), [params]);

  const [status, setStatus] = useState<McpStatusView | null>(null);
  const [catalog, setCatalog] = useState<McpCatalogView | null>(null);
  const [stats, setStats] = useState<McpStatsView | null>(null);
  const [calls, setCalls] = useState<McpCallView[]>([]);
  const [clients, setClients] = useState<McpClientRow[]>([]);
  const [overrides, setOverrides] = useState<McpOverrideView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  /* Le flux se met en pause au survol : une table qui se réordonne sous le curseur pendant qu'on
     lit une ligne fait perdre la ligne, et c'est celle qu'on était en train d'examiner. */
  const [paused, setPaused] = useState(false);

  const setUrl = useCallback(
    (next: { tab?: Tab; window?: McpWindow; filters?: FeedFilters }) => {
      setParams(
        filtersToParams(next.filters ?? filters, next.window ?? window_, next.tab ?? tab),
        { replace: true },
      );
    },
    [filters, setParams, tab, window_],
  );

  const load = useCallback(async () => {
    try {
      const query = new URLSearchParams({ window: window_ });
      const feed = new URLSearchParams({ limit: '200' });
      for (const [key, value] of Object.entries(filters)) if (value) feed.set(key, value);

      const [statusRes, catalogRes, statsRes, callsRes, clientsRes, overridesRes] =
        await Promise.all([
          axios.get<McpStatusView>('/api/mcp/status'),
          axios.get<McpCatalogView>(`/api/mcp/catalog?${query}`),
          axios.get<McpStatsView>(`/api/mcp/stats?${query}`),
          axios.get<McpCallView[]>(`/api/mcp/calls?${feed}`),
          axios.get<McpClientRow[]>(`/api/mcp/clients?${query}`),
          axios.get<McpOverrideView[]>('/api/mcp/overrides'),
        ]);
      setStatus(statusRes.data);
      setCatalog(catalogRes.data);
      setStats(statsRes.data);
      setCalls(callsRes.data);
      setClients(clientsRes.data);
      setOverrides(overridesRes.data);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'la console MCP est injoignable');
    } finally {
      setLoading(false);
    }
  }, [filters, window_]);

  /*
   * Une seule boucle : le premier chargement et le rafraîchissement sont le même appel, et
   * `paused` fait partie des dépendances plutôt que d'être lu dans une ref. Une ref lue pendant le
   * rendu est ce que la règle `react-hooks/refs` interdit, à raison — mais l'effet ici n'a pas
   * besoin d'une valeur fraîche à l'intérieur d'un intervalle figé : il se reconstruit quand la
   * pause change, ce qui est plus simple et plus juste.
   *
   * `cancelled` protège la seule chose qui compte : une réponse qui arrive après un changement de
   * fenêtre ou de filtre écraserait l'état par des données que l'écran ne demande plus.
   */
  useEffect(() => {
    let cancelled = false;
    const run = () => {
      if (!cancelled) void load();
    };
    run();
    if (paused) {
      return () => {
        cancelled = true;
      };
    }
    const timer = setInterval(run, REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [load, paused]);

  if (loading) return <div className="p-6 text-on-surface-variant">Chargement…</div>;
  if (error) {
    return (
      <ErrorPanel
        error={{
          title: 'La console MCP est injoignable',
          hint: "L'application répond-elle ? Ces endpoints sont ceux de l'application, pas ceux du serveur MCP.",
          raw: error,
        }}
        onRetry={() => void load()}
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        kicker="Agents"
        title="MCP"
        description="Ce que ce serveur offre à un agent, et ce que les agents en ont fait."
        actions={
          <div className="flex gap-2">
            <Button variant={tab === 'catalog' ? 'primary' : 'outline'} onClick={() => setUrl({ tab: 'catalog' })}>
              Catalogue
            </Button>
            <Button
              variant={tab === 'supervision' ? 'primary' : 'outline'}
              onClick={() => setUrl({ tab: 'supervision' })}
            >
              Supervision
            </Button>
          </div>
        }
      />

      {status ? (
        <StatusBanner
          status={status}
          locked={overrides.some((o) => o.kind === 'READONLY')}
          onSwitched={() => void load()}
        />
      ) : null}
      <OverrideBanner overrides={overrides} />

      {status && !status.enabled ? (
        <EmptyState
          icon="power_settings_new"
          title="Le serveur MCP est désactivé"
          description="explorer.mcp.enabled=false — aucun outil n'est enregistré et aucun endpoint n'écoute. C'est le défaut livré : brancher un modèle sur un cluster est une décision qui se prend."
        />
      ) : tab === 'catalog' ? (
        <CatalogTab
          catalog={catalog}
          endpoint={status?.endpoint ?? null}
          tryItEnabled={status?.tryItEnabled ?? false}
          overrides={overrides}
          onSwitched={() => void load()}
        />
      ) : (
        <SupervisionTab
          stats={stats}
          calls={calls}
          clients={clients}
          filters={filters}
          window={window_}
          paused={paused}
          onPause={setPaused}
          onFilters={(next) => setUrl({ filters: next })}
          onWindow={(next) => setUrl({ window: next })}
          overrides={overrides}
          onSwitched={() => void load()}
        />
      )}
    </div>
  );
};

/** Six faits, lus du runtime — l'endpoint est l'adresse liée, pas la propriété. */
/**
 * Les dérogations en cours, en permanence tant qu'il en tient une.
 *
 * Non refermable, et c'est tout son intérêt : le pire mode de défaillance du commutateur est une
 * dérogation qui survit à l'incident qu'elle répondait et devient la configuration permanente que
 * personne ne se souvient d'avoir choisie. Un bandeau qu'on peut fermer serait fermé le premier
 * jour, et la dérogation resterait.
 */
const OverrideBanner: FC<{ overrides: McpOverrideView[] }> = ({ overrides }) => {
  const lines = overrideBanner(overrides);
  if (!lines) return null;
  return (
    <Card className="border-warning bg-warning-container p-4">
      <div className="flex items-start gap-3">
        <span className="material-symbols-outlined text-warning" aria-hidden="true">
          shield_lock
        </span>
        <div className="space-y-1 text-sm">
          <p className="font-medium">
            {lines.length === 1 ? 'Une dérogation est active' : `${lines.length} dérogations sont actives`}
          </p>
          <ul className="space-y-1 text-xs text-on-surface-variant">
            {lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="text-xs text-on-surface-variant">
            Elles ne s'effacent pas d'elles-mêmes : une expiration rouvrirait la surface à un moment
            arbitraire, sans que personne le décide.
          </p>
        </div>
      </div>
    </Card>
  );
};

/**
 * Le formulaire que tout levier partage : qui, et pourquoi.
 *
 * Un vrai formulaire plutôt que `prompt()`. Le `prompt()` du navigateur bloque le fil, ne se
 * style pas, ne valide rien, et plusieurs navigateurs le suppriment purement et simplement dans
 * une iframe — un commutateur de secours qui dépend de lui est un commutateur qui, le jour de
 * l'incident, ne s'ouvre pas.
 *
 * La raison est **obligatoire**, l'auteur non. C'est la raison qui, dans le bandeau, fait lever la
 * dérogation quand l'incident est passé ; un nom sans phrase n'apprend rien à qui la lit trois
 * semaines plus tard. Le champ dit que l'auteur est déclaré et non vérifié : cette application
 * n'authentifie personne, et une colonne « auteur » qui laisserait croire le contraire serait
 * pire que pas de colonne.
 */
const OverrideForm: FC<{
  title: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: (actor: string, reason: string) => Promise<void>;
}> = ({ title, confirmLabel, onCancel, onConfirm }) => {
  const [actor, setActor] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (reason.trim() === '') return;
    setBusy(true);
    try {
      await onConfirm(actor.trim(), reason.trim());
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(e) => void submit(e)} className="w-72 space-y-2 text-left">
      <p className="text-xs font-medium">{title}</p>
      <Field label="Pourquoi ?">
        {(field) => (
          <Input
            {...field}
            autoFocus
            value={reason}
            placeholder="l'agent boucle sur prod"
            onChange={(e) => setReason(e.target.value)}
          />
        )}
      </Field>
      <Field label="Qui ? (déclaré, non vérifié)">
        {(field) => (
          <Input {...field} value={actor} onChange={(e) => setActor(e.target.value)} />
        )}
      </Field>
      <div className="flex gap-2">
        <Button size="sm" type="submit" variant="danger" disabled={busy || reason.trim() === ''}>
          {confirmLabel}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          Annuler
        </Button>
      </div>
    </form>
  );
};

/**
 * Le levier générique : un bouton qui ouvre le formulaire pour restreindre, et qui lève sans rien
 * demander.
 *
 * L'asymétrie est voulue. Restreindre demande une raison parce que la raison est ce qui fera lever
 * la dérogation plus tard ; lever n'en demande pas, parce qu'une dérogation levée ne laisse rien
 * derrière elle qu'il faudrait expliquer, et qu'un formulaire à ce moment-là est un frein sur le
 * seul des deux gestes qui rend la surface à son état configuré.
 */
const OverrideSwitch: FC<{
  endpoint: string;
  restricted: boolean;
  formTitle: string;
  restrictLabel: string;
  liftLabel: string;
  onSwitched: () => void;
}> = ({ endpoint, restricted, formTitle, restrictLabel, liftLabel, onSwitched }) => {
  const [open, setOpen] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const send = async (enable: boolean, actor: string, reason: string) => {
    try {
      const res = await axios.post<McpSwitchResult>(endpoint, { enable, actor, reason });
      // `applied: false` n'est pas une erreur : verrouiller une surface que la configuration tient
      // déjà en lecture seule ne change rien, et le message dit lequel des deux s'est produit.
      setProblem(res.data.applied ? null : res.data.message);
      setOpen(false);
      onSwitched();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : 'le levier est injoignable');
    }
  };

  if (open) {
    return (
      <OverrideForm
        title={formTitle}
        confirmLabel={restrictLabel}
        onCancel={() => setOpen(false)}
        onConfirm={(actor, reason) => send(true, actor, reason)}
      />
    );
  }

  return (
    <div className="text-right">
      <Button
        size="sm"
        variant={restricted ? 'outline' : 'ghost'}
        onClick={() => (restricted ? void send(false, '', '') : setOpen(true))}
      >
        {restricted ? liftLabel : restrictLabel}
      </Button>
      {problem ? <p className="mt-1 max-w-xs text-xs text-error">{problem}</p> : null}
    </div>
  );
};

/**
 * Le levier par outil.
 *
 * Il coupe, il ne supprime pas : un client garde en cache la liste d'outils de son `initialize`,
 * donc un outil qui disparaît en cours de session est un outil que le modèle continue d'appeler
 * sans rien pouvoir lire. Coupé, il refuse avec `-32044` en nommant l'opérateur et sa raison.
 */
const ToolSwitch: FC<{ tool: string; off: boolean; onSwitched: () => void }> = ({
  tool, off, onSwitched,
}) => (
  <OverrideSwitch
    endpoint={`/api/mcp/toggle/tool/${tool}`}
    restricted={off}
    formTitle={`Couper ${tool}`}
    restrictLabel="Couper"
    liftLabel="Rétablir"
    onSwitched={onSwitched}
  />
);

/**
 * Le jeton d'approbation, frappé pour un appel d'un outil.
 *
 * Sans lui l'outil est inappelable : il est nommé dans `approval-required-tools`, donc chaque
 * appel est refusé `-32042` tant qu'aucun humain n'a approuvé. Le badge le disait déjà et rien ne
 * permettait de le débloquer — un contrôle sans moyen de s'en servir.
 *
 * Le jeton n'est montré qu'une fois et n'est relu nulle part : c'est un porteur, et un endpoint
 * capable de le redonner ferait d'une approbation à usage unique une permission permanente pour
 * quiconque atteint cette application.
 */
const ApprovalMint: FC<{ tool: string }> = ({ tool }) => {
  const [result, setResult] = useState<McpApprovalResult | null>(null);

  const mint = async () => {
    try {
      const res = await axios.post<McpApprovalResult>(`/api/mcp/approve/${tool}`, {
        enable: true, actor: '', reason: '',
      }, { validateStatus: () => true });
      setResult(res.data);
    } catch (e) {
      setResult({
        token: null, tool: null, expiresInMinutes: 0,
        message: e instanceof Error ? e.message : 'la frappe est injoignable',
      });
    }
  };

  if (result?.token) {
    return (
      <div className="max-w-xs space-y-1 text-right">
        <p className="text-[11px] text-on-surface-variant">
          Un appel, {result.expiresInMinutes} min. Montré une seule fois.
        </p>
        <code className="block break-all rounded bg-surface-container-high p-1 text-[10px]">
          {result.token}
        </code>
        <Button size="sm" variant="ghost" onClick={() => void copyText(result.token ?? '')}>
          Copier
        </Button>
      </div>
    );
  }

  return (
    <div className="text-right">
      <Button size="sm" variant="outline" onClick={() => void mint()}>
        Approuver
      </Button>
      {result?.message ? (
        <p className="mt-1 max-w-xs text-xs text-error">{result.message}</p>
      ) : null}
    </div>
  );
};

const StatusBanner: FC<{
  status: McpStatusView;
  locked: boolean;
  onSwitched: () => void;
}> = ({ status, locked, onSwitched }) => (
  <Card className="p-4">
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
      <Badge tone={status.enabled ? 'success' : 'neutral'} dot>
        {status.enabled ? "À l'écoute" : 'Désactivé'}
      </Badge>
      {status.writeSurfaceOpen ? (
        <Tooltip content={`Outils mutants exposés : ${status.mutatingToolsExposed.join(', ')}`}>
          <Badge tone="warning">ÉCRITURE ACTIVE</Badge>
        </Tooltip>
      ) : (
        <Badge tone="success">LECTURE SEULE</Badge>
      )}
      <Fact label="Transports" value={status.transports.join(' + ') || '—'} />
      <Fact
        label="Endpoint"
        value={status.endpoint ?? 'non lié'}
        hint={status.endpoint ? "adresse locale : substituez l'hôte que vos agents utilisent" : undefined}
      />
      <Fact label="Authentification" value={status.authentication} />
      <Fact label="Portée topics" value={status.topicScope.join(', ')} />
      <Fact label="Portée groupes" value={status.groupScope.join(', ')} />
      {status.enabled ? (
        /* Le levier est à côté de l'état qu'il change. Il ne peut que restreindre : la surface
           d'écriture se décide à l'enregistrement des beans, donc un bouton qui prétendrait
           l'ouvrir mentirait — le serveur répond alors « rien à lever » et le dit. */
        <OverrideSwitch
          endpoint="/api/mcp/toggle/readonly"
          restricted={locked}
          formTitle="Verrouiller la surface en lecture seule"
          restrictLabel="Verrouiller"
          liftLabel="Lever le verrou"
          onSwitched={onSwitched}
        />
      ) : null}
    </div>
  </Card>
);

const Fact: FC<{ label: string; value: string; hint?: string }> = ({ label, value, hint }) => (
  <div>
    <div className="text-xs uppercase tracking-wide text-on-surface-variant">{label}</div>
    <div className="font-mono text-xs">{value}</div>
    {hint ? <div className="text-[11px] text-on-surface-variant">{hint}</div> : null}
  </div>
);

/** Un bandeau qui dit sur quoi les chiffres reposent. Absent quand il n'y a rien à dire. */
const WindowNotice: FC<{ view: McpCatalogView | McpStatsView }> = ({ view }) => {
  const caveat = windowCaveat(view.observedWindow);
  const history = historyNotice(view.observedWindow);
  if (!caveat && !history) return null;
  return (
    <div className="rounded-md border border-warning/30 bg-warning/5 p-3 text-xs text-on-surface-variant space-y-1">
      {caveat ? <p>⚠ {caveat}</p> : null}
      {history ? <p>{history}</p> : null}
    </div>
  );
};

const CatalogTab: FC<{
  catalog: McpCatalogView | null;
  endpoint: string | null;
  tryItEnabled: boolean;
  overrides: McpOverrideView[];
  onSwitched: () => void;
}> = ({ catalog, endpoint, tryItEnabled, overrides, onSwitched }) => {
  const [config, setConfig] = useState<McpClientConfig | null>(null);
  const [client, setClient] = useState('claude-code');
  /* Les outils coupés à chaud, tirés du bandeau plutôt que d'un second appel : la même vérité,
     lue une fois. */
  const switchedOff = useMemo(
    () => new Set(overrides.filter((o) => o.kind === 'TOOL').map((o) => o.target ?? '')),
    [overrides],
  );
  const [trying, setTrying] = useState<McpToolRow | null>(null);
  const [query, setQuery] = useState('');
  /* Replié par défaut : ces descriptions sont écrites pour un modèle, qui les lit en entier.
     L'opérateur balaie d'abord des noms et des états. */
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    void axios
      .get<McpClientConfig>(`/api/mcp/catalog/client-config?client=${client}`)
      .then((res) => setConfig(res.data))
      .catch(() => setConfig(null));
  }, [client, endpoint]);

  if (!catalog) return null;

  return (
    <div className="space-y-4">
      <WindowNotice view={catalog} />

      <Field label="Filtrer les outils" className="w-72">
        {(field) => (
          <Input
            {...field}
            value={query}
            placeholder="nom, catégorie ou description"
            onChange={(e) => setQuery(e.target.value)}
          />
        )}
      </Field>

      <Card className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-on-surface-variant">
            <tr>
              <th className="p-3">Outil</th>
              <th className="p-3">Catégorie</th>
              <th className="p-3">État</th>
              <th className="p-3">Plafonds</th>
              <th className="p-3 text-right">Appels</th>
              <th className="p-3 text-right">p95</th>
              <th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {sortTools(filterTools(catalog.tools, query)).map((tool) => (
              <tr key={tool.name} className="border-t border-outline-variant align-top">
                <td className="p-3">
                  <div className="font-mono text-xs">{tool.name}</div>
                  {/* La description de l'agent, à l'identique : la question de l'opérateur est
                      « qu'a-t-on dit au modèle ? », et une reformulation répond à une autre. Mais
                      elle est repliée sur sa première phrase — vingt lignes par outil, quinze
                      outils, et la table cesse d'être lisible. */}
                  <p className="mt-1 max-w-xl whitespace-pre-line text-xs text-on-surface-variant">
                    {expanded.has(tool.name) ? tool.description : firstSentence(tool.description)}
                  </p>
                  {hasMoreThanFirstSentence(tool.description) ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        setExpanded((current) => {
                          const next = new Set(current);
                          if (!next.delete(tool.name)) next.add(tool.name);
                          return next;
                        })}
                    >
                      {expanded.has(tool.name) ? 'Replier' : 'Tout lire'}
                    </Button>
                  ) : null}
                </td>
                <td className="p-3 text-xs">{tool.category}</td>
                <td className="p-3">
                  <VisibilityBadge tool={tool} />
                </td>
                <td className="p-3 text-xs text-on-surface-variant">
                  {tool.defaultBudgetMs !== null ? `${formatDuration(tool.defaultBudgetMs)} par défaut · ` : ''}
                  {tool.hardMaxRecords !== null ? `${tool.hardMaxRecords} enreg. · ` : ''}
                  {tool.hardMaxRows !== null ? `${formatNumber(tool.hardMaxRows)} lignes · ` : ''}
                  {formatBytes(tool.hardMaxBytes)}
                </td>
                <td className="p-3 text-right text-xs">
                  {formatNumber(tool.calls)}
                  {tool.denied > 0 ? (
                    <span className="text-error"> dont {formatNumber(tool.denied)} refusés</span>
                  ) : null}
                </td>
                <td className="p-3 text-right text-xs">
                  <MeasuredValue value={tool.p95Ms} unit="ms" />
                </td>
                <td className="p-3">
                  <div className="flex justify-end gap-2">
                    {/* Masqué plutôt que désactivé quand le serveur refuserait : un bouton qui a
                        l'air disponible et répond 403 apprend à se méfier de l'écran. */}
                    {tool.visibility.state !== 'HIDDEN' && tryItEnabled ? (
                      <Button size="sm" variant="outline" onClick={() => setTrying(tool)}>
                        Essayer
                      </Button>
                    ) : null}
                    {tool.visibility.state === 'EXPOSED_WITH_APPROVAL' ? (
                      <ApprovalMint tool={tool.name} />
                    ) : null}
                    {tool.visibility.state !== 'HIDDEN' ? (
                      <ToolSwitch
                        tool={tool.name}
                        off={switchedOff.has(tool.name)}
                        onSwitched={onSwitched}
                      />
                    ) : null}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card className="p-4 space-y-3">
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-medium">Configuration client</h3>
          <Field label="Client" className="w-52">
            {(field) => (
              <Select {...field} value={client} onChange={(e) => setClient(e.target.value)}>
                <option value="claude-code">Claude Code</option>
                <option value="claude-desktop">Claude Desktop</option>
                <option value="generic">Générique (JSON)</option>
              </Select>
            )}
          </Field>
        </div>
        {config ? (
          <>
            <p className="text-xs text-on-surface-variant">
              À placer dans : <code>{config.format}</code>. Aucun jeton n'est inséré ici
              {config.tokenHint ? ` — ${config.tokenHint}` : '.'}
            </p>
            <pre className="overflow-x-auto rounded bg-surface-container-high p-3 text-xs">{config.snippet}</pre>
            <Button size="sm" variant="outline" onClick={() => void copyText(config.snippet)}>
              Copier
            </Button>
          </>
        ) : null}
      </Card>

      {!tryItEnabled ? (
        <p className="text-xs text-on-surface-variant">
          Exécuter un outil depuis la console est désactivé (<code>explorer.mcp.console.allow-try-it</code>).
          C'est le défaut : cet endpoint exécute le vrai outil sur une URL applicative qui ne porte
          aucune authentification, donc l'ouvrir contournerait ce qui protège l'endpoint MCP lui-même.
        </p>
      ) : null}

      {trying ? <TryPanel tool={trying} onClose={() => setTrying(null)} /> : null}
    </div>
  );
};

/**
 * L'état d'un outil, motif compris.
 *
 * Montrer les outils masqués **avec le motif** est le point de la table : sans cela, un opérateur
 * qui cherche pourquoi son agent ne voit pas `kex_produce_message` n'a aucune piste et va lire le
 * YAML. La ligne répond avant.
 */
const VisibilityBadge: FC<{ tool: McpToolRow }> = ({ tool }) => {
  if (tool.visibility.state === 'EXPOSED') return <Badge tone="success">Exposé</Badge>;
  const tone = tool.visibility.state === 'HIDDEN' ? 'neutral' : 'warning';
  const label = tool.visibility.state === 'HIDDEN' ? 'Masqué' : 'Approbation';
  return (
    <Tooltip content={tool.visibility.reason ?? ''}>
      <Badge tone={tone}>
        {label} ⓘ
      </Badge>
    </Tooltip>
  );
};

/**
 * Exécute un outil depuis la console, par le même chemin qu'un agent.
 *
 * La réponse brute est affichée, enveloppe `coverage` comprise : tout l'intérêt du panneau est de
 * montrer ce qu'un modèle recevrait réellement, et un résumé embelli masquerait précisément ce
 * qu'on vient inspecter.
 */
const TryPanel: FC<{ tool: McpToolRow; onClose: () => void }> = ({ tool, onClose }) => {
  const [args, setArgs] = useState('{}');
  const [result, setResult] = useState<McpTryResult | null>(null);
  const [invalid, setInvalid] = useState<string | null>(null);

  const run = async () => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(args || '{}');
    } catch {
      setInvalid('Ce ne sont pas des arguments JSON valides.');
      return;
    }
    setInvalid(null);
    const res = await axios.post<McpTryResult>(`/api/mcp/try/${tool.name}`, parsed, {
      validateStatus: () => true,
    });
    setResult(res.data);
  };

  return (
    <Card className="p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="font-mono text-sm">{tool.name}</h3>
        <Button size="sm" variant="ghost" onClick={onClose}>
          Fermer
        </Button>
      </div>
      <p className="text-xs text-on-surface-variant">
        L'appel passe par la même garde et le même chemin qu'un agent, et il est journalisé
        <code> origin: CONSOLE</code> : il compte dans la charge, il ne se confond pas avec un agent.
      </p>
      <Field label="Arguments (JSON)" error={invalid ?? undefined}>
        {(field) => (
          <Textarea
            {...field}
            className="font-mono text-xs"
            rows={4}
            value={args}
            onChange={(e) => setArgs(e.target.value)}
          />
        )}
      </Field>
      <Button size="sm" onClick={() => void run()}>
        Exécuter
      </Button>
      {result ? (
        <pre className="max-h-80 overflow-auto rounded bg-surface-container-high p-3 text-xs">
          {result.message ? `${result.message}\n\n` : ''}
          {JSON.stringify(result.structuredContent, null, 2)}
        </pre>
      ) : null}
    </Card>
  );
};

/**
 * Le rejeu depuis le topic d'audit.
 *
 * `historyNotice` disait déjà à l'opérateur que l'histoire est ailleurs, et rien ne l'y menait.
 * Le flux vif répond à « que se passe-t-il » ; ceci répond à « que s'est-il passé », qui est par
 * nature une question posée après coup.
 *
 * La phrase de couverture compte autant que les lignes : un balayage borné qui revient vide peut
 * l'être parce que rien ne s'est passé ou parce qu'il n'est pas allé jusque-là, et l'écran ne doit
 * pas laisser choisir la lecture rassurante.
 */
const ReplayPanel: FC = () => {
  const [open, setOpen] = useState(false);
  const [hours, setHours] = useState('24');
  const [replay, setReplay] = useState<McpReplay | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const run = async () => {
    setBusy(true);
    setProblem(null);
    try {
      const to = new Date();
      const from = new Date(to.getTime() - Number(hours) * 3_600_000);
      const query = new URLSearchParams({ from: from.toISOString(), to: to.toISOString() });
      const res = await axios.get<McpReplay>(`/api/mcp/calls/replay?${query}`);
      setReplay(res.data);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : "le topic d'audit est injoignable");
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <Button size="sm" variant="outline" onClick={() => setOpen(true)}>
        Rejouer depuis le topic d'audit
      </Button>
    );
  }

  return (
    <Card className="space-y-3 p-4">
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Dernières (heures)" className="w-36">
          {(field) => (
            <Input {...field} type="number" min={1} value={hours} onChange={(e) => setHours(e.target.value)} />
          )}
        </Field>
        <Button size="sm" disabled={busy} onClick={() => void run()}>
          Rejouer
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          Fermer
        </Button>
      </div>

      {problem ? <p className="text-xs text-error">{problem}</p> : null}

      {replay ? (
        <div className="space-y-2">
          <p className="text-xs text-on-surface-variant">{replaySummary(replay)}</p>
          {replay.warnings.map((warning) => (
            <p key={warning} className="text-xs text-warning">⚠ {warning}</p>
          ))}
          {replay.calls.length > 0 ? (
            <pre className="max-h-80 overflow-auto rounded bg-surface-container-high p-3 text-[11px]">
              {replay.calls.map((call) => JSON.stringify(call)).join('\n')}
            </pre>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
};

/**
 * Ce qu'une ligne du flux ne montre pas et qu'on vient y chercher.
 *
 * `redactedParams`, `correlationId` et `stopReason` n'existaient que dans le CSV : il fallait
 * exporter un fichier pour savoir pourquoi un appel s'était mal passé, ce qui est le geste qu'on
 * fait en dernier et pas en premier. Ils sont ici, là où on regarde.
 *
 * Les arguments sont ceux **déjà rédigés** par la garde — c'est ce que le serveur a gardé, et
 * l'écran ne peut pas montrer autre chose : le secret n'est jamais entré dans l'anneau. Le jeton
 * d'approbation, lui, en a été retiré et non masqué, donc son absence est normale.
 */
const CallDetail: FC<{ call: McpCallView }> = ({ call }) => {
  const why = explainDenial(call.jsonRpcErrorCode, call.deniedByGuard);
  const params = Object.entries(call.redactedParams ?? {});
  return (
    <div className="grid gap-3 text-xs sm:grid-cols-2">
      <div className="space-y-1">
        <Fact label="Corrélation" value={call.correlationId} />
        <Fact label="Client" value={call.clientInfo ?? 'non déclaré'} />
        <Fact
          label="Arrêt"
          value={call.stopReason ?? "cet outil ne porte pas d'enveloppe"}
          hint={
            call.stopReason && call.stopReason !== 'EXHAUSTED'
              ? "un résultat vide veut dire « pas trouvé dans ce qui a été lu », pas « n'existe pas »"
              : undefined
          }
        />
        <Fact label="Sortie" value={call.outputBytes.measured
          ? formatBytes(call.outputBytes.value ?? 0)
          : (call.outputBytes.reason ?? 'non mesurée')} />
      </div>
      <div className="space-y-1">
        <div className="text-xs uppercase tracking-wide text-on-surface-variant">
          Arguments (rédigés par la garde)
        </div>
        {params.length === 0 ? (
          <p className="text-on-surface-variant">aucun argument</p>
        ) : (
          <pre className="max-h-40 overflow-auto rounded bg-surface-container-high p-2 text-[11px]">
            {params.map(([key, value]) => `${key}: ${JSON.stringify(value)}`).join('\n')}
          </pre>
        )}
        {why ? (
          <p className="text-warning">
            {call.jsonRpcErrorCode} — {why.sentence}
            {why.setting ? ` (${why.setting})` : ''}
          </p>
        ) : null}
      </div>
    </div>
  );
};

interface SupervisionProps {
  stats: McpStatsView | null;
  calls: McpCallView[];
  clients: McpClientRow[];
  filters: FeedFilters;
  window: McpWindow;
  paused: boolean;
  onPause: (paused: boolean) => void;
  onFilters: (filters: FeedFilters) => void;
  onWindow: (window: McpWindow) => void;
  overrides: McpOverrideView[];
  onSwitched: () => void;
}

const SupervisionTab: FC<SupervisionProps> = ({
  stats, calls, clients, filters, window: win, paused, onPause, onFilters, onWindow,
  overrides, onSwitched,
}) => {
  const quarantined = useMemo(
    () => new Set(overrides.filter((o) => o.kind === 'QUARANTINE').map((o) => o.target ?? '')),
    [overrides],
  );
  /* L'appel ouvert. Un seul à la fois : la question est « pourquoi celui-là », pas « comparons ». */
  const [openCall, setOpenCall] = useState<string | null>(null);
  if (!stats) return null;
  const share = deniedShare(stats);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Field label="Fenêtre" className="w-32">
          {(field) => (
            <Select {...field} value={win} onChange={(e) => onWindow(e.target.value as McpWindow)}>
              {WINDOWS.map((w) => (
                <option key={w} value={w}>{w}</option>
              ))}
            </Select>
          )}
        </Field>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            const blob = new Blob([callsToCsv(calls)], { type: 'text/csv' });
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = 'mcp-calls.csv';
            link.click();
            URL.revokeObjectURL(url);
          }}
        >
          Exporter en CSV
        </Button>
      </div>

      <ReplayPanel />

      <WindowNotice view={stats} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {/* Les refus sur la même carte que les succès : relégués ailleurs, un contrôle qui bloque
            deux cents fois par heure reste invisible jusqu'à ce qu'on aille le chercher. */}
        {/* Trois états, trois nombres. `errors` était calculé et affiché nulle part : un appel
            tombé pour une raison qu'aucune garde n'a choisie — un broker absent — disparaissait
            entre « appels » et « refusés », alors que c'est le seul des trois qui ne se règle pas
            dans le YAML. */}
        <Stat
          label="Appels"
          value={formatNumber(stats.calls)}
          hint={
            share === null
              ? 'aucun appel dans cette fenêtre'
              : `dont ${formatNumber(stats.denied)} refusés (${share.toFixed(1)} %) et ${formatNumber(stats.errors)} en erreur`
          }
          tone={stats.errors > 0 ? 'error' : stats.denied > 0 ? 'warning' : 'none'}
        />
        <Stat
          label="Latence"
          value={<MeasuredValue value={stats.p95Ms} unit="ms" />}
          hint={
            <>
              p50 <MeasuredValue value={stats.p50Ms} unit="ms" /> · max{' '}
              <MeasuredValue value={stats.maxMs} unit="ms" />
              {stats.slowestTool ? ` · le plus lent : ${stats.slowestTool}` : ''}
            </>
          }
        />
        <Stat
          label="Charge induite"
          value={<MeasuredValue value={stats.recordsScanned} unit="enreg." />}
          hint={<>sortie : <MeasuredValue value={stats.outputBytes} format={formatBytes} /></>}
        />
        <Stat
          label="Clients"
          value={formatNumber(stats.activeIdentities)}
          hint="identités distinctes — reconstruites côté écran, le transport HTTP est sans état"
        />
      </div>

      {stats.topTools.length > 0 ? (
        <Card className="p-4">
          <h3 className="mb-2 text-sm font-medium">Outils les plus appelés</h3>
          <ul className="space-y-1 text-xs">
            {stats.topTools.map((tool) => (
              <li key={tool.tool}>
                <span className="font-mono">{tool.tool}</span> — {formatNumber(tool.calls)} appels
              </li>
            ))}
          </ul>
        </Card>
      ) : null}

      {stats.denialsByCode.length > 0 ? (
        <Card className="p-4">
          <h3 className="mb-2 text-sm font-medium">Refusé, et pourquoi</h3>
          <ul className="space-y-1 text-xs">
            {stats.denialsByCode.map((denial) => {
              // Le code vient de KIP-1318 : un agent entraîné dessus le lit, un opérateur non.
              const why = explainDenial(denial.jsonRpcErrorCode, denial.guard);
              return (
                <li key={`${denial.jsonRpcErrorCode}-${denial.guard}`}>
                  <span className="font-mono">{denial.jsonRpcErrorCode}</span> · garde{' '}
                  <span className="font-mono">{denial.guard}</span> —{' '}
                  {formatNumber(denial.count)} fois
                  {why ? (
                    <>
                      <div className="text-on-surface-variant">{why.sentence}</div>
                      {why.setting ? (
                        <div className="text-on-surface-variant">
                          se règle dans <span className="font-mono">{why.setting}</span>
                        </div>
                      ) : null}
                    </>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </Card>
      ) : null}

      <Card className="p-3">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Outil" className="w-48">
            {(field) => (
              <Input
                {...field}
                value={filters.tool}
                onChange={(e) => onFilters({ ...filters, tool: e.target.value })}
              />
            )}
          </Field>
          <Field label="Verdict" className="w-36">
            {(field) => (
            <Select {...field} value={filters.outcome} onChange={(e) => onFilters({ ...filters, outcome: e.target.value })}>
              <option value="">tous</option>
              <option value="OK">OK</option>
              <option value="DENIED">refusés</option>
              <option value="ERROR">erreurs</option>
            </Select>
            )}
          </Field>
          <Field label="Origine" className="w-36">
            {(field) => (
            <Select {...field} value={filters.origin} onChange={(e) => onFilters({ ...filters, origin: e.target.value })}>
              <option value="">toutes</option>
              <option value="AGENT">agent</option>
              <option value="CONSOLE">console</option>
              <option value="PROMPT">prompt</option>
            </Select>
            )}
          </Field>
          <Button size="sm" variant="ghost" onClick={() => onFilters({ ...EMPTY_FILTERS })}>
            Réinitialiser
          </Button>
        </div>
      </Card>

      <Card
        className="overflow-x-auto"
        onMouseEnter={() => onPause(true)}
        onMouseLeave={() => onPause(false)}
      >
        {paused ? (
          <p className="px-3 pt-2 text-[11px] text-on-surface-variant">
            Rafraîchissement en pause pendant la lecture.
          </p>
        ) : null}
        {calls.length === 0 ? (
          <EmptyState
            icon="inbox"
            title="Aucun appel dans cette fenêtre"
            description="Ce n'est pas la même chose qu'un serveur inactif : le serveur écoute, aucun client ne l'a appelé sur cette période."
          />
        ) : (
          <table className="w-full text-xs">
            <thead className="text-left uppercase text-on-surface-variant">
              <tr>
                <th className="p-2">Horodatage</th>
                <th className="p-2">Origine</th>
                <th className="p-2">Identité</th>
                <th className="p-2">Outil</th>
                <th className="p-2">Verdict</th>
                <th className="p-2 text-right">Durée</th>
                <th className="p-2 text-right">Lu</th>
                <th className="p-2 text-center">Couverture</th>
              </tr>
            </thead>
            <tbody>
              {calls.map((call) => {
                const coverage = coverageLabel(call);
                const open = openCall === call.correlationId;
                return (
                  <Fragment key={call.correlationId}>
                    <tr
                      className="cursor-pointer border-t border-outline-variant hover:bg-surface-container"
                      onClick={() => setOpenCall(open ? null : call.correlationId)}
                    >
                      <td className="p-2 font-mono">
                        {new Date(call.startedAt).toLocaleTimeString('fr-FR')}
                      </td>
                      <td className="p-2">{call.origin.toLowerCase()}</td>
                      <td className="p-2 font-mono">{call.identity ?? '—'}</td>
                      <td className="p-2 font-mono">{call.tool}</td>
                      <td className="p-2">
                        <Badge tone={call.outcome === 'OK' ? 'success' : call.outcome === 'DENIED' ? 'warning' : 'error'}>
                          {outcomeLabel(call)}
                        </Badge>
                      </td>
                      <td className="p-2 text-right">{formatDuration(call.durationMs)}</td>
                      <td className="p-2 text-right">
                        <MeasuredValue value={call.recordsScanned} />
                      </td>
                      <td className="p-2 text-center">
                        <Tooltip content={coverage.title}>
                          <span aria-label={coverage.title}>{coverage.icon}</span>
                        </Tooltip>
                      </td>
                    </tr>
                    {open ? (
                      <tr className="bg-surface-container-low">
                        <td colSpan={8} className="p-3">
                          <CallDetail call={call} />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>

      {clients.length > 0 ? (
        <Card className="p-4">
          <h3 className="mb-2 text-sm font-medium">Clients</h3>
          <ul className="space-y-1 text-xs">
            {clients.map((client) => (
              <li key={client.identity} className="flex items-start justify-between gap-3">
                <span>
                  <span className="font-mono">{client.identity}</span>
                  {client.clientInfo ? ` — ${client.clientInfo}` : ''} ·{' '}
                  {formatNumber(client.calls)} appels
                </span>
                {/* Le levier le plus utile en incident : « cet agent boucle » se règle en coupant
                    l'identité, pas l'outil — il en appellerait un autre. */}
                <OverrideSwitch
                  endpoint={`/api/mcp/quarantine/${encodeURIComponent(client.identity)}`}
                  restricted={quarantined.has(client.identity)}
                  formTitle={`Mettre ${client.identity} en quarantaine`}
                  restrictLabel="Quarantaine"
                  liftLabel="Relâcher"
                  onSwitched={onSwitched}
                />
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </div>
  );
};

export default Mcp;
