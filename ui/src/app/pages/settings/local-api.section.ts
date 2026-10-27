import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, inject, linkedSignal, signal, viewChild, Injector } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { Announcer } from '../../kit/announcer';
import { GwSaveBar } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import { formSavedToast, numOf, parseOrigin, sameList, strOf, stringsOf } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Settings › Local API (design/pages/settings.html `#settings-local-api`): how the core accepts calls on this
 * computer. The listen address and the request checks are facts (always on; the port comes from the core's
 * `local-api` section); Allowed origins is a Save form (`PUT /api/settings/local-api {allowedOrigins}`), validated
 * like the core does (scheme, host and port, no path; at most 20). The core applies new origins on its next start
 * (it asks the desktop app to restart it, `core.restart-required`), so the toast says so.
 *
 * Removing a tag moves focus to the next tag's remove button, else the previous one, else the add field.
 */
@Component({
  selector: 'section[gwLocalApiSettings]',
  imports: [RouterLink, GwIcon, GwSaveBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-local-api', 'aria-labelledby': 'st-local-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-local-t">Local API</h2>
      <p class="gw-section__description">How the core accepts calls on this computer. Protection against other software and web pages is always on.</p>
    </header>
    <hr class="gw-divider">
    <div class="gw-form gw-form--sticky-actions">
      <section class="gw-form__section" aria-labelledby="st-listen-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-listen-t">Listen address</h3>
          <p class="gw-form__description">Fixed on the desktop. The port is set in <a class="gw-link" routerLink="/settings/general">General</a>.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-field">
            <label class="gw-field__label" for="st-listen">Address</label>
            <input class="gw-input gw-input--mono" id="st-listen" type="text" [value]="address()" readonly aria-describedby="st-listen-hint">
            <p class="gw-field__hint" id="st-listen-hint">Loopback only: nothing else on your network can reach the core, and claude.ai comes in only through the tunnel.</p>
          </div>
        </div>
      </section>

      <section class="gw-form__section" aria-labelledby="st-checks-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-checks-t">Request checks</h3>
          <p class="gw-form__description">Web pages can't reach the core (DNS rebinding check); other programs on this computer still need a token. Always on.</p>
        </header>
        <div class="gw-form__rows">
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key">Host header</dt><dd class="gw-kv__value"><span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Checked · only <span class="gw-mono">{{ address() }}:{{ port() }}</span> and <span class="gw-mono">localhost:{{ port() }}</span></span></span></dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Origin header</dt><dd class="gw-kv__value"><span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Checked · browsers only from the allowed origins below</span></span></dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Token</dt><dd class="gw-kv__value"><span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Required for every call, also from this computer</span></span></dd></div>
          </dl>
        </div>
      </section>

      <form class="gw-form__section" id="st-origins" method="dialog" novalidate aria-labelledby="st-origins-t" (submit)="save($event)" (reset)="discard($event)">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-origins-t">Allowed origins</h3>
          <p class="gw-form__description">Browser-based clients that may call the local API. Desktop clients and command-line tools send no Origin and don't need an entry.</p>
        </header>
        <div class="gw-form__rows">
          @if (origins().length) {
            <ul class="gw-tag-list" id="st-origins-list" aria-label="Allowed origins">
              @for (o of origins(); track o; let i = $index) {
                <li class="gw-tag gw-tag--mono">
                  <span class="gw-tag__label" [attr.title]="o">{{ o }}</span>
                  <button class="gw-tag__remove" type="button" [attr.data-origin-index]="i" [attr.aria-label]="'Remove ' + o" (click)="remove(i)"><svg lucideIcon="x" class="gw-icon"></svg></button>
                </li>
              }
            </ul>
          } @else {
            <div class="gw-empty gw-empty--compact" id="st-origins-empty">
              <span class="gw-empty__icon"><svg lucideIcon="globe-lock" class="gw-icon"></svg></span>
              <p class="gw-empty__title">No browser origins allowed</p>
              <p class="gw-empty__text">Only desktop clients and command-line tools can call the core. That is the safest setting.</p>
            </div>
          }
          <div class="gw-field">
            <label class="gw-field__label" for="st-origin-new">Add an origin</label>
            <div class="gw-input-group">
              <input #originInput class="gw-input gw-input--mono" id="st-origin-new" type="text" placeholder="http://localhost:3000" autocomplete="off" spellcheck="false" [value]="newOrigin()" [attr.aria-invalid]="addError() ? 'true' : null" [attr.aria-describedby]="(addError() ? 'st-origin-err ' : '') + 'st-origin-hint'" (input)="onInput($event)" (keydown.enter)="add($event)">
              <button class="gw-input-group__addon" type="button" id="st-origin-add" aria-label="Add origin" (click)="add($event)"><svg lucideIcon="plus" class="gw-icon"></svg></button>
            </div>
            @if (addError(); as err) {
              <p class="gw-field__error" id="st-origin-err"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ err }}</p>
            }
            <p class="gw-field__hint" id="st-origin-hint">MCP Inspector runs at <span class="gw-mono">http://localhost:6274</span>. Any other origin gets 403 Forbidden.</p>
          </div>
          @if (error(); as err) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">Allowed origins weren't saved</p><p class="gw-alert__body">{{ err }}</p></div>
            </div>
          }
        </div>
      </form>

      <div gwSaveBar formId="st-origins" saveLabel="Save allowed origins" what="Allowed origins" [dirty]="dirty()" [busy]="busy()"></div>
    </div>
  `,
})
export class GwLocalApiSettings {
  private readonly data = inject(SettingsData);
  private readonly desktop = inject(DesktopBridge);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly originInput = viewChild<ElementRef<HTMLInputElement>>('originInput');

  private readonly values = computed(() => this.data.section('local-api')?.values);
  protected readonly address = computed(() => strOf(this.values(), 'address', '127.0.0.1'));
  protected readonly port = computed(() => numOf(this.values(), 'port', 7465));
  private readonly saved = computed(() => stringsOf(this.values(), 'allowedOrigins'), { equal: sameList });

  /** The list being edited: follows the saved one until it changes here. */
  protected readonly origins = linkedSignal<readonly string[], readonly string[]>({
    source: this.saved,
    computation: (saved, previous) => (previous && !sameList(previous.value, previous.source) ? previous.value : saved),
  });
  protected readonly dirty = computed(() => !sameList(this.origins(), this.saved()));
  protected readonly newOrigin = signal('');
  protected readonly addError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected onInput(e: Event): void {
    this.newOrigin.set((e.target as HTMLInputElement).value);
    this.addError.set(null);
  }

  /** Add (button or Enter in the field): the field's Enter never submits the whole form. */
  protected add(e: Event): void {
    e.preventDefault();
    const raw = this.originInput()?.nativeElement.value ?? this.newOrigin();
    const parsed = parseOrigin(raw, this.origins());
    if (parsed.error !== null) {
      this.addError.set(parsed.error);
      this.originInput()?.nativeElement.focus();
      return;
    }
    this.origins.set([...this.origins(), parsed.value]);
    this.newOrigin.set('');
    const input = this.originInput()?.nativeElement;
    if (input) input.value = '';
    this.addError.set(null);
    this.announcer.announce(`${parsed.value} added`);
  }

  protected remove(i: number): void {
    const list = this.origins();
    const gone = list[i];
    this.origins.set(list.filter((_, j) => j !== i));
    this.announcer.announce(`${gone} removed`);
    // The button is gone: the next tag's remove button (same index), else the previous one, else the add field.
    afterNextRender(
      () => {
        const next = this.host.querySelector<HTMLElement>(`[data-origin-index="${Math.min(i, this.origins().length - 1)}"]`);
        (next ?? this.originInput()?.nativeElement)?.focus();
      },
      { injector: this.injector },
    );
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy() || !this.dirty()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.data.save('local-api', { allowedOrigins: [...this.origins()] });
      this.toasts.show(
        formSavedToast('Allowed origins', this.desktop.available ? 'The core restarts to apply them; calls in flight are retried.' : 'They apply the next time the core starts.'),
      );
    } catch (err) {
      this.error.set(isApiError(err) && err.status === 409 ? 'The list changed meanwhile, in another window. Check it, then save again.' : isApiError(err) ? err.detail : NO_ANSWER);
      this.announcer.announce('Allowed origins weren’t saved');
    } finally {
      this.busy.set(false);
    }
  }

  protected discard(e: Event): void {
    e.preventDefault();
    this.origins.set(this.saved());
    this.addError.set(null);
    this.error.set(null);
  }
}
