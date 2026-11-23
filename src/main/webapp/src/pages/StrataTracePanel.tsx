// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import {
  buildTraceQuery, describeCoverage, parseFlowResponse, parseSseBuffer,
  type ParsedFlow, type TraceProgress,
} from './streamFlowLogic';

type TraceStatus = 'idle' | 'running' | 'complete' | 'partial' | 'cancelled' | 'error';

/** The same bounded and cancellable trace as Stream Flow, projected onto the 3D scene. */
export default function StrataTracePanel({ topics, onTrace, onSelect }: {
  topics: string[];
  onTrace: (flow: ParsedFlow | null) => void;
  onSelect: (topic: string) => void;
}) {
  const [messageKey, setMessageKey] = useState('');
  const [status, setStatus] = useState<TraceStatus>('idle');
  const [flow, setFlow] = useState<ParsedFlow | null>(null);
  const [progress, setProgress] = useState<TraceProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => controllerRef.current?.abort();
  }, []);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    const key = messageKey.trim();
    if (!key || topics.length === 0 || controllerRef.current) return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setStatus('running');
    setFlow(null);
    setError(null);
    setProgress(null);
    onTrace(null);
    const body = {
      messageKey: key,
      maxMessagesPerTopic: 100,
      timeLimitMinutes: 30,
      exactKey: true,
      caseSensitive: true,
      useRegex: false,
      searchHeaders: false,
      searchPath: null,
      targetTopics: topics,
    };
    let gotFinal = false;
    const receivedFlow = (data: unknown, final: boolean) => {
      if (controller.signal.aborted) return;
      const parsed = parseFlowResponse(data);
      setFlow(parsed);
      onTrace(parsed);
      if (final) {
        gotFinal = true;
        setStatus(parsed.stats?.truncated || (parsed.stats?.topicsSkipped ?? 0) > 0 ||
          (parsed.stats?.topicsFailed ?? 0) > 0 ? 'partial' : 'complete');
      }
    };
    try {
      const response = await fetch('/api/stream-flow/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body), signal: controller.signal,
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(payload?.message || `Trace failed (HTTP ${response.status})`);
      }
      if (!response.body) {
        const fallback = await axios.post('/api/stream-flow', body, { timeout: 120_000, signal: controller.signal });
        receivedFlow(fallback.data, true);
      } else {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parsed = parseSseBuffer(buffer);
          buffer = parsed.rest;
          for (const event of parsed.events) {
            if (event.event === 'progress') setProgress(JSON.parse(event.data) as TraceProgress);
            if (event.event === 'flow') receivedFlow(JSON.parse(event.data), false);
            if (event.event === 'result') receivedFlow(JSON.parse(event.data), true);
            if (event.event === 'failed') {
              const reason = JSON.parse(event.data) as { message?: string };
              throw new Error(reason.message || 'Trace service failed');
            }
          }
        }
        if (!gotFinal && !controller.signal.aborted) {
          setStatus('partial');
          setError('Trace stream ended without a final result. Only received evidence is displayed.');
        }
      }
    } catch (reason) {
      if (controller.signal.aborted) {
        setStatus('cancelled');
      } else {
        setStatus('error');
        setError(reason instanceof Error ? reason.message : 'The trace could not be completed.');
      }
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  };

  const fullUrl = `/stream-flow${buildTraceQuery({
    messageKey: messageKey.trim(),
    searchPath: '', topics, windowMode: 'window', timeLimitMinutes: 30, maxMessages: 100,
    useRegex: false, exactKey: true, caseSensitive: true, searchHeaders: false,
  })}`;

  return (
    <section className="rounded-xl border border-outline-variant bg-surface p-4 space-y-3">
      <div>
        <h2 className="font-semibold text-on-surface">Trace a message key</h2>
        <p className="text-xs text-on-surface-variant mt-1">
          On-demand, read-only search of the Kafka record key in the {topics.length} visible topics.
          Up to 100 recent records per topic within 30 minutes. Coverage is reported by the server.
        </p>
      </div>
      <form onSubmit={search} className="flex flex-wrap items-center gap-2">
        <label htmlFor="strata-trace-key" className="text-xs text-on-surface-variant">Exact Kafka key</label>
        <input id="strata-trace-key" value={messageKey} onChange={event => setMessageKey(event.target.value)}
          maxLength={512} autoComplete="off" placeholder="order-7842"
          className="flex-1 min-w-36 rounded-lg border border-outline-variant bg-surface px-3 py-2 text-sm text-on-surface" />
        <button type="submit" disabled={status === 'running' || !messageKey.trim() || topics.length === 0}
          className="rounded-lg bg-primary px-4 py-2 text-sm text-on-primary disabled:opacity-45">
          {status === 'running' ? 'Tracing…' : 'Trace in 3D'}
        </button>
        {status === 'running' && <button type="button" onClick={() => controllerRef.current?.abort()}
          className="rounded-lg border border-outline-variant px-3 py-2 text-sm text-on-surface">Cancel</button>}
        <Link to={fullUrl} className="text-xs text-primary underline underline-offset-2">Full Stream Flow</Link>
      </form>
      {progress && status === 'running' && <p role="status" className="text-xs text-on-surface-variant">
        Scanned {progress.topicsCompleted}/{progress.topicsInScope} topics · {progress.messagesScanned.toLocaleString()} records · {progress.matches} matches
      </p>}
      {status === 'cancelled' && <p role="status" className="text-xs text-on-surface-variant">Cancelled. Any partial evidence remains visible.</p>}
      {error && <p role="alert" className="text-xs text-error">{error}</p>}
      {flow && <>
        <p className="text-xs text-on-surface-variant" role="status">
          {status === 'complete' ? 'Trace finished' : 'Partial trace'} · {describeCoverage(flow.stats) || 'Coverage unavailable'}
          {flow.hits.length === 0 ? ' · No matches in the scanned scope' : ` · ${flow.hits.length} topic matches`}
        </p>
        {flow.warnings.length > 0 && <p className="text-xs text-on-surface-variant">{flow.warnings.join(' ')}</p>}
        <p className="text-xs text-on-surface-variant">
          The 3D path connects chronological matches. It does not prove that a message was directly transferred between topics.
        </p>
        {flow.hits.length > 0 && <ol className="grid sm:grid-cols-2 lg:grid-cols-3 gap-2 max-h-64 overflow-auto">
          {flow.hits.map(hit => <li key={`${hit.topic}:${hit.firstPartition}:${hit.firstOffset}`}>
            <button className="w-full text-left rounded-lg border border-outline-variant p-2.5 text-xs text-on-surface hover:bg-surface-container-high"
              type="button" onClick={() => onSelect(hit.topic)}>
              <span className="font-semibold break-all">{hit.topic}</span>
              <span className="block text-on-surface-variant mt-1">
                P{hit.firstPartition} · offset {hit.firstOffset.toLocaleString()} · {hit.occurrences}{hit.occurrencesCapped ? '+' : ''} matches
              </span>
              <span className="block text-on-surface-variant">
                {hit.firstTimestamp > 0 ? new Date(hit.firstTimestamp).toLocaleString() : 'Record timestamp unavailable'}
                {hit.latencyFromPreviousMs !== null ? ` · Δ ${hit.latencyFromPreviousMs} ms` : ''}
              </span>
            </button>
          </li>)}
        </ol>}
      </>}
    </section>
  );
}
