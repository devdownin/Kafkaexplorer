import React, { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { 
  ChevronLeft, 
  Database, 
  Code, 
  Mail, 
  ListTree, 
  Info,
  Copy,
  AlertCircle
} from 'lucide-react';
import axios from 'axios';
import Editor from '@monaco-editor/react';

interface TopicDetail {
  topic: {
    name: string;
    partitions: number;
    estimatedSize: number;
    minOffsets: Record<number, number>;
    maxOffsets: Record<number, number>;
  };
  format: string;
  schema: Record<string, string>;
  ddl: string;
  samples: string[];
}

const TopicExplorer: React.FC = () => {
  const { name } = useParams<{ name: string }>();
  const [data, setData] = useState<TopicDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [readMode, setReadMode] = useState('earliest-offset');

  useEffect(() => {
    fetchTopicDetails();
  }, [name, readMode]);

  const fetchTopicDetails = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/topic/${name}?readMode=${readMode}`);
      setData(response.data);
    } catch (err) {
      console.error('Failed to fetch topic details', err);
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  if (loading && !data) return (
    <div className="flex-1 flex items-center justify-center">
      <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
    </div>
  );

  if (!data) return <div className="p-8">Topic not found</div>;

  return (
    <div className="p-8 max-w-7xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex flex-col gap-4">
        <Link to="/" className="flex items-center gap-2 text-[10px] font-bold text-slate-500 hover:text-primary uppercase tracking-widest transition-colors w-fit">
          <ChevronLeft className="w-3 h-3" /> Back to Dashboard
        </Link>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <h1 className="text-3xl font-bold text-slate-100">{data.topic.name}</h1>
            {data.topic.name.toLowerCase().endsWith('.dlt') && (
              <span className="px-2 py-1 rounded bg-amber-500/10 border border-amber-500/20 text-amber-500 text-[10px] font-bold uppercase tracking-widest">
                Dead Letter Topic
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="glass p-6 rounded-3xl">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Partitions</p>
          <h3 className="text-2xl font-bold text-slate-100">{data.topic.partitions}</h3>
        </div>
        <div className="glass p-6 rounded-3xl">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Messages (Approx)</p>
          <h3 className="text-2xl font-bold text-primary">{data.topic.estimatedSize.toLocaleString()}</h3>
        </div>
        <div className="glass p-6 rounded-3xl">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Detected Format</p>
          <h3 className="text-2xl font-bold text-emerald-500 uppercase">{data.format}</h3>
        </div>
      </div>

      {/* DDL & Schema Section */}
      <div className="grid grid-cols-12 gap-8">
        {/* DDL Area */}
        <div className="col-span-12 lg:col-span-8 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-bold text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <Code className="text-primary w-4 h-4" /> Flink SQL DDL
            </h2>
            <div className="flex bg-black/30 border border-white/5 rounded-lg overflow-hidden text-[10px] font-bold">
              <button 
                onClick={() => setReadMode('earliest-offset')}
                className={`px-3 py-1 transition-colors ${readMode === 'earliest-offset' ? 'bg-primary/20 text-primary' : 'text-slate-500'}`}
              >EARLIEST</button>
              <button 
                onClick={() => setReadMode('latest-offset')}
                className={`px-3 py-1 transition-colors ${readMode === 'latest-offset' ? 'bg-primary/20 text-primary' : 'text-slate-500'}`}
              >LATEST</button>
            </div>
          </div>
          
          <div className="glass rounded-3xl overflow-hidden h-[300px] border border-white/5">
            <div className="h-10 bg-white/[0.02] border-b border-white/5 flex items-center justify-between px-6 shrink-0">
              <span className="text-[10px] font-bold text-slate-500 uppercase">Auto-Generated Definition</span>
              <button onClick={() => copyToClipboard(data.ddl)} className="text-slate-500 hover:text-primary transition-colors">
                <Copy className="w-4 h-4" />
              </button>
            </div>
            <Editor
              height="calc(100% - 2.5rem)"
              defaultLanguage="sql"
              theme="vs-dark"
              value={data.ddl}
              options={{
                readOnly: true,
                fontSize: 12,
                fontFamily: 'JetBrains Mono',
                minimap: { enabled: false },
                padding: { top: 20 },
                backgroundColor: '#0B1120'
              }}
            />
          </div>
        </div>

        {/* Schema Sidebar */}
        <div className="col-span-12 lg:col-span-4 space-y-4">
          <h2 className="text-sm font-bold text-slate-500 uppercase tracking-widest flex items-center gap-2 px-2">
            <ListTree className="text-primary w-4 h-4" /> Inferred Schema
          </h2>
          <div className="glass rounded-3xl overflow-hidden border border-white/5">
            <table className="w-full text-left">
              <thead>
                <tr className="bg-white/[0.02] text-[10px] font-bold text-slate-500 uppercase tracking-widest border-b border-white/5">
                  <th className="px-6 py-4">Field</th>
                  <th className="px-6 py-4 text-right">Type</th>
                </tr>
              </thead>
              <tbody className="text-xs divide-y divide-white/5">
                {Object.entries(data.schema).map(([field, type]) => (
                  <tr key={field} className="hover:bg-white/[0.01]">
                    <td className="px-6 py-3 font-medium text-slate-300">{field}</td>
                    <td className="px-6 py-3 text-right font-mono text-slate-500 uppercase">{type}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Message Samples */}
      <section className="space-y-4">
        <h2 className="text-sm font-bold text-slate-500 uppercase tracking-widest flex items-center gap-2 px-2">
          <Mail className="text-primary w-4 h-4" /> Message Samples (Latest 20)
        </h2>
        <div className="glass rounded-3xl overflow-hidden border border-white/5">
          <div className="max-h-[600px] overflow-y-auto custom-scrollbar divide-y divide-white/5">
            {data.samples.map((sample, i) => (
              <div key={i} className="group relative">
                <pre className="p-6 text-[11px] font-mono text-slate-400 group-hover:text-slate-200 transition-colors overflow-x-auto whitespace-pre-wrap">
                  {sample}
                </pre>
                <button 
                  onClick={() => copyToClipboard(sample)}
                  className="absolute top-4 right-4 opacity-0 group-hover:opacity-100 transition-opacity p-2 bg-primary/10 text-primary rounded-lg"
                >
                  <Copy className="w-4 h-4" />
                </button>
              </div>
            ))}
            {data.samples.length === 0 && (
              <div className="p-20 text-center text-slate-600 space-y-2">
                <AlertCircle className="w-8 h-8 mx-auto opacity-20" />
                <p className="text-xs font-medium uppercase tracking-widest">No messages found in topic</p>
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
};

export default TopicExplorer;
