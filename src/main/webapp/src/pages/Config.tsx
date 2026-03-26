import React, { useState, useEffect } from 'react';
import axios from 'axios';
import LoadingSpinner from '../components/LoadingSpinner';

interface ClusterConfig {
  bootstrapServers: string;
  mode: string;
  truststorePath?: string;
  truststorePassword?: string;
  keystorePath?: string;
  keystorePassword?: string;
  keyPassword?: string;
  confluentKey?: string;
  confluentSecret?: string;
  isConnected?: boolean;
}

const MODES = [
  { value: 'PLAIN', label: 'PLAIN', description: 'No authentication' },
  { value: 'SSL', label: 'SSL / mTLS', description: 'Certificate-based auth' },
  { value: 'CONFLUENT_CLOUD', label: 'Confluent Cloud', description: 'SASL/SSL with API keys' },
];

const Config: React.FC = () => {
  const [config, setConfig] = useState<ClusterConfig>({
    bootstrapServers: 'localhost:9092',
    mode: 'PLAIN',
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [testResult, setTestResult] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const res = await axios.get<ClusterConfig>('/api/config');
        setConfig(res.data);
      } catch {
        // Backend may not expose REST config yet - use defaults
      } finally {
        setLoading(false);
      }
    };
    fetchConfig();
  }, []);

  const handleSave = async () => {
    const validationErr = validateConfig();
    if (validationErr) { setError(validationErr); return; }
    setSaving(true);
    setError(null);
    setSaveSuccess(false);
    try {
      await axios.post('/api/config', config);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch {
      setError('Failed to save configuration.');
    } finally {
      setSaving(false);
    }
  };

  const handleTestConnection = async () => {
    const validationErr = validateConfig();
    if (validationErr) { setError(validationErr); return; }
    setTesting(true);
    setTestResult(null);
    try {
      const res = await axios.post<ClusterConfig>('/api/config', config);
      setTestResult(res.data.isConnected ?? false);
    } catch {
      setTestResult(false);
    } finally {
      setTesting(false);
    }
  };

  const validateConfig = (): string | null => {
    const servers = config.bootstrapServers?.trim() ?? '';
    if (!servers) return 'Bootstrap servers are required.';
    const parts = servers.split(',').map(s => s.trim()).filter(Boolean);
    for (const part of parts) {
      if (!/^[^\s:]+:\d{1,5}$/.test(part)) {
        return `Invalid format: "${part}". Expected host:port (e.g. localhost:9092).`;
      }
    }
    if (config.mode === 'CONFLUENT_CLOUD') {
      if (!config.confluentKey?.trim()) return 'API Key is required for Confluent Cloud.';
      if (!config.confluentSecret?.trim()) return 'API Secret is required for Confluent Cloud.';
    }
    if (config.mode === 'SSL') {
      if (!config.truststorePath?.trim()) return 'Truststore path is required for SSL.';
      if (!config.keystorePath?.trim()) return 'Keystore path is required for SSL.';
    }
    return null;
  };

  const set = (key: keyof ClusterConfig, value: string) => setConfig(prev => ({ ...prev, [key]: value }));

  const inputClass = "w-full bg-primary/5 border border-primary/20 rounded-lg px-3 py-2.5 text-sm text-slate-100 font-mono placeholder:text-slate-600 focus:ring-1 focus:ring-primary outline-none";
  const labelClass = "block text-[10px] uppercase font-bold tracking-wider text-slate-500 mb-1.5";

  if (loading) return <LoadingSpinner />;

  return (
    <div className="p-6 max-w-3xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold">Configuration</h1>
        <p className="text-slate-500 dark:text-slate-400 text-sm mt-1">
          Manage Kafka cluster connection and security settings.
        </p>
      </div>

      {/* Connection Status Banner */}
      <div className={`rounded-xl border p-4 flex items-center gap-3 ${
        config.isConnected
          ? 'bg-emerald-500/5 border-emerald-500/20'
          : 'bg-slate-500/5 border-slate-500/20'
      }`}>
        <span className="relative flex h-3 w-3">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${config.isConnected ? 'bg-emerald-400' : 'bg-slate-500'}`} />
          <span className={`relative inline-flex rounded-full h-3 w-3 ${config.isConnected ? 'bg-emerald-500' : 'bg-slate-500'}`} />
        </span>
        <div>
          <p className={`text-sm font-bold ${config.isConnected ? 'text-emerald-400' : 'text-slate-400'}`}>
            {config.isConnected ? 'Connected' : 'Not connected'}
          </p>
          <p className="text-xs text-slate-500">{config.bootstrapServers}</p>
        </div>
        {testResult !== null && (
          <div className={`ml-auto flex items-center gap-1.5 text-xs font-bold ${testResult ? 'text-emerald-400' : 'text-red-400'}`}>
            <span className="material-symbols-outlined text-sm">{testResult ? 'check_circle' : 'cancel'}</span>
            {testResult ? 'Connection successful' : 'Connection failed'}
          </div>
        )}
      </div>

      {/* Cluster Connection */}
      <div className="rounded-xl border border-primary/10 bg-primary/5 overflow-hidden">
        <div className="p-4 border-b border-primary/10 flex items-center gap-3">
          <span className="material-symbols-outlined text-primary">lan</span>
          <h2 className="font-bold text-slate-100">Cluster Connection</h2>
        </div>
        <div className="p-5 space-y-5">
          {/* Bootstrap Servers */}
          <div>
            <label className={labelClass}>Bootstrap Servers</label>
            <input
              type="text"
              value={config.bootstrapServers}
              onChange={e => set('bootstrapServers', e.target.value)}
              placeholder="localhost:9092"
              className={inputClass}
            />
            <p className="text-[10px] text-slate-500 mt-1">Comma-separated list of host:port pairs.</p>
          </div>

          {/* Security Mode */}
          <div>
            <label className={labelClass}>Security Mode</label>
            <div className="grid grid-cols-3 gap-3">
              {MODES.map(mode => (
                <button
                  key={mode.value}
                  onClick={() => set('mode', mode.value)}
                  className={`p-3 rounded-lg border text-left transition-all ${
                    config.mode === mode.value
                      ? 'border-primary bg-primary/10 text-slate-100'
                      : 'border-primary/10 bg-background-dark/30 text-slate-400 hover:border-primary/30'
                  }`}
                >
                  <p className="text-xs font-bold">{mode.label}</p>
                  <p className="text-[10px] text-slate-500 mt-0.5">{mode.description}</p>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* SSL Config */}
      {config.mode === 'SSL' && (
        <div className="rounded-xl border border-primary/10 bg-primary/5 overflow-hidden">
          <div className="p-4 border-b border-primary/10 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">lock</span>
            <h2 className="font-bold text-slate-100">SSL / mTLS Settings</h2>
          </div>
          <div className="p-5 grid grid-cols-2 gap-4">
            <div>
              <label className={labelClass}>Truststore Path</label>
              <input type="text" value={config.truststorePath ?? ''} onChange={e => set('truststorePath', e.target.value)} placeholder="/path/to/truststore.jks" className={inputClass} />
            </div>
            <div>
              <label className={labelClass}>Truststore Password</label>
              <input type="password" value={config.truststorePassword ?? ''} onChange={e => set('truststorePassword', e.target.value)} className={inputClass} />
            </div>
            <div>
              <label className={labelClass}>Keystore Path</label>
              <input type="text" value={config.keystorePath ?? ''} onChange={e => set('keystorePath', e.target.value)} placeholder="/path/to/keystore.jks" className={inputClass} />
            </div>
            <div>
              <label className={labelClass}>Keystore Password</label>
              <input type="password" value={config.keystorePassword ?? ''} onChange={e => set('keystorePassword', e.target.value)} className={inputClass} />
            </div>
            <div>
              <label className={labelClass}>Key Password</label>
              <input type="password" value={config.keyPassword ?? ''} onChange={e => set('keyPassword', e.target.value)} className={inputClass} />
            </div>
          </div>
        </div>
      )}

      {/* Confluent Cloud Config */}
      {config.mode === 'CONFLUENT_CLOUD' && (
        <div className="rounded-xl border border-primary/10 bg-primary/5 overflow-hidden">
          <div className="p-4 border-b border-primary/10 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">cloud</span>
            <h2 className="font-bold text-slate-100">Confluent Cloud Settings</h2>
          </div>
          <div className="p-5 grid grid-cols-2 gap-4">
            <div>
              <label className={labelClass}>API Key</label>
              <input type="text" value={config.confluentKey ?? ''} onChange={e => set('confluentKey', e.target.value)} placeholder="YOUR_API_KEY" className={inputClass} />
            </div>
            <div>
              <label className={labelClass}>API Secret</label>
              <input type="password" value={config.confluentSecret ?? ''} onChange={e => set('confluentSecret', e.target.value)} placeholder="YOUR_API_SECRET" className={inputClass} />
            </div>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3 flex items-center gap-2 text-red-400 text-sm">
          <span className="material-symbols-outlined text-sm">warning</span>
          {error}
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center justify-between pt-2">
        <button
          onClick={handleTestConnection}
          disabled={testing || saving}
          className="flex items-center gap-2 px-4 py-2.5 rounded-lg border border-primary/30 text-primary font-bold text-sm hover:bg-primary/10 disabled:opacity-50 transition-all"
        >
          {testing ? (
            <span className="material-symbols-outlined animate-spin text-lg">refresh</span>
          ) : (
            <span className="material-symbols-outlined text-lg">wifi_tethering</span>
          )}
          {testing ? 'Testing...' : 'Test Connection'}
        </button>

        <button
          onClick={handleSave}
          disabled={saving || testing}
          className="flex items-center gap-2 px-6 py-2.5 rounded-lg bg-primary text-background-dark font-bold text-sm hover:brightness-110 disabled:opacity-50 transition-all"
        >
          {saving ? (
            <span className="material-symbols-outlined animate-spin text-lg">refresh</span>
          ) : saveSuccess ? (
            <span className="material-symbols-outlined text-lg">check_circle</span>
          ) : (
            <span className="material-symbols-outlined text-lg">save</span>
          )}
          {saving ? 'Saving...' : saveSuccess ? 'Saved!' : 'Save Configuration'}
        </button>
      </div>
    </div>
  );
};

export default Config;
