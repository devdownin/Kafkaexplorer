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
 * Importé une seule fois, avant le premier rendu (voir main.tsx).
 */
import * as monaco from 'monaco-editor';
import { loader } from '@monaco-editor/react';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

// Fournit le worker de l'éditeur depuis le bundle plutôt que depuis un CDN.
self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker();
  },
};

loader.config({ monaco });
