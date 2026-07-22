import React, { useEffect, useRef, useState } from 'react';
import mermaid from 'mermaid';

let mermaidInitialized = false;

interface MermaidRendererProps {
  chart: string;
}

const MermaidRenderer: React.FC<MermaidRendererProps> = ({ chart }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!mermaidInitialized) {
      mermaid.initialize({
        startOnLoad: false,
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
  }, []);

  useEffect(() => {
    if (!chart || chart === 'NO_CHANGE' || !containerRef.current) return;

    const renderChart = async () => {
      try {
        setError(null);
        const id = `mermaid-${Date.now()}`;
        const { svg } = await mermaid.render(id, chart);
        if (containerRef.current) {
          containerRef.current.innerHTML = svg;
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        if (containerRef.current) {
          containerRef.current.innerHTML = '';
        }
      }
    };

    renderChart();
  }, [chart]);

  if (!chart || chart.trim() === '') {
    return (
      <div className="flex items-center justify-center h-40 text-on-surface0 text-sm">
        <span className="material-symbols-outlined mr-2">schema</span>
        No flowchart available yet
      </div>
    );
  }

  if (chart === 'NO_CHANGE') {
    return (
      <div className="flex items-center justify-center h-16 text-on-surface0 text-sm">
        <span className="material-symbols-outlined mr-2 text-success">check_circle</span>
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
        </div>
        <pre className="text-xs text-on-surface0 font-mono p-3 bg-primary/5 rounded-lg overflow-auto max-h-48">
          {chart}
        </pre>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="w-full overflow-auto rounded-xl bg-surface-container-low/50 p-4 min-h-40 [&_svg]:max-w-full [&_svg]:mx-auto [&_svg]:block"
    />
  );
};

export default MermaidRenderer;
