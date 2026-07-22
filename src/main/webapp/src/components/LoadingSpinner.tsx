import React from 'react';

/** Indicateur de chargement pleine zone (icône rotative du design system). */
const LoadingSpinner: React.FC = () => (
  <div className="flex-1 flex items-center justify-center p-12" role="status" aria-live="polite">
    <span aria-hidden="true" className="material-symbols-outlined animate-spin text-[32px] text-primary">progress_activity</span>
    <span className="sr-only">Loading</span>
  </div>
);

export default LoadingSpinner;
