import { lazy, Suspense } from 'react';
import type { FC } from 'react';
import { createBrowserRouter, Link, Outlet, RouterProvider } from 'react-router-dom';
import Layout from './components/Layout';
import { ToastProvider } from './components/Toast';
import { ProgressBar, ConfirmProvider } from './components/ui';

/* Code-splitting : chaque page est chargée à la demande (lazy loading), ce qui
   réduit le bundle initial et accélère le premier rendu. */
const Dashboard = lazy(() => import('./pages/Dashboard'));
const QueryWorkbench = lazy(() => import('./pages/QueryWorkbench'));
const TopicExplorer = lazy(() => import('./pages/TopicExplorer'));
const Compare = lazy(() => import('./pages/Compare'));
const Lineage = lazy(() => import('./pages/Lineage'));
const Metrics = lazy(() => import('./pages/Metrics'));
const Audit = lazy(() => import('./pages/Audit'));
const StreamFlow = lazy(() => import('./pages/StreamFlow'));
const Config = lazy(() => import('./pages/Config'));
const Help = lazy(() => import('./pages/Help'));
const MetricsHelp = lazy(() => import('./pages/MetricsHelp'));
const Cluster = lazy(() => import('./pages/Cluster'));
const ProcessMining = lazy(() => import('./pages/ProcessMining'));

const PageFallback: FC = () => (
  <div className="flex items-center justify-center h-full min-h-[60vh]">
    <ProgressBar label="Loading" />
  </div>
);

const NotFound: FC = () => (
  <div className="flex flex-col items-center justify-center h-full min-h-[60vh] text-center px-6">
    <p className="text-[64px] font-semibold tracking-tight text-primary/80 tabular-nums leading-none">404</p>
    <h1 className="mt-4 text-lg font-semibold text-on-surface">Page not found</h1>
    <p className="mt-1 text-[13px] text-on-surface-variant max-w-sm">
      The page you're looking for doesn't exist or has moved.
    </p>
    <Link
      to="/"
      className="mt-5 inline-flex items-center gap-2 h-9 px-4 rounded-md bg-primary text-on-primary text-[13px] font-medium hover:bg-primary-fixed transition-colors"
    >
      <span aria-hidden="true" className="material-symbols-outlined text-[16px]">arrow_back</span>
      Back to Dashboard
    </Link>
  </div>
);

/** Coquille commune à toutes les routes : le `Layout` et la frontière de chargement. */
const Shell: FC = () => (
  <Layout>
    <Suspense fallback={<PageFallback />}>
      <Outlet />
    </Suspense>
  </Layout>
);

/*
 * Routeur « data » (`createBrowserRouter`) plutôt que `<BrowserRouter>`. Aucun loader n'est
 * utilisé, mais `useBlocker` — le seul moyen d'intercepter une navigation *interne* avant qu'elle
 * n'ait lieu — n'existe que là : sous `<BrowserRouter>` il lève une invariant. C'est ce qui permet
 * à un écran aux réglages non enregistrés de demander confirmation au lieu de les perdre en
 * silence, `beforeunload` ne couvrant que le rechargement et la fermeture d'onglet.
 *
 * Créé au niveau du module : le reconstruire à chaque rendu réinitialiserait l'historique.
 */
const router = createBrowserRouter([
  {
    element: <Shell />,
    children: [
      { path: '/', element: <Dashboard /> },
      { path: '/query', element: <QueryWorkbench /> },
      { path: '/topic/:name', element: <TopicExplorer /> },
      { path: '/compare', element: <Compare /> },
      { path: '/lineage', element: <Lineage /> },
      { path: '/metrics', element: <Metrics /> },
      { path: '/metrics/help', element: <MetricsHelp /> },
      { path: '/audit', element: <Audit /> },
      { path: '/stream-flow', element: <StreamFlow /> },
      { path: '/config', element: <Config /> },
      { path: '/help', element: <Help /> },
      { path: '/cluster', element: <Cluster /> },
      { path: '/process-mining', element: <ProcessMining /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

const App: FC = () => {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={router} />
      </ConfirmProvider>
    </ToastProvider>
  );
};

export default App;
