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

/** Poser une sélection, comme un glissement de souris dans l'éditeur. */
const setSelection = (start: number, end: number) => {
  selectionRange = { start, end };
  cursorListeners.forEach(fn => fn({ selection: makeSelection() }));
};

/** Dernière plage passée à `setSelection`, en coordonnées ligne/colonne Monaco. */
let lastSetSelection: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number } | null = null;

/** Le texte que la sélection posée recouvre — ce qu'une frappe remplacerait. */
function selectedText(): string {
  if (!lastSetSelection) return '';
  const lines = currentValue.split('\n');
  const offsetOf = (line: number, column: number) =>
    lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0) + column - 1;
  return currentValue.slice(
    offsetOf(lastSetSelection.startLineNumber, lastSetSelection.startColumn),
    offsetOf(lastSetSelection.endLineNumber, lastSetSelection.endColumn),
  );
}

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
      // verrait jamais le SQL posé.
      executeEdits: (_source: string, edits: { range: { _full?: boolean }; text: string }[]) => {
        const edit = edits[0];
        if (edit?.range?._full) onChange(edit.text);
      },
      focus: () => {},
      setPosition: () => {},
      setSelection: (range: typeof lastSetSelection) => { lastSetSelection = range; },
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
  lastSetSelection = null;
  currentValue = '';
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

describe('QueryWorkbench — the sidebar follows the execution mode', () => {
  const selectJobMode = () => userEvent.click(screen.getByRole('button', { name: 'Flink job' }));
  const clickTopic = () =>
    userEvent.click(screen.getByRole('button', { name: 'INSERT INTO from demo.orders.1.received' }));

  /** Le schéma que `/api/query/schema/{table}` rend pour une table générée par l'application. */
  const withSchema = (schema: Record<string, string>) => get.mockImplementation((url: string) => {
    if (url === '/api/query/init') return Promise.resolve({ data: CATALOGUE });
    if (url.startsWith('/api/query/schema/')) return Promise.resolve({ data: schema });
    return Promise.resolve({ data: {} });
  });

  it('writes an INSERT INTO in Flink job mode, the only statement that mode accepts', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await clickTopic();

    await waitFor(() => expect(editor().value).toContain('INSERT INTO demo_orders_1_received_out'));
    expect(editor().value).toContain('FROM demo_orders_1_received');
    expect(editor().value).not.toContain('LIMIT');
  });

  /*
   * `proc_time AS PROCTIME()` est une colonne calculée que le générateur de DDL ajoute à toute
   * table : elle sort d'un `SELECT *` mais aucun sink ne l'accepte, donc l'INSERT échouait sur
   * un désaccord d'arité — une erreur qui ne nomme pas sa cause.
   */
  it('lists the columns and leaves out the computed one, which no sink accepts', async () => {
    withSchema({ id: 'STRING', amount: 'DOUBLE', event_time: 'TIMESTAMP(3)', proc_time: 'TIMESTAMP_LTZ(3)' });
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await clickTopic();

    await waitFor(() => expect(editor().value).toContain('`id`'));
    expect(editor().value).toContain('`amount`');
    expect(editor().value).toContain('`event_time`');
    expect(editor().value).not.toContain('proc_time');
    expect(editor().value).not.toContain('SELECT *');
  });

  it('falls back to SELECT * when Flink does not know the table yet, and says what that risks', async () => {
    // Table non enregistrée : le schéma résolu est vide, il n'y a aucune colonne à lister.
    withSchema({});
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await clickTopic();

    await waitFor(() => expect(editor().value).toContain('SELECT * FROM demo_orders_1_received'));
    expect(editor().value).toContain('proc_time, which a sink refuses');
  });

  /*
   * Un nom inventé ne peut qu'échouer ; une table du catalogue résout. C'est tout ce qui est
   * promis — le commentaire renvoie la vérification des colonnes à l'utilisateur.
   */
  it('targets an existing Flink table when the catalogue holds one', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/api/query/init') {
        return Promise.resolve({ data: { ...CATALOGUE, tables: ['demo_orders_1_received_enriched'] } });
      }
      return Promise.resolve({ data: {} });
    });
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await clickTopic();

    await waitFor(() => expect(editor().value).toContain('INSERT INTO demo_orders_1_received_enriched'));
    expect(editor().value).toContain('is an existing Flink table');
    expect(editor().value).not.toContain('_received_out');
    // Et c'est ce nom-là que la frappe remplacera.
    await waitFor(() => expect(selectedText()).toBe('demo_orders_1_received_enriched'));
  });

  it('leaves the placeholder selected, so the first keystroke replaces it', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await clickTopic();

    await waitFor(() => expect(selectedText()).toBe('demo_orders_1_received_out'));
  });

  // L'écran vide n'offrait aucun point de départ en mode Job : les propositions étaient
  // conditionnées au mode lecture, alors que c'est là que la forme attendue est la moins évidente.
  it('offers a starting point in Job mode too, and it is an INSERT', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    const starter = await screen.findByText(/INSERT INTO demo_orders_1_received_out SELECT … FROM/);
    await userEvent.click(starter);

    await waitFor(() => expect(editor().value).toContain('INSERT INTO demo_orders_1_received_out'));
    // Même geste que la barre latérale : la cible est posée sélectionnée.
    await waitFor(() => expect(selectedText()).toBe('demo_orders_1_received_out'));
  });

  it('selects nothing in Read mode — there is no placeholder to replace', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');

    await userEvent.click(screen.getByRole('button', { name: 'SELECT from demo.orders.1.received' }));

    await waitFor(() => expect(editor().value).toContain('SELECT * FROM'));
    expect(selectedText()).toBe('');
  });

  // Le SELECT du mode lecture était refusé par la garde de mode : le clic ne pouvait mener
  // qu'au panneau « Flink Job mode only accepts INSERT INTO ».
  it('no longer poses a statement its own Run button would refuse', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();

    await userEvent.click(screen.getByRole('button', { name: 'INSERT INTO from demo.orders.1.received' }));
    await waitFor(() => expect(editor().value).toContain('INSERT INTO'));

    await userEvent.click(screen.getByRole('button', { name: /Submit job/ }));

    // Le SQL a passé la garde de mode et atteint l'endpoint des jobs, au lieu de s'arrêter sur
    // « Flink Job mode only accepts INSERT INTO ».
    await waitFor(() => expect(post.mock.calls.some(c => c[0] === '/api/query/jobs')).toBe(true));
    const submitted = post.mock.calls.find(c => c[0] === '/api/query/jobs')![1] as { sql: string };
    expect(submitted.sql).toContain('INSERT INTO demo_orders_1_received_out');
    expect(screen.queryByText('Flink Job mode only accepts INSERT INTO')).not.toBeInTheDocument();
  });

  it('goes back to a bounded SELECT when Read mode is selected again', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await selectJobMode();
    await userEvent.click(screen.getByRole('button', { name: 'Sync read' }));

    await userEvent.click(screen.getByRole('button', { name: 'SELECT from demo.orders.1.received' }));

    await waitFor(() => expect(editor().value).toBe('SELECT * FROM demo_orders_1_received LIMIT 50'));
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

describe('QueryWorkbench — Flink Job mode runs several statements too', () => {
  const JOB = {
    queryId: 'q1', flinkJobId: 'f1', statementType: 'INSERT_INTO', status: 'RUNNING',
    sql: 'INSERT INTO x SELECT 1', startedAt: 0, endedAt: null, cancelRequested: false,
  };
  const sql = 'INSERT INTO x SELECT * FROM a;\nINSERT INTO y SELECT * FROM b;';

  beforeEach(() => {
    post.mockImplementation((url: string) => url === '/api/query/validate'
      ? Promise.resolve({ data: { valid: true } })
      : Promise.resolve({ data: JOB }));
  });

  /*
   * Run visait déjà la seule instruction sous le curseur en mode Job, mais le compteur et
   * « Run all » y étaient masqués : un onglet de trois INSERT en soumettait un, sans que rien ne
   * le dise. Une exécution partielle silencieuse est ce que ce compteur existe pour empêcher.
   */
  it('says which statement Run will submit, and offers to submit them all', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.click(screen.getByRole('button', { name: 'Flink job' }));
    await userEvent.clear(editor());
    await userEvent.paste(sql);
    setCursor(0);

    expect(await screen.findByText('Statement 1/2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Run all/ })).toBeInTheDocument();
  });

  it('submits every INSERT of the tab', async () => {
    renderPage();
    await screen.findByText('demo.orders.1.received');
    await userEvent.click(screen.getByRole('button', { name: 'Flink job' }));
    await userEvent.clear(editor());
    await userEvent.paste(sql);
    setCursor(0);

    await userEvent.click(await screen.findByRole('button', { name: /Run all/ }));

    await waitFor(() => expect(post.mock.calls.filter(c => c[0] === '/api/query/jobs')).toHaveLength(2));
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
