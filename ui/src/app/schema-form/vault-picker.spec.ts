import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../core/api/api-error';
import { StoredSecret } from '../core/api/connections.api';
import { SecretStore, SecretsApi } from '../core/api/secrets.api';
import { JsonObject } from '../core/api/types';
import { provideKitIcons } from '../kit/icons';
import { SecretAction, createSchemaForm } from './schema-form';
import { GwSchemaForm } from './schema-form.component';

const SCHEMA: JsonObject = {
  type: 'object', required: ['password'], properties: {
    password: { type: 'string', title: 'Password', writeOnly: true, format: 'password', 'x-gateway-secret': 'value' },
  },
};
let initialStored: readonly StoredSecret[] = [];
let initialSchema = SCHEMA;

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class Host {
  readonly form = createSchemaForm(initialSchema, { idPrefix: 'secret', stored: initialStored });
}

interface ReferenceList {
  readonly references: string[];
  readonly complete: boolean;
  readonly issues: { path: string; reason: string; message: string }[];
}

const store = (id = 'vault-dev'): SecretStore => ({
  id, kind: 'vault', name: id, config: {}, state: 'connected', lastCheckedAt: null,
});
const references = (...refs: string[]): ReferenceList => ({ references: refs, complete: true, issues: [] });
const DEFAULT_REFERENCES = [
  'vault://kv/dev/orders#password',
  'vault://kv/dev/orders#username',
  'vault://kv/stage/billing#password',
];
const saved: StoredSecret = {
  field: '/password', secretId: 'secret-1', name: 'vault://kv/dev/orders#password',
  state: 'reference', version: null, source: 'vault', store: 'vault-dev',
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function mockApi(stores: readonly SecretStore[]) {
  return {
    stores: vi.fn<() => Promise<readonly SecretStore[]>>().mockResolvedValue(stores),
    vaultReferences: vi.fn<(id: string) => Promise<ReferenceList>>().mockResolvedValue(references(...DEFAULT_REFERENCES)),
    browseVault: vi.fn(),
    testReference: vi.fn(),
  };
}

async function setup(options: {
  stores?: readonly SecretStore[];
  saved?: readonly StoredSecret[];
  schema?: JsonObject;
  configure?: (api: ReturnType<typeof mockApi>) => void;
} = {}) {
  initialStored = options.saved ?? [];
  initialSchema = options.schema ?? SCHEMA;
  const api = mockApi(options.stores ?? [store()]);
  options.configure?.(api);
  TestBed.configureTestingModule({ providers: [provideKitIcons(), { provide: SecretsApi, useValue: api }] });
  const fixture = TestBed.createComponent(Host);
  const form = fixture.componentInstance.form;
  form.source.set('vault');
  const actions: SecretAction[] = [];
  form.onSecretAction((action) => actions.push(action));
  const settle = async () => {
    // Store discovery, the shared catalog and the component update have separate promise continuations.
    for (let i = 0; i < 4; i++) { fixture.detectChanges(); await fixture.whenStable(); }
    fixture.detectChanges();
  };
  await settle();
  const el = fixture.nativeElement as HTMLElement;
  const control = <T extends HTMLElement>(suffix: string) => el.querySelector<T>(`#secret-password-vault-${suffix}`)!;
  const reference = () => el.querySelector<HTMLInputElement>('#secret-password')!;
  const click = async (text: string) => {
    const button = [...el.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.trim() === text);
    expect(button, `Button ${text}`).toBeDefined();
    button!.click();
    await settle();
  };
  const type = async (value: string) => {
    reference().focus();
    reference().value = value;
    reference().dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const open = async () => { reference().focus(); reference().click(); await settle(); };
  const key = async (value: string) => {
    reference().focus();
    const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true });
    reference().dispatchEvent(event);
    await settle();
    return event;
  };
  const chooseStore = async (value: string) => {
    const field = control<HTMLSelectElement>('store');
    field.value = value;
    field.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
  };
  const choices = () => [...el.querySelectorAll<HTMLButtonElement>('[role="option"]')]
    .filter((option) => !option.closest('[hidden]'));
  const choiceLabels = () => choices().map((option) => option.textContent?.trim());
  const choose = async (label: string) => {
    const option = choices().find((option) => option.textContent?.trim() === label);
    expect(option, `Option ${label}`).toBeDefined();
    option!.click();
    await settle();
  };
  const status = () => control('status')?.textContent ?? el.textContent ?? '';
  return { api, fixture, form, actions, el, control, reference, click, type, open, key, chooseStore, choices, choiceLabels, choose, status, settle };
}

describe('Vault reference autocomplete', () => {
  it('loads all reference suggestions automatically, filters locally and inserts a complete selected reference', async () => {
    const view = await setup();
    expect(view.api.stores).toHaveBeenCalledTimes(1);
    expect(view.api.vaultReferences).toHaveBeenCalledExactlyOnceWith('vault-dev');
    expect(view.api.browseVault).not.toHaveBeenCalled();
    expect(view.api.testReference).not.toHaveBeenCalled();
    expect(view.reference().value).toBe('');
    expect(view.reference().getAttribute('role')).toBe('combobox');
    expect(view.reference().getAttribute('aria-autocomplete')).toBe('list');
    expect(view.el.textContent).not.toContain('Browse Vault');
    expect(view.el.textContent).not.toContain('Load keys');
    expect(view.el.querySelector('#secret-password-vault-folder')).toBeNull();

    await view.open();
    expect(view.choiceLabels()).toEqual(DEFAULT_REFERENCES.map((ref) => ref.slice('vault://'.length)));
    expect(view.reference().getAttribute('aria-expanded')).toBe('true');
    await view.type('orders#pass');
    expect(view.choiceLabels()).toEqual(['kv/dev/orders#password']);
    await view.choose('kv/dev/orders#password');
    expect(view.reference().value).toBe('kv/dev/orders#password');
    expect(view.reference().getAttribute('aria-expanded')).toBe('false');
    expect(view.form.submit()).toBe(true);
    expect(view.form.secretInputs()).toEqual({ '/password': {
      source: 'reference', ref: 'vault://kv/dev/orders#password', store: 'vault-dev',
    } });
    expect(view.form.value()).toEqual({});
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: 'vault://kv/dev/orders#password', store: 'vault-dev' })]);
    expect(view.api.vaultReferences).toHaveBeenCalledTimes(1);
  });

  it('supports arrow navigation, Enter selection and Escape or Tab dismissal from the input', async () => {
    const view = await setup();
    view.reference().focus();
    await view.key('ArrowDown');
    expect(view.reference().getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(view.reference());
    const first = view.reference().getAttribute('aria-activedescendant');
    expect(view.choices()[0].id).toBe(first);
    await view.key('ArrowDown');
    expect(view.reference().getAttribute('aria-activedescendant')).toBe(view.choices()[1].id);
    expect(document.activeElement).toBe(view.reference());
    const entered = await view.key('Enter');
    expect(entered.defaultPrevented).toBe(true);
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/dev/orders#username' });
    expect(view.reference().getAttribute('aria-expanded')).toBe('false');

    await view.type('password');
    await view.key('Escape');
    expect(view.reference().getAttribute('aria-expanded')).toBe('false');
    expect(view.reference().value).toBe('password');
    await view.key('ArrowUp');
    expect(view.reference().getAttribute('aria-expanded')).toBe('true');
    await view.key('Tab');
    expect(view.reference().getAttribute('aria-expanded')).toBe('false');
    expect(view.reference().value).toBe('password');
  });

  it.each(['kv/dev/orders?version=2#user', 'vault://kv/dev/orders#user?version=2'])(
    'selects a pinned key immediately when input, ArrowDown and Enter occur before another render: %s', async (typed) => {
      const view = await setup();
      await view.type('orders#pass');
      await view.choose('kv/dev/orders#password');
      expect(view.reference().getAttribute('aria-expanded')).toBe('false');

      const field = view.reference();
      field.focus();
      field.value = typed;
      field.dispatchEvent(new Event('input', { bubbles: true }));
      const down = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
      const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      field.dispatchEvent(down);
      field.dispatchEvent(enter);

      expect(down.defaultPrevented).toBe(true);
      expect(enter.defaultPrevented).toBe(true);
      expect(view.form.secretInputs()['/password']).toEqual({
        source: 'reference', ref: 'vault://kv/dev/orders?version=2#username', store: 'vault-dev',
      });
      expect(view.form.submitted()).toBe(false);
      expect(view.actions).toEqual([]);
      await view.settle();
      expect(field.value).toBe('kv/dev/orders?version=2#username');
      expect(field.getAttribute('aria-expanded')).toBe('false');
      expect(view.api.vaultReferences).toHaveBeenCalledTimes(1);
    },
  );

  it('opens matching suggestions after loading finishes when the user already started typing', async () => {
    const pending = deferred<readonly SecretStore[]>();
    const view = await setup({ configure: (api) => api.stores.mockReturnValueOnce(pending.promise) });
    expect(view.status()).toContain('Loading Vault stores');
    await view.type('orders#pass');
    pending.resolve([store()]);
    await view.settle();
    expect(view.reference().value).toBe('orders#pass');
    expect(view.reference().getAttribute('aria-expanded')).toBe('true');
    expect(view.choiceLabels()).toEqual(['kv/dev/orders#password']);
  });

  it('waits for an explicit store choice when more than one Vault store exists, then loads automatically', async () => {
    const view = await setup({ stores: [store(), store('vault-prod')] });
    expect(view.api.vaultReferences).not.toHaveBeenCalled();
    expect(view.control<HTMLSelectElement>('store').value).toBe('');
    await view.chooseStore('vault-prod');
    expect(view.api.vaultReferences).toHaveBeenCalledExactlyOnceWith('vault-prod');
    await view.type('billing');
    await view.choose('kv/stage/billing#password');
    expect(view.form.secretInputs()['/password']).toEqual({ source: 'reference', ref: 'vault://kv/stage/billing#password', store: 'vault-prod' });
  });

  it('normalizes a saved store name to its ID before loading suggestions and saving a replacement', async () => {
    const view = await setup({ stores: [{ ...store('vault-123'), name: 'Development Vault' }], saved: [{ ...saved, store: 'Development Vault' }] });
    expect(view.api.vaultReferences).not.toHaveBeenCalled();
    await view.click('Change');
    expect(view.api.vaultReferences).toHaveBeenCalledExactlyOnceWith('vault-123');
    await view.type('orders#pass');
    await view.choose('kv/dev/orders#password');
    expect(view.form.secretInputs()['/password']).toEqual({ source: 'reference', ref: saved.name, store: 'vault-123' });
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: saved.name, store: 'vault-123' })]);
  });

  it('ignores a late catalog response from a previously selected store', async () => {
    const earlier = deferred<ReferenceList>();
    const view = await setup({ stores: [store(), store('vault-prod')], configure: (api) => {
      api.vaultReferences.mockReturnValueOnce(earlier.promise)
        .mockResolvedValueOnce(references('vault://kv/prod/orders#password'));
    } });
    await view.chooseStore('vault-dev');
    await view.chooseStore('vault-prod');
    earlier.resolve(references('vault://kv/dev/orders#password'));
    await view.settle();
    await view.open();
    expect(view.choiceLabels()).toEqual(['kv/prod/orders#password']);
    await view.choose('kv/prod/orders#password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/prod/orders#password', store: 'vault-prod' });
  });

  it('does not apply a pending catalog after switching away from Vault', async () => {
    const pending = deferred<ReferenceList>();
    const view = await setup({ configure: (api) => api.vaultReferences.mockReturnValueOnce(pending.promise) });
    await view.type('manual-password');
    view.form.source.set('local');
    await view.settle();
    expect(view.el.querySelector('gw-vault-picker')).toBeNull();
    pending.resolve(references('vault://kv/dev/orders#password'));
    await view.settle();
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'local', value: 'manual-password' } });
    expect(view.el.querySelector('[role="option"]')).toBeNull();
  });

  it('keeps accessible suggestions usable while explaining a denied subtree', async () => {
    const message = 'Grant list permission on secret/metadata/prod to include its keys.';
    const view = await setup({ configure: (api) => api.vaultReferences.mockResolvedValueOnce({
      ...references('vault://kv/dev/orders#password'), complete: false,
      issues: [{ path: 'secret/prod', reason: 'denied', message }],
    }) });
    expect(view.el.textContent).toContain(message);
    await view.open();
    expect(view.choiceLabels()).toEqual(['kv/dev/orders#password']);
    await view.choose('kv/dev/orders#password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/dev/orders#password' });
  });

  it('keeps a manually entered reference testable when Vault cannot list keys', async () => {
    const message = "The token cannot list secret/metadata. Grant list permission on this path.";
    const view = await setup({ configure: (api) => api.vaultReferences.mockResolvedValueOnce({
      references: [], complete: false, issues: [{ path: 'secret', reason: 'denied', message }],
    }) });
    expect(view.el.textContent).toContain(message);
    await view.type('vault://secret/dev/orders#password');
    expect(view.reference().value).toBe('secret/dev/orders#password');
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: 'vault://secret/dev/orders#password', store: 'vault-dev' })]);
    expect(view.form.submit()).toBe(true);
  });

  it('shows an empty catalog separately from a query that has no matches', async () => {
    const view = await setup({ configure: (api) => api.vaultReferences.mockResolvedValueOnce(references()) });
    expect(view.status()).toMatch(/No .* (keys|references)/i);
    expect(view.reference().value).toBe('');
    view.api.vaultReferences.mockResolvedValueOnce(references('vault://kv/dev/orders#password'));
    await view.click('Refresh list');
    await view.type('missing');
    expect(view.status()).toMatch(/No .*match/i);
    expect(view.choices()).toHaveLength(0);
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://missing' });
  });

  it('refreshes the catalog without overwriting a manual edit', async () => {
    const view = await setup();
    await view.type('kv/manual#password');
    view.api.vaultReferences.mockResolvedValueOnce(references('vault://kv/new#password'));
    await view.click('Refresh list');
    expect(view.api.vaultReferences).toHaveBeenCalledTimes(2);
    expect(view.reference().value).toBe('kv/manual#password');
    await view.type('');
    await view.open();
    expect(view.choiceLabels()).toEqual(['kv/new#password']);
  });

  it('searches the entire loaded catalog while limiting the visible suggestion list', async () => {
    const all = Array.from({ length: 75 }, (_, i) => `vault://kv/dev/secret-${String(i).padStart(2, '0')}#password`);
    const view = await setup({ configure: (api) => api.vaultReferences.mockResolvedValueOnce(references(...all)) });
    await view.open();
    expect(view.choices()).toHaveLength(50);
    expect(view.status()).toContain('75 matching keys');
    expect(view.status()).toContain('showing the first 50');
    await view.type('secret-74');
    expect(view.choiceLabels()).toEqual(['kv/dev/secret-74#password']);
    await view.choose('kv/dev/secret-74#password');
    expect(view.form.secretInputs()['/password']).toMatchObject({ ref: all[74] });
    expect(view.api.vaultReferences).toHaveBeenCalledTimes(1);
  });

  it.each([
    { error: new Error('offline'), message: 'Could not load Vault stores' },
    { error: new ApiError('unprocessable', 422, { detail: 'Unlock the configured Vault store before loading keys.' }), message: 'Unlock the configured Vault store' },
  ])('retries store discovery after an initial failure: $message', async ({ error, message }) => {
    const view = await setup({ configure: (api) => api.stores.mockRejectedValueOnce(error) });
    expect(view.el.textContent).toContain(message);
    expect(view.api.vaultReferences).not.toHaveBeenCalled();
    await view.click('Refresh list');
    expect(view.api.stores).toHaveBeenCalledTimes(2);
    expect(view.api.vaultReferences).toHaveBeenCalledExactlyOnceWith('vault-dev');
  });

  it('retries a failed reference load and does not cache the failure', async () => {
    const view = await setup({ configure: (api) => api.vaultReferences.mockRejectedValueOnce(new Error('offline')) });
    expect(view.el.textContent).toMatch(/Could not load Vault (keys|references)/);
    await view.click('Refresh list');
    expect(view.api.vaultReferences).toHaveBeenCalledTimes(2);
    await view.open();
    expect(view.choiceLabels()).toHaveLength(DEFAULT_REFERENCES.length);
  });

  it('explains when no Vault store is connected without attempting to load references', async () => {
    const view = await setup({ stores: [] });
    expect(view.el.textContent).toContain('Connect Vault or OpenBao in Secrets');
    expect(view.api.vaultReferences).not.toHaveBeenCalled();
    expect(view.reference().value).toBe('');
  });

  it.each(['kv/dev/orders?version=2#pass', 'vault://kv/dev/orders#pass?version=2'])(
    'preserves a pinned version when choosing another key of the same secret: %s', async (typed) => {
      const view = await setup();
      await view.type(typed);
      await view.choose('kv/dev/orders#password');
      expect(view.reference().value).toBe('kv/dev/orders?version=2#password');
      expect(view.form.secretInputs()['/password']).toMatchObject({ ref: 'vault://kv/dev/orders?version=2#password' });
    },
  );

  it.each(['discard', 'cancel'] as const)('restores the saved store after %s of a replacement', async (action) => {
    const view = await setup({ stores: [store(), store('vault-prod')], saved: [saved] });
    await view.click('Change');
    await view.chooseStore('vault-prod');
    await view.type('kv/replacement#password');
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

  it('uses refreshed stored metadata instead of the abandoned draft store', async () => {
    const view = await setup({ stores: [store(), store('vault-prod')], saved: [saved] });
    await view.click('Change');
    await view.chooseStore('vault-prod');
    await view.type('kv/replacement#password');
    const refreshed = { ...saved, name: 'vault://kv/current#password' };
    view.form.setStored([refreshed]);
    await view.settle();
    expect(view.form.secretInputs()).toEqual({});
    expect(view.form.vaultStores()).toEqual({});
    await view.click('Test reference');
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: refreshed.name, store: 'vault-dev' })]);
  });

  it('shares automatic catalog loading between multiple secret fields', async () => {
    const view = await setup({ schema: { ...SCHEMA, properties: {
      ...(SCHEMA['properties'] as JsonObject),
      token: { type: 'string', title: 'Token', writeOnly: true, format: 'password', 'x-gateway-secret': 'value' },
    } } });
    expect(view.el.querySelectorAll('gw-vault-picker')).toHaveLength(2);
    expect(view.api.stores).toHaveBeenCalledTimes(1);
    expect(view.api.vaultReferences).toHaveBeenCalledExactlyOnceWith('vault-dev');
    expect(view.form.secretInputs()).toEqual({});
  });
});
