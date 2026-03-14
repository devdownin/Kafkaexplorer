import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import QueryWorkbench from './pages/QueryWorkbench';
import TopicExplorer from './pages/TopicExplorer';

const App: React.FC = () => {
  return (
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/query" element={<QueryWorkbench />} />
          <Route path="/topic/:name" element={<TopicExplorer />} />
          <Route path="/config" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Configuration coming soon</div>} />
          <Route path="/lineage" element={<div className="p-8 text-slate-500 font-bold uppercase tracking-widest">Lineage visualization coming soon</div>} />
          <Route path="*" element={<div className="p-8">404 - Page Not Found</div>} />
        </Routes>
      </Layout>
    </Router>
  );
};

export default App;
