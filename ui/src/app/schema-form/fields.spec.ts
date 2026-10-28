import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { JsonObject } from '../core/api/types';
import { provideKitIcons } from '../kit/icons';
import { createSchemaForm } from './schema-form';
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
