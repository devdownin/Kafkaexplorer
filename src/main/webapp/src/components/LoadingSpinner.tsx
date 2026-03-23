import React from 'react';

const LoadingSpinner: React.FC = () => (
  <div className="flex-1 flex items-center justify-center p-12">
    <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
  </div>
);

export default LoadingSpinner;
