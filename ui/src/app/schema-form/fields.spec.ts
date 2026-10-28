import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { StoredSecret } from '../core/api/connections.api';
import { JsonObject } from '../core/api/types';
import { provideKitIcons } from '../kit/icons';
import { SecretAction, createSchemaForm } from './schema-form';
import { GwSchemaForm } from './schema-form.component';

const SCHEMA: JsonObject = {
  type: 'object', required: ['protocol'], properties: {
    protocol: { type: 'string', title: 'Protocol', const: 'v1' },
    backend: { type: 'string', const: 'loki', default: 'loki' },
  },
};

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class Host {
  readonly form = createSchemaForm(SCHEMA, { idPrefix: 'fixed', current: initial() });
}

const initial = signal<JsonObject>({});
const CASES: readonly JsonObject[] = [{}, { protocol: 'v0' }];

describe('fixed schema values', () => {
  it.each(CASES)('keeps a missing or incompatible constant editable: %j', async (current) => {
    initial.set(current);
    TestBed.configureTestingModule({ providers: [provideKitIcons()] });
    const fixture = TestBed.createComponent(Host);
    const form = fixture.componentInstance.form;
    const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('#fixed-protocol')!;
    expect(input).not.toBeNull();
    expect(input.value).toBe(current['protocol'] ?? '');
    expect(el.querySelector('#fixed-backend')).toBeNull();
    expect(form.value()['backend']).toBe('loki');

    input.value = 'v1';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(form.value()['protocol']).toBe('v1');
    expect(el.querySelector('#fixed-protocol')).toBeNull();
    expect(form.submit()).toBe(true);
  });
});

const SECRET_SCHEMA: JsonObject = {
  type: 'object', required: ['password'], properties: {
    password: { type: 'string', title: 'Password', writeOnly: true, format: 'password', 'x-gateway-secret': 'value' },
  },
};
const storedSecrets = signal<readonly StoredSecret[]>([]);

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class SecretHost {
  readonly form = createSchemaForm(SECRET_SCHEMA, { idPrefix: 'secret', stored: storedSecrets() });
}

async function secretForm(source = 'gcp-sm', stored: readonly StoredSecret[] = []) {
  storedSecrets.set(stored);
  TestBed.configureTestingModule({ providers: [provideKitIcons()] });
  const fixture = TestBed.createComponent(SecretHost);
  const form = fixture.componentInstance.form;
  form.source.set(source);
  const actions: SecretAction[] = [];
  form.onSecretAction((action) => actions.push(action));
  const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  await settle();
  const el = fixture.nativeElement as HTMLElement;
  const input = () => el.querySelector<HTMLInputElement>('#secret-password')!;
  const type = async (value: string) => {
    input().value = value;
    input().dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const test = () => [...el.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('Test reference'))!.click();
  const prefix = () => el.querySelector('.gw-input-group__affix')?.textContent ?? null;
  return { form, actions, el, input, type, test, prefix, settle };
}

describe('secret reference fields', () => {
  const path = 'projects/example-dev/secrets/api-key';
  const ref = `gcp-sm://${path}`;

  it('shows the selected prefix and uses the same completed reference for testing and saving', async () => {
    const view = await secretForm();
    expect(view.prefix()).toBe('gcp-sm://');
    expect(view.input().value).toBe('');
    expect(view.input().placeholder).toMatch(/^projects\//);
    expect(view.input().getAttribute('aria-describedby')).toContain('secret-password-prefix');

    await view.type(path);
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref })]);
    expect(view.form.submit()).toBe(true);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'reference', ref } });
  });

  it('displays and tests a matching pasted URI once, even after the same bare path was entered', async () => {
    const view = await secretForm();
    await view.type(path);
    await view.type(ref);

    expect(view.prefix()).toBe('gcp-sm://');
    expect(view.input().value).toBe(path);
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref })]);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'reference', ref } });
  });

  it('updates the prefix when the source changes and preserves local secret text', async () => {
    const view = await secretForm();
    await view.type('kv/dev/orders#password');
    view.form.source.set('vault');
    await view.settle();
    expect(view.prefix()).toBe('vault://');
    expect(view.input().value).toBe('kv/dev/orders#password');
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref: 'vault://kv/dev/orders#password' })]);

    view.form.source.set('local');
    await view.settle();
    expect(view.prefix()).toBeNull();
    expect(view.input().value).toBe('kv/dev/orders#password');
    const value = '  gcp-sm://test-only-password  ';
    await view.type(value);
    expect(view.input().value).toBe(value);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'local', value } });
  });

  it('keeps an explicit URI for another store visible so its mismatch can be corrected', async () => {
    const view = await secretForm();
    const foreign = 'vault://kv/dev/orders#password';
    await view.type(foreign);
    expect(view.prefix()).toBeNull();
    expect(view.input().value).toBe(foreign);
    expect(view.form.submit()).toBe(false);
    await view.settle();
    expect(view.input().getAttribute('aria-invalid')).toBe('true');
  });

  it('tests the saved reference without requiring a replacement', async () => {
    const view = await secretForm('gcp-sm', [{ field: '/password', secretId: 'stored-secret', name: ref,
      source: 'gcp-sm', state: 'reference', version: null }]);
    expect(view.input()).toBeNull();
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref })]);
    expect(view.form.secretInputs()).toEqual({});
  });
});
