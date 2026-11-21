// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { useEffect, useState, type ComponentProps } from 'react';
import Editor from '@monaco-editor/react';

type EditorProps = ComponentProps<typeof Editor>;
/** Monaco must follow the same theme as the shell even when the editor stays mounted. */
export default function ThemedEditor(props: EditorProps) {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme === 'light' ? 'kse-light' : 'kse-dark');
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setTheme(document.documentElement.dataset.theme === 'light' ? 'kse-light' : 'kse-dark');
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);
  return <Editor {...props} theme={theme} />;
}
