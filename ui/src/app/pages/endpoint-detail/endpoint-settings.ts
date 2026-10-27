import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  WritableSignal,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { FormField, form, maxLength, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { EndpointSettings, EndpointSettingsUpdate } from '../../core/api/endpoints.api';
import { Env, JsonValue } from '../../core/api/types';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwSaveBar, GwSettingsLayout, GwSettingsSection, SettingsNavGroup } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { EndpointDetailData } from './endpoint-detail.data';
import {
  EndpointLimit,
  KNOWN_REVISIONS,
  activeTokens,
  busiestMeter,
  clientsOfRevision,
  effectiveLimits,
  exampleToken,
  instructionsCount,
  limitHints,
  limitText,
  parseLimit,
  plural,
  policyEnvs,
  primaryEnv,
  registrationText,
  resourceMetadataUrl,
  revisionsByClient,
  servedRevisions,
} from './endpoint-detail-view';

/** The settings nav of an endpoint (kit `#settings` panel): its sections' ids follow `ep-st-`. */
export const ENDPOINT_SETTINGS_GROUPS: readonly SettingsNavGroup[] = [
  {
    label: 'Behaviour',
    items: [
      { id: 'instructions', label: 'Instructions', icon: 'message-square-text' },
      { id: 'limits', label: 'Limits', icon: 'gauge' },
    ],
  },
  {
    label: 'Access',
    items: [
      { id: 'auth', label: 'Authorization', icon: 'shield-check' },
      { id: 'protocol', label: 'Protocol', icon: 'plug' },
    ],
  },
];

interface LimitsModel {
  size: string;
  perToken: string;
  perTool: string;
}

interface ProtocolModel {
  revisions: { rev: string; on: boolean }[];
}

/** Which field of the limits form holds which key of `settings.limits`. */
const LIMIT_FIELDS: readonly { readonly field: keyof LimitsModel; readonly key: EndpointLimit }[] = [
  { field: 'size', key: 'maxResponseKb' },
  { field: 'perToken', key: 'rateLimitPerToken' },
  { field: 'perTool', key: 'rateLimitPerTool' },
];

function limitsOf(settings: EndpointSettings | null): LimitsModel {
  const own = settings?.limits;
  return { size: limitText(own, 'maxResponseKb'), perToken: limitText(own, 'rateLimitPerToken'), perTool: limitText(own, 'rateLimitPerTool') };
}

/** The known revisions (and any other the core serves), each checked when the endpoint answers it. */
function protocolOf(settings: EndpointSettings | null): ProtocolModel {
  const served = servedRevisions(settings);
  const known = KNOWN_REVISIONS.map((r) => r.rev);
  const all = [...known, ...served.filter((r) => !known.includes(r)).sort().reverse()];
  return { revisions: all.map((rev) => ({ rev, on: served.includes(rev) })) };
}

const sameLimits = (a: LimitsModel, b: LimitsModel) => a.size === b.size && a.perToken === b.perToken && a.perTool === b.perTool;
const sameProtocol = (a: ProtocolModel, b: ProtocolModel) =>
  a.revisions.length === b.revisions.length && a.revisions.every((r, i) => r.rev === b.revisions[i].rev && r.on === b.revisions[i].on);

/**
 * A form's text that follows the core's value until the person edits it: a reload (an event, another tab's save)
 * replaces what is shown only while nothing is being changed, so typing is never overwritten.
 */
function followSaved<T>(saved: () => T, same: (a: T, b: T) => boolean) {
  return linkedSignal<T, T>({
    source: saved,
    computation: (now, previous) => (previous && !same(previous.value, previous.source) ? previous.value : now),
    equal: same,
  });
}

/**
 * Endpoint detail › Settings (design/pages/endpoint-detail.html `#settings`): what the endpoint tells agents, how hard
 * it may be called, how clients sign in and which MCP revisions it answers. A settings nav on the left
 * (`ep-st-instructions`, `ep-st-limits`, `ep-st-auth`, `ep-st-protocol`; the fragment also selects the tab).
 *
 * Instructions, Limits and Protocol are forms with a Save bar that shows while something changed; the OAuth switch
 * applies at once and confirms with a toast. A reload while a form is being edited never overwrites it.
 */
@Component({
  selector: 'div[gwEndpointSettings]',
  imports: [GwSettingsLayout, GwSettingsSection, GwSaveBar, FormField, GwIcon, RouterLink, GwCopy],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './endpoint-settings.html',
})
export class GwEndpointSettings {
  protected readonly data = inject(EndpointDetailData);
  private readonly store = inject(EndpointsStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  protected readonly groups = ENDPOINT_SETTINGS_GROUPS;
  protected readonly current = signal<string | null>('instructions');

  protected readonly slug = computed(() => this.data.slug() ?? '');
  protected readonly settings = computed(() => this.data.settings());
  protected readonly url = computed(() => this.data.summary()?.url ?? '');
  protected readonly metadataUrl = computed(() => resourceMetadataUrl(this.url()));
  protected readonly registration = computed(() => registrationText(this.data.authorization()?.['clientRegistration']));

  // ── Instructions ──────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly max = computed(() => this.settings()?.instructionsMax ?? 4000);
  private readonly savedInstructions = computed(() => this.settings()?.instructions ?? '');
  /** The text the gateway writes while the endpoint has none of its own (step API); null from an older core. */
  protected readonly defaultText = computed(() => this.settings()?.defaultInstructions ?? null);
  /**
   * What the field holds before an edit: the endpoint's own text, else — as the kit shows it — the one the gateway
   * writes, to edit from. Saving that text unchanged keeps the gateway writing it (it follows the tools).
   */
  private readonly baselineInstructions = computed(() => this.savedInstructions() || (this.defaultText() ?? ''));
  /** The field shows the gateway's own text (nothing of the endpoint's is saved). */
  protected readonly showsDefault = computed(() => !this.savedInstructions() && !!this.defaultText());
  protected readonly instructionsModel = followSaved(() => ({ text: this.baselineInstructions() }), (a, b) => a.text === b.text);
  protected readonly instructionsForm = form(
    this.instructionsModel,
    (p) => {
      // Also the textarea's maxlength (Signal Forms sets it): a longer text only arrives pasted or stored.
      maxLength(p.text, () => this.max(), { message: () => `Shorten it to ${this.max().toLocaleString('en-US')} characters.` });
    },
    { injector: this.injector },
  );
  protected readonly instructionsDirty = computed(() => this.instructionsModel().text !== this.baselineInstructions());
  protected readonly instructionsCounter = computed(() => instructionsCount(this.instructionsModel().text.length, this.max()));
  /**
   * The generated text agents get while the endpoint has none of its own: its length from `POST …/check`, for a core
   * that doesn't send the text itself.
   */
  protected readonly defaultChars = computed(() =>
    this.savedInstructions() || this.defaultText() ? null : (this.data.check()?.instructionsChars ?? null),
  );
  protected readonly instructionsBusy = signal(false);
  protected readonly instructionsError = signal<string | null>(null);

  // ── Limits ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly env = computed<Env>(() => primaryEnv(this.data.detail()?.connections ?? [], this.data.summary()?.env ?? 'dev'));
  /** The other environments of the endpoint's connections ("tools on stage connections follow the stage policy"). */
  protected readonly otherEnvs = computed(() => policyEnvs(this.data.detail()?.connections ?? [], this.env()).filter((e) => e !== this.env()));
  private readonly policySections = computed(() => this.data.policies()?.[this.env()]?.sections ?? null);
  protected readonly policyLimits = computed(() => effectiveLimits(this.policySections(), null));
  protected readonly effective = computed(() => effectiveLimits(this.policySections(), this.settings()?.limits));
  protected readonly hints = computed(() => limitHints(this.policyLimits(), this.env(), this.slug(), exampleToken(this.data.tokens())));
  protected readonly meter = computed(() => {
    const s = this.settings();
    return busiestMeter(this.data.policies()?.usage?.busiestToken ?? null, this.data.tokens(), this.effective().perToken, s?.usage.callsLastMinute ?? 0, this.slug());
  });

  private readonly savedLimits = computed(() => limitsOf(this.settings()), { equal: sameLimits });
  protected readonly limitsModel = followSaved(() => this.savedLimits(), sameLimits);
  protected readonly limitsForm = form(
    this.limitsModel,
    (p) => {
      for (const f of LIMIT_FIELDS) {
        validate(p[f.field], ({ value }) => {
          const error = parseLimit(value()).error;
          return error ? { kind: 'limit', message: error } : null;
        });
      }
    },
    { injector: this.injector },
  );
  protected readonly limitsDirty = computed(() => !sameLimits(this.limitsModel(), this.savedLimits()));
  protected readonly limitsBusy = signal(false);
  protected readonly limitsError = signal<string | null>(null);
  protected readonly limitsSubmitted = signal(false);

  // ── Authorization ─────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly activeTokenCount = computed(() => {
    const t = this.data.tokens();
    return t ? activeTokens(t) : (this.data.summary()?.tokens.active ?? 0);
  });
  protected readonly oauthBusy = signal(false);
  /** An endpoint published through the tunnel, for "like stage-readonly" (claude.ai signs in only there). */
  protected readonly publishedExample = computed(() => (this.store.list() ?? []).find((e) => e.published && e.slug !== this.slug())?.slug ?? null);
  protected readonly published = computed(() => this.data.summary()?.published ?? false);

  // ── Protocol ──────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly revisionHints = new Map(KNOWN_REVISIONS.map((r) => [r.rev, r.hint]));
  private readonly savedProtocol = computed(() => protocolOf(this.settings()), { equal: sameProtocol });
  protected readonly protocolModel = followSaved(() => this.savedProtocol(), sameProtocol);
  protected readonly protocolForm = form(
    this.protocolModel,
    (p) => {
      validate(p.revisions, ({ value }) =>
        value().some((r) => r.on) ? null : { kind: 'none', message: `Keep at least one revision: with none, no AI app can call ${this.slug()}.` },
      );
    },
    { injector: this.injector },
  );
  protected readonly protocolDirty = computed(() => !sameProtocol(this.protocolModel(), this.savedProtocol()));
  protected readonly protocolBusy = signal(false);
  protected readonly protocolError = signal<string | null>(null);
  protected readonly protocolSubmitted = signal(false);
  protected readonly seen = computed(() => revisionsByClient(this.settings()?.revisions));
  /** Turned off, but used by clients lately: they stop working when this is saved. */
  protected readonly breaking = computed(() =>
    this.protocolModel()
      .revisions.filter((r) => !r.on)
      .map((r) => ({ rev: r.rev, clients: clientsOfRevision(this.settings()?.revisions, r.rev) }))
      .filter((r) => r.clients.length),
  );
  protected readonly newest = KNOWN_REVISIONS[0].rev;

  protected readonly plural = plural;

  // ── Saving ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected async saveInstructions(e: Event): Promise<void> {
    e.preventDefault();
    if (this.instructionsBusy() || !this.instructionsDirty()) return;
    if (this.instructionsForm().invalid()) {
      this.instructionsForm.text().markAsTouched();
      return;
    }
    const text = this.instructionsModel().text;
    // Empty, or the gateway's own text, is "GateRook writes them": the core keeps null, not an empty string agents
    // would get or a copy that stops following the tools.
    const own = text.trim() && text !== this.defaultText() ? text : null;
    await this.save({ instructions: own }, 'Server instructions', this.instructionsBusy, this.instructionsError);
  }

  protected discardInstructions(e: Event): void {
    // The form's own reset would put the textarea back to its markup (empty); the saved text comes from the model.
    e.preventDefault();
    this.instructionsModel.set({ text: this.baselineInstructions() });
    this.instructionsForm().reset();
    this.instructionsError.set(null);
  }

  protected async saveLimits(e: Event): Promise<void> {
    e.preventDefault();
    this.limitsSubmitted.set(true);
    if (this.limitsBusy() || !this.limitsDirty()) return;
    if (this.limitsForm().invalid()) {
      this.limitsForm().markAsTouched();
      return;
    }
    // PUT replaces the endpoint's whole `limits`: keep its other keys, set or drop the three of this form.
    const limits: Record<string, JsonValue> = { ...(this.settings()?.limits ?? {}) };
    for (const f of LIMIT_FIELDS) {
      const v = parseLimit(this.limitsModel()[f.field]).value;
      if (v === null) delete limits[f.key];
      else limits[f.key] = v;
    }
    await this.save({ limits }, 'Response size and rate limits', this.limitsBusy, this.limitsError);
    if (!this.limitsError()) this.limitsSubmitted.set(false);
  }

  protected discardLimits(e: Event): void {
    e.preventDefault();
    this.limitsModel.set(this.savedLimits());
    this.limitsForm().reset();
    this.limitsSubmitted.set(false);
    this.limitsError.set(null);
  }

  protected limitError(field: keyof LimitsModel): string | null {
    const state = this.limitsForm[field]();
    return state.touched() || this.limitsSubmitted() ? (state.errors()[0]?.message ?? null) : null;
  }

  protected async saveProtocol(e: Event): Promise<void> {
    e.preventDefault();
    this.protocolSubmitted.set(true);
    if (this.protocolBusy() || !this.protocolDirty()) return;
    if (this.protocolForm().invalid()) return;
    const protocol = { ...(this.settings()?.protocol ?? {}), revisions: this.protocolModel().revisions.filter((r) => r.on).map((r) => r.rev) };
    await this.save({ protocol }, 'Supported revisions', this.protocolBusy, this.protocolError);
    if (!this.protocolError()) this.protocolSubmitted.set(false);
  }

  protected discardProtocol(e: Event): void {
    e.preventDefault();
    this.protocolModel.set(this.savedProtocol());
    this.protocolForm().reset();
    this.protocolSubmitted.set(false);
    this.protocolError.set(null);
  }

  protected protocolErrorText(): string | null {
    return this.protocolSubmitted() ? (this.protocolForm.revisions().errors()[0]?.message ?? null) : null;
  }

  /** OAuth 2.1 applies at once (kit: a switch outside a Save form confirms with a toast). */
  protected async setOauth(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const on = input.checked;
    if (this.oauthBusy()) {
      input.checked = !on;
      return;
    }
    this.oauthBusy.set(true);
    try {
      await this.data.updateSettings({ oauthEnabled: on });
      this.toasts.show({ tone: 'success', title: 'Saved', body: `OAuth 2.1 sign-in for ${this.slug()}: ${on ? 'on' : 'off'}.` });
    } catch (err) {
      // The switch shows what the core keeps: the binding didn't change, so put the box back by hand.
      input.checked = !on;
      this.toasts.show({ tone: 'danger', title: `OAuth 2.1 wasn't turned ${on ? 'on' : 'off'}`, body: this.errorText(err) });
    } finally {
      this.oauthBusy.set(false);
    }
  }

  private async save(
    update: Omit<EndpointSettingsUpdate, 'version'>,
    what: string,
    busy: WritableSignal<boolean>,
    error: WritableSignal<string | null>,
  ): Promise<void> {
    busy.set(true);
    error.set(null);
    try {
      await this.data.updateSettings(update);
      this.toasts.show({ tone: 'success', title: `${what} saved` });
    } catch (e) {
      error.set(this.errorText(e));
      this.announcer.announce(`${what} weren't saved`);
      // A stale version: read the core's values (the form keeps what was typed, so it can be saved again).
      if (isApiError(e) && e.status === 409) void this.data.reloadDetail();
    } finally {
      busy.set(false);
    }
  }

  /**
   * A 409 is a stale version ("Changed meanwhile": read again, save again) or an endpoint changed outside GateRook,
   * which no save fixes until it is reviewed (Keep as stored): that one says the core's words (review 2026-10-26: both
   * read "changed meanwhile … save again").
   */
  private errorText(e: unknown): string {
    // Older cores used the former product name in this error title.
    if (isApiError(e) && e.status === 409 && !['Changed outside GateRook', 'Changed outside MCP Gateway'].includes(e.title)) {
      return 'These settings changed meanwhile, in another window. Check the values, then save again.';
    }
    return isApiError(e) ? e.detail : 'The core didn’t answer. Try again.';
  }
}
