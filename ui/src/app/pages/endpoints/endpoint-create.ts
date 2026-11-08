import { ChangeDetectionStrategy, Component, DestroyRef, Injector, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { FormField, SchemaPath, form, maxLength, required, submit, validate } from '@angular/forms/signals';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointStart, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Env } from '../../core/api/types';
import { CoreStore } from '../../core/stores/core.store';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { DESCRIPTION_MAX, SLUG_MAX, StartFrom, createField, createdToast, slugError, startFromMeta } from './endpoints-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

interface CreateModel {
  slug: string;
  description: string;
  startFrom: StartFrom;
}

/**
 * The slug field of Create and Duplicate, the kit's `required`, `maxlength="40"` and `pattern` as Signal Forms rules
 * (it sets `required` and `maxlength` on the input itself). The form is `novalidate`: the messages are the page's, at
 * the field, after a submit attempt, worded by {@link slugError}.
 */
export function endpointSlugRules(path: SchemaPath<string>): void {
  required(path, { message: slugError('') ?? undefined });
  maxLength(path, SLUG_MAX, { message: `Use at most ${SLUG_MAX} characters.` });
  validate(path, ({ value }) => {
    const message = value().trim() ? slugError(value()) : null;
    return message ? { kind: 'pattern', message } : null;
  });
}

/** The Start from cards of Create endpoint, ordered by environment. */
const START_FROM: readonly { readonly id: StartFrom; readonly env: Env; readonly icon: string; readonly title: string; readonly description: string }[] = [
  { id: 'empty', env: 'dev', icon: 'square-dashed', title: 'Empty', description: 'No connections and every tool off. Add only what the agent needs.' },
  {
    id: 'read-only-dev',
    env: 'dev',
    icon: 'eye',
    title: 'Read-only dev',
    description: 'Choose dev connections below. Their read-only tools start on; tools that change data stay off.',
  },
  {
    id: 'read-only-stage',
    env: 'stage',
    icon: 'eye',
    title: 'Read-only stage',
    description: 'Choose stage connections below. Their read-only tools start on; tools that change data stay off.',
  },
  {
    id: 'incident-response',
    env: 'prod',
    icon: 'siren',
    title: 'Incident response',
    description: 'Choose prod connections below. Read tools start on; tools that only change data stay off.',
  },
];

/** The same endpoint form can open on the Overview or Endpoints page without navigating. */
@Component({
  selector: 'gw-endpoint-create',
  imports: [FormField, GwDialog, GwDialogClose, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './endpoint-create.html',
})
export class GwEndpointCreate {
  private readonly api = inject(EndpointsApi);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly createDialog = viewChild.required<GwDialog>('createDialog');
  readonly connections = input<readonly ConnectionRow[] | null>(null);
  readonly created = output<EndpointSummary>();
  readonly closed = output<void>();
  protected readonly starts = signal<readonly EndpointStart[] | null>(null);
  protected readonly startsLoading = signal(false);
  private startsRequest = 0;
  protected readonly startFrom = computed(() => START_FROM.map((s) => ({
    ...s, env: this.starts()?.find((preview) => preview.startFrom === s.id)?.env ?? s.env,
  })));

  protected readonly createModel = signal<CreateModel>({ slug: '', description: '', startFrom: 'empty' });
  protected readonly createForm = form(this.createModel, (p) => {
    endpointSlugRules(p.slug);
    maxLength(p.description, DESCRIPTION_MAX, { message: `Use at most ${DESCRIPTION_MAX} characters.` });
  });
  /** Errors show once the person tried to create, not while typing the first letters. */
  protected readonly createTried = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly startFromError = signal<string | null>(null);
  protected readonly startFromMeta = computed(() => startFromMeta(this.connections(), this.starts()));
  protected readonly chosenConnections = signal<readonly string[]>([]);
  protected readonly connectionChoices = computed(() => this.starts()?.find((s) => s.startFrom === this.createModel().startFrom)?.connections ?? null);

  open(opener: HTMLElement | null): void {
    this.resetCreate();
    void this.loadStarts();
    this.createDialog().openAfterRender(opener);
  }

  private async loadStarts(): Promise<void> {
    const request = ++this.startsRequest;
    this.starts.set(null);
    this.startsLoading.set(true);
    try {
      const starts = await this.api.starts();
      if (!this.destroyRef.destroyed && request === this.startsRequest) this.starts.set(starts);
    } catch {
      // An empty endpoint remains available when preset connections cannot be loaded.
    } finally {
      if (!this.destroyRef.destroyed && request === this.startsRequest) this.startsLoading.set(false);
    }
  }

  protected url(slug: string): string {
    return `http://${this.core.address()}/mcp/${slug.trim() || 'your-slug'}`;
  }

  protected setStartFrom(id: StartFrom): void {
    this.createModel.update((m) => ({ ...m, startFrom: id }));
    this.chosenConnections.set([]);
    this.startFromError.set(null);
  }

  protected toggleConnection(name: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.chosenConnections.update((names) => checked ? [...names.filter((n) => n !== name), name] : names.filter((n) => n !== name));
  }

  protected async submitCreate(e: Event): Promise<void> {
    e.preventDefault();
    this.createTried.set(true);
    this.createError.set(null);
    this.startFromError.set(null);
    await submit(this.createForm, {
      action: async () => {
        const m = this.createModel();
        const slug = m.slug.trim();
        const connections = this.chosenConnections();
        let endpoint: EndpointSummary;
        try {
          endpoint = await this.api.create({ slug, description: m.description.trim() || null, startFrom: m.startFrom, connections });
        } catch (err) {
          if (this.destroyRef.destroyed) return undefined;
          return this.createProblem(err);
        }
        if (this.destroyRef.destroyed) return undefined;
        this.toasts.show(createdToast(slug, connections.length));
        this.createDialog().close('create');
        this.created.emit(endpoint);
        return undefined;
      },
      onInvalid: () => this.focusFirstInvalid(this.createDialog().element),
    });
  }

  /** The core's refusal, on the field it names (409: the slug is taken), else in the dialog's alert. */
  private createProblem(err: unknown) {
    const field = isApiError(err) ? (err.kind === 'conflict' ? 'slug' : createField(err.field)) : null;
    const message = this.errorText(err, NO_ANSWER);
    const dialog = this.createDialog().element;
    this.focusAfterRender(() => this.firstInvalid(dialog) ?? dialog.querySelector<HTMLElement>('[role="alert"]'));
    if (field === 'slug') return [{ kind: 'server', message, fieldTree: this.createForm.slug }];
    if (field === 'description') return [{ kind: 'server', message, fieldTree: this.createForm.description }];
    if (field === 'startFrom') this.startFromError.set(message);
    else this.createError.set(message);
    return undefined;
  }

  protected onCreateClosed(): void {
    ++this.startsRequest;
    this.resetCreate();
    this.closed.emit();
  }

  private resetCreate(): void {
    this.createModel.set({ slug: '', description: '', startFrom: 'empty' });
    this.chosenConnections.set([]);
    this.createForm().reset();
    this.createTried.set(false);
    this.createError.set(null);
    this.startFromError.set(null);
  }

  protected fieldError(tried: boolean, errors: readonly { readonly message?: string }[]): string | null {
    return tried ? (errors[0]?.message ?? null) : null;
  }

  private errorText(error: unknown, fallback: string): string {
    return isApiError(error) ? error.detail : fallback;
  }

  private firstInvalid(scope: HTMLElement): HTMLElement | null {
    return scope.querySelector<HTMLElement>('[aria-invalid="true"]');
  }

  /** Focus on the first field in error, once its error shows. */
  private focusFirstInvalid(scope: HTMLElement): void {
    this.focusAfterRender(() => this.firstInvalid(scope));
  }

  private focusAfterRender(target: () => HTMLElement | null | undefined): void {
    afterNextRender(() => target()?.focus(), { injector: this.injector });
  }
}
