import React, { useState } from 'react';

const Lineage: React.FC = () => {
  const [selectedNode, setSelectedNode] = useState<string | null>('processed_events');

  return (
    <div className="flex h-full w-full overflow-hidden">
      {/* Sidebar Navigation */}
      <aside className="w-64 border-r border-border-dark bg-background-dark/50 p-4 flex flex-col gap-6 shrink-0">
        <div>
          <h3 className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-4">Cluster Context</h3>
          <div className="flex items-center gap-3 p-3 rounded-lg bg-primary/10 border border-primary/20">
            <span className="material-symbols-outlined text-primary">database</span>
            <div>
              <p className="text-sm font-semibold text-slate-100">Prod-US-East-01</p>
              <p className="text-xs text-slate-400">Status: Operational</p>
            </div>
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <h3 className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-2 px-3">Graph Layers</h3>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg bg-neutral-dark text-slate-100 text-sm">
            <span className="material-symbols-outlined text-xl text-primary">radio_button_checked</span>
            Topics
            <span className="ml-auto text-xs bg-slate-700 px-1.5 py-0.5 rounded">12</span>
          </button>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg text-slate-400 hover:bg-neutral-dark hover:text-slate-100 text-sm transition-all">
            <span className="material-symbols-outlined text-xl">view_kanban</span>
            Flink Tables
            <span className="ml-auto text-xs bg-slate-800 px-1.5 py-0.5 rounded">8</span>
          </button>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg text-slate-400 hover:bg-neutral-dark hover:text-slate-100 text-sm transition-all">
            <span className="material-symbols-outlined text-xl">terminal</span>
            SQL Jobs
            <span className="ml-auto text-xs bg-slate-800 px-1.5 py-0.5 rounded">4</span>
          </button>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg text-slate-400 hover:bg-neutral-dark hover:text-slate-100 text-sm transition-all">
            <span className="material-symbols-outlined text-xl">warning</span>
            Alerts
          </button>
        </div>
        <div className="mt-auto">
          <button className="w-full flex items-center justify-center gap-2 rounded-lg h-10 bg-primary text-background-dark font-bold text-sm shadow-lg shadow-primary/20 hover:brightness-110 transition-all">
            <span className="material-symbols-outlined text-lg">add_box</span>
            New SQL Job
          </button>
        </div>
      </aside>

      {/* Main Content Area: Lineage Graph */}
      <main className="flex-1 relative flex flex-col bg-background-dark graph-bg overflow-hidden">
        {/* Breadcrumbs / Controls */}
        <div className="absolute top-4 left-4 z-10 flex items-center gap-4">
          <div className="flex items-center gap-2 bg-neutral-dark/80 backdrop-blur-md border border-border-dark px-3 py-1.5 rounded-full text-xs font-medium">
            <span className="text-slate-400">Lineage</span>
            <span className="text-slate-600">/</span>
            <span className="text-primary">Dependency Graph</span>
          </div>
        </div>

        {/* Viewport Controls */}
        <div className="absolute bottom-6 left-6 z-10 flex flex-col gap-2">
          <div className="flex flex-col bg-neutral-dark/90 backdrop-blur-md border border-border-dark rounded-lg overflow-hidden shadow-2xl">
            <button className="p-2 hover:bg-primary/20 text-slate-300 border-b border-border-dark transition-colors"><span className="material-symbols-outlined">add</span></button>
            <button className="p-2 hover:bg-primary/20 text-slate-300 border-b border-border-dark transition-colors"><span className="material-symbols-outlined">remove</span></button>
            <button className="p-2 hover:bg-primary/20 text-slate-300 transition-colors"><span className="material-symbols-outlined">center_focus_weak</span></button>
          </div>
        </div>

        {/* SVG Graph */}
        <div className="flex-1 flex items-center justify-center relative">
          <svg className="w-full h-full max-w-4xl max-h-[80%]" fill="none" viewBox="0 0 800 500" xmlns="http://www.w3.org/2000/svg">
            {/* Connections */}
            <path d="M150 250 L280 150" opacity="0.4" stroke="#25f4f4" strokeDasharray="4 4" strokeWidth="2"></path>
            <path d="M150 250 L280 350" opacity="0.6" stroke="#25f4f4" strokeWidth="2"></path>
            <path d="M320 150 L480 250" opacity="0.6" stroke="#25f4f4" strokeWidth="2"></path>
            <path d="M320 350 L480 250" opacity="0.6" stroke="#25f4f4" strokeWidth="2"></path>
            <path d="M520 250 L680 250" opacity="0.8" stroke="#25f4f4" strokeWidth="2"></path>

            {/* Nodes */}
            <g transform="translate(110, 230)" className="cursor-pointer" onClick={() => setSelectedNode('raw_orders')}>
              <circle cx="20" cy="20" fill="#1b2d2d" r="24" stroke="#25f4f4" strokeWidth="2"></circle>
              <text fill="white" fontFamily="Inter" fontSize="12" textAnchor="middle" x="20" y="60">raw_orders</text>
              <circle cx="20" cy="20" fill="#25f4f4" opacity="0.2" r="8"></circle>
            </g>

            <g transform="translate(280, 130)" className="cursor-pointer" onClick={() => setSelectedNode('Filter_EU')}>
              <rect fill="#1b2d2d" height="40" stroke="#25f4f4" strokeWidth="2" transform="rotate(45 20 20)" width="40" x="0" y="0"></rect>
              <text fill="white" fontFamily="Inter" fontSize="12" textAnchor="middle" x="20" y="65">Filter_EU</text>
            </g>

            <g transform="translate(280, 330)" className="cursor-pointer" onClick={() => setSelectedNode('Filter_US')}>
              <rect fill="#1b2d2d" height="40" stroke="#25f4f4" strokeWidth="2" transform="rotate(45 20 20)" width="40" x="0" y="0"></rect>
              <text fill="white" fontFamily="Inter" fontSize="12" textAnchor="middle" x="20" y="65">Filter_US</text>
            </g>

            <g transform="translate(480, 225)" className="cursor-pointer" onClick={() => setSelectedNode('unified_orders')}>
              <rect fill="#25f4f4" fillOpacity="0.1" height="40" rx="4" stroke="#25f4f4" strokeWidth="2" width="60" x="0" y="0"></rect>
              <text fill="white" fontFamily="Inter" fontSize="12" textAnchor="middle" x="30" y="60">unified_orders</text>
            </g>

            <g transform="translate(680, 230)" className="cursor-pointer" onClick={() => setSelectedNode('processed_events')}>
              <circle cx="20" cy="20" fill="#1b2d2d" r="24" stroke="#25f4f4" strokeWidth="2"></circle>
              <text fill="#25f4f4" fontFamily="Inter" fontSize="12" fontWeight="bold" textAnchor="middle" x="20" y="60">processed_events</text>
              <circle cx="20" cy="20" opacity="0.3" r="30" stroke="#25f4f4" strokeWidth="1"></circle>
            </g>
          </svg>
        </div>
      </main>

      {/* Node Inspector (Side Panel Right) */}
      {selectedNode && (
        <aside className="w-96 border-l border-border-dark bg-background-dark flex flex-col shrink-0 overflow-hidden">
          <div className="p-6 border-b border-border-dark">
            <div className="flex items-center justify-between mb-4">
              <span className="px-2 py-0.5 rounded bg-primary/20 text-primary text-[10px] font-bold uppercase tracking-wider">Selected Node</span>
              <button className="text-slate-500 hover:text-slate-300 transition-colors" onClick={() => setSelectedNode(null)}>
                <span className="material-symbols-outlined">close</span>
              </button>
            </div>
            <h2 className="text-xl font-bold text-slate-100 mb-1">{selectedNode}</h2>
            <p className="text-xs text-slate-500 flex items-center gap-1">
              <span className="material-symbols-outlined text-sm">schedule</span>
              Last updated 2 mins ago
            </p>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar">
            <div className="p-6 space-y-8">
              {/* DDL Definition */}
              <section>
                <div className="flex items-center gap-2 mb-3">
                  <span className="material-symbols-outlined text-primary text-xl">code</span>
                  <h3 className="text-sm font-bold text-slate-300">DDL Definition</h3>
                </div>
                <div className="bg-neutral-dark rounded-lg p-4 font-mono text-xs text-slate-300 leading-relaxed border border-border-dark">
                  <span className="text-primary">CREATE TABLE</span> {selectedNode} (<br/>
                  &nbsp;&nbsp;order_id STRING,<br/>
                  &nbsp;&nbsp;user_id BIGINT,<br/>
                  &nbsp;&nbsp;amount DECIMAL(10, 2),<br/>
                  &nbsp;&nbsp;ts TIMESTAMP(3),<br/>
                  &nbsp;&nbsp;WATERMARK FOR ts AS ts - INTERVAL '5' SECOND<br/>
                  ) <span className="text-primary">WITH</span> (<br/>
                  &nbsp;&nbsp;'connector' = 'kafka',<br/>
                  &nbsp;&nbsp;'topic' = 'processed.events.v1',<br/>
                  &nbsp;&nbsp;'format' = 'json'<br/>
                  );
                </div>
              </section>
              {/* Schema */}
              <section>
                <div className="flex items-center gap-2 mb-3">
                  <span className="material-symbols-outlined text-primary text-xl">list_alt</span>
                  <h3 className="text-sm font-bold text-slate-300">Schema Details</h3>
                </div>
                <div className="bg-neutral-dark rounded-lg border border-border-dark divide-y divide-border-dark">
                  <div className="flex items-center justify-between px-4 py-3">
                    <span className="text-xs text-slate-100 font-medium">order_id</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">STRING</span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-3">
                    <span className="text-xs text-slate-100 font-medium">user_id</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">BIGINT</span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-3">
                    <span className="text-xs text-slate-100 font-medium">amount</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">DECIMAL</span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-3">
                    <span className="text-xs text-slate-100 font-medium">ts</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">TIMESTAMP</span>
                  </div>
                </div>
              </section>
              {/* Metrics */}
              <section>
                <div className="flex items-center gap-2 mb-3">
                  <span className="material-symbols-outlined text-primary text-xl">insights</span>
                  <h3 className="text-sm font-bold text-slate-300">Real-time Metrics</h3>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="bg-neutral-dark p-3 rounded-lg border border-border-dark">
                    <p className="text-[10px] text-slate-500 uppercase tracking-wider mb-1">Throughput</p>
                    <p className="text-lg font-bold text-primary">1.2k <span className="text-xs font-normal text-slate-400">msg/s</span></p>
                  </div>
                  <div className="bg-neutral-dark p-3 rounded-lg border border-border-dark">
                    <p className="text-[10px] text-slate-500 uppercase tracking-wider mb-1">Lag</p>
                    <p className="text-lg font-bold text-orange-400">42 <span className="text-xs font-normal text-slate-400">ms</span></p>
                  </div>
                </div>
              </section>
            </div>
          </div>
          <div className="p-6 border-t border-border-dark">
            <button className="w-full flex items-center justify-center gap-2 rounded-lg h-10 border border-primary text-primary font-bold text-sm hover:bg-primary/10 transition-all">
              <span className="material-symbols-outlined text-lg">edit_note</span>
              Edit SQL Statement
            </button>
          </div>
        </aside>
      )}
    </div>
  );
};

export default Lineage;
