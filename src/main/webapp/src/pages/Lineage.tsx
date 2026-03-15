import React, { useEffect, useState } from 'react';
import axios from 'axios';

interface Node {
  id: string;
  label: string;
  type: 'topic' | 'table' | 'view' | 'query';
}

interface Edge {
  from: string;
  to: string;
  label: string;
}

interface LineageData {
  nodes: Node[];
  edges: Edge[];
}

interface NodeDetail {
  descriptor?: {
    name: string;
    partitions: number;
    totalSize: number;
  };
  format?: string;
  schema?: Record<string, string>;
  ddl?: string;
}

const Lineage: React.FC = () => {
  const [data, setData] = useState<LineageData>({ nodes: [], edges: [] });
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [nodeDetail, setNodeDetail] = useState<NodeDetail | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchLineage();
  }, []);

  const fetchLineage = async () => {
    try {
      const response = await axios.get('/api/lineage');
      setData(response.data);
    } catch (error) {
      console.error('Failed to fetch lineage', error);
    } finally {
      setLoading(false);
    }
  };

  const handleNodeClick = async (nodeId: string) => {
    setSelectedNodeId(nodeId);
    setNodeDetail(null);
    try {
      // If it's a topic or table, we can get more details from the topic API
      // We need to strip the "topic_" prefix if present
      const name = nodeId.startsWith('topic_') ? nodeId.substring(6) : nodeId;
      const response = await axios.get(`/api/topic/${name}`);
      setNodeDetail(response.data);
    } catch (error) {
      console.error('Failed to fetch node details', error);
    }
  };

  const renderNode = (node: Node, index: number) => {
    const x = 100 + (index % 3) * 250;
    const y = 100 + Math.floor(index / 3) * 150;

    if (node.type === 'topic' || node.type === 'table') {
      return (
        <g key={node.id} transform={`translate(${x}, ${y})`} className="cursor-pointer" onClick={() => handleNodeClick(node.id)}>
          <circle cx="20" cy="20" fill="#1b2d2d" r="24" stroke={node.type === 'topic' ? "#25f4f4" : "#f4a261"} strokeWidth="2"></circle>
          <text fill="white" fontFamily="Inter" fontSize="10" textAnchor="middle" x="20" y="60">{node.label}</text>
          <circle cx="20" cy="20" fill={node.type === 'topic' ? "#25f4f4" : "#f4a261"} opacity="0.2" r="8"></circle>
        </g>
      );
    } else if (node.type === 'query') {
      return (
        <g key={node.id} transform={`translate(${x}, ${y})`} className="cursor-pointer" onClick={() => handleNodeClick(node.id)}>
          <rect fill="#1b2d2d" height="40" stroke="#25f4f4" strokeWidth="2" transform="rotate(45 20 20)" width="40" x="0" y="0"></rect>
          <text fill="white" fontFamily="Inter" fontSize="10" textAnchor="middle" x="20" y="65">{node.label}</text>
        </g>
      );
    } else {
      return (
        <g key={node.id} transform={`translate(${x}, ${y})`} className="cursor-pointer" onClick={() => handleNodeClick(node.id)}>
          <rect fill="#25f4f4" fillOpacity="0.1" height="40" rx="4" stroke="#25f4f4" strokeWidth="2" width="60" x="0" y="0"></rect>
          <text fill="white" fontFamily="Inter" fontSize="10" textAnchor="middle" x="30" y="60">{node.label}</text>
        </g>
      );
    }
  };

  return (
    <div className="flex h-full w-full overflow-hidden">
      {/* Sidebar Navigation */}
      <aside className="w-64 border-r border-border-dark bg-background-dark/50 p-4 flex flex-col gap-6 shrink-0">
        <div>
          <h3 className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-4">Cluster Context</h3>
          <div className="flex items-center gap-3 p-3 rounded-lg bg-primary/10 border border-primary/20">
            <span className="material-symbols-outlined text-primary">database</span>
            <div>
              <p className="text-sm font-semibold text-slate-100">Live Cluster</p>
              <p className="text-xs text-slate-400">Status: Connected</p>
            </div>
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <h3 className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-2 px-3">Graph Layers</h3>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg bg-neutral-dark text-slate-100 text-sm">
            <span className="material-symbols-outlined text-xl text-primary">radio_button_checked</span>
            Topics
            <span className="ml-auto text-xs bg-slate-700 px-1.5 py-0.5 rounded">
              {data.nodes.filter(n => n.type === 'topic').length}
            </span>
          </button>
          <button className="flex items-center gap-3 px-3 py-2 rounded-lg text-slate-400 hover:bg-neutral-dark hover:text-slate-100 text-sm transition-all">
            <span className="material-symbols-outlined text-xl">view_kanban</span>
            Flink Tables
            <span className="ml-auto text-xs bg-slate-800 px-1.5 py-0.5 rounded">
               {data.nodes.filter(n => n.type === 'table').length}
            </span>
          </button>
        </div>
      </aside>

      {/* Main Content Area: Lineage Graph */}
      <main className="flex-1 relative flex flex-col bg-background-dark graph-bg overflow-hidden">
        <div className="absolute top-4 left-4 z-10 flex items-center gap-4">
          <div className="flex items-center gap-2 bg-neutral-dark/80 backdrop-blur-md border border-border-dark px-3 py-1.5 rounded-full text-xs font-medium">
            <span className="text-slate-400">Lineage</span>
            <span className="text-slate-600">/</span>
            <span className="text-primary">Dependency Graph</span>
          </div>
        </div>

        <div className="flex-1 flex items-center justify-center relative">
          {loading ? (
            <div className="text-primary animate-pulse font-bold">LOADING LINEAGE...</div>
          ) : (
            <svg className="w-full h-full" fill="none" viewBox="0 0 800 600" xmlns="http://www.w3.org/2000/svg">
              {data.nodes.map((node, i) => renderNode(node, i))}
            </svg>
          )}
        </div>
      </main>

      {/* Node Inspector */}
      {selectedNodeId && (
        <aside className="w-96 border-l border-border-dark bg-background-dark flex flex-col shrink-0 overflow-hidden">
          <div className="p-6 border-b border-border-dark">
            <div className="flex items-center justify-between mb-4">
              <span className="px-2 py-0.5 rounded bg-primary/20 text-primary text-[10px] font-bold uppercase tracking-wider">Selected Node</span>
              <button className="text-slate-500 hover:text-slate-300 transition-colors" onClick={() => setSelectedNodeId(null)}>
                <span className="material-symbols-outlined">close</span>
              </button>
            </div>
            <h2 className="text-xl font-bold text-slate-100 mb-1">{selectedNodeId}</h2>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8">
            {nodeDetail ? (
              <>
                <section>
                  <div className="flex items-center gap-2 mb-3">
                    <span className="material-symbols-outlined text-primary text-xl">code</span>
                    <h3 className="text-sm font-bold text-slate-300">DDL Definition</h3>
                  </div>
                  <pre className="bg-neutral-dark rounded-lg p-4 font-mono text-[10px] text-slate-300 overflow-x-auto border border-border-dark whitespace-pre-wrap">
                    {nodeDetail.ddl || 'No DDL available for this node.'}
                  </pre>
                </section>
                <section>
                  <div className="flex items-center gap-2 mb-3">
                    <span className="material-symbols-outlined text-primary text-xl">list_alt</span>
                    <h3 className="text-sm font-bold text-slate-300">Schema Details</h3>
                  </div>
                  <div className="bg-neutral-dark rounded-lg border border-border-dark divide-y divide-border-dark">
                    {nodeDetail.schema && Object.entries(nodeDetail.schema).map(([col, type]) => (
                      <div key={col} className="flex items-center justify-between px-4 py-3">
                        <span className="text-xs text-slate-100 font-medium">{col}</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">{type}</span>
                      </div>
                    ))}
                  </div>
                </section>
              </>
            ) : (
              <div className="text-slate-500 text-sm">Fetching details...</div>
            )}
          </div>
        </aside>
      )}
    </div>
  );
};

export default Lineage;
