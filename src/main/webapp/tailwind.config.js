/** @type {import('tailwindcss').Config} */

/* ═══════════════════════════════════════════════════════════════════════════
   Kafka Explorer — Design System (aligné sur Spectra)
   Thèmes adaptatifs clair/sombre : tokens RGB sémantiques + accent indigo,
   violet en secondaire. Les tokens (nommage Material-like) sont partagés avec
   Spectra : changer une valeur ici rethème toute l'application.

   Les anciens tokens « terminal » (primary cyan, background-dark teal,
   neutral-dark, border-dark) sont RE-MAPPÉS vers les valeurs premium afin que
   les pages historiques soient reskinnées sans réécriture — zéro changement
   de logique métier.
   ═══════════════════════════════════════════════════════════════════════════ */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        /* ── Neutres (fond → surfaces empilées) ── */
        "background": "rgb(var(--kse-background) / <alpha-value>)",
        "surface": "rgb(var(--kse-surface) / <alpha-value>)",
        "surface-dim": "rgb(var(--kse-surface-dim) / <alpha-value>)",
        "surface-bright": "rgb(var(--kse-surface-bright) / <alpha-value>)",
        "surface-container-lowest": "rgb(var(--kse-surface-container-lowest) / <alpha-value>)",
        "surface-container-low": "rgb(var(--kse-surface-container-low) / <alpha-value>)",
        "surface-container": "rgb(var(--kse-surface-container) / <alpha-value>)",
        "surface-container-high": "rgb(var(--kse-surface-container-high) / <alpha-value>)",
        "surface-container-highest": "rgb(var(--kse-surface-container-highest) / <alpha-value>)",
        "surface-variant": "rgb(var(--kse-surface-variant) / <alpha-value>)",

        "on-background": "rgb(var(--kse-on-background) / <alpha-value>)",
        "on-surface": "rgb(var(--kse-on-surface) / <alpha-value>)",
        "on-surface-variant": "rgb(var(--kse-on-surface-variant) / <alpha-value>)",
        "outline": "rgb(var(--kse-outline) / <alpha-value>)",
        "outline-variant": "rgb(var(--kse-outline-variant) / <alpha-value>)",

        "inverse-surface": "rgb(var(--kse-inverse-surface) / <alpha-value>)",
        "inverse-on-surface": "rgb(var(--kse-inverse-on-surface) / <alpha-value>)",
        "inverse-primary": "rgb(var(--kse-inverse-primary) / <alpha-value>)",
        "surface-tint": "rgb(var(--kse-surface-tint) / <alpha-value>)",

        /* ── Primaire : indigo doux (accent de marque) ── */
        "primary": "rgb(var(--kse-primary) / <alpha-value>)",
        "on-primary": "rgb(var(--kse-on-primary) / <alpha-value>)",
        "primary-container": "rgb(var(--kse-primary-container) / <alpha-value>)",
        "on-primary-container": "rgb(var(--kse-on-primary-container) / <alpha-value>)",
        "primary-dim": "rgb(var(--kse-primary-dim) / <alpha-value>)",
        "primary-fixed": "rgb(var(--kse-primary-fixed) / <alpha-value>)",
        "primary-fixed-dim": "rgb(var(--kse-primary-fixed-dim) / <alpha-value>)",
        "on-primary-fixed": "rgb(var(--kse-on-primary-fixed) / <alpha-value>)",
        "on-primary-fixed-variant": "rgb(var(--kse-on-primary-fixed-variant) / <alpha-value>)",

        /* ── Secondaire : violet doux ── */
        "secondary": "rgb(var(--kse-secondary) / <alpha-value>)",
        "on-secondary": "rgb(var(--kse-on-secondary) / <alpha-value>)",
        "secondary-container": "rgb(var(--kse-secondary-container) / <alpha-value>)",
        "on-secondary-container": "rgb(var(--kse-on-secondary-container) / <alpha-value>)",
        "secondary-dim": "rgb(var(--kse-secondary-dim) / <alpha-value>)",
        "secondary-fixed": "rgb(var(--kse-secondary-fixed) / <alpha-value>)",
        "secondary-fixed-dim": "rgb(var(--kse-secondary-fixed-dim) / <alpha-value>)",
        "on-secondary-fixed": "rgb(var(--kse-on-secondary-fixed) / <alpha-value>)",
        "on-secondary-fixed-variant": "rgb(var(--kse-on-secondary-fixed-variant) / <alpha-value>)",

        /* ── Tertiaire : neutre clair ── */
        "tertiary": "rgb(var(--kse-tertiary) / <alpha-value>)",
        "on-tertiary": "rgb(var(--kse-on-tertiary) / <alpha-value>)",
        "tertiary-container": "rgb(var(--kse-tertiary-container) / <alpha-value>)",
        "on-tertiary-container": "rgb(var(--kse-on-tertiary-container) / <alpha-value>)",
        "tertiary-dim": "rgb(var(--kse-tertiary-dim) / <alpha-value>)",

        /* ── États sémantiques ── */
        "error": "rgb(var(--kse-error) / <alpha-value>)",
        "on-error": "rgb(var(--kse-on-error) / <alpha-value>)",
        "error-container": "rgb(var(--kse-error-container) / <alpha-value>)",
        "on-error-container": "rgb(var(--kse-on-error-container) / <alpha-value>)",
        "error-dim": "rgb(var(--kse-error-dim) / <alpha-value>)",

        "success": "rgb(var(--kse-success) / <alpha-value>)",
        "on-success": "rgb(var(--kse-on-success) / <alpha-value>)",
        "success-container": "rgb(var(--kse-success-container) / <alpha-value>)",
        "on-success-container": "rgb(var(--kse-on-success-container) / <alpha-value>)",

        "warning": "rgb(var(--kse-warning) / <alpha-value>)",
        "on-warning": "rgb(var(--kse-on-warning) / <alpha-value>)",
        "warning-container": "rgb(var(--kse-warning-container) / <alpha-value>)",
        "on-warning-container": "rgb(var(--kse-on-warning-container) / <alpha-value>)",

        /* ── Alias (compat shadcn-style) ── */
        "foreground": "rgb(var(--kse-foreground) / <alpha-value>)",
        "muted-foreground": "rgb(var(--kse-muted-foreground) / <alpha-value>)",
        "muted": "rgb(var(--kse-muted) / <alpha-value>)",
        "border": "rgb(var(--kse-border) / <alpha-value>)",
        "primary-foreground": "rgb(var(--kse-primary-foreground) / <alpha-value>)",
        "card": "rgb(var(--kse-card) / <alpha-value>)",

        /* ── Anciens tokens re-mappés (reskin des pages historiques) ── */
        "background-dark": "rgb(var(--kse-background-dark) / <alpha-value>)",
        "background-light": "rgb(var(--kse-background-light) / <alpha-value>)",
        "neutral-dark": "rgb(var(--kse-neutral-dark) / <alpha-value>)",
        "border-dark": "rgb(var(--kse-border-dark) / <alpha-value>)",
      },
      fontFamily: {
        "display": ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        "headline": ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        "body": ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        "label": ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        "mono": ["JetBrains Mono", "ui-monospace", "monospace"],
      },
      borderRadius: {
        "sm": "6px",
        "DEFAULT": "8px",
        "md": "8px",
        "lg": "12px",
        "xl": "16px",
        "2xl": "20px",
        "3xl": "24px",
        "full": "9999px",
      },
      keyframes: {
        "progress-fast": {
          "0%": { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(100%)" },
        },
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        "slide-up": {
          from: { opacity: "0", transform: "translateY(6px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "progress-fast": "progress-fast 1s linear infinite",
        "fade-in": "fade-in 0.2s ease-out",
        "slide-up": "slide-up 0.25s ease-out",
      },
    },
  },
  plugins: [],
}
