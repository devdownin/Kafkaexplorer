// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import { Badge, Button, ErrorPanel, Select, Skeleton, Tooltip } from '../ui';
import TopicConsumersPanel from '../topic/TopicConsumersPanel';
import { describeApiError, type QueryErrorInfo } from '../../pages/queryError';
import type { TopicDetailResponse, TopicMessage } from '../../api/types';
import {
  describeSample, groupByField, reasonFields, type ReasonField,
} from '../../pages/deadLetterReasons';
import { metricDraftLink } from '../../pages/metricFromQueue';

const TIMEOUT_MS = 20000;

export interface QueueDetailPanelProps {
  topic: string;
  /** La source appariée, ou `null` : sans elle il n'y a pas de taux à surveiller. */
  source: string | null;
}

/**
 * Ce qu'une file dit d'elle-même quand on l'ouvre : de quoi elle est faite, et qui la vide.
 *
 * Les deux courbes de la ligne répondent « ça se remplit » et « à quel taux ». Restaient les deux
 * questions suivantes, qui sont celles sur lesquelles on agit :
 *
 * **De quoi ?** Un regroupement des derniers enregistrements par un champ — `failure_reason` dans
 * le corps, `exception` ou `original-topic` en en-tête, selon la convention du producteur. C'est ce
 * qui sépare un service en panne d'un lot de messages malformés, et l'écran ne le disait pas du
 * tout. Le champ est **proposé** et reste changeable : les conventions divergent, et un classement
 * qui tombe juste neuf fois sur dix ne doit pas fermer la dixième.
 *
 * **Par qui ?** `TopicConsumersPanel`, celui de l'explorateur de topics, monté tel quel. C'est la
 * question qui décide de tout : une file qui reçoit dix messages par heure et qu'un consommateur
 * draine est saine, la même sans membre assigné est une fuite. Il n'est pas réécrit ici parce qu'il
 * répond déjà exactement à ça — un groupe en retard, un groupe sans membre et un groupe qui ne lit
 * qu'une partie des partitions y sont trois états distincts.
 *
 * **Tout est chargé à l'ouverture de la ligne, jamais avec le tableau.** Les deux lectures coûtent
 * autre chose que les offsets des courbes : un balayage des groupes du cluster pour l'une, un
 * échantillon de la file pour l'autre. Les payer pour soixante lignes que personne ne regarde
 * serait exactement le reproche que ce dépôt fait à la colonne d'activité quand elle mesure le
 * cluster au lieu de l'écran.
 */
const QueueDetailPanel: FC<QueueDetailPanelProps> = ({ topic, source }) => {
  const [messages, setMessages] = useState<TopicMessage[] | null>(null);
  const [error, setError] = useState<QueryErrorInfo | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    // `latest-offset` : sur une file de rebut, ce qu'on veut voir est ce qui arrive, pas ce qui
    // est tombé le premier — un topic plus vieux que le plafond de lecture répondrait sinon avec
    // des échecs résolus depuis longtemps.
    axios.get<TopicDetailResponse>(`/api/topic/${encodeURIComponent(topic)}`, {
      params: { readMode: 'latest-offset' },
      signal: controller.signal,
      timeout: TIMEOUT_MS,
    })
      .then(response => { setMessages(response.data.samples ?? []); setError(null); })
      .catch(e => {
        if (axios.isCancel(e)) return;
        setError(describeApiError(e, 'Could not read this queue.'));
      });
    return () => controller.abort();
  }, [topic]);

  const fields = useMemo(() => reasonFields(messages ?? []), [messages]);
  const field: ReasonField | null = useMemo(
    () => fields.find(f => f.id === chosen) ?? fields[0] ?? null,
    [fields, chosen],
  );
  const groups = useMemo(
    () => (messages && field ? groupByField(messages, field) : []),
    [messages, field],
  );

  return (
    <div className="px-4 py-4 space-y-6 bg-surface-container-low/60">
      <section className="space-y-3">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-2">
            <h3 className="text-[13px] font-semibold text-on-surface">What is arriving</h3>
            {fields.length > 1 && (
              <Select
                aria-label="Group by"
                value={field?.id ?? ''}
                onChange={e => setChosen(e.target.value)}
                className="w-[13rem]"
              >
                {fields.map(f => (
                  <option key={f.id} value={f.id}>
                    {f.name}{f.origin === 'header' ? ' (header)' : ''}
                  </option>
                ))}
              </Select>
            )}
          </div>
          {/*
            * Le pont détection → alerte. Désactivé sans source appariée plutôt que caché : c'est
            * une conséquence de l'appariement, et une commande qui disparaît sans un mot se lit
            * comme une fonctionnalité absente.
            */}
          {source ? (
            <Link to={metricDraftLink(topic, source)}>
              <Button variant="secondary" icon="monitoring">Alert on this rate</Button>
            </Link>
          ) : (
            <Tooltip content="Alerting on the failure rate needs a source to divide by, and none is paired with this queue.">
              <span>
                <Button variant="secondary" icon="monitoring" disabled>Alert on this rate</Button>
              </span>
            </Tooltip>
          )}
        </div>

        {error ? (
          <ErrorPanel error={error} />
        ) : !messages ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <>
            <p className="text-[12px] text-on-surface-variant">{describeSample(messages, field)}</p>
            {groups.length > 0 && (
              <ul className="space-y-1.5">
                {groups.map(group => (
                  <li key={group.value} className="flex items-center gap-3">
                    <span className="w-16 shrink-0 text-[12px] tabular-nums text-on-surface-variant text-right">
                      {group.count} · {Math.round(group.percent)}%
                    </span>
                    {/* La barre est décorative : le compte et la part sont écrits à côté, donc
                        rien n'est réservé à qui la voit. */}
                    <span aria-hidden="true" className="h-1.5 w-32 shrink-0 rounded-full bg-surface-container-high overflow-hidden">
                      <span
                        className={`block h-full ${group.missing ? 'bg-outline-variant' : 'bg-error/70'}`}
                        style={{ width: `${Math.max(2, group.percent)}%` }}
                      />
                    </span>
                    <span className={`text-[12px] truncate ${group.missing ? 'text-outline italic' : 'text-on-surface'}`} title={group.value}>
                      {group.value}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>

      <section className="space-y-2">
        <div className="flex items-center gap-2">
          <h3 className="text-[13px] font-semibold text-on-surface">Who drains it</h3>
          <Badge tone="neutral">read on open</Badge>
        </div>
        <TopicConsumersPanel topic={topic} />
      </section>
    </div>
  );
};

export default QueueDetailPanel;
