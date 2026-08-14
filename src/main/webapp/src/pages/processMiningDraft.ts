// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Reprise du pipeline Process Mining après un changement d'écran.
 *
 * Le pipeline coûte cher — deux appels au modèle, une lecture de topics — et il se perdait
 * entièrement dès qu'on allait vérifier un topic dans l'explorateur. Ce qui est conservé, ce sont
 * les *acquis* : la sélection, le profilage validé, le résultat d'analyse. Ce qui ne l'est pas,
 * c'est tout ce qui décrit une opération en cours.
 */

export type Step = 'SELECT' | 'PROFILING' | 'VALIDATE' | 'ANALYZE' | 'RESULTS';
export type AnalysisMode = 'SNAPSHOT' | 'LIVE';

/**
 * À quelle étape un brouillon peut réellement reprendre.
 *
 * Deux états ne se restaurent pas :
 *
 * - `PROFILING` est un écran d'attente adossé à une requête HTTP. La requête est morte avec la
 *   page ; restaurer l'étape afficherait un chargement qui ne finira jamais. On revient à la
 *   sélection, qui est intacte, et l'utilisateur relance.
 * - `RESULTS` en mode live tient à une session SSE, qui ne se reprend pas davantage : le flux est
 *   fermé, la fenêtre glissante est vide côté serveur. On revient à `ANALYZE`, où le bouton de
 *   lancement rouvrira une session — plutôt que de présenter un graphe figé comme s'il était vivant.
 *
 * Le reste est refusé faute des données dont l'étape dépend : pas de profilage, pas de validation ;
 * pas d'identifiant de mapping, pas d'analyse ; pas de résultat, pas d'écran de résultats.
 */
export function resumableStep(draft: {
  step: Step;
  analysisMode: AnalysisMode;
  hasProfile: boolean;
  hasMapping: boolean;
  hasSnapshot: boolean;
}): Step {
  const { step, analysisMode, hasProfile, hasMapping, hasSnapshot } = draft;
  switch (step) {
    case 'PROFILING':
      return 'SELECT';
    case 'VALIDATE':
      return hasProfile ? 'VALIDATE' : 'SELECT';
    case 'ANALYZE':
      return hasMapping ? 'ANALYZE' : hasProfile ? 'VALIDATE' : 'SELECT';
    case 'RESULTS':
      if (analysisMode === 'LIVE') return hasMapping ? 'ANALYZE' : 'SELECT';
      if (hasSnapshot) return 'RESULTS';
      return hasMapping ? 'ANALYZE' : 'SELECT';
    default:
      return 'SELECT';
  }
}

/** Ce qui a été restauré mérite d'être dit — un écran qui se rouvre à mi-parcours sans un mot
 *  laisse croire à un état courant, alors que c'est un brouillon d'une visite précédente. */
export function describeResume(restored: Step, asked: Step): string | null {
  if (restored === 'SELECT' && asked === 'SELECT') return null;
  if (restored === asked) return 'Picked up where you left off.';
  if (asked === 'PROFILING') return 'Profiling was still running when you left — start it again.';
  if (asked === 'RESULTS' && restored === 'ANALYZE') {
    return 'The live session ended when you left the page — launch it again.';
  }
  return 'Picked up where you left off, at the last step whose data survived.';
}
