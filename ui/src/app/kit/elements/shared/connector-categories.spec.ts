import { ConnectorCard, ConnectorGroup } from '../../../core/api/connector-types.api';
import { connectorCategories, connectorMark, filterConnectorGroups } from './connector-categories';

const card = (type: string, name: string, over: Partial<ConnectorCard> = {}): ConnectorCard => ({
  type, variant: null, name, icon: 'database', description: `${name} reads.`, kind: 'built-in', version: null,
  signed: null, tools: 4, toolsFrom: null, connections: 0, available: true, reason: null, presetConfig: null, ...over,
});

/** The core's coarse groups, in its order. */
const CORE: ConnectorGroup[] = [
  {
    name: 'Databases',
    cards: [
      card('postgres', 'PostgreSQL'), card('mongodb', 'MongoDB'), card('bigquery', 'Google BigQuery'),
      card('opensearch', 'OpenSearch'), card('prometheus', 'Prometheus'), card('cassandra', 'Apache Cassandra'),
      card('acme-db', 'Acme DB', { kind: 'plugin' }), card('duckdb', 'DuckDB', { kind: 'plugin', available: false }),
    ],
  },
  { name: 'Remote access', cards: [card('ssh', 'SSH', { icon: 'terminal' })] },
  { name: 'Messaging', cards: [card('acme-queue', 'Acme Queue', { kind: 'plugin' })] },
];

const names = (groups: readonly ConnectorGroup[]) => groups.map((g) => `${g.name}: ${g.cards.map((c) => c.type).join(' ')}`);

describe('connector categories', () => {
  it('splits the core groups into the picker categories in the kit order, keeping the order of the cards', () => {
    expect(names(connectorCategories(CORE))).toEqual([
      'SQL databases: postgres duckdb',
      'NoSQL databases: mongodb cassandra',
      'Data warehouses: bigquery',
      'Search engines: opensearch',
      'Metrics and time series: prometheus',
      'Other databases: acme-db',
      'Remote access: ssh',
      'Messaging: acme-queue',
    ]);
  });

  it('gives a variant its own category and mark (OpenSearch through the Logs type)', () => {
    const logs: ConnectorGroup[] = [{ name: 'Logs', cards: [
      card('logs', 'OpenSearch', { variant: 'opensearch' }), card('logs', 'Loki', { variant: 'loki' }),
    ] }];
    expect(names(connectorCategories(logs))).toEqual(['Search engines: logs', 'Logs: logs']);
    expect(connectorMark('logs', 'opensearch')).toBe('assets/integrations/opensearch.svg');
    expect(connectorMark('logs', 'loki')).toBe('assets/integrations/loki.svg');
  });

  it('names the vendor mark of a type, and none for a type that keeps its icon', () => {
    expect(connectorMark('postgres')).toBe('assets/integrations/postgresql.svg');
    expect(connectorMark('firebase-rtdb')).toBe('assets/integrations/firebase-realtime-database.svg');
    expect(connectorMark('gcp-logging')).toBe('assets/integrations/cloud-logging.svg');
    expect(connectorMark('mcp-server')).toBe('assets/integrations/mcp.svg');
    for (const type of ['oracle', 'gcp', 'ssh', 'ssh-logs', 'http', 'acme-db']) {
      expect(connectorMark(type), type).toBeNull();
    }
  });

  it('filters by name, other names, category and description: every word must match', () => {
    const all = connectorCategories(CORE);
    expect(names(filterConnectorGroups(all, ''))).toEqual(names(all));
    expect(names(filterConnectorGroups(all, '  Cockroach '))).toEqual(['SQL databases: postgres']);
    expect(names(filterConnectorGroups(all, 'nosql'))).toEqual(['NoSQL databases: mongodb cassandra']);
    expect(names(filterConnectorGroups(all, 'scylladb'))).toEqual(['NoSQL databases: cassandra']);
    expect(names(filterConnectorGroups(all, 'google warehouse'))).toEqual(['Data warehouses: bigquery']);
    expect(names(filterConnectorGroups(all, 'google shell'))).toEqual([]);
    expect(names(filterConnectorGroups(all, 'promql'))).toEqual(['Metrics and time series: prometheus']);
  });
});
