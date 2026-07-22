/* Configuration ESLint (format eslintrc — le script `lint` utilise `--ext`).
   Cible : React 19 + TypeScript + Vite. Vise à détecter les vrais défauts
   (variables inutilisées, dépendances de hooks) sans bruit stylistique. */
module.exports = {
  root: true,
  env: { browser: true, es2021: true },
  parser: '@typescript-eslint/parser',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } },
  plugins: ['@typescript-eslint', 'react-hooks', 'react-refresh'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: [
    'dist',
    'node_modules',
    'src/main/resources',
    '*.cjs',
    'tailwind.config.js',
    'postcss.config.js',
    'vite.config.ts',
  ],
  rules: {
    // Composants + hook/provider co-localisés (Toast, ConfirmDialog, Field…) :
    // motif volontaire du design system, pas un problème de HMR.
    'react-refresh/only-export-components': 'off',
    // `any` ponctuel toléré (payloads dynamiques Kafka/Flink).
    '@typescript-eslint/no-explicit-any': 'off',
    // Variables/args inutilisés = erreur, sauf préfixe `_` (ignore volontaire).
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
  },
};
