// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La logique pure de l'assistant SQL et métriques (`EMBEDDED-LLM-SCOPE.md`).
 *
 * Trois règles, celles du reste de l'application :
 *
 *  1. **Rien ne s'exécute ni ne s'enregistre tout seul.** Une proposition ouvre un onglet ou
 *     pré-remplit un formulaire ; Run et Save restent des gestes.
 *  2. **Une proposition dit si le planificateur l'a acceptée.** `proposalVerdict` produit le statut
 *     qui l'accompagne toujours ; il n'existe pas d'affichage d'un SQL généré sans lui.
 *  3. **La réponse dit ce qu'elle a vu** : les tables décrites au modèle et, par leur nom, celles
 *     laissées de côté — « je ne l'ai pas vue » n'est pas « elle n'existe pas ».
 *
 * Nommé pour ce qu'il contient, pas d'après une page : aucune collision de casse possible avec un
 * composant (voir CLAUDE.md, frontend).
 */

import type { AssistantMetricResponse, AssistantSqlResponse, AssistantStatus, MetricConfig } from '../api/types';
import type { BadgeTone } from '../components/ui';

/**
 * Le délai côté navigateur. Le serveur borne chaque appel au modèle
 * (`explorer.assistant.request-timeout-seconds`, 120 s par défaut) et peut en faire deux — la
 * réponse puis une réparation — plus une planification à blanc : 300 s couvrent ce cas sans
 * transformer en attente infinie un serveur qui ne répond plus.
 */
export const ASSISTANT_REQUEST_TIMEOUT_MS = 300_000;

/** Ce que l'interface peut faire de l'état de l'assistant. */
export interface AssistantAvailability {
  /** Les entrées de l'assistant sont affichées. */
  available: boolean;
  /** Pourquoi elles ne le sont pas — rendu par la page d'aide, pas par un bouton grisé muet. */
  reason: string | null;
}

export function assistantAvailability(status: AssistantStatus | null): AssistantAvailability {
  if (!status) return { available: false, reason: null };
  if (!status.enabled) {
    return { available: false, reason: status.problem ?? 'The assistant is off.' };
  }
  if (status.problem) return { available: false, reason: status.problem };
  return { available: true, reason: null };
}

/**
 * Ce qui part, dit là où l'on tape — la même règle que le bandeau des Réglages : l'affirmation
 * « reste sur cette machine » n'est faite que sur une adresse de bouclage.
 */
export function privacyNote(status: AssistantStatus | null): string | null {
  if (!status || status.localDeployment) return null;
  const who = status.provider ? `${status.provider}${status.model ? ` (${status.model})` : ''}` : 'the model';
  return `Table and column names are sent to ${who}, outside this host. Message values are never sent.`;
}

/** Ce que la page d'aide dit de l'assistant, d'après l'état que le serveur rapporte. */
export interface AssistantHelp {
  tone: BadgeTone;
  label: string;
  detail: string;
  /** Le geste qui l'allume, quand il est éteint — dit où l'on tape, pas seulement que c'est possible. */
  howToEnable: string | null;
}

export const HOW_TO_ENABLE_ASSISTANT =
  'Set EXPLORER_ASSISTANT_ENABLED=true (explorer.assistant.enabled), or start the local stack: '
  + 'docker compose -f docker-compose.yml -f compose/ollama.yml up -d. It uses claude.* unless '
  + 'EXPLORER_ASSISTANT_PROVIDER / _BASE_URL / _MODEL name another model.';

/**
 * `null` tant que l'état n'est pas connu — ou que le serveur n'en rapporte aucun : une page d'aide
 * qui affirmerait « éteint » d'un serveur qui ne connaît pas l'assistant dirait une chose fausse.
 */
export function describeAssistantHelp(status: AssistantStatus | null): AssistantHelp | null {
  if (!status) return null;
  const model = [status.provider, status.model].filter(Boolean).join(' · ');
  if (!status.enabled) {
    return {
      tone: 'neutral', label: 'Off on this server',
      detail: 'The editor shows no assistant entry until it is turned on.',
      howToEnable: HOW_TO_ENABLE_ASSISTANT,
    };
  }
  if (status.problem) {
    return {
      tone: 'warning', label: 'On, but it cannot answer',
      detail: status.problem, howToEnable: null,
    };
  }
  return {
    tone: 'success', label: 'On',
    detail: `${model ? `${model}. ` : ''}${privacyNote(status) ?? 'It runs on this host: nothing is sent elsewhere.'}`,
    howToEnable: null,
  };
}

export interface ProposalVerdict {
  tone: BadgeTone;
  label: string;
  /** La phrase complète, quand le libellé ne suffit pas. */
  detail: string | null;
}

/** Le statut qui accompagne toujours une proposition. */
export function proposalVerdict(response: AssistantSqlResponse): ProposalVerdict {
  switch (response.status) {
    case 'OK':
      if (!response.sql) {
        return { tone: 'neutral', label: 'No statement', detail: response.missing };
      }
      if (response.validated) {
        return {
          tone: 'success',
          label: 'Accepted by the planner',
          detail: response.repairRounds > 0
            ? `Corrected after ${plural(response.repairRounds, 'refusal')} from the planner.`
            : null,
        };
      }
      return {
        tone: 'warning',
        label: 'Not validated',
        detail: response.validationError ?? 'The planner did not accept this statement.',
      };
    case 'NEEDS_CLARIFICATION':
      return { tone: 'neutral', label: 'Needs clarification', detail: response.missing };
    case 'OUT_OF_SCOPE':
      return {
        tone: 'neutral',
        label: 'Out of scope',
        detail: response.validationError ?? response.missing
          ?? 'The request is not a query over the tables described.',
      };
    case 'BUSY':
      return { tone: 'warning', label: 'Busy', detail: response.error };
    case 'DISABLED':
      return { tone: 'neutral', label: 'Assistant off', detail: response.error };
    default:
      return { tone: 'error', label: 'Failed', detail: response.error };
  }
}

/** Les tables lues, puis celles laissées de côté — par leur nom. */
export function describeScope(response: AssistantSqlResponse): string | null {
  const parts: string[] = [];
  if (response.tablesConsidered.length) {
    parts.push(`Shown to the model: ${response.tablesConsidered.join(', ')}.`);
  }
  if (response.tablesOmitted.length) {
    parts.push(`Left out: ${response.tablesOmitted.join(', ')}.`);
  }
  return parts.length ? parts.join(' ') : null;
}

/** « gemma4:e2b · 8.4 s » — ce qui a répondu et combien de temps cela a pris. */
export function describeProvenance(response: AssistantSqlResponse): string | null {
  const bits: string[] = [];
  if (response.model) bits.push(response.model);
  if (response.durationMs > 0) bits.push(`${(response.durationMs / 1000).toFixed(1)} s`);
  if (response.usage?.costUsd != null) bits.push(`$${response.usage.costUsd.toFixed(4)}`);
  return bits.length ? bits.join(' · ') : null;
}

/**
 * Une erreur que l'assistant peut aider à corriger : celle d'une instruction, pas celle d'un
 * moteur occupé, d'un délai dépassé ou d'un broker injoignable — renvoyer celles-là au modèle lui
 * ferait « corriger » une requête juste.
 */
const NOT_THE_STATEMENTS_FAULT = /runtime was busy|timed? ?out|timeout|cannot reach|connection refused|broker|circuit breaker|planner is unavailable/i;

export function isFixableError(raw: string | null | undefined, sql: string | null | undefined): boolean {
  if (!raw || !raw.trim() || !sql || !sql.trim()) return false;
  return !NOT_THE_STATEMENTS_FAULT.test(raw);
}

/** Le nom de l'onglet qu'ouvre une proposition. */
export function proposalTabName(mode: 'GENERATE' | 'FIX'): string {
  return mode === 'FIX' ? 'Assistant fix' : 'Assistant';
}

/**
 * Le texte de l'onglet : la proposition, précédée de ce qu'il faut savoir pour ne pas la lancer à
 * l'aveugle — en commentaire SQL, donc retiré avant tout contrôle de mot-clé et jamais exécuté.
 */
export function proposalTabSql(response: AssistantSqlResponse, question: string | null): string {
  if (!response.sql) return '';
  const header: string[] = [];
  if (question) header.push(`-- Asked: ${singleLine(question)}`);
  if (response.explanation) header.push(`-- ${singleLine(response.explanation)}`);
  header.push(response.validated
    ? '-- Accepted by the planner. Check that it answers the question before running it.'
    : `-- NOT validated: ${singleLine(response.validationError ?? 'the planner did not accept it')}`);
  return `${header.join('\n')}\n${response.sql}\n`;
}

/**
 * Ce qu'un brouillon de métrique change au formulaire — et rien d'autre.
 *
 * Le SQL brut, toujours : un brouillon n'est pas un modèle de métrique, et le laisser sur un modèle
 * réinterpréterait son SQL comme des paramètres. Les seuils ne sont pas touchés — le modèle n'a vu
 * aucune valeur — et ni le nom ni la description ne sont effacés quand il n'en propose pas.
 * `null` quand il n'y a pas d'instruction à reprendre.
 */
export function metricDraftPatch(draft: AssistantMetricResponse, rawSqlTemplate: string): Partial<MetricConfig> | null {
  const sql = draft.proposal.sql;
  if (draft.proposal.status !== 'OK' || !sql) return null;
  const patch: Partial<MetricConfig> = {
    templateType: rawSqlTemplate,
    templateParams: {},
    executionMode: 'SQL',
    type: draft.type,
    sql,
  };
  if (draft.name) patch.name = draft.name;
  if (draft.description) patch.description = draft.description;
  return patch;
}

/** Une réponse que le serveur n'a pas pu donner, rendue comme un état de l'assistant. */
export function failedResponse(error: string): AssistantSqlResponse {
  return {
    status: 'ERROR', sql: null, explanation: null, missing: null, validated: false,
    validationError: null, repairRounds: 0, tablesConsidered: [], tablesOmitted: [],
    model: null, usage: null, durationMs: 0, error,
  };
}

function singleLine(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n > 1 ? 's' : ''}`;
}
