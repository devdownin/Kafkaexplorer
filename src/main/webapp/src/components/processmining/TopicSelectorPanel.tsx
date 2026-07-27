import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Field, Input, NumberInput, Select } from '../ui';

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
        <h2 className="text-lg font-semibold text-on-surface mb-1">Select Topics</h2>
        <p className="text-sm text-on-surface-variant">Choose the Kafka topics to analyse for process patterns.</p>
        <p className="text-xs text-on-surface-variant mt-1">
          Snapshot limits are applied per topic to keep multi-topic analysis balanced.
        </p>
      </div>

      {/* Topic filter + list */}
      <div className="border border-outline-variant rounded-xl overflow-hidden">
        <div className="p-3 border-b border-outline-variant/60 bg-primary/5 flex items-center gap-2">
          <span className="material-symbols-outlined text-on-surface-variant text-base">search</span>
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder-on-surface-variant"
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
            <div className="p-4 text-on-surface-variant text-sm text-center">Loading topics...</div>
          ) : filteredTopics.length === 0 ? (
            <div className="p-4 text-on-surface-variant text-sm text-center">No topics found</div>
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
                  className="rounded border-outline-variant text-primary focus:ring-primary/30 bg-transparent"
                />
                <span className="text-sm font-mono text-on-surface truncate group-hover:text-primary transition-colors">
                  {topic}
                </span>
              </label>
            ))
          )}
        </div>
        {selectedTopics.length > 0 && (
          <div className="p-2 border-t border-outline-variant/60 bg-primary/5 text-xs text-on-surface-variant">
            {selectedTopics.length} topic{selectedTopics.length > 1 ? 's' : ''} selected
          </div>
        )}
      </div>

      {/* Depth config */}
      <div className="grid grid-cols-2 gap-4">
        <Field label="Sampling Mode">
          {p => (
            <Select {...p} value={mode} onChange={e => setMode(e.target.value as SnapshotConfig['mode'])}>
              <option value="LATEST_N">Latest N messages</option>
              <option value="EARLIEST">From beginning</option>
              <option value="TIMESTAMP">From timestamp</option>
            </Select>
          )}
        </Field>
        <Field label={mode === 'TIMESTAMP' ? 'From timestamp' : 'Max messages per topic'}>
          {p => (mode === 'TIMESTAMP' ? (
            <Input {...p} type="datetime-local" value={fromTimestamp}
              onChange={e => setFromTimestamp(e.target.value)} />
          ) : (
            // `parseInt(v) || 500` renvoyait au défaut dès que le champ était vidé pour ressaisir.
            <NumberInput {...p} min={10} max={10000} fallback={500}
              value={maxMessages} onChange={setMaxMessages} />
          ))}
        </Field>
      </div>

      <button
        onClick={handleStart}
        disabled={selectedTopics.length === 0 || loading}
        className="w-full flex items-center justify-center gap-2 py-3 px-6 bg-primary text-on-primary rounded-xl font-semibold text-sm hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
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
