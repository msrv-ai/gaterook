import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { JsonObject } from '../../../core/api/types';
import { compileSchema } from '../../../schema-form/compile';
import { proposeName } from './proposed-name';

/** The Name a new connection gets from what it points to, before the person types one. */
function propose(type: string, model: JsonObject, databases: readonly (string | number)[] = [], taken: string[] = []) {
  return proposeName(compileSchema(CONNECTOR_FORMS[type]).all, model, type, databases, taken);
}

describe('proposeName', () => {
  it('names a connection by its one chosen database, then by the database field, then by the host', () => {
    expect(propose('postgres', { connection: { host: 'db.internal' } }, ['orders'])).toEqual(
      { name: 'orders', from: 'the database' });
    expect(propose('postgres', { connection: { host: 'db.internal' } }, ['orders', 'billing'])?.name).toBe('db');
    expect(propose('postgres', { connection: { host: 'orders-db.internal.example' } })).toEqual(
      { name: 'orders-db', from: 'the host' });
    expect(propose('mysql', { connection: { host: '127.0.0.1' } })).toEqual(
      { name: 'mysql-local', from: 'this computer' });
  });

  it('takes the cluster of a kubeconfig context, a project, a keyspace or an instance', () => {
    expect(propose('kubernetes', { auth: 'kubeconfig-file', context: 'gke_acme_europe-west1_prod-cluster' })?.name)
      .toBe('prod-cluster');
    expect(propose('kubernetes', { auth: 'kubeconfig-file',
      context: 'arn:aws:eks:eu-west-1:123456789012:cluster/payments' })?.name).toBe('payments');
    expect(propose('bigquery', { project: 'acme-analytics' })).toEqual(
      { name: 'acme-analytics', from: 'the project' });
    expect(propose('cassandra', { keyspace: 'telemetry', contactPoints: ['10.0.0.5'] })?.name).toBe('telemetry');
    expect(propose('spanner', { project: 'acme', instance: 'main-instance', database: 'ledger' })?.name).toBe('ledger');
  });

  it('names a database file by its name without the extension', () => {
    expect(propose('sqlite', { connection: { file: '~/projects/shop/inventory.sqlite3' } })).toEqual(
      { name: 'inventory', from: 'the database file' });
  });

  it('uses the host label that names the resource, not the door of a service', () => {
    expect(propose('mcp-server', { transport: 'http', url: 'https://mcp.notion.example/mcp' })?.name).toBe('notion');
    expect(propose('qdrant', { url: 'https://vectors.eu-west.cloud.qdrant.example:6333' })?.name).toBe('vectors');
  });

  it('proposes nothing from a store reference, an address alone, a UUID or a default', () => {
    expect(propose('postgres', { connection: { host: 'vault://kv/data/db#host' } })).toBeNull();
    expect(propose('redis', { connection: { host: '10.1.2.3' } })).toBeNull();
    expect(propose('aws', { credentials: { source: 'profile', profile: 'default' } })).toBeNull();
    expect(propose('neo4j', { uri: 'neo4j://10.0.0.7:7687', database: 'neo4j' })).toBeNull();
    expect(propose('firestore', { project: '7b0d0f62-3a51-4e0e-9a0e-3c1b8f1f4a2e' })).toBeNull();
  });

  it('never proposes a name another connection has', () => {
    expect(propose('mysql', { connection: { host: 'localhost' } }, [], ['mysql-local', 'mysql-local-2'])?.name)
      .toBe('mysql-local-3');
  });
});
