// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Le champ de points du fond de la page Metrics — la moitié qui se calcule, sans canevas.
 *
 * Séparé du composant pour la raison habituelle ici : ce qui se règle silencieusement de travers
 * dans une animation, ce n'est pas le dessin, c'est la *géométrie* — un compte de points qui
 * explose sur un grand écran, un champ qui repart de zéro à chaque redimensionnement, un lien
 * tracé entre deux points qui ne se voient pas. Rien de tout cela n'échoue : ça rend une page
 * lente ou une image qui saute, et personne ne sait dire quand c'est arrivé. Ici c'est une
 * fonction pure, donc c'est mesurable sans monter la page ni disposer d'un `<canvas>` — que jsdom
 * n'a de toute façon pas.
 */

/** Un point du champ. Positions en pixels CSS, vitesses en pixels par seconde. */
export interface FieldNode {
  x: number;
  y: number;
  vx: number;
  vy: number;
  /** Rayon en pixels CSS. Varié d'un point à l'autre, sinon le champ se lit comme une grille. */
  radius: number;
}

export interface FieldOptions {
  /** Un point pour ce nombre de pixels² — la densité est une surface, pas un compte. */
  readonly pixelsPerNode: number;
  /** Bornes dures du compte, dans les deux sens. */
  readonly minNodes: number;
  readonly maxNodes: number;
  /** Vitesse de dérive, en pixels par seconde. */
  readonly speed: number;
  readonly minRadius: number;
  readonly maxRadius: number;
}

/**
 * Les valeurs par défaut, et pourquoi elles sont si basses.
 *
 * Cette page est ouverte des heures : c'est un écran de supervision, pas une page d'accueil qu'on
 * traverse. Un fond y coûte son temps de calcul à chaque image, pour toujours. La densité est donc
 * dimensionnée sur ce que l'œil enregistre à peine — mesuré sur la démo en 1440×900, `pixelsPerNode`
 * à 26 000 donne 49 points — et surtout **plafonnée** : sans `maxNodes`, un écran 4K en donnerait
 * ~320, et le coût des liens croît en n², donc ×6,5 sur le compte est ×42 sur la boucle de liens.
 * Le plafond est ce qui rend le pire cas connu au lieu d'être fonction de l'écran de l'utilisateur.
 */
export const DEFAULT_FIELD_OPTIONS: FieldOptions = {
  pixelsPerNode: 26_000,
  minNodes: 12,
  maxNodes: 90,
  speed: 7,
  minRadius: 0.8,
  maxRadius: 1.9,
};

/** Au-delà de cette distance (px CSS), deux points ne sont pas reliés. */
export const LINK_DISTANCE = 132;

/**
 * Combien de points pour cette surface.
 *
 * Une surface nulle donne zéro et non `minNodes` : un hôte que rien n'a encore disposé n'est pas
 * un petit hôte, et peupler un champ de 0×0 pour le redimensionner à la première mesure est
 * exactement le clignotement que `resizeField` existe pour éviter.
 */
export function nodeCountFor(
  width: number,
  height: number,
  options: FieldOptions = DEFAULT_FIELD_OPTIONS,
): number {
  if (width <= 0 || height <= 0) return 0;
  const fromArea = Math.round((width * height) / options.pixelsPerNode);
  return Math.min(options.maxNodes, Math.max(options.minNodes, fromArea));
}

/**
 * Un champ neuf pour cette surface.
 *
 * `random` est un paramètre plutôt qu'un appel direct à `Math.random` : c'est ce qui rend la
 * fonction vérifiable — un test tire une suite connue et lit des positions, au lieu d'affirmer
 * que quelque chose a été appelé.
 */
export function createField(
  width: number,
  height: number,
  random: () => number = Math.random,
  options: FieldOptions = DEFAULT_FIELD_OPTIONS,
): FieldNode[] {
  const count = nodeCountFor(width, height, options);
  const nodes: FieldNode[] = [];
  for (let i = 0; i < count; i += 1) nodes.push(spawn(width, height, random, options));
  return nodes;
}

function spawn(width: number, height: number, random: () => number, options: FieldOptions): FieldNode {
  // Direction tirée sur le cercle puis mise à l'échelle : tirer `vx` et `vy` indépendamment
  // donnerait un champ deux fois plus rapide en diagonale qu'à l'horizontale, ce qui se voit.
  const angle = random() * Math.PI * 2;
  return {
    x: random() * width,
    y: random() * height,
    vx: Math.cos(angle) * options.speed,
    vy: Math.sin(angle) * options.speed,
    radius: options.minRadius + random() * (options.maxRadius - options.minRadius),
  };
}

/**
 * Avance le champ de `dtSeconds`, en place.
 *
 * En place, et c'est le seul endroit de ce module qui mute : la boucle d'animation appelle ceci
 * à chaque image, et allouer un tableau de 90 objets soixante fois par seconde pendant des heures
 * est un coût qu'on paierait pour rien.
 *
 * Les bords **enroulent** au lieu de rebondir. Un rebond rend les bords lisibles — les points s'y
 * accumulent et le cadre du fond apparaît, alors que ce calque est justement censé n'avoir pas de
 * cadre. L'enroulement se fait sur une marge, sinon un point disparaîtrait d'un bord et
 * réapparaîtrait net à l'autre au milieu d'un lien.
 */
export function advanceField(
  nodes: FieldNode[],
  width: number,
  height: number,
  dtSeconds: number,
): void {
  if (width <= 0 || height <= 0) return;
  const margin = LINK_DISTANCE / 2;
  for (const node of nodes) {
    node.x += node.vx * dtSeconds;
    node.y += node.vy * dtSeconds;
    if (node.x < -margin) node.x = width + margin;
    else if (node.x > width + margin) node.x = -margin;
    if (node.y < -margin) node.y = height + margin;
    else if (node.y > height + margin) node.y = -margin;
  }
}

/**
 * L'opacité du lien entre deux points, ou 0 quand il n'y en a pas.
 *
 * Comparée au **carré** de la distance : la racine est le coût dominant d'une boucle en n², et
 * elle ne sert qu'à retomber sur une valeur qu'on module ensuite. Le profil est quadratique
 * (`1 - d²/D²`) plutôt que linéaire, ce qui éteint le lien plus tôt et laisse le champ respirer
 * au lieu de rendre un maillage plein.
 */
export function linkAlpha(dx: number, dy: number, maxDistance: number = LINK_DISTANCE): number {
  const distanceSq = dx * dx + dy * dy;
  const maxSq = maxDistance * maxDistance;
  if (distanceSq >= maxSq) return 0;
  return 1 - distanceSq / maxSq;
}

/**
 * Adapte un champ existant à une nouvelle surface, au lieu d'en recréer un.
 *
 * C'est la fonction qui justifie ce module à elle seule. Le réflexe — rappeler `createField` sur
 * `resize` — refait tirer chaque position : le fond *saute* à chaque image d'un redimensionnement,
 * et un redimensionnement en produit des dizaines. Pire, ce n'est jamais visible en développement,
 * où on redimensionne rarement, et systématique chez qui range deux fenêtres côte à côte.
 *
 * Les positions sont donc mises à l'échelle proportionnellement — le champ suit la fenêtre — et
 * seul l'*écart* de compte est comblé : on retire à la fin, on ajoute des points neufs.
 */
export function resizeField(
  nodes: FieldNode[],
  previous: { width: number; height: number },
  next: { width: number; height: number },
  random: () => number = Math.random,
  options: FieldOptions = DEFAULT_FIELD_OPTIONS,
): FieldNode[] {
  const target = nodeCountFor(next.width, next.height, options);
  if (target === 0) return [];

  // Une surface précédente nulle n'a pas d'échelle : c'est la première mesure, pas un
  // redimensionnement. Le facteur 1 laisse alors les positions telles quelles plutôt que de les
  // multiplier par l'infini.
  const scaleX = previous.width > 0 ? next.width / previous.width : 1;
  const scaleY = previous.height > 0 ? next.height / previous.height : 1;

  const resized = nodes.slice(0, target).map(node => ({
    ...node,
    x: node.x * scaleX,
    y: node.y * scaleY,
  }));
  while (resized.length < target) resized.push(spawn(next.width, next.height, random, options));
  return resized;
}
