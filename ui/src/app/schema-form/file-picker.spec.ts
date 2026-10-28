import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CONNECTOR_FORMS } from '../../testing/connector-schemas';
import { DesktopBridge } from '../core/desktop/desktop-bridge';
import { provideKitIcons } from '../kit/icons';
import { createSchemaForm } from './schema-form';
import { GwSchemaForm } from './schema-form.component';

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class Host {
  readonly form = createSchemaForm(CONNECTOR_FORMS['ssh-logs'], { idPrefix: 'key', current: {
    connection: { host: 'logs.example.test', username: 'reader' },
    credentials: { method: 'privateKeyFile', privateKeyPath: '~/keys/original' },
  } });
}

async function render(choose = vi.fn<() => Promise<string | null>>().mockResolvedValue('/example/keys/reader'), available = true) {
  TestBed.configureTestingModule({ providers: [provideKitIcons(),
    { provide: DesktopBridge, useValue: { privateKeyPickerAvailable: available, choosePrivateKeyFile: choose } }] });
  const fixture = TestBed.createComponent(Host);
  const settle = async () => { await Promise.resolve(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  await settle();
  const el = fixture.nativeElement as HTMLElement;
  const input = () => el.querySelector<HTMLInputElement>('#key-credentials-privateKeyPath')!;
  const button = () => el.querySelector<HTMLButtonElement>('#key-credentials-privateKeyPath-choose')!;
  const type = async (value: string) => { input().value = value; input().dispatchEvent(new Event('input', { bubbles: true })); await settle(); };
  return { fixture, settle, input, button, type, choose, el, form: fixture.componentInstance.form };
}

describe('private key path field', () => {
  it('inserts the selected path into the editable configuration field without replacing log settings or secrets', async () => {
    const p = await render();
    expect(p.button().type).toBe('button');
    expect(p.button().getAttribute('aria-label')).toBe('Choose private key file');
    p.button().click(); await p.settle();
    expect(p.choose).toHaveBeenCalledExactlyOnceWith();
    expect(p.input().value).toBe('/example/keys/reader');
    expect(p.form.value()['credentials']).toEqual({ method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' });
    expect(p.form.secrets()).toEqual({});
    await p.type('~/keys/manual');
    expect(p.form.value()['credentials']).toEqual({ method: 'privateKeyFile', privateKeyPath: '~/keys/manual' });
  });

  it('keeps the existing path when the dialog is cancelled', async () => {
    const p = await render(vi.fn<() => Promise<string | null>>().mockResolvedValue(null));
    p.button().click(); await p.settle();
    expect(p.input().value).toBe('~/keys/original');
    expect(p.el.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows dialog errors and leaves manual entry available', async () => {
    const p = await render(vi.fn<() => Promise<string | null>>().mockRejectedValue(
      new Error("Error invoking remote method 'gw:choose-private-key-file': Error: Dialog unavailable")));
    p.button().click(); await p.settle();
    expect(p.el.querySelector('[role="alert"]')?.textContent).toBe('Dialog unavailable');
    expect(p.input().value).toBe('~/keys/original');
    await p.type('/example/keys/manual');
    expect(p.input().value).toBe('/example/keys/manual');
  });

  it('explains the browser limitation and keeps the path input usable', async () => {
    const p = await render(undefined, false);
    expect(p.button().getAttribute('aria-disabled')).toBe('true');
    expect(p.el.textContent).toContain('Choose file is available in the desktop app. Enter the path manually here.');
    p.button().click(); await p.settle();
    expect(p.choose).not.toHaveBeenCalled();
    await p.type('/example/keys/manual');
    expect(p.input().value).toBe('/example/keys/manual');
  });

  it('prevents duplicate dialogs and does not overwrite a path edited while one was open', async () => {
    let finish!: (path: string) => void;
    const p = await render(vi.fn(() => new Promise<string>((resolve) => { finish = resolve; })));
    p.button().click(); p.fixture.detectChanges();
    expect(p.button().getAttribute('aria-busy')).toBe('true');
    p.button().click();
    p.input().value = '/example/keys/newer';
    p.input().dispatchEvent(new Event('input', { bubbles: true }));
    finish('/example/keys/stale'); await p.settle();
    expect(p.choose).toHaveBeenCalledTimes(1);
    expect(p.input().value).toBe('/example/keys/newer');
  });
});
