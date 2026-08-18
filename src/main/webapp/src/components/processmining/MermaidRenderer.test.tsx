// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MermaidRenderer from './MermaidRenderer';

/*
 * Mermaid is mocked: what is under test is this component's handling of a render that fails and of
 * two renders in flight, not mermaid's parser. jsdom cannot lay out an SVG anyway.
 */
const renderMock = vi.fn();
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: (id: string, chart: string) => renderMock(id, chart),
  },
}));

describe('MermaidRenderer', () => {
  beforeEach(() => {
    renderMock.mockReset();
  });

  it('renders the diagram mermaid returns', async () => {
    renderMock.mockResolvedValue({ svg: '<svg data-testid="diagram"></svg>' });

    const { container } = render(<MermaidRenderer chart="flowchart TD\nA-->B" />);

    await waitFor(() => expect(container.querySelector('svg')).not.toBeNull());
  });

  it('shows the reason and the offending source when mermaid refuses the chart', async () => {
    renderMock.mockRejectedValue(new Error('Parse error on line 2'));

    render(<MermaidRenderer chart="not a diagram" />);

    expect(await screen.findByText(/Parse error on line 2/)).toBeTruthy();
    expect(screen.getByText('not a diagram')).toBeTruthy();
  });

  /**
   * The defect this component carried: the SVG was written through a ref, so the effect bailed out
   * on a null ref — and after a failed render the ref was null for ever, the error branch having
   * replaced the container. The next chart was never drawn and the stale error stayed on screen.
   * One malformed diagram from the model killed the panel for the rest of a live session.
   */
  it('recovers on the next window after a failed render', async () => {
    renderMock.mockRejectedValueOnce(new Error('Parse error on line 2'));
    const { rerender, container } = render(<MermaidRenderer chart="broken" />);
    expect(await screen.findByText(/Parse error on line 2/)).toBeTruthy();

    renderMock.mockResolvedValue({ svg: '<svg id="recovered"></svg>' });
    rerender(<MermaidRenderer chart="flowchart TD\nA-->B" />);

    await waitFor(() => expect(container.querySelector('#recovered')).not.toBeNull());
    expect(screen.queryByText(/Parse error on line 2/)).toBeNull();
  });

  /**
   * In live mode a chart arrives per window and mermaid.render is async, so a slow render of an
   * earlier window could land after a later one and leave the older diagram on screen.
   */
  it('ignores a render that a newer chart has superseded', async () => {
    let resolveFirst: (v: { svg: string }) => void = () => {};
    renderMock
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
      .mockResolvedValue({ svg: '<svg id="second"></svg>' });

    const { rerender, container } = render(<MermaidRenderer chart="first" />);
    rerender(<MermaidRenderer chart="second" />);
    await waitFor(() => expect(container.querySelector('#second')).not.toBeNull());

    // The stale render now completes; it must not replace the diagram that superseded it.
    resolveFirst({ svg: '<svg id="first"></svg>' });
    await waitFor(() => expect(container.querySelector('#second')).not.toBeNull());
    expect(container.querySelector('#first')).toBeNull();
  });

  it('says so rather than drawing nothing when there is no chart', () => {
    render(<MermaidRenderer chart="" />);
    expect(screen.getByText(/No flowchart available yet/)).toBeTruthy();
    expect(renderMock).not.toHaveBeenCalled();
  });

  it('reports NO_CHANGE as a result, not as an empty diagram', () => {
    render(<MermaidRenderer chart="NO_CHANGE" />);
    expect(screen.getByText(/No structural changes detected/)).toBeTruthy();
    expect(renderMock).not.toHaveBeenCalled();
  });
});
