import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ApiError } from '../core/api/api-error';
import { SecretRefTest, SecretStore, SecretsApi, VaultBrowse } from '../core/api/secrets.api';
import { JsonObject } from '../core/api/types';
import { provideKitIcons } from '../kit/icons';
import { SecretAction, createSchemaForm } from './schema-form';
import { GwSchemaForm } from './schema-form.component';
import { GwVaultPicker } from './vault-picker';

const SCHEMA: JsonObject = {
  type: 'object', required: ['password'], properties: {
    password: { type: 'string', title: 'Password', writeOnly: true, format: 'password', 'x-gateway-secret': 'value' },
  },
};

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class Host {
  readonly form = createSchemaForm(SCHEMA, { idPrefix: 'secret' });
}

const store = (id = 'vault-dev'): SecretStore => ({
  id, kind: 'vault', name: id, config: {}, state: 'connected', lastCheckedAt: null,
});
const keys = (...names: string[]): SecretRefTest => ({
  found: true, resolvedVersion: '4', created: null, type: 'json', keys: names,
});
const entry = (name: string, path: string, directory = false) => ({ name, path, directory });

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function setup(options: { stores?: readonly SecretStore[] } = {}) {
  const api = {
    stores: vi.fn<() => Promise<readonly SecretStore[]>>().mockResolvedValue(options.stores ?? [store()]),
    browseVault: vi.fn<(store: string, path: string) => Promise<VaultBrowse>>()
      .mockResolvedValue({ path: '', entries: [entry('kv', 'kv', true)] }),
    testReference: vi.fn<(uri: string, store?: string | null) => Promise<SecretRefTest>>()
      .mockResolvedValue(keys('password', 'username')),
  };
  TestBed.configureTestingModule({ providers: [provideKitIcons(), { provide: SecretsApi, useValue: api }] });
  const fixture = TestBed.createComponent(Host);
  const form = fixture.componentInstance.form;
  form.source.set('vault');
  const actions: SecretAction[] = [];
  form.onSecretAction((action) => actions.push(action));
  const settle = async () => {
    // Store discovery and the subsequent metadata call are separate promise continuations.
    for (let i = 0; i < 3; i++) { fixture.detectChanges(); await fixture.whenStable(); }
    fixture.detectChanges();
  };
  await settle();
  const el = fixture.nativeElement as HTMLElement;
  const picker = fixture.debugElement.query(By.directive(GwVaultPicker)).componentInstance as GwVaultPicker;
  const control = <T extends HTMLElement>(suffix: string) => el.querySelector<T>(`#secret-password-vault-${suffix}`)!;
  const reference = () => el.querySelector<HTMLInputElement>('#secret-password')!;
  const click = async (text: string) => {
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.trim() === text)!.click();
    await settle();
  };
  const type = async (value: string) => {
    reference().value = value;
    reference().dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const select = async (suffix: string, value: string) => {
    const field = control<HTMLSelectElement>(suffix);
    field.value = value;
    field.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
  };
  const folder = async (value: string) => {
    const field = control<HTMLInputElement>('folder');
    field.value = value;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const optionsOf = (suffix: string) => [...control<HTMLSelectElement>(suffix).options].slice(1).map((option) => option.textContent);
  return { api, fixture, form, actions, el, picker, control, reference, click, type, select, folder, optionsOf, settle };
}

describe('Vault secret selection', () => {
  it('loads metadata only on request and browses a mount, folder and secret before choosing a key', async () => {
    const view = await setup();
    expect(view.api.stores).not.toHaveBeenCalled();
    expect(view.api.browseVault).not.toHaveBeenCalled();
    expect(view.api.testReference).not.toHaveBeenCalled();
    expect(view.control<HTMLSelectElement>('key').disabled).toBe(true);

    view.api.browseVault.mockImplementation(async (_store, path) => ({ path, entries: path === ''
      ? [entry('kv', 'kv', true)] : path === 'kv'
      ? [entry('dev', 'kv/dev', true)] : [entry('orders', 'kv/dev/orders')] }));
    await view.click('Browse Vault');
    expect(view.api.browseVault).toHaveBeenLastCalledWith('vault-dev', '');
    expect(view.optionsOf('entries')).toEqual(['kv/']);
    await view.select('entries', '0');
    expect(view.control<HTMLInputElement>('folder').value).toBe('kv');
    expect(view.optionsOf('entries')).toEqual(['dev/']);
    await view.select('entries', '0');
    expect(view.optionsOf('entries')).toEqual(['orders']);
    await view.select('entries', '0');
    expect(view.reference().value).toBe('kv/dev/orders');
    expect(view.api.testReference).toHaveBeenCalledExactlyOnceWith('vault://kv/dev/orders', 'vault-dev');
    expect(view.optionsOf('key')).toEqual(['password', 'username']);
    expect(view.control<HTMLSelectElement>('key').value).toBe('');

    await view.select('key', 'password');
    expect(view.reference().value).toBe('kv/dev/orders#password');
    expect(view.form.submit()).toBe(true);
    expect(view.form.secretInputs()).toEqual({ '/password': {
      source: 'reference', ref: 'vault://kv/dev/orders#password', store: 'vault-dev',
    } });
    expect(view.form.value()).toEqual({});
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: 'vault://kv/dev/orders#password', store: 'vault-dev' })]);
  });

  it.each(['kv/dev/orders?version=2#old', 'vault://kv/dev/orders#old?version=2'])(
    'loads a manually entered pinned reference without its old key and preserves version: %s', async (reference) => {
      const view = await setup();
      await view.type(reference);
      await view.click('Load keys');
      expect(view.api.browseVault).not.toHaveBeenCalled();
      expect(view.api.testReference).toHaveBeenCalledExactlyOnceWith('vault://kv/dev/orders?version=2', 'vault-dev');
      await view.select('key', 'password');
      expect(view.reference().value).toBe('kv/dev/orders?version=2#password');
      expect(view.form.secretInputs()['/password']).toEqual({ source: 'reference', ref: 'vault://kv/dev/orders?version=2#password', store: 'vault-dev' });
    },
  );

  it('requires an explicit store choice when multiple Vault stores are connected', async () => {
    const view = await setup({ stores: [store(), store('vault-prod')] });
    await view.type('kv/orders');
    await view.click('Load keys');
    expect(view.api.testReference).not.toHaveBeenCalled();
    expect(view.control<HTMLSelectElement>('store').value).toBe('');
    expect(view.optionsOf('store')).toEqual(['vault-dev', 'vault-prod']);
    await view.select('store', 'vault-prod');
    await view.click('Load keys');
    expect(view.api.testReference).toHaveBeenCalledExactlyOnceWith('vault://kv/orders', 'vault-prod');
    await view.select('key', 'password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ store: 'vault-prod' });
  });

  it('resolves a saved store name to its ID before loading keys and saving a replacement', async () => {
    const view = await setup({ stores: [{ ...store('vault-123'), name: 'Development Vault' }] });
    const saved = { field: '/password', secretId: 'secret-1', name: 'vault://kv/saved#password',
      state: 'reference', version: null, source: 'vault', store: 'Development Vault' };
    view.form.setStored([saved]);
    await view.settle();
    await view.click('Change');
    await view.type(saved.name);
    await view.click('Load keys');
    expect(view.api.testReference).toHaveBeenCalledExactlyOnceWith('vault://kv/saved', 'vault-123');
    await view.select('key', 'password');
    expect(view.form.secretInputs()['/password']).toEqual({ source: 'reference', ref: saved.name, store: 'vault-123' });
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: saved.name, store: 'vault-123' })]);
  });

  it.each([
    { error: new Error('offline'), message: 'Could not load Vault stores' },
    { error: new ApiError('unprocessable', 422, { detail: 'Unlock the configured Vault store before browsing.' }), message: 'Unlock the configured Vault store' },
  ])('can retry loading stores after a discovery failure: $message', async ({ error, message }) => {
    const view = await setup();
    view.api.stores.mockRejectedValueOnce(error);
    await view.type('kv/orders');
    await view.click('Load keys');
    expect(view.el.querySelector('[role="alert"]')?.textContent).toContain(message);
    expect(view.api.testReference).not.toHaveBeenCalled();
    expect(view.reference().value).toBe('kv/orders');
    await view.click('Load keys');
    expect(view.api.stores).toHaveBeenCalledTimes(2);
    expect(view.el.querySelector('[role="alert"]')).toBeNull();
    expect(view.optionsOf('key')).toEqual(['password', 'username']);
  });

  it('explains when no Vault store is connected without attempting secret discovery', async () => {
    const view = await setup({ stores: [] });
    await view.type('kv/orders');
    await view.click('Load keys');
    expect(view.el.querySelector('[role="alert"]')?.textContent).toContain('Connect Vault or OpenBao in Secrets');
    expect(view.api.testReference).not.toHaveBeenCalled();
    expect(view.api.browseVault).not.toHaveBeenCalled();
    expect(view.control<HTMLSelectElement>('key').disabled).toBe(true);
  });

  it.each(['discard', 'cancel'] as const)('restores the saved store after %s of a reference replacement', async (action) => {
    const view = await setup({ stores: [store(), store('vault-prod')] });
    const saved = { field: '/password', secretId: 'secret-1', name: 'vault://kv/saved#password',
      state: 'reference', version: null, source: 'vault', store: 'vault-dev' };
    view.form.setStored([saved]);
    await view.settle();
    await view.click('Change');
    await view.type('kv/replacement#password');
    await view.click('Load keys');
    await view.select('store', 'vault-prod');
    expect(view.form.secretInputs()['/password']).toMatchObject({ store: 'vault-prod' });

    const node = view.form.compiled.all.find((node) => node.pointer === '/password')!;
    if (action === 'discard') view.form.reset({}, SCHEMA);
    else view.form.replace(node, false);
    await view.settle();
    expect(view.form.secretInputs()).toEqual({});
    expect(view.form.vaultStores()).toEqual({});
    expect(view.el.querySelector('gw-vault-picker')).toBeNull();
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: saved.name, store: 'vault-dev' })]);
  });

  it('uses refreshed stored metadata after a reference replacement instead of its draft store', async () => {
    const view = await setup({ stores: [store(), store('vault-prod')] });
    const saved = { field: '/password', secretId: 'secret-1', name: 'vault://kv/saved#password',
      state: 'reference', version: null, source: 'vault', store: 'vault-dev' };
    view.form.setStored([saved]);
    await view.settle();
    await view.click('Change');
    await view.type('kv/replacement#password');
    await view.click('Load keys');
    await view.select('store', 'vault-prod');
    expect(view.form.secretInputs()['/password']).toMatchObject({ store: 'vault-prod' });

    const refreshed = { ...saved, name: 'vault://kv/current#password' };
    view.form.setStored([refreshed]);
    await view.settle();
    expect(view.form.secretInputs()).toEqual({});
    expect(view.form.vaultStores()).toEqual({});
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: refreshed.name, store: 'vault-dev' })]);
  });

  it('discards keys from a previous path even when its response arrives after the next lookup', async () => {
    const view = await setup();
    const earlier = deferred<SecretRefTest>();
    view.api.testReference.mockReturnValueOnce(earlier.promise).mockResolvedValueOnce(keys('new-password'));
    await view.type('kv/old');
    await view.click('Load keys');
    expect(view.control('keys-status').textContent).toContain('Loading key names');
    await view.type('kv/new');
    expect(view.control<HTMLSelectElement>('key').disabled).toBe(true);
    await view.click('Load keys');
    expect(view.optionsOf('key')).toEqual(['new-password']);
    earlier.resolve(keys('old-password'));
    await view.settle();
    expect(view.optionsOf('key')).toEqual(['new-password']);
    await view.select('key', 'new-password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/new#new-password' });
  });

  it('discards a pending lookup after changing stores and keeps the new selection scoped to its store', async () => {
    const view = await setup({ stores: [store(), store('vault-prod')] });
    await view.type('kv/orders');
    await view.click('Load keys');
    await view.select('store', 'vault-dev');
    const earlier = deferred<SecretRefTest>();
    view.api.testReference.mockReturnValueOnce(earlier.promise).mockResolvedValueOnce(keys('prod-password'));
    await view.click('Load keys');
    await view.select('store', 'vault-prod');
    await view.click('Load keys');
    earlier.resolve(keys('dev-password'));
    await view.settle();
    expect(view.optionsOf('key')).toEqual(['prod-password']);
    await view.select('key', 'prod-password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/orders#prod-password', store: 'vault-prod' });
  });

  it('discards folder entries from an earlier request after the folder changes', async () => {
    const view = await setup();
    await view.click('Browse Vault');
    const earlier = deferred<VaultBrowse>();
    view.api.browseVault.mockReturnValueOnce(earlier.promise).mockResolvedValueOnce({ path: 'kv/new', entries: [entry('new', 'kv/new/new')] });
    await view.folder('kv/old');
    await view.click('Load secrets');
    await view.folder('kv/new');
    await view.click('Load secrets');
    earlier.resolve({ path: 'kv/old', entries: [entry('old', 'kv/old/old')] });
    await view.settle();
    expect(view.optionsOf('entries')).toEqual(['new']);
    expect(view.control<HTMLInputElement>('folder').value).toBe('kv/new');
  });

  it('does not apply a lookup after the picker is destroyed by a source change', async () => {
    const view = await setup();
    const pending = deferred<SecretRefTest>();
    view.api.testReference.mockReturnValueOnce(pending.promise);
    await view.type('kv/orders');
    await view.click('Load keys');
    view.form.source.set('local');
    await view.settle();
    expect(view.el.querySelector('gw-vault-picker')).toBeNull();
    pending.resolve(keys('password'));
    await view.settle();
    expect(view.picker.keyList()?.state).not.toBe('ready');
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'local', value: 'kv/orders' } });
  });

  it('keeps manual key lookup available when Vault denies listing', async () => {
    const view = await setup();
    view.api.browseVault.mockRejectedValueOnce(new ApiError('unprocessable', 422, {
      detail: 'Vault denied listing. Grant list permission on kv/metadata/dev or enter the reference manually.',
    }));
    await view.click('Browse Vault');
    expect(view.control('browse-status').textContent).toContain('Grant list permission');
    expect(view.control<HTMLSelectElement>('entries').disabled).toBe(true);
    await view.type('kv/dev/orders');
    await view.click('Load keys');
    expect(view.optionsOf('key')).toEqual(['password', 'username']);
  });

  it('shows empty, denied and unavailable states without inserting a key', async () => {
    const view = await setup();
    view.api.browseVault.mockResolvedValueOnce({ path: '', entries: [] });
    await view.click('Browse Vault');
    expect(view.control('browse-status').textContent).toContain('No secrets or folders');
    view.api.testReference.mockResolvedValueOnce(keys())
      .mockResolvedValueOnce({ ...keys(), found: false, reason: 'denied', error: 'Vault denied reading this secret.' })
      .mockRejectedValueOnce(new Error('offline'));
    await view.type('kv/orders');
    await view.click('Load keys');
    expect(view.control('keys-status').textContent).toContain('No selectable keys');
    expect(view.control<HTMLSelectElement>('key').disabled).toBe(true);
    await view.click('Load keys');
    expect(view.control('keys-status').textContent).toContain('Vault denied reading');
    await view.click('Load keys');
    expect(view.control('keys-status').textContent).toContain('Could not load Vault keys');
    expect(view.reference().value).toBe('kv/orders');
  });

  it('offers only reference-compatible key names and explains omitted names', async () => {
    const view = await setup();
    view.api.testReference.mockResolvedValueOnce(keys('password', 'with space', 'nested/key', 'percent%key', 'hash#key', 'query?key', 'back\\slash', 'nonascii-é', 'x'.repeat(257)));
    await view.type('kv/orders');
    await view.click('Load keys');
    expect(view.optionsOf('key')).toEqual(['password']);
    expect(view.control('keys-status').textContent).toContain('Some key names cannot be used');
    await view.select('key', 'password');
    expect(view.reference().value).toBe('kv/orders#password');
  });
});
