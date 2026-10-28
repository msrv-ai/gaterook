import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CONNECTOR_FORMS } from '../../testing/connector-schemas';
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
  ...['postgres', 'mysql'].map((type) => ({
    name: `${type}: selected database and password`, type,
    config: { connection: { host: 'db.example.test', databases: ['orders'] }, credentials: { user: 'reader' } },
    secrets: { '/credentials/password': password },
  })),
  ...['postgres', 'mysql'].map((type) => ({
    name: `${type}: passwordless authentication with selected database`, type,
    config: { connection: { host: 'db.example.test', databases: ['orders'] }, credentials: { user: 'reader' } },
  })),
  { name: 'MongoDB: selected database without authentication', type: 'mongodb',
    config: { connection: { uri: 'mongodb://mongo.example.test:27017', databases: ['orders'] } } },
  { name: 'MongoDB: selected database and password', type: 'mongodb',
    config: { connection: { uri: 'mongodb://mongo.example.test:27017', databases: ['orders'] }, credentials: { user: 'reader' } },
    secrets: { '/credentials/password': password } },
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
        if (['gcp-logging', 'loki', 'cloudwatch', 'opensearch'].includes(sample.type)) {
          expect(getAt(form.value(), ['backend'])).toBe(sample.type);
        }
        if (sample.type === 'opensearch') expect(getAt(form.value(), ['apiAccess'])).toBe('full');
        expect(Object.keys(form.secretInputs()).sort()).toEqual(Object.keys(sample.secrets ?? {}).sort());
      });
    }
  }

  it('OpenSearch has no legacy log scope or field mapping inputs', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['opensearch'], {
      idPrefix: 'opensearch', current: { url: 'https://search.example.test:9200', auth: { mode: 'none' } },
      injector: TestBed.inject(Injector),
    });
    const legacyFields = ['indices', 'scopeQuery', 'timestampField', 'messageField', 'severityField', 'labelFields', 'advanced'];
    const pointers = form.compiled.all.map((node) => node.pointer);
    for (const field of legacyFields) {
      expect(pointers).not.toContain(`/${field}`);
      expect(form.value()).not.toHaveProperty(field);
    }
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

  for (const type of ['postgres', 'mysql']) {
    it(`${type}: a dynamic Vault role supplies the username`, () => {
      const form = createSchemaForm(CONNECTOR_FORMS[type], {
        idPrefix: 'dynamic', current: { connection: { host: 'db.example.test', databases: ['orders'] } },
        injector: TestBed.inject(Injector),
      });
      form.source.set('dynamic');
      const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
      const role = 'vault://database/creds/orders-reader';
      form.setSecret(passwordNode, role);
      const valid = form.submit();
      expect(valid, form.invalidNodes().map((node) => `${node.pointer}: ${form.error(node)}`).join('\n')).toBe(true);
      expect(getAt(form.value(), ['credentials', 'user'])).toBeNull();
      expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'vault-dynamic', ref: role } });
    });

    for (const role of ['', 'orders-reader']) {
      it(`${type}: dynamic authentication rejects ${role ? 'a bare role name' : 'an empty role'}`, () => {
        const form = createSchemaForm(CONNECTOR_FORMS[type], {
          idPrefix: 'dynamic-required', current: { connection: { host: 'db.example.test', databases: ['orders'] } },
          injector: TestBed.inject(Injector),
        });
        form.source.set('dynamic');
        const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
        form.setSecret(passwordNode, role);
        expect(form.submit()).toBe(false);
        expect(form.invalidNodes().map((node) => node.pointer)).toEqual(['/credentials/password']);
        if (role) expect(form.error(passwordNode)).toContain('vault://');
      });
    }

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
    { name: 'Redis host', type: 'redis', config: {}, pointer: '/connection/host' },
    { name: 'PostgreSQL username', type: 'postgres',
      config: { connection: { host: 'db.example.test', databases: ['orders'] } }, pointer: '/credentials/user' },
    { name: 'SSH password', type: 'ssh', config: { connection: sshConnection, credentials: { method: 'password' } },
      pointer: '/credentials/password' },
    { name: 'Kubernetes server', type: 'kubernetes', config: { auth: 'token' }, pointer: '/server' },
    { name: 'HTTP base URL', type: 'http', config: { auth: { type: 'none' } }, pointer: '/baseUrls' },
    { name: 'AWS region scope', type: 'aws', config: { credentials: { source: 'profile', profile: 'example' } }, pointer: '/regions' },
    { name: 'GCP resource scope', type: 'gcp', config: { credentials: { source: 'adc' } }, pointer: '/scopes' },
    { name: 'GCP Cloud Logging project scope', type: 'gcp-logging',
      config: { credentials: { source: 'adc' } }, pointer: '/resourceNames' },
    { name: 'Loki connector URL', type: 'loki', config: { auth: { mode: 'none' } }, pointer: '/url' },
    { name: 'CloudWatch connector log groups', type: 'cloudwatch',
      config: { region: 'eu-central-1', credentials: { source: 'profile', profile: 'example' } }, pointer: '/logGroups' },
    { name: 'OpenSearch connector basic username', type: 'opensearch',
      config: { url: 'https://search.example.test:9200', auth: { mode: 'basic' } }, pointer: '/auth/username' },
    { name: 'OpenSearch scoped log indices', type: 'logs',
      config: { backend: 'opensearch', url: 'https://search.example.test:9200', apiAccess: 'logs', auth: { mode: 'none' } },
      pointer: '/indices' },
    { name: 'OpenSearch basic username', type: 'logs',
      config: { backend: 'opensearch', url: 'https://search.example.test:9200', auth: { mode: 'basic' } },
      pointer: '/auth/username' },
    { name: 'External MCP stdio command', type: 'mcp-server', config: { transport: 'stdio' }, pointer: '/command' },
    { name: 'External MCP HTTP URL', type: 'mcp-server', config: { transport: 'http' }, pointer: '/url' },
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
    form.source.set('vault');
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    form.setSecret(passwordNode, 'wrong-reference');
    expect(form.submit()).toBe(false);
    expect(form.error(passwordNode)).toContain('vault://');
  });
});
