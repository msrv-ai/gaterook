import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AriaDisabledGuard } from '../../aria-disabled';
import { provideKitIcons } from '../../icons';
import { ConnectorCardView, GwConnectorCard, GwConnectorCardActions, GwConnectorCardNote } from './connector-card';

/** The connector card's page slots (Connectors): the installed line, page actions, status, busy and Install. */

const CLICKHOUSE: ConnectorCardView = {
  type: 'clickhouse',
  variant: null,
  name: 'ClickHouse',
  icon: 'database',
  kind: 'plugin',
  version: '0.9.1',
  signed: false,
  description: 'Adds ch_query for analytics.',
  literals: ['ch_query'],
  tools: 6,
  action: 'install',
};

@Component({
  imports: [GwConnectorCard, GwConnectorCardActions],
  template: `
    <article gwConnectorCard id="a" [card]="card()" [confirmUnsigned]="confirm()" [busy]="busy()" (act)="acted.push($event.name)">
      <div gwConnectorCardActions><button type="button">Page action</button></div>
    </article>
  `,
})
class Host {
  readonly card = signal<ConnectorCardView>(CLICKHOUSE);
  readonly confirm = signal(true);
  readonly busy = signal(false);
  readonly acted: string[] = [];
}

/** Installed cards always project their line (the page renders it for every card). */
@Component({
  imports: [GwConnectorCard, GwConnectorCardNote],
  template: `<article gwConnectorCard [card]="card"><p gwConnectorCardNote>6 tools · no connections yet</p></article>`,
})
class NoteHost {
  readonly card: ConnectorCardView = { ...CLICKHOUSE, action: null };
}

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
  TestBed.inject(AriaDisabledGuard).install();
});
afterEach(() => (document.body.innerHTML = ''));

async function render<T = Host>(type: new () => T = Host as unknown as new () => T) {
  const fixture = TestBed.createComponent(type);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };
  return { el, host: fixture.componentInstance, settle };
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('connector card: page slots', () => {
  it('asks its own danger confirmation for unsigned code unless the page reviews it', async () => {
    const { el, host, settle } = await render();
    const install = el.querySelector<HTMLButtonElement>('.gw-card__footer button')!;
    install.click();
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('dialog')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Install unsigned plugin ClickHouse?');
    expect(host.acted).toEqual([]);
    dialog.close();

    host.confirm.set(false);
    await settle();
    el.querySelector<HTMLButtonElement>('.gw-card__footer button')!.click();
    await settle();
    expect(host.acted).toEqual(['ClickHouse']);

    // While the page waits for the core the button is busy and inert.
    host.busy.set(true);
    await settle();
    const busy = el.querySelector<HTMLButtonElement>('.gw-card__footer button')!;
    expect(busy.getAttribute('aria-busy')).toBe('true');
    expect(busy.querySelector('.gw-spinner')).not.toBeNull();
    busy.click();
    await settle();
    expect(host.acted).toEqual(['ClickHouse']);
  });

  it('shows a status instead of the tool count, the running version, and the page’s actions', async () => {
    const { el, host, settle } = await render();
    host.card.set({ ...CLICKHOUSE, action: null, runs: 'v1.2.0', status: { tone: 'info', text: 'Update available · ', mono: 'v1.3.0' } });
    await settle();
    expect(text(el.querySelector('.gw-card__body p'))).toBe('You run v1.2.0. Adds ch_query for analytics.');
    expect(el.querySelector('.gw-card__body code')?.textContent).toBe('ch_query');
    expect(text(el.querySelector('.gw-card__footer .gw-status--info'))).toBe('Update available · v1.3.0');
    expect(el.querySelector('.gw-connector-card__tools')).toBeNull();
    expect(text(el.querySelector('.gw-card__footer [gwConnectorCardActions]'))).toBe('Page action');
  });

  it('stacks the installed line under the description', async () => {
    const { el } = await render(NoteHost);
    const stack = el.querySelector('.gw-card__body .gw-stack--sm');
    expect(stack).not.toBeNull();
    expect(text(stack!.querySelector('[gwConnectorCardNote]'))).toBe('6 tools · no connections yet');
    expect(text(el.querySelector('.gw-connector-card__tools'))).toBe('6 tools');
  });
});
