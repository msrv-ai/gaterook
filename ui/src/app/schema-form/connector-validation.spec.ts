import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CONNECTOR_FORMS } from '../../testing/connector-schemas';
import { ApiError } from '../core/api/api-error';
import { JsonObject } from '../core/api/types';
import { getAt } from './compile';
import { createSchemaForm } from './schema-form';

interface ConnectionCase {
  readonly name: string;
  readonly type: string;
  readonly config: JsonObject;
  readonly secrets?: Readonly<Record<string, string>>;
}

// These exercise form validation only: credential values are test placeholders, never sent to a server.
const password = 'test-only-password';
const accessKeyId = 'AKIAIOSFODNN7EXAMPLE';
const secretKey = 'test-only-secret-access-key';
const serviceAccountKey = JSON.stringify({ type: 'service_account', project_id: 'example-project',
  client_email: 'reader@example-project.iam.gserviceaccount.com', private_key: 'test-only-private-key' });
const sshConnection = { host: 'ssh.example.test', username: 'reader' };
const cases: readonly ConnectionCase[] = [
  ...['postgres', 'mysql', 'sqlserver'].map((type) => ({
    name: `${type}: selected database and password`, type,
    config: { connection: { host: 'db.example.test', databases: ['orders'] }, credentials: { user: 'reader' } },
    secrets: { '/credentials/password': password },
  })),
  ...['postgres', 'mysql'].map((type) => ({
    name: `${type}: passwordless authentication with selected database`, type,
    config: { connection: { host: 'db.example.test', databases: ['orders'] }, credentials: { user: 'reader' } },
  })),
  { name: 'Amazon DynamoDB: access key and listed tables', type: 'dynamodb',
    config: { region: 'eu-central-1', tables: ['orders', 'audit-*'], credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'S3 object storage: Amazon S3 with an access key, a role and listed buckets', type: 's3',
    config: { region: 'eu-central-1', buckets: ['acme-reports', 'acme-logs-*'],
      credentials: { source: 'accessKey', accessKeyId, roleArn: 'arn:aws:iam::123456789012:role/gw-s3',
        externalId: 'gw-ext-0123456789ab' } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'S3 object storage: an S3-compatible endpoint with its own access key', type: 's3',
    config: { endpoint: 'https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com', region: 'auto',
      buckets: ['media'], credentials: { accessKeyId: '0123456789abcdef0123456789abcdef' } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'S3 object storage: MinIO on a private network, path style', type: 's3',
    config: { endpoint: 'http://minio.example.test:9000', region: 'us-east-1', pathStyle: true,
      credentials: { accessKeyId: 'minio-reader' } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'Google BigQuery: sign-in, impersonation and listed datasets', type: 'bigquery',
    config: { project: 'example-project', location: 'EU', datasets: ['sales', 'audit_*'],
      credentials: { source: 'adc', impersonate: 'gw-bigquery@example-project.iam.gserviceaccount.com' } } },
  { name: 'Google BigQuery: service account key', type: 'bigquery',
    config: { project: 'example-project', credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'Google Cloud Spanner: a database with impersonation and a database role', type: 'spanner',
    config: { project: 'example-project', instance: 'orders-prod', database: 'orders',
      credentials: { source: 'adc', impersonate: 'gw-spanner@example-project.iam.gserviceaccount.com' },
      advanced: { databaseRole: 'agent_reader' } } },
  { name: 'Google Cloud Spanner: service account key', type: 'spanner',
    config: { project: 'example-project', instance: 'orders-prod', database: 'orders',
      credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'Snowflake: programmatic access token, role, warehouse and listed tables', type: 'snowflake',
    config: { account: 'acme-analytics', user: 'GW_READER', authentication: { method: 'token' },
      warehouse: 'GW_WH', role: 'GW_READ', database: 'SALES', schema: 'PUBLIC', tables: ['SALES.PUBLIC.*'] },
    secrets: { '/authentication/token': 'test-only-programmatic-access-token' } },
  { name: 'Cloudflare: account, listed zones and API token', type: 'cloudflare',
    config: { accountId: '0123456789abcdef0123456789abcdef', zones: ['example.com', 'example.org'], credentials: {} },
    secrets: { '/credentials/token': 'test-only-cloudflare-api-token' } },
  { name: 'Cloud Firestore: sign-in, impersonation and listed collections', type: 'firestore',
    config: { project: 'example-project', database: '(default)', collections: ['orders', 'users/*/orders'],
      credentials: { source: 'adc', impersonate: 'gw-firestore@example-project.iam.gserviceaccount.com' } } },
  { name: 'Firebase Realtime Database: service account key and listed paths', type: 'firebase-rtdb',
    config: { databaseUrl: 'https://example-default-rtdb.firebaseio.com', paths: ['users', 'public-*'],
      credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'Prometheus: Mimir tenant with basic sign-in', type: 'prometheus',
    config: { url: 'https://mimir.example.com/prometheus', tenant: 'team-a', auth: { mode: 'basic', username: 'reader' } },
    secrets: { '/auth/secret': password } },
  { name: 'Prometheus: a local server without sign-in', type: 'prometheus', config: { url: 'http://127.0.0.1:9090' } },
  { name: 'InfluxDB 3: API token and listed databases', type: 'influxdb',
    config: { url: 'https://influxdb.example.test:8181', version: '3', databases: ['telemetry', 'iot_*'], auth: { method: 'token' } },
    secrets: { '/auth/token': 'test-only-token' } },
  { name: 'InfluxDB 1.x: username and password', type: 'influxdb',
    config: { url: 'https://influxdb.example.test:8086', version: '1', auth: { method: 'password', username: 'reader' } },
    secrets: { '/auth/password': password } },
  { name: 'Qdrant: an API key and listed collections', type: 'qdrant',
    config: { url: 'https://qdrant.example.test:6333', collections: ['docs', 'kb_*'], auth: { method: 'apiKey' } },
    secrets: { '/auth/apiKey': 'test-only-api-key' } },
  { name: 'Qdrant: a local server without a key', type: 'qdrant',
    config: { url: 'http://localhost:6333', auth: { method: 'none' } } },
  { name: 'Weaviate: an API key', type: 'weaviate',
    config: { url: 'https://weaviate.example.test', auth: { method: 'apiKey' } },
    secrets: { '/auth/apiKey': 'test-only-api-key' } },
  { name: 'Chroma Cloud: a token with its tenant and database', type: 'chroma',
    config: { url: 'https://chroma.example.test', tenant: 'tenant-1', database: 'prod', auth: { method: 'token' } },
    secrets: { '/auth/token': 'test-only-token' } },
  { name: 'Milvus: a user and a password', type: 'milvus',
    config: { url: 'https://milvus.example.test:19530', database: 'kb', auth: { method: 'password', username: 'reader' } },
    secrets: { '/auth/password': password } },
  { name: 'Zilliz Cloud: an API key', type: 'milvus',
    config: { url: 'https://cluster.zilliz.example.test', auth: { method: 'token' } },
    secrets: { '/auth/token': 'test-only-token' } },
  { name: 'Pinecone: a project key and listed indexes', type: 'pinecone',
    config: { collections: ['docs', 'support-*'] }, secrets: { '/apiKey': 'test-only-api-key' } },
  { name: 'Meilisearch: a search key and listed indexes', type: 'meilisearch',
    config: { url: 'https://search.example.test', indexes: ['movies'], auth: { method: 'apiKey' } },
    secrets: { '/auth/apiKey': 'test-only-api-key' } },
  { name: 'Typesense: a scoped key', type: 'typesense',
    config: { url: 'https://typesense.example.test' }, secrets: { '/apiKey': 'test-only-api-key' } },
  { name: 'Algolia: an application and its Search API key', type: 'algolia',
    config: { applicationId: 'ABCD1234EF', indexes: ['products'] }, secrets: { '/apiKey': 'test-only-api-key' } },
  { name: 'Azure AI Search: a query key and listed indexes', type: 'azure-ai-search',
    config: { endpoint: 'https://contoso.search.windows.net', indexes: ['hotels'] },
    secrets: { '/apiKey': 'test-only-api-key' } },
  { name: 'Azure AI Search: Microsoft Entra ID with a service principal in Azure Government', type: 'azure-ai-search',
    config: { endpoint: 'https://contoso.search.azure.us', auth: 'entraId', tenantId: 'contoso.onmicrosoft.com',
      indexes: ['hotels'], credentials: { source: 'clientSecret', clientId: '0a1b2c3d-1111-4222-8333-444455556666' } },
    secrets: { '/credentials/clientSecret': 'test-only-client-secret' } },
  { name: 'Azure AI Search: Azure CLI sign-in without a key', type: 'azure-ai-search',
    config: { endpoint: 'https://contoso.search.windows.net', auth: 'entraId', credentials: { source: 'cli' } } },
  { name: 'Amazon Redshift: a provisioned cluster with a role and a database user', type: 'redshift',
    config: { region: 'eu-central-1', target: { kind: 'cluster', cluster: 'acme-analytics' }, database: 'dev',
      databaseAuth: { method: 'dbUser', dbUser: 'gw_reader' },
      credentials: { source: 'accessKey', accessKeyId, roleArn: 'arn:aws:iam::412398765012:role/gw-redshift' } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'Amazon Redshift Serverless: a Secrets Manager sign-in', type: 'redshift',
    config: { region: 'eu-central-1', target: { kind: 'serverless', workgroup: 'acme-wg' }, database: 'dev',
      databaseAuth: { method: 'secret', secretArn: 'arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift/gw-reader-AbCdEf' },
      credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'Databricks SQL: a service principal on one warehouse', type: 'databricks',
    config: { host: 'https://dbc-a1b2c3d4-e5f6.cloud.databricks.com', warehouseId: '1a2b3c4d5e6f7a8b', catalog: 'main', schema: 'sales',
      catalogs: ['main'], schemas: ['sales'], auth: { method: 'oauth', clientId: '6f0e2c9a-1b3d-4e5f-8a7b-9c0d1e2f3a4b' } },
    secrets: { '/auth/clientSecret': 'test-only-client-secret' } },
  { name: 'Databricks SQL: a personal access token', type: 'databricks',
    config: { host: 'https://adb-1234567890123456.7.azuredatabricks.net', warehouseId: '1a2b3c4d5e6f7a8b', auth: { method: 'token' } },
    secrets: { '/auth/token': 'test-only-databricks-token' } },
  { name: 'Trino / Presto: password and listed catalogs', type: 'trino',
    config: { url: 'https://trino.example.test:8443', catalog: 'hive', schema: 'sales', catalogs: ['hive', 'iceberg_*'],
      auth: { mode: 'basic', user: 'reader' } }, secrets: { '/auth/password': password } },
  { name: 'Trino / Presto: JWT', type: 'trino',
    config: { url: 'https://trino.example.test', auth: { mode: 'jwt' } }, secrets: { '/auth/token': password } },
  { name: 'Trino / Presto: Presto on this computer without authentication', type: 'trino',
    config: { url: 'http://localhost:8080', engine: 'presto', auth: { mode: 'none', user: 'dev' } } },
  { name: 'Amazon Athena: access key, workgroup and listed databases', type: 'athena',
    config: { region: 'eu-central-1', workgroup: 'analytics', database: 'sales', databases: ['sales', 'audit_*'],
      outputLocation: 's3://acme-athena-results/gaterook/', credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'Amazon Athena: profile and role', type: 'athena',
    config: { region: 'us-east-1', credentials: { source: 'profile', profile: 'analytics',
      roleArn: 'arn:aws:iam::412398765012:role/gw-athena', externalId: 'gw-ext-0123456789ab' } } },
  { name: 'Azure Cosmos DB: read-only key and listed containers', type: 'cosmosdb',
    config: { endpoint: 'https://example-account.documents.azure.com', databases: ['app'],
      containers: ['orders', 'app/customers'], auth: { method: 'key', keyKind: 'readOnly' } },
    secrets: { '/auth/accountKey': 'test-only-account-key' } },
  { name: 'Azure Cosmos DB: Microsoft Entra ID service principal', type: 'cosmosdb',
    config: { endpoint: 'https://example-account.documents.azure.com', readConsistency: 'Session',
      auth: { method: 'entra', tenantId: '00000000-0000-0000-0000-000000000000',
        clientId: '11111111-1111-1111-1111-111111111111' } },
    secrets: { '/auth/clientSecret': 'test-only-client-secret' } },
  { name: 'Neo4j: password sign-in over TLS', type: 'neo4j',
    config: { uri: 'neo4j+s://graph.example.com:7687', database: 'neo4j',
      authentication: { method: 'password', user: 'gw_reader' } },
    secrets: { '/authentication/password': 'example-only' } },
  { name: 'Neo4j: bearer token', type: 'neo4j',
    config: { uri: 'bolt+s://graph.example.com', authentication: { method: 'token' } },
    secrets: { '/authentication/token': 'example-only-token' } },
  { name: 'Apache Cassandra: contact points, local data center, keyspace list and password', type: 'cassandra',
    config: { contactPoints: ['cassandra-1.example.test:9042', '10.0.4.12'], localDataCenter: 'dc1', keyspace: 'shop',
      keyspaces: ['shop', 'shop_*'], credentials: { user: 'gw_reader' } },
    secrets: { '/credentials/password': password } },
  { name: 'SQLite: a database file on this computer', type: 'sqlite',
    config: { connection: { file: '/home/dev/projects/shop/db/development.sqlite3' } } },
  { name: 'Oracle Database: service name and password', type: 'oracle',
    config: { connection: { host: 'oracle.example.test', database: 'orders.example.test' }, credentials: { user: 'READER' } },
    secrets: { '/credentials/password': password } },
  { name: 'MongoDB: selected database without authentication', type: 'mongodb',
    config: { connection: { host: 'mongo.example.test', databases: ['orders'] } } },
  { name: 'MongoDB: selected database and password', type: 'mongodb',
    config: { connection: { host: 'mongo.example.test', port: 27018, tls: true, databases: ['orders'] }, credentials: { user: 'reader' } },
    secrets: { '/credentials/password': password } },
  { name: 'MongoDB: connection string mode with selected database', type: 'mongodb',
    config: { connection: { mode: 'uri', uri: 'mongodb://mongo.example.test:27017', databases: ['orders'] } } },
  { name: 'Redis: host only, default database and no authentication', type: 'redis',
    config: { connection: { host: 'redis.example.test' } } },
  { name: 'Redis: password without an ACL username', type: 'redis',
    config: { connection: { host: 'redis.example.test' } }, secrets: { '/credentials/password': password } },
  { name: 'Redis: explicit database selection and ACL credentials', type: 'redis',
    config: { connection: { host: 'redis.example.test', databases: [2] }, credentials: { user: 'reader' } },
    secrets: { '/credentials/password': password } },
  { name: 'SSH: Vault CA without a jump host', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'vaultSshCa', vault: { store: 'team-vault' } } } },
  { name: 'SSH: private key without a passphrase or jump host', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'privateKey' } },
    secrets: { '/credentials/privateKey': 'test-only-private-key' } },
  { name: 'SSH: local private key without a passphrase', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/runner' } } },
  { name: 'SSH: encrypted local private key with a certificate', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '~/keys/runner',
      certificate: 'ssh-ed25519-cert-v01@openssh.com AAAA' } },
    secrets: { '/credentials/passphrase': 'test-key-passphrase' } },
  { name: 'SSH: password without a jump host', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'password' } },
    secrets: { '/credentials/password': password } },
  { name: 'SSH log files: password and exact file selection', type: 'ssh-logs',
    config: { connection: sshConnection, credentials: { method: 'password' }, logs: { root: '/var/log', allowedFiles: ['app.log'] } },
    secrets: { '/credentials/password': password } },
  { name: 'SSH log files: local private key without a passphrase', type: 'ssh-logs',
    config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/log-reader' },
      logs: { root: '/var/log', allowedFiles: ['app.log'] } } },
  { name: 'SSH log files: encrypted local private key', type: 'ssh-logs',
    config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '~/keys/log-reader' },
      logs: { root: '/var/log', allowedFiles: ['app.log'] } }, secrets: { '/credentials/passphrase': 'test-key-passphrase' } },
  { name: 'SSH: jump host inherits username and credentials', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'password' }, jump: { host: 'bastion.example.test' } },
    secrets: { '/credentials/password': password } },
  { name: 'SSH: jump host uses its own private key', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'password' },
      jump: { host: 'bastion.example.test', credentials: { method: 'privateKey' } } },
    secrets: { '/credentials/password': password, '/jump/credentials/privateKey': 'test-only-jump-key' } },
  { name: 'SSH: jump host uses its own encrypted local private key', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '~/keys/runner' },
      jump: { host: 'bastion.example.test', credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/bastion' } } },
    secrets: { '/jump/credentials/passphrase': 'test-jump-passphrase' } },
  { name: 'SSH: jump host uses its own password', type: 'ssh',
    config: { connection: sshConnection, credentials: { method: 'privateKey' },
      jump: { host: 'bastion.example.test', credentials: { method: 'password' } } },
    secrets: { '/credentials/privateKey': 'test-only-private-key', '/jump/credentials/password': password } },
  { name: 'Kubernetes: server and bearer token', type: 'kubernetes',
    config: { auth: 'token', server: 'https://k8s.example.test:6443' }, secrets: { '/token': 'test-only-token' } },
  { name: 'Kubernetes: pasted kubeconfig without server or token fields', type: 'kubernetes',
    config: { auth: 'kubeconfig' }, secrets: { '/kubeconfig': 'apiVersion: v1\nkind: Config\ncurrent-context: example\n' } },
  { name: 'Kubernetes: local kubeconfig and context without pasted credentials', type: 'kubernetes',
    config: { auth: 'kubeconfig-file', context: 'example' } },
  { name: 'HTTP: no authentication', type: 'http',
    config: { baseUrls: ['https://api.example.test'], auth: { type: 'none' } } },
  { name: 'HTTP: bearer token without API key or password', type: 'http',
    config: { baseUrls: ['https://api.example.test'], auth: { type: 'bearer' } }, secrets: { '/auth/token': 'test-only-token' } },
  { name: 'HTTP: API key header', type: 'http',
    config: { baseUrls: ['https://api.example.test'], auth: { type: 'apiKeyHeader' } }, secrets: { '/auth/apiKey': 'test-only-api-key' } },
  { name: 'HTTP: API key query parameter in dev', type: 'http',
    config: { baseUrls: ['https://api.example.test'], auth: { type: 'apiKeyQuery' } }, secrets: { '/auth/apiKey': 'test-only-api-key' } },
  { name: 'HTTP: basic authentication', type: 'http',
    config: { baseUrls: ['https://api.example.test'], auth: { type: 'basic', username: 'reader' } },
    secrets: { '/auth/password': password } },
  { name: 'AWS: access key without a profile', type: 'aws',
    config: { regions: ['eu-central-1'], credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'AWS: profile without access key fields', type: 'aws',
    config: { regions: ['eu-central-1'], credentials: { source: 'profile', profile: 'example' } } },
  { name: 'Azure: Azure CLI sign-in without a tenant or secrets', type: 'azure',
    config: { subscriptions: ['0b1f6471-1bf0-4dda-aec3-111122223333'], credentials: { source: 'cli' } } },
  { name: 'Azure: service principal with a client secret in Azure Government', type: 'azure',
    config: { cloud: 'AzureUSGovernment', tenantId: 'contoso.onmicrosoft.com',
      subscriptions: ['0b1f6471-1bf0-4dda-aec3-111122223333'],
      credentials: { source: 'clientSecret', clientId: '0a1b2c3d-1111-4222-8333-444455556666' } },
    secrets: { '/credentials/clientSecret': 'test-only-client-secret' } },
  { name: 'Azure Monitor Logs: device sign-in and a workspace', type: 'azure-monitor',
    config: { tenantId: 'contoso.onmicrosoft.com', workspaces: ['5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b'],
      credentials: { source: 'deviceCode' } } },
  { name: 'GCP: application default credentials without a key', type: 'gcp',
    config: { scopes: ['projects/example-project'], credentials: { source: 'adc' } } },
  { name: 'GCP: service account key', type: 'gcp',
    config: { scopes: ['projects/example-project'], credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'GCP Cloud Logging: application default credentials and fixed backend', type: 'gcp-logging',
    config: { resourceNames: ['projects/example-project'], credentials: { source: 'adc' } } },
  { name: 'GCP Cloud Logging: service account key and fixed backend', type: 'gcp-logging',
    config: { resourceNames: ['projects/example-project'], credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'Loki connector: no authentication and fixed backend', type: 'loki',
    config: { url: 'https://loki.example.test', auth: { mode: 'none' } } },
  { name: 'Loki connector: bearer token and fixed backend', type: 'loki',
    config: { url: 'https://loki.example.test', auth: { mode: 'bearer' } },
    secrets: { '/auth/secret': 'test-only-token' } },
  { name: 'CloudWatch connector: access key and fixed backend', type: 'cloudwatch',
    config: { region: 'eu-central-1', logGroups: ['/example/app'], credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'CloudWatch connector: profile and fixed backend', type: 'cloudwatch',
    config: { region: 'eu-central-1', logGroups: ['/example/app'], credentials: { source: 'profile', profile: 'example' } } },
  { name: 'OpenSearch connector: fixed full API access without log inputs', type: 'opensearch',
    config: { url: 'https://search.example.test:9200', auth: { mode: 'none' } } },
  { name: 'OpenSearch connector: basic authentication without log inputs', type: 'opensearch',
    config: { url: 'https://search.example.test:9200', auth: { mode: 'basic', username: 'reader' } },
    secrets: { '/auth/secret': password } },
  { name: 'Elasticsearch connector: fixed full API access without log inputs', type: 'elasticsearch',
    config: { url: 'https://elastic.example.test:9200', auth: { mode: 'none' } } },
  { name: 'Elasticsearch connector: basic authentication', type: 'elasticsearch',
    config: { url: 'https://elastic.example.test:9200', auth: { mode: 'basic', username: 'reader' } },
    secrets: { '/auth/secret': password } },
  { name: 'Elasticsearch connector: encoded API key', type: 'elasticsearch',
    config: { url: 'https://elastic.example.test:9200', auth: { mode: 'apiKey' } },
    secrets: { '/auth/secret': 'aWQ6dGVzdC1vbmx5LWtleQ==' } },
  { name: 'Elasticsearch connector: bearer token', type: 'elasticsearch',
    config: { url: 'https://elastic.example.test:9200', auth: { mode: 'bearer' } },
    secrets: { '/auth/secret': 'test-only-token' } },
  { name: 'Loki: no authentication', type: 'logs',
    config: { backend: 'loki', url: 'https://loki.example.test', auth: { mode: 'none' } } },
  { name: 'Loki: bearer token without a username', type: 'logs',
    config: { backend: 'loki', url: 'https://loki.example.test', auth: { mode: 'bearer' } },
    secrets: { '/auth/secret': 'test-only-token' } },
  { name: 'Loki: basic authentication', type: 'logs',
    config: { backend: 'loki', url: 'https://loki.example.test', auth: { mode: 'basic', username: 'reader' } },
    secrets: { '/auth/secret': password } },
  { name: 'GCP logs: application default credentials', type: 'logs',
    config: { backend: 'gcp-logging', resourceNames: ['projects/example-project'], credentials: { source: 'adc' } } },
  { name: 'GCP logs: service account key', type: 'logs',
    config: { backend: 'gcp-logging', resourceNames: ['projects/example-project'], credentials: { source: 'serviceAccountKey' } },
    secrets: { '/credentials/serviceAccountKey': serviceAccountKey } },
  { name: 'CloudWatch: access key without a profile', type: 'logs',
    config: { backend: 'cloudwatch', region: 'eu-central-1', logGroups: ['/example/app'], credentials: { source: 'accessKey', accessKeyId } },
    secrets: { '/credentials/secretAccessKey': secretKey } },
  { name: 'CloudWatch: profile without access key fields', type: 'logs',
    config: { backend: 'cloudwatch', region: 'eu-central-1', logGroups: ['/example/app'], credentials: { source: 'profile', profile: 'example' } } },
  { name: 'OpenSearch: full API with no authentication or log indices', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'none' } } },
  { name: 'OpenSearch: basic authentication without AWS fields', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'basic', username: 'reader' } },
    secrets: { '/auth/secret': password } },
  { name: 'OpenSearch: API key', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'apiKey' } },
    secrets: { '/auth/secret': 'os_test-only-api-key' } },
  { name: 'OpenSearch: bearer token', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'bearer' } },
    secrets: { '/auth/secret': 'test-only-token' } },
  { name: 'OpenSearch: AWS profile without basic or access key credentials', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'awsSigV4', awsRegion: 'eu-central-1',
      awsCredentials: { source: 'profile', profile: 'example' } } } },
  { name: 'OpenSearch: AWS access key without profile or basic credentials', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'awsSigV4', awsRegion: 'eu-central-1',
      awsCredentials: { source: 'accessKey', accessKeyId } } },
    secrets: { '/auth/awsCredentials/secretAccessKey': secretKey } },
  { name: 'OpenSearch: scoped logs with required index scope', type: 'logs',
    config: { backend: 'opensearch', url: 'https://search.example.test:9200', apiAccess: 'logs', indices: ['logs-*'], auth: { mode: 'none' } } },
  { name: 'External MCP: stdio without an HTTP URL', type: 'mcp-server',
    config: { transport: 'stdio', command: ['node', '/example/mcp-server.js'] } },
  { name: 'External MCP: HTTP without a local command', type: 'mcp-server',
    config: { transport: 'http', url: 'https://mcp.example.test/mcp' } },
  { name: 'External MCP: HTTP with an OAuth sign-in', type: 'mcp-server',
    config: { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { type: 'oauth' } } },
  { name: 'External MCP: HTTP with its own OAuth app', type: 'mcp-server',
    config: { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { type: 'oauth', clientId: 'gaterook-example' } },
    secrets: { '/auth/clientSecret': 'test-only-client-secret' } },
  { name: 'External MCP: HTTP with Google Cloud credentials', type: 'mcp-server',
    config: { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { type: 'google', credentials: { source: 'adc' } } } },
  { name: 'External MCP: HTTP with an AWS access key', type: 'mcp-server',
    config: { transport: 'http', url: 'https://aws-mcp.us-east-1.api.aws/mcp',
      auth: { type: 'aws', credentials: { source: 'accessKey', accessKeyId } } },
    secrets: { '/auth/credentials/secretAccessKey': secretKey } },
];

describe('schema-form: minimal active connector configurations', () => {
  it('covers every built-in connector form', () => {
    expect([...new Set(cases.map((sample) => sample.type))].sort()).toEqual(Object.keys(CONNECTOR_FORMS).sort());
  });

  for (const source of ['local', 'vault']) {
    for (const sample of cases) {
      it(`${sample.name} (${source} secrets)`, () => {
        const form = createSchemaForm(CONNECTOR_FORMS[sample.type], {
          idPrefix: 'validation', current: sample.config, env: 'dev', injector: TestBed.inject(Injector),
        });
        form.source.set(source);
        for (const [pointer, value] of Object.entries(sample.secrets ?? {})) {
          const node = form.compiled.all.find((candidate) => candidate.pointer === pointer
            && candidate.kind === 'secret' && form.visible(candidate));
          expect(node, `${sample.name}: active secret ${pointer}`).toBeDefined();
          form.setSecret(node!, source === 'local' ? value : `vault://example/connector${pointer}#value`);
        }
        for (const node of form.compiled.all.filter((candidate) => candidate.kind !== 'object'
          && candidate.kind !== 'secret' && form.visible(candidate))) {
          expect(typeof form.field(node), `${sample.name}: active model path ${node.pointer}`).toBe('function');
        }

        const valid = form.submit();
        const errors = form.invalidNodes().map((node) => `${node.pointer}: ${form.error(node)}`);
        expect(valid, errors.join('\n')).toBe(true);
        expect(errors).toEqual([]);
        expect(form.value()).toMatchObject(sample.config);
        if (['gcp-logging', 'loki', 'cloudwatch', 'opensearch', 'elasticsearch'].includes(sample.type)) {
          expect(getAt(form.value(), ['backend'])).toBe(sample.type);
        }
        if (['opensearch', 'elasticsearch'].includes(sample.type)) expect(getAt(form.value(), ['apiAccess'])).toBe('full');
        expect(Object.keys(form.secretInputs()).sort()).toEqual(Object.keys(sample.secrets ?? {}).sort());
      });
    }
  }

  it('Oracle requires a service name and keeps TLS verification enabled by default', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['oracle'], {
      idPrefix: 'oracle', current: { connection: { host: 'oracle.example.test' }, credentials: { user: 'READER' } },
      injector: TestBed.inject(Injector),
    });
    const node = (pointer: string) => form.compiled.all.find((candidate) => candidate.pointer === pointer)!;
    form.setSecret(node('/credentials/password'), password);
    expect(form.submit()).toBe(false);
    expect(form.invalidNodes().map((candidate) => candidate.pointer)).toEqual(['/connection/database']);
    form.set(node('/connection/database'), 'orders.example.test');
    expect(form.submit()).toBe(true);
    expect(getAt(form.value(), ['connection', 'port'])).toBe(1521);
    expect(getAt(form.value(), ['connection', 'sslMode'])).toBe('verify-full');
    expect(getAt(form.value(), ['connection', 'databases'])).toBeNull();
  });

  it.each(['opensearch', 'elasticsearch'])('%s has no legacy log scope or field mapping inputs', (type) => {
    const form = createSchemaForm(CONNECTOR_FORMS[type], {
      idPrefix: type, current: { url: 'https://search.example.test:9200', auth: { mode: 'none' } },
      injector: TestBed.inject(Injector),
    });
    const legacyFields = ['indices', 'scopeQuery', 'timestampField', 'messageField', 'severityField', 'labelFields', 'advanced'];
    const pointers = form.compiled.all.map((node) => node.pointer);
    for (const field of legacyFields) {
      expect(pointers).not.toContain(`/${field}`);
      expect(form.value()).not.toHaveProperty(field);
    }
  });

  it('SSH changes between pasted keys, local files, and passwords without submitting inactive credentials', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['ssh'], {
      idPrefix: 'ssh-method', current: { connection: sshConnection, credentials: { method: 'privateKey' } },
      injector: TestBed.inject(Injector),
    });
    const active = (pointer: string) => form.compiled.all.find((node) => node.pointer === pointer && form.visible(node))!;
    form.setSecret(active('/credentials/privateKey'), 'test-only-pasted-key');
    form.setSecret(active('/credentials/passphrase'), 'test-only-key-passphrase');
    form.set(active('/credentials/certificate'), 'ssh-ed25519-cert-v01@openssh.com AAAA');
    expect(form.submit()).toBe(true);

    form.set(active('/credentials/method'), 'privateKeyFile');
    expect(active('/credentials/privateKey')).toBeUndefined();
    expect(form.submit()).toBe(false);
    expect(form.invalidNodes().map((node) => node.pointer)).toEqual(['/credentials/privateKeyPath']);
    form.set(active('/credentials/privateKeyPath'), '~/keys/runner');
    expect(form.submit()).toBe(true);
    expect(form.value()['credentials']).toEqual({ method: 'privateKeyFile', privateKeyPath: '~/keys/runner',
      certificate: 'ssh-ed25519-cert-v01@openssh.com AAAA' });
    expect(form.secretInputs()).toEqual({ '/credentials/passphrase': { source: 'local', value: 'test-only-key-passphrase' } });

    form.set(active('/credentials/method'), 'password');
    form.setSecret(active('/credentials/password'), password);
    expect(form.submit()).toBe(true);
    expect(form.value()['credentials']).toEqual({ method: 'password' });
    expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'local', value: password } });

    form.set(active('/credentials/method'), 'privateKey');
    expect(form.submit()).toBe(true);
    expect(form.value()['credentials']).toEqual({ method: 'privateKey', certificate: 'ssh-ed25519-cert-v01@openssh.com AAAA' });
    expect(form.secretInputs()).toEqual({
      '/credentials/privateKey': { source: 'local', value: 'test-only-pasted-key' },
      '/credentials/passphrase': { source: 'local', value: 'test-only-key-passphrase' },
    });
  });

  it('External MCP: HTTP without a sign-in sends no auth, and a sign-in filled in needs its type', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['mcp-server'], {
      idPrefix: 'mcp-auth', current: { transport: 'http', url: 'https://mcp.example.test/mcp' }, injector: TestBed.inject(Injector),
    });
    const active = (pointer: string) => form.compiled.all.find((node) => node.pointer === pointer && form.visible(node))!;
    expect(form.submit()).toBe(true);
    expect(form.value()).not.toHaveProperty('auth');

    form.setSecret(active('/auth/clientSecret'), 'test-only-client-secret');
    expect(form.submit()).toBe(false);
    expect(form.invalidNodes().map((node) => node.pointer)).toEqual(['/auth/type']);
    form.set(active('/auth/type'), 'oauth');
    expect(form.submit()).toBe(true);
    expect(form.value()['auth']).toMatchObject({ type: 'oauth', callbackHost: '127.0.0.1', callbackPath: '/callback' });
    expect(form.secretInputs()).toEqual({ '/auth/clientSecret': { source: 'local', value: 'test-only-client-secret' } });

    form.set(active('/auth/type'), null);
    form.setSecret(active('/auth/clientSecret'), '');
    expect(form.submit()).toBe(true);
    expect(form.value()).not.toHaveProperty('auth');
    expect(form.secretInputs()).toEqual({});
  });

  it('External MCP: two sign-ins answered by leaving the sign-in out go back to the core', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['mcp-server'], {
      idPrefix: 'mcp-twice', pointerPrefix: '/config', injector: TestBed.inject(Injector),
      current: { transport: 'http', url: 'https://mcp.example.test/mcp', headers: [{ name: 'Authorization' }], auth: { type: 'oauth' } },
    });
    const active = (pointer: string) => form.compiled.all.find((node) => node.pointer === pointer && form.visible(node))!;
    const twice = 'The OAuth sign-in sends the Authorization header. Remove the Authorization header, or sign in another way.';
    form.applyServerErrors(new ApiError('unprocessable', 422, { errors: [{ field: '/config/headers', message: twice }] }));
    expect(form.error(active('/headers'))).toBe(twice);

    form.set(active('/auth/type'), null);
    expect(form.submit()).toBe(true);
    expect(form.value()).not.toHaveProperty('auth');
    expect(form.value()['headers']).toMatchObject([{ name: 'Authorization' }]);
  });

  it('External MCP: a stored sign-in keeps its settings and a stdio server never sends one', () => {
    const stored = { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { type: 'oauth', optional: true } };
    const http = createSchemaForm(CONNECTOR_FORMS['mcp-server'], { idPrefix: 'mcp-stored', current: stored, injector: TestBed.inject(Injector) });
    expect(http.submit()).toBe(true);
    expect(http.value()['auth']).toMatchObject({ type: 'oauth', optional: true });

    const stdio = createSchemaForm(CONNECTOR_FORMS['mcp-server'], {
      idPrefix: 'mcp-stdio', current: { transport: 'stdio', command: ['node', '/example/mcp-server.js'] }, injector: TestBed.inject(Injector),
    });
    expect(stdio.submit()).toBe(true);
    expect(stdio.value()).not.toHaveProperty('auth');
  });

  it('Azure AI Search sends either the API key or the Entra ID sign-in', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['azure-ai-search'], {
      idPrefix: 'azure-search', current: { endpoint: 'https://contoso.search.windows.net' }, injector: TestBed.inject(Injector),
    });
    const active = (pointer: string) => form.compiled.all.find((node) => node.pointer === pointer && form.visible(node));
    expect(active('/apiKey')).toBeDefined();
    expect(active('/credentials/source')).toBeUndefined();

    form.set(active('/auth')!, 'entraId');
    expect(active('/apiKey')).toBeUndefined();
    expect(active('/credentials/source')).toBeDefined();
    expect(form.submit()).toBe(true);
    expect(form.value()).toMatchObject({ auth: 'entraId', credentials: { source: 'cli' } });
    expect(form.secretInputs()).toEqual({});

    form.set(active('/auth')!, 'apiKey');
    expect(form.submit()).toBe(false);
    expect(form.invalidNodes().map((node) => node.pointer)).toEqual(['/apiKey']);
    expect(form.value()).not.toHaveProperty('credentials');
  });

  it('Redis keeps database zero when the user only enters the host', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'redis', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    expect(form.submit()).toBe(true);
    expect(getAt(form.value(), ['connection', 'database'])).toBe(0);
    expect(getAt(form.value(), ['connection', 'databases'])).toBeNull();
    expect(form.secretInputs()).toEqual({});
  });

  for (const type of ['postgres', 'mysql', 'sqlserver']) {
    it.each([
      { state: 'short-lived', source: 'vault' },
      { state: 'not-connected', source: 'vault-dynamic' },
    ])(`${type}: a saved $state dynamic credential needs no username or replacement`, ({ state, source }) => {
      const form = createSchemaForm(CONNECTOR_FORMS[type], {
        idPrefix: 'dynamic-stored', current: { connection: { host: 'db.example.test', databases: ['orders'] } },
        stored: [{ field: '/credentials/password', secretId: 'stored-dynamic-credential', name: 'orders-reader',
          state, source, version: null }],
        injector: TestBed.inject(Injector),
      });
      expect(form.source()).toBe('dynamic');
      const valid = form.submit();
      expect(valid, form.invalidNodes().map((node) => `${node.pointer}: ${form.error(node)}`).join('\n')).toBe(true);
      expect(form.secretInputs()).toEqual({});
      expect(getAt(form.value(), ['credentials', 'user'])).toBeNull();
    });
  }

  const missingRequired: readonly (ConnectionCase & { readonly pointer: string })[] = [
    { name: 'MongoDB host', type: 'mongodb', config: {}, pointer: '/connection/host' },
    { name: 'MongoDB connection string', type: 'mongodb', config: { connection: { mode: 'uri' } }, pointer: '/connection/uri' },
    { name: 'Redis host', type: 'redis', config: {}, pointer: '/connection/host' },
    { name: 'PostgreSQL username', type: 'postgres',
      config: { connection: { host: 'db.example.test', databases: ['orders'] } }, pointer: '/credentials/user' },
    { name: 'SQL Server username', type: 'sqlserver',
      config: { connection: { host: 'db.example.test', databases: ['orders'] } }, pointer: '/credentials/user' },
    { name: 'SQL Server password', type: 'sqlserver',
      config: { connection: { host: 'db.example.test', databases: ['orders'] }, credentials: { user: 'reader' } },
      pointer: '/credentials/password' },
    { name: 'SSH password', type: 'ssh', config: { connection: sshConnection, credentials: { method: 'password' } },
      pointer: '/credentials/password' },
    { name: 'SSH private key file', type: 'ssh', config: { connection: sshConnection, credentials: { method: 'privateKeyFile' } },
      pointer: '/credentials/privateKeyPath' },
    { name: 'SSH jump host private key file', type: 'ssh',
      config: { connection: sshConnection, credentials: { method: 'privateKeyFile', privateKeyPath: '~/keys/runner' },
        jump: { host: 'bastion.example.test', credentials: { method: 'privateKeyFile' } } },
      pointer: '/jump/credentials/privateKeyPath' },
    { name: 'Kubernetes server', type: 'kubernetes', config: { auth: 'token' }, pointer: '/server' },
    { name: 'HTTP base URL', type: 'http', config: { auth: { type: 'none' } }, pointer: '/baseUrls' },
    { name: 'AWS region scope', type: 'aws', config: { credentials: { source: 'profile', profile: 'example' } }, pointer: '/regions' },
    { name: 'GCP resource scope', type: 'gcp', config: { credentials: { source: 'adc' } }, pointer: '/scopes' },
    { name: 'Prometheus URL', type: 'prometheus', config: { auth: { mode: 'none' } }, pointer: '/url' },
    { name: 'InfluxDB URL', type: 'influxdb', config: { version: '3', auth: { method: 'token' } }, pointer: '/url' },
    { name: 'Qdrant URL', type: 'qdrant', config: { auth: { method: 'apiKey' } }, pointer: '/url' },
    { name: 'Milvus username', type: 'milvus', config: { url: 'https://milvus.example.test', auth: { method: 'password' } },
      pointer: '/auth/username' },
    { name: 'Algolia application ID', type: 'algolia', config: {}, pointer: '/applicationId' },
    { name: 'Azure AI Search endpoint', type: 'azure-ai-search', config: {}, pointer: '/endpoint' },
    { name: 'Azure AI Search API key', type: 'azure-ai-search', config: { endpoint: 'https://contoso.search.windows.net' },
      pointer: '/apiKey' },
    { name: 'Azure AI Search Entra ID client secret', type: 'azure-ai-search',
      config: { endpoint: 'https://contoso.search.windows.net', auth: 'entraId', tenantId: 'contoso.onmicrosoft.com',
        credentials: { source: 'clientSecret', clientId: '0a1b2c3d-1111-4222-8333-444455556666' } },
      pointer: '/credentials/clientSecret' },
    { name: 'Redshift database', type: 'redshift', config: { region: 'eu-central-1', target: { kind: 'serverless', workgroup: 'acme-wg' }, credentials: { source: 'accessKey', accessKeyId } },
      pointer: '/database' },
    { name: 'Databricks workspace', type: 'databricks', config: { warehouseId: '1a2b3c4d5e6f7a8b', auth: { method: 'token' } }, pointer: '/host' },
    { name: 'Trino coordinator URL', type: 'trino', config: { auth: { mode: 'none', user: 'dev' } }, pointer: '/url' },
    { name: 'Athena region', type: 'athena', config: { credentials: { source: 'profile', profile: 'analytics' } }, pointer: '/region' },
    { name: 'Cosmos DB endpoint', type: 'cosmosdb', config: { auth: { method: 'key', keyKind: 'readOnly' } }, pointer: '/endpoint' },
    { name: 'Neo4j URI', type: 'neo4j', config: { authentication: { method: 'password', user: 'gw_reader' } }, pointer: '/uri' },
    { name: 'Cassandra contact points', type: 'cassandra', config: { localDataCenter: 'dc1' }, pointer: '/contactPoints' },
    { name: 'BigQuery project', type: 'bigquery', config: { credentials: { source: 'adc' } }, pointer: '/project' },
    { name: 'Spanner instance', type: 'spanner', config: { project: 'example-project', database: 'orders',
      credentials: { source: 'adc' } }, pointer: '/instance' },
    { name: 'Snowflake account', type: 'snowflake', config: { user: 'GW_READER', authentication: { method: 'token' } },
      pointer: '/account' },
    { name: 'Cloudflare account ID', type: 'cloudflare', config: { credentials: {} }, pointer: '/accountId' },
    { name: 'Firestore project', type: 'firestore', config: { credentials: { source: 'adc' } }, pointer: '/project' },
    { name: 'Realtime Database URL', type: 'firebase-rtdb', config: { credentials: { source: 'adc' } },
      pointer: '/databaseUrl' },
    { name: 'GCP Cloud Logging project scope', type: 'gcp-logging',
      config: { credentials: { source: 'adc' } }, pointer: '/resourceNames' },
    { name: 'Loki connector URL', type: 'loki', config: { auth: { mode: 'none' } }, pointer: '/url' },
    { name: 'CloudWatch connector log groups', type: 'cloudwatch',
      config: { region: 'eu-central-1', credentials: { source: 'profile', profile: 'example' } }, pointer: '/logGroups' },
    { name: 'OpenSearch connector basic username', type: 'opensearch',
      config: { url: 'https://search.example.test:9200', auth: { mode: 'basic' } }, pointer: '/auth/username' },
    { name: 'Elasticsearch connector URL', type: 'elasticsearch',
      config: { auth: { mode: 'none' } }, pointer: '/url' },
    { name: 'Elasticsearch connector basic username', type: 'elasticsearch',
      config: { url: 'https://elastic.example.test:9200', auth: { mode: 'basic' } }, pointer: '/auth/username' },
    { name: 'Elasticsearch connector API key', type: 'elasticsearch',
      config: { url: 'https://elastic.example.test:9200', auth: { mode: 'apiKey' } }, pointer: '/auth/secret' },
    { name: 'OpenSearch scoped log indices', type: 'logs',
      config: { backend: 'opensearch', url: 'https://search.example.test:9200', apiAccess: 'logs', auth: { mode: 'none' } },
      pointer: '/indices' },
    { name: 'OpenSearch basic username', type: 'logs',
      config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'basic' } },
      pointer: '/auth/username' },
    { name: 'External MCP stdio command', type: 'mcp-server', config: { transport: 'stdio' }, pointer: '/command' },
    { name: 'External MCP HTTP URL', type: 'mcp-server', config: { transport: 'http' }, pointer: '/url' },
    { name: 'External MCP sign-in type once a sign-in setting is filled in', type: 'mcp-server',
      config: { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { clientId: 'gaterook-example' } }, pointer: '/auth/type' },
    { name: 'External MCP sign-in type once a sign-in default is changed', type: 'mcp-server',
      config: { transport: 'http', url: 'https://mcp.example.test/mcp', auth: { callbackHost: 'localhost' } }, pointer: '/auth/type' },
  ];

  for (const sample of missingRequired) {
    it(`still requires ${sample.name}`, () => {
      const form = createSchemaForm(CONNECTOR_FORMS[sample.type], {
        idPrefix: 'required', current: sample.config, injector: TestBed.inject(Injector),
      });
      expect(form.submit()).toBe(false);
      expect(form.invalidNodes().map((node) => node.pointer)).toContain(sample.pointer);
    });
  }

  it('validates an optional secret reference once it is filled', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'reference', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    form.setSecret(passwordNode, 'gcp-sm://');
    expect(form.submit()).toBe(false);
    expect(form.error(passwordNode)).toContain('after gcp-sm://');
  });
});

describe("schema-form: a value that starts with a store's scheme is read from that store", () => {
  const redis = () => createSchemaForm(CONNECTOR_FORMS['redis'], {
    idPrefix: 'reference', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
  });
  const at = (form: ReturnType<typeof redis>, pointer: string) => form.compiled.all.find((node) => node.pointer === pointer)!;
  const vaults = [
    { kind: 'vault', store: 'vault-dev', title: 'Vault · vault-dev', detail: null, available: true, reason: null },
    { kind: 'vault', store: 'vault-stage', title: 'Vault · vault-stage', detail: null, available: true, reason: null },
  ];

  for (const ref of [
    'gcp-sm://projects/example-dev/secrets/api-key',
    'vault://kv/dev/orders#password',
    'aws-sm://eu-central-1/dev/orders#password',
    'aws-sm://arn:aws:secretsmanager:eu-central-1:123456789012:secret:orders-AbCdEf',
  ]) {
    it(`sends ${ref} as the password's reference`, () => {
      const form = redis();
      const password = at(form, '/credentials/password');
      form.setSecret(password, `  ${ref}\n`);

      expect(form.submit()).toBe(true);
      expect(form.fieldReference(password)).toBe(ref);
      expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'reference', ref } });
    });
  }

  it('names nothing with a scheme alone, and says what goes after it', () => {
    const form = redis();
    const password = at(form, '/credentials/password');
    form.setSecret(password, 'vault://');

    expect(form.submit()).toBe(false);
    expect(form.error(password)).toContain('after vault://');
    expect(form.secretInputs()).toEqual({});
  });

  it("binds a reference to the store picked in the field's menu; Text empties it again", () => {
    const form = redis();
    form.stores.set(vaults);
    const password = at(form, '/credentials/password');
    form.chooseSource(password, vaults[1]);
    expect(form.fieldText(password)).toBe('vault://');
    form.setSecret(password, 'vault://kv/dev/orders#password');

    expect(form.secretInputs()).toEqual({ '/credentials/password': {
      source: 'reference', ref: 'vault://kv/dev/orders#password', store: 'vault-stage' } });
    form.chooseSource(password, null);
    expect(form.fieldText(password)).toBe('');
    expect(form.secretInputs()).toEqual({});
  });

  it('never keeps a typed secret in a reference: picking a store empties the value', () => {
    const form = redis();
    form.stores.set(vaults);
    const password = at(form, '/credentials/password');
    form.setSecret(password, 'typed-secret-1');
    form.chooseSource(password, vaults[0]);
    expect(form.fieldText(password)).toBe('vault://');
  });

  it("reads a login's user from a store and leaves it out of the config", () => {
    const form = redis();
    form.stores.set(vaults);
    const user = at(form, '/credentials/user');
    expect(form.credential(user)).toBe(true);
    form.set(user, 'app');
    form.chooseSource(user, vaults[0]);
    // A text field keeps what was typed after the scheme picked for it.
    expect(form.fieldText(user)).toBe('vault://app');
    form.set(user, 'vault://kv/dev/orders#username');

    expect(form.submit()).toBe(true);
    expect(form.value()['credentials'] ?? {}).not.toHaveProperty('user');
    expect(form.secretInputs()).toEqual({ '/credentials/user': {
      source: 'reference', ref: 'vault://kv/dev/orders#username', store: 'vault-dev' } });
  });

  it('keeps the host typed: where the credentials go is never read from a store', () => {
    const form = redis();
    const host = at(form, '/connection/host');
    expect(form.textReferable(host)).toBe(false);
    form.set(host, 'vault://kv/dev/orders#host');

    expect(form.submit()).toBe(false);
    expect(form.error(host)).toContain('never read from a store');
    expect(form.secretInputs()).toEqual({});
  });

  it('preserves local secrets exactly, including whitespace and text that only mentions a scheme', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'local', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    const value = '  test-only gcp-sm://password\n';
    form.setSecret(passwordNode, value);

    expect(form.submit()).toBe(true);
    expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'local', value } });
  });
});
