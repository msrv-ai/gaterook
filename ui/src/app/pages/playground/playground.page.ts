import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { isApiError, isNotAvailable } from '../../core/api/api-error';
import { JsonObject } from '../../core/api/types';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwToggle } from '../../kit/disclosure';
import { MODES } from '../../kit/elements/shared/dictionaries';
import { GwToolPolicyList } from '../../kit/elements/tool-policy-list/tool-policy-list';
import { GwIcon } from '../../kit/icons';
import { GwTabs } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import { SchemaForm, createSchemaForm } from '../../schema-form/schema-form';
import { GwPlaygroundFields } from './playground-fields';
import { GwPlaygroundResult } from './playground-result';
import { PlaygroundData } from './playground.data';
import {
  Chosen,
  RunState,
  approvalToast,
  carryOver,
  chooseEndpoint,
  chosenAnnouncement,
  connectionOptions,
  defaultPair,
  endpointAnnouncement,
  endpointOption,
  findPair,
  groupConnections,
  historyRows,
  modelFromArguments,
  parseArguments,
  pickerGroups,
  prodWarningTail,
  resultTable,
  runDecision,
  runStatus,
  runState,
  toolDescriptions,
  wireJson,
} from './playground-view';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

const isObject = (v: unknown): v is JsonObject => !!v && typeof v === 'object' && !Array.isArray(v);

/** The `enum` of a schema's `connection` property, if it lists one. */
function connectionEnum(schema: JsonObject | null | undefined): unknown[] {
  const props = schema?.['properties'];
  const conn = isObject(props) ? props['connection'] : null;
  return isObject(conn) && Array.isArray(conn['enum']) ? conn['enum'] : [];
}

/**
 * Playground (design/pages/playground.html; ARCHITECTURE.md §8.1 `playground?endpoint=&tool=`, §8.4 "playground"):
 * call a tool the way an agent does and see exactly what it gets back. Calls are real — the same policies,
 * approvals, masking, limits and audit as an agent's, as the principal `playground`.
 *
 * - Left, what to call: the endpoint (its URL; a prod endpoint warns which connections calls run on), the chosen tool
 *   with its connection, environment and mode, and Change tool, which opens the picker (tool-policy-list: search over
 *   names, connections and descriptions, "4 of 22 tools match" with mode chips, annotations) and closes once a row
 *   is picked, moving focus to the first argument. Arguments come from the tool's inputSchema, as a form or as JSON
 *   (both edit the same arguments and are checked before the call). A tool in Approval says "This call needs
 *   approval" before it runs; an Off tool can't run and says why (Run stays `aria-disabled` with the reason).
 * - Right, what came back: the meta strip, Structured / Text / JSON-RPC, the event under a disclosure, and the last
 *   five runs, each opening in Activity.
 * - Run: ⌘/Ctrl + Enter too. A run in flight shows placeholders and a busy Run; a mutation becomes an approval
 *   request (a toast, "Review request"), and once a person decides, `approval_status` brings the outcome here.
 *
 * States: nothing for 300 ms, then skeletons; errors with Try again and Details; no endpoints; a link to an endpoint
 * that doesn't exist; nothing run yet; an empty result; no history.
 */
@Component({
  selector: 'gw-playground-page',
  imports: [RouterLink, GwIcon, GwToggle, GwTabs, GwCodeBlock, GwToolPolicyList, GwPlaygroundFields, GwPlaygroundResult],
  providers: [PlaygroundData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--full' },
  templateUrl: './playground.page.html',
})
export class PlaygroundPage {
  /** `?endpoint=` (withComponentInputBinding): Open in Playground of an endpoint, the select. */
  readonly endpoint = input<string | null>(null);
  /** `?tool=`: a picker row, the Overview checklist. */
  readonly tool = input<string | null>(null);

  protected readonly data = inject(PlaygroundData);
  protected readonly time = inject(TimeService);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  private readonly pickerToggle = viewChild<GwToggle>('pickerToggle');
  private readonly argTabs = viewChild<GwTabs>('argTabs');

  /** The picked row: tool × connection, and its mode on the endpoint. */
  protected readonly chosen = signal<Chosen | null>(null);
  /** The arguments of the chosen tool (a schema form over its inputSchema). */
  protected readonly args = signal<SchemaForm | null>(null);
  /** What is typed in the JSON tab, while it is the source of the arguments; null when it follows the form. */
  protected readonly jsonDraft = signal<string | null>(null);
  protected readonly argsView = signal<'form' | 'json'>('form');
  /** The picker's search (the element's `query`). */
  protected readonly query = signal('');
  /** The section's live status (kit `#pg-out-status`): "Running sql_query", "sql_query allowed in 38 ms: 12 rows…". */
  protected readonly outStatus = signal('');
  /** Why the last Run didn't reach the endpoint (the admin API refused it, or the core didn't answer). */
  protected readonly runError = signal<unknown>(null);
  protected readonly slow = signal(false);
  /** A link named an endpoint that doesn't exist: the page opens another and says so. */
  protected readonly missing = signal<string | null>(null);

  /** `slug|tool` whose choice is on screen: a reload of the same endpoint keeps the arguments as edited. */
  private appliedKey: string | null = null;
  /** The endpoint picked in the select: once its tools load, the picker opens on it (kit). */
  private switching: string | null = null;
  private readonly toasted = new Set<string>();

  protected readonly endpoints = this.data.endpoints;
  protected readonly slug = this.data.slug;
  protected readonly summary = this.data.summary;
  protected readonly prod = computed(() => this.summary()?.env === 'prod');
  /** The open endpoint's tools, once they are its. */
  protected readonly loaded = computed(() => {
    const t = this.data.tools();
    return t && t.slug === this.slug() ? t : null;
  });
  protected readonly groups = computed(() => {
    const t = this.loaded();
    return t ? pickerGroups(t.groups, this.data.connections()) : [];
  });
  protected readonly descriptions = computed(() => toolDescriptions(this.loaded()?.tools.tools ?? []));
  protected readonly toolCount = computed(() => this.groups().reduce((n, g) => n + g.group.tools.length, 0));
  /** The chosen tool as the Playground API describes it: schema, whether Run works and why not. */
  protected readonly playTool = computed(() => {
    const c = this.chosen();
    return c ? (this.loaded()?.tools.tools.find((t) => t.name === c.name) ?? null) : null;
  });
  protected readonly state = computed<RunState | null>(() => {
    const c = this.chosen();
    return c ? runState(c, this.playTool()) : null;
  });
  protected readonly cannotRun = computed(() => this.state() === 'off' || this.state() === 'blocked');
  protected readonly busy = computed(() => !!this.data.running());
  protected readonly prodConnections = computed(() => (this.prod() ? groupConnections(this.loaded()?.groups ?? []) : []));
  protected readonly prodTail = computed(() => prodWarningTail(this.loaded()?.tools.tools ?? [], this.data.commentRequired()));
  /** The choices of the `connection` argument. */
  protected readonly choices = computed(() => {
    const c = this.chosen();
    if (!c) return [];
    const listed = connectionEnum(this.playTool()?.inputSchema).filter((v): v is string => typeof v === 'string');
    return connectionOptions(c, this.playTool(), listed);
  });
  /** The JSON tab: what was typed there, else the form's arguments. */
  protected readonly jsonText = computed(() => this.jsonDraft() ?? wireJson(this.args()?.value() ?? {}));
  protected readonly jsonProblem = computed(() => {
    const draft = this.jsonDraft();
    const form = this.args();
    if (draft === null || !form) return null;
    const parsed = parseArguments(draft, form.compiled.all);
    return parsed.ok ? null : parsed.problem;
  });
  /** The connection the arguments name now (the row's: the select only offers it). */
  protected readonly argConnection = computed(() => {
    const v = this.args()?.value()['connection'];
    return typeof v === 'string' ? v : null;
  });
  protected readonly lastRun = this.data.lastRun;
  /** One element while there is a run: the result view is made anew for each run (its tabs start where the kit's do). */
  protected readonly runs = computed(() => {
    const r = this.lastRun();
    return r ? [r] : [];
  });
  protected readonly lastEvent = computed(() => this.data.eventOf(this.lastRun()?.call.callId ?? null));
  protected readonly historyRows = computed(() => {
    const h = this.data.history();
    return h ? historyRows(h, this.lastRun()?.call.callId ?? null, this.data.measured()) : null;
  });
  protected readonly endpointOption = endpointOption;
  protected readonly modes = MODES;

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    inject(DestroyRef).onDestroy(() => clearTimeout(timer));
    // The endpoint the link names, else the first that isn't prod.
    effect(() => {
      const list = this.endpoints();
      const wanted = this.endpoint();
      if (!list) return;
      const pick = chooseEndpoint(list, wanted);
      untracked(() => {
        this.missing.set(pick.missing && pick.missing !== pick.slug ? pick.missing : null);
        if (pick.slug && pick.slug !== this.data.slug()) void this.data.open(pick.slug);
      });
    });
    // Once the endpoint's tools are in: the tool the link names, else the default; a reload keeps the choice.
    effect(() => {
      const loaded = this.loaded();
      const wanted = this.tool();
      if (!loaded) return;
      untracked(() => this.apply(loaded.slug, wanted));
    });
    // The core created the request of a run in flight (it may hold the answer while it waits for a person).
    effect(() => {
      const sent = this.data.requestSent();
      if (sent) untracked(() => this.toastApproval(sent.id, sent.tool, sent.connection));
    });
    // Every new answer — this page's run or the outcome of its approval request — is said in the section's status.
    effect(() => {
      const run = this.lastRun();
      if (!run) return;
      untracked(() => this.outStatus.set(runStatus(run.tool, runDecision(run.call, null), run.call, resultTable(run.call.structured))));
    });
  }

  // ─── Endpoint ───

  protected onEndpoint(event: Event): void {
    const slug = (event.target as HTMLSelectElement).value;
    if (!slug || slug === this.slug()) return;
    this.switching = slug;
    void this.router.navigate(['/playground'], { queryParams: { endpoint: slug } });
  }

  protected retryEndpoints(): void {
    void this.data.loadEndpoints();
  }

  protected retryTools(): void {
    void this.data.retryTools();
  }

  protected retryHistory(): void {
    void this.data.reloadHistory();
  }

  // ─── Tool ───

  private apply(slug: string, wanted: string | null): void {
    const key = `${slug}|${wanted ?? ''}`;
    const groups = this.loaded()?.groups ?? [];
    if (key === this.appliedKey) {
      // The same endpoint again (its tools reloaded): the pair's mode may have changed, the arguments stay.
      const c = this.chosen();
      const fresh = c ? findPair(groups, c.name, c.connection) : null;
      if (c && fresh && fresh.mode !== c.mode) this.chosen.set(fresh);
      return;
    }
    const firstOpen = this.appliedKey === null;
    // A row picked on this endpoint already chose its tool: the link's `?tool=` only follows.
    const sameEndpoint = this.appliedKey?.startsWith(`${slug}|`) ?? false;
    this.appliedKey = key;
    if (sameEndpoint && wanted && this.chosen()?.name === wanted) return;
    const pair = (wanted ? findPair(groups, wanted) : null) ?? defaultPair(groups);
    const switching = this.switching === slug;
    this.switching = null;
    if (!pair) {
      this.chosen.set(null);
      this.args.set(null);
      return;
    }
    if (switching) {
      // Kit: another endpoint opens the picker on all its tools, with its default tool chosen.
      this.query.set('');
      this.choose(pair, { quiet: true, collapse: false });
      afterNextRender(() => this.pickerToggle()?.set(true), { injector: this.injector });
      const prodConns = this.prod() ? groupConnections(groups) : null;
      this.announcer.announce(endpointAnnouncement(slug, this.toolCount(), prodConns, pair.name));
      return;
    }
    // A link to a tool (?tool=): the search names it, as the kit's script does.
    if (wanted && firstOpen) this.query.set(wanted);
    this.choose(pair, { quiet: firstOpen, collapse: true });
  }

  /** A row of the picker: chosen at once (the link then puts `?tool=` in the URL), the picker closes, focus to the arguments. */
  protected onPicked(pick: { readonly tool: string; readonly connection: string | null }): void {
    const slug = this.slug();
    const pair = findPair(this.loaded()?.groups ?? [], pick.tool, pick.connection);
    if (!slug || !pair) return;
    this.appliedKey = `${slug}|${pick.tool}`;
    this.choose(pair, { focus: true, collapse: true });
  }

  private choose(pair: Chosen, opts: { readonly quiet?: boolean; readonly collapse?: boolean; readonly focus?: boolean; readonly fresh?: boolean }): void {
    const tool = this.loaded()?.tools.tools.find((t) => t.name === pair.name) ?? null;
    const schema: JsonObject = tool?.inputSchema ?? { type: 'object', properties: {} };
    const props = isObject(schema['properties']) ? schema['properties'] : {};
    const listed = connectionEnum(schema).filter((v): v is string => typeof v === 'string');
    const choices = connectionOptions(pair, tool, listed);
    // The form keeps what was typed when the next tool takes the same argument (kit), unless Reset asked for a fresh one.
    const current: JsonObject = opts.fresh ? {} : carryOver(this.args()?.value() ?? {}, schema);
    if ('connection' in props) {
      const kept = current['connection'];
      const conn = pair.connection ?? (typeof kept === 'string' && choices.includes(kept) ? kept : choices.length === 1 ? choices[0] : null);
      if (conn) current['connection'] = conn;
      else delete current['connection'];
    }
    this.chosen.set(pair);
    this.args.set(createSchemaForm(schema, { idPrefix: 'pg-a', current: Object.keys(current).length ? current : null, injector: this.injector }));
    this.jsonDraft.set(null);
    if (opts.collapse) this.pickerToggle()?.set(false);
    if (opts.focus) afterNextRender(() => this.focusFirstArgument(), { injector: this.injector });
    const slug = this.slug();
    if (!opts.quiet && slug) this.announcer.announce(chosenAnnouncement(pair.name, pair.mode, slug));
  }

  private focusFirstArgument(): void {
    const panel = this.host.querySelector(this.argsView() === 'json' ? '#pg-panel-json' : '#pg-panel-form');
    // A tool without arguments: Run is next (the picker that had focus is closed now).
    const target = panel?.querySelector<HTMLElement>('input:not([type="hidden"]), select, textarea') ?? this.host.querySelector<HTMLElement>('#pg-run');
    target?.focus();
  }

  // ─── Arguments ───

  protected onArgsView(tabId: string): void {
    this.argsView.set(tabId === 'pg-tab-json' ? 'json' : 'form');
  }

  /** Typed in the form: the JSON tab follows the form again. */
  protected formEdited(): void {
    if (this.jsonDraft() !== null) this.jsonDraft.set(null);
  }

  /** Typed in the JSON tab: valid arguments go into the form at once; otherwise the form keeps the last valid ones. */
  protected onJson(event: Event): void {
    const text = (event.target as HTMLTextAreaElement).value;
    this.jsonDraft.set(text);
    const form = this.args();
    if (!form) return;
    const parsed = parseArguments(text, form.compiled.all);
    if (!parsed.ok) return;
    form.jsonText.set({});
    form.model.set(modelFromArguments(form.model(), parsed.value));
  }

  /** Reset: the tool's own defaults and the row's connection, in both tabs (kit "Arguments reset"). */
  protected onReset(event: Event): void {
    event.preventDefault();
    const c = this.chosen();
    if (!c) return;
    this.choose(c, { quiet: true, fresh: true });
    this.runError.set(null);
    this.announcer.announce('Arguments reset');
  }

  /** ⌘ Enter / Ctrl + Enter runs from anywhere in the form (kit). */
  protected onFormKey(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || !(event.metaKey || event.ctrlKey)) return;
    event.preventDefault();
    void this.run();
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    void this.run();
  }

  // ─── Run ───

  protected async run(): Promise<void> {
    const c = this.chosen();
    const form = this.args();
    const slug = this.slug();
    if (!c || !form || !slug || this.busy()) return;
    const state = this.state();
    if (state === 'off' || state === 'blocked') {
      this.announcer.announce(state === 'off' ? `${c.name} is off on endpoint ${slug} and can't run.` : `${c.name} can't run on endpoint ${slug}. ${this.playTool()?.reason ?? ''}`.trim());
      this.host.querySelector('#pg-off-alert')?.scrollIntoView?.({ block: 'nearest' });
      return;
    }
    if (this.jsonDraft() !== null && this.jsonProblem()) {
      this.argTabs()?.selectById('pg-tab-json');
      this.argsView.set('json');
      this.host.querySelector<HTMLElement>('#pg-json')?.focus();
      this.announcer.announce('Fix the JSON arguments first.');
      return;
    }
    if (!form.submit()) {
      if (this.argsView() === 'json') {
        this.argTabs()?.selectById('pg-tab-form');
        this.argsView.set('form');
      }
      this.announcer.announce('Fix the highlighted argument first.');
      return;
    }
    const connection = this.argConnection();
    const env = this.data.connections().find((x) => x.name === connection)?.env ?? c.env ?? this.summary()?.env ?? null;
    this.runError.set(null);
    this.outStatus.set(`Running ${c.name}`);
    const outcome = await this.data.run({ tool: c.name, arguments: form.value(), connection, env });
    if (!outcome.ok) {
      this.runError.set(outcome.error);
      this.outStatus.set(`${c.name} didn't run. ${this.errorText(outcome.error)}`);
      return;
    }
    const approval = outcome.run.call.approval;
    if (approval && (approval.status === 'pending' || approval.status === 'running')) this.toastApproval(approval.handle, c.name, connection);
  }

  private toastApproval(id: string, tool: string, connection: string | null): void {
    if (this.toasted.has(id)) return;
    this.toasted.add(id);
    const t = approvalToast(tool, connection);
    this.toasts.show({ title: t.title, body: t.body, tone: 'warning', icon: 'hourglass' });
  }

  // ─── Text ───

  /** What a failed call says to do: the core's words when it sent them. */
  protected errorText(e: unknown, fallback = 'See the core log for details, then try again.'): string {
    if (isNotAvailable(e)) return 'This build of the core has no Playground yet.';
    return isApiError(e) ? e.detail : fallback;
  }

  /** The technical side of a failed call, for the code block under Details. */
  protected errorDetails(e: unknown, route: string): string {
    if (isApiError(e)) return `${route} → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }
}
