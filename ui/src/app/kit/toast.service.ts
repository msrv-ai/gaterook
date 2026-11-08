import { Injectable, inject, signal } from '@angular/core';
import { DialogStack } from './dialog';

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
  /** ms; defaults to the saved duration (10 s initially), or 0 (stays) when it has actions. */
  readonly timeout?: number;
  readonly actions?: readonly ToastAction[];
}

export interface Toast {
  readonly id: number;
  /** The dialog opening that produced this feedback; null means the main page. */
  readonly dialogScope: number | null;
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

/** At most three toasts stand at once; a fourth pushes the oldest out. */
export const TOAST_CAP = 3;

export const DEFAULT_TOAST_DURATION_SECONDS = 10;
export const TOAST_DURATION_SECONDS = [5, 10, 15, 30, 60, 120] as const;

export function normalizeToastDurationSeconds(value: unknown): number {
  return TOAST_DURATION_SECONDS.some((seconds) => seconds === value) ? value as number : DEFAULT_TOAST_DURATION_SECONDS;
}

/**
 * Toasts: a signal list the `gw-toast-region` renders. A toast is never the only place a decision
 * or an error lives; reversible actions show one with Undo instead of asking first.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly dialogs = inject(DialogStack);
  private next = 1;
  private durationSeconds = DEFAULT_TOAST_DURATION_SECONDS;
  private readonly list = signal<readonly Toast[]>([]);

  readonly toasts = this.list.asReadonly();

  /** Applies to new toasts; a visible toast keeps its remaining reading time. */
  setDuration(seconds: unknown): void {
    this.durationSeconds = normalizeToastDurationSeconds(seconds);
  }

  show(opts: ToastOptions): Toast {
    const tone = opts.tone ?? 'neutral';
    const actions = opts.actions ?? [];
    const toast: Toast = {
      id: this.next++,
      dialogScope: this.dialogs.scope(),
      title: opts.title ?? null,
      body: opts.body ?? null,
      tone,
      icon: opts.icon ?? TONE_ICONS[tone] ?? 'bell',
      timeout: opts.timeout ?? (actions.length ? 0 : this.durationSeconds * 1000),
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
