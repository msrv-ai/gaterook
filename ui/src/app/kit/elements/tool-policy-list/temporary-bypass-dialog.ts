import { ChangeDetectionStrategy, Component, Injector, computed, inject, output, signal, viewChild } from '@angular/core';
import { isApiError } from '../../../core/api/api-error';
import { EndpointsApi, ToolGroup, ToolRow } from '../../../core/api/endpoints.api';
import { TimeService } from '../../../core/time/time.service';
import { ActivationGuard } from '../../activation-guard';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwIcon } from '../../icons';
import { MODES } from '../shared/dictionaries';

export interface TemporaryBypassTarget {
  readonly slug: string;
  readonly toolKey: string;
  readonly connection: string;
  readonly toolName?: string;
}

export const TEMPORARY_BYPASS_DURATIONS = [
  { minutes: 15, label: '15 minutes' },
  { minutes: 30, label: '30 minutes' },
  { minutes: 60, label: '1 hour' },
  { minutes: 180, label: '3 hours' },
  { minutes: 720, label: '12 hours' },
  { minutes: 1440, label: '24 hours' },
] as const;

let nextId = 0;

/** Shared exact-scope review for endpoint tools and approval details. Eligibility is reloaded before each opening. */
@Component({
  selector: 'gw-temporary-bypass-dialog',
  imports: [GwDialog, GwDialogClose, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './temporary-bypass-dialog.html',
})
export class GwTemporaryBypassDialog {
  private readonly injector = inject(Injector);
  private readonly arm = inject(ActivationGuard).arm();
  protected readonly time = inject(TimeService);
  private readonly dialog = viewChild.required<GwDialog>('dialog');
  readonly changed = output<ToolGroup[]>();
  readonly closed = output<void>();
  protected readonly uid = `temporary-bypass-${++nextId}`;
  protected readonly durations = TEMPORARY_BYPASS_DURATIONS;
  protected readonly target = signal<TemporaryBypassTarget | null>(null);
  protected readonly row = signal<ToolRow | null>(null);
  protected readonly group = signal<ToolGroup | null>(null);
  protected readonly duration = signal(15);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly refusal = signal<string | null>(null);
  protected readonly activeUntil = computed(() => {
    const until = this.row()?.bypassUntil;
    return until && Date.parse(until) > this.time.now() ? until : null;
  });
  protected readonly allowed = computed(() => !!this.row()?.temporaryBypassAllowed && !this.refusal());
  protected readonly savedMode = computed(() => MODES[this.row()?.storedMode ?? 'APPROVAL'].text);
  private sequence = 0;

  open(target: TemporaryBypassTarget, opener?: HTMLElement | null): void {
    if (this.busy()) return;
    this.arm.reset();
    this.target.set(target);
    this.row.set(null);
    this.group.set(null);
    this.duration.set(15);
    this.error.set(null);
    this.notice.set(null);
    this.refusal.set(null);
    this.dialog().openAfterRender(opener);
    void this.reload();
  }

  protected async reload(): Promise<void> {
    const target = this.target();
    if (!target || this.busy()) return;
    const sequence = ++this.sequence;
    this.loading.set(true);
    this.error.set(null);
    this.row.set(null);
    try {
      const api = this.injector.get(EndpointsApi);
      const [endpoint, groups] = await Promise.all([api.get(target.slug), api.tools(target.slug)]);
      if (sequence !== this.sequence) return;
      this.accept(groups, target);
      if (endpoint.endpoint.env === 'prod' || endpoint.endpoint.access === 'read-only') {
        this.refusal.set('Temporary bypass is available only on DEV and STAGE endpoints with standard access.');
      }
    } catch (error) {
      if (sequence === this.sequence) this.error.set(this.errorText(error));
    } finally {
      if (sequence === this.sequence) this.loading.set(false);
    }
  }

  protected pickDuration(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    if (this.durations.some((duration) => duration.minutes === value)) this.duration.set(value);
  }

  protected async save(revoke = false): Promise<void> {
    const target = this.target();
    const row = this.row();
    if (!target || !row || this.loading() || this.busy() || !this.arm.ready() || (revoke ? !this.activeUntil() : !this.allowed())) return;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const api = this.injector.get(EndpointsApi);
      const request = { toolKey: target.toolKey, connection: target.connection, version: row.version };
      const groups = revoke
        ? await api.revokeTemporaryBypass(target.slug, request)
        : await api.temporaryBypass(target.slug, { ...request, durationMinutes: this.duration() });
      this.accept(groups, target);
      this.notice.set(revoke ? 'Temporary bypass ended.' : 'Temporary bypass enabled.');
      this.changed.emit(groups);
    } catch (error) {
      this.error.set(this.errorText(error));
      // A conflict must be reviewed again, with a fresh version and eligibility, before another write.
      if (isApiError(error) && error.status === 409) this.row.set(null);
    } finally {
      this.busy.set(false);
    }
  }

  protected onClosed(): void {
    ++this.sequence;
    this.closed.emit();
  }

  protected preventBusyClose(event: Event): void {
    if (this.busy()) event.preventDefault();
  }

  private accept(groups: ToolGroup[], target: TemporaryBypassTarget): void {
    this.arm.reset();
    const group = groups.find((group) => group.connection === target.connection);
    const row = group?.tools.find((row) => row.toolKey === target.toolKey);
    this.group.set(group ?? null);
    this.row.set(row ?? null);
    this.refusal.set(!row ? 'This tool is no longer available on this endpoint.'
      : group?.env !== 'dev' && group?.env !== 'stage' ? 'Temporary bypass is available only for DEV and STAGE connections.'
      : !row.temporaryBypassAllowed ? row.temporaryBypassReason ?? 'Temporary bypass is unavailable for this tool.' : null);
  }

  private errorText(error: unknown): string {
    return isApiError(error) ? error.detail : 'The core did not answer. Try again.';
  }
}
