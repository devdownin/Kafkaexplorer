import React, { createContext, useContext, useState, useCallback } from 'react';

type ToastType = 'success' | 'error' | 'info';

interface ToastItem {
  id: number;
  message: string;
  type: ToastType;
}

interface ToastContextValue {
  toast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextValue>({ toast: () => {} });

export const useToast = () => useContext(ToastContext);

let nextId = 0;

const icons: Record<ToastType, string> = {
  success: 'check_circle',
  error: 'cancel',
  info: 'info',
};

const accents: Record<ToastType, string> = {
  success: 'text-success',
  error: 'text-error',
  info: 'text-primary',
};

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const toast = useCallback((message: string, type: ToastType = 'info') => {
    const id = ++nextId;
    setToasts(prev => [...prev, { id, message, type }]);
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3500);
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none" aria-live="polite" role="status">
        {toasts.map(t => (
          <div
            key={t.id}
            className="toast-in flex items-center gap-2.5 pl-3 pr-4 py-3 rounded-xl border border-outline-variant bg-surface-container-high shadow-2xl text-[13px] font-medium text-on-surface"
          >
            <span className={`material-symbols-outlined text-[18px] ${accents[t.type]}`}>{icons[t.type]}</span>
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};
