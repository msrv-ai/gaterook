import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, output, signal } from '@angular/core';
import { McpFixedHeader, McpServerAuthConfig, McpServerConfig, McpServerReview, McpServerSource } from '../../../core/api/plugins.api';
import { Env } from '../../../core/api/types';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { joinAnd, tokenizeCommand } from '../../connectors/connectors-view';
import { ConnectorsData } from '../../connectors/connectors.data';
import { failureText } from '../../connectors/dialogs/failure';
import { googleApiUrl, signingTarget } from '../../connectors/mcp-cloud';
import { PastedServer, parsePastedServer, serverName } from '../../connectors/mcp-paste';
import { fromProfile } from '../../connectors/mcp-profiles';
import { fromRegistry, templateParts } from '../../connectors/mcp-registry';
import { McpChoice, registryLabel } from './mcp-picker';

/**
 * One row of "Variables or headers": the name the process gets. Its value is a new secret of the server, typed in the
 * review right before the start (the core takes no existing secret by name: a server's values are its own secrets).
 */
export interface McpVariableDraft {
  readonly name: string;
}

/**
 * What the review shows and what starting the server sends: the core's review and the request it reviewed. The
 * request carries no values: they are typed in the review and sent once, with the start.
 */
export interface McpReviewed {
  readonly review: McpServerReview;
  readonly source: McpServerSource;
  /** The name and transport as asked (the review may name them too). */
  readonly name: string;
  readonly transport: 'stdio' | 'http';
  /** The command or URL as typed (empty for a catalog server: the core resolves it). */
  readonly target: string;
  /** The requested variables or headers, including a generated Authorization header for a Bearer token. */
  readonly variables: readonly { readonly name: string }[];
  /** The Authorization header is entered as a token, with its Bearer prefix added at connection time. */
  readonly bearer?: boolean;
  readonly opener: HTMLElement | null;
}

export interface McpFormErrors {
  readonly name?: string;
  readonly command?: string;
  readonly url?: string;
  readonly variables?: string;
  readonly fixedHeaders?: string;
  readonly clientId?: string;
  readonly scopes?: string;
  readonly callbackPort?: string;
  readonly impersonate?: string;
  readonly quotaProject?: string;
  readonly profile?: string;
  readonly accessKeyId?: string;
  readonly roleArn?: string;
  readonly externalId?: string;
  readonly service?: string;
  readonly region?: string;
}

/**
 * Sign-in options of a server at a URL. Bearer: a token entered in the review and sent in the Authorization header.
 * OAuth: an app the person created, because the server doesn't let
 * apps register themselves, or a callback the server accepts; empty, GateRook signs in as itself, with its published
 * client details or by registering, and listens on any free port. Google Cloud or AWS: the credentials each request
 * is authorized with instead (§7.6), a token from Google sign-in on this computer or a service account key, or SigV4
 * with an AWS profile or access key. A key is typed in the review.
 */
export interface McpSignInOptions {
  readonly with?: 'oauth' | 'bearer' | 'google' | 'aws';
  readonly clientId: string;
  readonly scopes: string;
  readonly callbackPort: string;
  /** Google: `adc` or `serviceAccountKey`; AWS: `profile` or `accessKey`. */
  readonly source?: string;
  readonly impersonate?: string;
  readonly quotaProject?: string;
  readonly profile?: string;
  readonly accessKeyId?: string;
  readonly roleArn?: string;
  readonly externalId?: string;
  readonly service?: string;
  readonly region?: string;
}

const NO_SIGN_IN_OPTIONS: McpSignInOptions = { clientId: '', scopes: '', callbackPort: '' };

const SERVER_NAME = /^[a-z][a-z0-9-]{0,63}$/;
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/;
const SERVER_URL = /^(https:\/\/[^\s/]+|http:\/\/(127\.0\.0\.1|localhost)[:/])/i;
const CLIENT_ID = /^[\x21-\x7E]{1,512}$/;
const SCOPES = /^[\x21\x23-\x5B\x5D-\x7E]+( [\x21\x23-\x5B\x5D-\x7E]+)*$/;
const SERVICE_ACCOUNT = /^[a-z0-9-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$/;
const PROJECT = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;
const ACCESS_KEY_ID = /^(AKIA|ASIA)[A-Z0-9]{16}$/;
const ROLE_ARN = /^arn:aws(-cn|-us-gov)?:iam::\d{12}:role\/[\w+=,.@/-]{1,512}$/;
const EXTERNAL_ID = /^[\w+=,.@:/-]{2,1224}$/;
const AWS_SERVICE = /^[a-z0-9-]{1,64}$/;
const AWS_REGION = /^[a-z]{2}(-[a-z]+)+-\d+$/;
/** A part of a registry command left for the person: `<workspace>`, `{token}`. */
const PLACEHOLDER = /<[A-Za-z_][\w.-]*>|\{[A-Za-z_]\w*\}/g;

/**
 * The request "Review and start" sends (`POST /api/mcp-servers/review`), or what keeps the form from being sent. The
 * command becomes argv (quotes only, never a shell); each variable (or header) is a name whose value the review asks
 * for; rows left empty are skipped, a name used twice is refused.
 */
export function mcpRequest(
  name: string,
  transport: 'stdio' | 'http',
  command: string,
  url: string,
  variables: readonly McpVariableDraft[],
  signIn: McpSignInOptions = NO_SIGN_IN_OPTIONS,
  fixedHeaders: readonly McpFixedHeader[] = [],
): { readonly source: McpServerSource; readonly errors: null } | { readonly source: null; readonly errors: McpFormErrors } {
  const errors: { -readonly [K in keyof McpFormErrors]?: string } = {};
  const n = name.trim();
  if (!n) errors.name = 'Name the server: lowercase letters, digits and -.';
  else if (!SERVER_NAME.test(n)) errors.name = 'Use lowercase letters, digits and -, starting with a letter.';
  let argv: string[] = [];
  if (transport === 'stdio') {
    const t = tokenizeCommand(command);
    const blanks = [...new Set(t.argv.flatMap((a) => a.match(PLACEHOLDER) ?? []))];
    if (t.error) errors.command = t.error;
    else if (!t.argv.length) errors.command = 'Enter the command that starts the server.';
    else if (blanks.length) errors.command = `Replace ${joinAnd(blanks)} in the command with your own ${blanks.length === 1 ? 'value' : 'values'}.`;
    argv = t.argv;
  } else if (!url.trim()) {
    errors.url = 'Enter the URL of the server.';
  } else if (!SERVER_URL.test(url.trim())) {
    errors.url = 'Use an HTTPS URL, starting with https://.';
  } else if (templateParts(url).length) {
    const blanks = templateParts(url);
    errors.url = `Replace ${joinAnd(blanks)} in the URL with your own ${blanks.length === 1 ? 'value' : 'values'}.`;
  }
  const bindings: { name: string }[] = [];
  const seen = new Set<string>();
  for (const v of variables) {
    const vn = v.name.trim();
    if (!vn) continue;
    if (!(transport === 'http' ? HEADER_NAME : ENV_NAME).test(vn)) {
      errors.variables =
        transport === 'http' ? `${vn} isn't a header name: letters, digits and -.` : `${vn} isn't a variable name: letters, digits and _, not starting with a digit.`;
      break;
    }
    // Header names don't depend on case; variable names do.
    const key = transport === 'http' ? vn.toLowerCase() : vn;
    if (seen.has(key)) {
      errors.variables = `${vn} is named twice. Give each ${transport === 'http' ? 'header' : 'variable'} once.`;
      break;
    }
    seen.add(key);
    bindings.push({ name: vn });
  }
  let auth: McpServerAuthConfig | undefined;
  if (transport === 'http') {
    const way = signIn.with ?? 'oauth';
    const scopes = signIn.scopes.trim().replace(/\s+/g, ' ');
    if (way !== 'bearer' && scopes && !SCOPES.test(scopes)) errors.scopes = 'Separate scopes with spaces; a scope has no quotes or backslashes.';
    const keyed = bindings.some((b) => b.name.toLowerCase() === 'authorization');
    if (way === 'bearer') {
      if (!keyed) bindings.push({ name: 'Authorization' });
    } else if (way === 'google') {
      auth = googleAuth(signIn, scopes, errors);
      if (!errors.url && !googleApiUrl(url.trim())) {
        errors.url = 'Google Cloud credentials go only to Google’s own servers (https://<service>.googleapis.com). Sign in to this one another way.';
      }
      if (keyed) errors.variables = 'Google Cloud credentials send the Authorization header. Remove that header, or sign in another way.';
    } else if (way === 'aws') {
      auth = awsAuth(signIn, url.trim(), !errors.url, errors);
      if (keyed) errors.variables = 'AWS credentials send the Authorization header. Remove that header, or sign in another way.';
    } else {
      const clientId = signIn.clientId.trim();
      const port = signIn.callbackPort.trim();
      if (clientId && !CLIENT_ID.test(clientId)) errors.clientId = 'A client ID has no spaces or control characters.';
      if (port && (!/^\d{4,5}$/.test(port) || +port < 1024 || +port > 65535)) errors.callbackPort = 'Use a port from 1024 to 65535.';
      if (keyed && clientId) {
        errors.variables = 'The OAuth sign-in sends the Authorization header. Remove that header, or the client ID.';
      }
      if (clientId || scopes || port) {
        // Options the person chose mean an OAuth sign-in; without any, the review finds out whether it needs one.
        auth = { type: 'oauth', ...(clientId ? { clientId } : {}), ...(scopes ? { scopes } : {}), ...(port ? { callbackPort: +port } : {}) };
      }
    }
  }
  // A verified profile's switches (X-MCP-Readonly: true): sent as written, so never a credential or a secret's name.
  const fixed = transport === 'http' ? fixedHeaders.map((h) => ({ name: h.name.trim(), text: h.text })) : [];
  for (const h of fixed) {
    const key = h.name.toLowerCase();
    if (!HEADER_NAME.test(h.name)) errors.fixedHeaders = `${h.name} isn't a header name: letters, digits and -.`;
    else if (['authorization', 'proxy-authorization', 'cookie'].includes(key)) errors.fixedHeaders = `${h.name} carries a credential: add it as a header whose value the review asks for.`;
    else if (bindings.some((b) => b.name.toLowerCase() === key)) errors.fixedHeaders = `${h.name} is also a header whose value the review asks for. Keep one.`;
    if (errors.fixedHeaders) break;
  }
  if (Object.keys(errors).length) return { source: null, errors };
  const config: McpServerConfig =
    transport === 'stdio'
      ? { name: n, transport, command: argv, env: bindings }
      : { name: n, transport, url: url.trim(), headers: bindings, ...(fixed.length ? { fixedHeaders: fixed } : {}), ...(auth ? { auth } : {}) };
  return { source: { config }, errors: null };
}

/** Google Cloud credentials (§7.6): a token from Google sign-in on this computer or a service account key. */
function googleAuth(o: McpSignInOptions, scopes: string, errors: { -readonly [K in keyof McpFormErrors]?: string }): McpServerAuthConfig {
  const source = o.source === 'serviceAccountKey' ? 'serviceAccountKey' : 'adc';
  const impersonate = (o.impersonate ?? '').trim();
  const quotaProject = (o.quotaProject ?? '').trim();
  if (impersonate && !SERVICE_ACCOUNT.test(impersonate)) errors.impersonate = 'A service account looks like name@project.iam.gserviceaccount.com.';
  if (quotaProject && !PROJECT.test(quotaProject)) errors.quotaProject = 'A project ID has 6 to 30 lowercase letters, digits and -.';
  return {
    type: 'google',
    credentials: { source, ...(impersonate ? { impersonate } : {}), ...(quotaProject ? { quotaProject } : {}) },
    ...(scopes ? { scopes } : {}),
  };
}

/** AWS credentials (§7.6): SigV4 with a profile of this computer or an access key, for a service in a region. */
function awsAuth(o: McpSignInOptions, url: string, urlOk: boolean, errors: { -readonly [K in keyof McpFormErrors]?: string }): McpServerAuthConfig {
  const source = o.source === 'accessKey' ? 'accessKey' : 'profile';
  const profile = (o.profile ?? '').trim() || 'default';
  const accessKeyId = (o.accessKeyId ?? '').trim();
  const roleArn = (o.roleArn ?? '').trim();
  const externalId = (o.externalId ?? '').trim();
  const service = (o.service ?? '').trim();
  const region = (o.region ?? '').trim();
  if (source === 'accessKey' && !ACCESS_KEY_ID.test(accessKeyId)) {
    errors.accessKeyId = accessKeyId ? 'An access key ID looks like AKIA… (20 characters).' : 'Enter the access key ID.';
  }
  if (source === 'profile' && profile.length > 128) errors.profile = 'A profile name has at most 128 characters.';
  if (roleArn && !ROLE_ARN.test(roleArn)) errors.roleArn = 'A role ARN looks like arn:aws:iam::123456789012:role/name.';
  if (externalId && !EXTERNAL_ID.test(externalId)) errors.externalId = 'An external ID has 2 to 1,224 letters, digits and +=,.@:/-.';
  if (service && !AWS_SERVICE.test(service)) errors.service = 'A service name has lowercase letters, digits and -.';
  if (region && !AWS_REGION.test(region)) errors.region = 'A region looks like us-east-1.';
  if (urlOk && !errors.service && !errors.region && !signingTarget({ service, region }, url)) {
    errors.service = 'Name the service and region to sign for: the URL doesn’t say, as aws-mcp.us-east-1.api.aws does.';
  }
  return {
    type: 'aws',
    credentials: {
      source,
      ...(source === 'profile' ? { profile } : { accessKeyId }),
      ...(roleArn ? { roleArn } : {}),
      ...(externalId ? { externalId } : {}),
    },
    ...(service ? { service } : {}),
    ...(region ? { region } : {}),
  };
}

let nextId = 0;

/** "project_ref" → "Project ref", "<workspace>" → "Workspace": the label of a part the person fills in. */
export function partLabel(part: string): string {
  const bare = part.replace(/^[{<]|[}>]$/g, '').replace(/[_.-]+/g, ' ').trim();
  return bare.charAt(0).toUpperCase() + bare.slice(1);
}

/** The parts a command or URL leaves for the person, in their order: `{project_ref}`, `<workspace>`. */
export function fillParts(text: string): string[] {
  return [...new Set(text.match(PLACEHOLDER) ?? [])];
}

/** The command or URL with each part that has a value put in. */
export function withParts(text: string, values: Readonly<Record<string, string>>): string {
  return text.replace(PLACEHOLDER, (part) => values[part]?.trim() || part);
}

/** Where the filled-in setup came from, as Configure's first line says it. */
interface Origin {
  readonly kind: 'profile' | 'registry' | 'paste' | 'catalog';
  readonly title: string;
  readonly body: string;
  readonly notes: readonly string[];
}

/**
 * `<form gwMcpConfigure>` (kit `connection-new`, step 2 of an MCP server): Add MCP server's fields as the wizard's
 * Configure. Server: its name, which prefixes the tool names, and its environment. How GateRook reaches it: a stdio
 * command or an HTTP URL, with a field for each part the person fills in (`{project_ref}`). Sign-in: OAuth, a Bearer
 * token, Google Cloud or AWS credentials. The variables or headers whose values the review asks for. A card of the
 * picker fills it (`load`): a verified profile, a registry entry, a paste; External MCP server starts empty, with a
 * field for a configuration from the docs; a signed catalog entry needs only its environment. Nothing runs here:
 * Continue asks the core what would run (`POST /api/mcp-servers/review`) and the wizard shows that review.
 */
@Component({
  selector: 'form[gwMcpConfigure]',
  imports: [GwIcon, GwToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-form gw-form--sticky-actions gw-connection-form', novalidate: '', '(submit)': 'submit($event)' },
  template: `
    @if (origin(); as o) {
      <div class="gw-alert gw-alert--compact" [class]="o.kind === 'profile' || o.kind === 'catalog' ? 'gw-alert--success' : 'gw-alert--info'" [id]="uid + '-origin'">
        <svg [lucideIcon]="o.kind === 'profile' || o.kind === 'catalog' ? 'badge-check' : o.kind === 'paste' ? 'file-code' : 'library'" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content">
          <p class="gw-alert__title">{{ o.title }}</p>
          <p class="gw-alert__body">{{ o.body }}</p>
          @for (note of o.notes; track $index) {<p class="gw-alert__body">{{ note }}</p>}
        </div>
      </div>
    }
    @if (manual()) {
      <div class="gw-stack gw-stack--sm">
        <div><button class="gw-btn gw-btn--ghost gw-btn--sm gw-connection-form__toggle" type="button" gwToggle [attr.aria-controls]="uid + '-paste'"><svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-connection-form__when-closed"></svg><svg lucideIcon="chevron-up" class="gw-icon gw-icon--sm gw-connection-form__when-open"></svg>Paste a configuration from the server’s docs</button></div>
        <div class="gw-field" [id]="uid + '-paste'" hidden>
          <label class="gw-field__label" [for]="uid + '-paste-text'">Configuration</label>
          <textarea class="gw-textarea gw-textarea--code" [id]="uid + '-paste-text'" rows="5" spellcheck="false" autocapitalize="off" autocomplete="off" placeholder='{ "mcpServers": { "linear": { "url": "https://mcp.linear.app/mcp" } } }' (input)="paste(value($event))" [attr.aria-invalid]="pasteError() ? 'true' : null" [attr.aria-describedby]="uid + '-paste-hint' + (pasteError() ? ' ' + uid + '-paste-err' : '') + (pasted() ? ' ' + uid + '-paste-done' : '')"></textarea>
          <p class="gw-field__hint" [id]="uid + '-paste-hint'">What the docs give for Claude Desktop, Cursor, VS Code or Codex, a <span class="gw-mono">claude mcp add</span> or <span class="gw-mono">codex mcp add</span> command, or the URL. It fills the fields below and isn’t kept; keys and tokens in it aren’t copied.</p>
          @if (pasteError()) {<p class="gw-field__error" [id]="uid + '-paste-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ pasteError() }}</p>}
          @if (pasted(); as p) {<p class="gw-field__hint" [id]="uid + '-paste-done'" role="status"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Filled in {{ p }}</p>}
        </div>
      </div>
    }
    @if (error()) {
      <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert" [id]="uid + '-error'" tabindex="-1">
        <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content"><p class="gw-alert__title">The server wasn’t reviewed</p><p class="gw-alert__body">{{ error() }}</p></div>
      </div>
    }

    <fieldset class="gw-form__section" [disabled]="busy()">
      <legend class="gw-form__legend">Server</legend>
      @if (catalog(); as c) {
        <p class="gw-form__description">It is named <span class="gw-mono">{{ c.id }}</span> after its catalog entry, and its name prefixes its tool names.</p>
      } @else {
        <p class="gw-form__description">Its name prefixes its tool names, and Activity filters by <span class="gw-mono">connection:{{ name().trim() || 'its-name' }}</span>.</p>
      }
      <div class="gw-form__rows">
        @if (!catalog()) {
          <div class="gw-field">
            <label class="gw-field__label" [for]="uid + '-name'">Name</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-name'" type="text" required placeholder="sentry-mcp" spellcheck="false" autocomplete="off" [value]="name()" (input)="name.set(value($event)); suggested.set(null); clear('name')" [attr.aria-invalid]="errors().name ? 'true' : null" [attr.aria-describedby]="uid + '-name-hint' + (errors().name ? ' ' + uid + '-name-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-name-hint'">Lowercase letters, digits and <span class="gw-mono">-</span>, starting with a letter.</p>
            @if (errors().name) {<p class="gw-field__error" [id]="uid + '-name-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().name }}</p>}
          </div>
        }
        <fieldset class="gw-field" [attr.aria-describedby]="uid + '-env-hint'">
          <legend class="gw-field__label">Environment</legend>
          <div class="gw-form__row">
            @for (e of envs; track e.id) {
              <label class="gw-choice-card gw-choice-card--compact">
                <input class="gw-choice-card__input" type="radio" [name]="uid + '-env'" [value]="e.id" [checked]="environment() === e.id" (change)="environment.set(e.id)" [attr.aria-labelledby]="uid + '-env-' + e.id + '-t'" [attr.aria-describedby]="uid + '-env-' + e.id + '-d'">
                <span class="gw-choice-card__title" [id]="uid + '-env-' + e.id + '-t'"><span [class]="'gw-env gw-env--' + e.id">{{ e.id }}</span> {{ e.title }}</span>
                <span class="gw-choice-card__description" [id]="uid + '-env-' + e.id + '-d'">{{ e.description }}</span>
              </label>
            }
          </div>
          <p class="gw-field__hint" [id]="uid + '-env-hint'">The marker approvers and agents see next to its tools. A prod server goes only on prod endpoints.</p>
        </fieldset>
      </div>
    </fieldset>

    @if (!catalog()) {
      <fieldset class="gw-form__section" [disabled]="busy()">
        <legend class="gw-form__legend">How GateRook reaches it</legend>
        <p class="gw-form__description">A command GateRook starts on this computer, or a URL it calls with Streamable HTTP or the older SSE transport.</p>
        <div class="gw-form__rows">
          <fieldset class="gw-field" [attr.aria-describedby]="uid + '-transport-hint'">
            <legend class="gw-field__label">Transport</legend>
            <div class="gw-segmented">
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="stdio" [checked]="transport() === 'stdio'" (change)="setTransport('stdio')"><span class="gw-segmented__label"><svg lucideIcon="terminal" class="gw-icon gw-icon--sm"></svg>stdio</span></label>
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="http" [checked]="transport() === 'http'" (change)="setTransport('http')"><span class="gw-segmented__label"><svg lucideIcon="globe" class="gw-icon gw-icon--sm"></svg>HTTP</span></label>
            </div>
            <p class="gw-field__hint" [id]="uid + '-transport-hint'">stdio: GateRook starts a command on this computer. HTTP: it calls a URL and signs in for you when the server asks.</p>
          </fieldset>
          @if (transport() === 'stdio') {
            <div class="gw-field">
              <label class="gw-field__label" [for]="uid + '-command'">Command</label>
              <input class="gw-input gw-input--mono" [id]="uid + '-command'" type="text" required placeholder="npx -y @scope/mcp-server@1.2.3" spellcheck="false" autocomplete="off" [value]="command()" (input)="command.set(value($event)); clear('command')" [attr.aria-invalid]="errors().command ? 'true' : null" [attr.aria-describedby]="uid + '-command-hint' + (errors().command ? ' ' + uid + '-command-err' : '')">
              <p class="gw-field__hint" [id]="uid + '-command-hint'">Runs without a shell, from <span class="gw-mono">~/.gaterook/mcp/</span> and the server name. Pin a version (<span class="gw-mono">&#64;1.2.3</span>): without one, npx can fetch new code on every start.</p>
              @if (errors().command) {<p class="gw-field__error" [id]="uid + '-command-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().command }}</p>}
            </div>
          } @else {
            <div class="gw-field">
              <label class="gw-field__label" [for]="uid + '-url'">URL</label>
              <input class="gw-input gw-input--mono" [id]="uid + '-url'" type="url" required placeholder="https://mcp.linear.app/mcp" spellcheck="false" autocomplete="off" [value]="url()" (input)="setUrl(value($event))" [attr.aria-invalid]="errors().url ? 'true' : null" [attr.aria-describedby]="uid + '-url-hint' + (errors().url ? ' ' + uid + '-url-err' : '')">
              <p class="gw-field__hint" [id]="uid + '-url-hint'">An HTTPS address. A server that asks for a sign-in gets one in your browser after the review.</p>
              @if (errors().url) {<p class="gw-field__error" [id]="uid + '-url-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().url }}</p>}
            </div>
          }
          @for (part of parts(); track part; let i = $index) {
            <div class="gw-field">
              <label class="gw-field__label" [for]="uid + '-part-' + i">{{ partLabel(part) }}</label>
              <input class="gw-input gw-input--mono" [id]="uid + '-part-' + i" type="text" required spellcheck="false" autocomplete="off" [value]="partValues()[part] ?? ''" (input)="setPart(part, value($event))" [attr.aria-invalid]="partErrors().includes(part) ? 'true' : null" [attr.aria-describedby]="uid + '-part-hint-' + i + (partErrors().includes(part) ? ' ' + uid + '-part-err-' + i : '')">
              <p class="gw-field__hint" [id]="uid + '-part-hint-' + i">Goes into <span class="gw-mono">{{ part }}</span> of the {{ transport() === 'http' ? 'URL' : 'command' }}.@if (partHints()[part]; as hint) { {{ hint }}}</p>
              @if (partErrors().includes(part)) {<p class="gw-field__error" [id]="uid + '-part-err-' + i"><svg lucideIcon="circle-alert" class="gw-icon"></svg>Enter the {{ partLabel(part).toLowerCase() }}.</p>}
            </div>
          }
        </div>
      </fieldset>

      @if (transport() === 'http') {
        <section class="gw-form__section" [attr.aria-labelledby]="uid + '-signin-t'">
          <header class="gw-form__section-header">
            <h3 class="gw-form__legend" [id]="uid + '-signin-t'">Sign-in</h3>
            <p class="gw-form__description">How GateRook signs in to the server. Your AI apps never see the sign-in or its tokens.</p>
          </header>
          <div class="gw-form__rows">
            <fieldset class="gw-field" [disabled]="busy()" [attr.aria-describedby]="uid + '-with-hint'">
              <legend class="gw-field__label">Sign in with</legend>
              <div class="gw-segmented">
                <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-with'" value="oauth" [id]="uid + '-with-oauth'" [checked]="signInWith() === 'oauth'" (change)="setSignInWith('oauth')"><span class="gw-segmented__label"><svg lucideIcon="log-in" class="gw-icon gw-icon--sm"></svg>OAuth</span></label>
                <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-with'" value="bearer" [id]="uid + '-with-bearer'" [checked]="signInWith() === 'bearer'" (change)="setSignInWith('bearer')"><span class="gw-segmented__label"><svg lucideIcon="key-round" class="gw-icon gw-icon--sm"></svg>Bearer token</span></label>
                <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-with'" value="google" [id]="uid + '-with-google'" [checked]="signInWith() === 'google'" (change)="setSignInWith('google')"><span class="gw-segmented__label"><svg lucideIcon="cloud" class="gw-icon gw-icon--sm"></svg>Google Cloud</span></label>
                <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-with'" value="aws" [id]="uid + '-with-aws'" [checked]="signInWith() === 'aws'" (change)="setSignInWith('aws')"><span class="gw-segmented__label"><svg lucideIcon="cloud" class="gw-icon gw-icon--sm"></svg>AWS</span></label>
              </div>
              <p class="gw-field__hint" [id]="uid + '-with-hint'">@switch (signInWith()) {
                @case ('bearer') {Enter just your token in the review. GateRook adds the <span class="gw-mono">Authorization: Bearer</span> header automatically and stores it encrypted. No browser sign-in is needed.}
                @case ('google') {Google’s MCP servers (<span class="gw-mono">https://&lt;service&gt;.googleapis.com/mcp</span>) take a token of your Google Cloud credentials. GateRook gets it and renews it: no OAuth app, no browser.}
                @case ('aws') {MCP servers on AWS (<span class="gw-mono">https://aws-mcp.&lt;region&gt;.api.aws/mcp</span>, Bedrock AgentCore) take requests signed with SigV4. No browser sign-in that ends after 12 hours.}
                @default {Sign in in your browser if the server asks. Use OAuth app options only if the provider requires your own app or a specific callback address.}
              }</p>
            </fieldset>
            @switch (signInWith()) {
              @case ('bearer') {}
              @case ('google') {
                <fieldset class="gw-field" [disabled]="busy()">
                  <legend class="gw-field__label">Credentials</legend>
                  <div class="gw-radio">
                    <input class="gw-radio__input" type="radio" [name]="uid + '-gcp'" [id]="uid + '-gcp-adc'" value="adc" [checked]="gcpSource() === 'adc'" (change)="gcpSource.set('adc')" [attr.aria-describedby]="uid + '-gcp-adc-hint'">
                    <span class="gw-radio__control" aria-hidden="true"></span>
                    <label class="gw-radio__label" [for]="uid + '-gcp-adc'">Google sign-in on this computer</label>
                    <p class="gw-radio__hint" [id]="uid + '-gcp-adc-hint'">What <span class="gw-mono">gcloud auth application-default login</span> set up. The server’s tools act as you.</p>
                  </div>
                  <div class="gw-radio">
                    <input class="gw-radio__input" type="radio" [name]="uid + '-gcp'" [id]="uid + '-gcp-key'" value="serviceAccountKey" [checked]="gcpSource() === 'serviceAccountKey'" (change)="gcpSource.set('serviceAccountKey')" [attr.aria-describedby]="uid + '-gcp-key-hint'">
                    <span class="gw-radio__control" aria-hidden="true"></span>
                    <label class="gw-radio__label" [for]="uid + '-gcp-key'">Service account key (JSON)</label>
                    <p class="gw-radio__hint" [id]="uid + '-gcp-key-hint'">The review asks for it. It is stored encrypted as a secret of this server and never shown again.</p>
                  </div>
                </fieldset>
                <div class="gw-field">
                  <label class="gw-field__label" [for]="uid + '-impersonate'">Act as a service account <span class="gw-field__optional">Optional</span></label>
                  <input class="gw-input gw-input--mono" [id]="uid + '-impersonate'" type="text" spellcheck="false" autocomplete="off" placeholder="gw-mcp@acme.iam.gserviceaccount.com" [value]="impersonate()" (input)="impersonate.set(value($event)); clear('impersonate')" [attr.aria-invalid]="errors().impersonate ? 'true' : null" [attr.aria-describedby]="uid + '-impersonate-hint' + (errors().impersonate ? ' ' + uid + '-impersonate-err' : '')">
                  <p class="gw-field__hint" [id]="uid + '-impersonate-hint'">A short-lived token of this service account, with only its roles. It needs <span class="gw-mono">roles/mcp.toolUser</span> and the roles of the product.</p>
                  @if (errors().impersonate) {<p class="gw-field__error" [id]="uid + '-impersonate-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().impersonate }}</p>}
                </div>
                <div class="gw-field">
                  <label class="gw-field__label" [for]="uid + '-quota'">Billed project <span class="gw-field__optional">Optional</span></label>
                  <input class="gw-input gw-input--mono" [id]="uid + '-quota'" type="text" spellcheck="false" autocomplete="off" placeholder="acme-prod" [value]="quotaProject()" (input)="quotaProject.set(value($event)); clear('quotaProject')" [attr.aria-invalid]="errors().quotaProject ? 'true' : null" [attr.aria-describedby]="uid + '-quota-hint' + (errors().quotaProject ? ' ' + uid + '-quota-err' : '')">
                  <p class="gw-field__hint" [id]="uid + '-quota-hint'">Sent as <span class="gw-mono">x-goog-user-project</span>. Default: the project of your sign-in.</p>
                  @if (errors().quotaProject) {<p class="gw-field__error" [id]="uid + '-quota-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().quotaProject }}</p>}
                </div>
                <div class="gw-field">
                  <label class="gw-field__label" [for]="uid + '-scopes'">Scopes <span class="gw-field__optional">Optional</span></label>
                  <input class="gw-input gw-input--mono" [id]="uid + '-scopes'" type="text" spellcheck="false" autocomplete="off" placeholder="https://www.googleapis.com/auth/cloud-platform" [value]="scopes()" (input)="scopes.set(value($event)); clear('scopes')" [attr.aria-invalid]="errors().scopes ? 'true' : null" [attr.aria-describedby]="uid + '-scopes-hint' + (errors().scopes ? ' ' + uid + '-scopes-err' : '')">
                  <p class="gw-field__hint" [id]="uid + '-scopes-hint'">Default: <span class="gw-mono">cloud-platform</span>, which every Google Cloud MCP server takes.</p>
                  @if (errors().scopes) {<p class="gw-field__error" [id]="uid + '-scopes-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().scopes }}</p>}
                </div>
              }
              @case ('aws') {
                <fieldset class="gw-field" [disabled]="busy()">
                  <legend class="gw-field__label">Credentials</legend>
                  <div class="gw-radio">
                    <input class="gw-radio__input" type="radio" [name]="uid + '-aws'" [id]="uid + '-aws-profile'" value="profile" [checked]="awsSource() === 'profile'" (change)="awsSource.set('profile')" [attr.aria-describedby]="uid + '-aws-profile-hint'">
                    <span class="gw-radio__control" aria-hidden="true"></span>
                    <label class="gw-radio__label" [for]="uid + '-aws-profile'">A profile on this computer</label>
                    <p class="gw-radio__hint" [id]="uid + '-aws-profile-hint'">From <span class="gw-mono">~/.aws/credentials</span> and <span class="gw-mono">~/.aws/config</span>.</p>
                  </div>
                  <div class="gw-radio">
                    <input class="gw-radio__input" type="radio" [name]="uid + '-aws'" [id]="uid + '-aws-key'" value="accessKey" [checked]="awsSource() === 'accessKey'" (change)="awsSource.set('accessKey')" [attr.aria-describedby]="uid + '-aws-key-hint'">
                    <span class="gw-radio__control" aria-hidden="true"></span>
                    <label class="gw-radio__label" [for]="uid + '-aws-key'">An access key</label>
                    <p class="gw-radio__hint" [id]="uid + '-aws-key-hint'">The review asks for its secret. It is stored encrypted as a secret of this server and never shown again.</p>
                  </div>
                </fieldset>
                @if (awsSource() === 'profile') {
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-profile'">Profile <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-profile'" type="text" spellcheck="false" autocomplete="off" placeholder="default" [value]="profile()" (input)="profile.set(value($event)); clear('profile')" [attr.aria-invalid]="errors().profile ? 'true' : null" [attr.aria-describedby]="errors().profile ? uid + '-profile-err' : null">
                    @if (errors().profile) {<p class="gw-field__error" [id]="uid + '-profile-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().profile }}</p>}
                  </div>
                } @else {
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-key-id'">Access key ID</label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-key-id'" type="text" spellcheck="false" autocomplete="off" placeholder="AKIA…" [value]="accessKeyId()" (input)="accessKeyId.set(value($event)); clear('accessKeyId')" [attr.aria-invalid]="errors().accessKeyId ? 'true' : null" [attr.aria-describedby]="errors().accessKeyId ? uid + '-key-id-err' : null">
                    @if (errors().accessKeyId) {<p class="gw-field__error" [id]="uid + '-key-id-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().accessKeyId }}</p>}
                  </div>
                }
                <div class="gw-field">
                  <label class="gw-field__label" [for]="uid + '-role'">Assume a role <span class="gw-field__optional">Optional</span></label>
                  <input class="gw-input gw-input--mono" [id]="uid + '-role'" type="text" spellcheck="false" autocomplete="off" placeholder="arn:aws:iam::123456789012:role/gw-mcp" [value]="roleArn()" (input)="roleArn.set(value($event)); clear('roleArn')" [attr.aria-invalid]="errors().roleArn ? 'true' : null" [attr.aria-describedby]="uid + '-role-hint' + (errors().roleArn ? ' ' + uid + '-role-err' : '')">
                  <p class="gw-field__hint" [id]="uid + '-role-hint'">Short-lived credentials of this role, renewed by GateRook. Its policy decides what the server may do in AWS.</p>
                  @if (errors().roleArn) {<p class="gw-field__error" [id]="uid + '-role-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().roleArn }}</p>}
                </div>
                <div class="gw-field">
                  <label class="gw-field__label" [for]="uid + '-external'">External ID <span class="gw-field__optional">Optional</span></label>
                  <input class="gw-input gw-input--mono" [id]="uid + '-external'" type="text" spellcheck="false" autocomplete="off" [value]="externalId()" (input)="externalId.set(value($event)); clear('externalId')" [attr.aria-invalid]="errors().externalId ? 'true' : null" [attr.aria-describedby]="errors().externalId ? uid + '-external-err' : null">
                  @if (errors().externalId) {<p class="gw-field__error" [id]="uid + '-external-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().externalId }}</p>}
                </div>
                <div class="gw-form__row">
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-service'">Service <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-service'" type="text" spellcheck="false" autocomplete="off" [placeholder]="target()?.service ?? 'aws-mcp'" [value]="service()" (input)="service.set(value($event)); clear('service')" [attr.aria-invalid]="errors().service ? 'true' : null" [attr.aria-describedby]="uid + '-service-hint' + (errors().service ? ' ' + uid + '-service-err' : '')">
                    <p class="gw-field__hint" [id]="uid + '-service-hint'">What requests are signed for. Default: from the URL, <span class="gw-mono">aws-mcp.us-east-1.api.aws</span> → <span class="gw-mono">aws-mcp</span> in <span class="gw-mono">us-east-1</span>.</p>
                    @if (errors().service) {<p class="gw-field__error" [id]="uid + '-service-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().service }}</p>}
                  </div>
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-region'">Region <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-region'" type="text" spellcheck="false" autocomplete="off" [placeholder]="target()?.region ?? 'us-east-1'" [value]="region()" (input)="region.set(value($event)); clear('region')" [attr.aria-invalid]="errors().region ? 'true' : null" [attr.aria-describedby]="errors().region ? uid + '-region-err' : null">
                    @if (errors().region) {<p class="gw-field__error" [id]="uid + '-region-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().region }}</p>}
                  </div>
                </div>
              }
              @default {
                <div><button class="gw-btn gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-signin'" [(expanded)]="signInOpen"><svg lucideIcon="sliders-horizontal" class="gw-icon gw-icon--sm"></svg> OAuth app options <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button></div>
                <div class="gw-form__rows" [id]="uid + '-signin'" [hidden]="!signInOpen()">
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client'">OAuth client ID <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client'" type="text" spellcheck="false" autocomplete="off" [value]="clientId()" (input)="clientId.set(value($event)); clear('clientId')" [attr.aria-invalid]="errors().clientId ? 'true' : null" [attr.aria-describedby]="uid + '-client-hint' + (errors().clientId ? ' ' + uid + '-client-err' : '')">
                    <p class="gw-field__hint" [id]="uid + '-client-hint'">The ID of the OAuth app you created for GateRook at the server’s provider. The review asks for its secret, if it has one.</p>
                    @if (errors().clientId) {<p class="gw-field__error" [id]="uid + '-client-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().clientId }}</p>}
                  </div>
                  <div class="gw-form__row">
                    <div class="gw-field">
                      <label class="gw-field__label" [for]="uid + '-scopes'">Scopes <span class="gw-field__optional">Optional</span></label>
                      <input class="gw-input gw-input--mono" [id]="uid + '-scopes'" type="text" spellcheck="false" autocomplete="off" placeholder="read write" [value]="scopes()" (input)="scopes.set(value($event)); clear('scopes')" [attr.aria-invalid]="errors().scopes ? 'true' : null" [attr.aria-describedby]="uid + '-scopes-hint' + (errors().scopes ? ' ' + uid + '-scopes-err' : '')">
                      <p class="gw-field__hint" [id]="uid + '-scopes-hint'">Default: what the server asks for.</p>
                      @if (errors().scopes) {<p class="gw-field__error" [id]="uid + '-scopes-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().scopes }}</p>}
                    </div>
                    <div class="gw-field">
                      <label class="gw-field__label" [for]="uid + '-port'">Callback port <span class="gw-field__optional">Optional</span></label>
                      <input class="gw-input gw-input--mono" [id]="uid + '-port'" type="text" inputmode="numeric" spellcheck="false" autocomplete="off" placeholder="7471" [value]="callbackPort()" (input)="callbackPort.set(value($event)); clear('callbackPort')" [attr.aria-invalid]="errors().callbackPort ? 'true' : null" [attr.aria-describedby]="uid + '-port-hint' + (errors().callbackPort ? ' ' + uid + '-port-err' : '')">
                      <p class="gw-field__hint" [id]="uid + '-port-hint'">Where the browser comes back to: <span class="gw-mono">http://localhost:&lt;port&gt;/callback</span>. Default: any free port.</p>
                      @if (errors().callbackPort) {<p class="gw-field__error" [id]="uid + '-port-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().callbackPort }}</p>}
                    </div>
                  </div>
                </div>
              }
            }
          </div>
        </section>
      }

      <fieldset class="gw-form__section" [disabled]="busy()">
        <legend class="gw-form__legend">{{ transport() === 'http' ? 'Headers from secrets' : 'Variables from secrets' }}</legend>
        <p class="gw-form__description" [id]="uid + '-env-desc'">Only their names here. The review asks for each value right before the first {{ transport() === 'http' ? 'connection' : 'start' }}; it is stored encrypted as a secret of this server and never shown again. Only these reach the server, and the agent never sees them.</p>
        <div class="gw-form__rows">
          @for (row of variables(); track $index; let i = $index) {
            <div class="gw-field">
              <label class="gw-field__label" [for]="uid + '-var-name-' + i">{{ transport() === 'http' ? 'Header' : 'Variable' }} name</label>
              <input class="gw-input gw-input--mono" [id]="uid + '-var-name-' + i" type="text" [placeholder]="transport() === 'http' ? (signInWith() === 'bearer' ? 'X-API-Key' : 'Authorization') : 'SENTRY_AUTH_TOKEN'" spellcheck="false" autocomplete="off" [value]="row.name" (input)="setVariable(i, value($event))" [attr.aria-describedby]="uid + '-env-desc'">
            </div>
          } @empty {
            <p class="gw-text-small gw-fg-muted">None. {{ transport() === 'http' ? 'The sign-in carries the access.' : 'The process gets PATH, HOME and basic user variables only.' }}</p>
          }
          @if (errors().variables) {<p class="gw-field__error" [id]="uid + '-var-err'" role="alert"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().variables }}</p>}
          <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" (click)="addVariable()"><svg lucideIcon="plus" class="gw-icon gw-icon--sm"></svg>{{ transport() === 'http' ? 'Add header' : 'Add variable' }}</button></div>
          @if (transport() === 'http' && fixedHeaders().length) {
            <!-- A verified profile's switches (X-MCP-Readonly: true): sent as written, not secrets. Dropping one makes the review warn. -->
            <fieldset class="gw-field" [attr.aria-describedby]="uid + '-fixed-hint'">
              <legend class="gw-field__label">Headers with a fixed value</legend>
              <ul class="gw-stack gw-stack--sm">
                @for (h of fixedHeaders(); track h.name; let i = $index) {
                  <li><code class="gw-code">{{ h.name }}: {{ h.text }}</code> <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [id]="uid + '-fixed-' + i" (click)="removeFixedHeader(i)">Remove<span class="gw-sr-only"> {{ h.name }}</span></button></li>
                }
              </ul>
              <p class="gw-field__hint" [id]="uid + '-fixed-hint'">Sent with every request as written here, not as secrets. Change them later in the server’s settings.</p>
              @if (errors().fixedHeaders) {<p class="gw-field__error" role="alert"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().fixedHeaders }}</p>}
            </fieldset>
          }
        </div>
      </fieldset>
    }

    <div class="gw-form__actions">
      <p class="gw-form__note"><svg lucideIcon="shield-check" class="gw-icon"></svg>Nothing runs before the review</p>
      <button class="gw-btn gw-btn--ghost" type="button" (click)="back.emit()" [attr.aria-disabled]="busy() ? 'true' : null"><svg lucideIcon="arrow-left" class="gw-icon"></svg> Back</button>
      <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Continue<span class="gw-sr-only"> to the review</span> <svg lucideIcon="arrow-right" class="gw-icon"></svg></button>
    </div>
  `,
})
export class GwMcpConfigure {
  private readonly data = inject(ConnectorsData);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Continue: the core reviewed the server; the wizard shows its review next. */
  readonly reviewed = output<McpReviewed>();
  /** Back to the picker. */
  readonly back = output<void>();

  protected readonly uid = `ma-${++nextId}`;
  protected readonly partLabel = partLabel;
  protected readonly envs: readonly { readonly id: Env; readonly title: string; readonly description: string }[] = [
    { id: 'dev', title: 'Development', description: 'Your own work and test accounts.' },
    { id: 'stage', title: 'Staging', description: 'Shared test systems.' },
    { id: 'prod', title: 'Production', description: 'Real data and accounts.' },
  ];
  readonly name = signal('');
  readonly environment = signal<Env>('dev');
  protected readonly transport = signal<'stdio' | 'http'>('stdio');
  protected readonly command = signal('');
  protected readonly url = signal('');
  protected readonly variables = signal<readonly McpVariableDraft[]>([]);
  /** A verified profile's headers with a fixed value (its read-only switches): sent as written, never asked for. */
  protected readonly fixedHeaders = signal<readonly McpFixedHeader[]>([]);
  protected readonly clientId = signal('');
  protected readonly scopes = signal('');
  protected readonly callbackPort = signal('');
  protected readonly signInOpen = signal(false);
  /** How GateRook signs in to a server at a URL: OAuth (the default), a Bearer token, or a cloud's credentials. */
  protected readonly signInWith = signal<'oauth' | 'bearer' | 'google' | 'aws'>('oauth');
  protected readonly gcpSource = signal<'adc' | 'serviceAccountKey'>('adc');
  protected readonly impersonate = signal('');
  protected readonly quotaProject = signal('');
  protected readonly awsSource = signal<'profile' | 'accessKey'>('profile');
  protected readonly profile = signal('');
  protected readonly accessKeyId = signal('');
  protected readonly roleArn = signal('');
  protected readonly externalId = signal('');
  protected readonly service = signal('');
  protected readonly region = signal('');
  /** The service and region an AWS URL signs for (aws-mcp.us-east-1.api.aws → aws-mcp in us-east-1): the placeholders. */
  protected readonly target = computed(() => signingTarget(null, this.url().trim()));
  protected readonly errors = signal<McpFormErrors>({});
  protected readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  /** The name a URL suggested; typing another one keeps that one. */
  protected readonly suggested = signal<string | null>(null);
  protected readonly pasted = signal<string | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  /** The parts of the command or URL the person fills in, each with a field of its own. */
  protected readonly parts = computed(() => fillParts(this.transport() === 'http' ? this.url() : this.command()));
  protected readonly partValues = signal<Readonly<Record<string, string>>>({});
  /** What a profile or a registry entry says about a part: "The ID of your Supabase project." */
  protected readonly partHints = signal<Readonly<Record<string, string>>>({});
  protected readonly partErrors = signal<readonly string[]>([]);
  protected readonly origin = signal<Origin | null>(null);
  /** A signed catalog entry: only its environment is chosen here. */
  protected readonly catalog = signal<{ readonly id: string; readonly version: string; readonly name: string } | null>(null);
  /** External MCP server: the person enters it, or pastes its configuration. */
  protected readonly manual = signal(false);

  /** Starts over from a card of the picker; nothing runs before the review. */
  load(choice: McpChoice, env: Env = 'dev'): void {
    this.name.set('');
    this.environment.set(env);
    this.transport.set(choice.origin === 'external' && choice.transport === 'http' ? 'http' : 'stdio');
    this.command.set('');
    this.url.set('');
    this.variables.set([]);
    this.fixedHeaders.set([]);
    this.clientId.set('');
    this.scopes.set('');
    this.callbackPort.set('');
    this.signInOpen.set(false);
    this.signInWith.set('oauth');
    this.gcpSource.set('adc');
    this.impersonate.set('');
    this.quotaProject.set('');
    this.awsSource.set('profile');
    this.profile.set('');
    this.accessKeyId.set('');
    this.roleArn.set('');
    this.externalId.set('');
    this.service.set('');
    this.region.set('');
    this.suggested.set(null);
    this.pasted.set(null);
    this.pasteError.set(null);
    this.partValues.set({});
    this.partHints.set({});
    this.partErrors.set([]);
    this.errors.set({});
    this.error.set(null);
    this.busy.set(false);
    this.origin.set(null);
    this.catalog.set(null);
    this.manual.set(choice.origin === 'external');
    const paste = this.host.querySelector<HTMLTextAreaElement>(`#${this.uid}-paste-text`);
    if (paste) paste.value = '';
    switch (choice.origin) {
      case 'external':
        return;
      case 'catalog':
        this.catalog.set({ id: choice.id, version: choice.version, name: choice.name });
        this.name.set(choice.id);
        this.origin.set({
          kind: 'catalog',
          title: `${choice.name} from GateRook’s signed catalog`,
          body: `Version ${choice.version.replace(/^v/, '')}. GateRook installs the package the catalog signed and runs it without a shell: there is no command to type.`,
          notes: [],
        });
        return;
      case 'profile': {
        const p = choice.profile;
        const filled = fromProfile(p);
        if ('error' in filled) {
          this.error.set(filled.error);
          return;
        }
        this.fill(filled);
        this.partHints.set(Object.fromEntries(p.placeholders.map((h) => [`{${h.name}}`, h.description])));
        this.origin.set({
          kind: 'profile',
          title: `GateRook’s verified profile of ${p.publisher}’s own server`,
          body: `Checked ${p.checked}. It sets the vendor’s read-only switches, and its tools that run code or hand work to another agent start in Approval or Off. That doesn’t verify what the server does.`,
          notes: filled.notes.filter((n) => !n.startsWith('Replace ')),
        });
        return;
      }
      case 'registry': {
        const r = choice.result;
        const filled = fromRegistry(r.server);
        if ('error' in filled) {
          this.error.set(filled.error);
          return;
        }
        this.fill(filled);
        const remote = r.use.kind === 'remote' ? r.use.remote : null;
        this.partHints.set(Object.fromEntries((remote?.variables ?? []).filter((v) => v.description).map((v) => [`{${v.name}}`, v.description!])));
        this.origin.set({
          kind: 'registry',
          title: `Filled in from ${registryLabel(choice.registry)}: ${r.title}`,
          body: `${r.server.name}${r.server.version ? ` · ${r.server.version.startsWith('v') ? r.server.version : `v${r.server.version}`}` : ''}${r.officialPublisher ? ` · official server of ${r.officialPublisher.name}` : ''}.${r.server.status === 'deprecated' ? ' Its publisher marked this entry deprecated.' : ''} Nothing in it has run or been reviewed yet.`,
          notes: filled.notes,
        });
        return;
      }
      case 'paste': {
        const what = this.fill(choice.server);
        this.origin.set({
          kind: 'paste',
          title: `Filled in from your paste: ${what}`,
          body: 'Values of keys and tokens in it weren’t copied: the review asks for each one, and GateRook stores it encrypted.',
          notes: [...(choice.server.others ? [`It named ${choice.server.others + 1} servers; this is the first. Add the others one at a time.`] : []), ...choice.server.notes],
        });
        return;
      }
    }
  }

  /** The URL as typed; an empty name (or the one suggested before) follows its host: mcp.linear.app → linear-mcp. */
  protected setUrl(url: string): void {
    this.url.set(url);
    this.clear('url');
    if (!this.name().trim() || this.name() === this.suggested()) {
      let suggestion = '';
      try {
        suggestion = serverName('', new URL(url.trim()));
      } catch {
        suggestion = '';
      }
      if (suggestion) {
        this.name.set(suggestion);
        this.suggested.set(suggestion);
        this.clear('name');
      }
    }
  }

  protected setPart(part: string, text: string): void {
    this.partValues.update((v) => ({ ...v, [part]: text }));
    if (this.partErrors().includes(part)) this.partErrors.update((e) => e.filter((x) => x !== part));
  }

  /**
   * External MCP server: a configuration pasted from the server's docs fills the fields. The text leaves the field once
   * it is read: it may hold a key, and keys are typed in the review, where they become secrets of the server.
   */
  protected paste(text: string): void {
    this.pasted.set(null);
    if (!text.trim()) {
      this.pasteError.set(null);
      return;
    }
    const r = parsePastedServer(text);
    if ('error' in r) {
      this.pasteError.set(r.error);
      return;
    }
    this.pasteError.set(null);
    const filled = this.fill(r);
    const field = this.host.querySelector<HTMLTextAreaElement>(`#${this.uid}-paste-text`);
    if (field) field.value = '';
    this.pasted.set(
      `${filled} from the configuration.` +
        (r.others ? ` It named ${r.others + 1} servers; this is the first. Add the others one at a time.` : '') +
        (r.notes.length ? ` ${r.notes.join(' ')}` : ''),
    );
  }

  /** Fills the fields from a pasted or chosen server and says what it filled: "the name, the URL and one header". */
  private fill(r: PastedServer): string {
    this.transport.set(r.transport);
    if (r.transport === 'http') this.url.set(r.url);
    else this.command.set(r.command);
    if (r.name) {
      this.name.set(r.name);
      this.suggested.set(r.name);
    }
    this.variables.set(r.variables.map((name) => ({ name })));
    this.fixedHeaders.set(r.transport === 'http' ? (r.fixedHeaders ?? []) : []);
    // A sign-in chosen for the previous server isn't this one's: a Google token or an AWS signature included.
    if (r.auth) this.applySignIn(r.auth);
    else if (this.signInWith() !== 'oauth') this.setSignInWith('oauth');
    this.errors.set({});
    const kind = r.transport === 'http' ? 'header' : 'variable';
    return joinAnd([
      ...(r.name ? ['the name'] : []),
      r.transport === 'http' ? 'the URL' : 'the command',
      ...(r.variables.length ? [r.variables.length === 1 ? `one ${kind}` : `${r.variables.length} ${kind}s`] : []),
      ...(r.transport === 'http' && r.fixedHeaders?.length ? [r.fixedHeaders.length === 1 ? 'one header with a fixed value' : `${r.fixedHeaders.length} headers with a fixed value`] : []),
    ]);
  }

  /** A sign-in the paste names (an AWS proxy's profile, Google's token header): its options, opened. */
  private applySignIn(auth: McpServerAuthConfig): void {
    if (auth.type === 'oauth') return;
    this.signInWith.set(auth.type);
    this.signInOpen.set(true);
    const c = auth.credentials;
    if (auth.type === 'google') {
      this.gcpSource.set(c?.source === 'serviceAccountKey' ? 'serviceAccountKey' : 'adc');
      this.quotaProject.set(c?.quotaProject ?? '');
      this.impersonate.set(c?.impersonate ?? '');
    } else {
      this.awsSource.set(c?.source === 'accessKey' ? 'accessKey' : 'profile');
      this.profile.set(c?.profile ?? '');
      this.service.set(auth.service ?? '');
      this.region.set(auth.region ?? '');
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected clear(field: keyof McpFormErrors): void {
    if (this.errors()[field]) this.errors.update((e) => ({ ...e, [field]: undefined }));
  }

  protected setSignInWith(way: 'oauth' | 'bearer' | 'google' | 'aws'): void {
    this.signInWith.set(way);
    this.errors.update((e) => ({ ...e, clientId: undefined, scopes: undefined, callbackPort: undefined, impersonate: undefined,
      quotaProject: undefined, profile: undefined, accessKeyId: undefined, roleArn: undefined, externalId: undefined,
      service: undefined, region: undefined, variables: undefined }));
  }

  protected setTransport(t: 'stdio' | 'http'): void {
    this.transport.set(t);
    this.errors.update((e) => ({ ...e, command: undefined, url: undefined }));
  }

  protected setVariable(i: number, name: string): void {
    this.variables.update((rows) => rows.map((r, j) => (j === i ? { name } : r)));
    this.clear('variables');
  }

  /** Drops a header with a fixed value, such as a profile's read-only switch: the review then warns that it's missing. */
  protected removeFixedHeader(i: number): void {
    this.fixedHeaders.update((rows) => rows.filter((_, j) => j !== i));
    this.clear('fixedHeaders');
  }

  protected addVariable(): void {
    this.variables.update((rows) => [...rows, { name: '' }]);
    const i = this.variables().length - 1;
    // The new row's Name takes the focus, so the keyboard continues where the row appeared.
    setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-var-name-${i}`)?.focus());
  }

  private focus(id: string): void {
    setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-${id}`)?.focus());
  }

  /** Continue: the core reviews what would run; a refused field keeps the focus, with its reason under it. */
  async submit(e?: Event): Promise<void> {
    e?.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const catalog = this.catalog();
    if (catalog) {
      await this.review({ catalog: `${catalog.id}@${catalog.version}`, env: this.environment() }, catalog.id, 'stdio', '', [], false);
      return;
    }
    const empty = this.parts().filter((p) => !this.partValues()[p]?.trim());
    if (empty.length) {
      this.partErrors.set(empty);
      this.focus(`part-${this.parts().indexOf(empty[0])}`);
      return;
    }
    const way = this.signInWith();
    const command = withParts(this.command(), this.partValues());
    const url = withParts(this.url(), this.partValues());
    const r = mcpRequest(this.name(), this.transport(), command, url, this.variables(), {
      with: way,
      clientId: this.clientId(),
      scopes: this.scopes(),
      callbackPort: this.callbackPort(),
      source: way === 'google' ? this.gcpSource() : way === 'aws' ? this.awsSource() : undefined,
      impersonate: this.impersonate(),
      quotaProject: this.quotaProject(),
      profile: this.profile(),
      accessKeyId: this.accessKeyId(),
      roleArn: this.roleArn(),
      externalId: this.externalId(),
      service: this.service(),
      region: this.region(),
    }, this.fixedHeaders());
    if (r.errors) {
      this.errors.set(r.errors);
      // The first refused field (the always-visible ones first); a sign-in option opens its section.
      const order: readonly [keyof McpFormErrors, string][] = [['name', 'name'], ['command', 'command'], ['url', 'url'],
        ['variables', 'var-name-0'], ['fixedHeaders', 'fixed-0'], ['clientId', 'client'], ['impersonate', 'impersonate'], ['quotaProject', 'quota'],
        ['scopes', 'scopes'], ['callbackPort', 'port'], ['profile', 'profile'], ['accessKeyId', 'key-id'],
        ['roleArn', 'role'], ['externalId', 'external'], ['service', 'service'], ['region', 'region']];
      const first = order.find(([key]) => r.errors[key]);
      if (first && !['name', 'command', 'url', 'variables', 'fixedHeaders'].includes(first[0])) this.signInOpen.set(true);
      this.focus(first?.[1] ?? 'name');
      return;
    }
    const variables = 'config' in r.source ? (r.source.config.headers ?? r.source.config.env ?? []) : [];
    const bearer = 'config' in r.source && r.source.config.transport === 'http' && way === 'bearer';
    await this.review({ ...r.source, env: this.environment() }, this.name().trim(), this.transport(), this.transport() === 'http' ? url.trim() : command.trim(), variables, bearer);
  }

  private async review(source: McpServerSource, name: string, transport: 'stdio' | 'http', target: string,
    variables: readonly { readonly name: string }[], bearer: boolean): Promise<void> {
    this.busy.set(true);
    try {
      const review = await this.data.reviewServer(source);
      this.reviewed.emit({
        review,
        source,
        name: review.name || name,
        transport: review.transport ?? transport,
        target,
        variables,
        bearer,
        opener: null,
      });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
      this.focus('error');
    } finally {
      this.busy.set(false);
    }
  }
}
