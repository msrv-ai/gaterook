/**
 * Connector type → the kit's icon and display name (shared connector vocabulary). The core
 * sends `typeName` where it has one; this is for lists that only carry the type id.
 */
export const CONNECTOR_ICONS: Record<string, string> = {
  postgres: 'database',
  mysql: 'database',
  oracle: 'database',
  sqlserver: 'database',
  sqlite: 'database',
  dynamodb: 'database',
  bigquery: 'database',
  snowflake: 'database',
  redshift: 'database',
  databricks: 'database',
  trino: 'database',
  athena: 'database',
  firestore: 'database',
  'firebase-rtdb': 'database',
  cosmosdb: 'database',
  prometheus: 'activity',
  influxdb: 'activity',
  sql: 'database',
  mongodb: 'leaf',
  redis: 'layers',
  opensearch: 'database',
  elasticsearch: 'database',
  ssh: 'terminal',
  'ssh-logs': 'scroll-text',
  logs: 'scroll-text',
  'gcp-logging': 'scroll-text',
  cloudwatch: 'scroll-text',
  loki: 'scroll-text',
  kubernetes: 'ship-wheel',
  gcp: 'cloud',
  aws: 'cloud',
  http: 'globe',
  'mcp-server': 'plug',
};

export const CONNECTOR_NAMES: Record<string, string> = {
  postgres: 'PostgreSQL',
  mysql: 'MySQL',
  oracle: 'Oracle Database',
  sqlserver: 'SQL Server',
  sqlite: 'SQLite',
  dynamodb: 'Amazon DynamoDB',
  bigquery: 'Google BigQuery',
  snowflake: 'Snowflake',
  redshift: 'Amazon Redshift',
  databricks: 'Databricks SQL',
  trino: 'Trino / Presto',
  athena: 'Amazon Athena',
  firestore: 'Cloud Firestore',
  'firebase-rtdb': 'Firebase Realtime Database',
  cosmosdb: 'Azure Cosmos DB',
  prometheus: 'Prometheus',
  influxdb: 'InfluxDB',
  mongodb: 'MongoDB',
  redis: 'Redis',
  opensearch: 'OpenSearch',
  elasticsearch: 'Elasticsearch',
  ssh: 'SSH',
  'ssh-logs': 'SSH log files',
  logs: 'Logs',
  'gcp-logging': 'GCP Cloud Logging',
  cloudwatch: 'AWS CloudWatch',
  loki: 'Loki',
  kubernetes: 'Kubernetes',
  gcp: 'GCP',
  aws: 'AWS',
  http: 'HTTP API',
  'mcp-server': 'External MCP server',
};

export function connectorIcon(type: string): string {
  return CONNECTOR_ICONS[type] ?? 'plug';
}

export function connectorName(type: string): string {
  return CONNECTOR_NAMES[type] ?? type;
}
