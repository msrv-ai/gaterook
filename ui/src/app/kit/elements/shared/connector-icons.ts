/**
 * Connector type → the kit's icon and display name (BLUEPRINT §7 dictionaries, as the kit's pages use them). The core
 * sends `typeName` where it has one; this is for lists that only carry the type id.
 */
export const CONNECTOR_ICONS: Record<string, string> = {
  postgres: 'database',
  mysql: 'database',
  sqlserver: 'database',
  sql: 'database',
  mongodb: 'leaf',
  redis: 'layers',
  opensearch: 'database',
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
  sqlserver: 'SQL Server',
  mongodb: 'MongoDB',
  redis: 'Redis',
  opensearch: 'OpenSearch',
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
  'mcp-server': 'MCP server',
};

export function connectorIcon(type: string): string {
  return CONNECTOR_ICONS[type] ?? 'plug';
}

export function connectorName(type: string): string {
  return CONNECTOR_NAMES[type] ?? type;
}
