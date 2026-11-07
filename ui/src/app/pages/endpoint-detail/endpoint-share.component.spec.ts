import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DesktopBridge, FullBackupResult } from '../../core/desktop/desktop-bridge';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { GwEndpointShare } from './endpoint-share.component';

describe('Endpoint share export', () => {
  let fixture: ComponentFixture<GwEndpointShare>;
  let root: HTMLElement;
  const desktop = { endpointShareAvailable: true, createEndpointShare: vi.fn() };
  const get = <T extends Element = HTMLElement>(selector: string) => root.querySelector<T>(selector)!;
  const button = (name: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === name)!;
  async function settle(): Promise<void> { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); }
  async function submit(passphrase = 'a unique shared passphrase', repeat = passphrase): Promise<void> {
    get<HTMLInputElement>('#ep-share-pass').value = passphrase;
    get<HTMLInputElement>('#ep-share-repeat').value = repeat;
    get('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
  }
  beforeEach(async () => {
    desktop.endpointShareAvailable = true;
    desktop.createEndpointShare.mockReset().mockResolvedValue({ cancelled: false, fileName: 'dev.grshare' });
    TestBed.configureTestingModule({ providers: [provideKitIcons(), { provide: DesktopBridge, useValue: desktop }] });
    fixture = TestBed.createComponent(GwEndpointShare);
    fixture.componentRef.setInput('slug', 'dev');
    fixture.componentRef.setInput('connections', [
      { name: 'dev-db', type: 'postgres', typeName: 'PostgreSQL', env: 'dev' },
      { name: 'logs', type: 'logs', typeName: 'Logs', env: 'dev' },
    ]);
    root = fixture.nativeElement;
    document.body.appendChild(root);
    await settle();
  });
  afterEach(() => { fixture.destroy(); document.body.innerHTML = ''; });

  it('exports only checked connections, excludes secrets by default and clears passphrase fields', async () => {
    button('Share endpoint').click(); await settle();
    expect(document.activeElement?.id).toBe('ep-share-pass');
    const choices = root.querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    expect(choices[2].checked).toBe(false);
    choices[1].click(); await settle();
    await submit();
    expect(desktop.createEndpointShare).toHaveBeenCalledExactlyOnceWith({ endpointSlug: 'dev', connectionNames: ['dev-db'], includeSecrets: false }, 'a unique shared passphrase');
    expect(get<HTMLInputElement>('#ep-share-pass').value).toBe('');
    expect(get<HTMLInputElement>('#ep-share-repeat').value).toBe('');
    expect(get<HTMLDialogElement>('dialog').open).toBe(false);
    expect(TestBed.inject(ToastService).toasts()[0].body).toContain('passphrase separately');
  });

  it('requires a valid matching passphrase before exporting, and supports explicit secrets inclusion', async () => {
    button('Share endpoint').click(); await settle();
    await submit('short');
    expect(desktop.createEndpointShare).not.toHaveBeenCalled();
    expect(document.activeElement?.id).toBe('ep-share-pass');
    await submit('a valid shared passphrase', 'a different passphrase');
    expect(document.activeElement?.id).toBe('ep-share-repeat');
    root.querySelectorAll<HTMLInputElement>('input[type=checkbox]')[2].click();
    await submit();
    expect(desktop.createEndpointShare.mock.calls[0][0].includeSecrets).toBe(true);
  });

  it('explains and excludes external MCP servers while allowing endpoint settings to be shared', async () => {
    fixture.componentRef.setInput('connections', [{ name: 'external-server', type: 'mcp-server', typeName: 'External MCP', env: 'dev' }]);
    await settle();
    button('Share endpoint').click(); await settle();
    const connection = root.querySelector<HTMLInputElement>('fieldset input[type=checkbox]')!;
    expect(connection.disabled).toBe(true);
    expect(connection.checked).toBe(false);
    expect(root.textContent).toContain('Set up this external MCP server separately');
    await submit();
    expect(desktop.createEndpointShare.mock.calls[0][0].connectionNames).toEqual([]);
  });

  it('prevents duplicate exports and closing during save, then permits cancellation without a success toast', async () => {
    let finish!: (value: FullBackupResult) => void;
    desktop.createEndpointShare.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    button('Share endpoint').click(); await settle();
    await submit();
    await submit();
    button('Cancel').click(); await settle();
    const event = new Event('cancel', { cancelable: true });
    get('dialog').dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(get<HTMLDialogElement>('dialog').open).toBe(true);
    expect(desktop.createEndpointShare).toHaveBeenCalledTimes(1);
    finish({ cancelled: true }); await settle();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    button('Cancel').click(); await settle();
    expect(get<HTMLInputElement>('#ep-share-pass').value).toBe('');
  });

  it('shows save failures without retaining the passphrase and blocks unsupported or unreviewed endpoints', async () => {
    desktop.endpointShareAvailable = false;
    button('Share endpoint').click(); await settle();
    expect(get<HTMLDialogElement>('dialog').open).toBe(false);
    desktop.endpointShareAvailable = true;
    fixture.componentRef.setInput('blocked', true); await settle();
    button('Share endpoint').click(); await settle();
    expect(get<HTMLDialogElement>('dialog').open).toBe(false);
    fixture.componentRef.setInput('blocked', false); await settle();
    desktop.createEndpointShare.mockRejectedValue(new Error('Not enough disk space.'));
    button('Share endpoint').click(); await settle();
    await submit();
    expect(get('#ep-share-error').textContent).toContain('Not enough disk space.');
    expect(get<HTMLInputElement>('#ep-share-pass').value).toBe('');
    expect(get<HTMLDialogElement>('dialog').open).toBe(true);
  });
});
