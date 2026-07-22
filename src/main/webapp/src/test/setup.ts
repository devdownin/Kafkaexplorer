import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Sans `globals: true`, l'auto-cleanup de @testing-library/react n'est pas
// enregistré : le DOM s'accumulerait d'un test à l'autre. On démonte
// explicitement après chaque test.
afterEach(cleanup);
