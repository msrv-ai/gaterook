import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CONNECTOR_FORMS, CONNECTOR_GOLDENS } from '../../testing/connector-schemas';
import { ApiError } from '../core/api/api-error';
import { JsonObject } from '../core/api/types';
import { cleanValue, compileSchema, getAt, initialValue, isVisible, layoutRows, setAt } from './compile';
import { golden } from './goldens';
import { SchemaForm, SchemaFormOptions, createSchemaForm } from './schema-form';

/**
 * The renderer on the core's real connector schemas (reports 04–12): a change here is a change of a form a person
 * sees. Regenerate with `node ui/scripts/schema-goldens.mjs` (add `--fetch` with the dev loop running) and review.
 */
describe('schema-form goldens: every connector form the core serves', () => {
  for (const type of Object.keys(CONNECTOR_FORMS)) {
    it(`${type}: fields, sections, rows, secrets and defaults`, () => {
      expect(golden(CONNECTOR_FORMS[type])).toEqual(CONNECTOR_GOLDENS[type]);
    });
  }

  it('covers the connector types of the catalog', () => {
    expect(Object.keys(CONNECTOR_FORMS).sort()).toEqual(
      ['aws', 'gcp', 'http', 'kubernetes', 'logs', 'mcp-server', 'mongodb', 'mysql', 'postgres', 'redis', 'ssh'].sort(),
    );
  });

  it('never puts a secret into what is sent, and every secret field is a secret widget', () => {
    for (const [type, schema] of Object.entries(CONNECTOR_FORMS)) {
      const form = compileSchema(schema);
      const secrets = form.all.filter((n) => n.kind === 'secret');
      for (const n of secrets) expect(n.secret, `${type} ${n.pointer}`).not.toBeNull();
      let model = initialValue(schema);
      for (const n of secrets) model = setAt(model, n.path, 'hunter2');
      expect(JSON.stringify(cleanValue(form, model)), type).not.toContain('hunter2');
    }
  });

  it('PostgreSQL: Host · Port · Database share a row, the password is a value secret', () => {
    const g = CONNECTOR_GOLDENS['postgres'];
    expect(g.sections).toEqual(['/connection', '/credentials', '/advanced']);
    expect(g.rows['/connection'][0]).toEqual(['/connection/host', '/connection/port', '/connection/database']);
    expect(g.fields.find((f) => f.pointer === '/credentials/password')).toMatchObject({ kind: 'secret', secret: 'value' });
    expect(g.defaults).toMatchObject({ connection: { port: 5432, sslMode: 'verify-full' } });
  });
});

describe('schema-form: branches and conditions', () => {
  const schema: JsonObject = {
    type: 'object',
    properties: {
      auth: {
        type: 'object',
        title: 'Auth',
        oneOf: [
          { title: 'Bearer token', properties: { type: { const: 'bearer' }, token: { type: 'string', 'x-gateway-secret': 'value' } }, required: ['token'] },
          { title: 'Basic', properties: { type: { const: 'basic' }, username: { type: 'string' }, password: { type: 'string', 'x-gateway-secret': 'value' } } },
        ],
      },
      transport: {
        type: 'object',
        title: 'Transport',
        properties: { kind: { enum: ['stdio', 'http'], default: 'stdio' } },
        allOf: [
          { if: { properties: { kind: { const: 'stdio' } } }, then: { properties: { command: { type: 'string' } }, required: ['command'] } },
          { if: { properties: { kind: { const: 'http' } } }, then: { properties: { url: { type: 'string', format: 'uri' } } } },
        ],
      },
    },
  };

  it('a oneOf becomes a choice and each branch shows its own fields', () => {
    const form = compileSchema(schema);
    const auth = form.sections[0];
    expect(auth.children[0]).toMatchObject({ key: 'type', kind: 'select' });
    expect(auth.children[0].options.map((o) => o.title)).toEqual(['Bearer token', 'Basic']);
    const model = initialValue(schema);
    expect(model['auth']).toEqual({ type: 'bearer', token: null, username: null, password: null });
    const username = form.all.find((n) => n.pointer === '/auth/username')!;
    expect(isVisible(username, model)).toBe(false);
    expect(isVisible(username, setAt(model, ['auth', 'type'], 'basic'))).toBe(true);
  });

  it('allOf if/then: the fields of a branch show only for its value and are dropped otherwise', () => {
    const form = compileSchema(schema);
    const command = form.all.find((n) => n.pointer === '/transport/command')!;
    const url = form.all.find((n) => n.pointer === '/transport/url')!;
    expect(command.required).toBe(true);
    let model = setAt(initialValue(schema), ['transport', 'command'], 'npx server');
    expect([isVisible(command, model), isVisible(url, model)]).toEqual([true, false]);
    model = setAt(setAt(model, ['transport', 'kind'], 'http'), ['transport', 'url'], 'https://mcp.acme.dev');
    expect(cleanValue(form, model)).toEqual({ auth: { type: 'bearer' }, transport: { kind: 'http', url: 'https://mcp.acme.dev' } });
  });

  it('SSH: each sign-in method shows only its own fields, with their defaults, and sends only them', () => {
    const ssh = CONNECTOR_FORMS['ssh'];
    const form = compileSchema(ssh);
    const at = (pointer: string) => form.all.find((n) => n.pointer === pointer)!;
    const fields = ['/credentials/vault/store', '/credentials/privateKey', '/credentials/password'].map(at);
    const shown = (model: JsonObject) => fields.map((n) => isVisible(n, model));
    let model = initialValue(ssh);
    expect(getAt(model, ['credentials', 'vault', 'mount'])).toBe('ssh-client-signer');
    expect(shown(model)).toEqual([true, false, false]);
    expect(at('/credentials/vault/store').required).toBe(true);
    model = setAt(model, ['credentials', 'method'], 'privateKey');
    expect(shown(model)).toEqual([false, true, false]);
    expect(at('/credentials/privateKey').required).toBe(true);
    model = setAt(model, ['credentials', 'certificate'], 'ssh-ed25519-cert-v01@openssh.com AAAA');
    expect(cleanValue(form, model)['credentials']).toEqual({ method: 'privateKey', certificate: 'ssh-ed25519-cert-v01@openssh.com AAAA' });
    model = setAt(model, ['credentials', 'method'], 'password');
    expect(shown(model)).toEqual([false, false, true]);
    expect(cleanValue(form, model)['credentials']).toEqual({ method: 'password' });
  });

  it('Logs: a key two branches share keeps the value of the branch shown', () => {
    const logs = CONNECTOR_FORMS['logs'];
    const form = compileSchema(logs);
    let model = setAt(initialValue(logs), ['url'], 'http://loki.dev.internal:3100');
    model = setAt(model, ['auth', 'mode'], 'bearer');
    expect(cleanValue(form, model)).toMatchObject({ backend: 'loki', url: 'http://loki.dev.internal:3100', auth: { mode: 'bearer' } });
    expect(cleanValue(form, model)['credentials']).toBeUndefined();
  });

  it('short fields share a row of at most three; anything with its own block stands alone', () => {
    const form = compileSchema({
      type: 'object',
      properties: {
        c: {
          type: 'object',
          properties: {
            a: { type: 'string' },
            b: { type: 'integer' },
            c: { type: 'string' },
            d: { type: 'integer' },
            e: { type: 'array', items: { type: 'string' } },
          },
        },
      },
    });
    expect(layoutRows(form.sections[0].children).map((r) => r.map((n) => n.key))).toEqual([['a', 'b', 'c'], ['d'], ['e']]);
  });
});

describe('schema-form: display sections', () => {
  const schema: JsonObject = {
    type: 'object',
    properties: {
      connection: {
        type: 'object',
        properties: {
          host: { type: 'string' },
          caCertificate: { type: 'string', maxLength: 10, 'x-widget': 'textarea', 'x-section': '/advanced' },
          unknownTarget: { type: 'string', 'x-section': '/missing' },
        },
      },
      advanced: { type: 'object', 'x-collapsed': true, properties: { timeout: { type: 'integer' } } },
    },
  };
  const make = (current: JsonObject): SchemaForm =>
    createSchemaForm(schema, { idPrefix: 't', pointerPrefix: '/config', current, injector: TestBed.inject(Injector) });

  it('moves a field once while preserving its config path and stored value', () => {
    const f = make({ connection: { host: 'db', caCertificate: 'saved CA' } });
    const [connection, advanced] = f.compiled.sections;
    expect(connection.children.map((n) => n.pointer)).toEqual(['/connection/host', '/connection/unknownTarget']);
    expect(advanced.rows.flat().map((n) => n.pointer)).toEqual(['/connection/caCertificate', '/advanced/timeout']);
    const certificate = f.compiled.all.filter((n) => n.pointer === '/connection/caCertificate');
    expect(certificate).toHaveLength(1);
    expect(certificate[0].path).toEqual(['connection', 'caCertificate']);
    expect(f.isExpanded(advanced)).toBe(false);
    expect(f.value()).toMatchObject({ connection: { host: 'db', caCertificate: 'saved CA' } });
    f.set(certificate[0], 'new CA');
    expect(f.value()).toMatchObject({ connection: { caCertificate: 'new CA' } });
    expect(f.value()['advanced']).not.toHaveProperty('caCertificate');
  });

  it('opens the display section when a relocated field fails client validation', () => {
    const f = make({ connection: { caCertificate: 'longer than the allowed length' } });
    const advanced = f.compiled.sections[1];
    expect(f.isExpanded(advanced)).toBe(false);
    expect(f.submit()).toBe(false);
    expect(f.invalidNodes().map((n) => n.pointer)).toEqual(['/connection/caCertificate']);
    expect(f.isExpanded(advanced)).toBe(true);
  });

  it('keeps conditional sections with the same pointer distinct and ignores ambiguous destinations', () => {
    const form = compileSchema({
      type: 'object',
      properties: {
        kind: { enum: ['first', 'second'] },
        connection: { type: 'object', properties: { certificate: { type: 'string', 'x-section': '/auth' } } },
      },
      allOf: [
        {
          if: { properties: { kind: { const: 'first' } } },
          then: { properties: { auth: { type: 'object', properties: { first: { type: 'string' } } } } },
        },
        {
          if: { properties: { kind: { const: 'second' } } },
          then: { properties: { auth: { type: 'object', properties: { second: { type: 'string' } } } } },
        },
      ],
    });
    const auth = form.sections.filter((section) => section.pointer === '/auth');
    expect(auth.map((section) => section.rows.flat().map((node) => node.pointer))).toEqual([['/auth/first'], ['/auth/second']]);
    expect(form.sections.find((section) => section.pointer === '/connection')!.children.map((node) => node.pointer))
      .toEqual(['/connection/certificate']);
    expect(form.all.filter((node) => node.kind !== 'object').map((node) => node.pointer))
      .toEqual(['/kind', '/auth/first', '/auth/second', '/connection/certificate']);
  });

  it('opens the display section for server errors at the original config path', () => {
    const f = make({ connection: { caCertificate: 'invalid CA' } });
    const advanced = f.compiled.sections[1];
    const remaining = f.applyServerErrors(new ApiError('unprocessable', 422, {
      errors: [{ field: '/config/connection/caCertificate', message: 'Not a valid certificate.' }],
    }));
    expect(remaining).toEqual([]);
    expect(f.isExpanded(advanced)).toBe(true);
  });
});

describe('schema-form: environments, locks, confirmations (x-env-default, x-forbidden-env, x-confirm)', () => {
  const make = (type: string, options: Partial<SchemaFormOptions> = {}): SchemaForm =>
    createSchemaForm(CONNECTOR_FORMS[type], { idPrefix: 't', injector: TestBed.inject(Injector), ...options });
  const node = (f: SchemaForm, pointer: string) => f.compiled.all.find((n) => n.pointer === pointer)!;

  it('MongoDB: a new prod connection starts with TLS on and secondaryPreferred, and sends them', () => {
    const f = make('mongodb', { env: 'prod' });
    expect(f.value()['connection']).toMatchObject({ tls: true, readPreference: 'secondaryPreferred', allowInvalidHostnames: false });
    expect(CONNECTOR_GOLDENS['mongodb'].prodDefaults['connection']).toMatchObject({ tls: true, readPreference: 'secondaryPreferred' });
  });

  it('MongoDB: picking prod moves the defaults the person left alone, and keeps what they chose', () => {
    const f = make('mongodb');
    expect(f.value()['connection']).toMatchObject({ tls: false, readPreference: 'primary' });
    f.set(node(f, '/connection/readPreference'), 'nearest');
    f.setEnv('prod');
    expect(f.value()['connection']).toMatchObject({ tls: true, readPreference: 'nearest' });
    // Back to dev, a new connection follows the defaults again.
    f.setEnv('dev');
    expect(f.value()['connection']).toMatchObject({ tls: false, readPreference: 'nearest' });
  });

  it('a stored config is only tightened by an env change', () => {
    const stored = { connection: { host: 'db.acme.dev', tls: false, readPreference: 'primary' } };
    const f = make('mongodb', { current: stored, env: 'dev' });
    f.setEnv('prod');
    expect(f.value()['connection']).toMatchObject({ tls: true, readPreference: 'secondaryPreferred' });
    const prod = make('mongodb', { current: { connection: { host: 'db.acme.dev', tls: true } }, env: 'prod' });
    prod.setEnv('dev');
    expect(prod.value()['connection']).toMatchObject({ tls: true });
    // Opening a stored prod connection changes nothing it holds.
    const opened = make('mongodb', { current: { connection: { host: 'db', tls: false, readPreference: 'primary' } }, env: 'prod' });
    expect(opened.value()['connection']).toMatchObject({ tls: false, readPreference: 'primary' });
  });

  it('Redis: "ca" can\'t be picked on prod, and a dev value of it goes back to the default', () => {
    const f = make('redis', { current: { connection: { host: 'cache', tlsVerify: 'ca' } }, env: 'dev' });
    const tlsVerify = node(f, '/connection/tlsVerify');
    expect(f.optionForbidden(tlsVerify, 'ca')).toBe(false);
    f.setEnv('prod');
    expect(f.optionForbidden(tlsVerify, 'ca')).toBe(true);
    expect(f.optionForbidden(tlsVerify, 'full')).toBe(false);
    expect(getAt(f.model(), ['connection', 'tlsVerify'])).toBe('full');
    const title = tlsVerify.options.find((o) => o.value === 'ca')!.title;
    expect(f.lockReason(tlsVerify)).toBe(`${title} isn't allowed on prod.`);
  });

  it('MongoDB: Skip the host name check stays off on prod, with the reason', () => {
    const f = make('mongodb', { current: { connection: { host: 'db', allowInvalidHostnames: true } }, env: 'dev' });
    const n = node(f, '/connection/allowInvalidHostnames');
    expect([f.locked(n), f.lockReason(n)]).toEqual([false, null]);
    f.setEnv('prod');
    expect([f.locked(n), f.lockReason(n)]).toEqual([true, 'Not allowed on prod.']);
    expect(f.value()['connection']).toMatchObject({ allowInvalidHostnames: false });
  });

  it('HTTP: an API key in the query is locked out on prod; a stored prod config holding it is reset', () => {
    const f = make('http', { current: { auth: { type: 'apiKeyQuery' } }, env: 'prod' });
    expect(getAt(f.model(), ['auth', 'type'])).toBe('bearer');
    expect(f.optionForbidden(node(f, '/auth/type'), 'apiKeyQuery')).toBe(true);
  });

  it('Kubernetes: Run the credential command asks before it is saved', () => {
    // A kubeconfig's setting: signing in with a server and token never shows it.
    const f = make('kubernetes', { current: { auth: 'kubeconfig-file' } });
    const exec = node(f, '/allowExecPlugin');
    expect(f.visible(exec)).toBe(true);
    expect(f.visible(node(f, '/server'))).toBe(false);
    expect(exec.confirm).toBe('warning');
    expect(f.confirmations()).toEqual([]);
    f.set(exec, true);
    expect(f.confirmations().map((c) => [c.node.pointer, c.tone, c.from, c.to])).toEqual([['/allowExecPlugin', 'warning', false, true]]);
    f.set(exec, false);
    expect(f.confirmations()).toEqual([]);
  });

  it('Kubernetes: the context lists the kubeconfig contexts from the metadata, and keeps a stored one', () => {
    const f = make('kubernetes', { current: { context: 'gke-prod' } });
    const context = node(f, '/context');
    expect(context.kind).toBe('select');
    expect(f.optionsOf(context).map((o) => o.value)).toEqual(['gke-prod']);
    f.metadata.set({ kubeconfigContexts: { 'kind-dev': 'https://127.0.0.1:6443', 'gke-prod': 'https://34.1.2.3' } });
    expect(f.optionsOf(context).map((o) => [o.value, o.description])).toEqual([
      ['kind-dev', 'https://127.0.0.1:6443'],
      ['gke-prod', 'https://34.1.2.3'],
    ]);
    f.metadata.set({ kubeconfigContexts: [{ name: 'a', server: 'https://a' }, 'b'] });
    expect(f.optionsOf(context).map((o) => o.value)).toEqual(['a', 'b', 'gke-prod']);
    expect(make('kubernetes').optionsOf(context)).toEqual([]);
  });

  it('multi-line secrets are textareas: kubeconfig, service account keys, PEM keys', () => {
    for (const [type, pointer] of [
      ['kubernetes', '/kubeconfig'],
      ['gcp', '/credentials/serviceAccountKey'],
      ['logs', '/credentials/serviceAccountKey'],
      ['ssh', '/credentials/privateKey'],
    ]) {
      expect(node(make(type), pointer).multiline, `${type} ${pointer}`).toBe(true);
    }
    expect(node(make('postgres'), '/credentials/password').multiline).toBe(false);
  });

  it('an empty text field holds "" (a null-bound text input would read typed text as a number) and sends nothing', () => {
    const f = make('postgres');
    expect(getAt(f.model(), ['connection', 'host'])).toBe('');
    expect(getAt(f.model(), ['credentials', 'user'])).toBe('');
    // A number field keeps null, and what is sent drops empty values as before.
    expect(getAt(f.model(), ['advanced', 'statementTimeoutMs'])).toBeNull();
    expect(f.value()['connection']).toEqual({ port: 5432, sslMode: 'verify-full' });
    f.field(node(f, '/connection/host'))().value.set('db.dev.internal');
    expect(f.value()['connection']).toEqual({ host: 'db.dev.internal', port: 5432, sslMode: 'verify-full' });
    f.reset(null, CONNECTOR_FORMS['postgres']);
    expect(getAt(f.model(), ['connection', 'host'])).toBe('');
  });
});
