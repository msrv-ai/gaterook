import {
  ChangeDetectionStrategy,
  Component,
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
  WritableSignal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FieldTree, FormField, TreeValidationResult, form, submit, validateTree } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { Notifications } from '../../core/api/notifications.api';
import { EnvPolicy } from '../../core/api/policies.api';
import { Env, JsonValue } from '../../core/api/types';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { PoliciesData, Usage } from './policies.data';
import {
  KeyInfo,
  MeterView,
  RunbookView,
  allowlistHosts,
  envConnections,
  followOption,
  hintFor,
  keyInfo,
  meter,
  numberError,
  optionsFor,
  placeholderFor,
  resetText,
  riskyEndpoints,
  savedToast,
  sectionConnections,
  storedPhrases,
  unsavedNote,
  valuesSummary,
  OptionView,
} from './policies-view';
import { CONNECTOR_SECTIONS, Hint, sectionOrder } from './policy-catalog';
import { FieldView, GwHintParts, GwPolicyCheckbox, GwPolicyChecks, GwPolicyNumber, GwPolicySelect, GwPolicyTags } from './policy-fields';
import {
  Draft,
  DraftValue,
  KeySchema,
  PoliciesByEnv,
  PolicySchemas,
  changes,
  editableKeys,
  effectiveValue,
  explicitKeys,
  formatNumber,
  inheritors,
  initialForm,
  keyId,
  patch,
  splitKeyId,
} from './policy-model';
import { EmptySection, LockRow, SectionCtx, SummaryRow, approvalRow, emptySection, extraKeys, lockRows, sectionDescription, summaryRows } from './policy-sections';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/** The kit's id prefixes: `pd-` dev, `ps-` stage, `pp-` prod. */
const PREFIX: Readonly<Record<Env, string>> = { dev: 'pd', stage: 'ps', prod: 'pp' };

/** One section of the form as the template draws it. */
interface SectionView {
  readonly name: string;
  readonly id: string;
  readonly titleId: string;
  readonly title: string;
  readonly description: Hint;
  readonly connections: readonly string[];
  /** A connector section without a connection of its kind here: the kit's empty card and a read-only summary. */
  readonly empty: EmptySection | null;
  readonly summary: readonly SummaryRow[];
  readonly approval: LockRow | null;
  readonly locks: readonly { key: string; text: string }[];
  /** Keys beyond the kit's layout, behind "More … settings". */
  readonly extras: readonly string[];
  /** Keys of a section the kit doesn't lay out (Logs, a plugin's), in schema order. */
  readonly generic: readonly string[];
}

/**
 * One environment's tab panel of Policies (design/pages/policies.html `#dev`, `#stage`, `#prod`): the toolbar
 * (what it applies to, where its values come from, View activity, Reset), then one `gw-form` with the sections SQL,
 * SSH, Kubernetes, Cloud, HTTP egress, Logs, Masking, Limits and Approvals, each at `#<env>-<section>`.
 *
 * The form is a Signal Form over every editable key of the core's schemas. An empty number or select follows the
 * value shown in it (its parent's, or the environment's default); Save sends only what changes, `null` for a key
 * that goes back to following. While something is unsaved the kit's sticky panel says what ("approval timeout on
 * prod, 10 to 15 min") with Discard and Save. Allowing `ssh_exec` on prod asks first (`gw-dialog--danger`, Keep it
 * off has the focus); Reset asks in a `gw-dialog--danger` that lists what goes back; a row changed outside MCP
 * Gateway is reviewed and kept in a `gw-dialog--warning`.
 */
@Component({
  selector: 'div[gwPolicyPanel]',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    FormField,
    GwIcon,
    GwDialog,
    GwDialogClose,
    GwHintParts,
    GwPolicyNumber,
    GwPolicyCheckbox,
    GwPolicySelect,
    GwPolicyChecks,
    GwPolicyTags,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-tabs__panel',
    role: 'tabpanel',
    tabindex: '0',
    '[id]': 'env()',
    '[attr.aria-labelledby]': '"pol-tab-" + env()',
    '[hidden]': '!selected()',
  },
  templateUrl: './policy-panel.html',
})
export class GwPolicyPanel {
  private readonly data = inject(PoliciesData);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  protected readonly time = inject(TimeService);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  readonly env = input.required<Env>();
  readonly selected = input(false);
  readonly policies = input.required<PoliciesByEnv>();
  readonly schemas = input.required<PolicySchemas>();
  readonly connections = input<readonly ConnectionRow[] | null>(null);
  readonly endpoints = input<readonly EndpointSummary[] | null>(null);
  readonly runbooks = input<readonly RunbookView[] | null>(null);
  readonly usage = input<Usage | null>(null);
  readonly notifications = input<Notifications | null>(null);

  private readonly resetDialog = viewChild.required<GwDialog>('resetDialog');
  private readonly execDialog = viewChild<GwDialog>('execDialog');
  private readonly keepDialog = viewChild.required<GwDialog>('keepDialog');

  protected readonly pre = computed(() => PREFIX[this.env()]);
  protected readonly policy = computed<EnvPolicy>(() => this.policies()[this.env()]);
  protected readonly busy = computed(() => this.data.busy().has(this.env()));

  // ── The form ──────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly model = signal<Draft>({});
  /**
   * The Signal Form over the model. Its value type is kept opaque (`unknown` per key): a field tree over the
   * recursive `JsonValue` would make the compiler instantiate types without end; each field is typed where it binds.
   */
  protected readonly form = form(this.model as unknown as WritableSignal<Record<string, unknown>>, (root) =>
    validateTree(root, (ctx) => this.validate(ctx.value() as Draft, ctx.fieldTree as unknown as Record<string, FieldTree<unknown>>)),
  );
  /** Errors show once the person tried to save (DESIGN §8 "Forms"). */
  protected readonly tried = signal(false);
  /** What the core refused, per key, until that key changes. */
  private readonly serverErrors = signal<ReadonlyMap<string, { readonly message: string; readonly value: DraftValue | undefined }>>(new Map());
  protected readonly saveError = signal<string | null>(null);
  /** The person confirmed `ssh_exec` on prod in the dialog: Save sends `confirm: "danger"`. */
  private readonly execConfirmed = signal(false);

  private readonly infos = computed(() => {
    const out = new Map<string, KeyInfo>();
    const schemas = this.schemas();
    for (const id of editableKeys(schemas)) {
      const [s, k] = splitKeyId(id);
      const info = keyInfo(schemas, s, k);
      if (info) out.set(id, info);
    }
    return out;
  });
  private readonly displayFn = computed(() => {
    const infos = this.infos();
    return (s: string, k: string) => infos.get(keyId(s, k))?.display ?? { unit: null, scale: 1 };
  });

  protected readonly changeList = computed(() => changes(this.policies(), this.schemas(), this.env(), this.model(), this.displayFn()));
  protected readonly dirty = computed(() => this.changeList().length > 0);
  protected readonly note = computed(() => unsavedNote(this.schemas(), this.env(), this.changeList()));

  constructor() {
    // Follow the core while nothing is unsaved; never overwrite what the person is typing.
    effect(() => {
      const policies = this.policies();
      const schemas = this.schemas();
      const env = this.env();
      untracked(() => {
        if (!this.dirty() || !Object.keys(this.model()).length) this.resetForm(policies, schemas, env);
      });
    });
  }

  /**
   * Puts the form back to the stored policy. After a Save, Reset or Keep the input still holds the policy from before
   * until the page renders again; the data already has the core's answer, so that is what the form starts from.
   */
  private resetForm(policies = this.data.policies() ?? this.policies(), schemas = this.schemas(), env = this.env()): void {
    const infos = this.infos();
    this.model.set(initialForm(policies, schemas, env, (s, k) => infos.get(keyId(s, k))?.display ?? { unit: null, scale: 1 }));
    this.form().reset();
    this.tried.set(false);
    this.serverErrors.set(new Map());
    this.saveError.set(null);
    this.execConfirmed.set(false);
  }

  /** The rules the core would refuse, at the field: numbers in range, whole where the schema says integer. */
  private validate(value: Draft, root: Record<string, FieldTree<unknown>>): TreeValidationResult {
    const errors: { kind: string; message: string; fieldTree: FieldTree<unknown> }[] = [];
    for (const [id, info] of this.infos()) {
      if (info.kind !== 'number') continue;
      const text = value[id];
      if (typeof text !== 'string' || !text.trim()) continue;
      const message = numberError(info, text);
      if (message) errors.push({ kind: 'range', message, fieldTree: root[id] });
    }
    return errors;
  }

  // ── Fields ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The field state of one key, as each field component binds it. */
  protected field<T>(id: string): FieldTree<T> {
    return (this.form as unknown as Record<string, FieldTree<T>>)[id];
  }

  /** A number or a select: its text. */
  protected textField(id: string): FieldTree<string> {
    return this.field<string>(id);
  }

  protected boolField(id: string): FieldTree<boolean> {
    return this.field<boolean>(id);
  }

  protected listField(id: string): FieldTree<JsonValue[]> {
    return this.field<JsonValue[]>(id);
  }

  protected readonly views = computed(() => {
    const out = new Map<string, FieldView & { info: KeyInfo }>();
    const policies = this.policies();
    const schemas = this.schemas();
    const env = this.env();
    const model = this.model();
    const changed = new Set(this.changeList().map((ch) => keyId(ch.section, ch.key)));
    const conns = this.connections() ?? [];
    const tried = this.tried();
    const server = this.serverErrors();
    for (const [id, info] of this.infos()) {
      const [s] = splitKeyId(id);
      const fieldErrors = tried ? (this.field<unknown>(id)?.().errors() ?? []) : [];
      const fromServer = server.get(id);
      const serverMessage = fromServer && sameDraft(fromServer.value, model[id]) ? fromServer.message : null;
      out.set(id, {
        info,
        id: `${this.pre()}-${id.replace('.', '-')}`,
        label: info.spec.label,
        labelCode: info.spec.labelCode,
        optional: info.spec.optional,
        hint: hintFor({
          policies,
          schemas,
          env,
          info,
          value: changed.has(id) ? this.changeList().find((ch) => keyId(ch.section, ch.key) === id)!.after : effectiveValue(policies[env], info.section, info.key),
          dirty: changed.has(id),
          connections: sectionConnections(s, env, conns).map((r) => r.name),
        }),
        error: serverMessage ?? fieldErrors[0]?.message ?? null,
      });
    }
    return out;
  });

  protected view(id: string): FieldView {
    return this.views().get(id) ?? { id, label: id, hint: [], error: null };
  }

  protected info(id: string): KeyInfo | null {
    return this.infos().get(id) ?? null;
  }

  protected schemaOf(id: string): KeySchema {
    return this.infos().get(id)?.ks ?? {};
  }

  protected has(id: string): boolean {
    return this.infos().has(id) && id in this.model();
  }

  protected unit(id: string): string | null {
    return this.infos().get(id)?.display.unit ?? null;
  }

  protected placeholder(id: string): string {
    const info = this.infos().get(id);
    return info ? placeholderFor(this.policies(), this.schemas(), this.env(), info) : '';
  }

  protected options(id: string): readonly OptionView[] {
    const info = this.infos().get(id);
    return info ? optionsFor(info, this.env()) : [];
  }

  protected follow(id: string): string | null {
    const info = this.infos().get(id);
    return info ? followOption(this.policies(), this.schemas(), this.env(), info) : null;
  }

  protected emptyText(id: string): Hint {
    const info = this.infos().get(id);
    const v = this.model()[id];
    return info?.spec.tags?.empty?.({ env: this.env(), value: v as JsonValue, connections: [] }) ?? [];
  }

  protected bool(id: string): boolean {
    return this.model()[id] === true;
  }

  protected listValue(id: string): JsonValue[] {
    const v = this.model()[id];
    return Array.isArray(v) ? v : [];
  }

  // ── Sections ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** The environments that inherit this one and deny with it while it doesn't verify. */
  protected readonly inheritorNames = computed(() => inheritors(this.policies(), this.env()));

  protected readonly applies = computed(() => envConnections(this.env(), this.connections() ?? []).map((r) => r.name));
  protected readonly valuesText = computed(() => valuesSummary(this.policies(), this.schemas(), this.env()));

  protected readonly sections = computed<SectionView[]>(() => {
    const policies = this.policies();
    const schemas = this.schemas();
    const env = this.env();
    const all = this.connections();
    const names = sectionOrder(Object.keys(schemas.sections));
    return names.map((name) => {
      const conns = all ? sectionConnections(name, env, all) : [];
      const ctx: SectionCtx = { policies, schemas, env, section: name, connections: conns, all: all ?? [] };
      const connector = !['masking', 'limits', 'approvals'].includes(name);
      // Without the connection list the page can't tell: it keeps the section editable rather than hide rules.
      const empty = connector && all !== null && !conns.length ? emptySection(ctx) : null;
      const known = name in CONNECTOR_SECTIONS && name !== 'logs';
      return {
        name,
        id: `${env}-${name}`,
        titleId: `${PREFIX[env]}-${name}-t`,
        title: schemas.sections[name]?.title ?? name,
        description: sectionDescription(ctx),
        connections: conns.map((r) => r.name),
        empty,
        summary: empty ? summaryRows(ctx) : [],
        approval: approvalRow(ctx),
        locks: known || ['masking', 'limits', 'approvals'].includes(name) ? [] : lockRows(schemas, name),
        extras: known || ['masking', 'limits', 'approvals'].includes(name) ? extraKeys(schemas, name) : [],
        generic: known || ['masking', 'limits', 'approvals'].includes(name) ? [] : extraKeys(schemas, name),
      };
    });
  });

  /** "More SQL settings": which sections show their extra keys. */
  protected readonly moreOpen = signal<ReadonlySet<string>>(new Set());

  protected toggleMore(section: string): void {
    this.moreOpen.update((s) => {
      const next = new Set(s);
      if (next.has(section)) next.delete(section);
      else next.add(section);
      return next;
    });
  }

  protected moreLabel(s: SectionView): string {
    return `More ${s.name === 'sql' || s.name === 'ssh' || s.name === 'http' ? s.title.split(' ')[0] : s.title} settings`;
  }

  /** Numbers of a list of keys go side by side in one `gw-form__row`; the rest one per row. */
  protected numbersOf(keys: readonly string[], section: string): string[] {
    return keys.map((k) => keyId(section, k)).filter((id) => this.infos().get(id)?.kind === 'number');
  }

  protected othersOf(keys: readonly string[], section: string): string[] {
    return keys.map((k) => keyId(section, k)).filter((id) => this.infos().has(id) && this.infos().get(id)!.kind !== 'number');
  }

  // ── Runbooks, meters, risk, channels ──────────────────────────────────────────────────────────────────────────

  protected readonly envRunbooks = computed(() => {
    const conns = new Set(sectionConnections('ssh', this.env(), this.connections() ?? []).map((r) => r.name));
    return (this.runbooks() ?? []).filter((r) => conns.has(r.connection));
  });

  protected readonly sshConnections = computed(() => sectionConnections('ssh', this.env(), this.connections() ?? []).map((r) => r.name));

  protected readonly tokenMeter = computed<MeterView | null>(() => {
    const u = this.usage();
    return u ? meter(effectiveValue(this.policy(), 'limits', 'rateLimitPerToken'), u[this.env()].token) : null;
  });

  protected readonly toolMeter = computed<MeterView | null>(() => {
    const u = this.usage();
    return u ? meter(effectiveValue(this.policy(), 'limits', 'rateLimitPerTool'), u[this.env()].tool) : null;
  });

  /** Endpoints that can send data to these hosts through the HTTP allowlist (the compact risk callout). */
  protected readonly risk = computed(() => {
    const hosts = allowlistHosts(effectiveValue(this.policy(), 'http', 'allowedBaseUrls'));
    if (!hosts.length) return null;
    const endpoints = riskyEndpoints(this.env(), this.connections() ?? [], this.endpoints() ?? []);
    return endpoints.length ? { hosts, endpoints } : null;
  });

  /** Channels that aren't set up (K2): their checkbox gives way to "Telegram is not set up." unless already chosen. */
  protected readonly channelsNotSetUp = computed(() => {
    const n = this.notifications();
    if (!n) return [];
    return n.channels.filter((ch) => ch.kind !== 'desktop' && !ch.enabled).map((ch) => ch.kind);
  });

  protected channelName(kind: string): string {
    return kind === 'slack' ? 'Slack' : kind === 'telegram' ? 'Telegram' : kind;
  }

  // ── Save, Discard ─────────────────────────────────────────────────────────────────────────────────────────────

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy() || !this.dirty()) return;
    this.tried.set(true);
    this.saveError.set(null);
    const env = this.env();
    await submit(this.form, {
      action: async () => {
        const list = this.changeList();
        const policies = this.policies();
        const p = this.policy();
        const execOn = list.some((ch) => ch.section === 'ssh' && ch.key === 'allowExec' && ch.after === true);
        try {
          await this.data.save(env, patch(list), p.version, env === 'prod' && execOn && this.execConfirmed() ? 'danger' : null);
        } catch (err) {
          this.onSaveError(err);
          return undefined;
        }
        this.toast(savedToast(policies, this.schemas(), env, list));
        this.resetForm();
        this.focusAfterRender(() => this.host);
        return undefined;
      },
      onInvalid: () => this.focusFirstInvalid(),
    });
  }

  /** The core's refusal at the field it names (`/sections/<section>/<key>`), else above Save. */
  private onSaveError(err: unknown): void {
    const field = isApiError(err) ? err.field : null;
    const m = field?.match(/^\/sections\/([^/]+)\/([^/]+)/);
    const id = m ? keyId(m[1], m[2]) : null;
    const message = isApiError(err) ? err.detail : NO_ANSWER;
    if (isApiError(err) && err.kind === 'conflict' && err.confirm === 'danger' && this.env() === 'prod') {
      // The core asks for the danger confirmation the dialog gives: ask it now.
      this.execConfirmed.set(false);
      this.saveError.set(message);
      return;
    }
    if (id && this.infos().has(id)) {
      this.serverErrors.update((map) => new Map(map).set(id, { message, value: this.model()[id] }));
      const [section] = splitKeyId(id);
      if (this.extrasHave(section, id)) this.moreOpen.update((s) => new Set([...s, section]));
      this.focusFirstInvalid();
      return;
    }
    this.saveError.set(isApiError(err) && err.kind === 'conflict' && !err.confirm ? `${message} Discard your changes to see the current values, then make them again.` : message);
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>('.gw-form__actions [role="alert"]'));
  }

  private extrasHave(section: string, id: string): boolean {
    return this.sections().some((s) => s.name === section && s.extras.some((k) => keyId(section, k) === id));
  }

  protected discard(e: Event): void {
    // A native reset would put every input back to its markup default, not to the stored policy.
    e.preventDefault();
    this.resetForm();
    this.announcer.announce(`Changes to the ${this.env()} policy discarded`);
    this.focusAfterRender(() => this.host);
  }

  // ── ssh_exec on prod ──────────────────────────────────────────────────────────────────────────────────────────

  /** Execution needs a person's yes on prod (`x-confirm: danger`): the danger dialog first. */
  protected readonly execNeedsConfirm = computed(() => this.policy().locks.some((l) => l.kind === 'confirm-danger' && l.section === 'ssh' && l.key === 'allowExec'));
  private execBox: HTMLInputElement | null = null;

  protected askExec(box: HTMLInputElement): void {
    this.execBox = box;
    this.execDialog()?.open(box);
  }

  protected onExecClosed(value: string): void {
    if (value === 'allow') {
      this.field<boolean>('ssh.allowExec')().value.set(true);
      this.field<boolean>('ssh.allowExec')().markAsDirty();
      this.execConfirmed.set(true);
      this.announcer.announce(`ssh_exec allowed on ${this.env()} in the form. Save the ${this.env()} policy to apply it.`);
    }
    this.execBox?.focus();
  }

  protected readonly execText = computed(() => {
    const hosts = this.sshConnections();
    const runbooks = this.envRunbooks().length;
    return {
      title: `Allow any shell command on ${hosts.length ? hosts.join(', ') : this.env()}?`,
      hosts,
      runbooks: runbooks ? `not only its ${runbooks === 1 ? 'runbook' : `${runbooks === 2 ? 'two' : formatNumber(runbooks)} runbooks`}` : 'not only runbooks',
    };
  });

  // ── Reset ─────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly canReset = computed(() => explicitKeys(this.policy()).length > 0 || !this.policy().verified);
  protected readonly reset = computed(() => resetText(this.policies(), this.schemas(), this.env(), this.changeList()));
  protected readonly resetError = signal<string | null>(null);

  protected askReset(e: Event): void {
    this.resetError.set(null);
    this.resetDialog().open(e.currentTarget as HTMLElement);
  }

  protected async confirmReset(): Promise<void> {
    if (this.busy()) return;
    const text = this.reset();
    this.resetError.set(null);
    try {
      await this.data.reset(this.env());
    } catch (err) {
      this.resetError.set(isApiError(err) ? err.detail : NO_ANSWER);
      return;
    }
    this.resetDialog().close('reset');
    this.resetForm();
    this.toast(text.toast);
  }

  protected onResetClosed(value: string): void {
    // The trigger is gone once nothing is set: the keyboard goes to the panel.
    if (value === 'reset') this.focusAfterRender(() => this.host);
  }

  // ── Changed outside MCP Gateway ───────────────────────────────────────────────────────────────────────────────

  protected readonly unverifiedParent = computed(() => {
    const p = this.policy();
    if (!p.verified || p.serving) return null;
    return p.inherits;
  });
  protected readonly stored = computed(() => storedPhrases(this.schemas(), this.policy()));
  protected readonly keepError = signal<string | null>(null);
  protected readonly keepConfirm = signal<string | null>(null);

  protected askKeep(e: Event): void {
    this.keepError.set(null);
    this.keepConfirm.set(null);
    this.keepDialog().open(e.currentTarget as HTMLElement);
  }

  protected async confirmKeep(): Promise<void> {
    if (this.busy()) return;
    this.keepError.set(null);
    try {
      await this.data.keep(this.env(), this.keepConfirm());
    } catch (err) {
      // 409 `confirm: danger`: the stored row allows ssh_exec on prod; keeping it needs the same yes as a change.
      if (isApiError(err) && err.confirm && !this.keepConfirm()) this.keepConfirm.set(err.confirm);
      this.keepError.set(isApiError(err) ? err.detail : NO_ANSWER);
      return;
    }
    this.keepDialog().close('kept');
    this.resetForm();
    this.toast({ tone: 'success', title: `${this.env()} policy kept as stored`, body: `Calls on ${this.env()} are decided by it again.` });
  }

  protected onKeepClosed(value: string): void {
    if (value === 'kept') this.focusAfterRender(() => this.host);
    this.keepConfirm.set(null);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected activityQuery(q: string): Record<string, string> {
    return { q };
  }

  private toast(t: { tone: 'success' | 'neutral' | 'warning' | 'danger'; title: string; body: string }): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
  }

  private focusFirstInvalid(): void {
    // A key behind "More … settings" with an error: open its section first.
    const open = new Set(this.moreOpen());
    for (const s of this.sections()) {
      if (s.extras.some((k) => this.views().get(keyId(s.name, k))?.error)) open.add(s.name);
    }
    this.moreOpen.set(open);
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>('[aria-invalid="true"]'));
  }

  private focusAfterRender(target: () => HTMLElement | null | undefined): void {
    afterNextRender(() => target()?.focus(), { injector: this.injector });
  }
}

function sameDraft(a: DraftValue | undefined, b: DraftValue | undefined): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}
