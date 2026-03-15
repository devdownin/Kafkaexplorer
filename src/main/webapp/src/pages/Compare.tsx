import React, { useState, useEffect } from 'react';
import axios from 'axios';

const Compare: React.FC = () => {
  const [topicA, setTopicA] = useState('orders_primary');
  const [topicB, setTopicB] = useState('orders_backup');
  const [samplesA, setSamplesA] = useState<string[]>([]);
  const [samplesB, setSamplesB] = useState<string[]>([]);
  const [availableTopics, setAvailableTopics] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchTopics();
  }, []);

  const fetchTopics = async () => {
    try {
      const response = await axios.get('/api/dashboard');
      if (response.data.topics) {
        setAvailableTopics(response.data.topics);
        if (response.data.topics.length >= 2) {
          setTopicA(response.data.topics[0]);
          setTopicB(response.data.topics[1]);
        }
      }
    } catch (error) {
      console.error('Failed to fetch topics', error);
    }
  };

  const runCompare = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/compare/samples?topicA=${topicA}&topicB=${topicB}`);
      setSamplesA(response.data.topicA);
      setSamplesB(response.data.topicB);
    } catch (error) {
      console.error('Failed to compare topics', error);
    } finally {
      setLoading(false);
    }
  };

  const getCardStyle = (index: number, listA: string[], listB: string[]) => {
    if (index >= listA.length || index >= listB.length) return "";
    return listA[index] !== listB[index] ? "border-l-4 border-l-orange-400" : "border-l-4 border-l-primary/60";
  };

  return (
    <div className="flex-1 flex flex-col p-4 gap-4 overflow-hidden h-full">
      <section className="flex flex-col border border-primary/20 rounded-xl overflow-hidden bg-background-light dark:bg-[#0d1a1a]">
        <div className="flex justify-between items-center p-3 bg-primary/5 border-t border-primary/10">
          <div className="flex items-center gap-6">
             <div className="flex flex-col">
                <span className="text-[10px] font-bold text-slate-500 uppercase">Topic A</span>
                <select
                    value={topicA}
                    onChange={(e) => setTopicA(e.target.value)}
                    className="bg-transparent border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer"
                >
                    {availableTopics.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
             </div>
             <div className="flex flex-col">
                <span className="text-[10px] font-bold text-slate-500 uppercase">Topic B</span>
                <select
                    value={topicB}
                    onChange={(e) => setTopicB(e.target.value)}
                    className="bg-transparent border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer"
                >
                    {availableTopics.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
             </div>
          </div>
          <button
            onClick={runCompare}
            disabled={loading}
            className="flex items-center gap-2 px-6 py-2 bg-primary text-background-dark font-bold rounded-lg hover:brightness-110 transition-all text-sm disabled:opacity-50"
          >
            <span className="material-symbols-outlined text-lg">play_arrow</span> {loading ? 'FETCHING...' : 'RUN COMPARE'}
          </button>
        </div>
      </section>

      <section className="flex-1 flex gap-4 overflow-hidden">
        {/* Topic A View */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-light dark:bg-[#0d1a1a] overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <span className="text-[10px] font-bold text-primary uppercase">{topicA}</span>
            <span className="text-xs text-slate-500">{samplesA.length} samples</span>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {samplesA.map((msg, i) => (
              <div key={i} className={`rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer ${getCardStyle(i, samplesA, samplesB)}`}>
                <pre className="font-mono text-[10px] text-slate-300 whitespace-pre-wrap">{msg}</pre>
              </div>
            ))}
          </div>
        </div>

        {/* Topic B View */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-light dark:bg-[#0d1a1a] overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <span className="text-[10px] font-bold text-primary uppercase">{topicB}</span>
            <span className="text-xs text-slate-500">{samplesB.length} samples</span>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {samplesB.map((msg, i) => (
              <div key={i} className={`rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/40 transition-all cursor-pointer ${getCardStyle(i, samplesB, samplesA)}`}>
                 <pre className="font-mono text-[10px] text-slate-300 whitespace-pre-wrap">{msg}</pre>
              </div>
            ))}
          </div>
        </div>
      </section>

      <footer className="h-10 border-t border-primary/20 flex items-center px-4 bg-primary/5 rounded-lg justify-between text-xs">
        <div className="flex gap-4">
          <span className="text-slate-400">Status: <b className="text-primary">{loading ? 'Comparing...' : 'Idle'}</b></span>
        </div>
      </footer>
    </div>
  );
};

export default Compare;
