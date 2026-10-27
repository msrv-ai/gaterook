import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { Env } from '../../../core/api/types';
import { provideKitIcons } from '../../icons';
import { ConnectionFormValue, GwConnectionForm } from './connection-form';

/** The form spec the core serves for a type (`GET /api/connector-types/{type}/form`), from the stored schemas. */
function spec(type: string): ConnectionFormSpec {
  return {
    type,
    name: type,
    env: 'dev',
    schema: CONNECTOR_FORMS[type],
    common: {},
    secretFields: [],
    secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
    envDefaults: { prod: [], stage: [] },
    variants: [],
  };
}

@Component({
  imports: [GwConnectionForm],
  template: `<form gwConnectionForm idPrefix="cf" aria-label="New connection" [spec]="spec()" name="dev-cluster" [env]="env()" (save)="saved.push($event)"></form>`,
})
class Host {
  readonly spec = signal(spec('kubernetes'));
  readonly env = signal<Env>('dev');
  readonly saved: ConnectionFormValue[] = [];
  readonly form = viewChild.required(GwConnectionForm);
}

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
});
afterEach(() => (document.body.innerHTML = ''));

async function render() {
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };
  return { fixture, el, host: fixture.componentInstance, settle };
}

/** Picks how the Kubernetes form signs in: each mode shows only its own fields. */
async function signInWith(el: HTMLElement, settle: () => Promise<void>, auth: 'token' | 'kubeconfig' | 'kubeconfig-file') {
  const select = el.querySelector<HTMLSelectElement>('#cf-auth')!;
  select.value = auth;
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

const KUBECONFIG = [
  'apiVersion: v1',
  'clusters:',
  '- cluster:',
  '    server: https://127.0.0.1:6443',
  '  name: kind-dev',
  'contexts:',
  '- context: {cluster: kind-dev, user: admin}',
  '  name: kind-dev',
  'users:',
  '- name: admin',
  '  user:',
  '    token: abc\tdef',
  '',
].join('\n');

describe('connection form: multi-line secrets', () => {
  it('a pasted kubeconfig goes into a textarea and reaches secrets byte for byte', async () => {
    const { el, host, settle } = await render();
    expect(el.querySelector('#cf-kubeconfig'), 'only when signing in with a pasted kubeconfig').toBeNull();
    await signInWith(el, settle, 'kubeconfig');
    expect(el.querySelector('#cf-server')).toBeNull();
    const field = el.querySelector<HTMLTextAreaElement>('#cf-kubeconfig')!;
    expect(field.tagName).toBe('TEXTAREA');
    expect(field.classList).toContain('gw-textarea--code');
    expect(field.getAttribute('autocomplete')).toBe('off');
    expect(field.getAttribute('spellcheck')).toBe('false');
    field.value = KUBECONFIG;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    const secrets = host.form().value().secrets;
    expect(secrets['/kubeconfig']).toEqual({ source: 'local', value: KUBECONFIG });
  });
});

describe('connection form: x-confirm and the prod locks', () => {
  it('a changed setting with x-confirm asks in a warning dialog; Cancel saves nothing, the confirm saves', async () => {
    const { el, host, settle } = await render();
    // A kubeconfig's credential command: fill what a pasted kubeconfig needs, so the form is valid.
    await signInWith(el, settle, 'kubeconfig');
    const kubeconfig = el.querySelector<HTMLTextAreaElement>('#cf-kubeconfig')!;
    kubeconfig.value = KUBECONFIG;
    kubeconfig.dispatchEvent(new Event('input', { bubbles: true }));
    const exec = el.querySelector<HTMLInputElement>('#cf-allowExecPlugin')!;
    exec.checked = true;
    exec.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().state().invalidNodes().map((n) => n.pointer)).toEqual([]);

    const form = el.querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('dialog.gw-dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--warning');
    expect(dialog.querySelector('.gw-dialog__title')!.textContent).toBe('Save dev-cluster with this change?');
    expect(dialog.querySelector('.gw-kv__value')!.textContent).toBe('On, was off');
    expect(document.activeElement?.textContent?.trim()).toBe('Cancel');
    expect(host.saved).toEqual([]);

    (document.activeElement as HTMLButtonElement).click();
    await settle();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(host.saved).toEqual([]);

    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    const confirm = [...dialog.querySelectorAll<HTMLButtonElement>('.gw-dialog__footer .gw-btn')].at(-1)!;
    expect(confirm.classList).toContain('gw-btn--primary');
    confirm.click();
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config).toMatchObject({ allowExecPlugin: true });
  });

  it('on prod, Skip TLS verification is off and disabled with its reason', async () => {
    const { el, host, settle } = await render();
    const skip = () => el.querySelector<HTMLInputElement>('#cf-insecureSkipTlsVerify')!;
    skip().checked = true;
    skip().dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().value().config).toMatchObject({ insecureSkipTlsVerify: true });
    expect(skip().disabled).toBe(false);

    host.env.set('prod');
    await settle();
    expect(skip().disabled).toBe(true);
    expect(skip().checked).toBe(false);
    const reason = el.querySelector('#cf-insecureSkipTlsVerify-lock')!;
    expect(reason.textContent).toBe('Not allowed on prod.');
    expect(skip().getAttribute('aria-describedby')).toContain('cf-insecureSkipTlsVerify-lock');
    expect(host.form().value().config).toMatchObject({ insecureSkipTlsVerify: false });
  });

  it('on prod, an option of a select that prod forbids is disabled and named', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.env.set('prod');
    await settle();
    const select = el.querySelector<HTMLSelectElement>('#cf-connection-tlsVerify')!;
    const ca = [...select.options].find((o) => o.value === 'ca')!;
    expect(ca.disabled).toBe(true);
    expect(select.getAttribute('aria-describedby')).toContain('cf-connection-tlsVerify-lock');
    expect(el.querySelector('#cf-connection-tlsVerify-lock')!.textContent).toMatch(/isn't allowed on prod\.$/);
    // Prod's own default is on from the start: TLS for a new prod Redis.
    expect(host.form().value().config['connection']).toMatchObject({ tls: true, tlsVerify: 'full' });
  });
});
