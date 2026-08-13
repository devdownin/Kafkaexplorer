import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Sans `globals: true`, l'auto-cleanup de @testing-library/react n'est pas
// enregistré : le DOM s'accumulerait d'un test à l'autre. On démonte
// explicitement après chaque test.
afterEach(cleanup);

// jsdom n'implémente aucune mise en page, donc pas `scrollIntoView` : tout composant qui garde
// son option surlignée visible (Combobox, CommandPalette, la table de StreamFlow) plante sur un
// `is not a function` qui ne dit rien du test. Un no-op suffit — il n'y a rien à faire défiler.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {};
}
