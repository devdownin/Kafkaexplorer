# Spatial light and dark themes

Kafka Explorer uses two application-wide color schemes, selected from the floating header. The choice is persisted under `kse.theme` in browser storage; without a saved preference the initial scheme follows `prefers-color-scheme`. An inline head script applies the scheme before the first paint.

## Architecture

- `src/spatial-tokens.css`: 65 shared semantic RGB color tokens for each scheme, spatial materials, scene, depth, radii and blur values.
- `tailwind.config.js`: existing color utility names now resolve to runtime CSS variables; opacity modifiers such as the Tailwind primary color at ten-percent opacity remain supported.
- `src/index.css`: context-specific spatial surfaces (sidebar, header, cards, dialog, inputs), motion, fallbacks and mobile blur limits.
- `src/components/ui/Spatial.tsx`: `GlassSurface`, `GlassCard`, `GlassPanel`, `GlassSidebar`, `GlassHeader`, `GlassPopover`, `GlassModal`, `FloatingToolbar`, `SpatialButton`, and `SpatialInput` with three material variants (thin, regular, thick) and three elevation variants (flat, raised, floating).
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

## Measured chart text contrast

The visualization palette distinguishes observations, predictions, thresholds and previous predictions. Foreground contrast was calculated using WCAG relative luminance against the chart tooltip surfaces; representative values are:

| Series / UI text | Light on #ffffff | Dark on #191d24 |
|---|---:|---:|
| Observed | 6.72:1 | 10.95:1 |
| Warning | 6.46:1 | 10.29:1 |
| Critical | 6.51:1 | 7.25:1 |
| Forecast | 5.28:1 | 8.71:1 |
| Previous forecast | 7.37:1 | 8.45:1 |
| Axis and metadata | 6.95:1 | 6.64:1 |

These samples meet WCAG AA for ordinary text against their tested opaque reference surfaces. Alpha-blended strokes, diminished labels, overlapping lines and transparent regions require separate visual verification. Chart series remain distinguishable through dashes and labels, rather than color alone.

## Third-pass optical refinement

The material system now separates **the backdrop** (static ambient gradients),
**the reading plane** (opaque cards, SQL editors, tables) and **the floating
planes** (header, sidebar, command palette, confirmations). The individual
navigation links, chart series and table cells do not receive their own
backdrop filters.

The light theme uses a secondary, low-opacity inside rim so white panels
retain their silhouettes against the pale scene. The dark theme uses an even
weaker internal reflection to avoid glossy, high-chroma surfaces. Each shared
glass primitive now has a functional elevation policy: flat for embedded
surfaces, raised for the usual context panel, floating for dialogs. The
palette defines separate optical-edge, reflection, selected, input and control
tokens for each theme.

### Interaction and contrast decisions

- Primary hover uses a darker accent, retaining contrast with its foreground
  instead of switching to the pale fixed-primary token.
- The header search trigger is a lightweight capsule with no dedicated blur;
  the floating palette retains thick glass and a more clearly separated
  result selection.
- Active navigation uses a tonal fill and an inset edge rather than a saturated
  block. Hover motion is limited to devices supporting precise pointers.
- Inputs read as inset surfaces, not nested floating cards; invalid fields keep
  a separate semantic border.
- Sidebar drawer extends to the viewport edge on mobile, and desktop spacing
  still starts at the existing 1024px breakpoint.

### Rendering and accessibility safeguards

CSS only applies backdrop blur to the sidebar, header and overlays; it is not
added to cards or repeated controls. Below tablet width the blur radius and
saturation decrease. Reduced transparency makes floating materials opaque;
forced-colors removes optical shadows and preserves explicit borders.
Reduced-motion removes visual movement without changing functionality.

Review light/dark screenshots at narrow phone, tablet and desktop sizes,
including theme switching with an open search panel, selected navigation and
disabled controls. Verify actual composited contrast and measure scroll GPU
cost in a browser performance profile before classifying the design as fully
visually validated.
