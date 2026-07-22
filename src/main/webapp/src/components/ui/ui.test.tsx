import { createRef } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  Button, Badge, Field, Input, EmptyState, Stat,
  Table, TableHead, TableBody, TableRow, Th, Td, TableSkeleton,
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
