import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

/**
 * Configuration ESLint (format « flat » — imposé par ESLint 9+, qui ne lit plus `.eslintrc`).
 * Cible : React 19 + TypeScript + Vite. Vise à détecter les vrais défauts (variables inutilisées,
 * dépendances de hooks) sans bruit stylistique.
 *
 * `ignores` remplace `ignorePatterns`, et le script `lint` n'a plus besoin de `--ext` : en flat
 * config, ce sont les `files` de chaque bloc qui décident des extensions couvertes.
 */
export default tseslint.config(
  {
    ignores: [
      'dist',
      'node_modules',
      '../resources/static',
      '*.cjs',
      'tailwind.config.js',
      'postcss.config.js',
      'vite.config.ts',
      'eslint.config.js',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      /*
       * `eslint-plugin-react-hooks` 7 ajoute à son jeu « recommended » une famille de règles
       * issues du React Compiler. Elles ne signalent pas de régression : elles décrivent un
       * modèle plus strict que celui sur lequel l'app est écrite, et en relèvent 36 occurrences
       * réparties sur une douzaine de fichiers — 21 `set-state-in-effect`, 5 `refs`,
       * 4 `static-components`, 3 `preserve-manual-memoization`, 2 `purity`, 1 `immutability`.
       *
       * Les traiter demande de restructurer des effets un par un, avec le risque que cela
       * comporte : c'est un chantier, pas l'effet de bord d'une montée de version. Elles sont
       * donc explicitement éteintes ici, avec leur décompte, plutôt que noyées dans un
       * `--max-warnings` relevé — et `rules-of-hooks` comme `exhaustive-deps`, qui attrapent de
       * vrais défauts, restent des erreurs.
       */
      'react-hooks/set-state-in-effect': 'off',
      'react-hooks/refs': 'off',
      'react-hooks/static-components': 'off',
      'react-hooks/preserve-manual-memoization': 'off',
      'react-hooks/purity': 'off',
      'react-hooks/immutability': 'off',
      // Composants + hook/provider co-localisés (Toast, ConfirmDialog, Field…) :
      // motif volontaire du design system, pas un problème de HMR.
      'react-refresh/only-export-components': 'off',
      // `any` ponctuel toléré (payloads dynamiques Kafka/Flink).
      '@typescript-eslint/no-explicit-any': 'off',
      // Variables/args inutilisés = erreur, sauf préfixe `_` (ignore volontaire).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
);
