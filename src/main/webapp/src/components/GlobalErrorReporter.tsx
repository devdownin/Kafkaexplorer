import { useEffect } from 'react';
import type { FC } from 'react';
import { useToast } from './Toast';
import { installGlobalErrorReporting } from '../globalErrors';

/**
 * Renders nothing; attaches the global error listeners for the life of the app.
 *
 * It lives inside `ToastProvider` because that is the only way to reach `useToast` — the
 * listeners have to be installed from within the tree that owns the toasts, not from
 * `main.tsx`.
 */
const GlobalErrorReporter: FC = () => {
  const { toast } = useToast();

  useEffect(
    () => installGlobalErrorReporting(message => toast(message, 'error')),
    [toast],
  );

  return null;
};

export default GlobalErrorReporter;
