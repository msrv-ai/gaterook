import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GwGuideStep, GuideStepState } from '../../guide-step';
import { provideKitIcons } from '../../icons';
import { GwStatusTile, TilePart, TileState } from './status-tile';

/**
 * The status tile and the guide step against the kit (design/elements/status-tile.html; COMPONENTS.md › gw-stepper
 * `--guide`, design/pages/overview.html): their markup, what a screen reader hears, and where the one action goes.
 */

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
});
afterEach(() => (document.body.innerHTML = ''));

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

@Component({
  imports: [GwStatusTile],
  template: `
    <ul class="gw-grid gw-grid--3" role="list" aria-label="Connections, problems first">
      <li gwStatusTile #tile="gwStatusTile" name="billing-api" mono env="stage" icon="globe" [link]="['/connections', 'billing-api']" [meta]="meta()" [state]="state()" [checking]="checking()">
        <button class="gw-btn gw-btn--sm" type="button" [attr.aria-describedby]="checking() ? tile.stateId : null">Test again<span class="gw-sr-only"> billing-api</span></button>
      </li>
      <li gwStatusTile name="Cursor" client="cursor" meta="Found on this computer" [state]="{ tone: 'info', label: 'Not connected' }" [level]="level()">
        <a class="gw-btn gw-btn--sm" href="/clients#cl-cu-t">Connect<span class="gw-sr-only"> Cursor</span></a>
      </li>
      <li gwStatusTile name="Codex" client="codex" link="/clients" fragment="cl-cx-t" [meta]="[{ text: 'Endpoint ' }, { text: 'dev', mono: true }]" [state]="{ tone: 'success', label: 'Connected' }"></li>
    </ul>
  `,
})
class Tiles {
  readonly meta = signal<string | TilePart[]>([{ text: 'HTTP API · ' }, { text: 'billing.stage.acme.dev', mono: true }]);
  readonly state = signal<TileState>({ tone: 'danger', label: 'Unreachable' });
  readonly checking = signal(false);
  readonly level = signal(3);
}

describe('Status tile (design/elements/status-tile.html)', () => {
  async function render() {
    const fixture = TestBed.createComponent(Tiles);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const tiles = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('li.gw-status-tile'));
    return { fixture, tiles, host: fixture.componentInstance };
  }

  it('a connection: the whole tile opens it, the name in mono with its environment, the meta line, its state in words', async () => {
    const { tiles } = await render();
    const tile = tiles[0];
    expect(tile.className).toBe('gw-card gw-status-tile gw-card--interactive');
    expect(tile.querySelector('.gw-icon-tile.gw-icon-tile--neutral')?.getAttribute('aria-hidden')).toBe('true');
    const link = tile.querySelector<HTMLAnchorElement>('h3.gw-status-tile__title a.gw-card__link')!;
    expect([link.getAttribute('href'), link.classList.contains('gw-mono')]).toEqual(['/connections/billing-api', true]);
    expect(text(tile.querySelector('h3'))).toBe('billing-api environment stage');
    expect(tile.querySelector('h3 .gw-env')?.className).toBe('gw-env gw-env--sm gw-env--stage');
    // A line with an address wraps and keeps its literal in mono; nothing is cut, so no title.
    const meta = tile.querySelector('.gw-status-tile__main p')!;
    expect([text(meta), meta.classList.contains('gw-truncate'), meta.getAttribute('title')]).toEqual(['HTTP API · billing.stage.acme.dev', false, null]);
    expect(text(meta.querySelector('.gw-mono'))).toBe('billing.stage.acme.dev');
    const state = tile.querySelector('.gw-status-tile__end .gw-status')!;
    expect([state.className, text(state)]).toEqual(['gw-status gw-status--danger', 'Unreachable']);
    // The one action sits after the state, above the tile's link.
    expect(text(tile.querySelector('.gw-status-tile__end button'))).toBe('Test again billing-api');
  });

  it('a plain meta line is cut to one line, whole in its title', async () => {
    const { fixture, tiles, host } = await render();
    host.meta.set('Read replica · replica.prod.internal');
    fixture.detectChanges();
    const meta = tiles[0].querySelector('.gw-status-tile__main p')!;
    expect([meta.classList.contains('gw-truncate'), meta.getAttribute('title')]).toEqual([true, 'Read replica · replica.prod.internal']);
  });

  it('busy: the state says Checking… as a status, and the action points at it', async () => {
    const { fixture, tiles, host } = await render();
    host.checking.set(true);
    fixture.detectChanges();
    const state = tiles[0].querySelector('.gw-status-tile__end .gw-status')!;
    expect([state.className, state.getAttribute('role'), text(state)]).toEqual(['gw-status gw-status--checking', 'status', 'Checking… billing-api']);
    expect(state.querySelector('.gw-spinner')?.getAttribute('aria-hidden')).toBe('true');
    expect(tiles[0].querySelector('button')!.getAttribute('aria-describedby')).toBe(state.id);
    host.checking.set(false);
    fixture.detectChanges();
    expect(text(tiles[0].querySelector('.gw-status'))).toBe('Unreachable');
  });

  it('an AI app: its mark, no link when its action is the only way on, the title one level below the set', async () => {
    const { fixture, tiles, host } = await render();
    const cursor = tiles[1];
    expect(cursor.className).toBe('gw-card gw-status-tile');
    expect(cursor.querySelector('[gwclientmark], .gw-client-mark')).not.toBeNull();
    expect(cursor.querySelector('.gw-icon-tile')).toBeNull();
    expect(cursor.querySelector('a.gw-card__link')).toBeNull();
    expect(text(cursor.querySelector('h3'))).toBe('Cursor');
    expect(text(cursor.querySelector('.gw-status'))).toBe('Not connected');
    expect(cursor.querySelector('.gw-status')!.className).toBe('gw-status gw-status--info');
    expect(text(cursor.querySelector('.gw-status-tile__end a'))).toBe('Connect Cursor');
    // Inside a guide step (h3) the tile's title is an h4.
    host.level.set(4);
    fixture.detectChanges();
    expect(cursor.querySelector('h3')).toBeNull();
    expect(text(cursor.querySelector('h4.gw-status-tile__title'))).toBe('Cursor');
  });

  it('a connected app: the title links to its card on AI apps', async () => {
    const { tiles } = await render();
    const codex = tiles[2];
    expect(codex.classList.contains('gw-card--interactive')).toBe(true);
    const link = codex.querySelector<HTMLAnchorElement>('a.gw-card__link')!;
    expect([link.getAttribute('href'), link.classList.contains('gw-mono')]).toEqual(['/clients#cl-cx-t', false]);
    expect(text(codex.querySelector('.gw-status-tile__main p .gw-mono'))).toBe('dev');
  });
});

@Component({
  imports: [GwGuideStep],
  template: `
    <ol class="gw-stepper gw-stepper--guide" aria-label="Get started">
      <li gwGuideStep [n]="1" [state]="first()" heading="Add your first connection"><p class="gw-stepper__description">A connection is something your AI can work with.</p></li>
      <li gwGuideStep [n]="2" [state]="second()" heading="Connect your AI app" [level]="level()"><div class="gw-stepper__content">cards</div></li>
      <li gwGuideStep [n]="3" state="upcoming" heading="Check that it works"></li>
    </ol>
  `,
})
class Guide {
  readonly first = signal<GuideStepState>('current');
  readonly second = signal<GuideStepState>('upcoming');
  readonly level = signal(3);
}

describe('Guide step (gw-stepper --guide)', () => {
  it('draws the marker and the label as a heading; the current step is aria-current, a done one says so', async () => {
    const fixture = TestBed.createComponent(Guide);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const steps = () => Array.from(el.querySelectorAll<HTMLElement>('li.gw-stepper__step'));
    const row = (s: HTMLElement) => [s.getAttribute('data-state'), s.getAttribute('aria-current'), text(s.querySelector('.gw-stepper__marker')), text(s.querySelector('.gw-stepper__label'))];
    expect(steps().map(row)).toEqual([
      ['current', 'step', '1', 'Add your first connection'],
      ['upcoming', null, '2', 'Connect your AI app'],
      ['upcoming', null, '3', 'Check that it works'],
    ]);
    expect(steps().every((s) => s.querySelector('.gw-stepper__marker')!.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(steps().map((s) => s.querySelector('.gw-stepper__label')!.tagName)).toEqual(['H3', 'H3', 'H3']);
    // What the page puts in a step follows its label, in order.
    expect(text(steps()[0].querySelector('h3 + .gw-stepper__description'))).toBe('A connection is something your AI can work with.');
    expect(text(steps()[1].querySelector('h3 + .gw-stepper__content'))).toBe('cards');

    fixture.componentInstance.first.set('complete');
    fixture.componentInstance.second.set('current');
    fixture.componentInstance.level.set(4);
    fixture.detectChanges();
    expect(steps().map(row).slice(0, 2)).toEqual([
      ['complete', null, '', 'Add your first connection, completed'],
      ['current', 'step', '2', 'Connect your AI app'],
    ]);
    expect(steps()[0].querySelector('.gw-stepper__marker svg')).not.toBeNull();
    expect(steps()[0].querySelector('.gw-stepper__label .gw-sr-only')?.textContent).toBe(', completed');
    expect(steps()[1].querySelector('.gw-stepper__label')!.tagName).toBe('H4');
  });
});
