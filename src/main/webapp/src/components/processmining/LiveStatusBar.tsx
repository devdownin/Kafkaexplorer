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
    <div className="flex items-center gap-6 px-4 py-2.5 bg-primary/5 border border-primary/20 rounded-xl text-xs">
      {/* Connection status */}
      <div className="flex items-center gap-2">
        <span className="relative flex h-2.5 w-2.5">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
            connected ? 'bg-emerald-400' : 'bg-red-400'
          }`}></span>
          <span className={`relative inline-flex rounded-full h-2.5 w-2.5 ${
            connected ? 'bg-emerald-500' : 'bg-red-500'
          }`}></span>
        </span>
        <span className={`font-medium ${connected ? 'text-emerald-400' : 'text-red-400'}`}>
          {connected ? 'Live' : 'Disconnected'}
        </span>
      </div>

      <div className="h-4 w-px bg-primary/20" />

      {/* Window size */}
      <div className="flex items-center gap-1.5 text-slate-400">
        <span className="material-symbols-outlined text-sm">data_array</span>
        <span>Window:</span>
        <span className="font-mono font-semibold text-slate-200">{windowSize}</span>
        <span>messages</span>
      </div>

      <div className="h-4 w-px bg-primary/20" />

      {/* Last analysis */}
      <div className="flex items-center gap-1.5 text-slate-400">
        <span className="material-symbols-outlined text-sm">schedule</span>
        <span>Last analysis:</span>
        <span className="font-mono font-semibold text-slate-200">{elapsed}</span>
      </div>
    </div>
  );
};

export default LiveStatusBar;
