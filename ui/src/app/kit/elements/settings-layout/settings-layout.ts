import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  booleanAttribute,
  effect,
  inject,
  input,
  model,
  numberAttribute,
  output,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Announcer } from '../../announcer';
import { GwIcon } from '../../icons';
import { SETTINGS_GROUPS, SettingsNavGroup } from './settings-sections';

export { SETTINGS_GROUPS } from './settings-sections';
export type { SettingsNavGroup, SettingsNavItem } from './settings-sections';

let nextId = 0;

/**
 * `<div gwSettingsLayout [(current)]="section">` (elements/settings-layout.html): a settings nav on the left that stays
 * in view and marks the section on screen (`aria-current="location"`, scroll spy), and the sections on the right
 * (`<section gwSettingsSection name="appearance">`). Below 1280px the nav becomes "Jump to section", a select with the
 * same groups: picking one scrolls there and leaves focus on the select. Items link to `<base>/<id>` so a section has
 * its own URL (`/settings/notifications`).
 */
@Component({
  selector: 'div[gwSettingsLayout]',
  imports: [GwIcon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout' },
  templateUrl: './settings-layout.html',
})
export class GwSettingsLayout {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly router = inject(Router);

  readonly groups = input<readonly SettingsNavGroup[]>(SETTINGS_GROUPS);
  /** The route the items link under: `/settings` → `/settings/<id>`. */
  readonly base = input('/settings');
  /** The section in view (an item id). */
  readonly current = model<string | null>(null);
  /** The nav's name ("Settings"; "Endpoint settings" on endpoint detail). */
  readonly label = input('Settings');
  /** The sections' id prefix (`settings-` → `#settings-appearance`; endpoint detail: `ep-st-` → `#ep-st-limits`). */
  readonly idPrefix = input('settings-');
  /**
   * Items link to the section's anchor on the same page (`#ep-st-limits`) instead of a route of their own: settings
   * inside a tab of another page (endpoint detail › Settings), where the fragment also selects the tab.
   */
  readonly fragments = input(false, { transform: booleanAttribute });

  protected readonly uid = `st-${++nextId}`;

  constructor() {
    afterNextRender(() => {
      if (typeof IntersectionObserver !== 'function') return;
      // The section whose top crosses the upper 30 % of the viewport is the current one (kit's scroll spy).
      const spy = new IntersectionObserver(
        (entries) => {
          for (const e of entries) {
            if (e.isIntersecting && e.target.id.startsWith(this.idPrefix())) this.current.set(e.target.id.slice(this.idPrefix().length));
          }
        },
        { rootMargin: '0px 0px -70% 0px' },
      );
      this.el.querySelectorAll('.gw-settings-layout__section[id]').forEach((s) => spy.observe(s));
      this.destroy = () => spy.disconnect();
    });
    inject(DestroyRef).onDestroy(() => this.destroy());
  }

  private destroy: () => void = () => undefined;

  /** Jump to section: the URL follows, the section scrolls into view, focus stays on the select. */
  protected jump(id: string): void {
    this.current.set(id);
    // In a tab of another page the URL keeps its own fragment; kit.js only scrolls there.
    if (!this.fragments()) void this.router.navigate([this.base(), id], { replaceUrl: true });
    this.section(id)?.scrollIntoView({ block: 'start' });
  }

  protected open(id: string): void {
    this.current.set(id);
    // The route changes; scroll here too, for a section of the page already on screen.
    queueMicrotask(() => this.section(id)?.scrollIntoView({ block: 'start' }));
  }

  private section(id: string): Element | null {
    return this.el.querySelector(`#${CSS.escape(this.idPrefix() + id)}`);
  }
}

/**
 * `<section gwSettingsSection name="appearance" heading="Appearance" description="…">`: one item of the settings nav,
 * its title and what it is for, a divider, then its forms. Its id is `settings-<name>` (the kit's anchors).
 */
@Component({
  selector: 'section[gwSettingsSection]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-settings-layout__section',
    '[id]': 'idPrefix() + name()',
    '[attr.aria-labelledby]': 'idPrefix() + name() + "-t"',
  },
  template: `
    <header class="gw-section__header">
      @switch (level()) {
        @case (3) { <h3 class="gw-section__title" [id]="idPrefix() + name() + '-t'">{{ heading() }}</h3> }
        @default { <h2 class="gw-section__title" [id]="idPrefix() + name() + '-t'">{{ heading() }}</h2> }
      }
      @if (description()) {
        <p class="gw-section__description">{{ description() }}</p>
      }
    </header>
    <hr class="gw-divider">
    <ng-content />
  `,
})
export class GwSettingsSection {
  readonly name = input.required<string>();
  readonly heading = input.required<string>();
  readonly description = input<string | null>(null);
  readonly level = input(2, { transform: numberAttribute });
  /** The id prefix of its layout (`settings-`, endpoint detail `ep-st-`). */
  readonly idPrefix = input('settings-');
}

/**
 * `<div gwSaveBar [dirty]="form.dirty()" formId="st-oauth" saveLabel="Save OAuth settings">`: the sticky Save bar of a
 * settings form that needs saving (the kit's `gw-form__actions` in a `gw-form--sticky-actions`). Hidden while
 * nothing changed; "Unsaved changes" is announced when it appears. Discard resets the form, Save submits it.
 */
@Component({
  selector: 'div[gwSaveBar]',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-form__actions', '[hidden]': '!dirty()' },
  template: `
    <p class="gw-form__note" role="status"><svg lucideIcon="circle-dot" class="gw-icon"></svg>Unsaved changes</p>
    <button class="gw-btn" type="reset" [attr.form]="formId()" (click)="discard.emit()">Discard</button>
    <button class="gw-btn gw-btn--primary" type="submit" [attr.form]="formId()" [attr.aria-disabled]="busy() ? 'true' : null">{{ saveLabel() }}</button>
  `,
})
export class GwSaveBar {
  private readonly announcer = inject(Announcer);
  readonly dirty = input(false, { transform: booleanAttribute });
  readonly busy = input(false, { transform: booleanAttribute });
  /** The id of the `<form>` the buttons belong to. */
  readonly formId = input.required<string>();
  readonly saveLabel = input('Save');
  /** What changed, for the announcement ("Unsaved changes in OAuth 2.1 for claude.ai"). */
  readonly what = input<string | null>(null);
  readonly discard = output<void>();

  constructor() {
    // Announce when the bar appears (not on every change while it stays).
    let was = false;
    effect(() => {
      const dirty = this.dirty();
      if (dirty && !was) {
        const what = untracked(() => this.what());
        this.announcer.announce(`Unsaved changes${what ? ` in ${what}` : ''}`);
      }
      was = dirty;
    });
  }
}
