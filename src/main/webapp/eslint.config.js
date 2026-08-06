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
       * Les règles issues du React Compiler sont actives. Ce qu'elles décrivaient a été corrigé
       * là où c'était corrigeable — composants hissés hors du rendu, état dérivé au lieu d'être
       * rattrapé par un effet, refs écrites dans un effet, rendu rendu pur. Restent les effets
       * de chargement, de mesure et d'abonnement : ils finissent forcément par poser un état, et
       * seule une bibliothèque de données ou Suspense les en dispenserait. Chacun porte donc une
       * exception nominative à son point d'appel, ce que `--report-unused-disable-directives`
       * garde honnête : une exception devenue inutile fait échouer le lint.
       */
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
