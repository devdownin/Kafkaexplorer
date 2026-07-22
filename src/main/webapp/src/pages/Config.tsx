import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { PageHeader, Button, CardSkeleton } from '../components/ui';

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
  llmProvider: 'ANTHROPIC' | 'OPENAI_COMPATIBLE' | 'OLLAMA' | 'SPECTRA';
  llmProviderLabel?: string;
  llmApiKey?: string;
  llmApiKeyConfigured?: boolean;
  llmApiKeyRequired?: boolean;
  llmBaseUrl: string;
  llmModel: string;
  llmUseRag?: boolean;
  llmCollection?: string;
  llmRequestTimeoutSeconds?: number;
  llmMaxTokens: number;
  llmSnapshotWindowSize: number;
  llmSnapshotWindowTimeoutSeconds: number;
  llmLocalDeployment?: boolean;
}

const MODES = [
  { value: 'PLAIN', label: 'PLAIN', description: 'No authentication' },
  { value: 'SSL', label: 'SSL / mTLS', description: 'Certificate-based auth' },
  { value: 'CONFLUENT_CLOUD', label: 'Confluent Cloud', description: 'SASL/SSL with API keys' },
];

const LLM_PROVIDERS = [
  { value: 'ANTHROPIC', label: 'Anthropic', description: 'Hosted Claude models' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI-compatible', description: 'vLLM, LM Studio or compatible gateways' },
  { value: 'OLLAMA', label: 'Ollama', description: 'Lightweight local open-source models' },
  { value: 'SPECTRA', label: 'SpectraLLM', description: 'Local SpectraLLM instance (RAG + fine-tuned models)' },
] as const;

const Config: React.FC = () => {
  const [config, setConfig] = useState<ClusterConfig>({
    bootstrapServers: 'localhost:9092',
    mode: 'PLAIN',
    llmProvider: 'OLLAMA',
    llmBaseUrl: 'http://localhost:11434/v1',
    llmModel: 'qwen3:4b',
    llmMaxTokens: 4096,
    llmSnapshotWindowSize: 100,
    llmSnapshotWindowTimeoutSeconds: 30,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [testResult, setTestResult] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [llmTesting, setLlmTesting] = useState(false);
  const [llmTestResult, setLlmTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const res = await axios.get<ClusterConfig>('/api/config');
        setConfig(prev => ({ ...prev, ...res.data }));
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
      const res = await axios.post<ClusterConfig>('/api/config', config);
      setConfig(prev => ({ ...prev, ...res.data }));
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
      setConfig(prev => ({ ...prev, ...res.data }));
      setTestResult(res.data.isConnected ?? false);
    } catch {
      setTestResult(false);
    } finally {
      setTesting(false);
    }
  };

  const handleTestLlm = async () => {
    const validationErr = validateConfig();
    if (validationErr) { setError(validationErr); return; }
    setLlmTesting(true);
    setLlmTestResult(null);
    setError(null);
    try {
      // Persist current settings first so the server tests against the selected provider.
      await axios.post('/api/config', config);
      const res = await axios.post<{ ok: boolean; message: string }>('/api/config/test-llm');
      setLlmTestResult(res.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'LLM test failed';
      setLlmTestResult({ ok: false, message: msg });
    } finally {
      setLlmTesting(false);
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
    // SpectraLLM picks its own served model, so the model field is optional for it.
    if (config.llmProvider !== 'SPECTRA' && !config.llmModel?.trim()) {
      return 'An LLM model is required for process mining.';
    }
    if (config.llmProvider !== 'OLLAMA' && !config.llmBaseUrl?.trim()) {
      return 'An LLM base URL is required for hosted, OpenAI-compatible or SpectraLLM providers.';
    }
    if (config.llmProvider === 'ANTHROPIC'
      && !config.llmApiKeyConfigured
      && !config.llmApiKey?.trim()) {
      return 'An Anthropic API key is required when the provider is Anthropic.';
    }
    if (!Number.isFinite(config.llmMaxTokens) || config.llmMaxTokens < 256) {
      return 'LLM max tokens must be at least 256.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowSize) || config.llmSnapshotWindowSize < 10) {
      return 'Live analysis window size must be at least 10 messages.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowTimeoutSeconds) || config.llmSnapshotWindowTimeoutSeconds < 5) {
      return 'Live analysis timeout must be at least 5 seconds.';
    }
    return null;
  };

  const set = (key: keyof ClusterConfig, value: string) => setConfig(prev => ({ ...prev, [key]: value }));
  const setNumber = (key: keyof ClusterConfig, value: number) => setConfig(prev => ({ ...prev, [key]: value }));

  const applyLlmProvider = (provider: ClusterConfig['llmProvider']) => {
    setConfig(prev => {
      const next: ClusterConfig = { ...prev, llmProvider: provider };
      if (provider === 'ANTHROPIC') {
        if (!prev.llmBaseUrl || prev.llmBaseUrl === 'http://localhost:11434/v1') {
          next.llmBaseUrl = 'https://api.anthropic.com';
        }
        if (!prev.llmModel) {
          next.llmModel = 'claude-3-5-sonnet-20241022';
        }
      }
      if (provider === 'OLLAMA') {
        if (!prev.llmBaseUrl || prev.llmBaseUrl === 'https://api.anthropic.com') {
          next.llmBaseUrl = 'http://localhost:11434/v1';
        }
        if (!prev.llmModel || prev.llmModel.startsWith('claude-')) {
          next.llmModel = 'qwen3:4b';
        }
        next.llmApiKey = '';
      }
      if (provider === 'SPECTRA') {
        if (!prev.llmBaseUrl
          || prev.llmBaseUrl === 'https://api.anthropic.com'
          || prev.llmBaseUrl === 'http://localhost:11434/v1') {
          next.llmBaseUrl = 'http://localhost:8080';
        }
        // SpectraLLM serves its own configured model; no per-request model to send.
        next.llmApiKey = '';
      }
      return next;
    });
  };

  const inputClass = "w-full bg-surface-container-low border border-outline-variant rounded-md px-3 py-2.5 text-[13px] text-on-surface font-mono placeholder:text-outline focus:border-primary/60 outline-none transition-colors";
  const labelClass = "block text-[12px] font-medium text-on-surface-variant mb-1.5";

  if (loading) return (
    <div className="p-4 md:p-6 max-w-3xl space-y-6">
      <PageHeader title="Configuration" description="Manage Kafka cluster connection, security and process-mining LLM settings." />
      <div className="skeleton-shimmer h-16 w-full rounded-xl" />
      <CardSkeleton lines={4} />
      <CardSkeleton lines={5} />
    </div>
  );

  return (
    <div className="p-4 md:p-6 max-w-3xl space-y-6">
      <PageHeader
        title="Configuration"
        description="Manage Kafka cluster connection, security and process-mining LLM settings."
      />

      {/* Connection Status Banner */}
      <div className={`rounded-xl border p-4 flex items-center gap-3 ${
        config.isConnected
          ? 'bg-success/10 border-success/25'
          : 'bg-surface-container border-outline-variant'
      }`}>
        <span className="relative flex h-3 w-3">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${config.isConnected ? 'bg-success' : 'bg-outline'}`} />
          <span className={`relative inline-flex rounded-full h-3 w-3 ${config.isConnected ? 'bg-success' : 'bg-outline'}`} />
        </span>
        <div>
          <p className={`text-sm font-bold ${config.isConnected ? 'text-success' : 'text-on-surface-variant'}`}>
            {config.isConnected ? 'Connected' : 'Not connected'}
          </p>
          <p className="text-xs text-on-surface-variant">{config.bootstrapServers}</p>
        </div>
        {testResult !== null && (
          <div className={`ml-auto flex items-center gap-1.5 text-xs font-bold ${testResult ? 'text-success' : 'text-error'}`}>
            <span className="material-symbols-outlined text-sm">{testResult ? 'check_circle' : 'cancel'}</span>
            {testResult ? 'Connection successful' : 'Connection failed'}
          </div>
        )}
      </div>

      {/* Cluster Connection */}
      <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
        <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
          <span className="material-symbols-outlined text-primary">lan</span>
          <h2 className="font-bold text-on-surface">Cluster Connection</h2>
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
            <p className="text-[10px] text-on-surface-variant mt-1">Comma-separated list of host:port pairs.</p>
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
                      ? 'border-primary bg-primary/10 text-on-surface'
                      : 'border-outline-variant bg-surface-container-low text-on-surface-variant hover:border-outline'
                  }`}
                >
                  <p className="text-xs font-bold">{mode.label}</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">{mode.description}</p>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* SSL Config */}
      {config.mode === 'SSL' && (
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">lock</span>
            <h2 className="font-bold text-on-surface">SSL / mTLS Settings</h2>
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
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">cloud</span>
            <h2 className="font-bold text-on-surface">Confluent Cloud Settings</h2>
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

      <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
        <div className="p-4 border-b border-outline-variant/60 flex items-center gap-3">
          <span className="material-symbols-outlined text-primary">neurology</span>
          <div>
            <h2 className="font-bold text-on-surface">Process Mining LLM</h2>
            <p className="text-xs text-on-surface-variant mt-0.5">
              Applied at runtime. Use environment variables or `application.yml` for persistent configuration.
            </p>
          </div>
        </div>
        <div className="p-5 space-y-5">
          <div>
            <label className={labelClass}>Provider</label>
            <div className="grid grid-cols-3 gap-3">
              {LLM_PROVIDERS.map(provider => (
                <button
                  key={provider.value}
                  onClick={() => applyLlmProvider(provider.value)}
                  className={`p-3 rounded-lg border text-left transition-all ${
                    config.llmProvider === provider.value
                      ? 'border-primary bg-primary/10 text-on-surface'
                      : 'border-outline-variant bg-surface-container-low text-on-surface-variant hover:border-outline'
                  }`}
                >
                  <p className="text-xs font-bold">{provider.label}</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">{provider.description}</p>
                </button>
              ))}
            </div>
          </div>

          <div className={`rounded-lg border px-4 py-3 text-xs ${
            config.llmLocalDeployment
              ? 'border-success/20 bg-success/5 text-success'
              : 'border-outline-variant/60 bg-surface-container-low text-on-surface-variant'
          }`}>
            {config.llmLocalDeployment
              ? 'Local inference detected. Lightweight open-source models can be used for snapshot and live process mining.'
              : 'Remote inference detected. You can switch to Ollama or another OpenAI-compatible endpoint for local lightweight models.'}
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className={labelClass}>Model</label>
              <input
                type="text"
                value={config.llmModel}
                onChange={e => set('llmModel', e.target.value)}
                placeholder={
                  config.llmProvider === 'OLLAMA' ? 'qwen3:4b'
                  : config.llmProvider === 'SPECTRA' ? 'Served by SpectraLLM (ignored)'
                  : 'model name'}
                disabled={config.llmProvider === 'SPECTRA'}
                className={inputClass}
              />
            </div>
            <div>
              <label className={labelClass}>Base URL</label>
              <input
                type="text"
                value={config.llmBaseUrl}
                onChange={e => set('llmBaseUrl', e.target.value)}
                placeholder={
                  config.llmProvider === 'OLLAMA' ? 'http://localhost:11434/v1'
                  : config.llmProvider === 'SPECTRA' ? 'http://localhost:8080'
                  : 'https://...'}
                className={inputClass}
              />
            </div>
            <div>
              <label className={labelClass}>API Key</label>
              <input
                type="password"
                value={config.llmApiKey ?? ''}
                onChange={e => set('llmApiKey', e.target.value)}
                placeholder={
                  config.llmProvider === 'OLLAMA' || config.llmProvider === 'SPECTRA'
                    ? 'Optional for local deployments' : 'Required'}
                className={inputClass}
              />
              <p className="text-[10px] text-on-surface-variant mt-1">
                {config.llmApiKeyConfigured ? 'A key is currently configured in memory.' : 'No key configured in memory.'}
              </p>
            </div>
            <div>
              <label className={labelClass}>Max Tokens</label>
              <input
                type="number"
                min={256}
                max={32768}
                value={config.llmMaxTokens}
                onChange={e => setNumber('llmMaxTokens', parseInt(e.target.value, 10) || 4096)}
                className={inputClass}
              />
            </div>
            <div>
              <label className={labelClass}>Live Window Size</label>
              <input
                type="number"
                min={10}
                max={5000}
                value={config.llmSnapshotWindowSize}
                onChange={e => setNumber('llmSnapshotWindowSize', parseInt(e.target.value, 10) || 100)}
                className={inputClass}
              />
            </div>
            <div>
              <label className={labelClass}>Live Window Timeout (s)</label>
              <input
                type="number"
                min={5}
                max={600}
                value={config.llmSnapshotWindowTimeoutSeconds}
                onChange={e => setNumber('llmSnapshotWindowTimeoutSeconds', parseInt(e.target.value, 10) || 30)}
                className={inputClass}
              />
            </div>
          </div>

          {config.llmProvider === 'SPECTRA' && (
            <label className="mt-4 flex items-start gap-3 rounded-lg border border-outline-variant/60 bg-background-dark/20 px-4 py-3 cursor-pointer">
              <input
                type="checkbox"
                checked={config.llmUseRag ?? false}
                onChange={e => setConfig(prev => ({ ...prev, llmUseRag: e.target.checked }))}
                className="mt-0.5"
              />
              <span>
                <span className="block text-xs font-bold text-on-surface">Enrich audit with SpectraLLM RAG</span>
                <span className="block text-[10px] text-on-surface-variant mt-0.5">
                  When enabled, the audit prompt is answered with hybrid retrieval over SpectraLLM's
                  ingested corpus. Leave off to ground the audit solely on the sampled Kafka messages.
                </span>
              </span>
            </label>
          )}

          {config.llmProvider === 'SPECTRA' && config.llmUseRag && (
            <div className="mt-3">
              <label className={labelClass}>SpectraLLM Collection</label>
              <input
                type="text"
                value={config.llmCollection ?? ''}
                onChange={e => set('llmCollection', e.target.value)}
                placeholder="Default collection"
                className={inputClass}
              />
              <p className="text-[10px] text-on-surface-variant mt-1">
                Optional — the ChromaDB collection to retrieve from. Leave blank for SpectraLLM's default.
              </p>
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-end gap-4">
            <div className="w-40">
              <label className={labelClass}>Request Timeout (s)</label>
              <input
                type="number"
                min={5}
                max={600}
                value={config.llmRequestTimeoutSeconds ?? 60}
                onChange={e => setNumber('llmRequestTimeoutSeconds', parseInt(e.target.value, 10) || 60)}
                className={inputClass}
              />
            </div>
            <Button variant="outline" icon={llmTesting ? undefined : 'network_check'} loading={llmTesting} onClick={handleTestLlm} disabled={llmTesting}>
              {llmTesting ? 'Testing LLM…' : 'Test LLM'}
            </Button>
          </div>

          {llmTestResult && (
            <div className={`mt-3 rounded-lg border px-4 py-3 text-[12px] flex items-start gap-2 ${
              llmTestResult.ok
                ? 'border-success/30 bg-success/10 text-success'
                : 'border-error/30 bg-error/10 text-error'
            }`}>
              <span className="material-symbols-outlined text-[16px] mt-0.5">
                {llmTestResult.ok ? 'check_circle' : 'error'}
              </span>
              <span className="break-words">{llmTestResult.message}</span>
            </div>
          )}
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="rounded-lg border border-error/25 bg-error/10 p-3 flex items-center gap-2 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[18px]">error</span>
          {error}
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center justify-between pt-2">
        <Button variant="outline" icon={testing ? undefined : 'wifi_tethering'} loading={testing} onClick={handleTestConnection} disabled={testing || saving}>
          {testing ? 'Testing…' : 'Test connection'}
        </Button>
        <Button variant="primary" icon={saving ? undefined : saveSuccess ? 'check_circle' : 'save'} loading={saving} onClick={handleSave} disabled={saving || testing}>
          {saving ? 'Saving…' : saveSuccess ? 'Saved!' : 'Save configuration'}
        </Button>
      </div>
    </div>
  );
};

export default Config;
