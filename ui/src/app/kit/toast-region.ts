import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  ElementRef,
  effect,
  inject,
  input,
  OnDestroy,
} from '@angular/core';
import { LucideDynamicIcon } from '@lucide/angular';
import { DialogStack } from './dialog';
import { Toast, ToastService, ToastTone } from './toast.service';

/**
 * One toast: auto-dismisses after its timeout, pausing while hovered or focused (WCAG 2.2.1), as kit.js does.
 */
@Directive({
  selector: '[gwToastTimer]',
  host: {
    '(mouseenter)': 'pause()',
    '(mouseleave)': 'start()',
    '(focusin)': 'pause()',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class GwToastTimer implements OnDestroy {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly toasts = inject(ToastService);
  readonly gwToastTimer = input.required<Toast>();
  private remaining = -1;
  private started = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const t = this.gwToastTimer();
      if (this.remaining < 0 && t.timeout > 0) {
        this.remaining = t.timeout;
        this.start();
      }
    });
  }

  start(): void {
    if (this.remaining <= 0) return;
    this.started = Date.now();
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.toasts.dismiss(this.gwToastTimer().id), this.remaining);
  }

  pause(): void {
    if (!this.timer) return;
    clearTimeout(this.timer);
    this.timer = null;
    this.remaining -= Date.now() - this.started;
  }

  protected onFocusOut(e: FocusEvent): void {
    if (!this.el.contains(e.relatedTarget as Node | null)) this.start();
  }

  ngOnDestroy(): void {
    if (this.timer) clearTimeout(this.timer);
  }
}

/**
 * `<gw-toast-region>`: the kit's `gw-toast-region` with the live toasts. While a modal dialog is open the region moves
 * into it (the rest of the document is inert then), and back when it closes — kit.js's `toastRegion()`.
 */
@Component({
  selector: 'gw-toast-region',
  imports: [LucideDynamicIcon, GwToastTimer],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-toast-region',
    'aria-label': 'Notifications',
    'aria-live': 'polite',
    'aria-relevant': 'additions',
    role: 'region',
  },
  template: `
    @for (t of toasts.toasts(); track t.id) {
      <div [class]="'gw-toast gw-toast--' + t.tone" [attr.role]="t.tone === 'danger' ? 'alert' : null" [gwToastTimer]="t">
        <svg [lucideIcon]="t.icon" class="gw-icon gw-toast__icon"></svg>
        <div class="gw-toast__content">
          @if (t.title) {
            <p class="gw-toast__title">{{ t.title }}</p>
          }
          @if (t.body) {
            <p class="gw-toast__body">{{ t.body }}</p>
          }
          @if (t.actions.length) {
            <div class="gw-toast__actions">
              @for (a of t.actions; track a.label) {
                <button class="gw-btn gw-btn--sm" type="button" (click)="act(t, a.onClick)">{{ a.label }}</button>
              }
            </div>
          }
        </div>
        <button
          class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-toast__close"
          type="button"
          aria-label="Dismiss notification"
          (click)="toasts.dismiss(t.id)"
        >
          <svg lucideIcon="x" class="gw-icon gw-icon--sm"></svg>
        </button>
      </div>
    }
  `,
})
export class GwToastRegion {
  protected readonly toasts = inject(ToastService);
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly dialogs = inject(DialogStack);
  private home: { parent: Node; next: Node | null } | null = null;

  constructor() {
    effect(() => {
      const top = this.dialogs.top();
      if (top) {
        if (!this.home) this.home = { parent: this.el.parentNode as Node, next: this.el.nextSibling };
        if (this.el.parentNode !== top) top.appendChild(this.el);
      } else if (this.home) {
        this.home.parent.insertBefore(this.el, this.home.next);
        this.home = null;
      }
    });
  }

  protected act(t: Toast, onClick?: () => void): void {
    onClick?.();
    this.toasts.dismiss(t.id);
  }
}

/** `<button gwToast="message" gwToastTitle="…" gwToastTone="success">` (kit.js `data-gw-toast`). */
@Directive({ selector: '[gwToast]', host: { '(click)': 'onClick()', 'data-gw-toast': '' } })
export class GwToast {
  private readonly toasts = inject(ToastService);
  readonly gwToast = input<string>('');
  readonly gwToastTitle = input<string | null>(null);
  readonly gwToastTone = input<ToastTone>('neutral');
  readonly gwToastIcon = input<string | undefined>(undefined);
  /** Set by `gwCopy` on the same button: "… copied" may only appear once the copy worked. */
  private deferred = false;

  deferToCopy(): void {
    this.deferred = true;
  }

  protected onClick(): void {
    if (!this.deferred) this.show();
  }

  show(): void {
    this.toasts.show({
      title: this.gwToastTitle(),
      body: this.gwToast() || null,
      tone: this.gwToastTone(),
      icon: this.gwToastIcon(),
    });
  }
}
