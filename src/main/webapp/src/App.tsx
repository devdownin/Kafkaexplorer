import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import { ToastProvider } from './components/Toast';
import Dashboard from './pages/Dashboard';
import QueryWorkbench from './pages/QueryWorkbench';
import TopicExplorer from './pages/TopicExplorer';
import Compare from './pages/Compare';
import Lineage from './pages/Lineage';
import Metrics from './pages/Metrics';
import Audit from './pages/Audit';
import StreamFlow from './pages/StreamFlow';
import Config from './pages/Config';
import Help from './pages/Help';
import Cluster from './pages/Cluster';

const App: React.FC = () => {
  return (
    <ToastProvider>
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/query" element={<QueryWorkbench />} />
          <Route path="/topic/:name" element={<TopicExplorer />} />
          <Route path="/compare" element={<Compare />} />
          <Route path="/lineage" element={<Lineage />} />
          <Route path="/metrics" element={<Metrics />} />
          <Route path="/audit" element={<Audit />} />
          <Route path="/stream-flow" element={<StreamFlow />} />
          <Route path="/config" element={<Config />} />
          <Route path="/help" element={<Help />} />
          <Route path="/cluster" element={<Cluster />} />
          <Route path="*" element={<div className="p-8">404 - Page Not Found</div>} />
        </Routes>
      </Layout>
    </Router>
    </ToastProvider>
  );
};

export default App;
