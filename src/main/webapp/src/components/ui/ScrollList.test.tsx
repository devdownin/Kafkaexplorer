import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { ScrollList } from './ScrollList';

const items = (n: number) => Array.from({ length: n }, (_, i) => <div key={i}>item {i}</div>);

describe('ScrollList', () => {
  it('renders transparently at or below the threshold (no scroll, no max-height)', () => {
    const { container } = render(<ScrollList threshold={25}>{items(25)}</ScrollList>);
    const el = container.firstElementChild as HTMLElement;
    expect(el.className).not.toContain('overflow-y-auto');
    expect(el.style.maxHeight).toBe('');
  });

  it('becomes scrollable with a bounded height above the threshold', () => {
    const { container } = render(<ScrollList threshold={25}>{items(26)}</ScrollList>);
    const el = container.firstElementChild as HTMLElement;
    expect(el.className).toContain('overflow-y-auto');
    // jsdom has no layout → measurement is unavailable, so the fallback applies.
    expect(el.style.maxHeight).toBe('22rem');
  });

  it('honours an explicit numeric maxHeight (px)', () => {
    const { container } = render(<ScrollList threshold={5} maxHeight={320}>{items(10)}</ScrollList>);
    const el = container.firstElementChild as HTMLElement;
    expect(el.style.maxHeight).toBe('320px');
  });

  it('honours an explicit CSS maxHeight and a custom fallback', () => {
    const { container } = render(
      <ScrollList threshold={5} fallbackMaxHeight="30vh">{items(10)}</ScrollList>,
    );
    expect((container.firstElementChild as HTMLElement).style.maxHeight).toBe('30vh');
  });

  it('uses the explicit count prop over the children count', () => {
    // Two DOM children, but count says 100 → scrollable.
    const { container } = render(
      <ScrollList threshold={25} count={100}><div>a</div><div>b</div></ScrollList>,
    );
    expect((container.firstElementChild as HTMLElement).className).toContain('overflow-y-auto');
  });
});
