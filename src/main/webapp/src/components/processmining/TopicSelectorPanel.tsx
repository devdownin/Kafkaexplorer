import React, { useState, useEffect } from 'react';
import axios from 'axios';

export interface SnapshotConfig {
  mode: 'EARLIEST' | 'LATEST_N' | 'TIMESTAMP';
  maxMessages: number;
  durationMinutes?: number | null;
  fromTimestamp?: number | null;
}

interface TopicSelectorPanelProps {
  onStart: (topics: string[], depth: SnapshotConfig) => void;
  loading?: boolean;
}

const TopicSelectorPanel: React.FC<TopicSelectorPanelProps> = ({ onStart, loading }) => {
  const [allTopics, setAllTopics] = useState<string[]>([]);
  const [selectedTopics, setSelectedTopics] = useState<string[]>([]);
  const [filter, setFilter] = useState('');
  const [mode, setMode] = useState<SnapshotConfig['mode']>('LATEST_N');
  const [maxMessages, setMaxMessages] = useState(500);
  const [fromTimestamp, setFromTimestamp] = useState('');
  const [fetchingTopics, setFetchingTopics] = useState(true);

  useEffect(() => {
    const fetchTopics = async () => {
      try {
        const res = await axios.get('/api/dashboard');
        setAllTopics(res.data.topics ?? []);
      } catch {
        setAllTopics([]);
      } finally {
        setFetchingTopics(false);
      }
    };
    fetchTopics();
  }, []);

  const filteredTopics = allTopics.filter(t =>
    t.toLowerCase().includes(filter.toLowerCase())
  );

  const toggleTopic = (topic: string) => {
    setSelectedTopics(prev =>
      prev.includes(topic)
        ? prev.filter(t => t !== topic)
        : [...prev, topic]
    );
  };

  const toggleAll = () => {
    if (selectedTopics.length === filteredTopics.length) {
      setSelectedTopics([]);
    } else {
      setSelectedTopics(filteredTopics);
    }
  };

  const handleStart = () => {
    if (selectedTopics.length === 0) return;
    const depth: SnapshotConfig = {
      mode,
      maxMessages,
      durationMinutes: null,
      fromTimestamp: mode === 'TIMESTAMP' && fromTimestamp
        ? new Date(fromTimestamp).getTime()
        : null,
    };
    onStart(selectedTopics, depth);
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-slate-100 mb-1">Select Topics</h2>
        <p className="text-sm text-slate-400">Choose the Kafka topics to analyse for process patterns.</p>
      </div>

      {/* Topic filter + list */}
      <div className="border border-primary/20 rounded-xl overflow-hidden">
        <div className="p-3 border-b border-primary/10 bg-primary/5 flex items-center gap-2">
          <span className="material-symbols-outlined text-slate-400 text-base">search</span>
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder-slate-500"
            placeholder="Filter topics..."
            value={filter}
            onChange={e => setFilter(e.target.value)}
          />
          {allTopics.length > 0 && (
            <button
              onClick={toggleAll}
              className="text-xs text-primary hover:text-primary/80 font-medium ml-2"
            >
              {selectedTopics.length === filteredTopics.length ? 'Deselect all' : 'Select all'}
            </button>
          )}
        </div>
        <div className="max-h-64 overflow-y-auto">
          {fetchingTopics ? (
            <div className="p-4 text-slate-500 text-sm text-center">Loading topics...</div>
          ) : filteredTopics.length === 0 ? (
            <div className="p-4 text-slate-500 text-sm text-center">No topics found</div>
          ) : (
            filteredTopics.map(topic => (
              <label
                key={topic}
                className="flex items-center gap-3 px-4 py-2 hover:bg-primary/5 cursor-pointer group"
              >
                <input
                  type="checkbox"
                  checked={selectedTopics.includes(topic)}
                  onChange={() => toggleTopic(topic)}
                  className="rounded border-slate-600 text-primary focus:ring-primary/30 bg-transparent"
                />
                <span className="text-sm font-mono text-slate-200 truncate group-hover:text-primary transition-colors">
                  {topic}
                </span>
              </label>
            ))
          )}
        </div>
        {selectedTopics.length > 0 && (
          <div className="p-2 border-t border-primary/10 bg-primary/5 text-xs text-slate-400">
            {selectedTopics.length} topic{selectedTopics.length > 1 ? 's' : ''} selected
          </div>
        )}
      </div>

      {/* Depth config */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-xs font-medium text-slate-400 mb-1.5">Sampling Mode</label>
          <select
            value={mode}
            onChange={e => setMode(e.target.value as SnapshotConfig['mode'])}
            className="w-full bg-background-dark border border-primary/20 rounded-lg px-3 py-2 text-sm text-slate-100 focus:border-primary/50 outline-none"
          >
            <option value="LATEST_N">Latest N messages</option>
            <option value="EARLIEST">From beginning</option>
            <option value="TIMESTAMP">From timestamp</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-400 mb-1.5">
            {mode === 'TIMESTAMP' ? 'From timestamp' : 'Max messages'}
          </label>
          {mode === 'TIMESTAMP' ? (
            <input
              type="datetime-local"
              value={fromTimestamp}
              onChange={e => setFromTimestamp(e.target.value)}
              className="w-full bg-background-dark border border-primary/20 rounded-lg px-3 py-2 text-sm text-slate-100 focus:border-primary/50 outline-none"
            />
          ) : (
            <input
              type="number"
              min={10}
              max={10000}
              value={maxMessages}
              onChange={e => setMaxMessages(parseInt(e.target.value, 10) || 500)}
              className="w-full bg-background-dark border border-primary/20 rounded-lg px-3 py-2 text-sm text-slate-100 focus:border-primary/50 outline-none"
            />
          )}
        </div>
      </div>

      <button
        onClick={handleStart}
        disabled={selectedTopics.length === 0 || loading}
        className="w-full flex items-center justify-center gap-2 py-3 px-6 bg-primary text-white rounded-xl font-semibold text-sm hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {loading ? (
          <>
            <span className="material-symbols-outlined text-base animate-spin">progress_activity</span>
            Profiling in progress...
          </>
        ) : (
          <>
            <span className="material-symbols-outlined text-base">search</span>
            Start Profiling ({selectedTopics.length} topic{selectedTopics.length !== 1 ? 's' : ''})
          </>
        )}
      </button>
    </div>
  );
};

export default TopicSelectorPanel;
