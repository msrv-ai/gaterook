import { ChangeDetectionStrategy, Component, Injector, computed, inject, linkedSignal, signal } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwToggle } from '../../kit/disclosure';
import { GwSaveBar } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SettingsData, runnableExport } from './settings.data';
import { ExportCard, RETENTION_YEARS, exportCard, formSavedToast, listText, numOf, retentionHint, yearsText } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Settings › Audit & retention (design/pages/settings.html `#settings-audit`).
 *
 * - Retention is a Save form (`PUT /api/settings/audit {retentionYears}`: 1, 2, 3 or 5 years); the hint says how many
 *   records the period holds at today's rate (the overview's calls in the last 24 hours).
 * - Export applies at once: one card per destination with its schedule and last run (`GET /api/audit-export`, step
 *   K5); a failing one shows the core's error under Show error details and Retry. Export now runs every enabled one.
 *   Adding and editing a destination has no screen in the kit, so Edit and Add destination are unavailable with that
 *   reason (ARCHITECTURE.md §8.6, "Settings (U14)").
 *
 * Until K5 is merged `/api/audit-export` is 404: the section says exports aren't in this build.
 */
@Component({
  selector: 'section[gwAuditSettings]',
  imports: [RouterLink, FormField, GwIcon, GwToggle, GwCodeBlock, GwSaveBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-audit', 'aria-labelledby': 'st-audit-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-audit-t">Audit &amp; retention</h2>
      <p class="gw-section__description">Every call is written to the audit log before it runs. Choose how long to keep it and where to copy it.</p>
      <div class="gw-section__actions">
        <a class="gw-btn gw-btn--ghost gw-btn--sm" routerLink="/activity"><svg lucideIcon="activity" class="gw-icon gw-icon--sm"></svg> Open Activity</a>
      </div>
    </header>
    <hr class="gw-divider">
    <div class="gw-alert gw-alert--info gw-alert--compact">
      <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
      <div class="gw-alert__content"><p class="gw-alert__title">Append-only</p><p class="gw-alert__body">Records can't be edited, and are removed only after the retention period and every export. A call that can't be written to the log doesn't run.</p></div>
    </div>
    <div class="gw-form gw-form--sticky-actions">
      <form class="gw-form__section" id="st-retention" method="dialog" novalidate aria-labelledby="st-retention-t" (submit)="save($event)" (reset)="discard($event)">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-retention-t">Retention</h3>
          <p class="gw-form__description">Older records are removed only after every export destination has received them.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-field">
            <label class="gw-field__label" for="st-keep">Keep records for</label>
            <select class="gw-select" id="st-keep" aria-describedby="st-keep-hint" [formField]="retentionForm.years">
              @for (y of years; track y) {
                <option [value]="'' + y">{{ yearsText(y) }}</option>
              }
            </select>
            <p class="gw-field__hint" id="st-keep-hint">{{ hint() }}</p>
          </div>
          @if (error(); as err) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">Retention wasn't saved</p><p class="gw-alert__body">{{ err }}</p></div>
            </div>
          }
        </div>
      </form>

      <section class="gw-form__section" aria-labelledby="st-export-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-export-t">Export</h3>
          <p class="gw-form__description">Copies of the log for your SIEM and data warehouse. Masked fields stay masked.</p>
        </header>
        <div class="gw-form__rows">
          @if (unavailable()) {
            <div class="gw-alert gw-alert--neutral gw-alert--compact" id="st-export-why">
              <svg lucideIcon="circle-help" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">Not available in this build</p><p class="gw-alert__body">This core doesn't copy the log anywhere yet: records stay on this computer, where Activity shows them.</p></div>
            </div>
          } @else if (failed()) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert" id="st-export-why">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">Export destinations didn't load</p><p class="gw-alert__body">{{ loadError() }}</p></div>
              <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="data.refresh()">Try again</button></div>
            </div>
          } @else if (cards().length) {
            <div class="gw-stack">
              @for (c of cards(); track c.id) {
                <section class="gw-card" [attr.aria-labelledby]="'st-exp-' + c.id + '-t'">
                  <header class="gw-card__header">
                    <div class="gw-card__heading">
                      <h4 class="gw-card__title" [id]="'st-exp-' + c.id + '-t'">{{ c.title }}</h4>
                      <p class="gw-card__description gw-mono">{{ c.target }}</p>
                    </div>
                    <div class="gw-card__actions"><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" aria-disabled="true" [attr.aria-label]="'Edit export to ' + c.title" aria-describedby="st-export-edit-why">Edit</button></div>
                  </header>
                  <div class="gw-card__body">
                    <div class="gw-stack gw-stack--sm">
                      <span class="gw-status gw-status--{{ c.tone }}"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ c.label }}@if (c.at; as at) { <time [attr.datetime]="time.iso(at)" [attr.title]="time.absolute(at)">{{ time.relative(at) }}</time>} @else if (!c.failing && c.tone !== 'neutral') { none yet}</span></span>
                      @if (c.failing) {
                        <div class="gw-alert gw-alert--danger">
                          <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                          <div class="gw-alert__content">
                            <p class="gw-alert__title">Export to {{ c.title }} is failing</p>
                            <p class="gw-alert__body">Records wait in the local log until an export succeeds. Fix what the error names, then retry.</p>
                            <div class="gw-alert__actions">
                              <button class="gw-btn gw-btn--sm" type="button" [attr.aria-busy]="busy() === c.id ? 'true' : null" [attr.aria-disabled]="busy() || c.blocked ? 'true' : null" [attr.aria-describedby]="c.blocked ? 'st-exp-' + c.id + '-why' : null" (click)="run(c)">@if (busy() === c.id) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="rotate-cw" class="gw-icon gw-icon--sm"></svg>} Retry export to {{ c.title }}</button>
                              @if (c.error) {
                                <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" gwToggle [attr.aria-controls]="'st-exp-' + c.id + '-details'">Show error details</button>
                              }
                            </div>
                            @if (c.blocked) {
                              <p class="gw-text-caption gw-fg-muted" [id]="'st-exp-' + c.id + '-why'">{{ c.blocked }}</p>
                            }
                            @if (c.error) {
                              <gw-code-block [attr.id]="'st-exp-' + c.id + '-details'" [id]="'st-exp-' + c.id + '-error'" hidden wrap lang="Error" [filename]="c.title + (c.at ? ' · ' + time.absolute(c.at) : '')" [text]="c.error" copyLabel="error details" [regionLabel]="c.title + ' error details'" />
                            }
                          </div>
                        </div>
                      }
                    </div>
                  </div>
                </section>
              }
            </div>
          } @else if (data.exports().value) {
            <div class="gw-empty gw-empty--compact">
              <span class="gw-empty__icon"><svg lucideIcon="cloud-upload" class="gw-icon"></svg></span>
              <p class="gw-empty__title">No export destinations</p>
              <p class="gw-empty__text">Records stay in the local log on this computer.</p>
            </div>
          }
          @let off = !runnableCount() || !!busy();
          <div class="gw-settings-layout__inline">
            <button class="gw-btn" type="button" [attr.aria-disabled]="off ? 'true' : null" [attr.aria-busy]="busy() === 'all' ? 'true' : null" [attr.aria-describedby]="unavailable() || failed() ? 'st-export-why' : !runnableCount() ? 'st-export-none' : null" (click)="runAll()">@if (busy() === 'all') {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="cloud-upload" class="gw-icon"></svg>} Export now</button>
            <button class="gw-btn gw-btn--ghost" type="button" aria-disabled="true" aria-describedby="st-export-edit-why"><svg lucideIcon="plus" class="gw-icon"></svg> Add destination</button>
            @if (!unavailable() && !failed() && !runnableCount()) {
              <p class="gw-sr-only" id="st-export-none">{{ enabledCount() ? 'No destination can run now: each one says why.' : 'No destination is on.' }}</p>
            }
            <p class="gw-text-caption gw-fg-muted" id="st-export-edit-why">Adding and editing destinations isn't in this version of the app yet.</p>
          </div>
        </div>
      </section>

      <div gwSaveBar formId="st-retention" saveLabel="Save retention" what="Retention" [dirty]="dirty()" [busy]="saving()"></div>
    </div>
  `,
})
export class GwAuditSettings {
  protected readonly data = inject(SettingsData);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  protected readonly years = RETENTION_YEARS;
  protected readonly yearsText = yearsText;
  private readonly saved = computed(() => String(numOf(this.data.section('audit')?.values, 'retentionYears', 1)));
  protected readonly model = linkedSignal<string, { years: string }>({
    source: this.saved,
    computation: (saved, previous) => (previous && previous.value.years !== previous.source ? previous.value : { years: saved }),
  });
  protected readonly retentionForm = form(this.model, () => undefined, { injector: this.injector });
  protected readonly dirty = computed(() => this.model().years !== this.saved());
  protected readonly hint = computed(() => retentionHint(this.data.calls24h(), Number(this.model().years)));
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly unavailable = computed(() => this.data.exports().unavailable);
  protected readonly failed = computed(() => !!this.data.exports().error && !this.unavailable());
  protected readonly loadError = computed(() => {
    const e = this.data.exports().error;
    return isApiError(e) ? e.detail : NO_ANSWER;
  });
  protected readonly cards = computed(() => (this.data.exports().value ?? []).map(exportCard));
  protected readonly enabledCount = computed(() => (this.data.exports().value ?? []).filter((d) => d.enabled).length);
  /** The destinations Export now runs (on, not changed outside MCP Gateway, not a demo one). */
  protected readonly runnableCount = computed(() => (this.data.exports().value ?? []).filter(runnableExport).length);
  protected readonly busy = signal<string | null>(null);

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving() || !this.dirty()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.data.save('audit', { retentionYears: Number(this.model().years) });
      this.toasts.show(formSavedToast('Retention'));
    } catch (err) {
      this.error.set(isApiError(err) && err.status === 409 ? 'Retention changed meanwhile, in another window. Check it, then save again.' : isApiError(err) ? err.detail : NO_ANSWER);
      this.announcer.announce('Retention wasn’t saved');
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(e: Event): void {
    e.preventDefault();
    this.model.set({ years: this.saved() });
    this.error.set(null);
  }

  /** Retry one failing destination. */
  protected async run(c: ExportCard): Promise<void> {
    if (this.busy() || c.blocked) return;
    this.busy.set(c.id);
    const since = (this.data.exports().value ?? []).find((d) => d.id === c.id)?.failingSince ?? null;
    try {
      const [failed] = await this.data.runExport(c.id);
      if (failed) {
        this.toasts.show({ tone: 'danger', title: `Export to ${c.title} didn't start`, body: isApiError(failed.error) ? failed.error.detail : NO_ANSWER });
      } else {
        this.toasts.show({ tone: 'info', title: `Retrying export to ${c.title}`, body: `Records${since ? ` since ${this.time.clock(since)}` : ''} are on their way to ${c.target}.` });
      }
    } finally {
      this.busy.set(null);
    }
  }

  /**
   * Export now: every destination that can run, from its last run. Each starts on its own: the toast names the ones
   * that started and, with the core's words, the ones that didn't.
   */
  protected async runAll(): Promise<void> {
    if (this.busy() || !this.runnableCount()) return;
    this.busy.set('all');
    const runnable = (this.data.exports().value ?? []).filter(runnableExport);
    const title = (id: string) => this.cards().find((c) => c.id === id)?.title ?? id;
    try {
      const failed = await this.data.runExport(null);
      const started = runnable.filter((d) => !failed.some((f) => f.id === d.id)).map((d) => title(d.id));
      if (!failed.length) {
        this.toasts.show({ tone: 'info', title: 'Export started', body: `Sending records since the last run to ${listText(started)}.` });
      } else {
        const why = failed.map((f) => `${title(f.id)}: ${isApiError(f.error) ? f.error.detail : NO_ANSWER}`).join(' ');
        this.toasts.show({
          tone: 'danger',
          title: started.length ? `The export started for ${listText(started)} only` : "The export didn't start",
          body: why,
        });
      }
    } finally {
      this.busy.set(null);
    }
  }
}
