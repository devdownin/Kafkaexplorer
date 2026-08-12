import { describe, it, expect } from 'vitest';
import { connectionLabel, connectionTitle } from './connectionStatus';

describe('connectionLabel', () => {
  it('shows the configured name when connected', () => {
    expect(connectionLabel({ isHealthy: true, clusterName: 'Orders prod' })).toBe('Orders prod');
  });

  it('says it is still connecting rather than inventing a name', () => {
    expect(connectionLabel({ isHealthy: true })).toBe('Connecting…');
    expect(connectionLabel({ isHealthy: true, clusterName: '   ' })).toBe('Connecting…');
  });

  it('says Disconnected whatever the name is', () => {
    expect(connectionLabel({ isHealthy: false, clusterName: 'Orders prod' })).toBe('Disconnected');
  });
});

describe('connectionTitle', () => {
  it('puts the verified address beside the chosen name', () => {
    expect(connectionTitle({ isHealthy: true, clusterName: 'Orders prod', bootstrapServers: 'kafka:29092' }))
      .toBe('Connected — Orders prod · kafka:29092');
  });

  it('falls back to whichever half it knows', () => {
    expect(connectionTitle({ isHealthy: true, clusterName: 'Orders prod' })).toBe('Connected — Orders prod');
    expect(connectionTitle({ isHealthy: true, bootstrapServers: 'kafka:29092' })).toBe('Connected — kafka:29092');
  });

  it('admits it knows nothing yet', () => {
    expect(connectionTitle({ isHealthy: true })).toBe('Checking the connection…');
  });

  it('names the address that is not answering — that is the whole diagnosis', () => {
    expect(connectionTitle({ isHealthy: false, bootstrapServers: 'kafka:9092' }))
      .toBe('Disconnected — no answer from kafka:9092');
    expect(connectionTitle({ isHealthy: false })).toBe('Disconnected');
  });

  it('never repeats a stale name as if it were connected', () => {
    expect(connectionTitle({ isHealthy: false, clusterName: 'Orders prod', bootstrapServers: 'kafka:9092' }))
      .not.toContain('Orders prod');
  });
});
