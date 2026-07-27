import type { FC } from 'react';
import { Combobox } from './Combobox';
import type { ComboboxProps } from './Combobox';
import { useCatalog } from '../../catalogStore';

export type TopicInputProps = Omit<ComboboxProps, 'options'> & {
  /** Suggère les tables Flink au lieu des topics Kafka. */
  source?: 'topics' | 'tables';
};

/**
 * Saisie d'un nom de topic, suggéré depuis le catalogue déjà chargé par `Layout`.
 * La saisie libre reste acceptée : un topic peut exister sans être encore dans le catalogue
 * (TTL de cache de 30 s côté serveur, création récente).
 */
export const TopicInput: FC<TopicInputProps> = ({ source = 'topics', ...props }) => {
  const catalog = useCatalog();
  return <Combobox options={source === 'tables' ? catalog.tables : catalog.topics} {...props} />;
};

export default TopicInput;
