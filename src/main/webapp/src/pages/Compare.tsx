import React, { useState } from 'react';

const Compare: React.FC = () => {
  const [syncCursors, setSyncCursors] = useState(true);
  const [showDiffOnly, setShowDiffOnly] = useState(false);

  return (
    <div className="flex-1 flex flex-col p-4 gap-4 overflow-hidden h-full">
      {/* SQL Editor Section */}
      <section className="flex flex-col">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-sm">terminal</span>
            <span className="text-xs font-mono uppercase tracking-widest text-slate-400">Shared Query Context</span>
          </div>
          <div className="flex gap-2">
            <button className="flex items-center gap-1 px-3 py-1 rounded bg-primary/10 text-primary text-xs font-bold hover:bg-primary/20">
              <span className="material-symbols-outlined text-sm">history</span> History
            </button>
          </div>
        </div>
        <div className="flex flex-col border border-primary/20 rounded-xl overflow-hidden bg-background-light dark:bg-[#0d1a1a]">
          <div className="flex bg-primary/5 px-4 py-2 border-b border-primary/10 items-center justify-between">
            <div className="flex gap-4">
              <div className="flex items-center gap-2 text-xs text-slate-400">
                <span className="material-symbols-outlined text-xs">database</span> Prod-US-East
              </div>
            </div>
            <div className="flex items-center gap-4">
              <button className="text-slate-400 hover:text-primary transition-all"><span className="material-symbols-outlined text-lg">format_align_left</span></button>
              <button className="text-slate-400 hover:text-primary transition-all"><span className="material-symbols-outlined text-lg">save</span></button>
            </div>
          </div>
          <div className="flex flex-1 relative">
            <div className="w-12 bg-primary/5 border-r border-primary/10 flex flex-col items-center pt-4 text-[10px] font-mono text-slate-500 select-none">
              <span>1</span><span>2</span><span>3</span>
            </div>
            <textarea
              className="w-full bg-transparent border-none focus:ring-0 font-mono text-sm p-4 h-24 text-primary resize-none placeholder:text-slate-600"
              placeholder="-- Write your SQL to filter both topics&#10;SELECT * FROM TABLE WHERE event_type = 'ORDER_CREATED' AND status != 'COMPLETED'"
            ></textarea>
          </div>
          <div className="flex justify-between items-center p-3 bg-primary/5 border-t border-primary/10">
            <div className="flex items-center gap-6">
              <div className="flex items-center gap-2">
                <span className="text-[10px] uppercase text-slate-500 font-bold">Sync Cursors</span>
                <button
                  onClick={() => setSyncCursors(!syncCursors)}
                  className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${syncCursors ? 'bg-primary' : 'bg-slate-700'}`}
                >
                  <span className={`inline-block h-3 w-3 transform rounded-full bg-background-dark transition-transform ${syncCursors ? 'translate-x-5' : 'translate-x-1'}`}></span>
                </button>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[10px] uppercase text-slate-500 font-bold">Show Diff Only</span>
                <button
                   onClick={() => setShowDiffOnly(!showDiffOnly)}
                   className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${showDiffOnly ? 'bg-primary' : 'bg-slate-700'}`}
                >
                  <span className={`inline-block h-3 w-3 transform rounded-full transition-transform ${showDiffOnly ? 'translate-x-5' : 'translate-x-1'} ${showDiffOnly ? 'bg-background-dark' : 'bg-slate-300'}`}></span>
                </button>
              </div>
            </div>
            <button className="flex items-center gap-2 px-6 py-2 bg-primary text-background-dark font-bold rounded-lg hover:brightness-110 transition-all text-sm">
              <span className="material-symbols-outlined text-lg">play_arrow</span> RUN COMPARE
            </button>
          </div>
        </div>
      </section>

      {/* side-by-side message views */}
      <section className="flex-1 flex gap-4 overflow-hidden">
        {/* Topic A View */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-light dark:bg-[#0d1a1a] overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <div className="flex flex-col">
              <span className="text-[10px] font-bold text-primary uppercase">Topic A (Source)</span>
              <select className="bg-transparent border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer">
                <option>orders_primary</option>
                <option>orders_raw</option>
              </select>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-500">2,451 messages</span>
              <span className="material-symbols-outlined text-slate-500 cursor-pointer hover:text-primary">filter_list</span>
            </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {/* Message Card */}
            <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer border-l-4 border-l-primary/60">
              <div className="flex justify-between items-center mb-2">
                <span className="font-mono text-[10px] text-slate-400">ID: order_88219</span>
                <span className="text-[10px] text-slate-500 italic">2s ago</span>
              </div>
              <div className="font-mono text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-slate-500">status:</span>
                  <span className="text-primary">"PENDING"</span>
                </div>
                <div className="flex justify-between bg-red-500/10 text-red-400 px-1 rounded">
                  <span className="text-slate-400">amount:</span>
                  <span>124.50</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">currency:</span>
                  <span className="text-slate-300">"USD"</span>
                </div>
              </div>
            </div>
            {/* Message Card 2 */}
            <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer">
              <div className="flex justify-between items-center mb-2">
                <span className="font-mono text-[10px] text-slate-400">ID: order_88218</span>
                <span className="text-[10px] text-slate-500 italic">15s ago</span>
              </div>
              <div className="font-mono text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-slate-500">status:</span>
                  <span className="text-primary">"COMPLETED"</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400">amount:</span>
                  <span className="text-slate-300">89.00</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Topic B View */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-light dark:bg-[#0d1a1a] overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <div className="flex flex-col">
              <span className="text-[10px] font-bold text-primary uppercase">Topic B (Target)</span>
              <select className="bg-transparent border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer">
                <option>orders_backup</option>
                <option>orders_archive</option>
              </select>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-500">2,451 messages</span>
              <span className="material-symbols-outlined text-slate-500 cursor-pointer hover:text-primary">sync</span>
            </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {/* Message Card (Diff with ID match) */}
            <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer border-l-4 border-l-primary/60">
              <div className="flex justify-between items-center mb-2">
                <span className="font-mono text-[10px] text-slate-400">ID: order_88219</span>
                <span className="text-[10px] text-slate-500 italic">2s ago</span>
              </div>
              <div className="font-mono text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-slate-500">status:</span>
                  <span className="text-primary">"PENDING"</span>
                </div>
                <div className="flex justify-between bg-green-500/10 text-green-400 px-1 rounded">
                  <span className="text-slate-400">amount:</span>
                  <span>124.55</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">currency:</span>
                  <span className="text-slate-300">"USD"</span>
                </div>
              </div>
            </div>
            {/* Message Card 2 */}
            <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer">
              <div className="flex justify-between items-center mb-2">
                <span className="font-mono text-[10px] text-slate-400">ID: order_88218</span>
                <span className="text-[10px] text-slate-500 italic">15s ago</span>
              </div>
              <div className="font-mono text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-slate-500">status:</span>
                  <span className="text-primary">"COMPLETED"</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400">amount:</span>
                  <span className="text-slate-300">89.00</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Diff Summary Bar */}
      <footer className="h-10 border-t border-primary/20 flex items-center px-4 bg-primary/5 rounded-lg justify-between text-xs">
        <div className="flex gap-4">
          <span className="text-slate-400">Differences detected: <b className="text-primary">2 messages</b></span>
          <span className="text-slate-400">Schema mismatch: <b className="text-red-400">None</b></span>
        </div>
        <div className="flex gap-4 items-center">
          <div className="flex items-center gap-1">
            <div className="w-2 h-2 rounded-full bg-green-500"></div>
            <span className="text-slate-400 text-[10px]">Value Added</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-2 h-2 rounded-full bg-red-500"></div>
            <span className="text-slate-400 text-[10px]">Value Modified/Removed</span>
          </div>
          <div className="flex items-center gap-1 ml-4 border-l border-primary/20 pl-4">
            <span className="material-symbols-outlined text-sm text-primary">download</span>
            <span className="text-slate-400">Export Diff Report</span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default Compare;
