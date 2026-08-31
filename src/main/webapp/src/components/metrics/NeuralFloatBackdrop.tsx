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
      /* `sticky top-0 h-0` plutôt que `absolute inset-0`, et c'est une mesure qui l'a décidé.
         Dans un conteneur qui défile, `inset-0` se résout sur la *fenêtre de défilement* et pas
         sur le contenu : mesuré sur la page Metrics de la démo en 1440×900, le calque faisait
         844 px pour 2 318 px de contenu, et après défilement jusqu'en bas son sommet était
         1 474 px au-dessus de la zone visible — l'ornement occupait le premier tiers de la page
         puis disparaissait. Un fond qui s'en va au défilement se lit comme un défaut, pas comme
         une intention. `sticky` le fait suivre la fenêtre ; `h-0` lui retire toute place dans le
         flux, de sorte qu'il ne pousse rien.

         Le `sticky` forme un contexte d'empilement, et c'est sans conséquence ici : il naît sur
         *ce* calque, pas sur la page. Le modal « Add metric » vit dans le conteneur frère, donc
         il n'y est pas enfermé — c'est tout le sujet du commentaire de la racine dans
         Metrics.tsx, et le contrôle Chromium a été refait après ce changement.

         Pas de `-z-10` : un z négatif n'irait derrière le contenu que si un ancêtre proche
         formait un contexte d'empilement, or en poser un sur la page est précisément ce qui
         enfermerait le modal. Le calque reste le *premier* des deux enfants positionnés
         `z-auto` de la racine, et le contenu, second, peint par-dessus par ordre du DOM.

         `motion-reduce:hidden` : l'ornement est le premier à disparaître quand l'OS demande
         moins de mouvement, et c'est déjà vrai avant que le composant n'arrive. */
      className="pointer-events-none sticky top-0 h-0 -mx-6 motion-reduce:hidden"
    >
      {/* Le débord horizontal est porté par l'ancre (`-mx-6` ci-dessus) et **pas** par cet hôte,
          qui se contente d'`inset-x-0`. La différence n'est pas cosmétique : avec les marges
          négatives ici, l'hôte était plus large que l'ancre qui le contient, et `layout-probe`
          comptait l'ancre comme un conteneur qui rogne son contenu sans moyen d'atteindre le
          reste — `metrics` passait de 0 à 1 `unreachable` aux deux largeurs et faisait échouer
          le job screenshots. Porté par l'ancre, le débord annule exactement le `p-6` de la
          racine : l'ancre fait la largeur de la *boîte de padding* de la racine, l'hôte fait la
          largeur de l'ancre, et plus rien ne dépasse de son parent à aucun niveau.

          `h-screen` dépasse la zone visible de la hauteur de l'en-tête, ce que le conteneur de
          défilement rogne — un dépassement vertical est sans effet ici, alors qu'une hauteur
          trop courte laisserait une bande nue en bas. */}
      <div className="absolute inset-x-0 -top-6 h-screen overflow-hidden">
        {/* <NeuralFloat /> — voir l'en-tête de ce fichier */}
      </div>
    </div>
  );
};
