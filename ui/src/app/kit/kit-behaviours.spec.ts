import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Announcer } from './announcer';
import { AriaDisabledGuard } from './aria-disabled';
import { GwChart } from './chart';
import { COPY_FAILED } from './clipboard';
import { COPIED_MS, GwCopy } from './copy';
import { GwCountdown } from './countdown';
import { TimeService } from '../core/time/time.service';
import { DialogStack, GwDialog, GwDialogClose, GwDialogNext, GwDialogOpen } from './dialog';
import { GwExpand, GwToggle } from './disclosure';
import { GwEnableWhen } from './enable-when';
import { GwFileDrop } from './file-drop';
import { GwMenu, GwMenuTrigger, MenuPick } from './menu';
import { GwPressed } from './pressed';
import { GwReveal } from './reveal';
import { GwSearchClearButton } from './search';
import { ShortcutService } from './shortcuts';
import { GwSidebarToggle } from './sidebar';
import { GwSelectable, GwSortRow, SortChange } from './table';
import { GwFragmentTabs, GwTabs, selectFromFragment } from './tabs';
import { GwThemeControl } from './theme';
import { GwToast } from './toast-region';
import { TOAST_CAP, ToastService } from './toast.service';
import { TooltipDismiss } from './tooltip';

/**
 * Parity of the kit's behaviours (design/assets/kit.js) as Angular directives: each test drives the DOM the way a
 * person does (click, keys) and checks the attributes kit.js would set.
 */

const key = (el: Element, k: string, init: KeyboardEventInit = {}) => {
  const e = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });
  el.dispatchEvent(e);
  return e;
};

async function render<T>(type: new () => T): Promise<{ fixture: ComponentFixture<T>; el: HTMLElement; host: T }> {
  const fixture = TestBed.createComponent(type);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
}

const q = <E extends Element = HTMLElement>(root: ParentNode, sel: string) => root.querySelector<E>(sel) as E;

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
});

describe('kit.js parity: aria-disabled guard', () => {
  @Component({
    imports: [],
    template: `
      <button id="off" type="button" aria-disabled="true" aria-describedby="why" (click)="clicks.set(clicks() + 1)">Approve</button>
      <p id="why">Add a comment first</p>
      <label><input id="box" type="checkbox" aria-disabled="true"> Ack</label>
      <form (submit)="submits.set(submits() + 1); $event.preventDefault()"><button id="sub" type="submit" aria-disabled="true">Save</button></form>
    `,
  })
  class Host {
    clicks = signal(0);
    submits = signal(0);
  }

  beforeEach(() => TestBed.inject(AriaDisabledGuard).install());

  it('a click never reaches the control, focus stays possible', async () => {
    const { el, host } = await render(Host);
    const btn = q<HTMLButtonElement>(el, '#off');
    btn.click();
    expect(host.clicks()).toBe(0);
    btn.focus();
    expect(document.activeElement).toBe(btn);
  });

  it('Enter and Space are cancelled', async () => {
    const { el } = await render(Host);
    expect(key(q(el, '#off'), 'Enter').defaultPrevented).toBe(true);
    expect(key(q(el, '#off'), ' ').defaultPrevented).toBe(true);
    expect(key(q(el, '#off'), 'a').defaultPrevented).toBe(false);
  });

  it('a checkbox does not change, through its label either', async () => {
    const { el } = await render(Host);
    const box = q<HTMLInputElement>(el, '#box');
    q(el, 'label').click();
    box.click();
    expect(box.checked).toBe(false);
  });

  it('a submit by an unavailable button is cancelled', async () => {
    const { el, host } = await render(Host);
    q<HTMLButtonElement>(el, '#sub').click();
    expect(host.submits()).toBe(0);
  });
});

describe('kit.js parity: menus', () => {
  @Component({
    imports: [GwMenu, GwMenuTrigger],
    template: `
      <div class="gw-menu-host">
        <button id="trigger" type="button" aria-haspopup="menu" [gwMenuTrigger]="m"><span data-gw-menu-value>Last 24 hours</span></button>
        <div class="gw-menu" gwMenu #m="gwMenu" id="range" role="menu" hidden (picked)="picks.push($event)">
          <div class="gw-menu__group" role="group">
            <button class="gw-menu__item" type="button" role="menuitemradio" aria-checked="false" data-value="1h"><span class="gw-menu__label">Last hour</span></button>
            <button class="gw-menu__item" type="button" role="menuitemradio" aria-checked="true" data-value="24h"><span class="gw-menu__label">Last 24 hours</span></button>
          </div>
          <button class="gw-menu__item" type="button" role="menuitem" data-value="custom"><span class="gw-menu__label">Custom…</span></button>
        </div>
      </div>
      <p id="outside">outside</p>
    `,
  })
  class Host {
    picks: MenuPick[] = [];
  }

  it('opens on click at the checked item, ↑/↓ wrap, Esc closes and refocuses the trigger', async () => {
    const { el, fixture } = await render(Host);
    const trigger = q(el, '#trigger');
    const panel = q(el, '#range');
    trigger.click();
    fixture.detectChanges();
    expect(panel.hidden).toBe(false);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const items = Array.from(panel.querySelectorAll<HTMLElement>('.gw-menu__item'));
    expect(document.activeElement).toBe(items[1]);
    key(items[1], 'ArrowDown');
    expect(document.activeElement).toBe(items[2]);
    key(items[2], 'ArrowDown');
    expect(document.activeElement).toBe(items[0]);
    key(items[0], 'Escape');
    expect(panel.hidden).toBe(true);
    expect(document.activeElement).toBe(trigger);
  });

  it('a radio pick checks only itself, names the trigger, reports and closes', async () => {
    const { el, host } = await render(Host);
    q(el, '#trigger').click();
    const items = Array.from(el.querySelectorAll<HTMLElement>('[role="menuitemradio"]'));
    items[0].click();
    expect(items.map((i) => i.getAttribute('aria-checked'))).toEqual(['true', 'false']);
    expect(q(el, '[data-gw-menu-value]').textContent).toBe('Last hour');
    expect(host.picks.map((p) => [p.value, p.role])).toEqual([['1h', 'menuitemradio']]);
    expect(q(el, '#range').hidden).toBe(true);
  });

  it('an outside click closes without moving focus', async () => {
    const { el } = await render(Host);
    q(el, '#trigger').click();
    q(el, '#outside').click();
    expect(q(el, '#range').hidden).toBe(true);
  });
});

describe('kit.js parity: tabs', () => {
  @Component({
    imports: [GwTabs],
    template: `
      <div role="tablist" gwTabs>
        <button role="tab" id="t1" aria-controls="p1" aria-selected="true">One</button>
        <button role="tab" id="t2" aria-controls="p2" aria-disabled="true">Two</button>
        <button role="tab" id="t3" aria-controls="p3">Three</button>
      </div>
      <div role="tabpanel" id="p1">1</div><div role="tabpanel" id="p2">2</div><div role="tabpanel" id="p3">3</div>
    `,
  })
  class Host {}

  it('selects on click and with arrows (skipping unavailable tabs), panels follow', async () => {
    const { el } = await render(Host);
    const [t1, , t3] = Array.from(el.querySelectorAll<HTMLElement>('[role="tab"]'));
    expect([q(el, '#p1').hidden, q(el, '#p2').hidden, q(el, '#p3').hidden]).toEqual([false, true, true]);
    expect(t1.tabIndex).toBe(0);
    key(t1, 'ArrowRight');
    expect(t3.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(t3);
    expect(q(el, '#p3').hidden).toBe(false);
    key(t3, 'Home');
    expect(t1.getAttribute('aria-selected')).toBe('true');
    t3.click();
    expect(q(el, '#p1').hidden).toBe(true);
    expect(t1.tabIndex).toBe(-1);
  });
});

describe('kit.js parity: dialogs', () => {
  @Component({
    imports: [GwDialog, GwDialogOpen, GwDialogClose],
    template: `
      <button id="opener" type="button" [gwDialogOpen]="d">Open</button>
      <dialog gwDialog #d="gwDialog" (closed)="closed.push($event)">
        <input id="first" autofocus>
        <button id="delete" type="button" gwDialogClose="delete">Delete</button>
      </dialog>
    `,
  })
  class Host {
    closed: string[] = [];
    readonly d = viewChild.required<GwDialog>('d');
  }

  it('opens modally with the autofocus field, closes with a value and returns focus to the opener', async () => {
    const { el, host } = await render(Host);
    const opener = q(el, '#opener');
    opener.focus();
    opener.click();
    const dialog = q<HTMLDialogElement>(el, 'dialog');
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.hasAttribute('data-gw-modal')).toBe(true);
    expect(document.activeElement).toBe(q(el, '#first'));
    q(el, '#delete').click();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(host.closed).toEqual(['delete']);
    expect(document.activeElement).toBe(opener);
  });

  it('a new opening never inherits the last answer', async () => {
    const { el, host } = await render(Host);
    q(el, '#opener').click();
    q(el, '#delete').click();
    host.d().open();
    host.d().close();
    expect(host.closed).toEqual(['delete', '']);
  });
});

describe('kit.js parity: disclosure and View raw', () => {
  @Component({
    imports: [GwToggle, GwExpand],
    template: `
      <button id="toggle" type="button" gwToggle aria-controls="raw">Show JSON</button>
      <button id="view" type="button" gwExpand="#raw">View raw</button>
      <div id="raw" hidden><div id="region" tabindex="0">{{ '{}' }}</div></div>
    `,
  })
  class Host {}

  it('toggles hidden and aria-expanded; View raw opens it and puts focus inside', async () => {
    const { el } = await render(Host);
    const toggle = q(el, '#toggle');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    expect(q(el, '#raw').hidden).toBe(false);
    toggle.click();
    expect(q(el, '#raw').hidden).toBe(true);
    q(el, '#view').click();
    expect(q(el, '#raw').hidden).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(q(el, '#region'));
  });
});

describe('kit.js parity: copy', () => {
  @Component({
    imports: [GwCopy, GwToast, GwDialog],
    template: `
      <button id="copy" type="button" gwCopy="mcp-gateway://activity/events/412">Copy link</button>
      <code id="src">sudo chown</code><button id="from" type="button" gwCopyFrom="#src">Copy</button>
      <button id="toasted" type="button" gwCopy="412" gwToast="Link to event 412 copied" gwToastTone="success">Copy link</button>
      <dialog gwDialog #dlg="gwDialog" id="dlg" aria-label="Token"><code id="token">gwt_live_1</code><button id="in-dialog" type="button" gwCopyFrom="#token">Copy</button></dialog>
    `,
  })
  class Host {
    readonly dlg = viewChild.required<GwDialog>('dlg');
  }

  const clipboardOf = (writeText: (t: string) => Promise<void>) =>
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });

  afterEach(() => {
    Reflect.deleteProperty(navigator, 'clipboard');
    Reflect.deleteProperty(document, 'execCommand');
  });

  it('copies the text, shows Copied for a while and announces it', async () => {
    const copied: string[] = [];
    clipboardOf(async (t) => void copied.push(t));
    vi.useFakeTimers();
    const { el } = await render(Host);
    q(el, '#copy').click();
    q(el, '#from').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(copied).toEqual(['mcp-gateway://activity/events/412', 'sudo chown']);
    expect(q(el, '#copy').hasAttribute('data-copied')).toBe(true);
    await vi.advanceTimersByTimeAsync(60);
    expect(document.querySelector('[role="status"]')?.textContent).toBe('Copied to clipboard');
    await vi.advanceTimersByTimeAsync(COPIED_MS);
    expect(q(el, '#copy').hasAttribute('data-copied')).toBe(false);
  });

  it('a refused write shows no Copied state and says so (no toast either)', async () => {
    clipboardOf(() => Promise.reject(new DOMException('Document is not focused', 'NotAllowedError')));
    Object.defineProperty(document, 'execCommand', { configurable: true, value: () => false });
    vi.useFakeTimers();
    const { el } = await render(Host);
    q(el, '#copy').click();
    q(el, '#toasted').click();
    await vi.advanceTimersByTimeAsync(60);
    expect(q(el, '#copy').hasAttribute('data-copied')).toBe(false);
    expect(document.querySelector('[role="alert"]')?.textContent).toBe(COPY_FAILED);
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('a toast on the button appears only once the copy worked, instead of the announcement', async () => {
    clipboardOf(async () => undefined);
    const { el } = await render(Host);
    q(el, '#toasted').click();
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    await new Promise((r) => setTimeout(r, 0));
    expect(TestBed.inject(ToastService).toasts().map((t) => t.body)).toEqual(['Link to event 412 copied']);
  });

  it('inside an open modal dialog the fallback copies from the dialog, not from the inert body', async () => {
    // No async clipboard (an old engine or a refused permission): the textarea path must live in the top layer.
    let parent: Element | null = null;
    let selected = '';
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: (cmd: string) => {
        const area = document.activeElement as HTMLTextAreaElement;
        parent = area.parentElement;
        selected = area.value.slice(area.selectionStart, area.selectionEnd);
        return cmd === 'copy';
      },
    });
    vi.useFakeTimers();
    const { el, host } = await render(Host);
    const button = q<HTMLButtonElement>(el, '#in-dialog');
    host.dlg().open(button);
    button.focus();
    button.click();
    await vi.advanceTimersByTimeAsync(60);
    expect(parent).toBe(q(el, '#dlg'));
    expect(selected).toBe('gwt_live_1');
    expect(document.activeElement).toBe(button);
    expect(q(el, '#dlg textarea')).toBeNull();
    expect(button.hasAttribute('data-copied')).toBe(true);
    expect(q(el, '#dlg [role="status"]')?.textContent).toBe('Copied to clipboard');
  });
});

describe('kit.js parity: announce', () => {
  it('a polite and an assertive message in the same moment are both read', () => {
    vi.useFakeTimers();
    const announcer = TestBed.inject(Announcer);
    announcer.announce('Copied to clipboard');
    vi.advanceTimersByTime(10);
    announcer.announce('sql_execute on dev-db expired, denied automatically', { assertive: true });
    vi.advanceTimersByTime(60);
    expect(document.querySelector('[role="status"]')?.textContent).toBe('Copied to clipboard');
    expect(document.querySelector('[role="alert"]')?.textContent).toBe('sql_execute on dev-db expired, denied automatically');
    // The same region twice: the later text is the one left in it (kit.js: each call refills after 50 ms).
    announcer.announce('Sorted by Name, ascending');
    announcer.announce('Sorted by Name, descending');
    vi.advanceTimersByTime(60);
    expect(document.querySelector('[role="status"]')?.textContent).toBe('Sorted by Name, descending');
  });
});

describe('kit.js parity: toasts', () => {
  it('stacks at most three, a toast with an action stays, icons follow the tone', () => {
    const toasts = TestBed.inject(ToastService);
    for (let i = 0; i < TOAST_CAP + 1; i++) toasts.show({ title: `t${i}` });
    expect(toasts.toasts().map((t) => t.title)).toEqual(['t1', 't2', 't3']);
    const undo = toasts.show({ title: 'Disconnected', tone: 'success', actions: [{ label: 'Undo' }] });
    expect(undo.timeout).toBe(0);
    expect(undo.icon).toBe('circle-check');
    expect(toasts.show({ title: 'x' }).timeout).toBe(6000);
    toasts.dismiss(undo.id);
    expect(toasts.toasts().some((t) => t.id === undo.id)).toBe(false);
  });
});

describe('kit.js parity: tables', () => {
  @Component({
    imports: [GwSortRow, GwSelectable],
    template: `
      <table gwSelectable (selectionChange)="selected = $event">
        <thead><tr gwSortRow (sortChange)="sorts.push($event)">
          <th><input type="checkbox" data-gw-select-all></th>
          <th aria-sort="none" data-sort-key="time"><button class="gw-table__sort" type="button">Time</button></th>
          <th aria-sort="descending"><button class="gw-table__sort" type="button">Tool</button></th>
        </tr></thead>
        <tbody>
          <tr><td><input type="checkbox" data-gw-select-row value="a"></td></tr>
          <tr><td><input type="checkbox" data-gw-select-row value="b"></td></tr>
        </tbody>
      </table>
    `,
  })
  class Host {
    sorts: SortChange[] = [];
    selected: string[] = [];
  }

  it('sorts one column at a time: ascending, then descending', async () => {
    const { el, host } = await render(Host);
    const [time, tool] = Array.from(el.querySelectorAll<HTMLElement>('th[aria-sort]'));
    q(time, 'button').click();
    expect([time.getAttribute('aria-sort'), tool.getAttribute('aria-sort')]).toEqual(['ascending', 'none']);
    q(time, 'button').click();
    expect(time.getAttribute('aria-sort')).toBe('descending');
    expect(host.sorts).toEqual([{ key: 'time', direction: 'ascending' }, { key: 'time', direction: 'descending' }]);
  });

  it('selects all, mirrors rows and shows a partial selection as indeterminate', async () => {
    const { el, host } = await render(Host);
    const all = q<HTMLInputElement>(el, '[data-gw-select-all]');
    const rows = Array.from(el.querySelectorAll<HTMLInputElement>('[data-gw-select-row]'));
    rows[0].click();
    expect(all.indeterminate).toBe(true);
    expect(rows[0].closest('tr')!.getAttribute('aria-selected')).toBe('true');
    expect(host.selected).toEqual(['a']);
    all.click();
    expect(rows.every((r) => r.checked)).toBe(true);
    expect(host.selected).toEqual(['a', 'b']);
  });
});

describe('kit.js parity: small controls', () => {
  @Component({
    imports: [GwSidebarToggle, GwSearchClearButton, GwReveal, GwPressed, GwEnableWhen, GwChart, GwFileDrop],
    template: `
      <div class="gw-shell"><button id="side" type="button" gwSidebarToggle>Collapse</button></div>
      <div class="gw-search"><input id="q" class="gw-search__input" value="sql" (input)="typed = $any($event.target).value"><button class="gw-search__clear" type="button">x</button></div>
      <input id="pw" type="password"><button id="reveal" type="button" gwReveal aria-controls="pw">Show</button>
      <button id="live" type="button" gwPressed>Live</button>
      <input id="ack" type="checkbox"><button id="go" type="button" disabled [gwEnableWhen]="'#ack'">Done</button>
      <div class="gw-chart" (rangeChange)="range = $event">
        <button class="gw-chart__bar" type="button" aria-pressed="false" aria-label="10:00–11:00">a</button>
        <button class="gw-chart__bar" type="button" aria-pressed="false" aria-label="11:00–12:00">b</button>
      </div>
      <label class="gw-file-drop" [gwFileDropMaxBytes]="10"><input id="file" type="file"><span data-gw-file-name></span></label>
    `,
  })
  class Host {
    typed = 'sql';
    range: number | null | undefined = undefined;
  }

  it('sidebar: collapses the shell and says what the button will do', async () => {
    const { el } = await render(Host);
    q(el, '#side').click();
    expect(q(el, '.gw-shell').hasAttribute('data-collapsed')).toBe(true);
    expect(q(el, '#side').getAttribute('aria-expanded')).toBe('false');
    expect(q(el, '#side').getAttribute('aria-label')).toBe('Expand sidebar');
  });

  it('search clear empties the field, fires input and refocuses', async () => {
    const { el, host } = await render(Host);
    q(el, '.gw-search__clear').click();
    expect(q<HTMLInputElement>(el, '#q').value).toBe('');
    expect(host.typed).toBe('');
    expect(document.activeElement).toBe(q(el, '#q'));
  });

  it('reveal and pressed flip their state', async () => {
    const { el, fixture } = await render(Host);
    q(el, '#reveal').click();
    q(el, '#live').click();
    fixture.detectChanges();
    expect(q<HTMLInputElement>(el, '#pw').type).toBe('text');
    expect(q(el, '#reveal').getAttribute('aria-pressed')).toBe('true');
    expect(q(el, '#live').getAttribute('aria-pressed')).toBe('true');
  });

  it('enable-when: unavailable (not natively disabled) until the box is checked', async () => {
    const { el } = await render(Host);
    const go = q<HTMLButtonElement>(el, '#go');
    expect(go.disabled).toBe(false);
    expect(go.getAttribute('aria-disabled')).toBe('true');
    q(el, '#ack').click();
    expect(go.hasAttribute('aria-disabled')).toBe(false);
  });

  it('chart: one pressed bar per chart; a second click releases it', async () => {
    const { el, host } = await render(Host);
    const [a, b] = Array.from(el.querySelectorAll<HTMLElement>('.gw-chart__bar'));
    a.click();
    b.click();
    expect([a.getAttribute('aria-pressed'), b.getAttribute('aria-pressed')]).toEqual(['false', 'true']);
    expect(host.range).toBe(1);
    b.click();
    expect(b.getAttribute('aria-pressed')).toBe('false');
    expect(host.range).toBeNull();
  });

  it('file drop: the chosen file is named; too large is an error', async () => {
    const { el } = await render(Host);
    const input = q<HTMLInputElement>(el, '#file');
    const drop = q(el, '.gw-file-drop');
    const pick = (f: File) => {
      Object.defineProperty(input, 'files', { value: [f], configurable: true });
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    pick(new File(['12345'], 'api.yaml'));
    expect(drop.getAttribute('data-state')).toBe('uploaded');
    expect(q(el, '[data-gw-file-name]').textContent).toBe('api.yaml');
    pick(new File(['x'.repeat(20)], 'big.jar'));
    expect(drop.getAttribute('data-state')).toBe('error');
    expect(input.getAttribute('aria-invalid')).toBe('true');
  });
});

describe('kit.js parity: keyboard shortcuts', () => {
  @Component({
    template: `<input id="search" aria-keyshortcuts="/"><textarea id="note"></textarea>`,
  })
  class Host {}

  it('"/" focuses the search field unless typing; ⌘K toggles the palette', async () => {
    const shortcuts = TestBed.inject(ShortcutService);
    shortcuts.install();
    let toggles = 0;
    shortcuts.registerPalette(() => toggles++);
    const { el } = await render(Host);
    const search = q(el, '#search');
    search.getClientRects = () => [{}] as unknown as DOMRectList;
    const slash = key(document.body, '/');
    expect(slash.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(search);
    const note = q<HTMLTextAreaElement>(el, '#note');
    note.focus();
    expect(key(note, '/').defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(note);
    key(document.body, 'k', { metaKey: true });
    key(document.body, 'k', { ctrlKey: true });
    expect(toggles).toBe(2);
    shortcuts.setSingleKeys(false);
    search.blur();
    expect(key(document.body, '/').defaultPrevented).toBe(false);
    shortcuts.setSingleKeys(true);
  });
});

describe('kit.js parity: countdown', () => {
  const now = signal(Date.parse('2026-10-22T14:30:00+02:00'));
  const fakeTime = {
    now,
    minute: now,
    secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  };

  @Component({
    imports: [GwCountdown],
    template: `
      <div gwCountdown="2026-10-22T14:32:00+02:00" [gwCountdownTotal]="300" gwCountdownAnnounce="1 minute left to decide" #c="gwCountdown" (ended)="ended = ended + 1">
        <p role="timer"><i class="gw-fg-warning"></i>{{ c.text() }}</p>
        <div class="gw-progress gw-progress--warning"></div>
      </div>
    `,
  })
  class Host {
    ended = 0;
  }

  beforeEach(() => TestBed.overrideProvider(TimeService, { useValue: fakeTime }));

  it('counts down on the core clock, turns urgent in the last minute and ends once', async () => {
    vi.useFakeTimers();
    const { el, fixture, host } = await render(Host);
    const box = q(el, '[gwCountdown]');
    expect(q(el, '[role="timer"]').textContent).toBe('2:00');
    expect(box.style.getPropertyValue('--gw-countdown')).toBe('0.4');
    expect(box.hasAttribute('data-urgent')).toBe(false);
    now.set(now() + 75_000);
    fixture.detectChanges();
    expect(q(el, '[role="timer"]').textContent).toBe('0:45');
    expect(box.hasAttribute('data-urgent')).toBe(true);
    expect(q(el, '.gw-progress').classList.contains('gw-progress--danger')).toBe(true);
    expect(q(el, '[role="timer"]').classList.contains('gw-fg-danger')).toBe(true);
    vi.advanceTimersByTime(60);
    expect(document.querySelector('[role="alert"]')?.textContent).toBe('1 minute left to decide');
    now.set(now() + 60_000);
    fixture.detectChanges();
    now.set(now() + 1_000);
    fixture.detectChanges();
    expect(box.hasAttribute('data-expired')).toBe(true);
    expect(q(el, '.gw-progress').hidden).toBe(true);
    expect(host.ended).toBe(1);
  });

  @Component({
    imports: [GwCountdown],
    template: `
      <div id="cd" [gwCountdown]="deadline()" gwCountdownAnnounce="1 minute left to decide" #c="gwCountdown" (ended)="ended = ended + 1">
        <p role="timer"><i class="gw-fg-warning"></i>{{ c.text() }}</p>
        <div class="gw-progress gw-progress--warning"></div>
      </div>
    `,
  })
  class Switching {
    readonly deadline = signal('2026-10-22T14:30:48+02:00');
    ended = 0;
  }

  it('a new deadline is a new countdown: tone, bar, announcement and end start over', async () => {
    vi.useFakeTimers();
    now.set(Date.parse('2026-10-22T14:30:00+02:00'));
    const { el, fixture, host } = await render(Switching);
    const box = q(el, '#cd');
    const alert = () => document.querySelector('[role="alert"]')?.textContent ?? '';
    vi.advanceTimersByTime(60);
    expect(alert()).toBe('1 minute left to decide');
    now.set(now() + 49_000);
    fixture.detectChanges();
    expect(host.ended).toBe(1);
    expect(q(el, '.gw-progress').hidden).toBe(true);

    // Another request in the same element: 4:12 left, its own 100 %.
    host.deadline.set('2026-10-22T14:35:01+02:00');
    fixture.detectChanges();
    expect(box.hasAttribute('data-urgent')).toBe(false);
    expect(box.hasAttribute('data-expired')).toBe(false);
    expect(box.style.getPropertyValue('--gw-countdown')).toBe('1');
    expect(q(el, '.gw-progress').hidden).toBe(false);
    expect(q(el, '.gw-progress').classList.contains('gw-progress--warning')).toBe(true);
    expect(q(el, '.gw-progress').classList.contains('gw-progress--danger')).toBe(false);
    expect(q(el, '[role="timer"]').classList.contains('gw-fg-danger')).toBe(false);
    expect(q(el, '[role="timer"] i').classList.contains('gw-fg-warning')).toBe(true);

    document.querySelector('[role="alert"]')!.textContent = '';
    now.set(Date.parse('2026-10-22T14:34:30+02:00'));
    fixture.detectChanges();
    vi.advanceTimersByTime(60);
    expect(box.hasAttribute('data-urgent')).toBe(true);
    expect(alert()).toBe('1 minute left to decide');
    now.set(Date.parse('2026-10-22T14:35:01+02:00'));
    fixture.detectChanges();
    expect(host.ended).toBe(2);
  });
});

// ───────────────────────── the rest of kit.js, one test per behaviour ─────────────────────────

describe('kit.js parity: aria-disabled radios and Space released', () => {
  @Component({
    template: `
      <div role="radiogroup">
        <label><input id="ro" type="radio" name="mode" value="read" aria-disabled="true" aria-describedby="why"> Read only</label>
        <label><input id="ap" type="radio" name="mode" value="approval" checked> Approval</label>
      </div>
      <p id="why">sql_execute changes data</p>
      <button id="off" type="button" aria-disabled="true">Approve</button>
    `,
  })
  class Host {}

  beforeEach(() => TestBed.inject(AriaDisabledGuard).install());

  it('an arrow key that checks an unavailable radio is reverted to the one checked before', async () => {
    const { el } = await render(Host);
    const ro = q<HTMLInputElement>(el, '#ro');
    const ap = q<HTMLInputElement>(el, '#ap');
    // The browser's arrow key moves AND checks: snapshot on keydown, then the change lands on Read only.
    key(ap, 'ArrowUp');
    ro.checked = true;
    ro.dispatchEvent(new Event('change', { bubbles: true }));
    expect(ro.checked).toBe(false);
    expect(ap.checked).toBe(true);
  });

  it('Space released on an unavailable control is cancelled too', async () => {
    const { el } = await render(Host);
    const up = new KeyboardEvent('keyup', { key: ' ', bubbles: true, cancelable: true });
    q(el, '#off').dispatchEvent(up);
    expect(up.defaultPrevented).toBe(true);
    const other = new KeyboardEvent('keyup', { key: 'Enter', bubbles: true, cancelable: true });
    q(el, '#off').dispatchEvent(other);
    expect(other.defaultPrevented).toBe(false);
  });
});

describe('kit.js parity: menus, the rest', () => {
  @Component({
    imports: [GwMenu, GwMenuTrigger],
    template: `
      <div class="gw-menu-host">
        <button id="trigger" type="button" aria-haspopup="menu" [gwMenuTrigger]="m">Columns</button>
        <div class="gw-menu" gwMenu #m="gwMenu" id="cols" role="menu" hidden (picked)="picks.push($event.value)">
          <button class="gw-menu__item" type="button" role="menuitemcheckbox" aria-checked="true" data-value="tool"><span class="gw-menu__label">Tool</span></button>
          <button class="gw-menu__item" type="button" role="menuitemcheckbox" aria-checked="false" data-value="client"><span class="gw-menu__label">Client</span></button>
          <a class="gw-menu__item" id="docs" href="#" data-gw-menu-close>Docs</a>
        </div>
      </div>
      <div class="gw-menu-host">
        <button id="pop" type="button" aria-haspopup="dialog" [gwMenuTrigger]="p">Appearance</button>
        <div class="gw-popover" gwMenu #p="gwMenu" id="popover" role="dialog" hidden><input id="field"></div>
      </div>
      <button id="elsewhere" type="button">Elsewhere</button>
    `,
  })
  class Host {
    picks: string[] = [];
  }

  it('↓ on the trigger opens on the first item, ↑ on the last; nothing opens a popover that way', async () => {
    const { el } = await render(Host);
    const trigger = q(el, '#trigger');
    const items = Array.from(el.querySelectorAll<HTMLElement>('#cols .gw-menu__item'));
    trigger.focus();
    // A checked menuitemcheckbox is where the menu starts, as kit.js does for every checked item.
    key(trigger, 'ArrowDown');
    expect(q(el, '#cols').hidden).toBe(false);
    expect(document.activeElement).toBe(items[0]);
    key(items[0], 'Escape');
    items[0].setAttribute('aria-checked', 'false');
    key(trigger, 'ArrowUp');
    expect(document.activeElement).toBe(items[2]);
    key(items[2], 'Escape');
    const pop = q(el, '#pop');
    expect(key(pop, 'ArrowDown').defaultPrevented).toBe(false);
    expect(q(el, '#popover').hidden).toBe(true);
  });

  it('a menuitemcheckbox flips and the menu stays open; data-gw-menu-close closes and refocuses', async () => {
    const { el, host } = await render(Host);
    q(el, '#trigger').click();
    const client = q(el, '[data-value="client"]');
    client.click();
    expect(client.getAttribute('aria-checked')).toBe('true');
    expect(host.picks).toEqual(['client']);
    expect(q(el, '#cols').hidden).toBe(false);
    q(el, '#docs').click();
    expect(q(el, '#cols').hidden).toBe(true);
    expect(document.activeElement).toBe(q(el, '#trigger'));
  });

  it('Tab closes a menu without moving focus back; focus leaving a popover closes it', async () => {
    vi.useFakeTimers();
    const { el } = await render(Host);
    q(el, '#trigger').click();
    const first = q(el, '#cols .gw-menu__item');
    key(first, 'Tab');
    expect(q(el, '#cols').hidden).toBe(true);
    expect(document.activeElement).toBe(first);

    q(el, '#pop').click();
    expect(q(el, '#popover').hidden).toBe(false);
    expect(document.activeElement).toBe(q(el, '#field'));
    q(el, '#elsewhere').focus();
    vi.advanceTimersByTime(1);
    expect(q(el, '#popover').hidden).toBe(true);
  });
});

describe('kit.js parity: tabs from the URL fragment', () => {
  @Component({
    imports: [GwTabs],
    template: `
      <div role="tablist" gwTabs>
        <button role="tab" id="dev-tab" aria-controls="dev" aria-selected="true">dev</button>
        <button role="tab" id="prod-tab" aria-controls="prod">prod</button>
      </div>
      <div role="tabpanel" id="dev">dev</div>
      <div role="tabpanel" id="prod"><section id="prod-approvals">Approvals</section></div>
    `,
  })
  class Host {}

  it('#<id> inside a hidden panel selects its tab first (kit.js tabFromHash)', async () => {
    const { el } = await render(Host);
    expect(q(el, '#prod').hidden).toBe(true);
    expect(selectFromFragment(document, 'prod-approvals')).toBe(true);
    expect(q(el, '#prod-tab').getAttribute('aria-selected')).toBe('true');
    expect(q(el, '#prod').hidden).toBe(false);
    expect(q(el, '#dev').hidden).toBe(true);
    expect(selectFromFragment(document, 'javascript:alert(1)')).toBe(false);
    expect(selectFromFragment(document, 'nothing-here')).toBe(false);
  });

  it('a same-route navigation that only changes the fragment selects the tab (kit.js hashchange)', async () => {
    @Component({ selector: 'gw-test-page', imports: [GwTabs], template: `<div role="tablist" gwTabs><button role="tab" id="a-tab" aria-controls="a" aria-selected="true">a</button><button role="tab" id="b-tab" aria-controls="b">b</button></div><div role="tabpanel" id="a">a</div><div role="tabpanel" id="b"><p id="b-inner">b</p></div>` })
    class Page {}
    @Component({ imports: [Page], hostDirectives: [GwFragmentTabs], template: `<gw-test-page />` })
    class Shell {}
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'policies', children: [] }])] });
    const { el, fixture } = await render(Shell);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/policies');
    await router.navigateByUrl('/policies#b-inner');
    vi.advanceTimersByTime(1);
    fixture.detectChanges();
    expect(q(el, '#b-tab').getAttribute('aria-selected')).toBe('true');
    expect(q(el, '#b').hidden).toBe(false);
  });
});

describe('kit.js parity: dialog steps, guard and backdrop', () => {
  @Component({
    imports: [GwDialog, GwDialogOpen, GwDialogClose, GwDialogNext],
    template: `
      <button id="opener" type="button" [gwDialogOpen]="one">Issue token</button>
      <dialog gwDialog #one="gwDialog" id="one">
        <form method="dialog" [gwDialogNext]="two"><input id="name" required autofocus><button id="next" type="submit">Issue</button></form>
      </dialog>
      <dialog gwDialog #two="gwDialog" id="two" gwDialogStatic [gwDialogGuard]="true" (closed)="closed.push($event)">
        <form method="dialog"><input id="stored" type="checkbox" required><button id="done" type="submit" autofocus>Done</button></form>
      </dialog>
      <dialog gwDialog #plain="gwDialog" id="plain"><button id="x" type="button" gwDialogClose>Close</button></dialog>
      <dialog gwDialog #fixed="gwDialog" id="fixed" gwDialogStatic><button type="button" gwDialogClose>Close</button></dialog>
    `,
  })
  class Host {
    closed: string[] = [];
    readonly plain = viewChild.required<GwDialog>('plain');
    readonly fixed = viewChild.required<GwDialog>('fixed');
  }

  it('a valid step hands over to the next dialog, and the last one returns focus to the first opener', async () => {
    const { el, host } = await render(Host);
    const opener = q(el, '#opener');
    opener.focus();
    opener.click();
    q<HTMLInputElement>(el, '#name').value = 'cursor-laptop';
    q(el, 'form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    expect(q(el, '#one').hasAttribute('open')).toBe(false);
    expect(q(el, '#two').hasAttribute('open')).toBe(true);
    expect(document.activeElement).toBe(q(el, '#done'));
    q<HTMLDialogElement>(el, '#two').close('done');
    expect(host.closed).toEqual(['done']);
    expect(document.activeElement).toBe(opener);
  });

  it('a guarded dialog cancels Esc while its form is invalid, and lets it through once valid', async () => {
    const { el, fixture } = await render(Host);
    const two = q<HTMLDialogElement>(el, '#two');
    two.showModal();
    fixture.detectChanges();
    expect(two.hasAttribute('data-gw-static')).toBe(true);
    const esc = new Event('cancel', { cancelable: true });
    two.dispatchEvent(esc);
    expect(esc.defaultPrevented).toBe(true);
    q<HTMLInputElement>(el, '#stored').checked = true;
    const again = new Event('cancel', { cancelable: true });
    two.dispatchEvent(again);
    expect(again.defaultPrevented).toBe(false);
  });

  it('a backdrop click closes a dialog, but not a static (form) one', async () => {
    const { el, host } = await render(Host);
    const outside = { bubbles: true, clientX: 5, clientY: 5 };
    host.plain().open();
    const plain = q<HTMLDialogElement>(el, '#plain');
    plain.getBoundingClientRect = () => ({ left: 100, right: 400, top: 100, bottom: 300 }) as DOMRect;
    plain.dispatchEvent(new MouseEvent('click', outside));
    expect(plain.hasAttribute('open')).toBe(false);

    host.fixed().open();
    const fixed = q<HTMLDialogElement>(el, '#fixed');
    fixed.getBoundingClientRect = () => ({ left: 100, right: 400, top: 100, bottom: 300 }) as DOMRect;
    fixed.dispatchEvent(new MouseEvent('click', outside));
    expect(fixed.hasAttribute('open')).toBe(true);
  });
});

describe('kit.js parity: remembered disclosures', () => {
  @Component({
    imports: [GwToggle],
    template: `<button id="t" type="button" gwToggle gwTogglePersist="how-secrets" aria-controls="how">How it works</button><div id="how" hidden>Steps</div>`,
  })
  class Host {}

  beforeEach(() => localStorage.removeItem('gw-toggle-how-secrets'));

  it('toggling writes gw-toggle-<key>; the next render opens as remembered', async () => {
    const first = await render(Host);
    q(first.el, '#t').click();
    expect(localStorage.getItem('gw-toggle-how-secrets')).toBe('open');
    first.fixture.destroy();
    document.body.innerHTML = '';

    const second = await render(Host);
    expect(q(second.el, '#t').getAttribute('aria-expanded')).toBe('true');
    expect(q(second.el, '#how').hidden).toBe(false);
    q(second.el, '#t').click();
    expect(localStorage.getItem('gw-toggle-how-secrets')).toBe('closed');
  });
});

describe('kit.js parity: tooltip Esc', () => {
  @Component({
    template: `<span class="gw-tooltip"><button id="tip" type="button" aria-describedby="tip-text">i</button><span class="gw-tooltip__bubble" id="tip-text" role="tooltip">Why</span></span><button id="other" type="button">x</button>`,
  })
  class Host {}

  it('Esc dismisses the focused tooltip until focus leaves it', async () => {
    TestBed.inject(TooltipDismiss).install();
    const { el } = await render(Host);
    q(el, '#tip').focus();
    key(q(el, '#tip'), 'Escape');
    expect(q(el, '.gw-tooltip').hasAttribute('data-dismissed')).toBe(true);
    q(el, '#other').focus();
    expect(q(el, '.gw-tooltip').hasAttribute('data-dismissed')).toBe(false);
  });
});

describe('kit.js parity: theme and contrast radios', () => {
  @Component({
    imports: [GwThemeControl],
    template: `
      <form id="a" method="dialog"><input type="radio" name="gw-theme" value="system"><input type="radio" name="gw-theme" value="dark"><input type="radio" name="gw-contrast" value="more"></form>
      <form id="b" method="dialog"><input type="radio" name="gw-theme" value="system"><input type="radio" name="gw-theme" value="dark"></form>
    `,
  })
  class Host {}

  afterEach(() => delete window.gwTheme);

  it('radios start from theme-boot, a pick sets it, every control on the page follows', async () => {
    let state: { theme: string; contrast: string } = { theme: 'system', contrast: 'system' };
    const sets: object[] = [];
    window.gwTheme = {
      get: () => ({ theme: state.theme, contrast: state.contrast, resolved: { theme: 'light', contrast: 'normal' } }) as never,
      set: (next) => {
        sets.push(next);
        state = { ...state, ...next };
        window.dispatchEvent(new Event('gw-theme-change'));
      },
    };
    const { el, fixture } = await render(Host);
    const radios = (form: string) => Array.from(el.querySelectorAll<HTMLInputElement>(`#${form} input[name="gw-theme"]`));
    expect(radios('a').map((r) => r.checked)).toEqual([true, false]);
    radios('a')[1].checked = true;
    radios('a')[1].dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    expect(sets).toEqual([{ theme: 'dark' }]);
    expect(radios('b').map((r) => r.checked)).toEqual([false, true]);
    const more = q<HTMLInputElement>(el, 'input[name="gw-contrast"]');
    more.checked = true;
    more.dispatchEvent(new Event('change', { bubbles: true }));
    expect(sets.at(-1)).toEqual({ contrast: 'more' });
  });
});

describe('kit.js parity: chart roving focus', () => {
  @Component({
    imports: [GwChart],
    template: `
      <div class="gw-chart">
        @for (b of bars(); track b) {
          <button class="gw-chart__bar" type="button" aria-pressed="false" [attr.aria-label]="b">{{ b }}</button>
        }
      </div>
    `,
  })
  class Host {
    bars = signal(['10:00', '11:00', '12:00']);
  }

  it('one tab stop per chart; arrows, Home and End move it', async () => {
    const { el } = await render(Host);
    const bars = () => Array.from(el.querySelectorAll<HTMLElement>('.gw-chart__bar'));
    expect(bars().map((b) => b.tabIndex)).toEqual([-1, -1, 0]);
    bars()[2].focus();
    key(bars()[2], 'ArrowLeft');
    expect(document.activeElement).toBe(bars()[1]);
    key(bars()[1], 'Home');
    expect(document.activeElement).toBe(bars()[0]);
    key(bars()[0], 'End');
    expect(document.activeElement).toBe(bars()[2]);
    expect(bars().map((b) => b.tabIndex)).toEqual([-1, -1, 0]);
  });

  it('bars rendered later for another range keep the single tab stop', async () => {
    const { el, fixture, host } = await render(Host);
    host.bars.set(['13:00', '14:00', '15:00', '16:00']);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    const stops = Array.from(el.querySelectorAll<HTMLElement>('.gw-chart__bar')).filter((b) => b.tabIndex === 0);
    expect(stops.map((b) => b.textContent)).toEqual(['16:00']);
  });
});

describe('kit.js parity: shortcuts, the rest', () => {
  @Component({ template: `<input id="search" aria-keyshortcuts="/"><dialog id="d"></dialog>` })
  class Host {}

  beforeEach(() => {
    localStorage.removeItem('gw-single-keys');
    document.documentElement.removeAttribute('data-gw-single-keys');
  });

  it('single-key shortcuts off: remembered, marked on <html>, and read back on the next start', async () => {
    const shortcuts = TestBed.inject(ShortcutService);
    shortcuts.install();
    shortcuts.setSingleKeys(false);
    expect(localStorage.getItem('gw-single-keys')).toBe('off');
    expect(document.documentElement.getAttribute('data-gw-single-keys')).toBe('off');
    expect(shortcuts.singleKeys()).toBe(false);

    document.documentElement.removeAttribute('data-gw-single-keys');
    TestBed.resetTestingModule();
    const again = TestBed.inject(ShortcutService);
    again.install();
    expect(again.singleKeys()).toBe(false);
    expect(document.documentElement.getAttribute('data-gw-single-keys')).toBe('off');
    again.setSingleKeys(true);
  });

  it('⌘, and Ctrl+, open Settings, never over an open dialog', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'settings/:section', children: [] }])] });
    const shortcuts = TestBed.inject(ShortcutService);
    shortcuts.install();
    const router = TestBed.inject(Router);
    const go = vi.spyOn(router, 'navigateByUrl');
    await render(Host);
    expect(key(document.body, ',', { metaKey: true }).defaultPrevented).toBe(true);
    expect(key(document.body, ',', { ctrlKey: true }).defaultPrevented).toBe(true);
    expect(go.mock.calls.map((c) => String(c[0]))).toEqual(['/settings/general', '/settings/general']);
    expect(key(document.body, ',', { ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false);
    // A decision dialog is open: the shortcut doesn't navigate away from it. (Only this router is asked: services of
    // earlier tests keep their document listeners, so defaultPrevented would speak for them too.)
    TestBed.inject(DialogStack).push(document.getElementById('d') as HTMLDialogElement);
    key(document.body, ',', { metaKey: true });
    expect(go).toHaveBeenCalledTimes(2);
  });
});
