import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import Compare from './Compare';
import { ToastProvider } from '../components/Toast';
import type { TopicMessage } from './compare';

/*
 * The regression this file exists for: `GET /api/topic/{name}` returns `TopicMessage` objects, and
 * this page still read them as strings. `tryParse` was handed an object, `JSON.parse("[object
 * Object]")` threw, the card fell through to its "not JSON" branch — which rendered the object
 * itself as a React child. React error #31, whole page replaced by the error boundary, on the
 * first click of "Run compare".
 *
 * So the test renders the real component against the real response shape, and asserts the page
 * survives it.
 */

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const message = (offset: number, value: string | null, partition = 0): TopicMessage => ({
  partition, offset, timestamp: 1_700_000_000_000, key: `ORD-${offset}`, value,
  headers: { 'correlation-id': `corr-${offset}` }, valueBytes: value?.length ?? 0, truncated: false,
});

const renderPage = () =>
  render(
    <ToastProvider>
      <RouterProvider router={createMemoryRouter([{ path: '/', element: <Compare /> }], { initialEntries: ['/'] })} />
    </ToastProvider>,
  );

const samplesFor = (topic: string): TopicMessage[] =>
  topic === 'orders.a'
    ? [message(1, '{"id":"ORD-1","status":"NEW"}'), message(2, '{"id":"ORD-2","status":"NEW"}')]
    : [message(1, '{"id":"ORD-1","status":"NEW"}'), message(2, '{"id":"ORD-2","status":"SHIPPED"}')];

beforeEach(() => {
  vi.clearAllMocks();
  // La page garde ses réglages dans `localStorage` (`compare:diffOnly`…), qui survit d'un cas à
  // l'autre dans le même fichier : sans ce nettoyage, le cas qui active « Diff only » filtre les
  // suivants.
  localStorage.clear();
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/compare/topics') return Promise.resolve({ data: ['orders.a', 'orders.b'] });
    const topic = url.replace('/api/topic/', '');
    return Promise.resolve({ data: { samples: samplesFor(topic) } });
  });
});

describe('Compare', () => {
  it('renders record objects without crashing, and shows their coordinates', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /run compare/i }));

    await waitFor(() => expect(screen.getAllByText(/p0 · offset 1/).length).toBe(2));
    // The payload is rendered field by field, never as "[object Object]".
    expect(screen.queryByText(/\[object Object\]/)).toBeNull();
    expect(screen.getAllByText('status:').length).toBeGreaterThan(0);
  });

  it('counts the differing pair, not the identical one', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /run compare/i }));

    // Le libellé et le compteur sont dans le même span, séparés par un <b> : on matche le span.
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.textContent === 'Differences 1' && el.tagName === 'SPAN'))
        .toBeInTheDocument());
    expect(screen.getByText((_, el) => el?.textContent === 'Compared 2' && el.tagName === 'SPAN'))
      .toBeInTheDocument();
  });

  it('keeps both panes on the same pair under "Diff only"', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /run compare/i }));
    await waitFor(() => expect(screen.getAllByText(/offset 2/).length).toBe(2));

    await user.click(screen.getByRole('switch', { name: /diff only/i }));

    // Only the second pair differs, and it must show on both sides — the old code filtered each
    // column separately and then indexed the other column by the filtered position.
    await waitFor(() => expect(screen.queryAllByText(/offset 1$/)).toHaveLength(0));
    const cards = screen.getAllByText(/p0 · offset 2/);
    expect(cards).toHaveLength(2);

    const statuses = screen.getAllByText('status:').map(
      (label) => within(label.parentElement as HTMLElement).getByText(/"NEW"|"SHIPPED"/).textContent,
    );
    expect(statuses).toEqual(['"NEW"', '"SHIPPED"']);
  });

  it('renders a non-JSON payload as text', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/compare/topics') return Promise.resolve({ data: ['orders.a', 'orders.b'] });
      return Promise.resolve({ data: { samples: [message(1, '<Order id="1"/>')] } });
    });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /run compare/i }));

    await waitFor(() => expect(screen.getAllByText('<Order id="1"/>').length).toBe(2));
  });

  /*
   * « Sync scroll » était persisté dans localStorage et lu par rien : le bouton basculait, et
   * les deux panneaux continuaient de défiler chacun de leur côté.
   */
  it('scrolls both panes together while Sync scroll is on', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /run compare/i }));
    await waitFor(() => expect(screen.getAllByText(/p0 · offset 1/).length).toBe(2));

    const paneA = screen.getByTestId('pane-A');
    const paneB = screen.getByTestId('pane-B');

    paneA.scrollTop = 240;
    fireEvent.scroll(paneA);
    expect(paneB.scrollTop).toBe(240);

    // Et dans l'autre sens, ce qui n'irait pas de soi si le garde anti-écho bloquait le miroir.
    paneB.scrollTop = 90;
    fireEvent.scroll(paneB);
    await waitFor(() => expect(paneA.scrollTop).toBe(90));
  });

  it('leaves the panes independent when Sync scroll is off', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());
    await user.click(screen.getByRole('switch', { name: /sync scroll/i }));
    await user.click(screen.getByRole('button', { name: /run compare/i }));
    await waitFor(() => expect(screen.getAllByText(/p0 · offset 1/).length).toBe(2));

    const paneA = screen.getByTestId('pane-A');
    const paneB = screen.getByTestId('pane-B');
    paneA.scrollTop = 240;
    fireEvent.scroll(paneA);
    expect(paneB.scrollTop).toBe(0);
  });

  /* Le champ « Shared Filter Context » n'était relié à rien : il est retiré, pas laissé décoratif. */
  it('offers no control that does nothing', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());
    expect(screen.queryByLabelText(/shared filter/i)).toBeNull();
    expect(screen.queryByPlaceholderText(/filter applied to both topics/i)).toBeNull();
  });

  it('survives a response with no samples at all', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/compare/topics') return Promise.resolve({ data: ['orders.a', 'orders.b'] });
      return Promise.resolve({ data: {} });
    });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText('Topic A')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /run compare/i }));

    // Reported as a successful load of nothing, not as "Failed to fetch topic samples".
    await waitFor(() => expect(screen.getByText(/Loaded 0 \+ 0 messages/)).toBeInTheDocument());
  });
});
