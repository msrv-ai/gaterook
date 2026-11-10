import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EndpointShareFiles, EndpointShareReview } from '../../core/desktop/endpoint-share';
import { ConnectionsStore } from '../../core/stores/connections.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { provideKitIcons } from '../../kit/icons';
import { EndpointImportPage } from './endpoint-import.page';

const REVIEW: EndpointShareReview = {
  handle: 'private-review-handle',
  preview: {
    endpoint: { slug: 'dev', description: 'Shared dev access', access: 'standard', instructions: null, settings: {} },
    suggestedSlug: 'dev-imported', coreTools: [], warnings: ['Client access is not transferred.'],
    connections: [
      { id: 'db', name: 'db', suggestedName: 'db-imported', type: 'postgres', env: 'dev', config: { host: 'db.example.test' }, templateCount: 0, secretCount: 1, omittedSecretFields: [], supported: true, warnings: [], tools: [{ toolKey: 'sql.query', mode: 'READ_ONLY', overrides: null }] },
      { id: 'logs', name: 'logs', suggestedName: 'logs', type: 'logs', env: 'dev', config: {}, templateCount: 0, secretCount: 0, omittedSecretFields: ['token'], supported: true, warnings: [], tools: [] },
      { id: 'mcp', name: 'mcp', suggestedName: 'mcp', type: 'mcp-server', env: 'dev', config: {}, templateCount: 0, secretCount: 0, omittedSecretFields: [], supported: false, warnings: ['Set up this server locally.'], tools: [] },
    ],
  },
};

describe('Shared endpoint review', () => {
  let fixture: ComponentFixture<EndpointImportPage>;
  let root: HTMLElement;
  let files: EndpointShareFiles;
  const desktop = { endpointShareAvailable: true, chooseEndpointShare: vi.fn(), previewEndpointShare: vi.fn(), importEndpointShare: vi.fn(), discardEndpointShare: vi.fn() };
  const get = <T extends Element = HTMLElement>(selector: string) => root.querySelector<T>(selector)!;
  const button = (name: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === name)!;
  async function settle(): Promise<void> { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); }
  async function click(name: string): Promise<void> { button(name).click(); await settle(); }
  async function unlock(): Promise<void> {
    get<HTMLInputElement>('#share-import-pass').value = 'a unique shared passphrase';
    get('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
  }
  beforeEach(async () => {
    desktop.endpointShareAvailable = true;
    desktop.chooseEndpointShare.mockReset().mockResolvedValue({ fileName: 'dev.grshare' });
    desktop.previewEndpointShare.mockReset().mockResolvedValue(REVIEW);
    desktop.importEndpointShare.mockReset().mockResolvedValue({ endpointSlug: 'dev-imported', connections: [{ id: 'new-db', name: 'db-imported', needsSetup: false }], warnings: [] });
    desktop.discardEndpointShare.mockReset().mockResolvedValue(undefined);
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(),
      { provide: DesktopBridge, useValue: desktop },
      { provide: ConnectionsStore, useValue: { load: vi.fn(async () => undefined) } },
      { provide: EndpointsStore, useValue: { load: vi.fn(async () => undefined) } },
    ] });
    files = TestBed.inject(EndpointShareFiles);
    fixture = TestBed.createComponent(EndpointImportPage);
    root = fixture.nativeElement;
    document.body.appendChild(root);
    await settle();
  });
  afterEach(() => { fixture.destroy(); document.body.innerHTML = ''; });

  it('opening a file never unlocks or imports it and validates the passphrase', async () => {
    files.select({ fileName: 'received.grshare' }); await settle();
    expect(root.textContent).toContain('received.grshare');
    expect(desktop.previewEndpointShare).not.toHaveBeenCalled();
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
    get('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle();
    expect(get('#share-import-pass').getAttribute('aria-invalid')).toBe('true');
    expect(desktop.previewEndpointShare).not.toHaveBeenCalled();
  });

  it('previews before import and includes available secrets so complete selected connections are immediately on', async () => {
    await click('Choose .grshare file'); await unlock();
    expect(desktop.previewEndpointShare).toHaveBeenCalledExactlyOnceWith('a unique shared passphrase');
    expect(root.querySelector('#share-import-pass')).toBeNull();
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
    expect(get<HTMLInputElement>('#share-import-secrets').checked).toBe(true);
    expect(get<HTMLInputElement>('[data-connection-id=mcp]').disabled).toBe(true);
    expect(root.textContent).toContain('db-imported');
    get<HTMLInputElement>('[data-connection-id=logs]').click(); await settle();
    await click('Import selected items');
    expect(desktop.importEndpointShare).toHaveBeenCalledExactlyOnceWith({ handle: REVIEW.handle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: true, endpointSlug: 'dev-imported' });
    expect(root.textContent).toContain('Selected items imported');
    expect(get('.gw-badge--success').textContent).toBe('On');
    expect(root.querySelector('.gw-badge--warning')).toBeNull();
    expect(root.textContent).not.toContain('save, then enable');
  });

  it('shows the note on trust settings a file carries next to a connection that can still be imported', async () => {
    const note = 'Trust settings in this file are not imported: /hostKeys/pinned. The connection is imported off, '
      + 'with Setup required, until you confirm them on this computer with Test connection.';
    const [db, ...others] = REVIEW.preview.connections;
    const connections = [{ ...db, warnings: [note] }, ...others];
    desktop.previewEndpointShare.mockResolvedValueOnce({ ...REVIEW, preview: { ...REVIEW.preview, connections } });
    await click('Choose .grshare file'); await unlock();
    const checkbox = get<HTMLInputElement>('[data-connection-id=db]');
    expect(checkbox.disabled).toBe(false);
    expect(checkbox.checked).toBe(true);
    expect(checkbox.closest('.gw-stack')?.textContent).toContain(note);
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
  });

  it('can import connections and secrets while skipping the endpoint, and prevents importing an empty selection', async () => {
    await click('Choose .grshare file'); await unlock();
    get<HTMLInputElement>('#share-import-endpoint').click();
    get<HTMLInputElement>('[data-connection-id=db]').click();
    get<HTMLInputElement>('[data-connection-id=logs]').click(); await settle();
    await click('Import selected items');
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
    expect(get<HTMLInputElement>('#share-import-secrets').disabled).toBe(true);
    get<HTMLInputElement>('[data-connection-id=db]').click(); await settle();
    expect(get<HTMLInputElement>('#share-import-secrets').checked).toBe(true);
    await click('Import selected items');
    expect(desktop.importEndpointShare).toHaveBeenCalledExactlyOnceWith({ handle: REVIEW.handle, connectionIds: ['db'], includeEndpoint: false, includeSecrets: true });
  });

  it('lets the recipient omit credentials and identifies only the incomplete connections as off', async () => {
    desktop.importEndpointShare.mockResolvedValueOnce({ endpointSlug: 'dev-imported', connections: [
      { id: 'new-db', name: 'db-imported', needsSetup: true },
      { id: 'new-public', name: 'public-api', needsSetup: false },
    ], warnings: [] });
    await click('Choose .grshare file'); await unlock();
    get<HTMLInputElement>('#share-import-secrets').click(); await settle();
    await click('Import selected items');
    expect(desktop.importEndpointShare).toHaveBeenCalledWith(expect.objectContaining({ includeSecrets: false }));
    expect(get('.gw-badge--warning').textContent).toBe('Setup required · Off');
    expect(get('.gw-badge--success').textContent).toBe('On');
  });

  it('never claims to include secrets when the selected connections have none in the file', async () => {
    await click('Choose .grshare file'); await unlock();
    get<HTMLInputElement>('[data-connection-id=db]').click(); await settle();
    expect(get<HTMLInputElement>('#share-import-secrets').checked).toBe(false);
    expect(get<HTMLInputElement>('#share-import-secrets').disabled).toBe(true);
    await click('Import selected items');
    expect(desktop.importEndpointShare).toHaveBeenCalledWith(expect.objectContaining({ connectionIds: ['logs'], includeSecrets: false }));
  });

  const permissionReview = { confirmationRequired: {
    confirm: 'danger', status: 409, title: 'Review database permissions', detail: 'An account has elevated permissions.',
    errors: [{ field: '/connections/db', message: 'db-imported can modify production data.' }],
  } };

  it('retains the unlocked review and requires an explicit batch permission confirmation before retrying', async () => {
    desktop.importEndpointShare.mockResolvedValueOnce(permissionReview);
    await click('Choose .grshare file'); await unlock();
    await click('Import selected items');
    expect(desktop.importEndpointShare).toHaveBeenCalledTimes(1);
    expect(root.querySelector('#share-review-title')).not.toBeNull();
    expect(root.querySelector('#share-import-pass')).toBeNull();
    expect(get('#share-import-confirmation').textContent).toContain('db-imported can modify production data.');
    expect(root.textContent).not.toContain('Selected items imported');
    await click('Import with these permissions');
    expect(desktop.importEndpointShare).toHaveBeenLastCalledWith({
      handle: REVIEW.handle, connectionIds: ['db', 'logs'], includeEndpoint: true, includeSecrets: true,
      endpointSlug: 'dev-imported', confirm: 'danger',
    });
    expect(root.textContent).toContain('Selected items imported');
    expect(desktop.previewEndpointShare).toHaveBeenCalledTimes(1);
  });

  it.each(['secrets', 'endpoint', 'slug', 'connections'])('requires a fresh permission review when %s selection changes', async (change) => {
    desktop.importEndpointShare.mockResolvedValueOnce(permissionReview);
    await click('Choose .grshare file'); await unlock();
    await click('Import selected items');
    if (change === 'slug') {
      const name = get<HTMLInputElement>('#share-import-slug');
      name.value = 'another-endpoint'; name.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      get<HTMLInputElement>(change === 'connections' ? '[data-connection-id=logs]' : '#share-import-' + change).click();
    }
    await settle();
    expect(root.querySelector('#share-import-confirmation')).toBeNull();
    await click('Import selected items');
    expect(desktop.importEndpointShare.mock.calls[1][0].confirm).toBeUndefined();
  });

  it('clears the consumed review after failed import and asks for the passphrase again', async () => {
    desktop.importEndpointShare.mockRejectedValue(new Error('The endpoint name is already in use.'));
    await click('Choose .grshare file'); await unlock();
    await click('Import selected items');
    expect(root.textContent).toContain('Unlock the file again');
    expect(get<HTMLInputElement>('#share-import-pass').value).toBe('');
    expect(root.querySelector('#share-review-title')).toBeNull();
  });

  it('forgets the selected display name when leaving a completed import', async () => {
    await click('Choose .grshare file'); await unlock();
    await click('Import selected items');
    fixture.destroy();
    expect(files.selected()).toBeNull();
  });

  it('discards a stale preview when a different OS file arrives during decryption', async () => {
    let finish!: (review: EndpointShareReview) => void;
    desktop.previewEndpointShare.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await click('Choose .grshare file'); await unlock();
    files.select({ fileName: 'new.grshare' }); await settle();
    finish(REVIEW); await settle();
    expect(desktop.discardEndpointShare).toHaveBeenCalledWith(REVIEW.handle);
    expect(root.textContent).toContain('new.grshare');
    expect(root.querySelector('#share-review-title')).toBeNull();
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
  });

  it('keeps a new OS file selected if an earlier import completes afterwards', async () => {
    let finish!: (result: object) => void;
    desktop.importEndpointShare.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await click('Choose .grshare file'); await unlock();
    await click('Import selected items');
    files.select({ fileName: 'next.grshare' }); await settle();
    finish({ endpointSlug: 'dev-imported', connections: [], warnings: [] }); await settle();
    expect(root.textContent).toContain('next.grshare');
    expect(root.textContent).not.toContain('Selected items imported');
  });

  it('cancels without importing, and clears a reviewed handle on navigation', async () => {
    await click('Choose .grshare file'); await unlock();
    await click('Cancel import');
    expect(desktop.discardEndpointShare).toHaveBeenCalledWith(REVIEW.handle);
    expect(files.selected()).toBeNull();
    expect(desktop.importEndpointShare).not.toHaveBeenCalled();
    await click('Choose .grshare file'); await unlock();
    fixture.destroy();
    expect(desktop.discardEndpointShare).toHaveBeenCalledTimes(2);
  });

  it('preserves a newly opened file when cancellation of the old review finishes', async () => {
    let finish!: () => void;
    desktop.discardEndpointShare.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve; }));
    await click('Choose .grshare file'); await unlock();
    await click('Cancel import');
    files.select({ fileName: 'next.grshare' }); await settle();
    finish(); await settle();
    expect(files.selected()?.fileName).toBe('next.grshare');
    expect(root.textContent).toContain('next.grshare');
  });
});
