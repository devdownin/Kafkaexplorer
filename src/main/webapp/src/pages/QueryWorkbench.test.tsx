// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import type { ReactNode } from 'react';

/*
 * The wiring, not the pure logic.
 *
 * `queryWorkbench.test.ts` covers what can be decided without React: statement splitting, sorting,
 * formatting, storage. What it cannot cover is the part that actually broke — whether Run sends
 * what `splitStatements` designated, whether clicking a topic still destroys the tab you are
 * writing in, whether the detail panel closes when a sort invalidates the index it holds. Those
 * live in JSX, and no page in this repository had a component test before this one.
 *
 * Monaco is mocked away entirely: it is 4 MB of editor that jsdom cannot lay out, and none of the
 * behaviour under test needs a real one. The stub exposes just enough of the API the page calls —
 * a selection, a model with `getOffsetAt`, `executeEdits` — so the wiring runs for real.
 */

// ── Monaco stub ──────────────────────────────────────────────────────────────
/** Cursor offset the stubbed editor reports; a test moves it to target a statement. */
let cursorOffset = 0;
/** Selected range, or null. Offsets into the document. */
let selectionRange: { start: number; end: number } | null = null;
let currentValue = '';

const setCursor = (offset: number) => {
  cursorOffset = offset;
  selectionRange = null;
  cursorListeners.forEach(fn => fn({ selection: makeSelection() }));
};

let cursorListeners: ((e: { selection: ReturnType<typeof makeSelection> }) => void)[] = [];

/** Éditions soumises à Monaco depuis le montage — voir `executeEdits` dans le stub. */
let submittedEdits: { range: { _full?: boolean }; text: string }[] = [];

/** Poser une sélection, comme un glissement de souris dans l'éditeur. */
const setSelection = (start: number, end: number) => {
  selectionRange = { start, end };
  cursorListeners.forEach(fn => fn({ selection: makeSelection() }));
};

function offsetToPosition(text: string, offset: number) {
  const clamped = Math.max(0, Math.min(offset, text.length));
  const before = text.slice(0, clamped);
  const line = before.split('\n').length;
  return { lineNumber: line, column: clamped - (before.lastIndexOf('\n') + 1) + 1 };
}

function makeSelection() {
  const start = selectionRange ? selectionRange.start : cursorOffset;
  const pos = offsetToPosition(currentValue, start);
  return {
    isEmpty: () => selectionRange === null,
    getPosition: () => ({ ...pos, _offset: start }),
    startLineNumber: pos.lineNumber,
    startColumn: pos.column,
  };
}

vi.mock('../monaco-setup', () => ({}));

vi.mock('@monaco-editor/react', () => {
  const Editor = ({ value, onChange, onMount }: {
    value: string;
    onChange: (v: string | undefined) => void;
    onMount: (editor: unknown, monaco: unknown) => void;
  }) => {
    currentValue = value;
    const editor = {
      getSelection: () => makeSelection(),
      getModel: () => ({
        getValueInRange: () => (selectionRange
          ? currentValue.slice(selectionRange.start, selectionRange.end)
          : ''),
        getOffsetAt: (p: { _offset?: number }) => p._offset ?? cursorOffset,
        getPositionAt: (offset: number) => offsetToPosition(currentValue, offset),
        // Marquée, pour que `executeEdits` reconnaisse un remplacement de tout le texte.
        getFullModelRange: () => ({ _full: true }),
        getValue: () => currentValue,
      }),
      onDidChangeCursorSelection: (fn: (e: { selection: ReturnType<typeof makeSelection> }) => void) => {
        cursorListeners.push(fn);
        return { dispose: () => {} };
      },
      addCommand: () => {},
      getAction: () => ({ run: () => {} }),
      // Le vrai Monaco applique l'édition puis notifie `onChange` ; l'assistant de fenêtrage passe
      // par là pour remplacer tout le texte, donc le stub doit en faire autant ou la page ne
      // verrait jamais le SQL posé. Les éditions sont retenues : c'est par elles, et non par la
      // valeur affichée, que se vérifie le passage par la pile d'annulation.
      executeEdits: (_source: string, edits: { range: { _full?: boolean }; text: string }[]) => {
        submittedEdits.push(...edits);
        const edit = edits[0];
        if (edit?.range?._full) onChange(edit.text);
      },
      focus: () => {},
      setPosition: () => {},
      revealPositionInCenter: () => {},
      revealPositionInCenterIfOutsideViewport: () => {},
    };
    // `onMount` runs once, like the real component's.
    queueMicrotask(() => onMount(editor, monacoStub));
    return (
      <textarea
        aria-label="SQL editor"
        value={value}
        onChange={e => onChange(e.target.value)}
      />
    );
  };
  const monacoStub = {
    KeyMod: { CtrlCmd: 1, Shift: 2 },
    KeyCode: { Enter: 3, KeyF: 4 },
    MarkerSeverity: { Error: 8 },
    Range: class {},
    editor: { setModelMarkers: () => {} },
    languages: {
      registerCompletionItemProvider: () => ({ dispose: () => {} }),
      registerHoverProvider: () => ({ dispose: () => {} }),
      registerDocumentFormattingEditProvider: () => ({ dispose: () => {} }),
      CompletionItemKind: { Class: 1, Field: 2, Keyword: 3 },
    },
  };
  return { default: Editor, useMonaco: () => monacoStub };
});

// ── axios stub ───────────────────────────────────────────────────────────────
const get = vi.fn();
const post = vi.fn();
const del = vi.fn();
vi.mock('axios', () => ({
  default: {
    get: (...a: unknown[]) => get(...a),
    post: (...a: unknown[]) => post(...a),
    delete: (...a: unknown[]) => del(...a),
    isCancel: () => false,
  },
}));

import QueryWorkbench from './QueryWorkbench';
import { ToastProvider } from '../components/Toast';
import { ConfirmProvider } from '../components/ui';

const CATALOGUE = {
  topics: ['demo.orders.1.received', 'internal.audit.history'],
  tables: [],
  health: true,
};

function renderPage(ui: ReactNode = <QueryWorkbench />) {
  const router = createMemoryRouter(
    [{ path: '/query', element: <ToastProvider><ConfirmProvider>{ui}</ConfirmProvider></ToastProvider> }],
    { initialEntries: ['/query'] },
  );
  return render(<RouterProvider router={router} />);
}

/** The editor textarea, once the page has settled. */
const editor = () => screen.getByLabelText('SQL editor') as HTMLTextAreaElement;

beforeEach(() => {
  localStorage.clear();
  cursorListeners = [];
  cursorOffset = 0;
  selectionRange = null;
  currentValue = '';
  submittedEdits = [];
  get.mockReset();
  post.mockReset();
  del.mockReset();
  del.mockResolvedValue({ data: { dropped: true, message: 'Table orders was dropped.' } });
  get.mockImplementation((url: string) => {
    if (url === '/api/query/init') return Promise.resolve({ data: CATALOGUE });
    return Promise.resolve({ data: {} });
  });
  post.mockResolvedValue({ data: { valid: true } });
});

afterEach(() => vi.restoreAllMocks());

describe('QueryWorkbench — the catalogue', () => {
  it('loads it on arrival, without waiting for the refresh button', async () => {
    renderPage();
    expect(await screen.findByText('demo.orders.1.received')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/query/init');
  });

  it('states why a list is empty instead of showing a bare zero', async () => {
    get.mockImplementation((url: string) => url === '/api/query/init'
      ? Promise.resolve({ data: { topics: [], tables: [], health: false, kafkaError: 'Connection to node -1 refused' } })
      : Promise.resolve({ data: {} }));
    renderPage();
    expect(await screen.findByText(/Connection to node -1 refused/)).toBeInTheDocument();
    expect(screen.getByText('Engine offline')).toBeInTheDocument();
  });

  it('offers starter queries built from the catalogue, skipping internal topics', async () => {
    renderPage();
    const starter = await screen.findByText('SELECT * FROM demo_orders_1_received LIMIT 50');
    expect(starter).toBeInTheDocument();
    expect(screen.queryByText(/internal_audit_history/)).not.toBeInTheDocument();
  });
});

/*
 * Les `CREATE TABLE` écrits ici sont rejoués au démarrage, donc redémarrer n'est plus le moyen de
 * vider le catalogue Flink. Ce geste rend cette issue — sans lui, le magasin ne saurait que
 * grossir, ce qui serait un défaut pire que celui qu'il corrige.
 */
describe('QueryWorkbench — dropping a table', () => {
  const withTable = (url: string) => (url === '/api/query/init'
    ? Promise.resolve({ data: { ...CATALOGUE, tables: ['orders'] } })
    : Promise.resolve({ data: {} }));

  it('asks first, since the definition exists nowhere else afterwards', async () => {
    get.mockImplementation(withTable);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Drop table orders' }));

    expect(await screen.findByText('Drop orders?')).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();
  });

  it('does nothing when the confirmation is declined', async () => {
    get.mockImplementation(withTable);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Drop table orders' }));
    await screen.findByText('Drop orders?');

    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));

    await waitFor(() => expect(screen.queryByText('Drop orders?')).not.toBeInTheDocument());
    expect(del).not.toHaveBeenCalled();
  });

  it('drops it and reloads the catalogue', async () => {
    get.mockImplementation(withTable);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Drop table orders' }));
    await screen.findByText('Drop orders?');

    await userEvent.click(screen.getByRole('button', { name: 'Drop table' }));

    await waitFor(() => expect(del).toHaveBeenCalledWith('/api/query/table/orders'));
    // Le catalogue est relu : la liste de gauche doit décrire ce que Flink a maintenant.
    await waitFor(() => expect(get.mock.calls.filter(c => c[0] === '/api/query/init').length)
      .toBeGreaterThan(1));
  });

  /*
   * « Il n'y avait rien de ce nom » est une réponse possible à une requête bien formée : l'annoncer
   * comme une suppression promettrait plus qu'il ne s'est produit — le piège pour lequel le bouton
   * Stop de cet éditeur avait déjà dû être corrigé.
   */
  it('says what the server actually did rather than assuming a 200 means dropped', async () => {
    get.mockImplementation(withTable);
    del.mockResolvedValue({ data: { dropped: false, message: 'No table named orders was registered or stored.' } });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Drop table orders' }));
    await screen.findByText('Drop orders?');

    await userEvent.click(screen.getByRole('button', { name: 'Drop table' }));

    expect(await screen.findByText('No table named orders was registered or stored.')).toBeInTheDocument();
  });
});

describe('QueryWorkbench — the sidebar lets a truncated name be read', () => {
  /*
   * La barre latérale est étroite et les noms sont `truncate` : sans `title`, un nom de topic
   * long est du texte que rien ne permet de lire. La liste des tables portait déjà la règle,
   * celle des topics avait été oubliée.
   */
  it('carries the whole topic name on the row you hover', async () => {
    renderPage();
    const row = await screen.findByRole('button', { name: 'SELECT from demo.orders.1.received' });
    expect(row).toHaveAttribute('title', 'demo.orders.1.received');
    // Le nom accessible reste celui de l'action : le `title` ne le double pas.
    expect(row).toHaveAccessibleName('SELECT from demo.orders.1.received');
    // Et il porte bien le nom entier, pas la version tronquée qui est à l'écran.
    expect(row).toHaveTextContent('demo.orders.1.received');
  });

  /*
   * Le `title` seul ne suffisait pas : il n'apparaît qu'à la souris, après le délai du
   * navigateur, et jamais au focus clavier — alors que la liste est tabulable. Le nom se déplie
   * donc sur place, et ce qui se casse en silence est l'appariement : un modificateur
   * `group-hover/name` sans `group/name` sur un ancêtre ne fait rien du tout, et rien à l'écran
   * ne le dirait. Les deux moitiés sont vérifiées ensemble, ici et pour les tables.
   */
  it('unfolds the name in place, on hover and on keyboard focus', async () => {
    renderPage();
    const row = await screen.findByRole('button', { name: 'SELECT from demo.orders.1.received' });
    const name = row.querySelector('span')!;

    expect(name).toHaveClass('truncate');
    expect(name.className).toContain('group-hover/name:whitespace-normal');
    expect(name.className).toContain('group-focus-within/name:whitespace-normal');
    expect(name.closest('.group\\/name')).not.toBeNull();
  });

  it('does the same for a Flink table and for the columns it unfolds', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/api/query/init') return Promise.resolve({ data: { ...CATALOGUE, tables: ['orders'] } });
      if (url === '/api/query/schema/orders') {
        return Promise.resolve({ data: { shipping_address_city: 'STRING' } });
      }
      return Promise.resolve({ data: {} });
    });
    renderPage();
    const tableName = await screen.findByTitle('orders');
    expect(tableName).toHaveTextContent('orders');
    expect(tableName.className).toContain('group-hover/name:whitespace-normal');
    expect(tableName.closest('.group\\/name')).not.toBeNull();

    await userEvent.click(tableName);

    expect(await screen.findByTitle('shipping_address_city'))
      .toHaveTextContent('shipping_address_city');
  });
});

describe('QueryWorkbench — nothing silently replaces the tab you are writing in', () => {
  it('fills an empty tab when a topic is clicked', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'SELECT from demo.orders.1.received' }));
    await waitFor(() => expect(editor().value).toContain('demo_orders_1_received'));
    expect(screen.getAllByRole('tab')).toHaveLength(1);
  });

  it('opens a new tab instead, when the current one holds SQL', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1');

    await userEvent.click(screen.getByRole('button', { name: 'SELECT from demo.orders.1.received' }));

    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(2));
    // The work in progress survived, in the tab it was written in.
    await userEvent.click(screen.getAllByRole('tab')[0]);
    await waitFor(() => expect(editor().value).toBe('SELECT 1'));
  });
});

describe('QueryWorkbench — the editor reads, and says so', () => {
  /*
   * Le mode « Flink job » a été retiré : il ne fonctionnait pas. Ce qu'il faut garantir tient en
   * deux faits — il n'y a plus de sélecteur de mode à trouver, et un INSERT collé dans l'onglet
   * est refusé *avec sa raison* plutôt que posté à un endpoint qui ne mène nulle part.
   */
  it('offers no execution mode to choose from', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');

    expect(screen.queryByRole('button', { name: 'Flink job' })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Execution mode' })).not.toBeInTheDocument();
  });

  it('poses a bounded read on the sidebar click, whatever the table', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');

    await userEvent.click(screen.getByRole('button', { name: 'SELECT from demo.orders.1.received' }));

    await waitFor(() => expect(editor().value).toBe('SELECT * FROM demo_orders_1_received LIMIT 50'));
  });

  it('refuses an INSERT with its reason, and posts it nowhere', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('INSERT INTO x SELECT * FROM demo_orders_1_received');
    setCursor(0);

    await userEvent.click(screen.getByRole('button', { name: /Run query|Run statement/ }));

    expect(await screen.findByText('INSERT INTO is not run by the SQL editor')).toBeInTheDocument();
    expect(post.mock.calls.some(c => c[0] === '/api/query/jobs')).toBe(false);
    expect(post.mock.calls.some(c => c[0] === '/api/query/run-sync')).toBe(false);
  });
});

describe('QueryWorkbench — what Run sends', () => {
  const runSync = () => post.mock.calls.find(c => c[0] === '/api/query/run-sync');

  beforeEach(() => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: { columns: ['id'], rows: [{ id: 'A' }], error: null, engine: 'KAFKA_DIRECT' } }));
  });

  it('sends the whole tab when it holds one statement', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1 FROM t');

    await userEvent.click(screen.getByRole('button', { name: /Run query/ }));
    await waitFor(() => expect(runSync()).toBeTruthy());
    expect((runSync()![1] as { sql: string }).sql).toBe('SELECT 1 FROM t');
  });

  it('sends only the statement the cursor is in', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    const sql = 'SELECT 1 FROM a;\nSELECT 2 FROM b;';
    await userEvent.clear(editor());
    await userEvent.paste(sql);
    // Cursor inside the second statement.
    setCursor(sql.indexOf('SELECT 2') + 2);

    await userEvent.click(await screen.findByRole('button', { name: /Run statement/ }));
    await waitFor(() => expect(runSync()).toBeTruthy());
    expect((runSync()![1] as { sql: string }).sql).toBe('SELECT 2 FROM b');
  });

  it('says which statement it will run, before it is pressed', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('SELECT 1 FROM a;\nSELECT 2 FROM b;');
    setCursor(0);
    expect(await screen.findByText('Statement 1/2')).toBeInTheDocument();
  });

  it('carries the row cap that the result is then judged against', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1');
    await userEvent.click(screen.getByRole('button', { name: /Run query/ }));
    await waitFor(() => expect(runSync()).toBeTruthy());
    expect((runSync()![1] as { maxRows: number }).maxRows).toBe(50);
  });
});

describe('QueryWorkbench — running every statement', () => {
  beforeEach(() => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: { columns: ['id'], rows: [{ id: 'A' }], error: null, engine: 'KAFKA_DIRECT' } }));
  });

  const sentSql = () => post.mock.calls
    .filter(c => c[0] === '/api/query/run-sync')
    .map(c => (c[1] as { sql: string }).sql);

  it('runs them in order', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('SELECT 1 FROM a;\nSELECT 2 FROM b;');
    setCursor(0);

    await userEvent.click(await screen.findByRole('button', { name: /Run all/ }));
    await waitFor(() => expect(sentSql()).toEqual(['SELECT 1 FROM a', 'SELECT 2 FROM b']));
  });

  it('stops at the first failure, and says the rest never ran', async () => {
    post.mockImplementation((url: string, body: unknown) => {
      if (url === '/api/query/validate') return Promise.resolve({ data: { valid: true } });
      const { sql } = body as { sql: string };
      return Promise.resolve(sql.includes('FROM a')
        ? { data: { columns: [], rows: [], error: "Object 'a' not found" } }
        : { data: { columns: ['id'], rows: [], error: null } });
    });
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('SELECT 1 FROM a;\nSELECT 2 FROM b;');
    setCursor(0);

    await userEvent.click(await screen.findByRole('button', { name: /Run all/ }));
    await waitFor(() => expect(sentSql()).toEqual(['SELECT 1 FROM a']));
    expect(await screen.findByText(/Failed at statement 1 of 2 — 1 never run/)).toBeInTheDocument();
    // « Jamais exécutée » et « réussie sans lignes » sont deux réponses différentes.
    expect(screen.getByRole('button', { name: 'Statement 2, skipped' })).toBeInTheDocument();
  });

  it('is not offered for a single statement', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1');
    expect(screen.queryByRole('button', { name: /Run all/ })).not.toBeInTheDocument();
  });
});

describe('QueryWorkbench — a batch keeps what every statement gave', () => {
  /** Une réponse par instruction, pour distinguer les résultats dans la grille. */
  const perStatement = () => post.mockImplementation((url: string, body: unknown) => {
    if (url === '/api/query/validate') return Promise.resolve({ data: { valid: true } });
    const { sql } = body as { sql: string };
    const tag = sql.includes('FROM a') ? 'from-a' : 'from-b';
    return Promise.resolve({
      data: { columns: ['tag'], rows: [{ tag }], error: null, engine: 'KAFKA_DIRECT', warnings: [] },
    });
  });

  const runTwo = async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('SELECT 1 FROM a;\nSELECT 2 FROM b;');
    setCursor(0);
    await userEvent.click(await screen.findByRole('button', { name: /Run all/ }));
  };

  /*
   * Le lot n'affichait que le résultat de la **dernière** instruction : les autres ne laissaient
   * ni lignes, ni durée, ni moteur, ni la preuve qu'elles avaient tourné.
   */
  it('lists one entry per statement, with what it gave', async () => {
    perStatement();
    await runTwo();
    expect(await screen.findByRole('button', { name: 'Statement 1, ok' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Statement 2, ok' })).toBeInTheDocument();
    expect(screen.getByText(/2 statements · 2 ok/)).toBeInTheDocument();
  });

  it('brings an earlier statement’s rows back into the grid', async () => {
    perStatement();
    await runTwo();
    // La grille montre la dernière, comme avant.
    expect(await screen.findByText('from-b')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Statement 1, ok' }));

    expect(await screen.findByText('from-a')).toBeInTheDocument();
    expect(screen.queryByText('from-b')).not.toBeInTheDocument();
  });

  it('leaves no batch behind when a single statement is run', async () => {
    perStatement();
    await runTwo();
    await screen.findByRole('button', { name: 'Statement 1, ok' });

    setCursor(0);
    await userEvent.click(screen.getByRole('button', { name: /Run statement/ }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Statement 1, ok' })).not.toBeInTheDocument());
  });
});

describe('QueryWorkbench — Stop stops the batch', () => {
  /*
   * Stop n'abandonnait qu'une requête HTTP. Entre deux instructions il n'y en a aucune en vol, et
   * la boucle enchaînait ; l'arrêt était par ailleurs annoncé en rouge comme un échec.
   */
  it('marks the statements it never reached, and does not call the stop a failure', async () => {
    let release = () => {};
    post.mockImplementation((url: string) => {
      if (url === '/api/query/validate') return Promise.resolve({ data: { valid: true } });
      if (url === '/api/query/run-sync') {
        return new Promise(resolve => {
          release = () => resolve({
            data: { columns: ['id'], rows: [], error: null, engine: 'KAFKA_DIRECT', warnings: [] },
          });
        });
      }
      return Promise.resolve({ data: { cancelled: false, outcome: 'NO_ACTIVE_JOB' } });
    });

    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('SELECT 1 FROM a;\nSELECT 2 FROM b;');
    setCursor(0);
    await userEvent.click(await screen.findByRole('button', { name: /Run all/ }));
    await screen.findByRole('button', { name: 'Statement 1, running' });

    await userEvent.click(screen.getByRole('button', { name: /Stop/ }));
    release();

    expect(await screen.findByText(/Stopped after statement 1 of 2 — 1 never run/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Statement 2, skipped' })).toBeInTheDocument();
    // La seconde n'est jamais partie.
    expect(post.mock.calls.filter(c => c[0] === '/api/query/run-sync')).toHaveLength(1);
  });
});

describe('QueryWorkbench — a selection that holds several statements', () => {
  beforeEach(() => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: { columns: ['id'], rows: [{ id: 'A' }], error: null, engine: 'KAFKA_DIRECT' } }));
  });

  const sentSql = () => post.mock.calls
    .filter(c => c[0] === '/api/query/run-sync')
    .map(c => (c[1] as { sql: string }).sql);

  /*
   * Sélectionner deux requêtes et les lancer est un geste ordinaire ; la sélection partait
   * auparavant en une seule requête, que le planner rejetait sur le `;`.
   */
  it('runs them one after another, not as one query', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    const sql = 'SELECT 1 FROM a;\nSELECT 2 FROM b;';
    await userEvent.clear(editor());
    await userEvent.paste(sql);
    setSelection(0, sql.length);

    await userEvent.click(await screen.findByRole('button', { name: /Run selection/ }));

    await waitFor(() => expect(sentSql()).toEqual(['SELECT 1 FROM a', 'SELECT 2 FROM b']));
  });

  it('still sends a sub-expression verbatim', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    const sql = 'SELECT 1 FROM a WHERE x = 2';
    await userEvent.clear(editor());
    await userEvent.paste(sql);
    setSelection(0, 'SELECT 1 FROM a'.length);

    await userEvent.click(await screen.findByRole('button', { name: /Run selection/ }));

    await waitFor(() => expect(sentSql()).toEqual(['SELECT 1 FROM a']));
  });
});

describe('QueryWorkbench — a tab with nothing to run', () => {
  it('says so instead of sending an empty query to the engine', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.clear(editor());
    await userEvent.paste('-- just a note');

    await userEvent.click(screen.getByRole('button', { name: /Run query/ }));

    expect(await screen.findByText('Nothing to run')).toBeInTheDocument();
    expect(post.mock.calls.filter(c => c[0] === '/api/query/run-sync')).toHaveLength(0);
  });
});

describe('QueryWorkbench — a closed tab can be reopened', () => {
  /*
   * Fermer demandait confirmation quand il y avait quelque chose à perdre — la moitié bon marché
   * du problème. Le texte d'un onglet n'existe nulle part ailleurs, et il n'y avait aucun retour.
   */
  it('offers to reopen the tab that was just closed, with its SQL', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1 FROM a');
    // Un second onglet : le dernier onglet ne se ferme pas.
    await userEvent.click(screen.getByRole('button', { name: 'New tab' }));
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(2));

    // Le premier onglet : celui qui porte le SQL.
    await userEvent.click(screen.getAllByRole('button', { name: /^Close / })[0]);
    // Le SQL en cours vaut une confirmation avant de disparaître.
    await userEvent.click(await screen.findByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(1));

    await userEvent.click(await screen.findByRole('button', { name: /^Reopen / }));

    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(2));
    expect(editor().value).toBe('SELECT 1 FROM a');
  });

  it('offers nothing while no tab has been closed', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    expect(screen.queryByRole('button', { name: /^Reopen / })).not.toBeInTheDocument();
  });
});

describe('QueryWorkbench — the window assistant', () => {
  /** Le bouton de l'assistant, une fois la page posée. */
  const applyButton = () => screen.getByRole('button', { name: 'Replace editor content' });

  it('writes the window query straight into an empty tab, asking nothing', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');

    await userEvent.click(applyButton());

    await waitFor(() => expect(editor().value).toContain('TUMBLE'));
    // Rien à perdre : la confirmation ne doit pas se mettre en travers d'un onglet vide.
    expect(screen.queryByText('Replace the editor content?')).not.toBeInTheDocument();
  });

  it('names the Flink table, not the dotted topic', async () => {
    // `resolveScope` résout vers la clé du catalogue — pour un topic, le nom pointé. Écrit tel
    // quel dans la requête, `demo.orders.1.received` est lu par Flink comme un identifiant en
    // quatre parties et ne résout rien : la table est enregistrée sous `demo_orders_1_received`.
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT * FROM demo_orders_1_received');

    // Le panneau nomme la table qu'il va viser, et c'est la même que celle du SQL.
    expect(screen.getByText('demo_orders_1_received')).toBeInTheDocument();

    await userEvent.click(applyButton());
    await userEvent.click(await screen.findByRole('button', { name: 'Replace' }));

    await waitFor(() => expect(editor().value).toContain('TUMBLE'));
    expect(editor().value).toContain('demo_orders_1_received');
    expect(editor().value).not.toContain('demo.orders.1.received');
  });

  it('asks before overwriting a tab that holds SQL, and keeps it when the answer is no', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1 FROM a');

    await userEvent.click(applyButton());

    expect(await screen.findByText('Replace the editor content?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    // Le refus est un refus : la requête en cours d'écriture est intacte.
    expect(editor().value).toBe('SELECT 1 FROM a');
  });

  /*
   * L'annulation promise par le dialogue et par le toast n'existe que parce que le remplacement
   * passe par l'API d'édition de Monaco : écrire la valeur depuis React appelle `model.setValue()`,
   * qui vide la pile d'annulation. jsdom ne peut pas exercer un vrai ⌘Z, mais il peut vérifier
   * l'invariant dont il dépend — sans quoi une « simplification » en `updateSql(text)` laisserait
   * toute cette suite au vert en supprimant l'échappatoire que l'écran annonce.
   */
  it('poses the query through the undo stack, not by setting the value', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1 FROM a');

    await userEvent.click(applyButton());
    await userEvent.click(await screen.findByRole('button', { name: 'Replace' }));
    await waitFor(() => expect(editor().value).toContain('TUMBLE'));

    const replacement = submittedEdits.filter(e => e.range._full);
    expect(replacement).toHaveLength(1);
    expect(replacement[0].text).toContain('TUMBLE');
  });

  it('replaces the whole tab once the replacement is confirmed', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1 FROM a');

    await userEvent.click(applyButton());
    await userEvent.click(await screen.findByRole('button', { name: 'Replace' }));

    // Remplacement, pas insertion : rien de l'ancien texte ne survit dans l'onglet.
    await waitFor(() => expect(editor().value).toContain('TUMBLE'));
    expect(editor().value).not.toContain('SELECT 1 FROM a');
  });
});

describe('QueryWorkbench — sharing by link', () => {
  it('reopens a query whose SQL contains a percent sign', async () => {
    // The double-decode this replaced threw URIError inside a useState initializer, so the page
    // did not mount at all — on the most ordinary predicate an exploration tool has.
    const sql = "SELECT * FROM t WHERE name LIKE '%foo%'";
    const router = createMemoryRouter(
      [{ path: '/query', element: <ToastProvider><ConfirmProvider><QueryWorkbench /></ConfirmProvider></ToastProvider> }],
      { initialEntries: [`/query?sql=${encodeURIComponent(sql)}`] },
    );
    render(<RouterProvider router={router} />);
    await waitFor(() => expect(editor().value).toBe(sql));
  });
});

describe('QueryWorkbench — the results grid', () => {
  const RESULT = {
    columns: ['customerId', 'note'],
    rows: [{ customerId: 'C1', note: null }, { customerId: 'C2', note: '' }],
    error: null,
    engine: 'KAFKA_DIRECT',
  };

  beforeEach(async () => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: RESULT }));
  });

  const runAndWait = async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT 1');
    await userEvent.click(screen.getByRole('button', { name: /Run query/ }));
    return screen.findByRole('columnheader', { name: /customerId/ });
  };

  it('shows the column name as the engine spelled it', async () => {
    const header = await runAndWait();
    // Not `CUSTOMERID`: the header is data, and the identifier has to stay retypable.
    expect(header).toHaveTextContent('customerId');
  });

  it('distinguishes a NULL from an empty string', async () => {
    await runAndWait();
    expect(screen.getByText('NULL')).toBeInTheDocument();
  });

  it('opens the row beside the grid on a cell click, and closes it on a sort', async () => {
    await runAndWait();
    await userEvent.click(screen.getByText('C1'));

    const detail = await screen.findByRole('complementary', { name: 'Row detail' });
    expect(within(detail).getByText('customerId')).toBeInTheDocument();

    // Sorting reorders the rows, so the index the panel holds no longer designates the same one.
    // Scoped to the header: the detail panel carries a "Copy customerId" button of its own.
    const header = screen.getByRole('columnheader', { name: /customerId/ });
    await userEvent.click(within(header).getByRole('button'));
    await waitFor(() =>
      expect(screen.queryByRole('complementary', { name: 'Row detail' })).not.toBeInTheDocument());
  });
});

describe('QueryWorkbench — history', () => {
  it('records a failed query, which is the one worth reopening', async () => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: { columns: [], rows: [], error: "Object 'nope' not found" } }));
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.type(editor(), 'SELECT * FROM nope');
    await userEvent.click(screen.getByRole('button', { name: /Run query/ }));

    await waitFor(() => {
      const stored = JSON.parse(localStorage.getItem('kse:query-history') ?? '[]');
      expect(stored[0]).toMatchObject({ sql: 'SELECT * FROM nope', ok: false });
    });
  });
});

/*
 * Le mode Job est le seul geste de cette page sans repli, et son panneau affichait le statut lu
 * ~150 ms après le départ, puis plus rien : un job mort à sa première ligne restait vert, et le
 * tableau de bord était le seul endroit où l'apprendre.
 */
describe('QueryWorkbench — a submitted job is followed, not only announced', () => {
  const selectJobMode = () => userEvent.click(screen.getByRole('button', { name: 'Flink job' }));

  const submission = {
    queryId: 'q-7', flinkJobId: 'f-7', statementType: 'INSERT', executionMode: 'ASYNC_JOB',
    status: 'RUNNING', sql: 'INSERT INTO sink SELECT id FROM demo_orders_1_received',
    startedAt: 1_700_000_000_000, endedAt: null, cancelRequested: false,
  };

  const submit = async (sql: string) => {
    post.mockImplementation((url: string) => (url === '/api/query/jobs'
      ? Promise.resolve({ data: submission })
      : Promise.resolve({ data: { valid: true } })));
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();
    await userEvent.clear(editor());
    await userEvent.type(editor(), sql);
    await userEvent.click(screen.getByRole('button', { name: /Submit job/i }));
  };

  it('shows the job with a way to stop it, and stopping asks the server', async () => {
    await submit('INSERT INTO sink SELECT id FROM demo_orders_1_received');

    const stop = await screen.findByRole('button', { name: 'Stop' });
    await userEvent.click(stop);

    await waitFor(() => expect(post).toHaveBeenCalledWith(
      '/api/query/cancel/q-7', null, expect.anything()));
  });

  /*
   * Un STATEMENT SET réunit plusieurs INSERT en **un** job — donc une seule lecture de la source.
   * Le garde du navigateur le refusait avant même d'appeler le serveur, qui l'accepte.
   */
  it('submits a STATEMENT SET instead of refusing it before the server sees it', async () => {
    await submit('EXECUTE STATEMENT SET BEGIN INSERT INTO sink SELECT id FROM demo_orders_1_received; END');

    await waitFor(() => expect(post).toHaveBeenCalledWith(
      '/api/query/jobs', expect.objectContaining({ sql: expect.stringContaining('STATEMENT SET') }),
      expect.anything()));
    expect(screen.queryByText(/only accepts INSERT/)).not.toBeInTheDocument();
  });
});
