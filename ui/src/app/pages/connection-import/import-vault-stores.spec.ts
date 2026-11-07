import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { ConnectionImportsApi, ImportStoreConnect, ImportStoreProposal } from '../../core/api/connection-imports.api';
import { SecretStore, SecretStoreTest, SecretsApi } from '../../core/api/secrets.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ImportVaultStores } from './import-vault-stores';

const proposal: ImportStoreProposal = {
  id: 'vault-dev', kind: 'vault', name: 'vault-dev',
  config: { address: 'https://vault.example.test', namespace: 'team', auth: { method: 'token' } },
  evidence: ['config/application.yml: vault.address'], warnings: [], credentialHints: [],
  secretSources: [{ field: '/auth/token', source: 'file', path: '/workspace/project/vault.json', jsonPointer: '/token' }],
};
const saved: SecretStore = { id: 'st_vault', kind: 'vault', name: 'vault-dev', config: proposal.config, state: 'connected', lastCheckedAt: null, verified: true };

describe('Vault setup during connection import', () => {
  let fixture: ComponentFixture<ImportVaultStores>;
  let component: ImportVaultStores;
  let stores: SecretStore[];
  let secrets: { stores: ReturnType<typeof vi.fn>; createStore: ReturnType<typeof vi.fn>; testStore: ReturnType<typeof vi.fn> };
  let imports: { connectStore: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    stores = [];
    secrets = {
      stores: vi.fn(async () => stores),
      createStore: vi.fn(async () => saved),
      testStore: vi.fn(async () => ({ ok: true, latencyMs: 1, server: null, identity: null, permissions: [], hints: [], checks: [], error: null, reason: null })),
    };
    imports = { get: vi.fn(async () => ({ manifest: { stores: [proposal] } })), connectStore: vi.fn(async (_session: string, _proposal: string, request: ImportStoreConnect) => {
      if ('existingStore' in request) return stores.find(store => store.id === request.existingStore)!;
      const store = { ...saved, name: request.name, config: request.config };
      stores = [store]; return store;
    }) };
    await TestBed.configureTestingModule({ imports: [ImportVaultStores], providers: [
      provideHttpClient(), provideRouter([]), provideKitIcons(),
      { provide: SecretsApi, useValue: secrets }, { provide: ConnectionImportsApi, useValue: imports },
      { provide: DesktopBridge, useValue: { available: false, vaultTokenPickerAvailable: false } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(ImportVaultStores);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('sessionId', 'ci_abcdefgh1234');
    fixture.componentRef.setInput('proposals', [proposal]);
    await settle();
  });

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) { fixture.detectChanges(); await fixture.whenStable(); }
    fixture.detectChanges();
  }
  function root(): HTMLElement { return fixture.nativeElement; }
  function input(id: string): HTMLInputElement { return root().querySelector(`#${id}`)!; }
  async function type(id: string, value: string): Promise<void> {
    input(id).value = value; input(id).dispatchEvent(new Event('input', { bubbles: true })); await settle();
  }
  async function open(): Promise<void> { root().querySelector<HTMLButtonElement>('button[aria-haspopup="dialog"]')!.click(); await settle(); }
  async function submit(): Promise<void> {
    root().querySelector('#dlg-connect-store form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await waitForIdle();
  }
  async function waitForIdle(): Promise<void> {
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(root().querySelector('#dlg-connect-store button[type="submit"]')!.getAttribute('aria-busy')).not.toBe('true');
    });
    await settle();
  }
  function dialogButton(dialog: string, text: string): HTMLButtonElement {
    return Array.from(root().querySelectorAll<HTMLButtonElement>(`${dialog} button`)).find(button => button.textContent?.trim() === text)!;
  }
  async function openHttpHomeLogin(): Promise<void> {
    fixture.componentRef.setInput('proposals', [{ ...proposal, config: { address: 'http://vault.example.test', auth: { method: 'cli-token' } }, secretSources: [] }]);
    await settle(); await open(); await submit();
  }
  function prepared(): HTMLInputElement { return root().querySelector('#dlg-connect-store input[type="checkbox"]')!; }

  it('prefills safe settings and keeps prepared authentication without connecting before confirmation', async () => {
    expect(secrets.stores).toHaveBeenCalledTimes(1);
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(secrets.testStore).not.toHaveBeenCalled();
    expect(root().querySelector<HTMLElement>('#import-vault-details-vault-dev')!.hidden).toBe(true);
    await open();
    expect(input('vault-name').value).toBe('vault-dev');
    expect(input('vault-address').value).toBe(proposal.config.address);
    expect(input('vault-ns').value).toBe('team');
    expect(prepared().checked).toBe(true);
    expect(input('vault-token').value).toBe('');
    expect(input('vault-token').disabled).toBe(true);
    expect(root().textContent).toContain('/workspace/project/vault.json');
    expect(root().textContent).toContain('/token');
    expect(imports.connectStore).not.toHaveBeenCalled();
    await submit();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, {
      name: proposal.name, config: proposal.config, preparedFields: ['/auth/token'],
    });
    expect(secrets.testStore).toHaveBeenCalledWith(saved.id);
    expect(secrets.createStore).not.toHaveBeenCalled();
    expect(root().textContent).toContain('Selected for this import');
  });

  it('lets the user replace prepared authentication with a write-only value', async () => {
    await open();
    prepared().click(); await settle();
    input('vault-token').value = 'new-fixture-token';
    await submit();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, expect.objectContaining({ secrets: { '/auth/token': 'new-fixture-token' }, preparedFields: [] }));
  });

  it('drops prepared consent after the target changes and remembers the corrected store after connection', async () => {
    await open();
    await type('vault-address', 'https://corrected.example.test');
    await type('vault-ns', '');
    expect(prepared().checked).toBe(false);
    await submit();
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(root().textContent).toContain('Enter the Vault token');
    prepared().click(); await settle();
    await submit();
    expect(component.match(proposal)?.id).toBe(saved.id);
    expect(component.address(proposal)).toBe('https://corrected.example.test');
    expect(component.namespace(proposal)).toBe('');
    expect(root().textContent).toContain('Selected for this import');
    expect(root().querySelector('article button[aria-haspopup="dialog"]')).toBeNull();
  });

  it('uses an existing normalized address and namespace only after an explicit choice', async () => {
    stores = [{ ...saved, name: 'existing-vault', config: { address: 'https://VAULT.example.test:443/', namespace: '/team/' }, state: 'failing', lastError: 'Offline during the last test' }];
    await component.refresh(); await settle();
    expect(root().textContent).toContain('Failing');
    expect(root().textContent).toContain('Offline during the last test');
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(root().querySelector('article button[aria-haspopup="dialog"]')).toBeNull();
    Array.from(root().querySelectorAll('button')).find(button => button.textContent?.includes('Use existing'))!.click(); await settle();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, { existingStore: saved.id });
    expect(secrets.createStore).not.toHaveBeenCalled();
    expect(secrets.testStore).not.toHaveBeenCalled();
  });

  it('refuses a duplicate that appears after the reviewed dialog opens', async () => {
    await open(); stores = [saved];
    await submit();
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(secrets.createStore).not.toHaveBeenCalled();
    expect(secrets.testStore).not.toHaveBeenCalled();
    expect(root().textContent).toContain('already configured as vault-dev');
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(true);
  });

  it('recovers a lost Connect response for a corrected address without creating another store', async () => {
    await open(); await type('vault-address', 'https://corrected.example.test');
    prepared().click(); await settle();
    imports.connectStore.mockImplementationOnce(async (_session: string, _proposal: string, request: ImportStoreConnect) => {
      if ('existingStore' in request) throw new Error('Expected the reviewed create request');
      stores = [{ ...saved, config: request.config }];
      throw new Error('Response lost');
    });
    await submit();
    expect(secrets.testStore).not.toHaveBeenCalled();
    expect(root().textContent).toContain('Response lost');
    imports.get.mockResolvedValueOnce({ manifest: { stores: [{ ...proposal, connectedStore: saved.id }] } });
    await component.refresh(); await settle();
    await submit();
    expect(imports.connectStore).toHaveBeenCalledTimes(2);
    expect(imports.connectStore).toHaveBeenLastCalledWith('ci_abcdefgh1234', proposal.id, { existingStore: saved.id });
    expect(secrets.createStore).not.toHaveBeenCalled();
    expect(secrets.testStore).toHaveBeenCalledTimes(1);
    expect(component.address(proposal)).toBe('https://corrected.example.test');
    expect(root().textContent).toContain('Selected for this import');
  });

  it('keeps the normal plaintext HTTP confirmation before sending a prepared credential', async () => {
    fixture.componentRef.setInput('proposals', [{ ...proposal, config: { ...proposal.config, address: 'http://vault.example.test' } }]); await settle();
    await open(); await submit();
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(root().querySelector<HTMLDialogElement>('#dlg-vault-http')!.open).toBe(true);
    Array.from(root().querySelectorAll('#dlg-vault-http button')).find(button => button.textContent?.trim() === 'Connect over HTTP')!.dispatchEvent(new MouseEvent('click', { bubbles: true })); await settle();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, expect.objectContaining({ config: expect.objectContaining({ allowPlainHttp: true }), preparedFields: ['/auth/token'] }));
  });

  it('opens a blank reviewed Vault form for legacy references without proposals', async () => {
    fixture.componentRef.setInput('proposals', []); await settle(); await open();
    expect(input('vault-address').value).toBe('');
    expect(root().querySelector('#dlg-connect-store input[type="checkbox"]')).toBeNull();
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(secrets.createStore).not.toHaveBeenCalled();
  });

  it('offers the standard home login method without reading or testing it before Connect', async () => {
    fixture.componentRef.setInput('proposals', [{ ...proposal, config: { ...proposal.config, auth: { method: 'cli-token' } }, secretSources: [] }]); await settle(); await open();
    expect((root().querySelector('#vault-method') as HTMLSelectElement).value).toBe('cli-token');
    expect(input('vault-token')).toBeNull();
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(secrets.testStore).not.toHaveBeenCalled();
    await submit();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, expect.objectContaining({ config: expect.objectContaining({ auth: { method: 'cli-token' } }), preparedFields: [] }));
  });

  it('lets HTTP home-token confirmation be cancelled without starting a connect or test', async () => {
    await openHttpHomeLogin();
    expect(root().querySelector<HTMLDialogElement>('#dlg-vault-http')!.open).toBe(true);
    expect(component.busy()).toBeNull();
    expect(imports.connectStore).not.toHaveBeenCalled();
    dialogButton('#dlg-vault-http', 'Cancel').click(); await settle();
    expect(root().querySelector<HTMLDialogElement>('#dlg-vault-http')!.open).toBe(false);
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(true);
    expect(dialogButton('#dlg-connect-store', 'Cancel').disabled).toBe(false);
    expect((root().querySelector('#vault-method') as HTMLSelectElement).value).toBe('cli-token');
    expect(input('vault-address').value).toBe('http://vault.example.test');
    expect(imports.connectStore).not.toHaveBeenCalled();
    expect(secrets.testStore).not.toHaveBeenCalled();
  });

  it.each(['failed result', 'rejected request'])('releases HTTP home-token setup after a saved store test returns a %s', async (outcome) => {
    let finish!: (result: SecretStoreTest) => void;
    let fail!: (error: Error) => void;
    secrets.testStore.mockImplementationOnce(() => new Promise<SecretStoreTest>((resolve, reject) => { finish = resolve; fail = reject; }));
    const busy = vi.fn(); component.busyChange.subscribe(busy);
    const changed = vi.fn(); component.changed.subscribe(changed);
    await openHttpHomeLogin();
    dialogButton('#dlg-vault-http', 'Connect over HTTP').click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(secrets.testStore).toHaveBeenCalledTimes(1); });
    expect(root().querySelector<HTMLDialogElement>('#dlg-vault-http')!.open).toBe(false);
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(true);
    expect(dialogButton('#dlg-connect-store', 'Test and connect').getAttribute('aria-busy')).toBe('true');
    expect(dialogButton('#dlg-connect-store', 'Cancel').disabled).toBe(true);
    expect(imports.connectStore).toHaveBeenCalledExactlyOnceWith('ci_abcdefgh1234', proposal.id, {
      name: proposal.name, config: { address: 'http://vault.example.test', auth: { method: 'cli-token' }, allowPlainHttp: true }, preparedFields: [],
    });
    expect(changed).not.toHaveBeenCalled();
    if (outcome === 'failed result') {
      finish({ ok: false, latencyMs: 10000, server: null, identity: null, permissions: [], hints: [], checks: [], error: 'Vault request timed out.', reason: 'timeout' });
    } else {
      fail(new Error('The test response was interrupted.'));
    }
    await waitForIdle();
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(false);
    expect(component.busy()).toBeNull();
    expect(busy.mock.calls.map(call => call[0])).toEqual([true, false]);
    expect(changed).toHaveBeenCalledTimes(1);
    expect(imports.connectStore).toHaveBeenCalledTimes(1);
    expect(component.match(proposal)?.id).toBe(saved.id);
    expect(root().textContent).toContain('Selected for this import');
    const toast = TestBed.inject(ToastService).toasts().at(-1)!;
    expect(toast.tone).toBe('warning');
    expect(toast.title).toContain(outcome === 'failed result' ? 'test failed' : 'saved, but its test did not finish');
    expect(toast.body).not.toContain('resolves now');
  });

  it('restores HTTP home-token controls and reviewed settings when the connect request fails', async () => {
    imports.connectStore.mockRejectedValueOnce(new Error('Vault sign-in did not finish. Try again.'));
    await openHttpHomeLogin();
    dialogButton('#dlg-vault-http', 'Connect over HTTP').click();
    await vi.waitFor(() => expect(imports.connectStore).toHaveBeenCalledTimes(1));
    await waitForIdle();
    expect(component.busy()).toBeNull();
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(true);
    expect(dialogButton('#dlg-connect-store', 'Cancel').disabled).toBe(false);
    expect((root().querySelector('#vault-method') as HTMLSelectElement).value).toBe('cli-token');
    expect(input('vault-address').value).toBe('http://vault.example.test');
    expect(root().textContent).toContain('Vault sign-in did not finish. Try again.');
    expect(secrets.testStore).not.toHaveBeenCalled();
    dialogButton('#dlg-connect-store', 'Cancel').click(); await settle();
    expect(root().querySelector<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(false);
  });

  it('prefills AppRole and clears prepared consent when the login identity changes', async () => {
    fixture.componentRef.setInput('proposals', [{ ...proposal, config: { ...proposal.config, auth: { method: 'approle', roleId: 'discovered-role' } }, secretSources: [{ field: '/auth/secretId', source: 'local' }] }]);
    await settle(); await open();
    expect(input('vault-role').value).toBe('discovered-role');
    expect(prepared().checked).toBe(true);
    expect(input('vault-secret-id').value).toBe('');
    await type('vault-role', 'reviewed-role');
    expect(prepared().checked).toBe(false);
    await submit();
    expect(imports.connectStore).not.toHaveBeenCalled();
  });

  it('preserves native file grants for a persistent token-file chosen by the user', async () => {
    Object.assign(TestBed.inject(DesktopBridge), { vaultTokenPickerAvailable: true, chooseVaultTokenFile: vi.fn(async () => ({ token: 'fg_fixture', path: '/private/example/vault-token' })) });
    fixture.componentRef.setInput('proposals', [{ ...proposal, config: { ...proposal.config, auth: { method: 'token-file' } }, secretSources: [] }]);
    await settle(); await open();
    root().querySelector<HTMLButtonElement>('#vault-token-choose')!.click(); await settle();
    expect(input('vault-token-path').value).toBe('/private/example/vault-token');
    expect(imports.connectStore).not.toHaveBeenCalled();
    await submit();
    expect(imports.connectStore).toHaveBeenCalledWith('ci_abcdefgh1234', proposal.id, expect.objectContaining({ config: expect.objectContaining({ auth: { method: 'token-file' } }), files: { '/auth/file': 'fg_fixture' }, preparedFields: [] }));
  });
});
