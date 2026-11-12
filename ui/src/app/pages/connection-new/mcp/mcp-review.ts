import { NgTemplateOutlet } from '@angular/common';
import { afterNextRender, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, Injector, output, signal, viewChild } from '@angular/core';
import { SecretInputs } from '../../../core/api/connections.api';
import { McpFixedHeader, McpProfile, McpServerAuthReview, McpServerConfig, McpServerReview, McpServerSource, McpSignIn } from '../../../core/api/plugins.api';
import { Env } from '../../../core/api/types';
import { GwCopy } from '../../../kit/copy';
import { GwIcon } from '../../../kit/icons';
import { formatArgv, npmPackage, onThisComputer, serverPaths, signInHost } from '../../connectors/connectors-view';
import { ConnectorsData } from '../../connectors/connectors.data';
import { failureText } from '../../connectors/dialogs/failure';
import { GwMcpSignIn } from '../../connectors/dialogs/mcp-sign-in';
import { cloudSecret, cloudSignInText } from '../../connectors/mcp-cloud';
import { profileStarts } from '../../connectors/mcp-profiles';
import { McpReviewed } from './mcp-configure';
import { McpServerConnected } from './mcp-endpoints';

/** The secret field of an OAuth app's client secret. */
export const CLIENT_SECRET = '/auth/clientSecret';

/**
 * One variable (or header) whose value the review asks for: its name, and where its value goes in `secrets`; or the
 * key of a cloud sign-in (a service account key, a secret access key), named by `label`.
 */
export interface McpReviewSecret {
  readonly name: string;
  /** The JSON Pointer the core reads the value at (`/env/0/secretRef`, `/headers/1/secretRef`). */
  readonly pointer: string;
  /** The field's label instead of "Value of <name>", for a key that isn't a header ("Secret access key"). */
  readonly label?: string;
  /** A multi-line value: a service account key's JSON. */
  readonly multiline?: boolean;
  /** A token entered without the Authorization header's Bearer prefix. */
  readonly bearer?: boolean;
}

/** What the review dialog shows: the core's review of the request, put in the kit's rows. */
export interface McpReviewView {
  readonly name: string;
  readonly http: boolean;
  /** Start (stdio) or Connect (HTTP). */
  readonly verb: 'Start' | 'Connect';
  readonly risk: string;
  /**
   * The words of the confirmation the person checks before the start. A required sign-in confirms the server itself
   * (no checkbox); with an optional one, the checkbox is for connecting without a sign-in.
   */
  readonly trust: string;
  readonly targetKey: 'Command' | 'URL';
  /** The command as it will run (resolved argv) or the URL. */
  readonly target: string;
  /** Pinned version (null: the row is left out), or unpinned. */
  readonly version: { readonly pinned: true; readonly version: string } | { readonly pinned: false } | null;
  readonly workdir: string;
  readonly sandbox: string | null;
  /**
   * The variables (or headers) in the core's order, then a cloud sign-in's key: each value is typed here and becomes a
   * secret of the server.
   */
  readonly secrets: readonly McpReviewSecret[];
  /** The variables (or headers) alone, by name, for "Secrets passed". */
  readonly named: readonly McpReviewSecret[];
  /** Over HTTP: what the review found about signing in, or null (a command, or a core without discovery). */
  readonly auth: McpServerAuthReview | null;
  /** `required`: Connect waits for the sign-in; `optional`: the server answers without one too. */
  readonly signIn: 'required' | 'optional' | null;
  /** The client ID of the person's OAuth app, from Sign-in options, or null. */
  readonly appClient: string | null;
  /** The server signs in with its cloud's credentials the person chose (§7.6), or null. */
  readonly cloud: 'google' | 'aws' | null;
  /** That sign-in in one sentence ("A Google token from the Google sign-in of this computer."), or null. */
  readonly cloudText: string | null;
  /** The cloud whose credentials could sign in instead of OAuth: the review offers to switch. */
  readonly suggest: 'google' | 'aws' | null;
  /**
   * Over HTTP: the core's warnings (a key in the URL, plain HTTP, a switch of the verified profile the URL doesn't
   * set). A command's are rows of the review already: its pin and where it runs.
   */
  readonly warnings: readonly string[];
  /** Over HTTP: the headers sent as written in the settings (`X-MCP-Readonly: true`), not secrets. */
  readonly fixed: readonly McpFixedHeader[];
  /** GateRook's verified profile of the vendor's server, with how its pins stand in the settings, or null. */
  readonly profile: McpProfile | null;
  /** "execute in Approval": the tools the profile starts other than their annotations say, or ''. */
  readonly profileStarts: string;
}

/**
 * The review in words (kit `dlg-review-mcp`, filled by its page script): the verb and risk of the transport, the
 * command as the core resolved it (ARCHITECTURE §8.6.7: `node …/dist/index.js` for a catalog server, not `npx`), the
 * pinned version or "Not pinned", where it runs and the secrets it gets, by name. The names and their order are the
 * core's (`env` of the review: a catalog entry names its own, `GITHUB_PERSONAL_ACCESS_TOKEN`); the n-th one's value
 * goes to the core at `/env/n/secretRef` (`/headers/n/…` over HTTP).
 */
export function mcpReviewView(r: McpReviewed): McpReviewView {
  const review: McpServerReview = r.review;
  const http = (review.transport ?? r.transport) === 'http';
  const name = review.name || r.name;
  const typed = 'config' in r.source ? r.source.config.command ?? [] : [];
  const pkg = http ? null : npmPackage(typed);
  const catalogVersion = review.catalogEntry?.includes('@') ? review.catalogEntry.split('@').at(-1)! : null;
  const version: McpReviewView['version'] = http
    ? null
    : review.notPinned
      ? { pinned: false }
      : catalogVersion
        ? { pinned: true, version: catalogVersion }
        : pkg?.version
          ? { pinned: true, version: pkg.version }
          : pkg
            ? { pinned: false }
            : null;
  const names = review.env.length ? review.env : r.variables.map((v) => v.name);
  const key = http ? 'headers' : 'env';
  const auth = http ? (review.auth ?? null) : null;
  const config = 'config' in r.source ? r.source.config : null;
  const chosen = http && config?.auth && config.auth.type !== 'oauth' ? config.auth : null;
  const cloud = chosen ? chosen.type as 'google' | 'aws' : null;
  // A key the person passes as the Authorization header is their choice instead of a sign-in.
  const keyed = http && names.some((n) => n.toLowerCase() === 'authorization');
  const signIn = keyed || cloud ? null : auth?.kind === 'oauth' ? 'required' : auth?.kind === 'optional' ? 'optional' : null;
  const cloudKey = cloudSecret(chosen);
  const credentials = cloud === 'google' ? 'a token of your Google Cloud credentials' : 'a signature of your AWS credentials';
  return {
    name,
    http,
    verb: http ? 'Connect' : 'Start',
    risk: cloud
      ? `Every call carries ${credentials}${names.length ? ' and these secrets' : ''} to this URL. Nobody has verified the server behind it.`
      : http
      ? signIn
        ? `Every call sends your sign-in${names.length ? ' and these secrets' : ''} to this URL. Nobody has verified the server behind it.`
        : names.length
          ? 'Every call sends these secrets to this URL. Nobody has verified the server behind it.'
          : 'Every call goes to this URL. Nobody has verified the server behind it.'
      : names.length
        ? 'This code runs on your computer with these secrets. Nobody has verified it: it can read them and reach anything your user account can.'
        : 'This code runs on your computer. Nobody has verified it: it can reach anything your user account can.',
    trust: cloud
      ? `I trust this server with my ${cloud === 'google' ? 'Google Cloud' : 'AWS'} credentials${names.length ? ' and these secrets' : ''}`
      : http
      ? signIn === 'required'
        ? `I trust this server with my sign-in${names.length ? ' and these secrets' : ''}`
        : names.length
          ? 'I trust this server with these secrets'
          : 'I trust this server with the calls of my AI apps'
      : names.length
        ? 'I trust this code with these secrets on this computer'
        : 'I trust this code on this computer',
    targetKey: http ? 'URL' : 'Command',
    target: http ? (review.url ?? r.target) : review.argv.length ? formatArgv(review.argv) : r.target,
    version,
    workdir: serverPaths(name).workdir,
    sandbox: review.sandbox,
    secrets: [
      ...names.map((n, i) => ({ name: n, pointer: `/${key}/${i}/secretRef`,
        ...(http && r.bearer && n.toLowerCase() === 'authorization' ? { label: 'Bearer token', bearer: true } : {}) })),
      ...(cloudKey ? [{ name: cloudKey.label, pointer: cloudKey.pointer, label: cloudKey.label, multiline: cloudKey.multiline }] : []),
    ],
    named: names.map((n, i) => ({ name: n, pointer: `/${key}/${i}/secretRef` })),
    auth,
    signIn,
    appClient: config?.auth?.clientId ?? null,
    cloud,
    cloudText: chosen ? cloudSignInText(chosen, review.url ?? r.target) : null,
    suggest: http && !cloud && !keyed && auth?.cloud ? auth.cloud : null,
    warnings: http ? (review.warnings ?? []) : [],
    fixed: http ? (config?.fixedHeaders ?? []) : [],
    profile: review.profile ?? null,
    profileStarts: review.profile ? profileStarts(review.profile.tools) : '',
  };
}

/**
 * The config a sign-in and the add that follows it send: the reviewed one with an OAuth sign-in, and the client ID
 * typed in the review when the server needs the person's OAuth app. Both must be the same: the core binds the
 * sign-in to the URL and sign-in settings it was made for.
 */
export function signInConfig(config: McpServerConfig, clientId: string | null): McpServerConfig {
  const id = clientId?.trim() || config.auth?.clientId || null;
  return { ...config, auth: { ...(config.auth ?? {}), type: 'oauth', ...(id ? { clientId: id } : {}) } };
}

/**
 * The values typed for the review's secrets, by pointer, or the indexes of the ones left empty. A value is sent as
 * typed, except a dedicated Bearer token: surrounding whitespace and an optional prefix are normalized, then the
 * Authorization prefix is added. An empty value is refused.
 */
export function mcpSecretValues(
  secrets: readonly McpReviewSecret[],
  values: readonly string[],
): { readonly secrets: SecretInputs; readonly missing: readonly number[] } {
  const out: { [pointer: string]: { source: 'local'; value: string } } = {};
  const missing: number[] = [];
  secrets.forEach((s, i) => {
    const typed = values[i] ?? '';
    const value = s.bearer ? typed.trim().replace(/^Bearer(?:\s+|$)/i, '').trim() : typed;
    if (!value) missing.push(i);
    else out[s.pointer] = { source: 'local', value: s.bearer ? `Bearer ${value}` : value };
  });
  return { secrets: out, missing };
}

let nextId = 0;

/**
 * `<form gwMcpReview>` (kit `connection-new`, step 3 of an MCP server): the review before an MCP server first starts —
 * what runs, which version, as whom, with which secrets — under an alert that names the risk (danger for code on this
 * computer, warning for a server at a URL). "Start MCP server sentry-mcp" stays `aria-disabled` until "I trust this code
 * with these secrets on this computer" (for a server at a URL: "I trust this server with the calls of my AI apps") is
 * checked. Starting it (`POST /api/mcp-servers {…, secrets, acknowledge: true}`) saves the server and lists its tools,
 * Off everywhere; the wizard then moves on to Add to endpoint.
 *
 * A server that answers only with an OAuth sign-in has neither the checkbox nor Connect: the review ends in a centred
 * call to sign in whose large button is the primary action, and the finished sign-in — the person allowed this server
 * their account — is the confirmation. The server connects right after it, and the endpoint step says it is signed in.
 * An optional sign-in connects the same way; without one, the checkbox and Connect remain. The values the sign-in
 * connects with (variables, the OAuth app's ID and secret) are typed first: Sign in waits for them.
 *
 * The values of its variables (or headers) are typed here, for a typed server and a catalog one alike: the core keeps
 * each as a new secret of the server (it takes no existing secret by name). They are write-only: never in a signal
 * (only whether a field is empty is), read from the fields once when the server starts, sent with that one request and
 * wiped from the fields when the person goes back, leaves the page or the review shows another server.
 */
@Component({
  selector: 'form[gwMcpReview]',
  imports: [NgTemplateOutlet, GwIcon, GwCopy, GwMcpSignIn],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-form gw-form--sticky-actions', novalidate: '', '(submit)': 'start($event)' },
  template: `
    @if (view(); as w) {
      <div class="gw-alert" [class]="w.http ? 'gw-alert--warning' : 'gw-alert--danger'" [id]="uid + '-risk'">
        <svg [lucideIcon]="w.http ? 'globe' : 'shield-alert'" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content"><p class="gw-alert__title" [id]="uid + '-t'">{{ w.verb }} MCP server <span class="gw-mono">{{ w.name }}</span>?</p><p class="gw-alert__body" [id]="uid + '-d'">{{ w.risk }}</p></div>
      </div>
      <section class="gw-card" [attr.aria-labelledby]="uid + '-facts-t'">
        <header class="gw-card__header">
          <div class="gw-card__heading">
            <h3 class="gw-card__title" [id]="uid + '-facts-t'">What {{ w.http ? 'connects' : 'runs' }}</h3>
            <p class="gw-card__description">As the core resolved it from your settings.</p>
          </div>
        </header>
        <div class="gw-card__body">
            <dl class="gw-kv gw-kv--dense">
              <div class="gw-kv__row"><dt class="gw-kv__key">Environment</dt><dd class="gw-kv__value"><span [class]="'gw-env gw-env--sm gw-env--' + environment()">{{ environment() }}</span></dd></div>
              <div class="gw-kv__row"><dt class="gw-kv__key">{{ w.targetKey }}</dt><dd class="gw-kv__value"><code class="gw-code">{{ w.target }}</code></dd></div>
              @if (w.version; as ver) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Version</dt><dd class="gw-kv__value">
                  @if (ver.pinned) {
                    Pinned to <span class="gw-mono">{{ ver.version }}</span>. npm checks the package integrity on every start.
                  } @else {
                    <span class="gw-badge gw-badge--warning gw-badge--sm"><svg lucideIcon="triangle-alert" class="gw-icon gw-badge__icon"></svg>Not pinned</span> npx can fetch new code on every start, with these secrets. Add <span class="gw-mono">&#64;version</span> to the package.
                  }
                </dd></div>
              }
              @if (!w.http) {
                <!-- ARCHITECTURE §7.5: where the OS has no sandbox for the process, the review says so. -->
                <div class="gw-kv__row"><dt class="gw-kv__key">Runs as</dt><dd class="gw-kv__value">
                  @if (w.sandbox) {
                    Your user, without a shell, in an OS sandbox ({{ w.sandbox }}), from <span class="gw-mono">{{ w.workdir }}</span>
                  } @else {
                    Your user, without a shell or a sandbox, from <span class="gw-mono">{{ w.workdir }}</span>. Not sandboxed on this system: it can read everything your account can, including GateRook's data.
                  }
                </dd></div>
              }
              <div class="gw-kv__row"><dt class="gw-kv__key">Secrets passed</dt><dd class="gw-kv__value">
                @if (w.signIn) {Your sign-in, encrypted here and renewed by GateRook@if (w.named.length) {, and }}
                @if (w.cloud) {{{ w.cloud === 'google' ? 'A token of your Google Cloud credentials' : 'A signature of your AWS credentials' }}, made by GateRook for each request@if (w.named.length) {, and }}
                @for (s of w.named; track s.pointer; let last = $last; let i = $index) {@if (i > 0) {{{ last ? ' and ' : ', ' }}}<span class="gw-mono">{{ s.name }}</span>}@if (w.named.length) {{{ w.named.length === 1 ? ', from a new secret' : ', each from a new secret' }} of {{ w.name }}, encrypted here} @else if (!w.signIn && !w.cloud) {None}
              </dd></div>
              @if (w.fixed.length) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Fixed headers</dt><dd class="gw-kv__value">
                  @for (h of w.fixed; track h.name; let last = $last) {<code class="gw-code">{{ h.name }}: {{ h.text }}</code>@if (!last) {, }}
                  sent as written with every request.
                </dd></div>
              }
              @if (w.cloudText) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Sign-in</dt><dd class="gw-kv__value">{{ w.cloudText }} The agent never sees {{ w.cloud === 'google' ? 'the token' : 'the keys' }}.</dd></div>
              }
              @if (w.signIn && w.auth; as a) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Sign-in</dt><dd class="gw-kv__value">
                  @if (onThisComputer(a.authorizationHost)) {OAuth on this computer} @else {OAuth at <span class="gw-mono">{{ a.authorizationHost || a.issuer }}</span>}{{ w.signIn === 'optional' ? ', optional: the server also answers without it, with less access' : '' }}.
                  {{ a.registration === 'metadata-document' ? 'GateRook signs in there as GateRook, with the client details it publishes.' : a.registration === 'dynamic' ? 'GateRook registers itself there as GateRook.' : a.registration === 'gaterook-app' ? 'GateRook signs in there with its own app: you enter a one-time code on its page, with nothing to set up.' : a.registration === 'app' ? 'As your OAuth app.' : '' }}
                </dd></div>
              }
              @if (w.profile; as p) {
                <!-- ARCHITECTURE §7.5: a verified profile only makes defaults stricter; it verifies no behavior. -->
                <div class="gw-kv__row"><dt class="gw-kv__key">Verified profile</dt><dd class="gw-kv__value">
                  {{ p.publisher }}’s own server. GateRook checked its tools, sign-in and switches on {{ p.checked }}; that doesn’t verify what the server does.
                  <a class="gw-link" [href]="p.documentation" target="_blank" rel="noopener noreferrer">{{ p.publisher }}’s documentation<span class="gw-sr-only"> (opens in your browser)</span></a>
                </dd></div>
                @for (pin of p.pins; track pin.name) {
                  <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-mono">{{ pin.name }}</span>@if (pin.in === 'header') {<span class="gw-sr-only"> header</span>}</dt><dd class="gw-kv__value">
                    @if (pin.set) {<span class="gw-badge gw-badge--success gw-badge--sm"><svg lucideIcon="check" class="gw-icon gw-badge__icon"></svg>Set</span>} @else {<span class="gw-badge gw-badge--warning gw-badge--sm"><svg lucideIcon="triangle-alert" class="gw-icon gw-badge__icon"></svg>Not set</span>} {{ pin.purpose }}
                  </dd></div>
                }
              }
              <div class="gw-kv__row"><dt class="gw-kv__key">Tools</dt><dd class="gw-kv__value">Listed after the {{ w.http ? 'connection' : 'start' }} and Off everywhere. On an endpoint, declared reads start as Read only and other tools start as Approval.@if (w.profileStarts) { Its verified profile starts {{ w.profileStarts }} whatever the server declares, and never as Read only.} The server declares its own annotations; the gateway can't verify them.</dd></div>
              @if (w.profile?.tools?.length) {
                <!-- A profile may name dozens of tools (one per DigitalOcean service): the first few, then the rest on request. -->
                @let rules = w.profile!.tools;
                <div class="gw-kv__row"><dt class="gw-kv__key">Starts stricter</dt><dd class="gw-kv__value">
                  <ul class="gw-stack gw-stack--sm" [attr.id]="uid + '-profile-tools'">
                    @for (t of rules; track t.tool; let i = $index) {
                      @if (i < profileToolsShown || allProfileTools()) {
                        <li><span class="gw-mono">{{ t.tool }}</span>: {{ t.start === 'off' ? 'Off' : 'Approval' }}. {{ t.reason }}</li>
                      }
                    }
                  </ul>
                  @if (rules.length > profileToolsShown && !allProfileTools()) {
                    <button class="gw-btn gw-btn--link gw-btn--sm" type="button" [attr.aria-controls]="uid + '-profile-tools'" (click)="allProfileTools.set(true)">Show {{ rules.length - profileToolsShown }} more</button>
                  }
                </dd></div>
              }
            </dl>
        </div>
      </section>
            @for (warning of w.warnings; track $index) {
              <div class="gw-alert gw-alert--warning gw-alert--compact">
                <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__body">{{ warning }}</p></div>
              </div>
            }
            @if (w.suggest; as cloud) {
              <div class="gw-alert gw-alert--info gw-alert--compact">
                <svg lucideIcon="cloud" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content">
                  <p class="gw-alert__title">{{ cloud === 'google' ? 'Sign in with Google Cloud credentials instead' : 'Sign requests with AWS credentials instead' }}</p>
                  <p class="gw-alert__body">{{ cloud === 'google' ? 'No OAuth app to create: GateRook gets a token from the Google sign-in of this computer (gcloud auth application-default login) and renews it. Other credentials: Sign-in options of Add MCP server.' : 'No browser sign-in that ends after 12 hours: GateRook signs each request with SigV4, with the default profile of this computer. Other credentials: Sign-in options of Add MCP server.' }}</p>
                </div>
                <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" [attr.aria-busy]="switching() ? 'true' : null" [attr.aria-disabled]="switching() ? 'true' : null" (click)="useCloud(cloud)">@if (switching()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ cloud === 'google' ? 'Use Google Cloud credentials' : 'Use AWS credentials' }}</button></div>
              </div>
            }
            @if (w.signIn && w.auth; as a) {
              @if (a.registration === 'app-required' || a.registration === 'app') {
                <fieldset class="gw-field">
                  <legend class="gw-field__label">Your OAuth app</legend>
                  @if (a.registration === 'app-required') {
                    <div class="gw-alert gw-alert--warning gw-alert--compact">
                      <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                      <div class="gw-alert__content"><p class="gw-alert__title">This server needs your own OAuth app</p><p class="gw-alert__body">{{ a.message }}</p></div>
                    </div>
                  }
                  @if (a.redirectUri) {
                    <div class="gw-field">
                      <label class="gw-field__label" [for]="uid + '-redirect'">Redirect URI of the app</label>
                      <div class="gw-copy-field">
                        <input class="gw-copy-field__value" [id]="uid + '-redirect'" type="text" readonly [value]="a.redirectUri" [attr.aria-describedby]="uid + '-redirect-hint'">
                        <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" [gwCopyFrom]="'#' + uid + '-redirect'">
                          <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> redirect URI</span></span>
                          <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
                        </button>
                      </div>
                      <p class="gw-field__hint" [id]="uid + '-redirect-hint'">Register it in the OAuth app at the provider. GateRook listens there on this computer only while you sign in.</p>
                    </div>
                  }
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client'">OAuth client ID</label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client'" type="text" spellcheck="false" autocomplete="off" [value]="w.appClient ?? ''" [readOnly]="!!w.appClient" (input)="clientId.set(fieldValue($event))">
                  </div>
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client-secret'">Client secret @if (!a.secretRequired) {<span class="gw-field__optional">Optional</span>}</label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client-secret'" type="password" autocomplete="new-password" spellcheck="false" (input)="clientSecretTyped.set(fieldValue($event) !== '')" [attr.aria-describedby]="uid + '-client-secret-hint'">
                    <p class="gw-field__hint" [id]="uid + '-client-secret-hint'">{{ a.secretRequired ? 'The provider takes the app only with its secret.' : 'Only when the app has one.' }} It is stored encrypted as a secret of {{ w.name }} and never shown again.@if (hostOf(a.tokenEndpoint); as tokens) { GateRook sends it only to <span class="gw-mono">{{ tokens }}</span>, where this review found the authorization server’s token endpoint.}</p>
                  </div>
                </fieldset>
              }
            } @else if (w.auth; as a) {
              @if ((a.kind === 'refused' || a.kind === 'unknown') && !w.secrets.length) {
                <div class="gw-alert gw-alert--warning gw-alert--compact">
                  <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                  <div class="gw-alert__content"><p class="gw-alert__title">{{ a.kind === 'refused' ? 'It wants a key, not a sign-in' : 'GateRook couldn’t tell how to sign in' }}</p><p class="gw-alert__body">{{ a.message }}{{ a.kind === 'refused' ? ' Use a Bearer token, or go back and add the header that carries its key.' : ' Connecting may fail.' }}</p></div>
                  @if (a.kind === 'refused' && w.http && !w.cloud) {
                    <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" [attr.aria-busy]="switching() ? 'true' : null" [attr.aria-disabled]="switching() ? 'true' : null" (click)="useBearer()">@if (switching()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Use a Bearer token</button></div>
                  }
                </div>
              }
            }
            @if (w.secrets.length) {
              <!-- Review 2026-10-26: the core needs a value for each variable to start the server (a catalog entry too). -->
              <fieldset class="gw-field" [attr.aria-describedby]="uid + '-values-hint'">
                <legend class="gw-field__label">{{ w.secrets.length === 1 ? 'Its value' : 'Their values' }}</legend>
                @for (s of w.secrets; track s.pointer; let i = $index) {
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-value-' + i">@if (s.label) {{{ s.label }}} @else {Value of <span class="gw-mono">{{ s.name }}</span>}</label>
                    @if (s.multiline) {
                      <textarea class="gw-textarea gw-textarea--code" [id]="uid + '-value-' + i" rows="4" required autocomplete="off" spellcheck="false" (input)="valueInput(i, $event)" [attr.aria-invalid]="missing().includes(i) ? 'true' : null" [attr.aria-describedby]="missing().includes(i) ? uid + '-value-err-' + i : null"></textarea>
                    } @else {
                      <input class="gw-input gw-input--mono" [id]="uid + '-value-' + i" type="password" required autocomplete="new-password" spellcheck="false" (input)="valueInput(i, $event)" [attr.aria-invalid]="missing().includes(i) ? 'true' : null" [attr.aria-describedby]="((s.bearer ? uid + '-value-hint-' + i : '') + (missing().includes(i) ? ' ' + uid + '-value-err-' + i : '')).trim() || null">
                    }
                    @if (s.bearer) {<p class="gw-field__hint" [id]="uid + '-value-hint-' + i">Enter only the token. GateRook sends it as <span class="gw-mono">Authorization: Bearer &lt;token&gt;</span>.</p>}
                    @if (missing().includes(i)) {<p class="gw-field__error" [id]="uid + '-value-err-' + i"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ s.label ? 'Enter the ' + s.label.charAt(0).toLowerCase() + s.label.slice(1) + '.' : 'Enter the value of ' + s.name + '.' }}</p>}
                  </div>
                }
                <p class="gw-field__hint" [id]="uid + '-values-hint'">{{ w.secrets.length === 1 ? 'It is' : 'Each is' }} stored encrypted as a secret of {{ w.name }} when it {{ w.http ? 'connects' : 'starts' }}, and never shown again. The agent never sees {{ w.secrets.length === 1 ? 'it' : 'them' }}.</p>
              </fieldset>
            }
            <!-- A server that answers only with a sign-in: signing in is the confirmation, and the server connects
                 right after it. The large Sign in button is the dialog's one primary action; no checkbox, no Connect. -->
            @if (w.signIn === 'required' && error()) {
              <ng-container [ngTemplateOutlet]="failure" />
            }
            @if (w.signIn && w.auth; as a) {
              <div gwMcpSignIn [hero]="true" [server]="w.name" [optional]="w.signIn === 'optional'" [connecting]="busy()" [start]="startSignIn" [host]="a.authorizationHost ?? null" [blocked]="signInBlocked()" [device]="a.registration === 'gaterook-app'" (signedIn)="onSignedIn($event)"></div>
            }
            @if (w.signIn !== 'required') {
              <label class="gw-checkbox">
                <input #ack class="gw-checkbox__input" type="checkbox" [id]="uid + '-ack'" [checked]="acknowledged()" (change)="acknowledged.set(ack.checked)" required>
                <span class="gw-checkbox__control" aria-hidden="true"></span>
                <span class="gw-checkbox__label" [id]="uid + '-ack-label'">{{ w.trust }}</span>
              </label>
              @if (error()) {
                <ng-container [ngTemplateOutlet]="failure" />
              }
            }
      <div class="gw-form__actions">
        <p class="gw-form__note"><svg lucideIcon="info" class="gw-icon"></svg>{{ w.http ? 'Connecting' : 'Starting' }} saves {{ w.name }}; its tools stay Off</p>
        <button class="gw-btn gw-btn--ghost" type="button" (click)="goBack()" [attr.aria-disabled]="busy() || switching() ? 'true' : null" [attr.aria-describedby]="busy() || switching() ? uid + '-back-wait' : null"><svg lucideIcon="arrow-left" class="gw-icon"></svg> Back</button>
        @if (busy() || switching()) {<span class="gw-sr-only" [id]="uid + '-back-wait'">Wait for the current request to finish before going back.</span>}
        <!-- With a required sign-in, Connect shows only when a finished sign-in couldn't connect on its own (a value
             emptied while the browser was open). -->
        @if (w.signIn !== 'required' || (signedIn() && !busy())) {
          <button class="gw-btn" [class.gw-btn--danger]="w.signIn !== 'required'" [class.gw-btn--primary]="w.signIn === 'required'" type="submit" [attr.aria-disabled]="!confirmed() || busy() || switching() ? 'true' : null" [attr.aria-describedby]="w.signIn === 'required' ? null : uid + '-ack-label'" [attr.aria-busy]="busy() || switching() ? 'true' : null">@if (busy() || switching()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ w.verb }} MCP server {{ w.name }}</button>
        }
      </div>
      <ng-template #failure>
        <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert" [id]="uid + '-error'">
          <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content"><p class="gw-alert__title">{{ w.name }} didn't {{ w.http ? 'connect' : 'start' }}</p><p class="gw-alert__body">{{ error() }}</p>@if (signInAgain()) {<p class="gw-alert__body">A sign-in is used once: sign in again to try once more.</p>}</div>
        </div>
      </ng-template>
    }
  `,
})
export class GwMcpReview {
  private readonly data = inject(ConnectorsData);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly injector = inject(Injector);

  /** The server connected (or started): the wizard moves on to Add to endpoint. */
  readonly connected = output<McpServerConnected>();
  /** Back to Configure, with what was typed there. */
  readonly back = output<void>();

  protected readonly uid = `mr-${++nextId}`;
  protected readonly onThisComputer = onThisComputer;
  protected readonly environment = signal<Env>('dev');
  private readonly source = signal<McpServerSource | null>(null);
  protected readonly view = signal<McpReviewView | null>(null);
  /** How many of a verified profile's tools the review lists before Show more, and whether the person asked for all. */
  protected readonly profileToolsShown = 4;
  protected readonly allProfileTools = signal(false);
  protected readonly busy = signal(false);
  protected readonly acknowledged = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The error came after a sign-in, which the core took: trying again takes a new sign-in. */
  protected readonly signInAgain = signal(false);
  /** The value fields left empty at the last Start (by index). */
  protected readonly missing = signal<readonly number[]>([]);
  /** The value fields that aren't empty (by index): a sign-in connects with them, so Sign in waits for all. */
  protected readonly typed = signal<readonly number[]>([]);
  /** The client ID typed in the review, for a server that needs the person's OAuth app. */
  protected readonly clientId = signal('');
  /** Whether a client secret was typed (its value stays in the field): Google takes no app without one. */
  protected readonly clientSecretTyped = signal(false);
  /** A review of the same server with its cloud's credentials is on its way. */
  protected readonly switching = signal(false);
  private reviewed: McpReviewed | null = null;
  /** The finished sign-in, and the config it was made for (the add sends exactly that one). */
  protected readonly signedIn = signal<McpSignIn | null>(null);
  private signedInConfig: McpServerConfig | null = null;
  private readonly signInBlock = viewChild(GwMcpSignIn);

  /**
   * Why Sign in can't start: a server that needs an OAuth app has no client ID yet, or no secret where it needs one; or
   * a value it connects with is still empty (the server connects right after the sign-in).
   */
  protected signInBlocked(): string | null {
    const w = this.view();
    if (!w) return null;
    const app = w.auth?.registration === 'app-required' || w.auth?.registration === 'app';
    if (app && w.auth?.registration === 'app-required' && !w.appClient && !this.clientId().trim()) return 'Enter the client ID of your OAuth app first.';
    if (app && w.auth?.secretRequired && !this.clientSecretTyped()) return 'Enter the client secret of your OAuth app first.';
    const empty = w.secrets.find((_, i) => !this.typed().includes(i));
    if (empty) return empty.label ? `Enter the ${empty.label.charAt(0).toLowerCase() + empty.label.slice(1)} first.` : `Enter the value of ${empty.name} first.`;
    return null;
  }

  /** Whether the server may connect: the trust confirmation is checked, or a finished sign-in confirmed it. */
  protected confirmed(): boolean {
    return this.acknowledged() || !!this.signedIn();
  }

  /** A value field changed: its error goes, and whether it is empty is noted (never the value). */
  protected valueInput(i: number, e: Event): void {
    this.clearMissing(i);
    const filled = (e.target as HTMLInputElement | HTMLTextAreaElement).value !== '';
    if (filled !== this.typed().includes(i)) this.typed.update((t) => (filled ? [...t, i] : t.filter((x) => x !== i)));
  }

  /**
   * The browser came back signed in. The person allowed this server their account, so the sign-in confirms the server
   * as the checkbox does, and it connects now, without another click.
   */
  protected onSignedIn(s: McpSignIn): void {
    this.signedIn.set(s);
    this.error.set(null);
    this.signInAgain.set(false);
    void this.connect();
  }

  /**
   * The same server, reviewed again to sign in with its cloud's credentials (§7.6): Google sign-in on this computer, or
   * SigV4 with the default AWS profile. Other credentials are chosen in Add MCP server's Sign-in options.
   */
  protected async useCloud(cloud: 'google' | 'aws'): Promise<void> {
    const r = this.reviewed;
    if (!r || !('config' in r.source) || this.switching()) return;
    const config: McpServerConfig = {
      ...r.source.config,
      auth: cloud === 'google' ? { type: 'google', credentials: { source: 'adc' } } : { type: 'aws', credentials: { source: 'profile', profile: 'default' } },
    };
    this.switching.set(true);
    this.error.set(null);
    try {
      const source = { ...r.source, config };
      const review = await this.data.reviewServer(source);
      this.render({ ...r, review, source });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
    } finally {
      this.switching.set(false);
    }
  }

  /**
   * The same server, reviewed again with a Bearer token in its Authorization header, for one that refused the request
   * without offering a sign-in: as choosing Bearer token in Sign-in options does, without going back.
   */
  protected async useBearer(): Promise<void> {
    const r = this.reviewed;
    if (!r || !('config' in r.source) || this.switching()) return;
    const headers = r.source.config.headers ?? [];
    const config: McpServerConfig = {
      ...r.source.config,
      auth: undefined,
      headers: headers.some((h) => h.name.toLowerCase() === 'authorization') ? headers : [...headers, { name: 'Authorization' }],
    };
    this.switching.set(true);
    this.error.set(null);
    try {
      const source = { ...r.source, config };
      const review = await this.data.reviewServer(source);
      if (this.reviewed !== r) return;
      this.render({ ...r, review, source, variables: config.headers ?? [], bearer: true });
      setTimeout(() => this.host.querySelector<HTMLElement>(`input[id^="${this.uid}-value-"]`)?.focus());
    } catch (err) {
      if (this.reviewed === r) this.error.set(failureText(err, 'The server could not be reviewed with a Bearer token.'));
    } finally {
      this.switching.set(false);
    }
  }

  /**
   * Sign in (`POST /api/mcp-sign-ins`): the reviewed config with the sign-in, the app's secret if one was typed. A new
   * sign-in is the next try: the last failure goes.
   */
  protected readonly startSignIn = (): Promise<McpSignIn> => {
    const source = this.source();
    if (!source || !('config' in source)) return Promise.reject(new Error('Only a server at a URL signs in.'));
    this.error.set(null);
    this.signInAgain.set(false);
    const config = signInConfig(source.config, this.clientId());
    this.signedInConfig = config;
    this.signedIn.set(null);
    const secret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`)?.value ?? '';
    // The client secret goes only to the authorization server this review showed: the core refuses any other one.
    const auth = this.view()?.auth;
    const reviewed = secret && auth?.issuer && auth.tokenEndpoint ? { authorizationServer: { issuer: auth.issuer, tokenEndpoint: auth.tokenEndpoint } } : {};
    return this.data.startSignIn({ config, env: source.env, ...(secret ? { secrets: { [CLIENT_SECRET]: { source: 'local', value: secret } } } : {}), ...reviewed });
  };

  protected fieldValue(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  /** The host of a URL the core reported, or null. */
  protected hostOf(url: string | null | undefined): string | null {
    if (!url) return null;
    try {
      return new URL(url).host || null;
    } catch {
      return null;
    }
  }

  constructor() {
    // Leaving the page: typed values go, and a sign-in still waiting is cancelled at the core.
    inject(DestroyRef).onDestroy(() => this.reset());
  }

  /** Shows the core's review of what Configure asked for, unacknowledged and with empty values. */
  load(reviewed: McpReviewed): void {
    this.switching.set(false);
    this.allProfileTools.set(false);
    this.render(reviewed);
  }

  protected goBack(): void {
    if (this.busy() || this.switching()) return;
    this.reset();
    this.back.emit();
  }

  /** Every review starts unacknowledged and with empty values (the fields are reused from the last one). */
  private render(reviewed: McpReviewed): void {
    this.acknowledged.set(false);
    this.wipe();
    this.signInBlock()?.reset();
    this.signedIn.set(null);
    this.signedInConfig = null;
    this.clientId.set('');
    this.clientSecretTyped.set(false);
    this.reviewed = reviewed;
    this.source.set(reviewed.source);
    this.environment.set(reviewed.source.env ?? 'dev');
    this.view.set(mcpReviewView(reviewed));
    this.busy.set(false);
    this.error.set(null);
    this.signInAgain.set(false);
    this.missing.set([]);
  }

  protected clearMissing(i: number): void {
    if (this.missing().includes(i)) this.missing.update((m) => m.filter((x) => x !== i));
  }

  /** Leaving the review: typed values leave the page, and a sign-in still waiting is cancelled at the core. */
  reset(): void {
    this.wipe();
    this.signInBlock()?.reset();
  }

  /** The typed values leave the page: the fields are emptied (closing, another review, a started server). */
  protected wipe(): void {
    this.valueFields().forEach((f) => (f.value = ''));
    this.typed.set([]);
    const secret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`);
    if (secret) secret.value = '';
  }

  protected async start(e: Event): Promise<void> {
    e.preventDefault();
    await this.connect();
  }

  /** Connects (or starts) the reviewed server: after the trust confirmation, or right after a sign-in. */
  private async connect(): Promise<void> {
    const source = this.source();
    const w = this.view();
    if (!source || !w || this.busy() || this.switching()) return;
    // Enter in a field submits a review whose sign-in is required too: it has no Connect to wait for.
    if (!this.confirmed() && w.signIn !== 'required') return;
    const fields = this.valueFields();
    const values = mcpSecretValues(
      w.secrets,
      w.secrets.map((_, i) => fields[i]?.value ?? ''),
    );
    if (values.missing.length) {
      this.missing.set(values.missing);
      fields[values.missing[0]]?.focus();
      return;
    }
    const signIn = this.signedIn();
    if (w.signIn === 'required' && !signIn) {
      this.error.set(`Sign in to ${w.name} first: it answers only with a sign-in.`);
      this.host.querySelector<HTMLElement>('[gwMcpSignIn] .gw-empty__actions button')?.focus();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.signInAgain.set(false);
    const clientSecret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`)?.value ?? '';
    const all: SecretInputs = { ...values.secrets, ...(signIn && clientSecret ? { [CLIENT_SECRET]: { source: 'local' as const, value: clientSecret } } : {}) };
    const secrets = Object.keys(all).length ? { secrets: all } : {};
    const request: McpServerSource =
      'config' in source
        ? signIn && this.signedInConfig
          ? { ...source, config: this.signedInConfig, ...secrets, signIn: signIn.id }
          : { ...source, config: this.withoutSignIn(source.config, w), ...secrets }
        : { ...source, ...secrets };
    try {
      const started = await this.data.addServer(request);
      this.wipe();
      this.signedIn.set(null);
      this.connected.emit({
        name: started?.name || w.name,
        env: source.env ?? 'dev',
        http: w.http,
        tools: typeof started?.tools === 'number' ? started.tools : null,
        signedIn: signIn ? { host: signInHost(signIn.authorizationHost || w.auth?.authorizationHost) } : null,
        opener: null,
      });
    } catch (err) {
      // The values stay in their fields for another try; closing the dialog wipes them.
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
      if (signIn) {
        // The core takes a sign-in once, even when the server then doesn't answer: the next try is a new sign-in.
        this.signInBlock()?.reset();
        this.signedIn.set(null);
        this.signedInConfig = null;
        this.signInAgain.set(true);
        // The alert lands above the sign-in, which may be scrolled to: bring it into view.
        afterNextRender(() => this.host.querySelector(`#${this.uid}-error`)?.scrollIntoView?.({ block: 'nearest' }), { injector: this.injector });
      }
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * The config a server connects with when the person didn't sign in: one whose sign-in is optional keeps it, so they
   * can sign in later from its row (unless it needs an OAuth app they haven't named).
   */
  private withoutSignIn(config: McpServerConfig, w: McpReviewView): McpServerConfig {
    if (w.signIn !== 'optional' || !w.auth) return config;
    if (w.auth.registration === 'app-required' && !w.appClient && !this.clientId().trim()) return config;
    const signed = signInConfig(config, this.clientId());
    return { ...signed, auth: { ...signed.auth!, optional: true } };
  }

  private valueFields(): (HTMLInputElement | HTMLTextAreaElement)[] {
    return Array.from(this.host.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(`input[id^="${this.uid}-value-"], textarea[id^="${this.uid}-value-"]`));
  }
}
