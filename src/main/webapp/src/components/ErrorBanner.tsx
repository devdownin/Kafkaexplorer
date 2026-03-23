import React from 'react';

interface ErrorBannerProps {
  message: string;
  onRetry?: () => void;
}

const ErrorBanner: React.FC<ErrorBannerProps> = ({ message, onRetry }) => (
  <div className="p-8 flex flex-col items-center gap-4">
    <div className="text-red-400 flex items-center gap-2">
      <span className="material-symbols-outlined">warning</span>
      {message}
    </div>
    {onRetry && (
      <button
        onClick={onRetry}
        className="flex items-center gap-2 px-4 py-2 rounded-lg border border-primary/30 text-primary text-sm font-bold hover:bg-primary/10 transition-colors"
      >
        <span className="material-symbols-outlined text-base">refresh</span>
        Retry
      </button>
    )}
  </div>
);

export default ErrorBanner;
