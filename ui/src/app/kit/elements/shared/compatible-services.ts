import { ConnectorCard, ConnectorGroup } from '../../../core/api/connector-types.api';
import { JsonObject } from '../../../core/api/types';

/**
 * Services that speak a built-in connector's protocol: Supabase and Neon speak PostgreSQL's, Valkey speaks RESP. The
 * connection-type picker shows each as a card of its own that opens the base connector's form with the service's
 * starting values, so a developer finds "Supabase" without knowing it is a PostgreSQL connection. The connection is
 * an ordinary connection of the base type. A service whose protocol has a big family gets a category of its own
 * ("PostgreSQL-compatible"); the others sit next to their base type ("Neo4j AuraDB" after Neo4j).
 */
export interface CompatibleService {
  /** The card's variant: unique within its base type, never one of the core's own variants. */
  readonly id: string;
  /** The built-in connector type it connects with. */
  readonly type: string;
  readonly name: string;
  /** What to know before connecting, in one or two sentences. */
  readonly description: string;
  /** Its category, or none to sit next to its base type in the base type's category. */
  readonly group?: string;
  /** The vendor's mark in `assets/integrations/` (with its extension), or none to show the base type's icon. */
  readonly mark?: string;
  /** Other words a developer may search for. */
  readonly search?: string;
  /** Starting values of the base form (merged over its defaults); none starts from the defaults. */
  readonly preset?: JsonObject;
}

const PG = 'PostgreSQL-compatible';
const MYSQL = 'MySQL-compatible';
const MSSQL = 'SQL Server-compatible';
const ORACLE = 'Oracle-compatible';
const MONGO = 'MongoDB-compatible';
const REDIS = 'Redis-compatible';
const CQL = 'Cassandra-compatible';
const PROM = 'Prometheus-compatible';

/** The compatible services' categories, in the picker's order: after every base category. */
export const COMPATIBLE_CATEGORIES = [PG, MYSQL, MSSQL, ORACLE, MONGO, REDIS, CQL, PROM] as const;

const verifyFull = (port?: number): JsonObject => ({ connection: { ...(port ? { port } : {}), sslMode: 'verify-full' } });
const tls = (extra: JsonObject = {}): JsonObject => ({ connection: { tls: true, ...extra } });

export const COMPATIBLE_SERVICES: readonly CompatibleService[] = [
  // ── PostgreSQL protocol ──
  { id: 'supabase', type: 'postgres', group: PG, name: 'Supabase', mark: 'supabase.svg', preset: verifyFull(),
    description: "Supabase Postgres. Use the direct connection or the session pooler, and paste Supabase's CA certificate for full verification." },
  { id: 'neon', type: 'postgres', group: PG, name: 'Neon', mark: 'neon.svg', preset: verifyFull(),
    description: 'Neon serverless Postgres over verified TLS. Use the direct host: the pooled host runs PgBouncer in transaction mode.' },
  { id: 'aws-rds', type: 'postgres', group: PG, name: 'Amazon Aurora and RDS for PostgreSQL', mark: 'aws-rds.svg',
    search: 'aws amazon aurora rds',
    description: 'With a database user and password. For full verification, paste the Amazon RDS CA bundle.' },
  { id: 'cloud-sql', type: 'postgres', group: PG, name: 'Cloud SQL for PostgreSQL', mark: 'gcp-cloud-sql.svg', search: 'google gcp',
    description: "With a database user and password, through the instance's IP and server CA certificate, or the Cloud SQL Auth Proxy on this computer." },
  { id: 'alloydb', type: 'postgres', group: PG, name: 'AlloyDB for PostgreSQL', mark: 'gcp-alloydb.svg', search: 'google gcp',
    description: "With a database user and password, through the instance's IP and the cluster's CA certificate, or the AlloyDB Auth Proxy on this computer." },
  { id: 'azure', type: 'postgres', group: PG, name: 'Azure Database for PostgreSQL', mark: 'azure-postgresql.svg', preset: verifyFull(),
    search: 'microsoft flexible server', description: 'Flexible server with a database user and password over verified TLS.' },
  { id: 'timescale', type: 'postgres', group: PG, name: 'TimescaleDB', mark: 'timescale.svg', search: 'tiger data cloud time series',
    description: 'TimescaleDB and Tiger Cloud: hypertables read as tables and continuous aggregates as views.' },
  { id: 'postgis', type: 'postgres', group: PG, name: 'PostGIS', mark: 'postgis.svg', search: 'spatial geometry geography gis',
    preset: { advanced: { allowedFunctions: ['st_asgeojson', 'st_astext', 'st_area', 'st_contains', 'st_distance', 'st_dwithin', 'st_intersects', 'st_length', 'st_srid', 'st_within', 'st_x', 'st_y'] } },
    description: 'PostgreSQL with PostGIS. The read-only spatial functions agents may call are listed under Advanced; add more there.' },
  { id: 'cockroachdb', type: 'postgres', group: PG, name: 'CockroachDB', mark: 'cockroachdb.svg', preset: verifyFull(26257), search: 'cockroach labs',
    description: "Port 26257 with the cluster's CA certificate. CockroachDB implements only part of PostgreSQL's catalogs and functions." },
  { id: 'yugabytedb', type: 'postgres', group: PG, name: 'YugabyteDB', mark: 'yugabytedb.svg', preset: { connection: { port: 5433 } }, search: 'ysql yugabyte aeon',
    description: "YugabyteDB's YSQL API, on port 5433 by default." },
  { id: 'edb', type: 'postgres', group: PG, name: 'EDB Postgres', mark: 'edb.svg', search: 'enterprisedb advanced server biganimal',
    description: 'EDB Postgres Advanced Server and EDB Postgres AI.' },
  { id: 'greenplum', type: 'postgres', group: PG, name: 'Greenplum', mark: 'greenplum.svg', search: 'mpp warehouse',
    description: "Greenplum's PostgreSQL interface; Greenplum 7 builds on PostgreSQL 12." },

  // ── MySQL protocol ──
  { id: 'mariadb', type: 'mysql', group: MYSQL, name: 'MariaDB', mark: 'mariadb.svg', search: 'skysql',
    description: 'MariaDB Server and MariaDB Cloud through the MySQL connector.' },
  { id: 'aws-rds', type: 'mysql', group: MYSQL, name: 'Amazon Aurora and RDS for MySQL', mark: 'aws-rds.svg', search: 'aws amazon aurora rds',
    description: 'With a database user and password. For full verification, paste the Amazon RDS CA bundle.' },
  { id: 'cloud-sql', type: 'mysql', group: MYSQL, name: 'Cloud SQL for MySQL', mark: 'gcp-cloud-sql.svg', search: 'google gcp',
    description: "With a database user and password, through the instance's IP and server CA certificate, or the Cloud SQL Auth Proxy on this computer." },
  { id: 'azure', type: 'mysql', group: MYSQL, name: 'Azure Database for MySQL', mark: 'azure-mysql.svg', search: 'microsoft flexible server',
    preset: verifyFull(), description: 'Flexible server with a database user and password over verified TLS.' },
  { id: 'planetscale', type: 'mysql', group: MYSQL, name: 'PlanetScale', mark: 'planetscale.svg', preset: verifyFull(), search: 'vitess',
    description: "PlanetScale's MySQL endpoint over verified TLS, with a branch's user and password." },
  { id: 'vitess', type: 'mysql', group: MYSQL, name: 'Vitess', mark: 'vitess.svg', search: 'vtgate',
    description: "Vitess through vtgate's MySQL port." },
  { id: 'tidb', type: 'mysql', group: MYSQL, name: 'TiDB', mark: 'tidb.svg', preset: { connection: { port: 4000 } }, search: 'pingcap tidb cloud',
    description: 'TiDB and TiDB Cloud, on port 4000 by default.' },
  { id: 'singlestore', type: 'mysql', group: MYSQL, name: 'SingleStore', mark: 'singlestore.svg', search: 'memsql helios',
    description: 'SingleStore Helios and self-managed SingleStore through the MySQL protocol.' },
  { id: 'percona', type: 'mysql', group: MYSQL, name: 'Percona Server for MySQL', mark: 'percona.svg',
    description: 'Percona Server for MySQL and Percona XtraDB Cluster.' },
  { id: 'oceanbase', type: 'mysql', group: MYSQL, name: 'OceanBase', mark: 'oceanbase.svg', preset: { connection: { port: 2881 } },
    description: 'OceanBase in MySQL mode, on port 2881 by default.' },
  { id: 'polardb', type: 'mysql', group: MYSQL, name: 'PolarDB for MySQL', mark: 'polardb.svg', search: 'alibaba cloud',
    description: "Alibaba Cloud's PolarDB for MySQL." },
  { id: 'starrocks', type: 'mysql', group: MYSQL, name: 'StarRocks', mark: 'starrocks.svg', preset: { connection: { port: 9030 } }, search: 'celerdata',
    description: "StarRocks through the frontend's MySQL port, 9030 by default." },
  { id: 'doris', type: 'mysql', group: MYSQL, name: 'Apache Doris', mark: 'doris.svg', preset: { connection: { port: 9030 } }, search: 'velodb selectdb',
    description: "Apache Doris through the frontend's MySQL port, 9030 by default." },

  // ── SQL Server and Oracle ──
  { id: 'azure-sql', type: 'sqlserver', group: MSSQL, name: 'Azure SQL Database', mark: 'azure-sql.svg', search: 'microsoft mssql',
    description: 'With SQL authentication over verified TLS; Microsoft Entra sign-in is not supported.' },
  { id: 'azure-sql-mi', type: 'sqlserver', group: MSSQL, name: 'Azure SQL Managed Instance', mark: 'azure-sql-mi.svg', search: 'microsoft mssql',
    description: 'With SQL authentication over verified TLS; Microsoft Entra sign-in is not supported.' },
  { id: 'aws-rds', type: 'sqlserver', group: MSSQL, name: 'Amazon RDS for SQL Server', mark: 'aws-rds.svg', search: 'aws amazon mssql',
    description: 'With SQL authentication. For full verification, paste the Amazon RDS CA bundle.' },
  { id: 'aws-rds', type: 'oracle', group: ORACLE, name: 'Amazon RDS for Oracle', mark: 'aws-rds.svg', search: 'aws amazon',
    description: 'With a database user and password. For full verification, paste the Amazon RDS CA bundle.' },
  { id: 'autonomous', type: 'oracle', group: ORACLE, name: 'Oracle Autonomous Database', mark: 'oracle.png', search: 'adb atp adw oci',
    preset: { connection: { port: 1522, sslMode: 'verify-full' } },
    description: 'Over TLS without a wallet: allow TLS connections without mutual TLS on the database first.' },

  // ── MongoDB protocol ──
  { id: 'atlas', type: 'mongodb', group: MONGO, name: 'MongoDB Atlas', mark: 'mongodb.svg', preset: tls({ mode: 'uri' }),
    description: "With the cluster's mongodb+srv:// connection string and a database user." },
  { id: 'documentdb', type: 'mongodb', group: MONGO, name: 'Amazon DocumentDB', mark: 'aws-documentdb.svg', preset: tls(), search: 'aws amazon',
    description: "Over TLS with the Amazon RDS CA bundle. It supports only part of MongoDB's aggregation stages and operators." },
  { id: 'cosmosdb', type: 'mongodb', group: MONGO, name: 'Azure Cosmos DB for MongoDB', mark: 'cosmosdb.svg', preset: tls(), search: 'microsoft ru vcore',
    description: "RU and vCore accounts over TLS. They support only part of MongoDB's aggregation stages and operators." },
  { id: 'ferretdb', type: 'mongodb', group: MONGO, name: 'FerretDB', mark: 'ferretdb.svg',
    description: "FerretDB's MongoDB API on PostgreSQL." },
  { id: 'percona', type: 'mongodb', group: MONGO, name: 'Percona Server for MongoDB', mark: 'percona.svg',
    description: 'Percona Server for MongoDB.' },
  { id: 'firestore', type: 'mongodb', group: MONGO, name: 'Firestore with MongoDB compatibility', mark: 'firestore.svg', preset: tls({ mode: 'uri' }),
    search: 'google gcp firebase', description: "With the database's connection string and its credentials." },

  // ── RESP ──
  { id: 'valkey', type: 'redis', group: REDIS, name: 'Valkey', mark: 'valkey.svg',
    description: 'Valkey through the Redis connector, which detects it.' },
  { id: 'elasticache', type: 'redis', group: REDIS, name: 'Amazon ElastiCache and MemoryDB', mark: 'aws-elasticache.svg', preset: tls(),
    search: 'aws amazon memorydb', description: 'With in-transit encryption and an AUTH token or an ACL user; IAM sign-in is not supported.' },
  { id: 'memorystore', type: 'redis', group: REDIS, name: 'Memorystore for Redis and Valkey', mark: 'gcp-memorystore.svg', preset: tls(),
    search: 'google gcp', description: "In-transit encryption needs the instance's CA certificate; IAM sign-in is not supported." },
  { id: 'azure', type: 'redis', group: REDIS, name: 'Azure Cache for Redis', mark: 'azure-cache-redis.svg', preset: tls({ port: 6380 }),
    search: 'microsoft azure managed redis', description: 'Over TLS with an access key: port 6380, or 10000 for Azure Managed Redis.' },
  { id: 'upstash', type: 'redis', group: REDIS, name: 'Upstash', mark: 'upstash.svg', preset: { connection: { tls: true }, credentials: { user: 'default' } },
    description: "Over TLS with the database's default user and password." },
  { id: 'dragonfly', type: 'redis', group: REDIS, name: 'Dragonfly', mark: 'dragonfly.svg', search: 'dragonflydb',
    description: "Dragonfly's Redis API." },
  { id: 'keydb', type: 'redis', group: REDIS, name: 'KeyDB', mark: 'keydb.svg',
    description: "KeyDB's Redis API." },
  { id: 'garnet', type: 'redis', group: REDIS, name: 'Garnet', mark: 'garnet.svg', search: 'microsoft',
    description: "Microsoft Garnet's RESP API, which implements part of Redis's commands." },

  // ── CQL ──
  { id: 'scylladb', type: 'cassandra', group: CQL, name: 'ScyllaDB', mark: 'scylladb.svg', search: 'scylla cloud',
    description: 'ScyllaDB and ScyllaDB Cloud through CQL.' },
  { id: 'keyspaces', type: 'cassandra', group: CQL, name: 'Amazon Keyspaces', mark: 'aws-keyspaces.svg', preset: { tls: true }, search: 'aws amazon',
    description: 'Over TLS on port 9142 with service-specific credentials.' },
  { id: 'azure', type: 'cassandra', group: CQL, name: 'Azure Managed Instance for Apache Cassandra', mark: 'azure-cassandra.svg', preset: { tls: true },
    search: 'microsoft', description: 'Over TLS with a database user and password.' },

  // ── Prometheus HTTP API ──
  { id: 'thanos', type: 'prometheus', group: PROM, name: 'Thanos', mark: 'thanos.svg', description: "Thanos Query's Prometheus API." },
  { id: 'mimir', type: 'prometheus', group: PROM, name: 'Grafana Mimir', mark: 'mimir.svg',
    description: 'Set the tenant when the cluster serves several.' },
  { id: 'cortex', type: 'prometheus', group: PROM, name: 'Cortex', mark: 'cortex.svg', description: 'Set the tenant when the cluster serves several.' },
  { id: 'victoriametrics', type: 'prometheus', group: PROM, name: 'VictoriaMetrics', mark: 'victoriametrics.svg', search: 'vmselect',
    description: "VictoriaMetrics' Prometheus API." },
  { id: 'grafana-cloud', type: 'prometheus', group: PROM, name: 'Grafana Cloud Metrics', mark: 'grafana.svg', preset: { auth: { mode: 'basic' } },
    description: "With the stack's instance ID and an access policy token." },

  // ── Next to their base type ──
  { id: 'starburst', type: 'trino', name: 'Starburst', mark: 'starburst.svg', search: 'galaxy enterprise',
    description: 'Starburst Galaxy and Starburst Enterprise through the Trino connector.' },
  { id: 'azure', type: 'databricks', name: 'Azure Databricks', mark: 'azure-databricks.svg', search: 'microsoft',
    description: 'Azure Databricks workspaces and their SQL warehouses.' },
  { id: 'aws', type: 'opensearch', name: 'Amazon OpenSearch Service', mark: 'aws-opensearch.svg', search: 'aws amazon',
    description: 'With fine-grained access control and an internal user and password.' },
  { id: 'cloud', type: 'elasticsearch', name: 'Elastic Cloud', mark: 'elasticsearch.svg', search: 'elastic',
    description: 'Elastic Cloud deployments and serverless projects with an API key.' },
  { id: 'aura', type: 'neo4j', name: 'Neo4j AuraDB', mark: 'neo4j.svg', description: "With neo4j+s:// and the instance's credentials." },
  { id: 'cloud', type: 'influxdb', name: 'InfluxDB Cloud', mark: 'influxdb.svg', description: 'InfluxDB Cloud with an API token.' },
  { id: 'grafana-cloud', type: 'loki', name: 'Grafana Cloud Logs', mark: 'grafana.svg', description: "With the stack's user ID and an access policy token." },
  { id: 'eks', type: 'kubernetes', name: 'Amazon EKS', mark: 'aws-eks.svg', search: 'aws amazon',
    description: "A cluster from your kubeconfig; its aws CLI sign-in runs once you trust the command." },
  { id: 'gke', type: 'kubernetes', name: 'Google Kubernetes Engine', mark: 'gcp-gke.svg', search: 'google gcp gke',
    description: "A cluster from your kubeconfig; its gke-gcloud-auth-plugin sign-in runs once you trust the command." },
  { id: 'aks', type: 'kubernetes', name: 'Azure Kubernetes Service', mark: 'azure-aks.svg', search: 'microsoft aks',
    description: "A cluster from your kubeconfig; its kubelogin sign-in runs once you trust the command." },
  { id: 'openshift', type: 'kubernetes', name: 'Red Hat OpenShift', mark: 'openshift.svg', search: 'okd',
    description: 'A cluster from your kubeconfig, with a service account token or oc login.' },
  { id: 'k3s', type: 'kubernetes', name: 'k3s', mark: 'k3s.svg', search: 'rancher k3d',
    description: 'A k3s cluster from its kubeconfig.' },
];

const BY_KEY = new Map(COMPATIBLE_SERVICES.map((s) => [`${s.type}:${s.id}`, s]));

/** The compatible service a card stands for (`type:variant`), if it is one. */
export function compatibleService(type: string, variant: string | null): CompatibleService | null {
  return variant ? (BY_KEY.get(`${type}:${variant}`) ?? null) : null;
}

/**
 * The core's groups with a card for every compatible service whose base type the core offers, placed right after its
 * base card: the same type, availability and tools, the service's own name, description and starting values, and no
 * connection count (a count by type would claim the base type's connections for the service).
 */
export function withCompatibleServices(groups: readonly ConnectorGroup[]): ConnectorGroup[] {
  return groups.map((g) => {
    const cards: ConnectorCard[] = [];
    for (const card of g.cards) {
      cards.push(card);
      if (card.variant || card.kind !== 'built-in') continue;
      for (const s of COMPATIBLE_SERVICES) {
        if (s.type !== card.type) continue;
        cards.push({
          ...card,
          variant: s.id,
          name: s.name,
          description: s.description,
          connections: 0,
          presetConfig: s.preset ?? null,
          catalogRef: null,
          compatibleWith: card.name,
        });
      }
    }
    return { name: g.name, cards };
  });
}
