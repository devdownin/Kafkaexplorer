// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useEffect, useRef } from 'react';
import {
  advanceField,
  createField,
  linkAlpha,
  resizeField,
  type FieldNode,
} from './metricsPulseField';

/**
 * Le fond animé de la page Metrics : un champ de points qui dérive, reliés quand ils se croisent.
 *
 * ── Pourquoi il est écrit ici plutôt qu'installé ────────────────────────────────────────────
 * Ce calque a d'abord attendu « Neural Float » de React Bits. Deux faits l'ont refermé, et ils
 * sont notés ici parce que la question se reposera : **Neural Float n'existe pas dans la
 * bibliothèque libre** — c'est un composant de React Bits *Pro*, derrière une clé payante — et
 * l'ensemble libre est publié sous **MIT + Commons Clause**, qui interdit de redistribuer les
 * composants eux-mêmes. Ce dépôt est en AGPL v3 et il *est* redistribué : sources publiques, jar,
 * images. Absorber un fichier non-libre y demanderait une exception à la licence du projet, pour
 * un ornement. Le fond est donc écrit ici, sous la même licence que le reste — sans dépendance
 * nouvelle, sans registre, sans clé.
 *
 * La géométrie ci-dessous, elle, ne vient pas de nulle part : elle a été mesurée à l'époque du
 * calque vide et rien ne l'a invalidée. Les commentaires qui la portent sont conservés tels
 * quels — c'est la partie qui se reprend en silence.
 */

/** `primary` du thème (#a3adff), en composantes, parce que le canevas ne lit pas Tailwind. */
const PRIMARY_RGB = '163, 173, 255';

/**
 * Deux opacités, toutes deux basses, et l'écart entre les deux est délibéré : ce sont les *liens*
 * qui saturent une image (il y en a un par paire proche, les points sont comptés une fois), donc
 * un lien à la même opacité qu'un point rend un maillage laiteux au lieu d'une constellation.
 */
const NODE_ALPHA = 0.40;
const LINK_ALPHA = 0.16;

/**
 * ~30 images par seconde plutôt que le rythme de l'écran.
 *
 * Un fond n'a pas de mouvement rapide à rendre — les points dérivent à 7 px/s — et cette page
 * reste ouverte des heures sur un poste qui affiche par ailleurs des graphes. Diviser la
 * cadence par deux divise le coût par deux pour une différence que l'œil ne fait pas sur une
 * dérive aussi lente. `requestAnimationFrame` reste le battement : c'est lui qui s'arrête tout
 * seul quand l'onglet passe à l'arrière-plan, ce qu'un `setInterval` ne ferait pas.
 */
const FRAME_INTERVAL_MS = 1000 / 30;

/**
 * Le pas de temps est plafonné : au retour d'un onglet laissé de côté, `now - lastFrameAt` vaut
 * des minutes, et le champ se téléporterait. Un quart de seconde est la plus grande avance qui
 * reste lisible comme un mouvement.
 */
const MAX_STEP_MS = 250;


interface MetricsPulseBackdropProps {
  /**
   * Au moins une métrique *en marche* — voir `hasRunningMetric` dans `pages/metricsHealth.ts`.
   *
   * Le calque n'existe pas quand c'est faux, plutôt que d'exister masqué : une page qui ne mesure
   * rien encore ne doit pas payer une boucle d'animation, et un `<canvas>` invisible qui tourne
   * est précisément ce qu'on ne voit pas en revenant six mois plus tard.
   */
  active: boolean;
}

export const MetricsPulseBackdrop: React.FC<MetricsPulseBackdropProps> = ({ active }) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const host = canvas?.parentElement;
    if (!canvas || !host) return;

    /* Le mouvement réduit est traité **deux fois**, et ce n'est pas une redondance. La classe
       `motion-reduce:hidden` de l'ancre cache le calque tout de suite, sans attendre que React
       monte quoi que ce soit ; ce test-ci fait qu'aucune boucle ne démarre. Le CSS seul
       laisserait tourner une animation invisible — exactement ce que le commentaire de `active`
       ci-dessus refuse — et le JS seul laisserait une image s'afficher avant le premier effet. */
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    // Plafonné à 2 : au-delà, on quadruple le nombre de pixels à peindre pour un ornement qui est
    // à 16 % d'opacité. C'est la même borne que celle qu'appliquent les fonds de ce genre.
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    let context: CanvasRenderingContext2D | null = null;
    let nodes: FieldNode[] = [];
    let size = { width: 0, height: 0 };
    let frame = 0;
    let lastFrameAt = 0;
    /* Remesurer à chaque image coûterait une lecture de `getBoundingClientRect` par image, donc
       un recalcul de mise en page imposé au navigateur trente fois par seconde pour une taille
       qui ne bouge presque jamais. Le drapeau fait que la mesure a lieu au montage puis
       uniquement après une notification du `ResizeObserver` — et qu'elle est **retentée** tant
       qu'elle n'a rien donné, sans quoi un hôte pas encore disposé au montage resterait vide. */
    let needsMeasure = true;

    /**
     * Mesure l'hôte et prépare le canevas. Rend `false` tant qu'il n'y a rien à peindre.
     *
     * Le garde-fou « surface nulle » est **avant** `getContext`, et c'est ce qui garde la suite de
     * tests silencieuse : jsdom n'implémente aucune mise en page (donc un rectangle de 0×0) ni
     * `getContext`, qu'il signale bruyamment sur la console quand on l'appelle. Sortir sur la
     * mesure est de toute façon la bonne conduite dans un navigateur — un hôte que rien n'a encore
     * disposé n'a pas de champ à peupler.
     */
    const measure = (): boolean => {
      const rect = host.getBoundingClientRect();
      const width = Math.round(rect.width);
      const height = Math.round(rect.height);
      if (width <= 0 || height <= 0) return false;

      if (!context) {
        context = canvas.getContext('2d');
        if (!context) return false;
      }

      if (width !== size.width || height !== size.height) {
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        // Le contexte est remis à l'échelle après chaque changement de taille : écrire dans
        // `canvas.width` réinitialise la transformation, donc la poser une fois au montage la
        // perdrait au premier redimensionnement et tout serait peint à l'échelle 1.
        context.setTransform(dpr, 0, 0, dpr, 0, 0);
        nodes = nodes.length
          ? resizeField(nodes, size, { width, height })
          : createField(width, height);
        size = { width, height };
      }
      return true;
    };

    const draw = () => {
      if (!context) return;
      const ctx = context;
      ctx.clearRect(0, 0, size.width, size.height);

      /* Les liens d'abord, les points ensuite : un point posé par-dessus les traits qui le
         rejoignent cache leur extrémité, et le champ se lit alors comme un maillage plutôt que
         comme des points reliés. */
      ctx.strokeStyle = `rgb(${PRIMARY_RGB})`;
      ctx.lineWidth = 1;
      for (let i = 0; i < nodes.length; i += 1) {
        for (let j = i + 1; j < nodes.length; j += 1) {
          const alpha = linkAlpha(nodes[i].x - nodes[j].x, nodes[i].y - nodes[j].y);
          if (alpha <= 0) continue;
          ctx.globalAlpha = alpha * LINK_ALPHA;
          ctx.beginPath();
          ctx.moveTo(nodes[i].x, nodes[i].y);
          ctx.lineTo(nodes[j].x, nodes[j].y);
          ctx.stroke();
        }
      }

      ctx.fillStyle = `rgb(${PRIMARY_RGB})`;
      ctx.globalAlpha = NODE_ALPHA;
      for (const node of nodes) {
        ctx.beginPath();
        ctx.arc(node.x, node.y, node.radius, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
    };

    const tick = (now: number) => {
      frame = window.requestAnimationFrame(tick);
      const elapsed = now - lastFrameAt;
      if (elapsed < FRAME_INTERVAL_MS) return;
      lastFrameAt = now;
      if (needsMeasure) {
        if (!measure()) return;
        needsMeasure = false;
      }
      advanceField(nodes, size.width, size.height, Math.min(elapsed, MAX_STEP_MS) / 1000);
      draw();
    };

    // Le redimensionnement ne redessine pas lui-même : il laisse simplement la prochaine image
    // remesurer. Un `ResizeObserver` se déclenche en rafale pendant qu'on tire un bord de fenêtre,
    // et peindre à chaque notification ferait exactement le travail que la cadence plafonnée
    // ci-dessus existe pour éviter.
    const observer =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => { needsMeasure = true; });
    observer?.observe(host);

    frame = window.requestAnimationFrame(tick);
    return () => {
      window.cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [active]);

  if (!active) return null;

  return (
    <div
      data-testid="metrics-pulse-backdrop"
      aria-hidden="true"
      /* Le calque se déclare ornemental, pour les outils qui ont besoin d'une image *stable*.
         `capture.mjs` le masque avant de photographier, et ce n'est pas une coquetterie : mesuré
         sur un même build et un même serveur, deux passages rendaient les sept autres pages
         identiques au bit près et `metrics.png` différente à chaque fois — 24 437 pixels
         dispersés. C'est exactement la propriété que `fixtures.mjs` énonce en dérivant tout d'un
         instant fixe : « a screenshot that changes on every build is a diff nobody can review ».

         Une graine fixe ne suffirait pas, et ça a été mesuré avant d'être écrit : les positions
         initiales redeviennent identiques, mais le champ *dérive* avec le temps écoulé et la
         capture tombe à un instant qui, lui, varie. Ce qu'il faut est donc de retirer l'ornement
         de l'image, pas de rendre son tirage prévisible.

         L'attribut plutôt que le `data-testid` dans l'outil : ce qu'on masque est « un décor »,
         pas « ce composant-ci », et le prochain calque de ce genre n'aura pas à être ajouté à une
         liste tenue ailleurs. */
      data-decorative="true"
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
         moins de mouvement. Il est doublé côté JS, où la boucle ne démarre pas du tout. */
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
        <canvas ref={canvasRef} className="h-full w-full" />
      </div>
    </div>
  );
};
