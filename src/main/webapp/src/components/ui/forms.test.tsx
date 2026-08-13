import { describe, it, expect, afterEach } from 'vitest';
import { useState } from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { Combobox } from './Combobox';
import { NumberInput } from './NumberInput';
import { PasswordInput } from './PasswordInput';
import { Field, Input } from './Field';
import { TopicInput } from './TopicInput';
import { setCatalog, resetCatalog } from '../../catalogStore';

afterEach(() => {
  cleanup();
  resetCatalog();
});

function ComboHarness({ options, value: initial = '' }: { options: string[]; value?: string }) {
  const [value, setValue] = useState(initial);
  return <Combobox value={value} onChange={setValue} options={options} aria-label="Topic" />;
}

function NumberHarness() {
  const [value, setValue] = useState(4096);
  return (
    <>
      <NumberInput value={value} onChange={setValue} fallback={4096} min={256} max={32768} aria-label="Max tokens" />
      <output data-testid="committed">{value}</output>
    </>
  );
}

describe('Combobox', () => {
  const options = ['orders.created', 'orders.shipped', 'payments.done'];

  it('filters options as the user types', () => {
    render(<ComboHarness options={options} />);
    fireEvent.change(screen.getByLabelText('Topic'), { target: { value: 'orders' } });
    expect(screen.getAllByRole('option').map(o => o.textContent))
      .toEqual(['orders.created', 'orders.shipped']);
  });

  it('accepts a value that is not in the list', () => {
    render(<ComboHarness options={options} />);
    const input = screen.getByLabelText('Topic');
    fireEvent.change(input, { target: { value: 'brand.new.topic' } });
    expect(input).toHaveValue('brand.new.topic');
    // Free text is the point: suggestions must never block an unknown topic.
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  it('selects the highlighted option with the keyboard', () => {
    render(<ComboHarness options={options} />);
    const input = screen.getByLabelText('Topic');
    fireEvent.change(input, { target: { value: 'orders' } });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(input).toHaveValue('orders.shipped');
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  it('exposes the active option through aria-activedescendant', () => {
    render(<ComboHarness options={options} />);
    const input = screen.getByLabelText('Topic');
    fireEvent.change(input, { target: { value: 'orders' } });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    const active = screen.getAllByRole('option')[0];
    expect(input).toHaveAttribute('aria-activedescendant', active.id);
    expect(active).toHaveAttribute('aria-selected', 'true');
  });

  it('does not suggest anything when the only match equals the input', () => {
    render(<ComboHarness options={options} />);
    fireEvent.change(screen.getByLabelText('Topic'), { target: { value: 'payments.done' } });
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  // Une valeur déjà choisie se filtrait elle-même jusqu'à la liste vide : le chevron ne faisait
  // plus rien, et l'éditeur de métrique — qui pré-remplit le topic — ne permettait plus d'en
  // changer par la liste.
  it('opens the whole list from the chevron even when a value is already selected', () => {
    render(<ComboHarness options={options} value="payments.done" />);
    fireEvent.click(screen.getByRole('button', { name: 'Show all suggestions' }));
    expect(screen.getAllByRole('option').map(o => o.textContent)).toEqual(options);
  });

  it('highlights the current value when the whole list is opened', () => {
    render(<ComboHarness options={options} value="payments.done" />);
    fireEvent.click(screen.getByRole('button', { name: 'Show all suggestions' }));
    expect(screen.getAllByRole('option')[2]).toHaveAttribute('aria-selected', 'true');
  });

  it('picks another option from the opened list', () => {
    render(<ComboHarness options={options} value="payments.done" />);
    const input = screen.getByLabelText('Topic');
    fireEvent.click(screen.getByRole('button', { name: 'Show all suggestions' }));
    fireEvent.pointerDown(screen.getByText('orders.shipped'));
    expect(input).toHaveValue('orders.shipped');
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  it('opens the whole list with ArrowDown when the value filters it to nothing', () => {
    render(<ComboHarness options={options} value="payments.done" />);
    const input = screen.getByLabelText('Topic');
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(screen.getAllByRole('option')).toHaveLength(3);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    // Le surlignage part de la valeur courante (index 2), donc la suivante boucle sur la première.
    expect(input).toHaveValue('orders.created');
  });

  it('returns to filtering as soon as the user types', () => {
    render(<ComboHarness options={options} value="payments.done" />);
    const input = screen.getByLabelText('Topic');
    fireEvent.click(screen.getByRole('button', { name: 'Show all suggestions' }));
    fireEvent.change(input, { target: { value: 'orders' } });
    expect(screen.getAllByRole('option').map(o => o.textContent))
      .toEqual(['orders.created', 'orders.shipped']);
  });
});

describe('TopicInput', () => {
  it('suggests topics from the shared catalog', () => {
    setCatalog(['orders.created', 'orders.shipped'], []);
    render(<TopicInput value="" onChange={() => {}} aria-label="Topic" />);
    fireEvent.focus(screen.getByLabelText('Topic'));
    expect(screen.getAllByRole('option')).toHaveLength(2);
  });
});

describe('NumberInput', () => {
  it('lets the field be cleared without snapping back mid-typing', () => {
    render(<NumberHarness />);
    const input = screen.getByLabelText('Max tokens');
    // The old `parseInt(v) || 4096` jumped straight back to the default here.
    fireEvent.change(input, { target: { value: '' } });
    expect(input).toHaveValue(null);
    fireEvent.change(input, { target: { value: '512' } });
    expect(input).toHaveValue(512);
    expect(screen.getByTestId('committed')).toHaveTextContent('512');
  });

  it('accepts a leading zero instead of treating it as falsy', () => {
    render(<NumberHarness />);
    const input = screen.getByLabelText('Max tokens');
    fireEvent.change(input, { target: { value: '0' } });
    expect(input).toHaveValue(0);
  });

  it('falls back and clamps on blur', () => {
    render(<NumberHarness />);
    const input = screen.getByLabelText('Max tokens');

    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);
    expect(screen.getByTestId('committed')).toHaveTextContent('4096');

    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.blur(input);
    expect(screen.getByTestId('committed')).toHaveTextContent('256');

    fireEvent.change(input, { target: { value: '999999' } });
    fireEvent.blur(input);
    expect(screen.getByTestId('committed')).toHaveTextContent('32768');
  });
});

describe('PasswordInput', () => {
  it('toggles between masked and revealed', () => {
    render(<PasswordInput aria-label="Keystore password" defaultValue="hunter2" />);
    const input = screen.getByLabelText('Keystore password');
    expect(input).toHaveAttribute('type', 'password');
    fireEvent.click(screen.getByRole('button', { name: 'Show value' }));
    expect(input).toHaveAttribute('type', 'text');
    fireEvent.click(screen.getByRole('button', { name: 'Hide value' }));
    expect(input).toHaveAttribute('type', 'password');
  });
});

describe('Field', () => {
  it('honours an explicit id so a form can focus the first invalid control', () => {
    render(
      <Field label="Bootstrap Servers" id="cfg-bootstrap" error="Required">
        {p => <Input {...p} />}
      </Field>,
    );
    const input = screen.getByLabelText('Bootstrap Servers');
    expect(input).toHaveAttribute('id', 'cfg-bootstrap');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('Required');
  });
});
