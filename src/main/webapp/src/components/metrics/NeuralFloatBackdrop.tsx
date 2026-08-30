// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react';

/**
 * Le calque d'accueil du composant « Neural Float » (React Bits Pro) sur la page Metrics.
 *
 * ── État : le calque est posé, le composant ne l'est pas ─────────────────────────────────────
 * `npx shadcn@latest add @reactbits-starter/neural-float-tw` n'a **pas** pu être exécuté ici :
 * `pro.reactbits.dev` et `ui.shadcn.com` sont tous deux refusés par la politique réseau de
 * l'environnement (403 au CONNECT), et aucune `REACTBITS_LICENSE_KEY` n'y est définie. Ce
 * fichier est donc la moitié qui ne dépend pas du registre : la condition d'affichage, le
 * placement, l'accessibilité et le respect de `prefers-reduced-motion` — tout ce qui aurait de
 * toute façon été à écrire ici plutôt que fourni par le paquet.
 *
 * Rien n'est dessiné tant que le composant n'est pas installé, et c'est délibéré : une animation
 * écrite à la main ici serait un deuxième « Neural Float », non licencié, que le vrai viendrait
 * doubler sans le remplacer.
 *
 * ── Pour finir l'installation, sur une machine qui atteint le registre ───────────────────────
 *   1. export REACTBITS_LICENSE_KEY=…            (components.json le lit dans l'en-tête Authorization)
 *   2. cd src/main/webapp
 *   3. npx shadcn@latest add @reactbits-starter/neural-float-tw
 *   4. décommenter l'import et la balise ci-dessous, en ajustant le nom exporté et les props au
 *      composant réellement livré — la doc est sur https://pro.reactbits.dev/docs/components/neural-float
 *
 * Le reste de l'application n'a alors rien à changer : `Metrics.tsx` monte ce calque et lui
 * passe déjà sa condition.
 */

// import { NeuralFloat } from '@/components/ui/neural-float-tw';

interface NeuralFloatBackdropProps {
  /**
   * Au moins une métrique *en marche* — voir `hasRunningMetric` dans `pages/metricsHealth.ts`.
   *
   * Le calque n'existe pas quand c'est faux, plutôt que d'exister masqué : une page qui ne mesure
   * rien encore ne doit pas payer une boucle d'animation, et un `<canvas>` invisible qui tourne
   * est précisément ce qu'on ne voit pas en revenant six mois plus tard.
   */
  active: boolean;
}

export const NeuralFloatBackdrop: React.FC<NeuralFloatBackdropProps> = ({ active }) => {
  if (!active) return null;

  return (
    <div
      data-testid="neural-float-backdrop"
      aria-hidden="true"
      /* Pas de `-z-10` : un z négatif n'irait derrière le contenu que si un ancêtre proche
         formait un contexte d'empilement, or en poser un sur la page enfermerait le modal
         « Add metric » sous la Sidebar (voir le commentaire de la racine dans Metrics.tsx).
         Le calque est donc simplement le *premier* des deux enfants positionnés `z-auto` de la
         racine, et le contenu, second, peint par-dessus par ordre du DOM.
         `motion-reduce:hidden` : l'ornement est le premier à disparaître quand l'OS demande
         moins de mouvement, et c'est déjà vrai avant que le composant n'arrive. */
      className="pointer-events-none absolute inset-0 overflow-hidden motion-reduce:hidden"
    >
      {/* <NeuralFloat /> — voir l'en-tête de ce fichier */}
    </div>
  );
};
