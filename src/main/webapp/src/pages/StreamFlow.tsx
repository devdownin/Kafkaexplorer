import React, { useState } from 'react';
import axios from 'axios';

interface FlowData {
  nodes: { id: string; label: string; type: string }[];
  edges: { from: string; to: string; label: string }[];
}

const StreamFlow: React.FC = () => {
  const [messageKey, setMessageKey] = useState('');
  const [data, setData] = useState<FlowData | null>(null);
  const [loading, setLoading] = useState(false);

  const traceMessage = async () => {
    if (!messageKey) return;
    setLoading(true);
    try {
      const response = await axios.post('/api/stream-flow', {
        messageKey: messageKey,
        useRegex: false,
        maxMessagesPerTopic: 100
      });
      setData(response.data);
    } catch (error) {
      console.error('Trace failed', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex-1 flex flex-col bg-background-dark h-full">
      <header className="p-6 border-b border-primary/10 flex items-center justify-between">
        <div className="flex-1 max-w-2xl">
          <h2 className="text-xl font-bold text-slate-100 mb-2">Stream Flow Tracer</h2>
          <div className="flex gap-2">
            <input
              type="text"
              value={messageKey}
              onChange={(e) => setMessageKey(e.target.value)}
              placeholder="Enter Key (e.g. order_88219) to trace across topics..."
              className="flex-1 bg-neutral-dark border border-border-dark rounded-lg px-4 py-2 text-sm text-slate-100 focus:ring-1 focus:ring-primary focus:border-primary outline-none"
            />
            <button
              onClick={traceMessage}
              disabled={loading || !messageKey}
              className="px-6 py-2 bg-primary text-background-dark font-bold rounded-lg hover:brightness-110 disabled:opacity-50"
            >
              {loading ? 'TRACING...' : 'TRACE'}
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 relative graph-bg flex items-center justify-center p-8">
        {loading ? (
          <div className="text-primary animate-pulse font-bold tracking-widest">SCANNING CLUSTER TOPICS...</div>
        ) : data && data.nodes.length > 0 ? (
          <div className="w-full h-full flex flex-col items-center gap-12 overflow-y-auto py-12">
             {data.nodes.map((node, i) => (
               <React.Fragment key={node.id}>
                 <div className="relative group">
                    <div className="absolute -inset-1 bg-primary/20 rounded-lg blur opacity-25 group-hover:opacity-100 transition duration-1000 group-hover:duration-200"></div>
                    <div className="relative px-8 py-4 bg-neutral-dark ring-1 ring-primary/20 rounded-lg flex items-center gap-4">
                      <span className="material-symbols-outlined text-primary">topic</span>
                      <div>
                        <p className="text-xs text-slate-500 font-bold uppercase tracking-wider">Occurrence {i+1}</p>
                        <p className="text-sm font-bold text-slate-100">{node.label}</p>
                      </div>
                    </div>
                 </div>
                 {i < data.nodes.length - 1 && (
                   <div className="h-12 w-px bg-gradient-to-b from-primary/50 to-transparent relative">
                      <span className="material-symbols-outlined absolute -bottom-4 -left-3.5 text-primary opacity-50">expand_more</span>
                   </div>
                 )}
               </React.Fragment>
             ))}
          </div>
        ) : data ? (
          <div className="text-slate-500 text-center">
            <span className="material-symbols-outlined text-6xl mb-4 block opacity-20">search_off</span>
            <p>No occurrences found for this key in the last sampled records.</p>
          </div>
        ) : (
          <div className="text-slate-500 text-center">
            <span className="material-symbols-outlined text-6xl mb-4 block opacity-20">hub</span>
            <p>Enter a message key above to visualize its path through the cluster.</p>
          </div>
        )}
      </main>
    </div>
  );
};

export default StreamFlow;
