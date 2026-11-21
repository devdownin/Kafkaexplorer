# Spatial light and dark themes

Kafka Explorer uses two application-wide color schemes, selected from the floating header. The choice is persisted under `kse.theme` in browser storage; without a saved preference the initial scheme follows `prefers-color-scheme`. An inline head script applies the scheme before the first paint.

## Architecture

- `src/spatial-tokens.css`: 65 shared semantic RGB color tokens for each scheme, spatial materials, scene, depth, radii and blur values.
- `tailwind.config.js`: existing color utility names now resolve to runtime CSS variables; opacity modifiers (e.g. `bg-primary/10`) remain supported.
- `src/index.css`: context-specific spatial surfaces (sidebar, header, cards, dialog, inputs), motion, fallbacks and mobile blur limits.
- `src/components/ui/Spatial.tsx`: `GlassSurface`, `GlassCard`, `GlassPanel`, `GlassSidebar`, `GlassHeader`, `GlassPopover`, `GlassModal`, `FloatingToolbar`, `SpatialButton`, and `SpatialInput` with `thin/regular/thick` and `flat/raised/floating` variants.
- `src/components/Layout.tsx` + `Header.tsx`: accessible theme switch, detached navigation, search and header surfaces.
- `src/components/ThemedEditor.tsx` + `monaco-setup.ts`: distinct light and dark Monaco palettes, including editors embedded in Metrics and Topic Explorer.

## UX decisions

Dense tables and SQL editors stay opaque (Material 0). Heavy backdrop blur is restricted to large parent containers and overlays. Sidebar uses regular glass, header uses thin glass, command palette and confirmation dialogs use thick glass. The light scene uses subdued blue/violet ambient light; the dark scene retains the existing indigo identity. No business logic, Kafka requests, or forecast calculations change.

## Accessibility and performance

Focus outlines and semantic text colors are retained; background and text color channels are separately tuned for contrast. Reduced-motion disables decorative transitions. Mobile uses lower blur radii. A CSS feature query supplies an almost opaque background where `backdrop-filter` is not supported. Monaco is bundled locally as before.

## Review checklist

1. Navigate Dashboard, Topics, SQL editor, Metrics, Forecast, and Settings in both themes.
2. Verify charts, long tables, SQL editor completion popovers, overlays and keyboard navigation.
3. Check 375px/768px/1024px/1440px widths, keyboard focus and reduced-motion.
4. Confirm background/foreground and status contrast using automated WCAG measurements.

## Next iteration

Audit page-specific hard-coded colors in non-shared visualizations (especially SVG, status maps, chart series, and legacy syntax previews) and add screenshot-based light/dark visual regression checks. Consider a Bento composition for the Dashboard without changing information density in operational screens.
