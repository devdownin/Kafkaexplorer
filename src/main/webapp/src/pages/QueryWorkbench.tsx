import React, { useState, useEffect } from 'react';
import Editor from '@monaco-editor/react';
import {
  Play,
  StopCircle,
  Database,
  Table as TableIcon,
  Waves,
  Trash2,
  Info,
  ChevronRight,
  ChevronDown,
  Terminal
} from 'lucide-react';
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
  const [sql, setSql] = useState("SELECT * FROM \"demo.orders.1.received\" LIMIT 10");
  const [results, setResults] = useState<QueryResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [expandedTables, setExpandedTables] = useState<Record<string, boolean>>({});
  const [tableSchemas, setTableSchemas] = useState<Record<string, Record<string, string>>>({});

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

  const cancelQuery = async () => {
    if (results?.queryId) {
      try {
        await axios.post(`/api/query/cancel/${results.queryId}`);
        setExecuting(false);
      } catch (err) {
        console.error('Cancel failed', err);
      }
    }
  };

  return (
    <div className="flex h-[calc(100vh-4rem)] overflow-hidden">
      {/* Schema Sidebar */}
      <aside className="w-72 border-r border-white/5 bg-background-dark/30 flex flex-col shrink-0">
        <div className="p-4 border-b border-white/5 flex items-center justify-between">
          <h2 className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Schema Browser</h2>
          <button onClick={fetchSchema} className="text-slate-500 hover:text-primary transition-colors">
            <Info className="w-4 h-4" />
          </button>
        </div>
        
        <div className="flex-1 overflow-y-auto custom-scrollbar p-4 space-y-6">
          {/* Flink Tables */}
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-primary px-2">
              <TableIcon className="w-4 h-4" />
              <span className="text-[10px] font-bold uppercase tracking-wider">Flink Tables</span>
            </div>
            <div className="space-y-1">
              {schema?.tables.map(table => (
                <div key={table} className="group">
                  <div 
                    onClick={() => toggleTable(table)}
                    className="flex items-center justify-between py-1.5 px-2 rounded-lg hover:bg-white/5 cursor-pointer transition-colors"
                  >
                    <span className="text-xs text-slate-300 truncate font-medium">{table}</span>
                    {expandedTables[table] ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                  </div>
                  {expandedTables[table] && tableSchemas[table] && (
                    <div className="ml-4 pl-4 border-l border-primary/20 mt-1 space-y-1">
                      {Object.entries(tableSchemas[table]).map(([col, type]) => (
                        <div key={col} className="flex justify-between items-center text-[10px]">
                          <span className="text-slate-400">{col}</span>
                          <span className="text-slate-600 font-mono uppercase">{type}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Kafka Topics */}
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-primary px-2">
              <Waves className="w-4 h-4" />
              <span className="text-[10px] font-bold uppercase tracking-wider">Kafka Topics</span>
            </div>
            <div className="space-y-1">
              {schema?.topics.map(topic => (
                <div 
                  key={topic}
                  onClick={() => setSql(`SELECT * FROM "${topic}"`)}
                  className="py-1.5 px-2 rounded-lg hover:bg-white/5 cursor-pointer transition-colors text-xs text-slate-400 hover:text-slate-100 truncate font-mono"
                >
                  {topic}
                </div>
              ))}
            </div>
          </div>
        </div>
      </aside>

      {/* Editor & Results Area */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Editor Toolbar */}
        <div className="h-14 border-b border-white/5 flex items-center justify-between px-6 bg-background-dark/20 shrink-0">
          <div className="flex items-center gap-4">
            <button 
              onClick={runQuery}
              disabled={executing}
              className="flex items-center gap-2 px-4 py-1.5 bg-primary text-background-dark rounded-lg text-xs font-bold hover:brightness-110 disabled:opacity-50 transition-all"
            >
              <Play className="w-4 h-4" /> RUN QUERY
            </button>
            {executing && (
              <button 
                onClick={cancelQuery}
                className="flex items-center gap-2 px-4 py-1.5 bg-red-500 text-white rounded-lg text-xs font-bold hover:bg-red-600 transition-all"
              >
                <StopCircle className="w-4 h-4" /> STOP
              </button>
            )}
            <button 
              onClick={() => setSql('')}
              className="p-2 text-slate-500 hover:text-slate-200 transition-colors"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
          
          <div className="text-[10px] font-bold text-slate-500 uppercase tracking-widest bg-black/30 px-3 py-1 rounded-full border border-white/5">
            Flink SQL Runner
          </div>
        </div>

        {/* Monaco Editor */}
        <div className="flex-1 min-h-[300px] border-b border-white/5">
          <Editor
            height="100%"
            defaultLanguage="sql"
            theme="vs-dark"
            value={sql}
            onChange={(val) => setSql(val || '')}
            options={{
              fontSize: 13,
              fontFamily: 'JetBrains Mono',
              minimap: { enabled: false },
              padding: { top: 20 },
              scrollBeyondLastLine: false,
              automaticLayout: true,
              backgroundColor: '#060B15'
            }}
          />
        </div>

        {/* Results Panel */}
        <div className="h-1/2 flex flex-col bg-background-dark/40 overflow-hidden">
          <div className="h-10 border-b border-white/5 px-6 flex items-center justify-between bg-black/20 shrink-0">
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Query Results</span>
            {results?.rows.length ? (
              <span className="text-[10px] font-bold text-primary">{results.rows.length} rows fetched</span>
            ) : null}
          </div>
          
          <div className="flex-1 overflow-auto custom-scrollbar">
            {results?.error ? (
              <div className="p-8 text-red-500 font-mono text-sm whitespace-pre-wrap">
                {results.error}
              </div>
            ) : results?.columns.length ? (
              <table className="w-full text-left border-collapse">
                <thead className="sticky top-0 bg-background-dark/90 backdrop-blur-sm z-10 border-b border-white/5">
                  <tr>
                    {results.columns.map(col => (
                      <th key={col} className="px-4 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider">{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {results.rows.map((row, i) => (
                    <tr key={i} className="hover:bg-white/[0.02] transition-colors">
                      {results.columns.map((col, j) => (
                        <td key={j} className="px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap">
                          {row[col] == null ? '' : typeof row[col] === 'object' ? JSON.stringify(row[col]) : String(row[col])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : !executing && (
              <div className="h-full flex flex-col items-center justify-center text-slate-600 space-y-2">
                <Terminal className="w-8 h-8 opacity-20" />
                <p className="text-xs font-medium">No results to display</p>
              </div>
            )}
            {executing && (
              <div className="h-full flex flex-col items-center justify-center space-y-4">
                <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
                <p className="text-xs text-slate-500 font-medium">Executing streaming query...</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default QueryWorkbench;
