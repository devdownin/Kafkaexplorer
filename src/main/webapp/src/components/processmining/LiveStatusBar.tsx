import React, { useEffect, useState } from 'react';

/** Ingestion counters sent with every WINDOW_STATS event, emitted when a window is cut. */
export interface LiveWindowStats {
  windowSize: number;
  timestamp: number;
  messagesReceived: number;
  bytesReceived: number;
  maxPayloadBytes: number;
  droppedMessages: number;
  parseFailures: number;
  distinctShapes: number;
}

interface LiveStatusBarProps {
  connected: boolean;
  windowSize: number;
  stats?: LiveWindowStats | null;
  lastUpdate: number | null;
}

const formatBytes = (bytes: number): string => {
  if (!bytes) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const LiveStatusBar: React.FC<LiveStatusBarProps> = ({ connected, windowSize, stats, lastUpdate }) => {
  const [elapsed, setElapsed] = useState<string>('--');

  useEffect(() => {
    if (!lastUpdate) {
      setElapsed('--');
      return;
    }

    const update = () => {
      const seconds = Math.floor((Date.now() - lastUpdate) / 1000);
      if (seconds < 60) {
        setElapsed(`${seconds}s ago`);
      } else {
        const minutes = Math.floor(seconds / 60);
        setElapsed(`${minutes}m ${seconds % 60}s ago`);
      }
    };

    update();
    const interval = setInterval(update, 1000);
    return () => clearInterval(interval);
  }, [lastUpdate]);

  const divider = <div className="h-4 w-px bg-primary/20" />;

  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2.5 bg-surface-container-low border border-outline-variant rounded-xl text-xs">
      {/* Connection status */}
      <div className="flex items-center gap-2">
        <span className="relative flex h-2.5 w-2.5">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
            connected ? 'bg-success' : 'bg-error'
          }`}></span>
          <span className={`relative inline-flex rounded-full h-2.5 w-2.5 ${
            connected ? 'bg-success' : 'bg-error'
          }`}></span>
        </span>
        <span className={`font-medium ${connected ? 'text-success' : 'text-error'}`}>
          {connected ? 'Live' : 'Disconnected'}
        </span>
      </div>

      {divider}

      {/* Window size */}
      <div className="flex items-center gap-1.5 text-on-surface-variant">
        <span className="material-symbols-outlined text-sm">data_array</span>
        <span>Window:</span>
        <span className="font-mono font-semibold text-on-surface">{windowSize}</span>
        <span>messages</span>
      </div>

      {/* Ingested volume — payloads are digested, so this is what was read, not what was sent */}
      {stats && stats.bytesReceived > 0 && (
        <>
          {divider}
          <div className="flex items-center gap-1.5 text-on-surface-variant">
            <span className="material-symbols-outlined text-sm">download</span>
            <span>Ingested:</span>
            <span className="font-mono font-semibold text-on-surface">
              {formatBytes(stats.bytesReceived)}
            </span>
            <span>(max {formatBytes(stats.maxPayloadBytes)}/msg)</span>
          </div>
        </>
      )}

      {/* Distinct payload structures in the window */}
      {stats && stats.distinctShapes > 0 && (
        <>
          {divider}
          <div className="flex items-center gap-1.5 text-on-surface-variant">
            <span className="material-symbols-outlined text-sm">schema</span>
            <span>Structures:</span>
            <span className="font-mono font-semibold text-on-surface">{stats.distinctShapes}</span>
          </div>
        </>
      )}

      {/* Backpressure: the analysis is slower than ingestion */}
      {stats && stats.droppedMessages > 0 && (
        <>
          {divider}
          <div
            className="flex items-center gap-1.5 text-warning"
            title="Analysis is slower than ingestion — the oldest messages of the window were dropped"
          >
            <span className="material-symbols-outlined text-sm">speed</span>
            <span>Dropped:</span>
            <span className="font-mono font-semibold">{stats.droppedMessages}</span>
          </div>
        </>
      )}

      {stats && stats.parseFailures > 0 && (
        <>
          {divider}
          <div className="flex items-center gap-1.5 text-error" title="Payloads that could not be parsed">
            <span className="material-symbols-outlined text-sm">error</span>
            <span>Unparsed:</span>
            <span className="font-mono font-semibold">{stats.parseFailures}</span>
          </div>
        </>
      )}

      {divider}

      {/* Last window */}
      <div className="flex items-center gap-1.5 text-on-surface-variant">
        <span className="material-symbols-outlined text-sm">schedule</span>
        <span>Last window:</span>
        <span className="font-mono font-semibold text-on-surface">{elapsed}</span>
      </div>
    </div>
  );
};

export default LiveStatusBar;
