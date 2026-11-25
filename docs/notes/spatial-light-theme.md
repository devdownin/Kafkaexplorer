# Spatial light and dark themes

Kafka Explorer uses two application-wide color schemes. **Light is the initial default**, regardless of the operating-system preference. The floating header has an accessible **Dark mode** switch (`src/theme.ts`) that changes to dark immediately and back to light without cycling through an intermediate system mode. Only the dark override is persisted under `kse.theme`; switching back to light clears the key. An inline head script applies the selected scheme and the `theme-color` meta before the first paint.

## Architecture

- `src/spatial-tokens.css`: 65 shared semantic RGB color tokens for each scheme, spatial materials, scene, depth, radii and blur values.
- `tailwind.config.js`: existing color utility names now resolve to runtime CSS variables; opacity modifiers such as the Tailwind primary color at ten-percent opacity remain supported.
- `src/index.css`: context-specific spatial surfaces (sidebar, header, cards, dialog, inputs), motion, fallbacks and mobile blur limits.
- `src/components/ui/Spatial.tsx`: `GlassSurface`, `GlassPopover` and `GlassModal` — glass for layer 3 only (dialogs, command palette, the Data Model edge tooltip), three materials (thin, regular, thick) and three elevations (flat, raised, floating).
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

## Fourth pass — Lots A and B

### Lot A — Visual consistency and forecast legibility

- All pages using the shared `PageHeader` now get a wrapping title/action
  layout without forcing a fixed-width button group on phone screens.
- `Card`, `Stat`, `Table`, and `EmptyState` share opaque reading
  surfaces, a toned border and a consistent type scale. Data tables remain
  scrollable horizontally and table-head cells stay opaque while scrolling.
- Dashboard diagnostics use a distinct inset surface instead of making a
  second layer of translucent panels.
- Metrics summaries stack in two columns up to the desktop breakpoint,
  avoiding four tiny 175px tiles on tablets.
- The forecast chart is separated into a quiet opaque plot well; chart axes,
  crosshair, legend and uncertainty band have distinct semantics. Observed
  stays solid and predicted stays dashed; the transition is explicitly labeled.
- Query Workbench uses an opaque editor/results plane. Data Model uses
  theme-matched canvas and mobile controls without fixed dark overlays.

### Lot B — Responsive UX, accessibility and rendering

- At narrow widths page actions wrap below headings, while Data Model's
  graph controls move away from the viewport corners.
- Existing navigation and control motion is limited to brief hover states and
  respects reduced-motion. The reading surfaces add no per-card blur.
- Reduced-contrast/forced-colors fallbacks now include KPI tiles, data tables,
  empty-state icons and forecast chart wells.
- The Metrics ambient canvas resolves its `--kse-primary` color at start and
  on theme change, without reading computed styles on every animation frame.
  Its existing 30fps and DPR caps, visibility pause and reduced-motion handling
  are retained. The decorative layer remains opt-in.
- The shared data table header and SQL result header are opaque: there is
  no recurring per-scroll backdrop filter over thousands of rows.

### Validation contract

The UI changes should satisfy:

1. Light/dark at widths 375, 768, 1024, 1440 CSS px without unexpected
   horizontal document scroll, clipping of primary actions or inaccessible
   selection.
2. Header/Sidebar/search popovers keep readable contrast over any page.
3. Forecast shows observed, predicted, uncertainty and threshold with
   distinct line styles and a keyboard-accessible data table.
4. Data Model controls remain reachable and nodes readable; Query editor
   retains usable width at the existing 1024px desktop threshold.
5. Keyboard navigation, system contrast modes and reduced-motion work.
6. Performance profiling confirms no repetitive expensive backdrop filters
   on table rows or charts.

The repository already provides the Playwright fixture utilities
`docs/screenshots/capture.mjs` and `docs/screenshots/layout-probe.mjs`
for visual/viewport validation; actual cross-theme screenshots and GPU
measurements must be performed in a browser. Successful typecheck, lint
and unit tests are necessary but are not a substitute for those checks.

### Reproducible cross-theme probe

Run `node docs/screenshots/theme-probe.mjs http://127.0.0.1:4173 /tmp/kse-theme-shots` after starting the stub-backed SPA via the documented screenshot harness. It tests 32 combinations (Dashboard, Metrics, Query, Data Model × light/dark × 375/768/1024/1440 px). It fails on missing theme/selector, horizontal document overflow or offscreen primary page actions, and writes screenshots when the output directory is supplied. This probe was added as an optional developer tool, not silently declared as executed in CI.

## Fifth pass — audit fixes

What the audit of the fourth pass found, and what closed it:

- **A light-only override matched nothing.** `index.css` carried three passes overriding each
  other, and the rule meant to give the pages' `ring-1 ring-white/[0.045]` cards a border in
  light was escaped twice (a doubled backslash before each special character), so 24 cards on Cluster, Config, Topic Explorer, Compare,
  Help, Dead Letter and the metric cards drew a white ring on white — 1.0:1, no outline. The file
  is now **one pass, one definition per selector, and no `:root[data-theme]` rule**: a theme
  differs by its tokens only (`--card-ring`, `--skeleton-*`, `--scene-glow`,
  `--scroll-thumb-hover`), so an override cannot silently miss again.
- **Dark-only literals.** `FlowMetricDrawer` wrote its error/risk/success lines in
  `#f58c8c` / `#f5c264` / `#8fd6a3` (1.6–2.3:1 on white); they use `text-error` / `-warning` /
  `-success`. The black and white alpha fills on Metrics and Process Mining went to surface tokens.
- **Former system appearance behavior.** An earlier version persisted the OS default on mount and froze it. The intermediate three-state selector subsequently followed the OS live; it has since been replaced by a simpler light-default, two-state switch.
- **Input borders at ≥ 3:1** (WCAG 1.4.11): `--glass-input-border` is 3.3:1 in both schemes.
  The header search capsule keeps the lighter `--glass-control-border` — it is labelled by its
  text and icon. Card borders were raised (1.16 → ~1.3:1) but deliberately not to 3:1: a card is
  not a control, 1.4.11 does not apply, and a 3:1 frame on every card is the opposite of the
  material this theme is after.
- **Typography**: SF Pro first on Apple platforms (`-apple-system`), self-hosted Inter elsewhere;
  `h1`/`h2` tracked at −0.015em.
- **Primitives**: the unused `GlassCard`, `GlassPanel`, `GlassSidebar`, `GlassHeader`,
  `FloatingToolbar`, `SpatialButton` and `SpatialInput` were removed — cards are opaque reading
  planes (`Card`), and controls are `Button` and `Field`. `ConfirmDialog`, `CommandPalette` and
  the Data Model tooltip now use `GlassModal` / `GlassPopover` instead of raw classes.
- `.glass`, `.header-border` (unused) and `background-attachment: fixed` (the shell does not
  scroll; `main` does) were dropped. Light `.nav-active` now takes the
  `--glass-selected-*` tokens its own override used to shadow.

`theme-probe.mjs` finds the switch by its accessible `Dark mode` label. It still checks layout, not
contrast: the ratios above are computed from the tokens.

## Sixth pass — material and motion

- **Switch**: iOS thumb (white, 16 px in a 20 px track, soft shadow, 200 ms overshoot spring); the
  off track is `outline` at 70 %, about 3:1 against the card in both schemes — the former tonal
  gray vanished into it.
- **Select** (`kse-select`): no native chrome, a chevron (`--select-chevron`, one per scheme) on the
  same recess as the text fields. The chevron rule sits after `.kse-input` because that class's
  `background` shorthand resets it.
- **Prominent button** (`kse-btn-prominent`, Button's primary variant and "New query"): a faint
  vertical gradient, a 1 px light rim and a shadow tinted with the accent. Forced colors fall
  back to `ButtonFace`.
- **Icons**: `wght 300`, no explicit `opsz` (the browser's automatic optical sizing matches the
  axis to the rendered size), and the current route's icon is filled (`--icon-fill`).
- **Glass rim**: `--glass-rim`, a 0.5 px outer hairline, on the sidebar, header and the glass
  materials — in light the translucent edge alone does not separate a pale panel from a pale scene.
- **Data Model nodes**: `themedTint()` paints each domain through `--dm-header-N` /
  `--dm-accent-N`. Dark keeps the original dark blocks; light uses pastel containers with dark
  text and accents darkened for strokes. Exports (SVG, PNG, Mermaid) keep the hex palette: they
  are standalone files that cannot know the page's theme.
- **Dashboard bento**: Cluster Health remains the large tile; Message Count is now a compact
  tile on the same row at desktop widths, alongside the smaller topic and Flink counts. The
  `TrendLine` now plots estimated produced-message rates from successive latest-offset snapshots,
  sampled on their own 30-second minimum cadence (and stopped with auto-refresh).
  The total retained-message count stays visible as secondary context. Fewer than two samples draw nothing — the tile says
  when the trend becomes available, rather than drawing a dash that pretends to be one. A
  constant series is a flat mid-line, not noise stretched over the range. On narrower screens,
  the two wider tiles stack to keep the values and trend readable.

## Seventh pass — motion and type

- **Page entrance**: `kse-page-enter` (180 ms fade, scale .985 → 1) on a `h-full` wrapper in `Layout`, keyed on the first path segment, so a section change replays it but a route parameter change does not remount the page. It ends on `transform: none` so no containing block outlives it for fixed descendants.
- **Hover**: `.kse-interactive` lifts by shadow alone; the 1 px translate is gone. KPI tiles (`Stat`) are not interactive and get no hover — an effect on something that cannot be clicked is a false affordance.
- **Type**: page titles 24 → 30 px with −0.02em tracking; `Stat` labels are sentence case at 12 px instead of 11 px capitals. Small caps were not used: without an `smcp` feature in the subset font the browser synthesises them at about 9 px, below the micro-text floor this stylesheet enforces. Tabular figures on the Cluster configuration values (KPI values and metric cards already had them).

## Eighth pass — focus, accents, density

- **Focus**: two rings — the solid 2 px outline (the 3:1 the criterion needs) and a 6 px halo tinted with the accent. Forced colors drop the halo and use `Highlight`.
- **Segmented control** (`components/ui/Segmented.tsx`, formerly private to the SQL editor): one pill that slides to the chosen option, measured rather than computed since labels differ in width, with the `Switch`'s overshoot easing. The first paint places it without animating. Options stay `<button aria-pressed type="button">`; the pill is `aria-hidden`.
- **Sentence case everywhere shared**: the page kicker, the sidebar sections and table headers (`Th`) follow the KPI labels. Page-local `uppercase` labels (about 140) were left alone — they are not shared components and each sits in a layout tuned around it.
- **`kse-float`**: tooltips and toasts are thick glass. They do not use `.kse-glass-*`, whose `position: relative` would override the `absolute` a tooltip positions itself with.
- **Accent** (`accent-tokens.css`, `appearance.ts`, Settings → Appearance): indigo (default), blue, teal, pink, orange, graphite, as `data-accent` on `<html>`. Each overrides primary, its container, its dim variant, the tint, the focus ring and the chart primary, per scheme. Every pair was checked at ≥ 4.5:1 (primary on page and card, text on primary, container text); the default is stored as absence. Violet and green are not offered: violet is the secondary colour and green is the success colour, so an accent there would read as a status.
- **Density** (`data-density="compact"`): table rows, `h-9` buttons and fields, and sidebar links tighten, with every target kept at or above 24 px. The rules are unlayered so they beat the utility classes the components carry.
- Both preferences are browser-only and applied by `index.html` before first paint.

## Dashboard operational-truth pass (2026-11-23)

- **Throughput** is computed from complete latest-offset snapshots by a single batched Kafka lookup: only topics seen in both reads with nondecreasing offsets contribute. The KPI shows msg/s and the number of comparable topics, not a retained-count delta. The first read, absent readings, long gaps and failed batches show unavailable instead of zero. The 30-second minimum sampling cadence prevents heavy reads every five seconds.
- **State** in the topic table now describes *record presence* and *DLT naming*, not a health verdict. The backend returns `unmeasuredTopics` from a strict all-partitions-complete count lookup so unreadable counts are distinct from a measured empty topic; the diagnostics' empty count excludes them.
- **Staleness** is explicit when a background refresh fails, the broker becomes unavailable, or the last successful snapshot exceeds the polling allowance. The previous successfully measured rows are retained during failure. An intentionally disabled timer is labelled Off, not presented as a failed refresh.

## Dashboard top producers and scoped diagnostic actions (2026-11-23)

The diagnostic tiles now navigate to meaningful filtered results instead of only scrolling to an unfiltered table. The empty shortcut uses strict all-partition count coverage; the silent shortcut freezes the set of topics measured on the visible page, avoiding a misleading claim of cluster-wide silence. Both display a removable active-filter badge and reset search/exclusion toggles before applying the filter.

A compact, responsive five-item **Top producing topics** section uses the same two latest-offset snapshots as the throughput KPI. It sorts comparable topics by observed msg/s, shows the sampling window and coverage, and links each topic to the explorer. The panel is intentionally a ranked list rather than five full-sized cards: the focus remains on quick operational navigation and predictable density in both light and dark themes.

## Ninth pass — navigation hierarchy and interaction

The sidebar's Explore / Observe / Analyze sections are now real disclosure groups rather than static labels. Each heading is a keyboard-operable button with an explicit expanded state, a controlled region, and a quiet item count. Groups remain expanded by default to preserve the established first-run navigation; operators can collapse sections that are not relevant to the current task. Navigating to a route in a section always reveals that section again, including navigation initiated from the command palette.

The header breadcrumb now carries the same group context (`Observe / Metrics`, for example) before the page title. The sidebar and header therefore share the same navigation taxonomy instead of making users infer which group a page belongs to. The active page remains the strongest visual signal; group labels and counts are secondary, and the accordion affordance is visible on hover and keyboard focus without adding another glass layer.

No route, permission, API, or domain behavior changes. The collapsed rail keeps its existing icon-only separators; group disclosure is available in the expanded navigation and mobile drawer.

## Tenth pass — command palette and work navigation

The command palette now exposes a real combobox/listbox relationship: the search field announces the result list, the active option is referenced through `aria-activedescendant`, and options are grouped by Actions, Pages, Recent topics, Topics and Tables. The empty-result message is announced as a status instead of being placed inside an empty listbox.

Keyboard navigation remains anchored in the search field. Arrow keys move the active option, Enter invokes the active command, Escape dismisses the palette, and Tab does not leak focus behind the modal. Result rows remain pointer-selectable but are removed from the tab sequence so the combobox keeps one predictable keyboard focus point. Closing/unmounting the palette restores focus to the control that opened it, including when a route action is selected.

These changes improve how the global command surface connects navigation and operational work without changing command definitions, route targets, topic/table filtering, recents persistence or Kafka behavior. The modal remains a single floating surface; no new blur or translucent reading surfaces were introduced.

## Eleventh pass — business workspace and panel relationships

Strata 3D's Overview, Focus selected and Compare presets now use the same visible selected-state treatment as the arrangement switch, rather than relying on a state attribute with no corresponding visual hierarchy. The navigator and inspector toggles are grouped as workspace-panel controls, announce their expanded state, and reference the panel they reveal while it is present. Both panels now have explicit landmark names/IDs so assistive technology and the controls describe the same destinations.

This makes the 3D scene, topic navigator and diagnostic inspector read as one workspace with three deliberate zones: spatial overview, scope/navigation and evidence/diagnostics. It does not change the topic selection, comparison, filtering, scene layout or evidence collection behavior. The existing 2XL resize handles and responsive stacking remain intact.
