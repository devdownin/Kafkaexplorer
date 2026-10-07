// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button, Field, Input, TopicInput } from '../ui';

/**
 * A list of resource names as removable chips plus one field to add the next.
 *
 * The forecast assistant asked for "all source topics (comma separated)" in a free-text field
 * prefilled from the catalogue: a typo or a stray comma there became a declared source nobody
 * reads, and removing one name meant editing a string. Topics are suggested from the cluster
 * catalogue `Layout` already loads; a name outside it stays accepted, as everywhere else.
 */
export function NameListField({ id, label, names, onChange, topics = false, error, description }: {
  id: string; label: string; names: string[]; onChange: (names: string[]) => void;
  topics?: boolean; error?: string; description?: ReactNode;
}) {
  const [draft, setDraft] = useState('');
  function add() {
    const name = draft.trim();
    if (name && !names.includes(name)) onChange([...names, name]);
    setDraft('');
  }
  return <div className="space-y-2">
    {names.length > 0 && <ul className="flex flex-wrap gap-2" aria-label={`${label}: selected`}>
      {names.map(name => <li key={name} className="flex items-center gap-1 rounded-full border border-outline-variant px-2 py-0.5 text-sm">
        <span>{name}</span>
        <Button variant="ghost" size="sm" aria-label={`Remove ${name}`} onClick={() => onChange(names.filter(n => n !== name))}>×</Button>
      </li>)}
    </ul>}
    <div className="flex gap-2 items-end">
      <Field id={id} label={label} error={error} description={description} className="flex-1">
        {p => topics
          ? <TopicInput {...p} value={draft} onChange={setDraft} onEnter={add} />
          : <Input {...p} value={draft} onChange={e => setDraft(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />}
      </Field>
      <Button onClick={add} disabled={!draft.trim()} aria-label={`Add to ${label}`}>Add</Button>
    </div>
  </div>;
}
