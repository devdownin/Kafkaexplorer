import { lazy, Suspense } from 'react';
import type { FC } from 'react';
import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
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

const App: FC = () => {
  return (
    <ToastProvider>
      <ConfirmProvider>
      <Router>
        <Layout>
          <Suspense fallback={<PageFallback />}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/query" element={<QueryWorkbench />} />
              <Route path="/topic/:name" element={<TopicExplorer />} />
              <Route path="/compare" element={<Compare />} />
              <Route path="/lineage" element={<Lineage />} />
              <Route path="/metrics" element={<Metrics />} />
              <Route path="/metrics/help" element={<MetricsHelp />} />
              <Route path="/audit" element={<Audit />} />
              <Route path="/stream-flow" element={<StreamFlow />} />
              <Route path="/config" element={<Config />} />
              <Route path="/help" element={<Help />} />
              <Route path="/cluster" element={<Cluster />} />
              <Route path="/process-mining" element={<ProcessMining />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Suspense>
        </Layout>
      </Router>
      </ConfirmProvider>
    </ToastProvider>
  );
};

export default App;
