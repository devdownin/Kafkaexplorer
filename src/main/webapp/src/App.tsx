import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import QueryWorkbench from './pages/QueryWorkbench';
import TopicExplorer from './pages/TopicExplorer';
import Compare from './pages/Compare';
import Lineage from './pages/Lineage';
import Metrics from './pages/Metrics';

const App: React.FC = () => {
  return (
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/query" element={<QueryWorkbench />} />
          <Route path="/topic/:name" element={<TopicExplorer />} />
          <Route path="/compare" element={<Compare />} />
          <Route path="/lineage" element={<Lineage />} />
          <Route path="/metrics" element={<Metrics />} />
          <Route path="/audit" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Audit logs coming soon</div>} />
          <Route path="/stream-flow" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Stream Flow coming soon</div>} />
          <Route path="/config" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Configuration coming soon</div>} />
          <Route path="*" element={<div className="p-8">404 - Page Not Found</div>} />
        </Routes>
      </Layout>
    </Router>
  );
};

export default App;
