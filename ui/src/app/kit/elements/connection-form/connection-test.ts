import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, booleanAttribute, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { GwCodeBlock } from '../../../code/code-block';
import { TestHint, TestOutcome } from '../../../core/api/connections.api';
import { JsonObject, JsonValue } from '../../../core/api/types';
import { DesktopBridge, desktopErrorText } from '../../../core/desktop/desktop-bridge';
import { TimeService } from '../../../core/time/time.service';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { TextPart, literalParts } from '../shared/literals';

/** Where a test connects and as whom: "Connecting to db.dev.internal:5432 as app_rw. Gives up after 5 s." */
export interface TestTarget {
  readonly address: string | null;
  readonly user: string | null;
  readonly timeoutMs: number | null;
}

export type TestState = 'idle' | 'testing' | 'done';

/** Only local Google ADC connections can use the desktop sign-in command. */
export function usesGoogleAdc(type: string | undefined, config: JsonObject | null): boolean {
  if (type !== 'gcp' && type !== 'gcp-logging' && !(type === 'logs' && config?.['backend'] === 'gcp-logging')) return false;
  const credentials = config?.['credentials'];
  return !!credentials && typeof credentials === 'object' && !Array.isArray(credentials) && credentials['source'] === 'adc';
}

const ERROR_TITLES: Record<string, (address: string | null) => string> = {
  unreachable: (a) => (a ? `Can't reach ${a}` : "Can't reach the server"),
  timeout: (a) => (a ? `Can't reach ${a}` : 'The server did not answer'),
  'connect-timeout': (a) => (a ? `Can't reach ${a}` : 'The server did not answer'),
  dns: (a) => (a ? `Can't find ${a.replace(/:\d+$/, '')}` : "Can't find the host"),
  auth: () => 'The server refused the login',
  'auth-failed': () => 'The server refused the login',
  tls: () => "The server's certificate was refused",
  'not-implemented': () => "This connection can't be tested yet",
  // A pinned SSH host key no longer matches (C4): never trusted from here, the person checks the host first.
  'ssh.host-key-changed': (a) => (a ? `The host key of ${a} changed` : 'The host key changed'),
  'ssh.host-key-revoked': (a) => (a ? `The host key of ${a} is revoked` : 'The host key is revoked'),
};

/** What a confirmation the test stopped for asks the person to check (needsConfirmation.kind). */
const CONFIRM_TITLES: Record<string, (host: string | null) => string> = {
  'host-key': (h) => (h ? `Check the host key of ${h}` : 'Check the host key'),
  'tls-cert': (h) => (h ? `Check the certificate of ${h}` : 'Check the certificate'),
  'exec-plugin': () => 'Run the credential command from the kubeconfig?',
};

/** "INSERT, UPDATE, DELETE on public.*": SQL keywords and `schema.*` patterns are literals the kit sets in mono. */
const SQL_LITERAL = /\b[A-Z][A-Z_]{1,}(?:,\s*[A-Z][A-Z_]{1,})*\b|[A-Za-z_][\w-]*\.\*/g;

/** Splits a permission sentence of the core into prose and the SQL literals in it (never changes the text). */
export function permissionParts(text: string): TextPart[] {
  const parts: TextPart[] = [];
  let last = 0;
  for (const m of text.matchAll(SQL_LITERAL)) {
    const at = m.index ?? 0;
    if (at > last) parts.push({ text: text.slice(last, at), code: false });
    parts.push({ text: m[0], code: true });
    last = at + m[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), code: false });
  return parts;
}

/** "This user can write. Fine for dev: …" → the first sentence as the alert's title, the rest as its body. */
export function hintParts(text: string): { readonly title: string | null; readonly body: string } {
  const m = /^(.+?[^.\s][.!?])\s+(\S[\s\S]*)$/.exec(text.trim());
  if (!m) return { title: null, body: text };
  return { title: m[1].replace(/\.$/, ''), body: m[2] };
}

/** The host a confirmation or a changed key is about ("10.20.0.14:22"), else `fallback` (where the test went). */
export function testHost(data: JsonValue | null | undefined, fallback: string | null): string | null {
  const d = data && typeof data === 'object' && !Array.isArray(data) ? (data as Record<string, JsonValue>) : null;
  const host = typeof d?.['host'] === 'string' ? (d['host'] as string) : null;
  const port = typeof d?.['port'] === 'number' ? d['port'] : null;
  if (host) return port ? `${host}:${port}` : host;
  return fallback;
}

/** What a failed test is called ("Can't reach db.dev.internal:5432"): the panel's alert and a wizard's stepper. */
export function testFailureTitle(outcome: TestOutcome | null, address: string | null): string {
  const code = outcome?.error?.code ?? '';
  const where = code.startsWith('ssh.host-key') ? testHost(outcome?.details, address) : address;
  return ERROR_TITLES[code]?.(where) ?? 'The test failed';
}

/** What a test that stopped for a confirmation asks ("Check the host key of 10.20.0.14:22"). */
export function testConfirmTitle(outcome: TestOutcome | null, address: string | null): string {
  const c = outcome?.needsConfirmation;
  if (!c) return '';
  return CONFIRM_TITLES[c.kind]?.(testHost(c.data, address)) ?? 'Check before the test goes on';
}

/**
 * Fingerprint, key type, subject: what a person compares before trusting a host key, a certificate or a credential
 * command (never the raw public key line). The edit view's trust dialog lists the same facts.
 */
export function testFacts(data: JsonValue | null | undefined): { readonly key: string; readonly label: string; readonly value: string }[] {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return [];
  const d = data as Record<string, JsonValue>;
  const out: { key: string; label: string; value: string }[] = [];
  const add = (key: string, title: string) => {
    const v = d[key];
    if (typeof v === 'string' && v) out.push({ key, label: title, value: v });
  };
  add('keyType', 'Key type');
  add('fingerprint', 'Fingerprint');
  add('subject', 'Subject');
  add('issuer', 'Issuer');
  add('sha256', 'SHA-256');
  add('command', 'Command');
  return out;
}

/** "schemaChanges" → "Schema changes". */
function label(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function severity(h: TestHint): string {
  return (h.severity ?? '').toLowerCase();
}

/**
 * `<section gwConnectionTest>` (connection-form "Test connection"): the panel that connects with the values above and
 * saves nothing. idle → "Not tested yet"; testing → the button keeps its label and gets a spinner (aria-busy +
 * aria-disabled, described by the status line); done → "Connected · server · latency" with the permissions it found
 * (a write grant flagged in words and with an icon) or what failed, the technical details in a code block behind a
 * disclosure. A test that stopped before credentials left (a new SSH host key, a certificate, a credential command)
 * shows what to check and a Trust action; a pinned key that changed is a failure with nothing to trust. The result
 * area is `role="status"`: the outcome is announced.
 *
 * Add connection's step 3 names it "Test result", describes it with where and as whom it connects (projected
 * `[gwTestDescription]`) and sends "Change user" back to the Configure step (`changeUserButton`).
 */
@Component({
  selector: 'section[gwConnectionTest]',
  imports: [NgTemplateOutlet, GwIcon, GwToggle, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-card--muted gw-connection-form__test', '[attr.aria-labelledby]': 'idPrefix() + "-test-t"' },
  template: `
    @let p = idPrefix();
    @let o = outcome();
    <header class="gw-card__header">
      <div class="gw-card__heading">
        <h3 class="gw-card__title" [id]="p + '-test-t'">{{ heading() }}</h3>
        <p class="gw-card__description"><ng-content select="[gwTestDescription]">Connects from this machine with the values above. Nothing is saved.</ng-content></p>
      </div>
      <div class="gw-card__actions">
        @if (state() === 'testing' || googleSigningIn()) {
          <button class="gw-btn" type="button" aria-busy="true" aria-disabled="true" [attr.aria-describedby]="p + (googleSigningIn() ? '-google-status' : '-test-status')">
            <span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>{{ o ? 'Test again' : 'Test connection' }}
          </button>
        } @else if (state() === 'done') {
          <button class="gw-btn" type="button" (click)="run.emit()"><svg lucideIcon="refresh-cw" class="gw-icon"></svg>Test again</button>
        } @else {
          <button class="gw-btn" type="button" (click)="run.emit()"><svg lucideIcon="play" class="gw-icon"></svg>Test connection</button>
        }
      </div>
    </header>
    <div class="gw-card__body gw-connection-form__result" role="status">
      @switch (state()) {
        @case ('testing') {
          <p class="gw-text-small gw-fg-muted" [id]="p + '-test-status'">
            @if (target().address) {Connecting to <span class="gw-mono">{{ target().address }}</span>} @else {Connecting from this machine}@if (target().user) { as <span class="gw-mono">{{ target().user }}</span>}.@if (timeout()) { Gives up after {{ timeout() }}.}
          </p>
        }
        @case ('done') {
          @if (o && o.ok) {
            <span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ connectedLine() }}</span></span>
            @if (o.identity || o.permissions) {
              <dl class="gw-kv gw-kv--dense" [attr.aria-label]="'Detected permissions of ' + (o.identity ?? 'this user')">
                @if (o.identity) {
                  <div class="gw-kv__row"><dt class="gw-kv__key">User</dt><dd class="gw-kv__value gw-mono">{{ o.identity }}</dd></div>
                }
                @if (o.permissions?.read) {
                  <div class="gw-kv__row"><dt class="gw-kv__key">Read</dt><dd class="gw-kv__value"><ng-container *ngTemplateOutlet="parts; context: { $implicit: permissionParts(o.permissions!.read!) }" /></dd></div>
                }
                @if (o.permissions?.write) {
                  <div class="gw-kv__row">
                    <dt class="gw-kv__key">Write</dt>
                    <dd class="gw-kv__value">@if (canWrite()) {<span class="gw-badge gw-badge--warning gw-badge--sm"><svg lucideIcon="triangle-alert" class="gw-icon gw-badge__icon"></svg>Can write</span> }<ng-container *ngTemplateOutlet="parts; context: { $implicit: permissionParts(o.permissions!.write!) }" /></dd>
                  </div>
                }
                @for (d of detailRows(); track d.key) {
                  <div class="gw-kv__row"><dt class="gw-kv__key">{{ d.label }}</dt><dd class="gw-kv__value"><ng-container *ngTemplateOutlet="parts; context: { $implicit: permissionParts(d.value) }" /></dd></div>
                }
                @if (o.permissions?.admin?.length) {
                  <div class="gw-kv__row"><dt class="gw-kv__key">Admin</dt><dd class="gw-kv__value gw-mono">{{ o.permissions!.admin.join(', ') }}</dd></div>
                }
              </dl>
            }
            @if (canWrite()) {
              <div class="gw-alert gw-alert--warning gw-alert--compact">
                <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content">
                  <p class="gw-alert__title">This user can write</p>
                  <p class="gw-alert__body">{{ writeAdvice() }}</p>
                </div>
                @if (changeUserButton()) {
                  <div class="gw-alert__actions">
                    <button class="gw-btn gw-btn--link" type="button" (click)="changeUser.emit()">Change user<span class="gw-sr-only"> {{ o.identity }}</span></button>
                  </div>
                } @else if (userFieldId()) {
                  <div class="gw-alert__actions">
                    <a class="gw-btn gw-btn--link" [attr.href]="'#' + userFieldId()" (click)="focusUser($event)">Change user<span class="gw-sr-only"> {{ o.identity }}</span></a>
                  </div>
                }
              </div>
            }
            @for (h of otherHints(); track h.code) {
              @let hp = hintParts(h.text);
              <div class="gw-alert gw-alert--compact" [class]="hintTone(h)">
                <svg [lucideIcon]="severity(h) === 'info' ? 'info' : 'triangle-alert'" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content">
                  @if (hp.title) {
                    <p class="gw-alert__title">{{ hp.title }}</p>
                  }
                  <p class="gw-alert__body">{{ hp.body }}</p>
                </div>
              </div>
            }
          } @else if (o && o.needsConfirmation) {
            @let c = o.needsConfirmation;
            <!-- The test stopped before the credentials left: the person compares what the server showed, then trusts it. -->
            <div class="gw-alert gw-alert--warning">
              <svg [lucideIcon]="c.kind === 'host-key' ? 'key-round' : 'shield-question-mark'" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content">
                <p class="gw-alert__title"><ng-container *ngTemplateOutlet="parts; context: { $implicit: confirmParts() }" /></p>
                <p class="gw-alert__body">{{ c.text }}</p>
                @if (confirmFacts().length) {
                  <dl class="gw-kv gw-kv--dense">
                    @for (f of confirmFacts(); track f.key) {
                      <div class="gw-kv__row"><dt class="gw-kv__key">{{ f.label }}</dt><dd class="gw-kv__value gw-mono">{{ f.value }}</dd></div>
                    }
                  </dl>
                }
                @if (o.confirmationId) {
                  <div class="gw-alert__actions">
                    <button class="gw-btn gw-btn--sm" type="button" [attr.aria-busy]="trusting() ? 'true' : null" [attr.aria-disabled]="trusting() ? 'true' : null" (click)="onTrust()">
                      @if (trusting()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ trustLabel() }}
                    </button>
                  </div>
                }
              </div>
            </div>
          } @else if (o) {
            <div class="gw-alert gw-alert--danger">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content">
                <p class="gw-alert__title"><ng-container *ngTemplateOutlet="parts; context: { $implicit: failureParts() }" /></p>
                <p class="gw-alert__body">{{ o.error?.message ?? 'The test failed.' }}</p>
                @if (googleSignIn()) {
                  <p class="gw-alert__body" [id]="p + '-google-help'">Sign in through Google Cloud CLI to update this computer's Google credentials. Other local applications using the same sign-in will also use the selected account.</p>
                  <div class="gw-alert__actions">
                    <button class="gw-btn gw-btn--sm" type="button"
                      [attr.aria-disabled]="googleSigningIn() || !desktop.googleSignInAvailable ? 'true' : null"
                      [attr.aria-busy]="googleSigningIn() ? 'true' : null"
                      [attr.aria-describedby]="p + '-google-help ' + p + '-google-status'"
                      (click)="signInToGoogle()">
                      @if (googleSigningIn()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}
                      Sign in with Google
                    </button>
                  </div>
                  <p class="gw-alert__body" [id]="p + '-google-status'">
                    @if (googleSigningIn()) {
                      Complete Google sign-in in your browser. The connection will be tested again when you finish.
                    } @else if (!desktop.googleSignInAvailable) {
                      Available in the desktop app. Run <code class="gw-code">gcloud auth application-default login</code> in a terminal, then test again.
                    } @else {
                      Runs <code class="gw-code">gcloud auth application-default login</code> and tests the connection again after sign-in.
                    }
                  </p>
                  @if (googleSignInError(); as error) {<p class="gw-alert__body" role="alert">{{ error }}</p>}
                }
                @if (failureFacts().length) {
                  <dl class="gw-kv gw-kv--dense">
                    @for (f of failureFacts(); track f.key) {
                      <div class="gw-kv__row"><dt class="gw-kv__key">{{ f.label }}</dt><dd class="gw-kv__value gw-mono">{{ f.value }}</dd></div>
                    }
                  </dl>
                }
                @if (o.error?.technical) {
                  <div class="gw-alert__actions">
                    <button
                      class="gw-btn gw-btn--ghost gw-btn--sm gw-connection-form__toggle"
                      type="button"
                      gwToggle
                      [(expanded)]="open"
                      [attr.aria-controls]="p + '-details'"
                    >
                      <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-connection-form__when-closed"></svg
                      ><svg lucideIcon="chevron-up" class="gw-icon gw-icon--sm gw-connection-form__when-open"></svg>Details
                    </button>
                  </div>
                }
              </div>
            </div>
            @if (o.error?.technical) {
              <gw-code-block
                wrap
                [attr.id]="p + '-details'"
                [id]="p + '-details-pre'"
                [hidden]="!open()"
                lang="Error"
                [filename]="detailsFile()"
                [text]="o.error!.technical!"
                copyLabel="error details"
                regionLabel="Error details"
              />
            }
          }
        }
        @default {
          <span class="gw-status gw-status--neutral"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Not tested yet</span></span>
          <p class="gw-text-small gw-fg-muted">{{ idleText() }}</p>
        }
      }
    </div>
    <ng-template #parts let-parts>@for (part of parts; track $index) {@if (part.code) {<span class="gw-mono">{{ part.text }}</span>} @else {<ng-container>{{ part.text }}</ng-container>}}</ng-template>
  `,
})
export class GwConnectionTest {
  private readonly time = inject(TimeService);
  protected readonly desktop = inject(DesktopBridge);
  private readonly destroyRef = inject(DestroyRef);
  readonly idPrefix = input('cf');
  readonly state = input<TestState>('idle');
  readonly outcome = input<TestOutcome | null>(null);
  readonly target = input<TestTarget>({ address: null, user: null, timeoutMs: null });
  /** When the test ran (the details block's "core log · 14:02:11"). */
  readonly testedAt = input<string | null>(null);
  /** The User field the write warning links back to. */
  readonly userFieldId = input<string | null>(null);
  readonly detailsOpen = input(false, { transform: booleanAttribute });
  readonly idleText = input('You will see the server version, the latency and what this user is allowed to do.');
  /** The card's title: "Test connection" under a form, "Test result" as Add connection's own step. */
  readonly heading = input('Test connection');
  /** "Change user" as a button that emits `changeUser` (a wizard goes back a step), not a link to the field. */
  readonly changeUserButton = input(false, { transform: booleanAttribute });
  /** Trust is on its way (the page confirmed and tests again). */
  readonly trusting = input(false, { transform: booleanAttribute });
  readonly googleSignIn = input(false, { transform: booleanAttribute });
  /** Changes when the connection draft changes, so a late sign-in never tests a different configuration. */
  readonly googleSignInContext = input<unknown>(null);
  readonly run = output<void>();
  readonly changeUser = output<void>();
  /** The person trusts what the test stopped for (`needsConfirmation`): the page sends `confirmationId`. */
  readonly trust = output<string>();
  /** The details disclosure, opened again with each new result when `detailsOpen` asks for it. */
  protected readonly open = linkedSignal(() => (this.outcome(), this.detailsOpen()));
  protected readonly permissionParts = permissionParts;
  protected readonly hintParts = hintParts;
  protected readonly severity = severity;
  protected readonly googleSigningIn = signal(false);
  protected readonly googleSignInError = linkedSignal<string | null>(() => {
    this.outcome();
    this.googleSignInContext();
    return null;
  });

  protected async signInToGoogle(): Promise<void> {
    const outcome = this.outcome();
    if (this.googleSigningIn() || !this.googleSignIn() || !this.desktop.googleSignInAvailable
        || this.state() !== 'done' || !outcome || outcome.ok || outcome.needsConfirmation) return;
    const context = this.googleSignInContext();
    const current = () => !this.destroyRef.destroyed && this.googleSignIn() && this.outcome() === outcome
      && this.googleSignInContext() === context && this.state() === 'done';
    this.googleSignInError.set(null);
    this.googleSigningIn.set(true);
    let signedIn = false;
    try {
      await this.desktop.signInToGoogle();
      signedIn = true;
    } catch (error) {
      if (current()) this.googleSignInError.set(desktopErrorText(error, 'Google sign-in did not finish. Try again or run gcloud auth application-default login in a terminal.'));
    } finally {
      if (!this.destroyRef.destroyed) this.googleSigningIn.set(false);
    }
    if (signedIn && current()) this.run.emit();
  }

  protected readonly timeout = computed(() => {
    const ms = this.target().timeoutMs;
    if (!ms) return null;
    return ms >= 1000 && ms % 1000 === 0 ? `${ms / 1000} s` : `${ms} ms`;
  });

  protected readonly connectedLine = computed(() => {
    const o = this.outcome();
    return ['Connected', o?.serverVersion, o ? `${o.latencyMs} ms` : null].filter(Boolean).join(' · ');
  });

  protected readonly canWrite = computed(() => {
    const level = (this.outcome()?.permissions?.level ?? '').toLowerCase();
    return level === 'write' || level === 'admin';
  });

  /** The connector's own hint about the write grant ("This user can write. Fine for dev: …"), if it gave one. */
  private readonly writeHint = computed(() =>
    this.canWrite() ? ((this.outcome()?.hints ?? []).find((h) => /write/i.test(h.code)) ?? null) : null,
  );

  /** The write warning's body: the connector's advice after its first sentence, or the kit's general one. */
  protected readonly writeAdvice = computed(() => {
    const h = this.writeHint();
    const parts = h ? hintParts(h.text) : null;
    return parts?.title ? parts.body : 'For prod use a read-only user or a replica.';
  });

  /** Hints beyond the write warning the panel already words (codes about write grants). */
  protected readonly otherHints = computed(() =>
    (this.outcome()?.hints ?? []).filter((h) => !(this.canWrite() && /write/i.test(h.code))),
  );

  /** What else the connector found about the account (`permissions.details`: "Schema changes: None: no CREATE…"). */
  protected readonly detailRows = computed(() => {
    const d = this.outcome()?.permissions?.details;
    if (!d || typeof d !== 'object' || Array.isArray(d)) return [];
    return Object.entries(d as Record<string, JsonValue>)
      .filter((e): e is [string, string] => typeof e[1] === 'string' && e[1] !== '')
      .map(([key, value]) => ({ key, label: label(key), value }));
  });

  protected readonly confirmTitle = computed(() => testConfirmTitle(this.outcome(), this.target().address));

  protected readonly confirmFacts = computed(() => testFacts(this.outcome()?.needsConfirmation?.data));

  protected readonly trustLabel = computed(() => {
    switch (this.outcome()?.needsConfirmation?.kind) {
      case 'host-key':
        return 'Trust this key and test again';
      case 'tls-cert':
        return 'Trust this certificate and test again';
      case 'exec-plugin':
        return 'Allow the command and test again';
      default:
        return 'Trust and test again';
    }
  });

  protected readonly failureTitle = computed(() => testFailureTitle(this.outcome(), this.target().address));

  /** The titles with the host in mono, as the kit sets it ("Can't reach `db.dev.internal:5432`"). */
  protected readonly confirmParts = computed(() =>
    literalParts(this.confirmTitle(), [testHost(this.outcome()?.needsConfirmation?.data, this.target().address)]),
  );
  protected readonly failureParts = computed(() => {
    const o = this.outcome();
    const host = (o?.error?.code ?? '').startsWith('ssh.host-key') ? testHost(o?.details, this.target().address) : this.target().address;
    return literalParts(this.failureTitle(), [host, host?.replace(/:\d+$/, '')]);
  });

  /** A changed host key shows the key the server presented now (to compare with the host), as the kit's facts. */
  protected readonly failureFacts = computed(() => {
    const o = this.outcome();
    return (o?.error?.code ?? '').startsWith('ssh.host-key') ? testFacts(o?.details) : [];
  });

  protected readonly detailsFile = computed(() => {
    const at = this.testedAt();
    return at ? `core log · ${this.time.clock(at, true)}` : 'core log';
  });

  protected hintTone(h: TestHint): string {
    const s = severity(h);
    return s === 'info' ? 'gw-alert--info' : s === 'danger' || s === 'error' ? 'gw-alert--danger' : 'gw-alert--warning';
  }

  protected onTrust(): void {
    const id = this.outcome()?.confirmationId;
    if (id && !this.trusting()) this.trust.emit(id);
  }

  protected focusUser(event: Event): void {
    const id = this.userFieldId();
    const el = id ? document.getElementById(id) : null;
    if (!el) return;
    event.preventDefault();
    el.focus();
    el.scrollIntoView({ block: 'center' });
  }
}
