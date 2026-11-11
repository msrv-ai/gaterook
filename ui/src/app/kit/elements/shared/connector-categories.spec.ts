import { ConnectorCard, ConnectorGroup } from '../../../core/api/connector-types.api';
import { compatibleService, withCompatibleServices } from './compatible-services';
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
    for (const type of ['oracle', 'ssh', 'ssh-logs', 'http', 'acme-db']) {
      expect(connectorMark(type), type).toBeNull();
    }
  });

  it('gives every cloud type a mark: SVG by default, the PNG files of Google Cloud and Cloudflare by name', () => {
    expect(connectorMark('gcp')).toBe('assets/integrations/google-cloud.png');
    expect(connectorMark('cloudflare')).toBe('assets/integrations/cloudflare.png');
    expect(connectorMark('aws')).toBe('assets/integrations/aws.svg');
    expect(connectorMark('azure')).toBe('assets/integrations/azure.svg');
    expect(connectorMark('azure-monitor')).toBe('assets/integrations/azure-monitor.svg');
    expect(connectorMark('s3')).toBe('assets/integrations/s3.svg');
    expect(connectorMark('cloudwatch')).toBe('assets/integrations/cloudwatch.svg');
    expect(connectorMark('kubernetes')).toBe('assets/integrations/kubernetes.svg');
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

describe('compatible services', () => {
  const core = (): ConnectorGroup[] => [
    { name: 'Databases', cards: [card('postgres', 'PostgreSQL'), card('neo4j', 'Neo4j'), card('duckdb', 'DuckDB', { kind: 'plugin' })] },
  ];

  it('adds a card after its base type: the base type and tools, its own name and starting values, no count', () => {
    const groups = withCompatibleServices(core());
    const keys = groups[0].cards.map((c) => (c.variant ? `${c.type}:${c.variant}` : c.type));
    expect(keys.slice(0, 3)).toEqual(['postgres', 'postgres:supabase', 'postgres:neon']);
    expect(keys).toContain('neo4j:aura');
    // Plugins get none: their compatible services aren't known.
    expect(keys.filter((k) => k.startsWith('duckdb'))).toEqual(['duckdb']);
    const supabase = groups[0].cards.find((c) => c.variant === 'supabase')!;
    expect(supabase).toMatchObject({ type: 'postgres', name: 'Supabase', tools: 4, connections: 0, compatibleWith: 'PostgreSQL',
      presetConfig: { connection: { sslMode: 'verify-full' } } });
  });

  it('puts a family in its own category after the base ones, and the others next to their base type', () => {
    const names = (gs: ConnectorGroup[]) => gs.map((g) => `${g.name}: ${g.cards.map((c) => c.variant ?? c.type).slice(0, 3).join(' ')}`);
    expect(names(connectorCategories(withCompatibleServices(core())))).toEqual([
      'SQL databases: postgres duckdb',
      'NoSQL databases: neo4j aura',
      'PostgreSQL-compatible: supabase neon aws-rds',
    ]);
  });

  it('shows a service’s own mark, never its base type’s, and finds it by its own words only', () => {
    expect(connectorMark('postgres', 'supabase')).toBe('assets/integrations/supabase.svg');
    expect(connectorMark('mongodb', 'atlas')).toBe('assets/integrations/mongodb.svg');
    expect(compatibleService('postgres', 'supabase')?.group).toBe('PostgreSQL-compatible');
    expect(compatibleService('postgres', null)).toBeNull();
    const all = connectorCategories(withCompatibleServices(core()));
    expect(names(filterConnectorGroups(all, 'cockroach'))).toEqual(['SQL databases: postgres', 'PostgreSQL-compatible: postgres']);
    expect(filterConnectorGroups(all, 'supavisor')).toEqual([]);
  });
});
