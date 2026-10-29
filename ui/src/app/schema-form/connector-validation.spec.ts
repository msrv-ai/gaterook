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
      const role = 'database/creds/orders-reader';
      form.setSecret(passwordNode, role);
      const valid = form.submit();
      expect(valid, form.invalidNodes().map((node) => `${node.pointer}: ${form.error(node)}`).join('\n')).toBe(true);
      expect(getAt(form.value(), ['credentials', 'user'])).toBeNull();
      expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'vault-dynamic', ref: `vault://${role}` } });
    });

    for (const role of ['', 'gcp-sm://projects/example-dev/secrets/orders-reader']) {
      it(`${type}: dynamic authentication rejects ${role ? 'a reference to another store' : 'an empty role'}`, () => {
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
    { name: 'MongoDB host', type: 'mongodb', config: {}, pointer: '/connection/host' },
    { name: 'MongoDB connection string', type: 'mongodb', config: { connection: { mode: 'uri' } }, pointer: '/connection/uri' },
    { name: 'Redis host', type: 'redis', config: {}, pointer: '/connection/host' },
    { name: 'PostgreSQL username', type: 'postgres',
      config: { connection: { host: 'db.example.test', databases: ['orders'] } }, pointer: '/credentials/user' },
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
    form.setSecret(passwordNode, 'gcp-sm://projects/example-dev/secrets/api-key');
    expect(form.submit()).toBe(false);
    expect(form.error(passwordNode)).toContain('vault://');
  });
});

describe('schema-form: secret references from the selected source', () => {
  const references = [
    { source: 'gcp-sm', path: 'projects/example-dev/secrets/api-key',
      ref: 'gcp-sm://projects/example-dev/secrets/api-key' },
    { source: 'vault', path: 'kv/dev/orders#password', ref: 'vault://kv/dev/orders#password' },
    { source: 'aws-sm', path: 'eu-central-1/dev/orders#password', ref: 'aws-sm://eu-central-1/dev/orders#password' },
    { source: 'aws-sm', path: 'arn:aws:secretsmanager:eu-central-1:123456789012:secret:orders-AbCdEf',
      ref: 'aws-sm://arn:aws:secretsmanager:eu-central-1:123456789012:secret:orders-AbCdEf' },
    { source: 'dynamic', path: 'database/creds/orders-reader', ref: 'vault://database/creds/orders-reader' },
  ];

  for (const sample of references) {
    for (const input of [sample.path, sample.ref]) {
      it(`accepts ${sample.source} input ${input} and sends its complete reference`, () => {
        const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
          idPrefix: 'reference', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
        });
        form.source.set(sample.source);
        const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
        form.setSecret(passwordNode, `  ${input}\n`);

        expect(form.submit()).toBe(true);
        expect(form.secretReference(passwordNode)).toBe(sample.ref);
        expect(form.secretInputs()).toEqual({ '/credentials/password': {
          source: sample.source === 'dynamic' ? 'vault-dynamic' : 'reference', ref: sample.ref,
        } });
      });
    }
  }

  it.each(['vault', 'gcp-sm', 'aws-sm'])('keeps an optional %s reference empty', (source) => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'reference', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    form.source.set(source);
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    form.setSecret(passwordNode, ' \n ');

    expect(form.submit()).toBe(true);
    expect(form.secretReference(passwordNode)).toBe('');
    expect(form.secretInputs()).toEqual({});
  });

  it('rejects an explicit reference to another provider without rewriting it', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'reference', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    form.source.set('gcp-sm');
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    form.setSecret(passwordNode, 'vault://kv/dev/orders#password');

    expect(form.submit()).toBe(false);
    expect(form.error(passwordNode)).toContain('gcp-sm://');
    expect(form.secretReference(passwordNode)).toBe('vault://kv/dev/orders#password');
  });

  it('preserves local secrets exactly, including whitespace and reference-like text', () => {
    const form = createSchemaForm(CONNECTOR_FORMS['redis'], {
      idPrefix: 'local', current: { connection: { host: 'redis.example.test' } }, injector: TestBed.inject(Injector),
    });
    const passwordNode = form.compiled.all.find((node) => node.pointer === '/credentials/password')!;
    const value = '  gcp-sm://test-only-password\n';
    form.setSecret(passwordNode, value);

    expect(form.submit()).toBe(true);
    expect(form.secretInputs()).toEqual({ '/credentials/password': { source: 'local', value } });
  });
});
