import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ClientCard, ManualAppRequest, ManualAppResult } from '../../core/api/clients.api';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ClientsData } from './clients.data';
import { connected, kitClients, kitEndpoints, kitManual } from './clients.fixtures';
import { ManualAppDialog } from './manual-app-dialog';

const TOKEN = 'gwt_test_manual_app_shown_once';

function manualApp(): ClientCard {
  return {
    ...kitClients().clients[2],
    client: 'manual-example', name: 'Local assistant', manual: true,
    status: 'pending', statusText: 'Awaiting connection', detected: false,
    configPath: null, configDisplay: null, evidence: [], methods: [],
    links: [{
      ...connected('cursor', { endpoint: 'dev', method: 'token' }).link,
      id: 'manual-example', client: 'manual-example', method: 'manual',
      status: 'pending', statusText: 'Awaiting connection',
      configPath: null, configDisplay: null, entryName: null,
      tokenName: 'app-example', lastCallAt: null, usedAt: null,
    }],
  };
}

function issued(): ManualAppResult {
  return {
    client: manualApp(), setup: kitManual(),
    token: { id: 'tok_example', name: 'app-example', token: TOKEN, prefix: 'gwt_test', expiresAt: '2027-04-24T12:30:00Z' },
  };
}

describe('Manual AI app setup', () => {
  let fixture: ComponentFixture<ManualAppDialog>;
  let el: HTMLElement;
  let opener: HTMLButtonElement;
  let saved: ClientCard[];
  let add: ReturnType<typeof vi.fn<(request: ManualAppRequest) => Promise<ManualAppResult>>>;
  let renew: ReturnType<typeof vi.fn<(id: string, ttlDays?: number | null) => Promise<ManualAppResult>>>;

  beforeEach(async () => {
    add = vi.fn(() => Promise.resolve(issued()));
    renew = vi.fn(() => Promise.resolve(issued()));
    saved = [];
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideKitIcons(),
      { provide: ClientsData, useValue: { addManual: add, renewManual: renew } },
      { provide: TimeService, useValue: { date: () => 'Apr 24, 2027' } },
    ] });
    TestBed.inject(AriaDisabledGuard).install();
    fixture = TestBed.createComponent(ManualAppDialog);
    fixture.componentRef.setInput('endpoints', kitEndpoints());
    fixture.componentRef.setInput('defaultTtlDays', 90);
    fixture.componentInstance.saved.subscribe((card) => saved.push(card));
    el = fixture.nativeElement;
    document.body.appendChild(el);
    opener = document.createElement('button');
    opener.textContent = 'Add app';
    document.body.appendChild(opener);
    await settle();
  });

  afterEach(() => { document.body.innerHTML = ''; });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const text = (element: Element | null) => (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const button = (name: string) => [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => text(b) === name)!;
  const dialog = () => el.querySelector<HTMLDialogElement>('dialog')!;

  async function open(card: ClientCard | null = null): Promise<void> {
    opener.focus();
    fixture.componentInstance.open(opener, card);
    await settle();
  }

  async function enter(selector: string, value: string): Promise<void> {
    const control = el.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event(control.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
    await settle();
  }

  async function click(name: string): Promise<void> {
    button(name).click();
    await settle();
  }

  it('selects the default lifetime when the form options are first rendered', async () => {
    fixture.componentRef.setInput('defaultTtlDays', 184);
    await open();
    expect(el.querySelector<HTMLSelectElement>('#manual-app-lifetime')!.value).toBe('184');
    await enter('#manual-app-name', 'Local assistant');
    await click('Add app');
    expect(add).toHaveBeenCalledExactlyOnceWith({ name: 'Local assistant', endpoint: 'dev', ttlDays: 184 });
  });

  it('selects an endpoint when its options load after the dialog is opened', async () => {
    fixture.componentRef.setInput('endpoints', []);
    await open();
    await enter('#manual-app-name', 'Local assistant');
    expect(button('Add app').getAttribute('aria-disabled')).toBe('true');
    fixture.componentRef.setInput('endpoints', kitEndpoints());
    await settle();
    expect(el.querySelector<HTMLSelectElement>('#manual-app-endpoint')!.value).toBe('dev');
    expect(button('Add app').getAttribute('aria-disabled')).toBeNull();
    await click('Add app');
    expect(add).toHaveBeenCalledExactlyOnceWith({ name: 'Local assistant', endpoint: 'dev', ttlDays: 90 });
  });

  it('submits a trimmed display name, selected endpoint and token lifetime before showing MCP settings', async () => {
    await open();
    expect(document.activeElement?.id).toBe('manual-app-name');
    expect(el.querySelector<HTMLSelectElement>('#manual-app-lifetime')!.value).toBe('90');
    await enter('#manual-app-name', '  Local assistant  ');
    await enter('#manual-app-endpoint', 'stage-readonly');
    await enter('#manual-app-lifetime', '7');
    await click('Add app');

    expect(add).toHaveBeenCalledExactlyOnceWith({ name: 'Local assistant', endpoint: 'stage-readonly', ttlDays: 7 });
    expect(renew).not.toHaveBeenCalled();
    expect(saved).toEqual([manualApp()]);
    expect(el.querySelector<HTMLInputElement>('#manual-app-url')!.value).toBe(kitManual().url);
    expect(text(dialog())).toContain('Streamable HTTP. Authentication: Bearer token.');
    expect(text(dialog())).toContain('Awaiting connection');
    expect(text(dialog())).toContain(TOKEN);
    expect(document.activeElement?.hasAttribute('data-token-copy')).toBe(true);
  });

  it('requires acknowledgement before closing and removes the one-time token from the closed dialog', async () => {
    await open();
    await enter('#manual-app-name', 'Local assistant');
    await click('Add app');
    expect(button('Done').getAttribute('aria-disabled')).toBe('true');
    const escape = new Event('cancel', { cancelable: true });
    dialog().dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    dialog().click();
    await click('Done');
    expect(dialog().open).toBe(true);
    expect(text(dialog())).toContain(TOKEN);

    el.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await settle();
    await click('Done');
    expect(dialog().open).toBe(false);
    expect(text(el)).not.toContain(TOKEN);
    expect(document.activeElement).toBe(opener);

    await open();
    expect(text(dialog())).not.toContain(TOKEN);
    expect(el.querySelector<HTMLInputElement>('#manual-app-name')!.value).toBe('');
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('rejects a blank name without creating access and keeps unavailable endpoints disabled', async () => {
    fixture.componentRef.setInput('endpoints', kitEndpoints().map((e) => e.slug === 'dev' ? { ...e, status: 'disabled' } : e));
    await open();
    expect(el.querySelector<HTMLOptionElement>('#manual-app-endpoint option[value="dev"]')!.disabled).toBe(true);
    expect(el.querySelector<HTMLSelectElement>('#manual-app-endpoint')!.value).toBe('stage-readonly');
    await enter('#manual-app-name', '   ');
    await click('Add app');
    expect(add).not.toHaveBeenCalled();
    expect(text(dialog())).toContain('Enter an app name.');
    expect(document.activeElement?.id).toBe('manual-app-name');
  });

  it('keeps the draft after a failed request so the same app can be retried', async () => {
    add.mockRejectedValueOnce(ApiError.fromHttp(new HttpErrorResponse({
      status: 503, error: { title: 'Unavailable', detail: 'The core is restarting. Try again.' },
    })));
    await open();
    await enter('#manual-app-name', 'Local assistant');
    await click('Add app');
    expect(text(el.querySelector('[role="alert"]'))).toContain('The core is restarting. Try again.');
    expect(el.querySelector<HTMLInputElement>('#manual-app-name')!.value).toBe('Local assistant');
    expect(saved).toEqual([]);
    await click('Add app');
    expect(add).toHaveBeenCalledTimes(2);
    expect(saved).toHaveLength(1);
    expect(text(dialog())).toContain(TOKEN);
  });

  it('prevents double submission and closing while credentials are being created', async () => {
    let finish!: (result: ManualAppResult) => void;
    add.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await open();
    await enter('#manual-app-name', 'Local assistant');
    button('Add app').click();
    fixture.detectChanges();
    button('Add app').click();
    button('Cancel').click();
    const escape = new Event('cancel', { cancelable: true });
    dialog().dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(dialog().open).toBe(true);
    expect(add).toHaveBeenCalledTimes(1);
    finish(issued());
    await settle();
    expect(text(dialog())).toContain(TOKEN);
  });

  it('explains token replacement and changes access only after explicit confirmation', async () => {
    const card = manualApp();
    await open(card);
    expect(dialog().classList.contains('gw-dialog--danger')).toBe(true);
    expect(document.activeElement).toBe(button('Cancel'));
    expect(text(dialog())).toContain('The current token will stop working immediately.');
    expect(el.querySelector('#manual-app-name')).toBeNull();
    expect(el.querySelector('#manual-app-endpoint')).toBeNull();
    expect(renew).not.toHaveBeenCalled();
    await click('Cancel');
    expect(renew).not.toHaveBeenCalled();
    await open(card);
    await enter('#manual-app-lifetime', '30');
    await click('Replace token');
    expect(renew).toHaveBeenCalledExactlyOnceWith('manual-example', 30);
    expect(add).not.toHaveBeenCalled();
    expect(saved).toEqual([card]);
    expect(text(dialog())).toContain(TOKEN);
  });
});
