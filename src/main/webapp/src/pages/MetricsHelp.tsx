import React from 'react';
import { useNavigate } from 'react-router-dom';

const MetricsHelp: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-[#142828] text-[#f5f8f8] p-8 font-display">
      {/* Header */}
      <header className="mb-12 border-l-4 border-primary pl-6">
        <div className="flex items-center gap-3 mb-2">
          <span className="text-[10px] font-bold text-primary bg-primary/10 px-2 py-0.5 rounded tracking-widest uppercase">Portail de Documentation</span>
          <span className="text-[10px] font-bold text-[#10b981] bg-[#10b9811a] px-2 py-0.5 rounded tracking-widest uppercase">System V.1.2</span>
        </div>
        <h1 className="text-4xl font-extrabold text-[#f5f8f8] tracking-tighter uppercase mb-2">
          Aide aux Métriques <span className="text-primary/40 ml-2">/ Module Métriques Kafka SQL</span>
        </h1>
        <p className="text-[#b9cac9] max-w-3xl font-medium">
          Configurez, surveillez et faites évoluer vos flux de données avec une télémétrie haute précision. Ce guide fournit les spécifications techniques pour les ingénieurs.
        </p>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* 1. Introduction */}
        <section className="lg:col-span-8 p-8 bg-[#102222] border border-primary/10 rounded shadow-[0_0_15px_0_rgba(37,244,244,0.05)] relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-4 opacity-10 group-hover:opacity-20 transition-opacity">
            <span className="material-symbols-outlined text-6xl text-primary" style={{ fontVariationSettings: "'FILL' 1" }}>insights</span>
          </div>
          <h2 className="text-xl font-bold text-primary mb-4 flex items-center gap-2 uppercase tracking-tight">
            <span className="material-symbols-outlined text-[22px]">info</span> 01. Introduction
          </h2>
          <div className="grid md:grid-cols-2 gap-8 relative z-10">
            <div>
              <p className="text-sm leading-relaxed text-[#b9cac9] mb-4">
                Le module Métriques permet d'orchestrer la surveillance de vos flux en transformant des requêtes SQL analytiques en indicateurs Prometheus exploitables en temps réel.
              </p>
              <ul className="text-[12px] space-y-2 text-primary/80 font-mono uppercase tracking-wider">
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Business Monitoring</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Technical Performance</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Alerting Avancé</li>
              </ul>
            </div>
            <div className="border-l border-primary/10 pl-8">
              <p className="text-[11px] font-bold text-primary tracking-widest uppercase mb-2">Objectifs Clefs</p>
              <p className="text-xs leading-relaxed text-[#b9cac9]">
                Détectez les anomalies de débit, mesurez la latence infra-milliseconde et assurez l'intégrité de vos données grâce à une intégration native KSQL.
              </p>
            </div>
          </div>
        </section>

        {/* 7. Cas d'usage / KPIs */}
        <section className="lg:col-span-4 row-span-2 bg-[#182d2d] border border-primary/5 p-6 rounded flex flex-col">
          <h3 className="text-xs font-bold text-primary/60 tracking-[0.2em] uppercase mb-6">Indicateurs de Performance (SLA)</h3>
          <div className="space-y-6 flex-1">
            <div className="p-4 bg-[#102222] border-l-2 border-success/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-success uppercase tracking-widest">Disponibilité SQL</span>
                <span className="material-symbols-outlined text-[18px] text-success">check_circle</span>
              </div>
              <p className="text-[24px] font-bold text-[#f5f8f8] leading-none mb-1">99.98%</p>
              <p className="text-[10px] text-[#b9cac9]">Ratio succès/échec du moteur de règles.</p>
            </div>
            <div className="p-4 bg-[#102222] border-l-2 border-warning/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-warning uppercase tracking-widest">Latence Moyenne</span>
                <span className="material-symbols-outlined text-[18px] text-warning">timer</span>
              </div>
              <p className="text-[24px] font-bold text-[#f5f8f8] leading-none mb-1">14 ms</p>
              <p className="text-[10px] text-[#b9cac9]">Temps de scrape Prometheus Actuator.</p>
            </div>
            <div className="p-4 bg-[#102222] border-l-2 border-error/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-error uppercase tracking-widest">Taux d'erreur Technique</span>
                <span className="material-symbols-outlined text-[18px] text-error">error</span>
              </div>
              <p className="text-[24px] font-bold text-[#f5f8f8] leading-none mb-1">0.02 %</p>
              <p className="text-[10px] text-[#b9cac9]">Messages rejetés pour non-conformité SQL.</p>
            </div>
          </div>
          <div className="mt-8 pt-6 border-t border-primary/10">
            <button 
              onClick={() => navigate('/metrics')}
              className="w-full py-3 bg-primary text-[#102222] font-bold text-[12px] uppercase tracking-widest rounded hover:brightness-110 active:scale-95 transition-all shadow-[0_0_15px_rgba(37,244,244,0.2)]">
              Retour aux Métriques
            </button>
          </div>
        </section>

        {/* 3. Modèles de configuration */}
        <section className="lg:col-span-8 p-8 bg-[#102222] border border-primary/10 rounded">
          <div className="flex items-center justify-between mb-8">
            <h2 className="text-xl font-bold text-primary flex items-center gap-3 uppercase tracking-tight">
              <span className="material-symbols-outlined">dashboard_customize</span> 03. Modèles de Configuration
            </h2>
            <span className="text-[10px] font-mono bg-primary/20 text-primary px-3 py-1 rounded-full uppercase">Engine v2.1</span>
          </div>
          <div className="grid md:grid-cols-2 gap-8">
            <div className="space-y-4">
              <div className="bg-[#182d2d] p-4 rounded border border-primary/5 font-mono text-[13px] text-[#25f4f4] relative">
                <div className="absolute top-2 right-2 flex gap-1">
                  <span className="w-2 h-2 rounded-full bg-error/40"></span>
                  <span className="w-2 h-2 rounded-full bg-warning/40"></span>
                  <span className="w-2 h-2 rounded-full bg-success/40"></span>
                </div>
                <span className="text-primary/50 block mb-2">// RAW_SQL Example</span>
                <span className="text-primary">SELECT</span> COUNT(*) <span className="text-primary">AS</span> metric_value,<br/>
                error_code <span className="text-primary">AS</span> label_code<br/>
                <span className="text-primary">FROM</span> "topic_orders"<br/>
                <span className="text-primary">WHERE</span> status = 'FAILED'<br/>
                <span className="text-primary">GROUP BY</span> error_code;
              </div>
              <p className="text-[11px] text-[#b9cac9] italic border-l-2 border-primary/20 pl-4 uppercase font-bold tracking-tight">
                Exemple de requête analytique brute.
              </p>
            </div>
            <div className="space-y-6">
              <div className="p-4 bg-[#102222] rounded border border-[#25f4f41a]">
                <h4 className="text-[11px] font-bold text-primary tracking-widest uppercase mb-1">TOPIC_COUNT_DELTA</h4>
                <p className="text-[10px] text-[#b9cac9]">Compare le débit entre deux topics pour détecter une perte de messages.</p>
              </div>
              <div className="p-4 bg-[#102222] rounded border border-[#25f4f41a]">
                <h4 className="text-[11px] font-bold text-primary tracking-widest uppercase mb-1">TOPIC_TRANSIT_LATENCY</h4>
                <p className="text-[10px] text-[#b9cac9]">Mesure le délai (ms) entre l'ingestion Topic A et la sortie Topic B.</p>
              </div>
            </div>
          </div>
        </section>

        {/* 2. Types de métriques */}
        <section className="lg:col-span-12 p-8 bg-[#182d2d] border border-primary/10 rounded">
          <h2 className="text-xl font-bold text-primary mb-8 flex items-center gap-3 uppercase tracking-tight">
            <span className="material-symbols-outlined">analytics</span> 02. Types de métriques Prometheus
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
            <div className="bg-[#102222]/50 p-6 rounded border border-[#25f4f41a] hover:border-primary/40 transition-all">
              <h4 className="text-primary font-bold text-lg tracking-tight mb-2">GAUGE</h4>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed mb-4">Valeur instantanée pouvant augmenter ou diminuer (stocks, files).</p>
              <span className="px-2 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded">UP / DOWN</span>
            </div>
            <div className="bg-[#102222]/50 p-6 rounded border border-[#25f4f41a] hover:border-primary/40 transition-all">
              <h4 className="text-[#10b981] font-bold text-lg tracking-tight mb-2">COUNTER</h4>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed mb-4">Valeur cumulative monotone. Recommence à zéro au redémarrage.</p>
              <span className="px-2 py-0.5 bg-[#10b9811a] text-[#10b981] text-[10px] font-bold rounded">INCREMENT ONLY</span>
            </div>
            <div className="bg-[#102222]/50 p-6 rounded border border-[#25f4f41a] hover:border-primary/40 transition-all">
              <h4 className="text-[#96d1d0] font-bold text-lg tracking-tight mb-2">SUMMARY</h4>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed mb-4">Calcule des quantiles sur une fenêtre glissante.</p>
              <span className="px-2 py-0.5 bg-[#0f5151] text-[#88c3c2] text-[10px] font-bold rounded">STATISTICAL</span>
            </div>
            <div className="bg-[#102222]/50 p-6 rounded border border-[#25f4f41a] hover:border-primary/40 transition-all">
              <h4 className="text-[#004f50] font-bold text-lg tracking-tight mb-2">TIMER</h4>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed mb-4">Mesure la durée d'événements et le débit associé.</p>
              <span className="px-2 py-0.5 bg-[#00dddd1a] text-[#00dddd] text-[10px] font-bold rounded">LATENCY / MS</span>
            </div>
          </div>
        </section>

        {/* 4. Contraintes SQL & 6. Intégration Prometheus */}
        <section className="lg:col-span-12 p-8 bg-[#102222] border border-primary/10 rounded grid md:grid-cols-2 gap-12">
          <div>
            <h2 className="text-xl font-bold text-primary mb-6 flex items-center gap-3 uppercase tracking-tight">
              <span className="material-symbols-outlined">gavel</span> 04. Contraintes SQL
            </h2>
            <ul className="space-y-4">
              <li className="flex gap-4">
                <span className="material-symbols-outlined text-primary text-[20px]">check_circle</span>
                <div>
                  <span className="font-bold block text-xs uppercase tracking-tight">Alias 'metric_value'</span>
                  <p className="text-[11px] text-[#b9cac9] leading-relaxed">Obligatoire pour la colonne principale de données numériques.</p>
                </div>
              </li>
              <li className="flex gap-4">
                <span className="material-symbols-outlined text-primary text-[20px]">check_circle</span>
                <div>
                  <span className="font-bold block text-xs uppercase tracking-tight">Bounded Scan Limit</span>
                  <p className="text-[11px] text-[#b9cac9] leading-relaxed">Une limite de sécurité (100k messages) est appliquée par intervalle.</p>
                </div>
              </li>
            </ul>
          </div>
          <div>
            <h2 className="text-xl font-bold text-primary mb-6 flex items-center gap-3 uppercase tracking-tight">
              <span className="material-symbols-outlined">sensors</span> 06. Intégration Prometheus
            </h2>
            <div className="bg-[#2c4a4a4d] p-4 rounded border border-[#25f4f41a] space-y-3">
              <div className="flex justify-between text-[11px]">
                <span className="text-[#b9cac9] uppercase font-bold">Scrape Interval :</span>
                <span className="text-primary font-mono">15s - 60s recommended</span>
              </div>
              <div className="flex justify-between text-[11px]">
                <span className="text-[#b9cac9] uppercase font-bold">Naming Convention :</span>
                <span className="text-primary font-mono">kafka_sql_[app]_[metric]</span>
              </div>
              <div className="flex justify-between text-[11px]">
                <span className="text-[#b9cac9] uppercase font-bold">Endpoint Security :</span>
                <span className="text-[#10b981] font-mono">TLS / OAuth2 required</span>
              </div>
            </div>
          </div>
        </section>

        {/* 5. Guide Pas à Pas */}
        <section className="lg:col-span-12 p-8 bg-[#102222] border border-primary/20 rounded-xl shadow-[0_0_25px_-5px_rgba(37,244,244,0.1)]">
          <div className="text-center mb-12">
            <h2 className="text-2xl font-black text-[#f5f8f8] uppercase tracking-tighter mb-2">Guide de Déploiement</h2>
            <p className="text-primary text-[10px] font-bold uppercase tracking-[0.3em]">Quick-Start Integration Guide</p>
          </div>
          <div className="flex flex-col lg:flex-row items-center justify-between gap-4">
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-primary flex items-center justify-center text-[#102222] font-bold mb-4 shadow-[0_0_15px_rgba(37,244,244,0.5)]">1</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Accès & Ajout</h5>
              <p className="text-[10px] text-[#b9cac9] leading-tight">Menu Monitoring &gt; Kafka Metrics. Cliquez sur "Ajouter".</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-[#182d2d] border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">2</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Modèle & SQL</h5>
              <p className="text-[10px] text-[#b9cac9] leading-tight">Choisissez RAW_SQL et rédigez votre requête d'agrégation.</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-[#182d2d] border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">3</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Test & Alertes</h5>
              <p className="text-[10px] text-[#b9cac9] leading-tight">Vérifiez les résultats instantanés et fixez les seuils SLA.</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px]">
              <div className="w-12 h-12 rounded-full bg-[#182d2d] border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">4</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Sauvegarde</h5>
              <p className="text-[10px] text-[#b9cac9] leading-tight">Exposez la métrique sur l'endpoint Prometheus global.</p>
            </div>
          </div>
        </section>

        {/* 8. Dépannage */}
        <section className="lg:col-span-12 p-8 border border-error/20 bg-error/5 rounded-lg">
          <h3 className="text-xl font-bold flex items-center gap-3 text-error uppercase tracking-tight mb-6">
            <span className="material-symbols-outlined">report</span> 08. Dépannage & Erreurs
          </h3>
          <div className="grid md:grid-cols-3 gap-8">
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">"No rows returned"</span>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed">Vérifiez vos filtres WHERE et assurez-vous que les topics contiennent des données sur la fenêtre de scan.</p>
            </div>
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">"Pending Status"</span>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed">Le processeur SQL est en attente de ressources. Augmentez l'intervalle si la requête est lourde.</p>
            </div>
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">Latency Units</span>
              <p className="text-[11px] text-[#b9cac9] leading-relaxed">Les métriques de temps doivent être normalisées en millisecondes pour l'agrégation Grafana native.</p>
            </div>
          </div>
        </section>
      </div>

      {/* System Status Footer */}
      <footer className="mt-16 flex flex-col md:flex-row justify-between items-center gap-6 border-t border-primary/5 pt-8">
        <div className="flex gap-4">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-success animate-pulse"></span>
            <span className="text-[10px] font-bold text-[#b9cac9] uppercase tracking-widest">État Global : Opérationnel</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-bold text-primary uppercase tracking-widest">Ingestion : 1.2M msg/sec</span>
          </div>
        </div>
        <div className="flex gap-8 items-center">
          <span className="text-[10px] text-[#b9cac9] uppercase font-mono">Mise à jour : {new Date().toISOString().replace('T', ' ').substring(0, 19)} UTC</span>
          <div className="flex gap-4">
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">terminal</span>
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">help</span>
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">share</span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default MetricsHelp;
