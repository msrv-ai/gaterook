import { ConnectorCard, ConnectorGroup } from '../../../core/api/connector-types.api';
import { COMPATIBLE_CATEGORIES, compatibleService } from './compatible-services';

/**
 * The categories of the connector-type picker (Add connection, step 1), in the kit's order. The core groups types more
 * coarsely ("Databases"); the picker splits them by what developers look for. A type this list doesn't know keeps the
 * core's group, and an unknown database type goes to "Other databases".
 */
export const CONNECTOR_CATEGORIES = [
  'SQL databases',
  'NoSQL databases',
  'Data warehouses',
  'Search engines',
  'Metrics and time series',
  'Other databases',
  'Logs',
  'Remote access',
  'Kubernetes',
  'Cloud',
  'HTTP and MCP',
  ...COMPATIBLE_CATEGORIES,
] as const;

const CATEGORY_OF: Readonly<Record<string, string>> = {
  postgres: 'SQL databases',
  mysql: 'SQL databases',
  oracle: 'SQL databases',
  sqlserver: 'SQL databases',
  sqlite: 'SQL databases',
  spanner: 'SQL databases',
  duckdb: 'SQL databases',
  h2: 'SQL databases',
  mongodb: 'NoSQL databases',
  redis: 'NoSQL databases',
  dynamodb: 'NoSQL databases',
  firestore: 'NoSQL databases',
  'firebase-rtdb': 'NoSQL databases',
  cosmosdb: 'NoSQL databases',
  cassandra: 'NoSQL databases',
  neo4j: 'NoSQL databases',
  bigquery: 'Data warehouses',
  snowflake: 'Data warehouses',
  redshift: 'Data warehouses',
  databricks: 'Data warehouses',
  trino: 'Data warehouses',
  athena: 'Data warehouses',
  clickhouse: 'Data warehouses',
  opensearch: 'Search engines',
  elasticsearch: 'Search engines',
  prometheus: 'Metrics and time series',
  influxdb: 'Metrics and time series',
};

/**
 * Connector type → its vendor's mark in `assets/integrations/` (official artwork; provenance and terms in its
 * SOURCES.md): an SVG file's base name, or a file name with its extension for the PNG marks of Google Cloud (its site
 * icon: the asset collection has only a lockup unreadable on a 32 px tile) and Cloudflare (its press kit has no SVG).
 * A type without one keeps its kit icon: the protocols (SSH, HTTP) that have no vendor.
 */
const MARKS: Readonly<Record<string, string>> = {
  postgres: 'postgresql',
  mysql: 'mysql',
  oracle: 'oracle',
  sqlserver: 'sqlserver',
  sqlite: 'sqlite',
  duckdb: 'duckdb',
  mongodb: 'mongodb',
  redis: 'redis',
  dynamodb: 'dynamodb',
  firestore: 'firestore',
  'firebase-rtdb': 'firebase-realtime-database',
  cosmosdb: 'cosmosdb',
  cassandra: 'cassandra',
  neo4j: 'neo4j',
  bigquery: 'bigquery',
  spanner: 'spanner',
  snowflake: 'snowflake',
  redshift: 'redshift',
  databricks: 'databricks',
  trino: 'trino',
  athena: 'athena',
  clickhouse: 'clickhouse',
  opensearch: 'opensearch',
  elasticsearch: 'elasticsearch',
  prometheus: 'prometheus',
  influxdb: 'influxdb',
  'gcp-logging': 'cloud-logging',
  cloudwatch: 'cloudwatch',
  'azure-monitor': 'azure-monitor',
  loki: 'loki',
  kubernetes: 'kubernetes',
  gcp: 'google-cloud.png',
  aws: 'aws',
  azure: 'azure',
  cloudflare: 'cloudflare.png',
  s3: 's3',
  'mcp-server': 'mcp',
};

/**
 * Other names a developer may type for a type: its vendor, short names and the language it speaks. The compatible
 * services' cards share them (CQL finds ScyllaDB), so a service with a card of its own never belongs here: "Supabase"
 * finds the Supabase card, not PostgreSQL's.
 */
const ALIASES: Readonly<Record<string, string>> = {
  postgres: 'postgresql pg psql',
  oracle: 'plsql',
  sqlserver: 'mssql microsoft t-sql',
  sqlite: 'file',
  duckdb: 'parquet csv file',
  h2: 'h2database java jdbc',
  mongodb: 'mongo document',
  redis: 'cache key-value',
  dynamodb: 'aws amazon key-value document',
  firestore: 'firebase google gcp document',
  'firebase-rtdb': 'firebase realtime google gcp',
  cosmosdb: 'azure microsoft cosmos document',
  cassandra: 'cql wide-column',
  neo4j: 'graph cypher',
  bigquery: 'google gcp warehouse',
  spanner: 'google gcp cloud googlesql distributed',
  snowflake: 'warehouse',
  redshift: 'aws amazon warehouse',
  databricks: 'spark lakehouse unity catalog warehouse',
  trino: 'presto lakehouse',
  athena: 'aws amazon s3 glue',
  clickhouse: 'olap analytics',
  opensearch: 'search',
  elasticsearch: 'elastic search',
  prometheus: 'promql metrics',
  influxdb: 'influx influxql time series metrics',
  'gcp-logging': 'google cloud logging gke',
  cloudwatch: 'aws amazon',
  loki: 'grafana logql',
  'ssh-logs': 'log files server',
  ssh: 'shell terminal server runbook',
  kubernetes: 'k8s cluster pods',
  gcp: 'google cloud',
  aws: 'amazon web services',
  azure: 'microsoft',
  cloudflare: 'workers dns zones cdn r2 d1 kv',
  s3: 'aws amazon bucket object storage files r2 minio spaces backblaze b2',
  'azure-monitor': 'microsoft log analytics kql',
  http: 'rest api openapi',
  'mcp-server': 'model context protocol',
};

/**
 * `assets/integrations/<file>` for the vendor mark of a type (or of its variant: the Logs type's OpenSearch), or
 * `null` when the type keeps its icon.
 */
export function connectorMark(type: string, variant: string | null = null): string | null {
  // A compatible service shows its own vendor's mark, or the base type's icon: never the base type's mark.
  const service = compatibleService(type, variant);
  if (service) return service.mark ? `assets/integrations/${service.mark}` : null;
  const file = (variant && MARKS[variant]) || MARKS[type];
  return file ? `assets/integrations/${file.includes('.') ? file : `${file}.svg`}` : null;
}

/** The picker's category of a card (type and variant) that the core put in `group`. */
export function connectorCategory(type: string, group: string, variant: string | null = null): string {
  const service = compatibleService(type, variant);
  if (service?.group) return service.group;
  return (variant && CATEGORY_OF[variant]) || CATEGORY_OF[type] || (group === 'Databases' ? 'Other databases' : group);
}

/**
 * The core's groups regrouped into the picker's categories, in {@link CONNECTOR_CATEGORIES} order (a category the list
 * doesn't know, such as a plugin's own group, follows them); each keeps the core's order of its cards.
 */
export function connectorCategories(groups: readonly ConnectorGroup[]): ConnectorGroup[] {
  const byName = new Map<string, ConnectorCard[]>(CONNECTOR_CATEGORIES.map((name) => [name, []]));
  for (const group of groups) {
    for (const card of group.cards) {
      const name = connectorCategory(card.type, group.name, card.variant);
      const cards = byName.get(name) ?? [];
      cards.push(card);
      byName.set(name, cards);
    }
  }
  return [...byName].filter(([, cards]) => cards.length).map(([name, cards]) => ({ name, cards }));
}

/** The words of a text in lower case, split at anything but a letter or a digit: "T-SQL" is "t" and "sql". */
function searchWords(text: string): string[] {
  return text.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
}

/**
 * The words a search box matches for a card: its name, type and variant, its other names and its category. A
 * compatible service adds its own words and its base type's name, other names and category, so "sql" finds Supabase.
 * A description isn't matched: Cosmos DB's mentions SQL queries, and "sql" must not find NoSQL databases.
 */
export function connectorSearchWords(card: ConnectorCard, category: string): string[] {
  const service = compatibleService(card.type, card.variant);
  // A service's variant id ("azure", "aws") is no type: its other names would be the cloud's, not the service's.
  const variantAliases = service ? null : ALIASES[card.variant ?? ''];
  const baseCategory = service ? CATEGORY_OF[card.type] : null;
  return searchWords([card.name, card.type, card.variant, service?.search, card.compatibleWith, variantAliases,
    ALIASES[card.type], category, baseCategory].filter(Boolean).join(' '));
}

/**
 * The categories with only the cards that match every word of `query`, each as the start of one of the card's words
 * ("post" finds PostgreSQL; "sql" finds the SQL databases but not "NoSQL"); a blank query keeps everything.
 */
export function filterConnectorGroups(groups: readonly ConnectorGroup[], query: string): ConnectorGroup[] {
  const wanted = searchWords(query);
  if (!wanted.length) return [...groups];
  const matches = (card: ConnectorCard, category: string) => {
    const words = connectorSearchWords(card, category);
    return wanted.every((w) => words.some((word) => word.startsWith(w)));
  };
  return groups
    .map((g) => ({ name: g.name, cards: g.cards.filter((c) => matches(c, g.name)) }))
    .filter((g) => g.cards.length);
}
