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

async function secretForm(stored: readonly StoredSecret[] = []) {
  storedSecrets.set(stored);
  TestBed.configureTestingModule({ providers: [provideKitIcons()] });
  const fixture = TestBed.createComponent(SecretHost);
  const form = fixture.componentInstance.form;
  form.stores.set([{ kind: 'gcp-sm', store: null, title: 'GCP Secret Manager', detail: null, available: true, reason: null }]);
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
  const menu = () => el.querySelector<HTMLButtonElement>('#secret-password-src')!;
  const pick = async (title: string) => {
    menu().click();
    await settle();
    [...el.querySelectorAll<HTMLElement>('#secret-password-src-menu [role="option"]')]
      .find((o) => o.querySelector('.gw-menu__label')!.firstChild!.textContent!.trim() === title)!.click();
    await settle();
  };
  return { form, actions, el, input, type, test, menu, pick, settle };
}

describe('secret reference fields', () => {
  const path = 'projects/example-dev/secrets/api-key';
  const ref = `gcp-sm://${path}`;

  it("a value typed with a store's scheme is a reference: shown as typed, tested and saved as one", async () => {
    const view = await secretForm();
    expect(view.menu().textContent?.trim()).toBe('Text');
    expect(view.input().type).toBe('password');
    await view.type(ref);

    expect(view.menu().textContent?.trim()).toBe('GCP');
    expect(view.input().type).toBe('text');
    expect(view.input().value).toBe(ref);
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref })]);
    expect(view.form.submit()).toBe(true);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'reference', ref } });
  });

  it("picking a store in the field's menu puts its scheme in front; Text empties the reference", async () => {
    const view = await secretForm();
    await view.pick('GCP Secret Manager');
    expect(view.input().value).toBe('gcp-sm://');
    expect(view.input().placeholder).toMatch(/^gcp-sm:\/\/projects\//);
    expect(document.activeElement).toBe(view.input());
    await view.type(ref);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'reference', ref } });

    await view.pick('Text');
    expect(view.input().value).toBe('');
    expect(view.input().type).toBe('password');
    expect(view.form.secretInputs()).toEqual({});
  });

  it('keeps a password typed as Text exactly, also when it only mentions a scheme', async () => {
    const view = await secretForm();
    const value = '  test-only gcp-sm://password  ';
    await view.type(value);
    expect(view.menu().textContent?.trim()).toBe('Text');
    expect(view.input().value).toBe(value);
    expect(view.form.secretInputs()).toEqual({ '/password': { source: 'local', value } });
  });

  it('tests the saved reference without requiring a replacement', async () => {
    const view = await secretForm([{ field: '/password', secretId: 'stored-secret', name: ref,
      source: 'gcp-sm', state: 'reference', version: null }]);
    expect(view.input()).toBeNull();
    view.test();
    expect(view.actions).toEqual([expect.objectContaining({ kind: 'test', ref })]);
    expect(view.form.secretInputs()).toEqual({});
  });
});

const NOTE_SCHEMA: JsonObject = {
  type: 'object', properties: {
    host: { type: 'string', title: 'Host' },
    note: { type: 'string', title: 'Note' },
  },
};

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class NoteHost {
  readonly form = createSchemaForm(NOTE_SCHEMA, { idPrefix: 'note' });
}

describe('a reference typed into any text field', () => {
  it('turns the field into a reference field with its menu, keeping the keyboard in it; never the host', async () => {
    TestBed.configureTestingModule({ providers: [provideKitIcons()] });
    const fixture = TestBed.createComponent(NoteHost);
    document.body.appendChild(fixture.nativeElement);
    const form = fixture.componentInstance.form;
    const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const type = async (id: string, value: string) => {
      const input = el.querySelector<HTMLInputElement>(`#note-${id}`)!;
      input.focus();
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await settle();
    };
    // A text field beside no secret has no menu while it holds a value.
    expect(el.querySelector('#note-note-src')).toBeNull();
    await type('note', 'gcp-sm://projects/example-dev/secrets/note');
    expect(el.querySelector('#note-note-src')?.textContent?.trim()).toBe('GCP');
    expect(document.activeElement).toBe(el.querySelector('#note-note'));
    expect(form.value()).toEqual({});
    expect(form.secretInputs()).toEqual({ '/note': { source: 'reference', ref: 'gcp-sm://projects/example-dev/secrets/note' } });

    await type('host', 'vault://kv/dev/db#host');
    expect(el.querySelector('#note-host-src')).toBeNull();
    expect(form.submit()).toBe(false);
    await settle();
    expect(el.textContent).toContain('never read from a store');
    expect(form.secretInputs()).not.toHaveProperty('/host');
    fixture.nativeElement.remove();
  });
});

const CHOICE_SCHEMA: JsonObject = {
  type: 'object', required: ['mode'], properties: {
    mode: { enum: ['read', 'write'] },
    start: { enum: ['on-first-call', 'with-core'], default: 'on-first-call' },
    context: { enum: ['kind-dev', 'gke-prod'] },
    auth: { type: 'object', title: 'Sign-in', required: ['type'], properties: { type: { enum: ['oauth', 'google'] } } },
  },
};

@Component({ imports: [GwSchemaForm], template: '<gw-schema-form [form]="form" />' })
class ChoiceHost {
  readonly form = createSchemaForm(CHOICE_SCHEMA, { idPrefix: 'choice' });
}

describe('choices that may stay empty', () => {
  it('say Not set and keep it once a value is picked, so an omittable object can be left out again', async () => {
    TestBed.configureTestingModule({ providers: [provideKitIcons()] });
    const fixture = TestBed.createComponent(ChoiceHost);
    const form = fixture.componentInstance.form;
    const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const select = (id: string) => el.querySelector<HTMLSelectElement>(`#choice-${id}`)!;
    const empty = (id: string) => [...select(id).options].filter((o) => o.value === '').map((o) => o.text);
    const pick = async (id: string, value: string) => {
      select(id).value = value;
      select(id).dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
    };
    expect(['mode', 'start', 'context', 'auth-type'].map(empty)).toEqual([['Choose…'], [], ['Not set'], ['Not set']]);

    await pick('mode', 'read');
    await pick('context', 'gke-prod');
    await pick('auth-type', 'oauth');
    expect(['mode', 'context', 'auth-type'].map(empty)).toEqual([[], ['Not set'], ['Not set']]);
    expect(form.value()).toEqual({ mode: 'read', start: 'on-first-call', context: 'gke-prod', auth: { type: 'oauth' } });

    await pick('auth-type', '');
    await pick('context', '');
    expect(form.value()).toEqual({ mode: 'read', start: 'on-first-call' });
    expect(form.submit()).toBe(true);
  });
});
