import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import QueryWorkbench from './pages/QueryWorkbench';
import TopicExplorer from './pages/TopicExplorer';
import Compare from './pages/Compare';
import Lineage from './pages/Lineage';
import Audit from './pages/Audit';
import StreamFlow from './pages/StreamFlow';

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
          <Route path="/audit" element={<Audit />} />
          <Route path="/stream-flow" element={<StreamFlow />} />
          <Route path="/config" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Configuration coming soon</div>} />
          <Route path="*" element={<div className="p-8">404 - Page Not Found</div>} />
        </Routes>
      </Layout>
    </Router>
  );
};

export default App;
