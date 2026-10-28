import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { TestOutcome } from '../../../core/api/connections.api';
import { JsonObject } from '../../../core/api/types';
import { provideKitIcons } from '../../icons';
import { ConnectionFormValue, GwConnectionForm } from './connection-form';
import { FileBrowseRequest, FileBrowseTrust } from './remote-files';
import { relativeFile } from './file-selection';

const SCHEMA = CONNECTOR_FORMS['ssh-logs'];
const SPEC: ConnectionFormSpec = { type: 'ssh-logs', name: 'SSH log files', env: 'dev', schema: SCHEMA, common: {},
  secretFields: [], secretSources: [], envDefaults: { prod: [], stage: [] }, variants: [] };
const OUTCOME: TestOutcome = { ok: true, latencyMs: 1, serverVersion: null, identity: null, permissions: null,
  hints: [], checks: [], error: null, needsConfirmation: null, confirmationId: null, suggestedPolicyChange: null, details: null };
const listing = (path = '/var/log', names = ['app.log'], dirs: string[] = [], truncated = false): TestOutcome => ({ ...OUTCOME,
  details: { path, parent: path === '/' ? null : path.slice(0, path.lastIndexOf('/')) || '/', truncated,
    entries: [...dirs.map((name) => ({ name, path: `${path}/${name}`, type: 'directory', size: 0 })),
      ...names.map((name) => ({ name, path: `${path}/${name}`, type: 'file', size: 20 }))] } });

@Component({ imports: [GwConnectionForm], template: `<form gwConnectionForm idPrefix="cf" name="remote-logs" [spec]="spec" [config]="config()" (save)="saved.push($event)" (browseFiles)="requests.push($event)" (trustFiles)="trusts.push($event)"></form>` })
class Host {
  readonly spec = SPEC;
  readonly config = signal<JsonObject>({ connection: { host: 'logs.example.test', username: 'reader' }, credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' }, logs: { root: '/var/log', allowedFiles: [] } });
  readonly requests: FileBrowseRequest[] = [];
  readonly trusts: FileBrowseTrust[] = [];
  readonly saved: ConnectionFormValue[] = [];
  readonly form = viewChild.required(GwConnectionForm);
}

async function render() {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  const host = fixture.componentInstance;
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); };
  await settle();
  const picker = () => host.form().files()!;
  const button = (label: string) => [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label)!;
  const load = async (path?: string, outcome = listing()) => {
    picker().load(path);
    await settle();
    const request = host.requests.at(-1)!;
    picker().complete(request, outcome);
    await settle();
    return request;
  };
  return { fixture, host, el, settle, picker, button, load };
}

afterEach(() => { document.body.innerHTML = ''; });

describe('remote log file selection', () => {
  it('keeps the file picker visible while optional parsing settings stay in the collapsed Log format section', async () => {
    const v = await render();
    const parsing = v.el.querySelector<HTMLElement>('#cf-logs-body')!;
    expect(parsing.hidden).toBe(true);
    expect(v.el.querySelector('#cf-files-browse')!.closest('[hidden]')).toBeNull();
    expect(v.el.querySelector('#cf-logs-root')).toBeNull();
    expect(v.el.querySelector('#cf-logs-allowedFiles')).toBeNull();
    expect(v.el.querySelector('#cf-credentials-privateKeyPath')).not.toBeNull();
    v.button('Log format settings').click(); await v.settle();
    expect(parsing.hidden).toBe(false);
    expect([...v.el.querySelector<HTMLSelectElement>('#cf-logs-format')!.options].map((option) => option.value))
      .toEqual(['auto', 'text', 'jsonl']);
    expect(v.el.querySelector<HTMLSelectElement>('#cf-logs-format')!.value).toBe('auto');
    expect(v.el.querySelector<HTMLInputElement>('#cf-logs-timezone')!.value).toBe('UTC');
    expect(v.el.querySelector<HTMLSelectElement>('#cf-logs-timestampUnit')!.value).toBe('milliseconds');
    for (const name of ['timestampField', 'levelField', 'timestampPattern']) {
      expect(v.el.querySelector(`#cf-logs-${name}`)).not.toBeNull();
    }
  });

  it('saves JSONL and text parsing options without changing selected files or authentication', async () => {
    const v = await render();
    await v.load();
    v.el.querySelector<HTMLInputElement>('input[aria-label="Allow app.log"]')!.click(); await v.settle();
    v.button('Log format settings').click(); await v.settle();
    for (const [name, value] of [['format', 'jsonl'], ['timestampUnit', 'seconds']]) {
      const control = v.el.querySelector<HTMLSelectElement>(`#cf-logs-${name}`)!;
      control.value = value; control.dispatchEvent(new Event('change', { bubbles: true })); await v.settle();
    }
    for (const [name, value] of [['timezone', 'Europe/Madrid'], ['timestampField', '@timestamp'], ['levelField', 'severity'], ['timestampPattern', 'uuuu-MM-dd HH:mm:ss']]) {
      const control = v.el.querySelector<HTMLInputElement>(`#cf-logs-${name}`)!;
      control.value = value; control.dispatchEvent(new Event('input', { bubbles: true })); await v.settle();
    }
    expect(v.picker().root()).toBe('/var/log');
    expect(v.picker().selected()).toEqual(['app.log']);
    expect(v.host.form().value().config['credentials']).toEqual({ method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' });
    v.button('Save connection').click(); await v.settle();
    expect(v.host.saved[0].config['logs']).toMatchObject({ root: '/var/log', allowedFiles: ['app.log'], format: 'jsonl',
      timezone: 'Europe/Madrid', timestampField: '@timestamp', levelField: 'severity', timestampPattern: 'uuuu-MM-dd HH:mm:ss', timestampUnit: 'seconds' });
    const format = v.el.querySelector<HTMLSelectElement>('#cf-logs-format')!;
    format.value = 'text'; format.dispatchEvent(new Event('change', { bubbles: true })); await v.settle();
    expect(v.picker().selected()).toEqual(['app.log']);
    expect(v.host.form().value().config['logs']).toMatchObject({ format: 'text', timestampPattern: 'uuuu-MM-dd HH:mm:ss' });
  });

  it('navigates folders, permits exact relative files and clears permissions when the root changes', async () => {
    const v = await render();
    expect(v.el.querySelector('#cf-logs-root')).toBeNull();
    await v.load('/var/log', listing('/var/log', ['app.log'], ['api']));
    v.el.querySelector<HTMLInputElement>('input[aria-label="Allow app.log"]')!.click();
    await v.settle();
    expect(v.host.form().value().config['logs']).toMatchObject({ root: '/var/log', allowedFiles: ['app.log'] });
    v.el.querySelector<HTMLButtonElement>('button[aria-label="Open folder api"]')!.click();
    await v.settle();
    const nested = v.host.requests.at(-1)!;
    expect(nested.path).toBe('/var/log/api');
    v.picker().complete(nested, listing('/var/log/api', ['error.log']));
    await v.settle();
    v.el.querySelector<HTMLInputElement>('input[aria-label="Allow error.log"]')!.click();
    await v.settle();
    expect(v.picker().selected()).toEqual(['app.log', 'api/error.log']);
    v.button('Use this log folder').click();
    await v.settle();
    expect(v.picker().selected()).toEqual([]);
    expect(v.host.form().value().config['logs']).toMatchObject({ root: '/var/log/api' });
    expect(v.host.form().value().config['logs']).not.toHaveProperty('allowedFiles');
    expect(v.picker().directory()?.path).toBe('/var/log/api');
    v.el.querySelector<HTMLInputElement>('input[aria-label="Allow error.log"]')!.click();
    await v.settle();
    v.button('Save connection').click();
    await v.settle();
    expect(v.host.saved[0].config['logs']).toMatchObject({ root: '/var/log/api', allowedFiles: ['error.log'] });
  });

  it('allows choosing a first root and selecting a file immediately after a canonicalized directory response', async () => {
    const v = await render();
    v.host.config.set({ connection: { host: 'logs.example.test', username: 'reader' }, credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' } });
    await v.settle();
    await v.load('/srv/app-logs/', listing('/srv/app-logs', ['server.log']));
    v.button('Use this log folder').click();
    await v.settle();
    expect(v.picker().directory()?.path).toBe('/srv/app-logs');
    v.el.querySelector<HTMLInputElement>('input[aria-label="Allow server.log"]')!.click();
    await v.settle();
    expect(v.host.form().value().config['logs']).toMatchObject({ root: '/srv/app-logs', allowedFiles: ['server.log'] });
  });

  it('rejects stale replies after changing the path, host or credentials, including repeated paths', async () => {
    const v = await render();
    v.picker().load();
    await v.settle();
    const old = v.host.requests.at(-1)!;
    v.picker().address.set('/srv/logs');
    await v.settle();
    v.picker().load();
    await v.settle();
    const latest = v.host.requests.at(-1)!;
    expect(v.picker().complete(old, listing())).toBe(false);
    v.picker().complete(latest, listing('/srv/logs'));
    await v.settle();
    const host = v.el.querySelector<HTMLInputElement>('#cf-connection-host')!;
    host.value = 'other.example.test'; host.dispatchEvent(new Event('input', { bubbles: true }));
    await v.settle();
    expect(v.picker().directory()).toBeNull();
    expect(v.picker().complete(latest, listing('/srv/logs'))).toBe(false);
    await v.load('/var/log');
    const password = v.el.querySelector<HTMLInputElement>('#cf-credentials-passphrase')!;
    password.value = 'new-secret'; password.dispatchEvent(new Event('input', { bubbles: true }));
    await v.settle();
    expect(v.picker().directory()).toBeNull();
    await v.load('/var/log');
    const repeat = v.host.requests.at(-1)!;
    v.picker().load(); await v.settle();
    expect(v.picker().complete(repeat, listing())).toBe(false);
  });

  it('preserves exact directory names including trailing spaces when navigating', async () => {
    const v = await render();
    await v.load('/var/log', listing('/var/log', [], ['service logs ']));
    v.el.querySelector<HTMLButtonElement>('button[aria-label="Open folder service logs "]')!.click();
    await v.settle();
    expect(v.host.requests.at(-1)?.path).toBe('/var/log/service logs ');
  });

  it('requires a file selection, preserves saved selections, and does not select files outside the root', async () => {
    const v = await render();
    v.button('Save connection').click(); await v.settle();
    expect(v.host.saved).toHaveLength(0);
    expect(v.el.textContent).toContain('Select at least one log file');
    v.host.config.set({ connection: { host: 'logs.example.test', username: 'reader' }, credentials: { method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' }, logs: { root: '/var/log', allowedFiles: ['saved.log'] } });
    await v.settle();
    await v.load('/etc', listing('/etc', ['private.txt']));
    expect(v.el.querySelector('input[aria-label="Allow private.txt"]')).toBeNull();
    expect(v.picker().selected()).toEqual(['saved.log']);
    v.el.querySelector<HTMLButtonElement>('button[aria-label="Remove allowed file saved.log"]')!.click(); await v.settle();
    expect(v.picker().selected()).toEqual([]);
  });

  it('shows host key facts and emits a confirmation only for the current draft', async () => {
    const v = await render();
    await v.load('/var/log', { ...OUTCOME, ok: false, confirmationId: 'key-1',
      needsConfirmation: { kind: 'host-key', text: 'Compare this fingerprint with the server administrator.',
        data: { host: 'logs.example.test', port: 22, fingerprint: 'SHA256:example', keyType: 'ssh-ed25519' } } });
    expect(v.el.textContent).toContain('SHA256:example');
    v.button('Trust host key and browse').click(); await v.settle();
    expect(v.host.trusts).toHaveLength(1);
    expect(v.host.trusts[0].confirmationId).toBe('key-1');
    expect(v.picker().busy()).toBe(true);
  });

  it('shows empty, partial and error states and resets pending navigation on discard', async () => {
    const v = await render();
    await v.load('/var/log', listing('/var/log', [], [], true));
    expect(v.el.textContent).toContain('No regular files or folders');
    expect(v.el.textContent).toContain('more entries than can be shown');
    v.picker().load(); await v.settle();
    const pending = v.host.requests.at(-1)!;
    v.picker().complete(pending, null, 'Permission denied'); await v.settle();
    expect(v.el.textContent).toContain('Permission denied');
    v.host.form().reset(); await v.settle();
    expect(v.picker().complete(pending, listing())).toBe(false);
    expect(v.picker().result()).toBeNull();
  });

  it('matches roots on path boundaries and treats nested files as exact paths', () => {
    expect(relativeFile('/var/log', '/var/log/api/error.log')).toBe('api/error.log');
    expect(relativeFile('/var/log', '/var/logs/secret')).toBeNull();
    expect(relativeFile('/var/log', '/var/log/../secret')).toBeNull();
    expect(relativeFile('/', '/var/log/app.log')).toBe('var/log/app.log');
  });
});
