import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  model,
  numberAttribute,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ToolRow } from '../../../core/api/endpoints.api';
import { Env, JsonObject, ToolMode } from '../../../core/api/types';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwMenu, GwMenuTrigger, MenuPick } from '../../menu';
import { GwSearchClearButton } from '../../search';
import { connectorIcon } from '../shared/connector-icons';
import { MODES } from '../shared/dictionaries';
import {
  LimitField,
  MODE_FILTERS,
  MODE_VALUE,
  ModeFilter,
  ModeNote,
  ToolPolicyGroup,
  bulkHints,
  inherited,
  limitFields,
  matches,
  modeCounts,
  modeNote,
  overrideCount,
  overridden,
  policyEnv,
  toolCount,
  withUnit,
} from './tool-policy-view';

let nextId = 0;

export interface ToolModeChange {
  readonly toolKey: string;
  readonly connection: string | null;
  readonly mode: ToolMode;
  readonly version: number;
}

export interface ToolOverridesChange {
  readonly toolKey: string;
  readonly connection: string | null;
  /** The pair's stricter limits; null resets it to the environment policy. */
  readonly overrides: JsonObject | null;
  readonly version: number;
}

/** A tool row as drawn: the API row, its sentence, mode note, limit fields and the policy it inherits. */
interface RowView {
  readonly tool: ToolRow;
  readonly key: string;
  readonly id: string;
  readonly description: string | null;
  readonly note: ModeNote | null;
  readonly fields: readonly LimitField[];
  readonly env: Env;
  readonly overrides: number;
}

interface GroupView {
  readonly source: ToolPolicyGroup;
  readonly id: string;
  readonly connection: string | null;
  readonly title: string;
  readonly icon: string;
  readonly description: string;
  readonly rows: readonly RowView[];
  readonly external: boolean;
  /** The core's own tools (no connection): no limits and no "Set all to…" (kit endpoint-detail "Built-in tools"). */
  readonly builtIn: boolean;
  readonly hints: { readonly off: string; readonly read: string; readonly approval: string };
  readonly total: number;
}

/**
 * `<div gwToolPolicyList [groups]="…" slug="dev" (modeChange)="…">` (elements/tool-policy-list.html): what agents may
 * call on one endpoint, and how. Every tool grouped by the connection it runs on, with its MCP annotations, the mode
 * picker Off · Read only · Approval and, in a disclosure row, per-tool limits over the environment policy of the
 * connection (an override can only be stricter). There is no mode that lets an agent change data without you: a
 * tool that changes data offers Approval or Off, with the reason under the picker, and its Read only stays NATIVELY
 * disabled (DESIGN.md §8: safety must not depend on a script). Tools of external MCP servers and plugins say who
 * declared their annotations; Read only for one of them needs a confirmation for that tool.
 *
 * `variant="picker"` is Playground's tool picker: the same groups, the mode as a `gw-mode` chip, rows link to the tool
 * (`picked` says which row, so the page knows the connection too); the search also matches connection names, and
 * `summaryChips` gives the summary of the Playground page ("4 of 22 tools match" + mode chips) instead of the
 * element page's "14 tools on endpoint dev".
 * The element changes nothing itself: it emits `modeChange`, `bulk`, `overridesChange` and `confirmReadOnly` for the
 * page to send (`EndpointsApi`), and shows the groups it is given.
 */
@Component({
  selector: 'div[gwToolPolicyList]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwToggle, GwSearchClearButton, GwDialog, GwDialogClose],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-tool-policy-list' },
  templateUrl: './tool-policy-list.html',
})
export class GwToolPolicyList {
  private readonly doc = inject(DOCUMENT);
  readonly groups = input.required<readonly ToolPolicyGroup[]>();
  /** The endpoint's slug ("Search tools on dev", "Mode on dev"). */
  readonly slug = input.required<string>();
  /** The endpoint's environment: the policy of a group without a connection. */
  readonly endpointEnv = input<Env>('dev');
  /** A read-only endpoint: tools that change data stay off, their pickers locked. */
  readonly readOnly = input(false, { transform: booleanAttribute });
  /** Tool name → one sentence (the connector type's tool descriptions). */
  readonly descriptions = input<Readonly<Record<string, string>>>({});
  /** Environment → its effective policy sections (`GET /api/policies`): the inherited limits. */
  readonly policies = input<Partial<Record<Env, JsonObject>>>({});
  readonly variant = input<'edit' | 'picker'>('edit');
  /** Picker: the current tool, and where a row leads (its `?tool=`). */
  readonly selected = input<string | null>(null);
  /**
   * Picker: the connection of the current tool's row, when the same tool runs on several connections of the endpoint
   * (`undefined`: every row of the tool is the current one; `null`: the row without a connection).
   */
  readonly selectedConnection = input<string | null | undefined>(undefined);
  readonly pickerRoute = input<string>('/playground');
  /** Picker: the summary counts the shown tools by mode, as the Playground page does. */
  readonly summaryChips = input(false, { transform: booleanAttribute });
  /** What the summary counts ("tools", "new tools"). */
  readonly noun = input('tools');
  readonly showModeFilter = input(true, { transform: booleanAttribute });
  /** Limits rows open at first: `connection/toolKey`. */
  readonly openLimits = input<readonly string[]>([]);
  readonly level = input(3, { transform: numberAttribute });

  /** The search and the mode filter (two-way: a page may keep them in its URL). */
  readonly query = model('');
  readonly modeFilter = model<ModeFilter>('ALL');

  readonly modeChange = output<ToolModeChange>();
  readonly bulk = output<{ readonly connection: string | null; readonly mode: ToolMode }>();
  readonly overridesChange = output<ToolOverridesChange>();
  readonly confirmReadOnly = output<{ readonly toolKey: string; readonly connection: string | null; readonly definitionHash: string }>();
  /** Picker: a row's link was activated (before the router follows it): the tool and the connection of its group. */
  readonly picked = output<{ readonly tool: string; readonly connection: string | null }>();

  protected readonly uid = `tpl-${++nextId}`;
  protected readonly filters = MODE_FILTERS;
  protected readonly modes = MODES;
  protected readonly modeValue = MODE_VALUE;
  /** Modes picked here and not yet answered by new groups from the page. */
  private readonly localModes = signal<Readonly<Record<string, ToolMode>>>({});
  /** A limit value that would loosen the policy: shown at its field, not sent. */
  protected readonly limitErrors = signal<Readonly<Record<string, string>>>({});
  protected readonly confirming = signal<{ readonly row: RowView; readonly group: GroupView } | null>(null);
  protected readonly acknowledged = signal(false);
  private readonly confirmDialog = viewChild<GwDialog>('confirmDialog');

  constructor() {
    // New groups from the page are the truth: local picks and errors were answered (or refused) by them.
    effect(() => {
      this.groups();
      untracked(() => {
        this.localModes.set({});
        this.limitErrors.set({});
      });
    });
  }

  protected readonly picker = computed(() => this.variant() === 'picker');
  protected readonly filterLabel = computed(() => MODE_FILTERS.find((f) => f.value === this.modeFilter())?.label ?? 'All');

  private readonly all = computed<GroupView[]>(() =>
    this.groups().map((g) => {
      const connection = g.group.connection;
      const env = policyEnv(g.group, this.endpointEnv());
      const local = this.localModes();
      const rows: RowView[] = g.group.tools.map((t) => {
        const key = `${connection ?? 'core'}/${t.toolKey}`;
        const tool = local[key] ? { ...t, mode: local[key] } : t;
        const fields = limitFields(g.type);
        return {
          tool,
          key,
          id: `${this.uid}-${(connection ?? 'core').replace(/[^A-Za-z0-9_-]/g, '_')}-${t.toolKey.replace(/[^A-Za-z0-9_-]/g, '_')}`,
          description: this.descriptions()[t.name] ?? null,
          note: modeNote(tool, { slug: this.slug(), readOnly: this.readOnly() }),
          fields,
          env,
          overrides: overrideCount(fields, t.overrides),
        };
      });
      const external = rows.length > 0 && rows.every((r) => !!r.tool.declaredBy);
      const builtIn = connection === null;
      return {
        source: g,
        id: `${this.uid}-g-${(connection ?? 'core').replace(/[^A-Za-z0-9_-]/g, '_')}`,
        connection,
        title: g.title ?? connection ?? (builtIn ? 'Built-in tools' : g.group.group),
        icon: g.type ? connectorIcon(g.type) : 'waypoints',
        description: '',
        rows,
        external,
        builtIn,
        hints: bulkHints(connection ?? g.group.group, rows.map((r) => r.tool), { readOnly: this.readOnly() }),
        total: rows.length,
      };
    }),
  );

  protected readonly totalTools = computed(() => this.all().reduce((n, g) => n + g.total, 0));

  /** The groups after search and mode filter; groups without a match are left out. */
  protected readonly shown = computed<GroupView[]>(() => {
    const q = this.query();
    const mode = this.modeFilter();
    const filtering = q.trim() !== '' || mode !== 'ALL';
    // The picker searches connections too (kit playground.html: "tool names, connections and descriptions").
    const picker = this.picker();
    return this.all()
      .map((g) => {
        const rows = g.rows.filter((r) => matches(r.tool, picker ? `${r.description ?? ''} ${g.connection ?? ''}` : r.description, q, mode));
        const detail = g.source.detail ? `${g.source.detail} · ` : '';
        const counts = modeCounts(g.rows.map((r) => r.tool)).map((c) => c.text);
        const description = filtering
          ? `${detail}${rows.length} of ${toolCount(g.total)} shown`
          : [g.source.detail, toolCount(g.total), ...counts].filter(Boolean).join(' · ');
        return { ...g, rows, description };
      })
      .filter((g) => g.rows.length > 0);
  });

  protected readonly filtering = computed(() => this.query().trim() !== '' || this.modeFilter() !== 'ALL');
  protected readonly shownCount = computed(() => this.shown().reduce((n, g) => n + g.rows.length, 0));
  protected readonly summary = computed(() => {
    if (this.picker() && !this.filtering()) return this.summaryChips() ? toolCount(this.totalTools()) : `${toolCount(this.totalTools())} on endpoint ${this.slug()}`;
    if (this.filtering()) return `${this.shownCount()} of ${toolCount(this.totalTools())} match`;
    return `${this.totalTools()} ${this.noun()}`;
  });
  protected readonly chips = computed(() =>
    this.picker() && !this.summaryChips() ? [] : modeCounts(this.shown().flatMap((g) => g.rows.map((r) => r.tool))),
  );

  /** Picker: the row of the current tool (and, when given, of its connection). */
  protected isSelected(g: GroupView, r: RowView): boolean {
    if (!this.picker() || this.selected() !== r.tool.name) return false;
    const conn = this.selectedConnection();
    return conn === undefined || conn === g.connection;
  }

  protected onPick(g: GroupView, r: RowView): void {
    this.picked.emit({ tool: r.tool.name, connection: g.connection });
  }

  protected readonly emptyTitle = computed(() => {
    const q = this.query().trim();
    if (!this.totalTools()) return 'No tools yet';
    if (q) return `No tools match “${q}”`;
    return `No tools in ${this.filterLabel().toLowerCase()} mode`;
  });

  protected clearFilters(): void {
    this.query.set('');
    this.modeFilter.set('ALL');
    this.doc.getElementById(`${this.uid}-q`)?.focus();
  }

  protected isOpen(r: RowView): boolean {
    return this.openLimits().includes(r.key);
  }

  protected onQuery(e: Event): void {
    this.query.set((e.target as HTMLInputElement).value);
  }

  protected onFilter(pick: MenuPick): void {
    this.modeFilter.set(pick.value as ModeFilter);
  }

  protected onMode(g: GroupView, r: RowView, value: string): void {
    const mode = (Object.keys(MODE_VALUE) as ToolMode[]).find((m) => MODE_VALUE[m] === value);
    if (!mode || mode === r.tool.mode) return;
    this.localModes.update((m) => ({ ...m, [r.key]: mode }));
    this.modeChange.emit({ toolKey: r.tool.toolKey, connection: g.connection, mode, version: r.tool.version });
  }

  protected onBulk(g: GroupView, pick: MenuPick): void {
    const mode = pick.value as ToolMode;
    this.bulk.emit({ connection: g.connection, mode });
  }

  protected policyValue(r: RowView, f: LimitField): number | null {
    return inherited(f, this.policies()[r.env]);
  }

  protected ownValue(r: RowView, f: LimitField): number | null {
    return overridden(f, r.tool.overrides);
  }

  protected placeholder(r: RowView, f: LimitField): string {
    const v = this.policyValue(r, f);
    return v === null ? '' : v.toLocaleString('en-US');
  }

  protected hint(r: RowView, f: LimitField): string {
    const v = this.policyValue(r, f);
    const policy = `${r.env} policy`;
    const value = v === null ? '' : `: ${withUnit(v, f.unit)}`;
    return this.ownValue(r, f) === null ? `Inherited from ${policy}${value}` : `Overrides ${policy}${value}`;
  }

  protected errorOf(r: RowView, f: LimitField): string | null {
    return this.limitErrors()[`${r.key}/${f.key}`] ?? null;
  }

  /** A field left: an empty value inherits again, a number must be stricter than the policy. */
  protected onLimit(g: GroupView, r: RowView, f: LimitField, e: Event): void {
    const raw = (e.target as HTMLInputElement).value.replace(/[,\s]/g, '');
    const errKey = `${r.key}/${f.key}`;
    const setError = (msg: string | null): void =>
      this.limitErrors.update((m) => {
        const next = { ...m };
        if (msg) next[errKey] = msg;
        else delete next[errKey];
        return next;
      });
    const next: JsonObject = { ...(r.tool.overrides ?? {}) };
    if (raw === '') {
      delete next[f.key];
    } else {
      const n = Number(raw);
      const policy = this.policyValue(r, f);
      if (!Number.isFinite(n) || n <= 0) {
        setError(`Enter a number of ${f.unit}, or leave it empty to follow the ${r.env} policy.`);
        return;
      }
      if (policy !== null && n > policy) {
        setError(`Can only be stricter than the ${r.env} policy: ${withUnit(policy, f.unit)} or less.`);
        return;
      }
      next[f.key] = Math.round(n * f.scale);
    }
    setError(null);
    this.overridesChange.emit({
      toolKey: r.tool.toolKey,
      connection: g.connection,
      overrides: Object.keys(next).length ? next : null,
      version: r.tool.version,
    });
  }

  protected reset(g: GroupView, r: RowView): void {
    this.overridesChange.emit({ toolKey: r.tool.toolKey, connection: g.connection, overrides: null, version: r.tool.version });
  }

  protected askConfirm(g: GroupView, r: RowView, opener: HTMLElement): void {
    this.acknowledged.set(false);
    this.confirming.set({ row: r, group: g });
    this.confirmDialog()?.openAfterRender(opener);
  }

  protected allow(): void {
    const c = this.confirming();
    if (!c || !this.acknowledged() || !c.row.tool.definitionHash) return;
    this.confirmReadOnly.emit({ toolKey: c.row.tool.toolKey, connection: c.group.connection, definitionHash: c.row.tool.definitionHash });
    this.confirmDialog()?.close('allow');
  }

  protected onAck(e: Event): void {
    this.acknowledged.set((e.target as HTMLInputElement).checked);
  }
}
