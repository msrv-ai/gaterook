import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, linkedSignal, output, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../../core/api/api-error';
import { ConnectionRow } from '../../../core/api/connections.api';
import { Template, TemplateSaved } from '../../../core/api/templates.api';
import { Env } from '../../../core/api/types';
import { GwCodeBlock } from '../../../code/code-block';
import { Announcer } from '../../../kit/announcer';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { ConnectorsData } from '../connectors.data';
import { failureText } from '../dialogs/failure';
import {
  PARAM_TYPES,
  ParamDraft,
  ParamType,
  TOOL_NAME,
  TemplateDraft,
  formatSchema,
  inputSchemaOf,
  scanSql,
  scanStatus,
  syncParams,
  toDefinition,
} from '../template-sql';

/** What the editor shows: a new SQL query template, or a saved one (with its definition when the core sends it). */
export interface EditorTarget {
  readonly template: Template | null;
  readonly draft: TemplateDraft;
  readonly connection: string | null;
  /** The saved template's definition can't be read from this core: no form, only why. */
  readonly unreadable: boolean;
  /** Where its tools are on, for Try in Playground (`?endpoint=&tool=`). */
  readonly playground: { readonly endpoint: string; readonly tool: string } | null;
}

interface FieldErrors {
  readonly name?: string;
  readonly connection?: string;
  readonly sql?: string;
}

/**
 * `<section gwTemplateEditor>` (kit `#template-editor`): a SQL query template — tool name, Runs on, the description
 * for the agent, the SQL with `:name` placeholders — its Parameters (typed, required, allowed values, default,
 * description) and "What the agent sees", generated from the fields as the core will publish it. Save sends the
 * definition (`POST`/`PUT /api/templates`); the core compiles it and, if it refuses, its errors stay above the
 * actions and nothing is saved. "Unsaved changes" while the form differs from the saved template; Discard restores it.
 */
@Component({
  selector: 'section[gwTemplateEditor]',
  imports: [RouterLink, GwIcon, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card', id: 'template-editor', '[attr.aria-labelledby]': '"tpl-title"' },
  templateUrl: './template-editor.html',
})
export class GwTemplateEditor {
  private readonly data = inject(ConnectorsData);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  readonly target = input.required<EditorTarget>();
  /** The connections a SQL query can run on (PostgreSQL, MySQL, SQL Server). */
  readonly connections = input.required<readonly ConnectionRow[]>();
  /** A template was saved: the panel shows it as the edited one. */
  readonly saved = output<Template>();

  protected readonly paramTypes = PARAM_TYPES;

  protected readonly name = signal('');
  protected readonly connection = signal<string | null>(null);
  protected readonly description = signal('');
  protected readonly sql = signal('');
  protected readonly scan = computed(() => scanSql(this.sql()));
  /** One row per placeholder in order of use; what was typed for a name stays while the name stays in the SQL. */
  protected readonly params = linkedSignal<readonly string[], readonly ParamDraft[]>({
    source: () => this.scan().placeholders,
    computation: (names, previous) => syncParams(names, previous?.value ?? this.seed),
    equal: (a, b) => a.length === b.length && a.every((p, i) => p === b[i]),
  });
  private seed: readonly ParamDraft[] = [];

  protected readonly errors = signal<FieldErrors>({});
  protected readonly compileErrors = signal<readonly string[]>([]);
  protected readonly warnings = signal<readonly string[]>([]);
  protected readonly failure = signal<string | null>(null);
  protected readonly busy = signal(false);

  /** The saved state the form is compared with (Unsaved changes, Discard). */
  private readonly snapshot = signal<string>('');

  protected readonly draft = computed<TemplateDraft>(() => ({ name: this.name(), description: this.description(), sql: this.sql(), params: this.params() }));
  protected readonly dirty = computed(() => this.serialize() !== this.snapshot());
  protected readonly status = computed(() => scanStatus(this.scan(), this.sql()));
  protected readonly schemaText = computed(() => formatSchema(inputSchemaOf(this.params())));
  protected readonly readOnly = computed(() => !this.scan().changes && !this.scan().error);
  protected readonly insert = computed(() => this.scan().statement === 'INSERT');
  protected readonly toolName = computed(() => this.name().trim() || 'new_template');
  protected readonly runsOn = computed(() => this.connections().find((c) => c.name === this.connection()) ?? null);
  protected readonly editing = computed(() => this.target().template);

  constructor() {
    // A new target (another template, or New template): the fields start from it, saved state included.
    effect(() => {
      const t = this.target();
      untracked(() => this.load(t));
    });
  }

  private load(t: EditorTarget): void {
    this.seed = t.draft.params;
    this.name.set(t.draft.name);
    this.description.set(t.draft.description);
    this.connection.set(t.connection ?? this.connections()[0]?.name ?? null);
    this.sql.set(t.draft.sql);
    this.params.set(syncParams(scanSql(t.draft.sql).placeholders, t.draft.params));
    this.errors.set({});
    this.compileErrors.set([]);
    this.warnings.set([]);
    this.failure.set(null);
    this.snapshot.set(this.serialize());
  }

  private serialize(): string {
    return JSON.stringify({ c: this.connection(), d: toDefinition(this.draft()) });
  }

  protected envOf(c: ConnectionRow | null): Env | null {
    return c?.env ?? null;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected setParam(i: number, patch: Partial<ParamDraft>): void {
    this.params.update((ps) => ps.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  }

  protected setType(i: number, e: Event): void {
    this.setParam(i, { type: this.value(e) as ParamType });
  }

  protected setName(v: string): void {
    this.name.set(v);
    if (this.errors().name) this.errors.update((e) => ({ ...e, name: undefined }));
  }

  protected setSql(v: string): void {
    this.sql.set(v);
    if (this.errors().sql) this.errors.update((e) => ({ ...e, sql: undefined }));
  }

  protected setConnection(v: string): void {
    this.connection.set(v || null);
    if (this.errors().connection) this.errors.update((e) => ({ ...e, connection: undefined }));
  }

  /** Discard: back to the saved template (a new one: back to empty). */
  protected discard(e: Event): void {
    e.preventDefault();
    this.load(this.target());
    this.announcer.announce('Changes discarded');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy()) return;
    const errors: { -readonly [K in keyof FieldErrors]?: string } = {};
    const name = this.name().trim();
    if (!TOOL_NAME.test(name)) errors.name = 'Use lowercase letters, digits and _, starting with a letter.';
    if (!this.connection()) errors.connection = 'Choose the connection it runs on.';
    if (!this.sql().trim()) errors.sql = 'Write the SQL the tool runs.';
    else if (this.scan().error) errors.sql = this.scan().error!;
    this.errors.set(errors);
    if (Object.keys(errors).length) {
      const first = errors.name ? 'tpl-name' : errors.connection ? 'tpl-conn' : 'tpl-sql';
      this.host.querySelector<HTMLElement>(`#${first}`)?.focus();
      return;
    }
    const template = this.editing();
    if (template && !this.dirty()) {
      this.announcer.announce('No changes to save');
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    this.compileErrors.set([]);
    this.warnings.set([]);
    try {
      const definition = toDefinition(this.draft());
      const result: TemplateSaved = template
        ? await this.data.updateTemplate(template.id, definition, template.version)
        : await this.data.createTemplate('sql-query', this.connection()!, definition);
      if (!result.template || result.compile?.ok === false) {
        // Compiling refused it: nothing was saved; what the core said stays above the actions.
        this.compileErrors.set(result.compile?.errors?.length ? result.compile.errors : ['The core didn’t compile this template.']);
        this.focusAfterRender('#tpl-compile');
        return;
      }
      this.warnings.set(result.compile?.warnings ?? []);
      this.snapshot.set(this.serialize());
      this.toasts.show({ tone: 'success', title: `Template ${result.template.name} saved`, body: 'Add it to an endpoint to let agents call it.' });
      this.saved.emit(result.template);
    } catch (err) {
      if (isApiError(err) && err.field) {
        const field = err.field.startsWith('/name') || err.field.startsWith('/definition/name') ? 'name' : err.field.startsWith('/connection') ? 'connection' : err.field.includes('sql') ? 'sql' : null;
        if (field) {
          this.errors.set({ [field]: err.detail });
          this.host.querySelector<HTMLElement>(`#tpl-${field === 'connection' ? 'conn' : field}`)?.focus();
          return;
        }
      }
      this.failure.set(failureText(err, 'This build of the gateway core can’t save SQL query templates yet.'));
      this.focusAfterRender('#tpl-failure');
    } finally {
      this.busy.set(false);
    }
  }

  private focusAfterRender(selector: string): void {
    afterNextRender(() => this.host.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector });
  }
}
