// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

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
import { formatSql } from './pages/queryWorkbenchLogic';
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

/**
 * The editor in the application's own colours. `vs-dark` paints a neutral grey (#1e1e1e) inside a
 * blue-black shell; this takes the surface tokens of `tailwind.config.js` so the editor reads as
 * part of the page. Pages ask for it by name: `theme="kse-dark"`.
 */
export const KSE_THEME = 'kse-dark';
monaco.editor.defineTheme(KSE_THEME, {
  base: 'vs-dark',
  inherit: true,
  rules: [],
  colors: {
    'editor.background': '#0b0d10',
    'editor.foreground': '#e8eaf0',
    'editorLineNumber.foreground': '#4d566a',
    'editorLineNumber.activeForeground': '#9aa3b2',
    'editor.lineHighlightBackground': '#12151a',
    'editor.lineHighlightBorder': '#12151a',
    'editor.selectionBackground': '#a3adff33',
    'editor.inactiveSelectionBackground': '#a3adff1f',
    'editorCursor.foreground': '#a3adff',
    'editorGutter.background': '#0b0d10',
    'editorWidget.background': '#191d24',
    'editorWidget.border': '#2a303b',
    'editorSuggestWidget.background': '#191d24',
    'editorSuggestWidget.border': '#2a303b',
    'editorSuggestWidget.selectedBackground': '#262c36',
    'scrollbarSlider.background': '#2a303b99',
    'scrollbarSlider.hoverBackground': '#3a4252cc',
    'minimap.background': '#0b0d10',
  },
});

/**
 * Formateur SQL, enregistré **ici** et non dans une page.
 *
 * Monaco n'embarque aucun formateur pour ce langage : il fournit une coloration Monarch et rien
 * d'autre (`monaco-editor/esm/vs/languages/definitions/sql/` ne contient que `sql.js` et
 * `register.js`). Sans fournisseur, `editor.action.formatDocument` — donc le bouton « Format » et
 * `Shift+Alt+F` — ne reformate rien et se contente d'un discret « There is no formatter for 'sql'
 * files installed » dans l'éditeur.
 *
 * Le fournisseur est global à l'instance Monaco, mais il était enregistré depuis un effet de
 * `QueryWorkbench`, donc *disposé au démontage de la page*. Les trois autres éditeurs SQL de
 * l'application — les deux de `Metrics`, celui de `TopicExplorer` — n'en avaient donc jamais :
 * même symptôme, aux mêmes endroits, pour une correction qui se croyait terminée. Ce module est
 * importé par chacune de ces pages et évalué une fois, ce qui est exactement la portée voulue.
 */
monaco.languages.registerDocumentFormattingEditProvider('sql', {
  provideDocumentFormattingEdits: (model) => [{
    range: model.getFullModelRange(),
    text: formatSql(model.getValue()),
  }],
});
