// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import Help from './Help';
import { LESSONS } from './helpContent';

/*
 * The wiring, not the content.
 *
 * `help.test.ts` checks that the examples stay executable; this checks that the page hands them
 * over intact — the "Open in editor" link is the one thing on this page a reader will click
 * expecting a query to be there, and it is a URL built by hand.
 */

const renderPage = () =>
  render(<RouterProvider router={createMemoryRouter([{ path: '/', element: <Help /> }], { initialEntries: ['/'] })} />);

/** Chaque leçon est un `role="article"` nommé par son titre — cible stable, sans classe CSS. */
const lessonCard = (title: string) => screen.getByRole('article', { name: new RegExp(title, 'i') });

describe('Help', () => {
  it('shows the whole path, each step numbered for a screen reader too', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: /learn by doing/i })).toBeInTheDocument();
    LESSONS.forEach((lesson) => expect(lessonCard(lesson.title)).toBeInTheDocument());
    expect(lessonCard(LESSONS[0].title)).toHaveAccessibleName(`Step 1: ${LESSONS[0].title}`);
  });

  it('carries the exact SQL into the editor link', () => {
    renderPage();
    const lesson = LESSONS[0];
    const link = within(lessonCard(lesson.title)).getByRole('link', { name: /open in editor/i });
    const href = link.getAttribute('href') ?? '';
    expect(new URLSearchParams(href.slice(href.indexOf('?'))).get('sql')).toBe(lesson.sql);
  });

  // Le mode « Flink job » a été retiré de l'éditeur : plus aucune leçon ne propose une
  // instruction que Run refuserait, donc chacune s'ouvre.
  it('opens every step in the editor, none of them being refused', () => {
    renderPage();
    LESSONS.forEach((lesson) => {
      const card = lessonCard(lesson.title);
      expect(within(card).getByRole('link', { name: /open in editor/i })).toBeInTheDocument();
    });
  });

  it('filters the path, and says so when nothing matches', async () => {
    const user = userEvent.setup();
    renderPage();
    const filter = screen.getByLabelText(/filter the steps/i);

    await user.type(filter, 'xpath');
    expect(lessonCard('Query an XML topic')).toBeInTheDocument();
    expect(screen.queryByRole('article', { name: /join two topics/i })).toBeNull();

    await user.clear(filter);
    await user.type(filter, 'zzzz');
    expect(screen.getByText(/no step matches/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /clear the filter/i }));
    expect(lessonCard('Join two topics')).toBeInTheDocument();
  });

  it('sanitizes whatever topic name is typed into the naming lab', async () => {
    const user = userEvent.setup();
    renderPage();
    const input = screen.getByLabelText(/kafka topic/i);

    await user.clear(input);
    await user.type(input, 'orders.v2-raw');
    expect(screen.getByText('SELECT * FROM orders_v2_raw')).toBeInTheDocument();
  });
});
