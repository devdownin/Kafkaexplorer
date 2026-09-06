// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce que la page de supervision des files d'échec décide, hors de tout composant.
 *
 * Elle répond à une question que le tableau de bord pose mal : un topic de rebut n'est pas un
 * topic ordinaire dont on surveille la santé, c'est un topic dont **tout trafic est une mauvaise
 * nouvelle**. Le tableau de bord le range à côté des autres, trié par nom, avec une courbe qu'on
 * lit comme partout ailleurs — « ça produit, tout va bien ». Ici la lecture est inversée et c'est
 * la raison d'être de l'écran.
 *
 * Deux séries par topic, parce qu'aucune des deux ne suffit :
 *
 * 1. **Les arrivées** — combien de messages sont tombés dans cette file, bucket par bucket. C'est
 *    la mesure directe, et elle ne dit rien du contexte : `40/h` est une catastrophe sur un flux à
 *    50 messages par heure et un bruit de fond sur un flux à 500 000.
 * 2. **La part du trafic de la source** — les mêmes buckets rapportés à ceux du topic dont cette
 *    file recueille les échecs. C'est le taux d'échec, et c'est ce qui se compare d'un topic à
 *    l'autre et d'une semaine à l'autre, là où un compte absolu ne se compare à rien.
 *
 * Rien ici ne demande de nouvelle mesure au serveur : les deux séries sortent du même
 * `GET /api/dashboard/activity`, qui prend une liste de topics — la file et sa source sont
 * demandées ensemble, et le rapport se fait dans le navigateur.
 *
 * Les conventions de nommage ne sont pas redéfinies ici : `topicKinds.ts` les porte, pour la
 * raison qui y est écrite (quatre copies, c'est quatre occasions de diverger). Ce module ajoute
 * l'appariement, que ce fichier-là n'avait pas à connaître.
 */

import type { TopicActivity } from '../api/types';
import { deadLetterLabel, isDeadLetterTopic, isRetryTopic } from './topicKinds';
import { detectTrend } from './topicActivity';

/** Ce qu'un topic est pour cet écran. `RETRY` n'est pas une file d'échec, c'est une file d'attente. */
export type DeadLetterKind = 'DLQ' | 'DLT' | 'RETRY';

/**
 * Comment la source a été trouvée — ou pourquoi elle ne l'a pas été.
 *
 * Ce n'est pas un détail d'implémentation : `exact` est une déduction du nom, `prefix` est une
 * **inférence**, et l'écran les dit différemment. Le second cas est celui qu'une convention
 * `<domaine>.<flux>.<étape>` produit — `demo.orders.2.dlt` recueille les échecs de
 * `demo.orders.2.validated`, jamais d'un topic nommé `demo.orders.2`, qui n'existe pas. La règle
 * stricte seule n'appariait donc rien sur le jeu de démonstration que ce dépôt sème lui-même.
 */
export type PairingHow = 'exact' | 'prefix' | 'ambiguous' | 'none';

/** Le résultat de l'appariement, avec ce qu'il faut pour l'expliquer sans le refaire. */
export interface SourcePairing {
  /**
   * Le topic source, **vérifié contre le catalogue du cluster** et jamais deviné : sans lui la
   * seconde courbe n'existe pas, et une source inventée produirait un taux d'échec calculé contre
   * un dénominateur qui n'a jamais été mesuré.
   */
  source: string | null;
  how: PairingHow;
  /** Le nom exact cherché en premier — sinon une absence se lit comme un défaut de mesure. */
  tried: string | null;
  /** Les topics qui répondaient tous au même préfixe : c'est l'ambiguïté, énumérée. */
  alternatives: string[];
}

/** Une ligne de l'écran : la file, ce qu'elle est, et le topic dont elle recueille les échecs. */
export interface SupervisionTopic {
  topic: string;
  kind: DeadLetterKind;
  pairing: SourcePairing;
}

/** Un segment de nom de topic, avec sa position — l'appariement découpe la chaîne d'origine. */
interface Segment {
  text: string;
  start: number;
  end: number;
}

function segmentsOf(topic: string): Segment[] {
  const out: Segment[] = [];
  let start = 0;
  for (let i = 0; i <= topic.length; i++) {
    if (i === topic.length || topic[i] === '.' || topic[i] === '_' || topic[i] === '-') {
      out.push({ text: topic.slice(start, i), start, end: i });
      start = i + 1;
    }
  }
  return out;
}

/** Le marqueur de file morte, en suffixe — la même règle que `topicKinds`, appliquée au découpage. */
const DEAD_LETTER_TAIL = /[._-](dlt|dlq)$/i;

/**
 * Les noms sous lesquels la source de cette file pourrait exister, du plus précis au moins précis.
 *
 * Deux règles, et une seule chacune :
 *
 * - **Une file morte est un suffixe**, donc la source est ce qui la précède : `orders.DLQ` →
 *   `orders`. Si ce qui reste est lui-même un topic de reprise (`orders.retry.5m.DLQ`), ses
 *   propres candidats suivent — la chaîne réelle est `orders` → reprise → rebut, et apparier la
 *   file au maillon qui la précède immédiatement donne un taux d'échec plus juste que de
 *   l'apparier au tout début.
 * - **Une reprise se nomme dans les deux sens**, d'où deux cas et pas un balayage : le marqueur en
 *   tête (`retry-orders`) désigne ce qui suit, ailleurs (`orders.retry.5m`) ce qui précède. Un
 *   balayage de toutes les découpes produirait des candidats comme `5m`, qui n'ont aucun sens et
 *   qui finiraient par tomber juste une fois sur un cluster assez grand.
 *
 * Aucun de ces noms n'est affirmé : `resolveSource` les confronte au catalogue.
 */
export function sourceCandidates(topic: string): string[] {
  const out: string[] = [];
  const push = (candidate: string) => {
    if (candidate && candidate !== topic && !out.includes(candidate)) out.push(candidate);
  };

  let base = topic;
  if (isDeadLetterTopic(topic)) {
    base = topic.replace(DEAD_LETTER_TAIL, '');
    push(base);
  }

  if (isRetryTopic(base)) {
    const segments = segmentsOf(base);
    const index = segments.findIndex(s => s.text.toLowerCase().includes('retry'));
    if (index === 0) {
      const after = segments[0].end < base.length ? base.slice(segments[0].end + 1) : '';
      push(after);
    } else if (index > 0) {
      push(base.slice(0, segments[index].start - 1));
    }
  }

  return out;
}

/** Vrai pour un topic qui est lui-même une file : jamais la source d'une autre. */
function isQueue(topic: string): boolean {
  return isDeadLetterTopic(topic) || isRetryTopic(topic);
}

/**
 * La source de cette file, et comment elle a été trouvée.
 *
 * **Deux passes, parce qu'une seule n'appariait rien sur le cluster de démonstration.** La règle
 * stricte — retirer le marqueur, chercher le nom obtenu — suppose que la source s'appelle
 * exactement le préfixe de la file. C'est vrai de `orders.DLQ` → `orders`, et faux de toute
 * convention à étapes numérotées : `demo.orders.2.dlt` donne `demo.orders.2`, qui n'est le nom
 * d'aucun topic, alors que sa source `demo.orders.2.validated` est juste à côté dans le
 * catalogue. Les trois files que `setup-demo.sh` sème tombent toutes dans ce cas, donc la seconde
 * courbe n'existait sur aucune ligne du jeu de données que ce dépôt recommande lui-même.
 *
 * La seconde passe cherche donc les topics qui **partagent ce préfixe** et qui ne sont pas
 * eux-mêmes des files — un `.retry` ne peut pas être la source de son `.dlt` par cette voie, il
 * l'est par la chaîne, que les candidats couvrent déjà en position plus précise.
 *
 * **Elle n'apparie qu'en l'absence d'ambiguïté, et l'ambiguïté est énumérée plutôt qu'arbitrée.**
 * `demo.payments.dlq` a deux voisins possibles (`authorized`, `captured`) : en choisir un
 * donnerait un taux d'échec calculé contre la moitié du trafic, c'est-à-dire un nombre faux qui a
 * l'air d'un nombre. L'écran nomme les deux et laisse la ligne sans seconde courbe — c'est la même
 * règle que partout ici, une mesure qu'on ne peut pas prendre ne se remplace pas par une valeur.
 */
export function resolveSource(topic: string, catalogue: readonly string[]): SourcePairing {
  const candidates = sourceCandidates(topic);
  const tried = candidates[0] ?? null;
  const known = new Set(catalogue);

  const exact = candidates.find(candidate => known.has(candidate));
  if (exact) return { source: exact, how: 'exact', tried, alternatives: [] };

  for (const candidate of candidates) {
    const siblings = catalogue.filter(other =>
      other !== topic
      && other.length > candidate.length + 1
      && other.startsWith(candidate)
      && '._-'.includes(other[candidate.length])
      && !isQueue(other));
    if (siblings.length === 1) {
      return { source: siblings[0], how: 'prefix', tried, alternatives: [] };
    }
    if (siblings.length > 1) {
      // Un candidat plus court serait plus ambigu encore : on s'arrête là plutôt que d'élargir.
      return { source: null, how: 'ambiguous', tried, alternatives: siblings };
    }
  }

  return { source: null, how: 'none', tried, alternatives: [] };
}

/**
 * Les topics de l'écran, dans l'ordre où ils s'affichent.
 *
 * Les files mortes passent avant les reprises : une reprise qui se remplit est un incident en
 * cours, une file morte qui se remplit est un incident déjà perdu, et c'est celui-là qu'on ouvre
 * en premier. À l'intérieur d'un groupe, l'ordre est alphabétique — le tri par volume est un
 * réglage de l'écran, pas une propriété de la liste.
 */
export function supervisionTopics(topics: string[]): SupervisionTopic[] {
  const rows = topics
    .filter(isQueue)
    .map<SupervisionTopic>(topic => ({
      topic,
      kind: deadLetterLabel(topic) ?? 'RETRY',
      pairing: resolveSource(topic, topics),
    }));

  const rank = (row: SupervisionTopic) => (row.kind === 'RETRY' ? 1 : 0);
  return rows.sort((a, b) => rank(a) - rank(b) || a.topic.localeCompare(b.topic));
}

/**
 * Les files elles-mêmes, dans l'ordre de l'écran — la **première** des deux demandes.
 *
 * Elles étaient demandées entrelacées avec leurs sources, ce qui doublait la liste, et le
 * contrôleur coupe à `explorer.activity-max-topics` (100 par défaut) en gardant le début. Au-delà
 * d'une cinquantaine de files, la coupe mordait donc sur des lignes réelles : elles n'avaient
 * jamais de courbe, le tri par volume les classait au fond faute de mesure, et l'écran affirmait
 * ainsi un classement « du plus rempli au moins rempli » qu'il n'avait pas mesuré — la chose
 * précise que cette page refuse partout ailleurs.
 *
 * Séparer les deux demandes double la portée pour le même plafond : les files seules tiennent
 * jusqu'à cent, ce qui couvre les tuiles du haut et le classement pour tout cluster réaliste. Les
 * sources suivent, à part, et seulement pour ce qui est affiché — voir `sourceRequestTopics`.
 */
export function queueRequestTopics(rows: SupervisionTopic[]): string[] {
  const out: string[] = [];
  for (const row of rows) if (!out.includes(row.topic)) out.push(row.topic);
  return out;
}

/**
 * Les sources à demander, **pour les lignes affichées seulement** et sans celles déjà connues.
 *
 * La seconde courbe n'est tracée que sur les lignes visibles, donc mesurer la source des autres
 * serait payer un aller-retour au broker pour un dessin que personne ne regarde. Ce qui a déjà été
 * lu n'est pas redemandé : la connaissance ne fait que croître au fil de la pagination, ce qui est
 * ce qui rend le procédé stable — trier par taux change la page, la page demande des sources, les
 * sources changent le tri, et sans ce cliquet la boucle pourrait osciller entre deux pages.
 */
export function sourceRequestTopics(
  visible: SupervisionTopic[], known: ReadonlySet<string>,
): string[] {
  const out: string[] = [];
  for (const row of visible) {
    const source = row.pairing.source;
    if (source && !known.has(source) && !out.includes(source)) out.push(source);
  }
  return out;
}

/**
 * Ce que la ligne dit de son appariement — deux phrases, jamais une seule.
 *
 * Une source déduite du nom et une source **inférée du voisinage** ne se valent pas : la seconde
 * est une hypothèse que l'écran a le devoir d'exposer, puisque tout le taux d'échec en dépend. Le
 * libellé court tient dans la cellule, le détail va dans l'infobulle et dans l'énoncé accessible.
 */
export function describePairing(pairing: SourcePairing): { label: string; detail: string } {
  switch (pairing.how) {
    case 'exact':
      return {
        label: `from ${pairing.source}`,
        detail: `The name says so: ${pairing.source} is what is left once the queue marker is removed, and the cluster has that topic.`,
      };
    case 'prefix':
      return {
        label: `from ${pairing.source} (inferred)`,
        detail: `No topic is named ${pairing.tried}, so the source was inferred: ${pairing.source} is the only topic under that prefix that is not itself a queue. The share below rests on that guess.`,
      };
    case 'ambiguous':
      return {
        label: 'source ambiguous',
        detail: `${pairing.alternatives.length} topics sit under ${pairing.tried} (${pairing.alternatives.join(', ')}), and picking one would compute the share against part of the traffic. None was picked.`,
      };
    default:
      return {
        label: 'no source paired',
        detail: pairing.tried
          ? `No topic named ${pairing.tried} exists on this cluster, and nothing sits under that prefix, so there is nothing to compute a share against.`
          : 'The name carries no source to derive, so there is nothing to compute a share against.',
      };
  }
}

/**
 * La file morte dans laquelle cette reprise se déverse quand elle renonce, si le cluster en a une.
 *
 * **Une reprise n'est pas une file de rebut, et l'écran les traitait pareil.** Le bandeau dit que
 * le trafic ici est une perte : c'est vrai d'un `.DLQ`, faux d'un `.retry`. Une reprise qui se
 * remplit *et se vide* est un système qui fait exactement son travail — le message a échoué une
 * fois, il sera rejoué, et la plupart passeront. Ce qui est une perte, c'est ce qui **sort** de la
 * reprise par le bas : l'escalade vers la file morte.
 *
 * Elle se déduit de l'appariement déjà calculé, lu dans l'autre sens : `orders.retry.5m.DLQ` a
 * pour source `orders.retry.5m`, donc c'est l'escalade de cette reprise. Rien n'est deviné et rien
 * n'est demandé au cluster en plus.
 */
export function escalationTargetOf(
  row: SupervisionTopic, rows: readonly SupervisionTopic[],
): string | null {
  if (row.kind !== 'RETRY') return null;

  // Chaînage explicite : `orders.retry.5m.DLQ` a pour source `orders.retry.5m`. Le cas le plus
  // sûr, puisque le nom de la file morte dit de quoi elle recueille les échecs.
  const chained = rows.find(other => other.kind !== 'RETRY' && other.pairing.source === row.topic);
  if (chained) return chained.topic;

  /*
   * **Sinon, la file morte qui sort du même flux.** Mesuré sur le jeu de démonstration, où la
   * règle précédente seule ne se déclenchait jamais : Spring Kafka nomme `orders.2.dlt` en *frère*
   * de `orders.2.retry.5m`, pas en enfant — les deux sont des sorties de `orders.2.validated`, et
   * c'est l'appariement déjà calculé qui le dit. Même leçon que pour l'appariement lui-même : une
   * règle juste sur la convention qu'on avait en tête et muette sur celle que les producteurs
   * écrivent vraiment.
   *
   * L'inférence est plus faible que le chaînage, donc elle exige l'**unicité** : deux files mortes
   * sous la même source, et on ne sait pas laquelle recueille cette reprise.
   */
  const source = row.pairing.source;
  if (!source) return null;
  const siblings = rows.filter(other =>
    other.kind !== 'RETRY' && other.pairing.source === source);
  return siblings.length === 1 ? siblings[0].topic : null;
}

// ── La part du trafic qui échoue ─────────────────────────────────────────────

/**
 * La seconde courbe : le taux d'échec, bucket par bucket, en pourcentage.
 *
 * `null` plutôt que `0` partout où le rapport n'existe pas, et c'est le point de tout le module :
 * un bucket où la source n'a rien produit n'a pas de taux d'échec — tracé à zéro il se lirait
 * « tout va bien », qui est exactement ce qu'on ne sait pas. Le trou est dessiné comme un trou.
 *
 * Ce que la mesure ne peut pas éviter est dit plutôt que corrigé : un message produit à la fin
 * d'un bucket échoue dans le suivant, donc les deux séries sont décalées d'un traitement. Sur des
 * buckets d'une heure c'est du bruit ; sur des buckets de cinq minutes et une reprise à
 * temporisation longue, la part se lit à l'échelle de la fenêtre (`overall`) et pas point par
 * point.
 */
export interface ShareSeries {
  /** Un point par bucket, en pourcentage. `null` = pas de rapport définissable ici. */
  points: (number | null)[];
  /** Le pic mesurable et son bucket ; `null` quand aucun point ne l'est. */
  peak: number | null;
  peakIndex: number;
  /** La part sur toute la fenêtre — totaux rapportés, jamais une moyenne des points. */
  overall: number | null;
  /** Buckets où la file a reçu quelque chose que la source n'explique pas. */
  unexplained: number;
  /** Vrai dès qu'un point dépasse 100 % : l'appariement ou la redélivrance est à revoir. */
  overflow: boolean;
  available: boolean;
  /** Pourquoi la courbe manque ou est partielle. `null` quand elle est complète. */
  note: string | null;
}

const NO_SHARE: ShareSeries = {
  points: [], peak: null, peakIndex: 0, overall: null,
  unexplained: 0, overflow: false, available: false, note: null,
};

function unavailableShare(note: string): ShareSeries {
  return { ...NO_SHARE, note };
}

/**
 * Rapporte les arrivées de la file au trafic de sa source.
 *
 * Les deux séries viennent du même appel, donc du même découpage — c'est vérifié quand même :
 * deux séries de longueurs différentes divisées point à point produiraient un taux qui compare
 * des instants différents, ce qui ne se voit sur aucune courbe.
 */
export function shareSeries(
  queue: TopicActivity | null | undefined,
  source: TopicActivity | null | undefined,
): ShareSeries {
  if (!queue || !queue.available) return unavailableShare('The queue itself could not be measured.');
  if (!source) return unavailableShare('No source topic is paired with this queue.');
  if (!source.available) {
    return unavailableShare(`The source topic ${source.topic} could not be measured: ${source.note ?? 'the broker did not answer.'}`);
  }
  if (queue.counts.length !== source.counts.length || queue.bucketMs !== source.bucketMs) {
    return unavailableShare('The two series are not bucketed alike, so they cannot be compared.');
  }

  let peak: number | null = null;
  let peakIndex = 0;
  let unexplained = 0;
  let overflow = false;

  const points = queue.counts.map((count, i) => {
    const produced = source.counts[i];
    if (produced <= 0) {
      // Rien produit en amont : il n'y a pas de « part de », et zéro serait une affirmation de
      // santé. Ce qui est tombé dans la file malgré ça est compté à part — c'est le décalage
      // d'un traitement, ou une source mal appariée.
      if (count > 0) unexplained++;
      return null;
    }
    const percent = (count / produced) * 100;
    if (percent > 100) overflow = true;
    if (peak === null || percent > peak) {
      peak = percent;
      peakIndex = i;
    }
    return percent;
  });

  const producedTotal = source.counts.reduce((sum, n) => sum + n, 0);
  const overall = producedTotal > 0 ? (queue.total / producedTotal) * 100 : null;

  const notes: string[] = [];
  if (unexplained > 0) {
    notes.push(`${unexplained} bucket(s) received messages while ${source.topic} produced none — a queue lags its source by one hop.`);
  }
  if (overflow) {
    notes.push(`The queue took more than ${source.topic} produced in at least one bucket: redeliveries, or a source that is not the right one.`);
  }
  if (source.coveredFromMs !== null || source.partitionsMeasured < source.partitionsTotal) {
    notes.push(`${source.topic} is a floor over part of the window, so the share is a ceiling there.`);
  }

  return {
    points,
    peak,
    peakIndex,
    overall,
    unexplained,
    overflow,
    available: points.some(p => p !== null),
    note: notes.length > 0 ? notes.join(' ') : null,
  };
}

/**
 * Le plancher de l'échelle verticale, en pourcentage.
 *
 * Une sparkline mise à l'échelle de sa propre pointe est le bon choix pour des comptes, où il n'y
 * a pas d'unité naturelle — c'est ce que fait `topicActivity.sparkline`. Pour un pourcentage il y
 * en a une, et cadrer sur un pic de 0,3 % dessinerait une montagne pour un taux d'échec qui n'en
 * est pas une. Sous 1 %, la courbe reste écrasée en bas de la boîte, ce qui est l'information.
 */
export const SHARE_SCALE_FLOOR = 1;

export interface SharePoint {
  x: number;
  y: number;
}

/** Géométrie de la courbe de part, cassée là où elle n'est pas mesurable. */
export interface ShareShape {
  /** Un `d` par tronçon continu — un tronçon d'un seul point n'en produit pas, `dots` le porte. */
  segments: string[];
  /** Les points isolés, qu'aucun tronçon ne relie. */
  dots: SharePoint[];
  /** Les plages non mesurables, en coordonnées du `viewBox`, à griser. */
  gaps: Array<{ x: number; width: number }>;
  points: Array<SharePoint | null>;
  /** Le haut de la boîte, en pourcentage. */
  top: number;
}

const round = (n: number) => Math.round(n * 100) / 100;

export function shareShape(
  points: (number | null)[], width: number, height: number, padding = 2,
): ShareShape {
  const usableW = Math.max(1, width - padding * 2);
  const usableH = Math.max(1, height - padding * 2);
  const baseline = padding + usableH;
  const step = points.length > 1 ? usableW / (points.length - 1) : 0;
  const measured = points.filter((p): p is number => p !== null);
  const top = Math.max(SHARE_SCALE_FLOOR, ...(measured.length > 0 ? measured : [0]));

  const placed = points.map<SharePoint | null>((value, i) => (
    value === null ? null : {
      x: round(padding + i * step),
      y: round(baseline - (value / top) * usableH),
    }
  ));

  const segments: string[] = [];
  const dots: SharePoint[] = [];
  let run: SharePoint[] = [];
  const flush = () => {
    if (run.length > 1) segments.push(run.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' '));
    else if (run.length === 1) dots.push(run[0]);
    run = [];
  };
  placed.forEach(point => {
    if (point) run.push(point);
    else flush();
  });
  flush();

  const gaps: Array<{ x: number; width: number }> = [];
  let gapStart: number | null = null;
  placed.forEach((point, i) => {
    if (point === null && gapStart === null) gapStart = i;
    if (point !== null && gapStart !== null) {
      gaps.push({ x: round(padding + gapStart * step), width: round((i - gapStart) * step) });
      gapStart = null;
    }
  });
  if (gapStart !== null) {
    gaps.push({ x: round(padding + gapStart * step), width: round((points.length - gapStart) * step) });
  }

  return { segments, dots, gaps, points: placed, top };
}

/** `0.42%`, `12%`, `140%` — deux décimales sous 1 %, aucune au-dessus de 10. */
export function formatPercent(value: number): string {
  if (value === 0) return '0%';
  if (value < 1) return `${Math.round(value * 100) / 100}%`;
  if (value < 10) return `${Math.round(value * 10) / 10}%`;
  return `${Math.round(value)}%`;
}

/** L'énoncé accessible de la seconde courbe — une image sans texte n'informe personne. */
export function describeShare(series: ShareSeries, queue: string, source: string | null): string {
  if (!source) return `Failure rate for ${queue}: no source topic is paired, so there is nothing to compare against.`;
  if (!series.available) {
    return `Failure rate for ${queue} against ${source} could not be computed: ${series.note ?? 'no bucket was comparable.'}`;
  }
  const overall = series.overall === null ? 'not computable over the window' : formatPercent(series.overall);
  const peak = series.peak === null ? '' : ` Peak ${formatPercent(series.peak)} in one bucket.`;
  return `${queue} took ${overall} of what ${source} produced over the window.${peak}`
    + `${series.note ? ` ${series.note}` : ''}`;
}

// ── Ce que la ligne dit d'elle-même ──────────────────────────────────────────

/**
 * L'état d'une file, tel qu'un badge le dit.
 *
 * La lecture est inversée par rapport au reste de l'application, et c'est délibéré : `quiet` est
 * la bonne nouvelle ici. Un topic de rebut silencieux est un pipeline qui n'a rien perdu, là où
 * le tableau de bord traite le même silence comme un signal à surveiller.
 */
export type DeadLetterState = 'unknown' | 'quiet' | 'receiving' | 'surging';

export interface DeadLetterVerdict {
  state: DeadLetterState;
  /** Le libellé du badge. */
  label: string;
  tone: 'neutral' | 'secondary' | 'success' | 'warning' | 'error';
  /** La phrase complète, pour l'infobulle et l'énoncé accessible. */
  detail: string;
}

/**
 * Ce que la fenêtre dit de cette file.
 *
 * `surging` s'appuie sur `detectTrend`, qui est déjà le juge du régime courant sur le tableau de
 * bord — refaire un seuil ici donnerait deux définitions de « ça monte » pour la même série, et
 * elles dériveraient. Ce qui change est la conclusion qu'on en tire, pas la mesure.
 */
export function assessQueue(
  activity: TopicActivity | null | undefined,
  kind: DeadLetterKind,
  /**
   * Ce que l'escalade de cette reprise a reçu sur la même fenêtre, quand une escalade existe et
   * qu'elle a pu être mesurée. `undefined` veut dire « pas d'escalade connue », ce qui n'est pas
   * la même chose que `total: 0` — une reprise sans file morte identifiée n'est pas une reprise
   * qui n'en perd aucun.
   */
  escalation?: TopicActivity | null,
): DeadLetterVerdict {
  const what = kind === 'RETRY' ? 'retries' : 'dead letters';
  if (!activity || !activity.available) {
    return {
      state: 'unknown',
      label: 'not measured',
      tone: 'neutral',
      detail: activity?.note ?? 'The broker did not answer for this topic, so nothing is claimed about it.',
    };
  }
  if (activity.total === 0) {
    return {
      state: 'quiet',
      label: 'quiet',
      tone: 'success',
      detail: `No ${what} over the window. On this screen that is the good news.`,
    };
  }
  const trend = detectTrend(activity);
  /*
   * Le ton d'une reprise se lit à son escalade, pas à son volume. Une reprise qui reçoit et dont
   * la file morte reste vide a rattrapé ce qui lui est passé par les mains, ce qui est le
   * fonctionnement nominal ; l'annoncer en orange comme un rebut apprend à ignorer la couleur, et
   * une couleur qu'on ignore ne sert plus le jour où elle compte. Une escalade inconnue ne bénéficie
   * pas de l'adoucissement : ne pas savoir n'est pas une bonne nouvelle.
   */
  const contained = kind === 'RETRY' && escalation?.available === true && escalation.total === 0;
  const escalated = escalation?.available === true && escalation.total > 0
    ? ` ${escalation.total.toLocaleString()} of them escalated to ${escalation.topic}.`
    : '';

  if (trend && trend.direction === 'up') {
    return {
      state: 'surging',
      label: 'surging',
      tone: 'error',
      detail: `${activity.total.toLocaleString()} ${what} over the window, and the last bucket runs `
        + `${Math.round(trend.ratio * 10) / 10}× the window's median — this is filling up right now.${escalated}`,
    };
  }
  if (contained) {
    return {
      state: 'receiving',
      label: 'retrying',
      tone: 'secondary',
      detail: `${activity.total.toLocaleString()} retries over the window, and nothing reached `
        + `${escalation.topic}. A retry queue that fills and drains is doing its job — what would be `
        + 'a loss is what escalates out of it.',
    };
  }
  return {
    state: 'receiving',
    label: 'receiving',
    tone: 'warning',
    detail: `${activity.total.toLocaleString()} ${what} over the window.${escalated}`,
  };
}

/** Les chiffres de tête de l'écran. */
export interface SupervisionSummary {
  topics: number;
  deadLetter: number;
  retry: number;
  /** Files ayant reçu au moins un message sur la fenêtre. */
  receiving: number;
  surging: number;
  /** Total des arrivées, toutes files confondues, sur la fenêtre. */
  messages: number;
  /** Files sans source appariée : leur seconde courbe n'existe pas, et c'est un manque à combler. */
  unpaired: number;
  /** Vrai tant qu'aucune file n'a pu être mesurée — distinct de « tout est calme ». */
  measured: number;
}

export function summarize(
  rows: SupervisionTopic[], activities: Record<string, TopicActivity>,
): SupervisionSummary {
  const summary: SupervisionSummary = {
    topics: rows.length,
    deadLetter: rows.filter(r => r.kind !== 'RETRY').length,
    retry: rows.filter(r => r.kind === 'RETRY').length,
    receiving: 0,
    surging: 0,
    messages: 0,
    unpaired: rows.filter(r => r.pairing.source === null).length,
    measured: 0,
  };
  for (const row of rows) {
    const activity = activities[row.topic];
    if (!activity?.available) continue;
    summary.measured++;
    summary.messages += activity.total;
    const verdict = assessQueue(activity, row.kind);
    if (verdict.state === 'receiving' || verdict.state === 'surging') summary.receiving++;
    if (verdict.state === 'surging') summary.surging++;
  }
  return summary;
}
