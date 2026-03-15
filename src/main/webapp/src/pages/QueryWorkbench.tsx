import React, { useState, useEffect } from 'react';
import Editor from '@monaco-editor/react';
import axios from 'axios';

interface SchemaInfo {
  topics: string[];
  tables: string[];
  health: boolean;
}

interface QueryResult {
  queryId: string;
  columns: string[];
  rows: Record<string, any>[];
  error: string | null;
}

const QueryWorkbench: React.FC = () => {
  const [schema, setSchema] = useState<SchemaInfo | null>(null);
  const [sql, setSql] = useState("SELECT\n  window_start, window_end, product_id,\n  SUM(quantity) AS total_sales\nFROM orders_stream\nWINDOW TUMBLING (SIZE 5 MINUTES)\nGROUP BY\n  window_start, window_end, product_id\nEMIT CHANGES;");
  const [results, setResults] = useState<QueryResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [offsetMode, setOffsetMode] = useState<'EARLIEST' | 'LATEST'>('EARLIEST');

  // Schema details state
  const [expandedTables, setExpandedTables] = useState<Record<string, boolean>>({});
  const [tableSchemas, setTableSchemas] = useState<Record<string, Record<string, string>>>({});

  // Window Assistant state
  const [windowType, setWindowType] = useState('Tumbling (Non-overlapping)');
  const [windowSize, setWindowSize] = useState(5);
  const [windowUnit, setWindowUnit] = useState('MIN');

  useEffect(() => {
    fetchSchema();
  }, []);

  const fetchSchema = async () => {
    try {
      const response = await axios.get('/api/query/init');
      setSchema(response.data);
    } catch (err) {
      console.error('Failed to fetch schema', err);
    }
  };

  const toggleTable = async (tableName: string) => {
    const isExpanded = !!expandedTables[tableName];
    setExpandedTables(prev => ({ ...prev, [tableName]: !isExpanded }));

    if (!isExpanded && !tableSchemas[tableName]) {
      try {
        const response = await axios.get(`/api/query/schema/${tableName}`);
        setTableSchemas(prev => ({ ...prev, [tableName]: response.data }));
      } catch (err) {
        console.error('Failed to fetch table schema', err);
      }
    }
  };

  const runQuery = async () => {
    setExecuting(true);
    setResults(null);
    try {
      const response = await axios.post('/api/query', { sql });
      setResults(response.data);
    } catch (err) {
      setResults({ queryId: '', columns: [], rows: [], error: 'Query execution failed' });
    } finally {
      setExecuting(false);
    }
  };

  const applyWindowLogic = () => {
    const unitMap: Record<string, string> = { 'MIN': 'MINUTES', 'SEC': 'SECONDS', 'HOUR': 'HOURS' };
    const unit = unitMap[windowUnit] || 'MINUTES';
    const newSql = `-- Window logic applied: ${windowType}\nSELECT * FROM source\nWINDOW TUMBLING (SIZE ${windowSize} ${unit})\nGROUP BY ...`;
    setSql(newSql);
  };

  return (
    <div className="flex h-full overflow-hidden">
      {/* Sidebar Navigation & Schema Browser */}
      <aside className="w-72 flex-shrink-0 flex flex-col border-r border-primary/10 bg-background-dark/50">
        <div className="p-4 flex items-center gap-3 border-b border-primary/10">
          <div className="size-8 bg-primary rounded flex items-center justify-center text-background-dark">
            <span className="material-symbols-outlined font-bold">database</span>
          </div>
          <div>
            <h1 className="text-sm font-bold tracking-tight">KAFKA EXPLORER</h1>
            <p className="text-[10px] text-primary uppercase font-semibold">Prod-West-1</p>
          </div>
        </div>
        
        {/* Schema Browser */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-widest">Schema Browser</h2>
            <span className="material-symbols-outlined text-sm cursor-pointer hover:text-primary" onClick={fetchSchema}>refresh</span>
          </div>
          <div className="space-y-1">
            {/* Flink Tables */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-slate-400">grid_view</span>
                <span className="text-sm font-medium">Flink Tables</span>
              </summary>
              <div className="pl-6 pt-1 space-y-1">
                {schema?.tables.map(table => (
                  <div key={table} className="group">
                    <div
                      onClick={() => toggleTable(table)}
                      className="flex items-center justify-between py-1 px-2 rounded hover:bg-primary/5 cursor-pointer transition-colors"
                    >
                      <span className="text-xs text-slate-300 truncate font-medium">{table}</span>
                      <span className="material-symbols-outlined text-xs text-slate-500 group-hover:text-primary transition-transform duration-200" style={{ transform: expandedTables[table] ? 'rotate(90deg)' : 'none' }}>
                        chevron_right
                      </span>
                    </div>
                    {expandedTables[table] && tableSchemas[table] && (
                      <div className="ml-4 pl-3 border-l border-primary/20 mt-1 space-y-1">
                        {Object.entries(tableSchemas[table]).map(([col, type]) => (
                          <div key={col} className="flex justify-between items-center text-[10px] py-0.5">
                            <span className="text-slate-400 truncate pr-2">{col}</span>
                            <span className="text-slate-600 font-mono uppercase shrink-0">{type}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </details>

            {/* Kafka Topics */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-slate-400">topic</span>
                <span className="text-sm font-medium">Kafka Topics</span>
              </summary>
              <div className="pl-8 pt-1 space-y-1">
                {schema?.topics.map(topic => (
                  <div key={topic} className="flex items-center gap-2 py-1 text-xs text-slate-400 border-l border-primary/20 pl-3 hover:text-primary cursor-pointer font-mono truncate" onClick={() => setSql(`SELECT * FROM "${topic}" LIMIT 10`)}>
                    <span>{topic}</span>
                  </div>
                ))}
              </div>
            </details>
          </div>
        </div>

        {/* User Status */}
        <div className="p-4 border-t border-primary/10 flex items-center gap-3">
          <div className="w-8 h-8 rounded-full bg-primary/20 flex items-center justify-center">
            <span className="material-symbols-outlined text-primary text-sm">person</span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-semibold truncate">dev_user_01</p>
            <div className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
              <span className="text-[10px] text-slate-400 uppercase">Connected</span>
            </div>
          </div>
          <span className="material-symbols-outlined text-slate-500 cursor-pointer hover:text-white">settings</span>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* Toolbar */}
        <header className="h-14 border-b border-primary/10 flex items-center px-6 justify-between bg-background-dark/30">
          <div className="flex items-center gap-4">
            <nav className="flex items-center bg-background-dark border border-primary/20 rounded-lg p-0.5">
              <button className="px-4 py-1.5 text-xs font-bold rounded-md bg-primary text-background-dark">SQL EDITOR</button>
              <button className="px-4 py-1.5 text-xs font-bold rounded-md text-slate-400 hover:text-white">STREAMS</button>
              <button className="px-4 py-1.5 text-xs font-bold rounded-md text-slate-400 hover:text-white">DASHBOARDS</button>
            </nav>
            <div className="h-6 w-[1px] bg-primary/20"></div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-400">Offset:</span>
              <div className="flex bg-background-dark border border-primary/20 rounded overflow-hidden">
                <button
                  onClick={() => setOffsetMode('EARLIEST')}
                  className={`px-2 py-1 text-[10px] font-bold border-r border-primary/20 transition-colors ${offsetMode === 'EARLIEST' ? 'bg-primary/20 text-primary' : 'text-slate-500'}`}
                >
                  EARLIEST
                </button>
                <button
                  onClick={() => setOffsetMode('LATEST')}
                  className={`px-2 py-1 text-[10px] font-bold transition-colors ${offsetMode === 'LATEST' ? 'bg-primary/20 text-primary' : 'text-slate-500'}`}
                >
                  LATEST
                </button>
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button 
              onClick={runQuery}
              disabled={executing}
              className="flex items-center gap-2 px-4 py-2 bg-primary text-background-dark rounded-lg text-xs font-bold hover:brightness-110 disabled:opacity-50"
            >
              <span className="material-symbols-outlined text-sm">play_arrow</span>
              RUN QUERY
            </button>
            <button className="flex items-center gap-2 px-3 py-2 bg-background-dark border border-primary/20 rounded-lg text-xs font-bold hover:bg-primary/5">
              <span className="material-symbols-outlined text-sm">save</span>
            </button>
          </div>
        </header>

        {/* Editor Section (Top) */}
        <div className="flex-1 flex flex-col min-h-0 relative">
          <div className="flex-1 flex overflow-hidden">
            {/* Code Editor View */}
            <div className="flex-1 flex flex-col bg-background-dark/20 overflow-hidden">
              <div className="flex items-center px-4 py-2 border-b border-primary/5 bg-background-dark/40">
                <span className="text-[10px] font-bold text-primary tracking-widest uppercase">main_query.sql</span>
              </div>
              <div className="flex-1 overflow-hidden">
                <Editor
                  height="100%"
                  defaultLanguage="sql"
                  theme="vs-dark"
                  value={sql}
                  onChange={(val) => setSql(val || '')}
                  options={{
                    fontSize: 14,
                    fontFamily: 'JetBrains Mono',
                    minimap: { enabled: false },
                    padding: { top: 20 },
                    scrollBeyondLastLine: false,
                    automaticLayout: true
                  }}
                />
              </div>
            </div>

            {/* Window Assistant Sidebar (Right) */}
            <div className="w-80 border-l border-primary/10 bg-background-dark/40 p-4 flex flex-col gap-4">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-primary">magic_button</span>
                <h3 className="text-sm font-bold">Window Assistant</h3>
              </div>
              <p className="text-xs text-slate-400 leading-relaxed">Generate windowing logic for your streaming queries.</p>
              <div className="space-y-4 pt-2">
                <div className="space-y-2">
                  <label className="text-[10px] font-bold text-slate-500 uppercase">Window Type</label>
                  <select
                    value={windowType}
                    onChange={(e) => setWindowType(e.target.value)}
                    className="w-full bg-background-dark border border-primary/20 rounded px-3 py-2 text-xs focus:ring-1 focus:ring-primary focus:border-primary outline-none text-slate-200"
                  >
                    <option>Tumbling (Non-overlapping)</option>
                    <option>Hopping (Overlapping)</option>
                    <option>Session (Inactivity based)</option>
                  </select>
                </div>
                <div className="space-y-2">
                  <label className="text-[10px] font-bold text-slate-500 uppercase">Window Size</label>
                  <div className="flex gap-2">
                    <input
                      type="number"
                      value={windowSize}
                      onChange={(e) => setWindowSize(parseInt(e.target.value))}
                      className="w-full bg-background-dark border border-primary/20 rounded px-3 py-2 text-xs focus:ring-1 focus:ring-primary focus:border-primary outline-none text-slate-200"
                    />
                    <select
                      value={windowUnit}
                      onChange={(e) => setWindowUnit(e.target.value)}
                      className="w-24 bg-background-dark border border-primary/20 rounded px-3 py-2 text-xs text-slate-200"
                    >
                      <option>MIN</option>
                      <option>SEC</option>
                      <option>HOUR</option>
                    </select>
                  </div>
                </div>
                <div className="p-3 bg-primary/5 border border-dashed border-primary/30 rounded-lg">
                  <p className="text-[11px] text-primary leading-snug">
                    <span className="font-bold">Pro Tip:</span> Tumbling windows are useful for reporting periodic metrics like "Sales per 5 minutes".
                  </p>
                </div>
                <button
                  onClick={applyWindowLogic}
                  className="w-full py-2.5 bg-primary/10 border border-primary text-primary rounded-lg text-xs font-bold hover:bg-primary/20 transition-colors"
                >
                  APPLY TO EDITOR
                </button>
              </div>
            </div>
          </div>

          {/* Results Section (Bottom) */}
          <div className="h-1/2 border-t border-primary/20 flex flex-col bg-background-dark/60">
            {/* Statistics Bar */}
            <div className="h-10 border-b border-primary/10 flex items-center px-4 justify-between bg-background-dark/80 shrink-0">
              <div className="flex items-center gap-6">
                <div className="flex items-center gap-2">
                  <span className="material-symbols-outlined text-sm text-primary">timer</span>
                  <span className="text-[11px] font-medium text-slate-400">Execution: <span className="text-slate-100">{executing ? '...' : '142ms'}</span></span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="material-symbols-outlined text-sm text-primary">speed</span>
                  <span className="text-[11px] font-medium text-slate-400">Throughput: <span className="text-slate-100">1.2k msg/s</span></span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="material-symbols-outlined text-sm text-primary">list_alt</span>
                  <span className="text-[11px] font-medium text-slate-400">Total Rows: <span className="text-slate-100">{results?.rows.length || 0}</span></span>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full bg-primary ${executing ? 'animate-pulse' : ''}`}></span>
                <span className="text-[10px] font-bold text-primary">STREAMING LIVE</span>
              </div>
            </div>

            {/* Data Grid */}
            <div className="flex-1 overflow-auto custom-scrollbar">
              {results?.error ? (
                <div className="p-8 text-red-500 font-mono text-sm whitespace-pre-wrap">
                  {results.error}
                </div>
              ) : results?.columns.length ? (
                <table className="w-full text-left border-collapse min-w-[800px]">
                  <thead className="sticky top-0 bg-background-dark/95 backdrop-blur-sm z-10">
                    <tr>
                      {results.columns.map(col => (
                        <th key={col} className="px-4 py-3 border-b border-primary/10 text-[10px] font-bold text-slate-500 uppercase tracking-widest">{col}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-primary/5">
                    {results.rows.map((row, i) => (
                      <tr key={i} className="hover:bg-primary/5 transition-colors group">
                        {results.columns.map(col => (
                          <td key={col} className="px-4 py-3 text-xs font-mono text-slate-300">
                            {typeof row[col] === 'object' ? JSON.stringify(row[col]) : String(row[col])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-slate-600 space-y-2">
                  <span className="material-symbols-outlined text-3xl opacity-20">terminal</span>
                  <p className="text-xs font-medium uppercase tracking-widest">No results to display</p>
                </div>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};

export default QueryWorkbench;
