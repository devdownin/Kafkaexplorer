/**
 * Monaco auto-hébergé — supprime la dépendance au CDN jsdelivr.
 *
 * Par défaut, `@monaco-editor/loader` télécharge Monaco depuis
 * `cdn.jsdelivr.net` au premier montage d'un éditeur : hors-ligne ou derrière
 * un proxy strict, l'éditeur SQL (QueryWorkbench, Metrics, TopicExplorer) reste
 * bloqué sur « Loading… ». On bundle Monaco localement via Vite et on pointe le
 * loader dessus. Le worker de l'éditeur est également fourni en local
 * (`?worker` de Vite) — l'éditeur SQL n'a pas besoin des workers de langage
 * TS/JSON/CSS/HTML.
 *
 * Importé statiquement par chaque page qui monte un éditeur (QueryWorkbench,
 * Metrics, TopicExplorer) — PAS par main.tsx : ces pages étant lazy-loadées,
 * Monaco (3,9 Mo) n'est téléchargé qu'à la première visite d'une de ces routes,
 * jamais sur le Dashboard. L'évaluation du module (donc `loader.config`) a lieu
 * pendant la phase d'import du chunk de page, avant tout montage d'éditeur.
 * `manualChunks` (vite.config.ts) garde monaco-editor dans son propre chunk
 * `monaco`, partagé et mis en cache entre les trois pages.
 */
import * as monaco from 'monaco-editor';
import { loader } from '@monaco-editor/react';
// `monaco-editor/editor/…`, et non `monaco-editor/esm/vs/editor/…` : la 0.56 déclare un champ
// `exports` (`"./*": "./esm/vs/*.js"`) qui enracine les sous-chemins sur `esm/vs`. L'ancien chemin
// s'y résout en `esm/vs/esm/vs/…`, qui n'existe pas — Rollup ne trouvait plus le worker et la
// compilation de production échouait, alors que `tsc` et Vitest, eux, passaient.
import editorWorker from 'monaco-editor/editor/editor.worker?worker';

// Fournit le worker de l'éditeur depuis le bundle plutôt que depuis un CDN.
self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker();
  },
};

loader.config({ monaco });
