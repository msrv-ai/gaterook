import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { JsonObject } from '../core/api/types';
import { provideKitIcons } from '../kit/icons';
import { createSchemaForm } from './schema-form';
import { GwSchemaForm } from './schema-form.component';

/**
 * A tag list of a schema form (a Redis deny list, a schema allowlist) never loses what was typed (audit M-U3): a value
 * still in the input when the form is saved, or when the input loses focus, is part of the saved config.
 */

const SCHEMA: JsonObject = {
  type: 'object',
  properties: {
    denyPatterns: { type: 'array', title: 'Denied key patterns', items: { type: 'string' } },
  },
};

@Component({
  imports: [GwSchemaForm],
  template: `
    <form class="gw-form" id="tags-form" (submit)="save($event)">
      <gw-schema-form [form]="form" />
    </form>
    <button type="submit" form="tags-form" id="save">Save</button>
  `,
})
class Host {
  readonly form = createSchemaForm(SCHEMA, { idPrefix: 'tags', current: { denyPatterns: ['FLUSH*'] } });
  saved: JsonObject | null = null;

  save(e: Event): void {
    e.preventDefault();
    if (this.form.submit()) this.saved = this.form.value();
  }
}

async function render() {
  TestBed.configureTestingModule({ providers: [provideKitIcons()] });
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const el = fixture.nativeElement as HTMLElement;
  const input = el.querySelector<HTMLInputElement>('.gw-connection-form__tags input')!;
  const type = async (value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const tags = () => Array.from(el.querySelectorAll('.gw-tag__label')).map((t) => t.textContent?.trim());
  return { fixture, el, input, type, settle, tags, host: fixture.componentInstance };
}

describe('schema form: tag lists keep what was typed', () => {
  afterEach(() => (document.body.innerHTML = ''));

  it('Save adds a value still in the input before the form is read, focus still in it', async () => {
    const { el, input, type, settle, tags, host } = await render();
    input.focus();
    await type('CONFIG*');
    el.querySelector<HTMLButtonElement>('#save')!.click();
    await settle();
    expect(host.saved).toEqual({ denyPatterns: ['FLUSH*', 'CONFIG*'] });
    expect(tags()).toEqual(['FLUSH*', 'CONFIG*']);
    expect(input.value).toBe('');
  });

  it('leaving the input adds the value', async () => {
    const { input, type, settle, tags, host } = await render();
    await type('  SHUTDOWN ');
    input.dispatchEvent(new FocusEvent('blur'));
    await settle();
    expect(tags()).toEqual(['FLUSH*', 'SHUTDOWN']);
    expect(host.form.value()).toEqual({ denyPatterns: ['FLUSH*', 'SHUTDOWN'] });
    expect(input.value).toBe('');
  });

  it('Enter adds the value without submitting; a value already listed is not added twice', async () => {
    const { input, type, settle, tags, host } = await render();
    await type('DEBUG*');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    expect(tags()).toEqual(['FLUSH*', 'DEBUG*']);
    expect(host.saved).toBeNull();
    await type('FLUSH*');
    input.dispatchEvent(new FocusEvent('blur'));
    await settle();
    expect(tags()).toEqual(['FLUSH*', 'DEBUG*']);
    expect(input.value).toBe('');
  });

  it('an empty input adds nothing', async () => {
    const { el, type, settle, host } = await render();
    await type('   ');
    el.querySelector<HTMLButtonElement>('#save')!.click();
    await settle();
    expect(host.saved).toEqual({ denyPatterns: ['FLUSH*'] });
  });
});
