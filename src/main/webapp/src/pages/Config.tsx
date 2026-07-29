import React, { useState, useEffect, useMemo, useRef } from 'react';
import axios from 'axios';
import {
  PageHeader, Button, CardSkeleton, Field, Input, NumberInput, PasswordInput, useConfirm,
} from '../components/ui';

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

/** Champs pouvant porter une erreur de validation. */
type ValidatedField =
  | 'bootstrapServers' | 'truststorePath' | 'keystorePath'
  | 'confluentKey' | 'confluentSecret'
  | 'llmModel' | 'llmBaseUrl' | 'llmApiKey'
  | 'llmMaxTokens' | 'llmSnapshotWindowSize' | 'llmSnapshotWindowTimeoutSeconds';

type FieldErrors = Partial<Record<ValidatedField, string>>;

/** Ids DOM stables : la validation focalise le premier champ fautif. */
const fieldIds: Record<ValidatedField, string> = {
  bootstrapServers: 'cfg-bootstrap-servers',
  truststorePath: 'cfg-truststore-path',
  keystorePath: 'cfg-keystore-path',
  confluentKey: 'cfg-confluent-key',
  confluentSecret: 'cfg-confluent-secret',
  llmModel: 'cfg-llm-model',
  llmBaseUrl: 'cfg-llm-base-url',
  llmApiKey: 'cfg-llm-api-key',
  llmMaxTokens: 'cfg-llm-max-tokens',
  llmSnapshotWindowSize: 'cfg-llm-window-size',
  llmSnapshotWindowTimeoutSeconds: 'cfg-llm-window-timeout',
};

/** Ordre d'apparition à l'écran — détermine quel champ reçoit le focus en cas d'erreurs. */
const FIELD_ORDER: ValidatedField[] = [
  'bootstrapServers', 'truststorePath', 'keystorePath', 'confluentKey', 'confluentSecret',
  'llmModel', 'llmBaseUrl', 'llmApiKey',
  'llmMaxTokens', 'llmSnapshotWindowSize', 'llmSnapshotWindowTimeoutSeconds',
];

const LLM_PROVIDERS = [
  { value: 'ANTHROPIC', label: 'Anthropic', description: 'Hosted Claude models' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI-compatible', description: 'vLLM, LM Studio or compatible gateways' },
  { value: 'OLLAMA', label: 'Ollama', description: 'Lightweight local open-source models' },
  { value: 'SPECTRA', label: 'SpectraLLM', description: 'Local SpectraLLM instance (RAG + fine-tuned models)' },
] as const;

const Config: React.FC = () => {
  const confirm = useConfirm();
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
  const [errors, setErrors] = useState<FieldErrors>({});
  const [llmTesting, setLlmTesting] = useState(false);
  const [llmTestResult, setLlmTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  /** Dernier état persisté, pour savoir si le formulaire a été modifié. */
  const savedRef = useRef<string>('');
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const res = await axios.get<ClusterConfig>('/api/config');
        setConfig(prev => {
          const next = { ...prev, ...res.data };
          savedRef.current = JSON.stringify(next);
          return next;
        });
      } catch {
        // Backend may not expose REST config yet - use defaults
      } finally {
        setLoading(false);
      }
    };
    fetchConfig();
  }, []);

  useEffect(() => {
    setDirty(savedRef.current !== '' && JSON.stringify(config) !== savedRef.current);
  }, [config]);

  // Un rechargement ou une fermeture d'onglet avec des réglages non enregistrés perdait tout
  // sans un mot. La navigation interne (react-router) n'est pas couverte par cet événement.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  /**
   * Le serveur refuse de repointer le cluster tant qu'un audit, un job Flink ou une session
   * Process Mining tourne encore dessus (409) : il dit lequel, et on propose de forcer plutôt que
   * d'afficher « Failed to save configuration » sur un refus parfaitement délibéré.
   */
  const applyConfig = async (force: boolean) => {
    const res = await axios.post<ClusterConfig>('/api/config', force ? { ...config, force } : config);
    setConfig(prev => {
      const next = { ...prev, ...res.data };
      savedRef.current = JSON.stringify(next);
      return next;
    });
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 3000);
  };

  const handleSave = async () => {
    if (!checkBeforeSubmit()) return;
    setSaving(true);
    setError(null);
    setSaveSuccess(false);
    try {
      await applyConfig(false);
    } catch (err) {
      const conflict = axios.isAxiosError(err) && err.response?.status === 409
        ? (err.response.data as { message?: string } | undefined)?.message
        : null;
      if (!conflict) {
        setError('Failed to save configuration.');
      } else if (await confirm({
        title: 'Work is still running on this cluster',
        description: conflict,
        confirmLabel: 'Repoint anyway',
        tone: 'danger',
        icon: 'warning',
      })) {
        try {
          await applyConfig(true);
        } catch {
          setError('Failed to save configuration.');
        }
      }
    } finally {
      setSaving(false);
    }
  };

  const handleTestConnection = async () => {
    if (!checkBeforeSubmit()) return;
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
    if (!checkBeforeSubmit()) return;
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

  /**
   * Toutes les erreurs d'un coup, indexées par champ.
   *
   * La version précédente renvoyait la **première** erreur sous forme de chaîne, affichée dans un
   * bandeau en bas d'une page qui défile : on en corrigeait une, on cliquait Save, on obtenait la
   * suivante, et le champ fautif n'était ni signalé ni focalisé.
   */
  const validateConfig = (): FieldErrors => {
    const errors: FieldErrors = {};
    const servers = config.bootstrapServers?.trim() ?? '';
    if (!servers) {
      errors.bootstrapServers = 'Bootstrap servers are required.';
    } else {
      const invalid = servers.split(',').map(s => s.trim()).filter(Boolean)
        .find(part => !/^[^\s:]+:\d{1,5}$/.test(part));
      if (invalid) {
        errors.bootstrapServers = `Invalid entry "${invalid}". Expected host:port (e.g. localhost:9092).`;
      }
    }
    if (config.mode === 'CONFLUENT_CLOUD') {
      if (!config.confluentKey?.trim()) errors.confluentKey = 'API Key is required for Confluent Cloud.';
      if (!config.confluentSecret?.trim()) errors.confluentSecret = 'API Secret is required for Confluent Cloud.';
    }
    if (config.mode === 'SSL') {
      if (!config.truststorePath?.trim()) errors.truststorePath = 'Truststore path is required for SSL.';
      if (!config.keystorePath?.trim()) errors.keystorePath = 'Keystore path is required for SSL.';
    }
    // SpectraLLM picks its own served model, so the model field is optional for it.
    if (config.llmProvider !== 'SPECTRA' && !config.llmModel?.trim()) {
      errors.llmModel = 'A model is required for process mining.';
    }
    if (config.llmProvider !== 'OLLAMA' && !config.llmBaseUrl?.trim()) {
      errors.llmBaseUrl = 'A base URL is required for hosted, OpenAI-compatible or SpectraLLM providers.';
    }
    if (config.llmProvider === 'ANTHROPIC'
      && !config.llmApiKeyConfigured
      && !config.llmApiKey?.trim()) {
      errors.llmApiKey = 'An API key is required when the provider is Anthropic.';
    }
    if (!Number.isFinite(config.llmMaxTokens) || config.llmMaxTokens < 256) {
      errors.llmMaxTokens = 'Must be at least 256.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowSize) || config.llmSnapshotWindowSize < 10) {
      errors.llmSnapshotWindowSize = 'Must be at least 10 messages.';
    }
    if (!Number.isFinite(config.llmSnapshotWindowTimeoutSeconds) || config.llmSnapshotWindowTimeoutSeconds < 5) {
      errors.llmSnapshotWindowTimeoutSeconds = 'Must be at least 5 seconds.';
    }
    return errors;
  };

  /**
   * Valide, publie les erreurs et focalise le premier champ fautif.
   * @returns true quand le formulaire peut partir
   */
  const checkBeforeSubmit = (): boolean => {
    const found = validateConfig();
    setErrors(found);
    const firstInvalid = FIELD_ORDER.find(key => found[key]);
    if (!firstInvalid) return true;
    // Le champ peut être dans une section masquée par le mode courant — d'où le garde-fou.
    document.getElementById(fieldIds[firstInvalid])?.focus();
    return false;
  };

  const set = (key: keyof ClusterConfig, value: string) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
  };
  const setNumber = (key: keyof ClusterConfig, value: number) => {
    setConfig(prev => ({ ...prev, [key]: value }));
    clearError(key);
  };

  /** Une erreur disparaît dès qu'on retouche le champ — la revalidation a lieu au submit. */
  const clearError = (key: keyof ClusterConfig) => {
    setErrors(prev => (prev[key as ValidatedField] ? { ...prev, [key]: undefined } : prev));
  };

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

  const errorCount = useMemo(() => Object.values(errors).filter(Boolean).length, [errors]);

  if (loading) return (
    <div className="p-4 md:p-6 max-w-3xl space-y-6">
      <PageHeader title="Configuration" description="Manage Kafka cluster connection, security and process-mining LLM settings." />
      <div className="skeleton-shimmer h-16 w-full rounded-xl" />
      <CardSkeleton lines={4} />
      <CardSkeleton lines={5} />
    </div>
  );

  return (
    <form
      className="p-4 md:p-6 max-w-3xl space-y-6"
      noValidate
      // Un vrai <form> : Entrée depuis n'importe quel champ enregistre, au lieu de ne rien faire.
      onSubmit={e => { e.preventDefault(); void handleSave(); }}
    >
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
          <Field
            label="Bootstrap Servers"
            required
            id={fieldIds.bootstrapServers}
            error={errors.bootstrapServers}
            description="Comma-separated list of host:port pairs."
          >
            {p => (
              <Input
                {...p}
                className="font-mono"
                value={config.bootstrapServers}
                onChange={e => set('bootstrapServers', e.target.value)}
                placeholder="localhost:9092"
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>

          {/* Security Mode */}
          <fieldset>
            <legend className="block text-[12px] font-medium text-on-surface-variant mb-1.5">Security Mode</legend>
            <div className="grid grid-cols-3 gap-3">
              {MODES.map(mode => (
                <button
                  key={mode.value}
                  type="button"
                  aria-pressed={config.mode === mode.value}
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
          </fieldset>
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
            <Field label="Truststore Path" required id={fieldIds.truststorePath} error={errors.truststorePath}>
              {p => (
                <Input {...p} className="font-mono" value={config.truststorePath ?? ''}
                  onChange={e => set('truststorePath', e.target.value)}
                  placeholder="/path/to/truststore.jks" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="Truststore Password">
              {p => (
                <PasswordInput {...p} value={config.truststorePassword ?? ''}
                  onChange={e => set('truststorePassword', e.target.value)} />
              )}
            </Field>
            <Field label="Keystore Path" required id={fieldIds.keystorePath} error={errors.keystorePath}>
              {p => (
                <Input {...p} className="font-mono" value={config.keystorePath ?? ''}
                  onChange={e => set('keystorePath', e.target.value)}
                  placeholder="/path/to/keystore.jks" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="Keystore Password">
              {p => (
                <PasswordInput {...p} value={config.keystorePassword ?? ''}
                  onChange={e => set('keystorePassword', e.target.value)} />
              )}
            </Field>
            <Field label="Key Password">
              {p => (
                <PasswordInput {...p} value={config.keyPassword ?? ''}
                  onChange={e => set('keyPassword', e.target.value)} />
              )}
            </Field>
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
            <Field label="API Key" required id={fieldIds.confluentKey} error={errors.confluentKey}>
              {p => (
                <Input {...p} className="font-mono" value={config.confluentKey ?? ''}
                  onChange={e => set('confluentKey', e.target.value)}
                  placeholder="YOUR_API_KEY" autoComplete="off" spellCheck={false} />
              )}
            </Field>
            <Field label="API Secret" required id={fieldIds.confluentSecret} error={errors.confluentSecret}>
              {p => (
                <PasswordInput {...p} value={config.confluentSecret ?? ''}
                  onChange={e => set('confluentSecret', e.target.value)}
                  placeholder="YOUR_API_SECRET" />
              )}
            </Field>
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
          <fieldset>
            <legend className="block text-[12px] font-medium text-on-surface-variant mb-1.5">Provider</legend>
            <div className="grid grid-cols-3 gap-3">
              {LLM_PROVIDERS.map(provider => (
                <button
                  key={provider.value}
                  type="button"
                  aria-pressed={config.llmProvider === provider.value}
                  onClick={() => { applyLlmProvider(provider.value); setErrors({}); }}
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
          </fieldset>

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
            <Field
              label="Model"
              required={config.llmProvider !== 'SPECTRA'}
              id={fieldIds.llmModel}
              error={errors.llmModel}
              description={config.llmProvider === 'SPECTRA' ? 'Served by SpectraLLM — not sent per request.' : undefined}
            >
              {p => (
                <Input
                  {...p}
                  className="font-mono"
                  value={config.llmModel}
                  onChange={e => set('llmModel', e.target.value)}
                  placeholder={
                    config.llmProvider === 'OLLAMA' ? 'qwen3:4b'
                    : config.llmProvider === 'SPECTRA' ? 'Served by SpectraLLM (ignored)'
                    : 'model name'}
                  disabled={config.llmProvider === 'SPECTRA'}
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </Field>
            <Field
              label="Base URL"
              required={config.llmProvider !== 'OLLAMA'}
              id={fieldIds.llmBaseUrl}
              error={errors.llmBaseUrl}
            >
              {p => (
                <Input
                  {...p}
                  className="font-mono"
                  value={config.llmBaseUrl}
                  onChange={e => set('llmBaseUrl', e.target.value)}
                  placeholder={
                    config.llmProvider === 'OLLAMA' ? 'http://localhost:11434/v1'
                    : config.llmProvider === 'SPECTRA' ? 'http://localhost:8080'
                    : 'https://...'}
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </Field>
            <Field
              label="API Key"
              required={config.llmProvider === 'ANTHROPIC' && !config.llmApiKeyConfigured}
              id={fieldIds.llmApiKey}
              error={errors.llmApiKey}
              description={config.llmApiKeyConfigured
                ? 'A key is currently configured in memory — leave blank to keep it.'
                : 'No key configured in memory.'}
            >
              {p => (
                <PasswordInput
                  {...p}
                  value={config.llmApiKey ?? ''}
                  onChange={e => set('llmApiKey', e.target.value)}
                  placeholder={
                    config.llmProvider === 'OLLAMA' || config.llmProvider === 'SPECTRA'
                      ? 'Optional for local deployments' : 'sk-…'}
                />
              )}
            </Field>
            <Field label="Max Tokens" id={fieldIds.llmMaxTokens} error={errors.llmMaxTokens} description="256 – 32768">
              {p => (
                <NumberInput {...p} min={256} max={32768} fallback={4096}
                  value={config.llmMaxTokens}
                  onChange={v => setNumber('llmMaxTokens', v)} />
              )}
            </Field>
            <Field label="Live Window Size" id={fieldIds.llmSnapshotWindowSize} error={errors.llmSnapshotWindowSize} description="Messages per analysis window (10 – 5000)">
              {p => (
                <NumberInput {...p} min={10} max={5000} fallback={100}
                  value={config.llmSnapshotWindowSize}
                  onChange={v => setNumber('llmSnapshotWindowSize', v)} />
              )}
            </Field>
            <Field label="Live Window Timeout (s)" id={fieldIds.llmSnapshotWindowTimeoutSeconds} error={errors.llmSnapshotWindowTimeoutSeconds} description="Flush the window after this delay (5 – 600)">
              {p => (
                <NumberInput {...p} min={5} max={600} fallback={30}
                  value={config.llmSnapshotWindowTimeoutSeconds}
                  onChange={v => setNumber('llmSnapshotWindowTimeoutSeconds', v)} />
              )}
            </Field>
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
            <Field
              label="SpectraLLM Collection"
              className="mt-3"
              description="Optional — the ChromaDB collection to retrieve from. Leave blank for SpectraLLM's default."
            >
              {p => (
                <Input {...p} className="font-mono" value={config.llmCollection ?? ''}
                  onChange={e => set('llmCollection', e.target.value)}
                  placeholder="Default collection" autoComplete="off" spellCheck={false} />
              )}
            </Field>
          )}

          <div className="mt-4 flex flex-wrap items-end gap-4">
            <Field label="Request Timeout (s)" className="w-40">
              {p => (
                <NumberInput {...p} min={5} max={600} fallback={60}
                  value={config.llmRequestTimeoutSeconds ?? 60}
                  onChange={v => setNumber('llmRequestTimeoutSeconds', v)} />
              )}
            </Field>
            <Button type="button" variant="outline" icon={llmTesting ? undefined : 'network_check'} loading={llmTesting} onClick={handleTestLlm} disabled={llmTesting}>
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

      {/* Récapitulatif : les détails sont sur les champs, ceci ne fait que compter et orienter. */}
      {errorCount > 0 && (
        <div className="rounded-lg border border-error/25 bg-error/10 p-3 flex items-center gap-2 text-error text-[13px]" role="alert">
          <span className="material-symbols-outlined text-[18px]">error</span>
          {errorCount === 1 ? '1 field needs attention.' : `${errorCount} fields need attention.`}
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center justify-between pt-2">
        <Button type="button" variant="outline" icon={testing ? undefined : 'wifi_tethering'} loading={testing} onClick={handleTestConnection} disabled={testing || saving}>
          {testing ? 'Testing…' : 'Test connection'}
        </Button>
        <div className="flex items-center gap-3">
          {dirty && !saving && (
            <span className="text-[12px] text-on-surface-variant">Unsaved changes</span>
          )}
          <Button
            type="submit"
            variant="primary"
            icon={saving ? undefined : saveSuccess ? 'check_circle' : 'save'}
            loading={saving}
            disabled={saving || testing}
          >
            {saving ? 'Saving…' : saveSuccess ? 'Saved!' : 'Save configuration'}
          </Button>
        </div>
      </div>
    </form>
  );
};

export default Config;
