import React, { useEffect, useState } from 'react';

interface LiveStatusBarProps {
  connected: boolean;
  windowSize: number;
  lastUpdate: number | null;
}

const LiveStatusBar: React.FC<LiveStatusBarProps> = ({ connected, windowSize, lastUpdate }) => {
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

  return (
    <div className="flex items-center gap-6 px-4 py-2.5 bg-surface-container-low border border-outline-variant rounded-xl text-xs">
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

      <div className="h-4 w-px bg-primary/20" />

      {/* Window size */}
      <div className="flex items-center gap-1.5 text-on-surface-variant">
        <span className="material-symbols-outlined text-sm">data_array</span>
        <span>Window:</span>
        <span className="font-mono font-semibold text-on-surface">{windowSize}</span>
        <span>messages</span>
      </div>

      <div className="h-4 w-px bg-primary/20" />

      {/* Last analysis */}
      <div className="flex items-center gap-1.5 text-on-surface-variant">
        <span className="material-symbols-outlined text-sm">schedule</span>
        <span>Last analysis:</span>
        <span className="font-mono font-semibold text-on-surface">{elapsed}</span>
      </div>
    </div>
  );
};

export default LiveStatusBar;
