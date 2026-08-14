// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react';

interface ErrorBannerProps {
  message: string;
  onRetry?: () => void;
}

/** Bandeau d'erreur inline : icône, message et action de réessai optionnelle. */
const ErrorBanner: React.FC<ErrorBannerProps> = ({ message, onRetry }) => (
  <div className="flex flex-col items-center gap-4 py-12 px-6 text-center" role="alert">
    <div className="w-11 h-11 rounded-xl bg-error/10 border border-error/25 flex items-center justify-center">
      <span aria-hidden="true" className="material-symbols-outlined text-[22px] text-error">error</span>
    </div>
    <p className="text-[13px] text-on-surface max-w-md leading-relaxed">{message}</p>
    {onRetry && (
      <button
        onClick={onRetry}
        className="inline-flex items-center gap-2 h-9 px-4 rounded-md border border-outline-variant text-on-surface text-[13px] font-medium hover:bg-surface-container-high transition-colors"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[16px]">refresh</span>
        Retry
      </button>
    )}
  </div>
);

export default ErrorBanner;
