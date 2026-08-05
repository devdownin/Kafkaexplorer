import { createRef } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  Button, Badge, Field, Input, EmptyState, ErrorPanel, Stat,
  Table, TableHead, TableBody, TableRow, Th, Td, TableSkeleton,
  Tooltip, HelpTip,
} from './index';

describe('Button', () => {
  it('renders its label and fires onClick', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Run query</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Run query' }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('is disabled and shows a spinner while loading', () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Save</Button>);
    const btn = screen.getByRole('button', { name: /Save/ });
    expect(btn).toBeDisabled();
    expect(btn.querySelector('.animate-spin')).toBeTruthy();
  });

  it('applies the primary variant surface class', () => {
    render(<Button variant="primary">Go</Button>);
    expect(screen.getByRole('button', { name: 'Go' })).toHaveClass('bg-primary');
  });

  it('forwards its ref to the underlying button element', () => {
    const ref = createRef<HTMLButtonElement>();
    render(<Button ref={ref}>Focus me</Button>);
    expect(ref.current).toBeInstanceOf(HTMLButtonElement);
  });
});

describe('Tooltip', () => {
  /**
   * Le point de tout l'exercice : `title=""` n'apparaît qu'à la souris. Une explication qui
   * n'atteint pas le clavier n'explique rien à qui navigue au clavier.
   */
  it('opens on focus, not only on hover', async () => {
    render(<Tooltip content="Compares the record key itself"><button>Key</button></Tooltip>);
    const tip = screen.getByRole('tooltip');

    expect(tip).toHaveClass('opacity-0');
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Key' })).toHaveFocus();
    expect(tip).toHaveClass('opacity-100');
  });

  it('closes again on Escape', async () => {
    render(<Tooltip content="Explanation"><button>Key</button></Tooltip>);
    await userEvent.tab();
    expect(screen.getByRole('tooltip')).toHaveClass('opacity-100');

    await userEvent.keyboard('{Escape}');
    expect(screen.getByRole('tooltip')).toHaveClass('opacity-0');
  });

  /** La description reste montée : un `aria-describedby` qui pointe dans le vide n'est rien. */
  it('describes its trigger whether it is open or not', () => {
    render(<Tooltip content="Explanation"><button>Key</button></Tooltip>);
    const trigger = screen.getByRole('button', { name: 'Key' });
    const tip = screen.getByRole('tooltip');

    expect(trigger).toHaveAttribute('aria-describedby', tip.id);
    expect(tip).toHaveTextContent('Explanation');
  });

  it('gives a HelpTip an accessible name of its own', async () => {
    render(<HelpTip label="More information about the scan budget" content="How many records." />);
    const button = screen.getByRole('button', { name: 'More information about the scan budget' });

    await userEvent.tab();
    expect(button).toHaveFocus();
    expect(screen.getByRole('tooltip')).toHaveClass('opacity-100');
  });
});

describe('Badge', () => {
  it('renders its content and an optional status dot', () => {
    const { container } = render(<Badge tone="success" dot>Healthy</Badge>);
    expect(screen.getByText('Healthy')).toBeInTheDocument();
    // dot is an aria-hidden span with a rounded-full class
    expect(container.querySelector('.rounded-full')).toBeTruthy();
  });
});

describe('Field', () => {
  it('links the label to the control and shows the description', () => {
    render(
      <Field label="Bootstrap servers" description="host:port pairs">
        {(p) => <Input {...p} placeholder="localhost:9092" />}
      </Field>,
    );
    const input = screen.getByLabelText('Bootstrap servers');
    expect(input).toBeInTheDocument();
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(screen.getByText('host:port pairs')).toBeInTheDocument();
  });

  it('marks the control invalid and surfaces an alert on error', () => {
    render(
      <Field label="Name" error="Required">
        {(p) => <Input {...p} />}
      </Field>,
    );
    expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('Required');
  });
});

describe('Stat', () => {
  it('shows the value when not loading', () => {
    render(<Stat label="Total Topics" value={142} />);
    expect(screen.getByText('142')).toBeInTheDocument();
  });

  it('hides the value and shows a shimmer while loading', () => {
    const { container } = render(<Stat label="Total Topics" value={142} loading />);
    expect(screen.queryByText('142')).not.toBeInTheDocument();
    expect(container.querySelector('.skeleton-shimmer')).toBeTruthy();
  });
});

describe('Table primitives', () => {
  it('renders header and body cells', () => {
    render(
      <Table>
        <TableHead><tr><Th>Topic</Th></tr></TableHead>
        <TableBody><TableRow><Td>orders.created</Td></TableRow></TableBody>
      </Table>,
    );
    expect(screen.getByRole('columnheader', { name: 'Topic' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'orders.created' })).toBeInTheDocument();
  });

  it('TableSkeleton renders the requested number of rows', () => {
    const { container } = render(<TableSkeleton rows={5} columns={3} />);
    // header row + 5 body rows, each a flex row of shimmer blocks
    expect(container.querySelectorAll('.skeleton-shimmer').length).toBeGreaterThanOrEqual(5 * 3);
  });

  it('stays unbounded when rowCount is at or below the threshold', () => {
    const { container } = render(
      <Table rowCount={25} scrollThreshold={25}>
        <TableBody><TableRow><Td>x</Td></TableRow></TableBody>
      </Table>,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.className).not.toContain('kse-scroll-table');
    expect(wrapper.style.maxHeight).toBe('');
  });

  it('caps height with a sticky-header scroll container past the threshold', () => {
    const { container } = render(
      <Table rowCount={26} scrollThreshold={25} maxBodyHeight={400}>
        <TableBody><TableRow><Td>x</Td></TableRow></TableBody>
      </Table>,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.className).toContain('kse-scroll-table');
    expect(wrapper.className).toContain('overflow-auto');
    expect(wrapper.style.maxHeight).toBe('400px');
  });
});

describe('EmptyState', () => {
  it('renders title, description and an action', async () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        title="No metrics yet"
        description="Pick a template"
        action={<Button onClick={onClick}>Add metric</Button>}
      />,
    );
    expect(screen.getByRole('heading', { name: 'No metrics yet' })).toBeInTheDocument();
    expect(screen.getByText('Pick a template')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add metric' }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('ErrorPanel', () => {
  const error = {
    title: 'Unknown column "amountt"',
    hint: 'Check the column name against the topic schema.',
    raw: 'SQL validation failed: column amountt not found in table orders',
  };

  it('shows the readable title and the hint', () => {
    render(<ErrorPanel error={error} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(error.title)).toBeInTheDocument();
    expect(screen.getByText(error.hint)).toBeInTheDocument();
  });

  /** Le message brut est la vraie raison du refus : caché par défaut, jamais jeté. */
  it('keeps the raw error one click away', async () => {
    render(<ErrorPanel error={error} />);
    expect(screen.queryByText(error.raw)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Show raw error/ }));
    expect(screen.getByText(error.raw)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Hide raw error/ }));
    expect(screen.queryByText(error.raw)).not.toBeInTheDocument();
  });

  it('offers no raw toggle when the raw text adds nothing', () => {
    render(<ErrorPanel error={{ title: 'Boom', raw: 'Boom' }} />);
    expect(screen.queryByRole('button', { name: /raw error/ })).not.toBeInTheDocument();
  });

  it('reports the SQL position when the engine cited one', () => {
    render(<ErrorPanel error={{ ...error, location: { line: 3, column: 12 } }} />);
    expect(screen.getByText('Line 3, column 12')).toBeInTheDocument();
  });

  it('fires retry and dismiss', async () => {
    const onRetry = vi.fn();
    const onDismiss = vi.fn();
    render(<ErrorPanel error={error} onRetry={onRetry} onDismiss={onDismiss} />);
    await userEvent.click(screen.getByRole('button', { name: /Retry/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss error' }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});
