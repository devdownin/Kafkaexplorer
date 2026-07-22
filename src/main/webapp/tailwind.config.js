/** @type {import('tailwindcss').Config} */

/* ═══════════════════════════════════════════════════════════════════════════
   Kafka Explorer — Design System (aligné sur Spectra)
   Thème sombre premium : neutres profonds légèrement froids + accent indigo,
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
        "background": "#0b0d10",
        "surface": "#0b0d10",
        "surface-dim": "#08090c",
        "surface-bright": "#262c36",
        "surface-container-lowest": "#06070a",
        "surface-container-low": "#0e1114",
        "surface-container": "#12151a",
        "surface-container-high": "#191d24",
        "surface-container-highest": "#21262f",
        "surface-variant": "#1c2129",

        "on-background": "#e8eaf0",
        "on-surface": "#e8eaf0",
        "on-surface-variant": "#9aa3b2",
        "outline": "#79839a",
        "outline-variant": "#2a303b",

        "inverse-surface": "#eef1f6",
        "inverse-on-surface": "#1a1d23",
        "inverse-primary": "#4653c6",
        "surface-tint": "#a3adff",

        /* ── Primaire : indigo doux (accent de marque) ── */
        "primary": "#a3adff",
        "on-primary": "#141833",
        "primary-container": "#272e55",
        "on-primary-container": "#c9d0ff",
        "primary-dim": "#8892f5",
        "primary-fixed": "#b6bdff",
        "primary-fixed-dim": "#8892f5",
        "on-primary-fixed": "#141833",
        "on-primary-fixed-variant": "#272e55",

        /* ── Secondaire : violet doux ── */
        "secondary": "#c9a9f7",
        "on-secondary": "#2c1849",
        "secondary-container": "#3b2762",
        "on-secondary-container": "#ead9ff",
        "secondary-dim": "#b78df2",
        "secondary-fixed": "#ead9ff",
        "secondary-fixed-dim": "#c9a9f7",
        "on-secondary-fixed": "#2c1849",
        "on-secondary-fixed-variant": "#3b2762",

        /* ── Tertiaire : neutre clair ── */
        "tertiary": "#dfe4ee",
        "on-tertiary": "#454e5e",
        "tertiary-container": "#262c36",
        "on-tertiary-container": "#c4cbd8",
        "tertiary-dim": "#b9c1d1",

        /* ── États sémantiques ── */
        "error": "#f58c8c",
        "on-error": "#3b0a0a",
        "error-container": "#58151b",
        "on-error-container": "#ffc2bd",
        "error-dim": "#e06c6c",

        "success": "#6ee7a0",
        "on-success": "#08301a",
        "success-container": "#14402a",
        "on-success-container": "#b7f5cf",

        "warning": "#f5c264",
        "on-warning": "#3d2a05",
        "warning-container": "#4a3a12",
        "on-warning-container": "#ffe3a8",

        /* ── Alias (compat shadcn-style) ── */
        "foreground": "#e8eaf0",
        "muted-foreground": "#9aa3b2",
        "muted": "#191d24",
        "border": "#2a303b",
        "primary-foreground": "#141833",
        "card": "#12151a",

        /* ── Anciens tokens re-mappés (reskin des pages historiques) ── */
        "background-dark": "#0b0d10",
        "background-light": "#f5f7fb",
        "neutral-dark": "#12151a",
        "border-dark": "#2a303b",
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
