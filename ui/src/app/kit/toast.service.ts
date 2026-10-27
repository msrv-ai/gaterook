import { Injectable, signal } from '@angular/core';

export type ToastTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'brand';

export interface ToastAction {
  readonly label: string;
  readonly onClick?: () => void;
}

export interface ToastOptions {
  readonly title?: string | null;
  readonly body?: string | null;
  readonly tone?: ToastTone;
  /** A Lucide icon; by default the tone's (success circle-check, warning triangle-alert, danger circle-x…). */
  readonly icon?: string;
  /** ms; default 6000, or 0 (stays) when it has actions: an action you must reach never disappears by itself. */
  readonly timeout?: number;
  readonly actions?: readonly ToastAction[];
}

export interface Toast {
  readonly id: number;
  readonly title: string | null;
  readonly body: string | null;
  readonly tone: ToastTone;
  readonly icon: string;
  readonly timeout: number;
  readonly actions: readonly ToastAction[];
}

const TONE_ICONS: Record<ToastTone, string> = {
  success: 'circle-check',
  warning: 'triangle-alert',
  danger: 'circle-x',
  info: 'info',
  neutral: 'bell',
  brand: 'bell',
};

/** At most three toasts stand at once; a fourth pushes the oldest out (kit.js caps the stack). */
export const TOAST_CAP = 3;

/**
 * Toasts (kit.js `gwToast`): a signal list the `gw-toast-region` renders. A toast is never the only place a decision
 * or an error lives (COMPONENTS.md "Toasts"); reversible actions show one with Undo instead of asking first.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private next = 1;
  private readonly list = signal<readonly Toast[]>([]);

  readonly toasts = this.list.asReadonly();

  show(opts: ToastOptions): Toast {
    const tone = opts.tone ?? 'neutral';
    const actions = opts.actions ?? [];
    const toast: Toast = {
      id: this.next++,
      title: opts.title ?? null,
      body: opts.body ?? null,
      tone,
      icon: opts.icon ?? TONE_ICONS[tone] ?? 'bell',
      timeout: opts.timeout ?? (actions.length ? 0 : 6000),
      actions,
    };
    this.list.update((l) => [...l.slice(Math.max(0, l.length - (TOAST_CAP - 1))), toast]);
    return toast;
  }

  dismiss(id: number): void {
    this.list.update((l) => l.filter((t) => t.id !== id));
  }

  clear(): void {
    this.list.set([]);
  }
}
