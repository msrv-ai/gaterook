import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../../code/code-block';
import { ConnectionRow } from '../../../core/api/connections.api';
import { Template } from '../../../core/api/templates.api';
import { TimeService } from '../../../core/time/time.service';
import { GwAriaDisabled } from '../../../kit/aria-disabled';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../../kit/menu';
import { ModeChip, endpointFor, templateEndpoints, templateKind, templateModes, templateSignature, templateToolNames } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureDetails, failureText } from '../dialogs/failure';
import { fromDefinition } from '../template-sql';
import { EditorTarget, GwTemplateEditor } from './template-editor';

/** What a template row asks the page to do (its confirmations live outside the tabs). */
export interface TemplateAction {
  readonly type: 'delete' | 'keep';
  readonly template: Template;
  readonly opener: HTMLElement | null;
}

/** One row of Templates, as the kit lays it out (kind and update time under the name, so 1440 fits). */
export interface TemplateRow {
  readonly template: Template;
  /** The name as the list shows it: a SQL query's signature "orders_by_status(status, since)" (step API), else the name. */
  readonly label: string;
  readonly kind: { readonly label: string; readonly icon: string; readonly noun: string };
  readonly runsOn: ConnectionRow | null;
  readonly modes: { readonly chips: ModeChip[]; readonly note: string };
  readonly endpoints: string[];
  /** Where to add it (an endpoint that has its connection, else one of its env), or the list. */
  readonly addTo: string | null;
  readonly playground: { readonly endpoint: string; readonly tool: string } | null;
  readonly activityQuery: string;
  readonly editable: boolean;
}

/**
 * `<div gwTemplatesPanel>` — Connectors › Templates (kit `#templates`): no-code tools from a SQL query, an SSH
 * runbook or an OpenAPI import, each on the connection it runs on. The table names each template with its kind and
 * update time, Runs on with the environment, its tools' default mode, and the endpoints that use them; the row menu
 * links to Activity, Playground and the endpoint to add it to, and deletes it. A SQL query template opens in the
 * editor below the table (New template › SQL query, or its name).
 *
 * New template offers a kind only when this core's connectors compile it (`templateKinds` of the connection types);
 * otherwise the menu item is unavailable with the reason.
 */
@Component({
  selector: 'div[gwTemplatesPanel]',
  imports: [RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwAriaDisabled, GwTemplateEditor, GwToggle, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-tabs__panel', role: 'tabpanel', id: 'templates', 'aria-labelledby': 'tab-templates', tabindex: '0' },
  templateUrl: './templates.panel.html',
})
export class GwTemplatesPanel {
  protected readonly data = inject(ConnectorsData);
  protected readonly time = inject(TimeService);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  readonly slow = input(false);
  readonly action = output<TemplateAction>();

  protected readonly target = signal<EditorTarget | null>(null);
  protected readonly opening = signal<string | null>(null);

  /** The connections a SQL query template can run on: types whose connector offers `sql-query`. */
  protected readonly sqlConnections = computed(() => {
    const types = this.data.templateKinds.value()?.get('sql-query') ?? [];
    return (this.data.connections.value() ?? []).filter((c) => types.includes(c.type));
  });

  /** Why New template › kind isn't offered: this core's connectors don't compile it (null: offered). */
  protected readonly kindReasons = computed(() => {
    const kinds = this.data.templateKinds.value();
    const loaded = kinds !== null;
    const reason = (kind: string, text: string): string | null => (loaded && kinds.has(kind) ? null : loaded ? text : 'Checking what this core offers…');
    return {
      sql: reason('sql-query', 'No installed SQL connector offers query templates.'),
      // These kinds have no editor in this build of the app (only SQL queries do): said as it is, never faked.
      ssh: kinds?.has('ssh-runbook') ? 'This build of the app has no runbook editor yet.' : reason('ssh-runbook', 'This build’s SSH connector doesn’t offer runbooks yet.'),
      openapi: kinds?.has('openapi') ? 'This build of the app has no OpenAPI import yet.' : reason('openapi', 'This build’s HTTP API connector can’t import OpenAPI files yet.'),
    };
  });

  protected readonly rows = computed<TemplateRow[] | null>(() => {
    const templates = this.data.templates.value();
    if (!templates) return null;
    const connections = this.data.connections.value() ?? [];
    const tools = this.data.endpointTools();
    const endpoints = this.data.endpoints.value() ?? [];
    return templates.map((t) => {
      const runsOn = connections.find((c) => c.name === t.connection) ?? null;
      const on = templateEndpoints(t.id, tools);
      const first = on[0] ?? null;
      const tool = first ? templateToolNames(t.id, tools.get(first))[0] : undefined;
      return {
        template: t,
        label: templateSignature(t),
        kind: templateKind(t.kind),
        runsOn,
        modes: templateModes(t),
        endpoints: on,
        addTo: endpointFor(t.connection, runsOn?.env ?? null, endpoints, tools),
        playground: first && tool ? { endpoint: first, tool } : null,
        activityQuery: t.kind === 'ssh-runbook' ? `runbook:${t.name}` : `tool:${tool ?? t.name}`,
        editable: t.kind === 'sql-query',
      };
    });
  });

  protected readonly errorText = (e: unknown): string => failureText(e);
  protected readonly errorDetails = (e: unknown): string => failureDetails('GET /api/templates', e);

  /** New template › SQL query: an empty editor under the table, focus on Tool name. */
  protected newSqlQuery(): void {
    if (this.kindReasons().sql) return;
    this.target.set({
      template: null,
      draft: { name: '', description: '', sql: '', params: [] },
      connection: this.sqlConnections()[0]?.name ?? null,
      unreadable: false,
      playground: null,
    });
    this.revealEditor();
  }

  /** Opens a saved SQL query template in the editor (its definition, when the core sends it). */
  protected async edit(row: TemplateRow, e: Event): Promise<void> {
    e.preventDefault();
    if (this.opening()) return;
    this.opening.set(row.template.id);
    let full: Template = row.template;
    try {
      full = await this.data.getTemplate(row.template.id);
    } catch {
      // Without the single read the list row stands in; its definition is unknown, so the editor says so.
    } finally {
      this.opening.set(null);
    }
    this.target.set({
      template: full,
      draft: full.definition ? fromDefinition(full.definition) : { name: full.name, description: '', sql: '', params: [] },
      connection: full.connection,
      unreadable: !full.definition,
      playground: row.playground,
    });
    this.revealEditor();
  }

  /** The editor saved a template: it stays open on the saved version. */
  protected onSaved(t: Template): void {
    const current = this.target();
    if (!current) return;
    this.target.set({ ...current, template: t, connection: t.connection, draft: current.draft });
  }

  protected act(type: TemplateAction['type'], template: Template, e: Event): void {
    const target = e.currentTarget as HTMLElement | null;
    const trigger = target?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
    this.action.emit({ type, template, opener: trigger ?? target });
  }

  protected retry(): void {
    void this.data.templates.refresh();
  }

  protected menuId(t: Template): string {
    return `menu-tpl-${t.id.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  }

  /** After a delete the editor of that template closes. */
  forget(id: string): void {
    if (this.target()?.template?.id === id) this.target.set(null);
  }

  private revealEditor(): void {
    afterNextRender(
      () => {
        const section = this.host.querySelector<HTMLElement>('#template-editor');
        section?.scrollIntoView?.({ block: 'start' });
        // Focus now, in this render: a second afterNextRender would wait for a render nothing schedules (zoneless).
        const name = this.target()?.unreadable ? null : section?.querySelector<HTMLElement>('#tpl-name');
        (name ?? section?.querySelector<HTMLElement>('.gw-card__title'))?.focus();
      },
      { injector: this.injector },
    );
  }
}
