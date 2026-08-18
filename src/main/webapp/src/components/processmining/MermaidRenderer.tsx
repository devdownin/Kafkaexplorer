// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useEffect, useRef, useState } from 'react';
import mermaid from 'mermaid';

let mermaidInitialized = false;
/*
 * The id mermaid stamps on the SVG it returns. Module-scoped so it stays unique across remounts —
 * a per-component counter restarts at zero, and two diagrams sharing an id is invalid markup.
 */
let svgSequence = 0;

interface MermaidRendererProps {
  chart: string;
}

const MermaidRenderer: React.FC<MermaidRendererProps> = ({ chart }) => {
  const [error, setError] = useState<string | null>(null);
  /*
   * The rendered SVG is state, not something written into a ref's node.
   *
   * It used to be assigned with `containerRef.current.innerHTML`, which forced the effect to bail
   * out when the ref was null — and after a failed render it was null for ever: the error branch
   * below replaces the container, so the next chart hit `if (!containerRef.current) return` and was
   * never drawn, while the stale error stayed on screen above it. One malformed diagram from the
   * model killed the flowchart panel for the rest of a live session, and only a reload brought it
   * back. Holding the SVG in state removes the ordering problem rather than working around it.
   */
  const [svg, setSvg] = useState<string | null>(null);
  /*
   * Which render is the current one. In live mode a new flowchart arrives with every window and
   * `mermaid.render` is async, so a slow render of window N could land after window N+1 and leave
   * the older diagram on screen — the same generation guard the trace and the topic search use.
   */
  const renderId = useRef(0);

  useEffect(() => {
    if (!chart || chart === 'NO_CHANGE') return;

    const generation = ++renderId.current;

    const renderChart = async () => {
      try {
        if (!mermaidInitialized) {
          mermaid.initialize({
            startOnLoad: false,
            // The flowchart is written by a language model, so this input is not ours. 'strict' is
            // mermaid's own default; stating it means a future default cannot quietly relax it.
            securityLevel: 'strict',
            theme: 'dark',
            themeVariables: {
              primaryColor: '#272e55',
              primaryTextColor: '#e8eaf0',
              primaryBorderColor: '#a3adff',
              lineColor: '#79839a',
              secondaryColor: '#191d24',
              tertiaryColor: '#12151a',
            },
          });
          mermaidInitialized = true;
        }

        const { svg: rendered } = await mermaid.render(`mermaid-${++svgSequence}`, chart);
        if (generation !== renderId.current) return;
        setError(null);
        setSvg(rendered);
      } catch (err) {
        if (generation !== renderId.current) return;
        setError(err instanceof Error ? err.message : String(err));
        setSvg(null);
      }
    };

    void renderChart();
  }, [chart]);

  if (!chart || chart.trim() === '') {
    return (
      <div className="flex items-center justify-center h-40 text-on-surface-variant text-sm">
        <span aria-hidden="true" className="material-symbols-outlined mr-2">schema</span>
        No flowchart available yet
      </div>
    );
  }

  if (chart === 'NO_CHANGE') {
    return (
      <div className="flex items-center justify-center h-16 text-on-surface-variant text-sm">
        <span aria-hidden="true" className="material-symbols-outlined mr-2 text-success">check_circle</span>
        No structural changes detected in this window
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-2">
        <div className="bg-error/10 border border-error/30 rounded-lg p-3">
          <p className="text-xs font-bold text-error mb-1">Mermaid render error</p>
          <p className="text-xs text-error font-mono">{error}</p>
          <p className="text-[11px] text-on-surface-variant mt-1">
            The diagram is written by the model, so an invalid one means the answer was malformed —
            not that the flow is. The next window is drawn as usual.
          </p>
        </div>
        <pre className="text-xs text-on-surface-variant font-mono p-3 bg-primary/5 rounded-lg overflow-auto max-h-48">
          {chart}
        </pre>
      </div>
    );
  }

  return (
    <div
      className="w-full overflow-auto rounded-xl bg-surface-container-low/50 p-4 min-h-40 [&_svg]:max-w-full [&_svg]:mx-auto [&_svg]:block"
      dangerouslySetInnerHTML={{ __html: svg ?? '' }}
    />
  );
};

export default MermaidRenderer;
