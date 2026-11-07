import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, output, signal, viewChild } from '@angular/core';
import { McpRegistryServer, McpServerAuthConfig, McpServerConfig, McpServerReview, McpServerSource } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { GwSearchClearButton } from '../../../kit/search';
import { GwTabs } from '../../../kit/tabs';
import { joinAnd, tokenizeCommand } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { signingTarget } from '../mcp-cloud';
import { PastedServer, parsePastedServer, serverName } from '../mcp-paste';
import { RegistryUse, fromRegistry, registryUse, templateParts } from '../mcp-registry';
import { failureText } from './failure';

/** One result of Find a server: the entry, the name it is shown under, how it would be added and where it runs. */
export interface RegistryResult {
  readonly server: McpRegistryServer;
  readonly title: string;
  readonly use: RegistryUse;
  /** The host of its URL, or the package command (`npx @sentry/mcp-server@0.42.0`). */
  readonly target: string;
}

export function registryResult(server: McpRegistryServer): RegistryResult {
  const use = registryUse(server);
  let target = '';
  if (use.kind === 'remote') {
    try {
      target = new URL(use.remote.url).host;
    } catch {
      target = use.remote.url;
    }
  } else if (use.kind === 'package') {
    target = `${use.runtime} ${use.pkg.identifier}${use.pkg.version ? `@${use.pkg.version}` : ''}`;
  }
  return { server, title: server.title || server.name.split('/')[1] || server.name, use, target };
}

/** The line under the results that says where they come from. */
const OFFICIAL_REGISTRY = 'registry.modelcontextprotocol.io';

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
    errors.url = 'Use an HTTPS URL, or http://127.0.0.1 for a server on this computer.';
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
  if (Object.keys(errors).length) return { source: null, errors };
  const config: McpServerConfig =
    transport === 'stdio'
      ? { name: n, transport, command: argv, env: bindings }
      : { name: n, transport, url: url.trim(), headers: bindings, ...(auth ? { auth } : {}) };
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

/**
 * `<dialog gwMcpAddDialog>` (kit `dlg-add-mcp`): put an MCP server behind the gateway — its name (it prefixes the
 * tool names), stdio command or HTTP URL, and the variables (or headers) whose values come from secrets. Two tabs:
 * Manual entry, with those fields and a pasted configuration, and the Official MCP Registry, whose Use fills Manual
 * entry and returns there. Nothing starts here: "Review and start" asks the core what would run
 * (`POST /api/mcp-servers/review`) and opens the review, which starts it. Focus starts on Name; each refusal stays at
 * its field.
 */
@Component({
  selector: 'dialog[gwMcpAddDialog]',
  imports: [GwIcon, GwDialogClose, GwToggle, GwSearchClearButton, GwTabs],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    // --tall: Manual entry and the registry's results differ in length; switching tabs keeps the panel still.
    class: 'gw-dialog gw-dialog--lg gw-dialog--tall',
    '[attr.aria-labelledby]': 'uid + "-t"',
    '[attr.aria-describedby]': 'uid + "-d"',
  },
  template: `
    <form method="dialog" novalidate (submit)="submit($event)">
      <header class="gw-dialog__header">
        <span class="gw-icon-tile"><svg lucideIcon="plug" class="gw-icon"></svg></span>
        <h2 class="gw-dialog__title" [id]="uid + '-t'">Add MCP server</h2>
        <p class="gw-dialog__description" [id]="uid + '-d'">Put any MCP server behind the gateway: a command on this computer, or a URL you sign in to as in Claude Desktop, Cursor or Codex. Its tools get modes, approvals and audit.</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-tabs">
          <div class="gw-tabs__list" role="tablist" aria-label="Ways to add an MCP server" gwTabs (selectedChange)="onTab($event)">
            <button class="gw-tabs__tab" type="button" role="tab" [id]="uid + '-tab-manual'" aria-selected="true" [attr.aria-controls]="uid + '-manual'"><svg lucideIcon="pencil" class="gw-icon"></svg> Manual entry</button>
            <button class="gw-tabs__tab" type="button" role="tab" [id]="uid + '-tab-registry'" aria-selected="false" [attr.aria-controls]="uid + '-registry'" tabindex="-1"><svg lucideIcon="library" class="gw-icon"></svg> Official MCP Registry</button>
          </div>
          <div class="gw-tabs__panel" role="tabpanel" [id]="uid + '-manual'" [attr.aria-labelledby]="uid + '-tab-manual'" tabindex="0">
            <div class="gw-stack">
              @if (chosen(); as c) {<p class="gw-field__hint" role="status"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Filled in {{ c }}</p>}
              <div class="gw-stack gw-stack--sm">
                <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-paste'"><svg lucideIcon="file-code" class="gw-icon gw-icon--sm"></svg> Paste a configuration from the server’s docs <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button></div>
                <div class="gw-field" [id]="uid + '-paste'" hidden>
                  <label class="gw-field__label" [for]="uid + '-paste-text'">Configuration</label>
                  <textarea class="gw-textarea gw-textarea--code" [id]="uid + '-paste-text'" rows="5" spellcheck="false" autocapitalize="off" autocomplete="off" placeholder='{ "mcpServers": { "linear": { "url": "https://mcp.linear.app/mcp" } } }' (input)="paste(value($event))" [attr.aria-invalid]="pasteError() ? 'true' : null" [attr.aria-describedby]="uid + '-paste-hint' + (pasteError() ? ' ' + uid + '-paste-err' : '') + (pasted() ? ' ' + uid + '-paste-done' : '')"></textarea>
                  <p class="gw-field__hint" [id]="uid + '-paste-hint'">What the docs give for Claude Desktop, Cursor, VS Code or Codex, a <span class="gw-mono">claude mcp add</span> or <span class="gw-mono">codex mcp add</span> command, or the URL. It fills the fields below and isn’t kept; keys and tokens in it aren’t copied.</p>
                  @if (pasteError()) {<p class="gw-field__error" [id]="uid + '-paste-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ pasteError() }}</p>}
                  @if (pasted(); as p) {<p class="gw-field__hint" [id]="uid + '-paste-done'" role="status"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Filled in {{ p }}</p>}
                </div>
              </div>
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-name'">Name</label>
                <input class="gw-input gw-input--mono" [id]="uid + '-name'" type="text" [attr.autofocus]="tab() === 'manual' ? '' : null" required placeholder="sentry-mcp" spellcheck="false" autocomplete="off" [value]="name()" (input)="name.set(value($event)); suggested.set(null); clear('name')" [attr.aria-invalid]="errors().name ? 'true' : null" [attr.aria-describedby]="uid + '-name-hint' + (errors().name ? ' ' + uid + '-name-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-name-hint'">Lowercase letters, digits and <span class="gw-mono">-</span>. It also prefixes the tool names.</p>
                @if (errors().name) {<p class="gw-field__error" [id]="uid + '-name-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().name }}</p>}
              </div>
              <fieldset class="gw-field" [attr.aria-describedby]="uid + '-transport-hint'">
                <legend class="gw-field__label">Transport</legend>
                <div class="gw-segmented">
                  <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="stdio" [checked]="transport() === 'stdio'" (change)="setTransport('stdio')"><span class="gw-segmented__label"><svg lucideIcon="terminal" class="gw-icon gw-icon--sm"></svg>stdio</span></label>
                  <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="http" [checked]="transport() === 'http'" (change)="setTransport('http')"><span class="gw-segmented__label"><svg lucideIcon="globe" class="gw-icon gw-icon--sm"></svg>HTTP</span></label>
                </div>
                <p class="gw-field__hint" [id]="uid + '-transport-hint'">stdio: the gateway starts a command on this computer. HTTP: it calls a URL with Streamable HTTP, or the older SSE transport, and signs in to it for you when the server asks.</p>
              </fieldset>
              <div class="gw-field" [hidden]="transport() !== 'stdio'">
                <label class="gw-field__label" [for]="uid + '-command'">Command</label>
                <input class="gw-input gw-input--mono" [id]="uid + '-command'" type="text" required [disabled]="transport() !== 'stdio'" placeholder="npx -y @scope/mcp-server@1.2.3" spellcheck="false" autocomplete="off" [value]="command()" (input)="command.set(value($event)); clear('command')" [attr.aria-invalid]="errors().command ? 'true' : null" [attr.aria-describedby]="uid + '-command-hint' + (errors().command ? ' ' + uid + '-command-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-command-hint'">Runs without a shell, from <span class="gw-mono">~/.gaterook/mcp/</span> and the server name. Pin a version (<span class="gw-mono">&#64;1.2.3</span>): without one, npx can fetch new code on every start.</p>
                @if (errors().command) {<p class="gw-field__error" [id]="uid + '-command-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().command }}</p>}
              </div>
              <div class="gw-field" [hidden]="transport() !== 'http'">
                <label class="gw-field__label" [for]="uid + '-url'">URL</label>
                <input class="gw-input gw-input--mono" [id]="uid + '-url'" type="url" required [disabled]="transport() !== 'http'" placeholder="https://mcp.linear.app/mcp" spellcheck="false" autocomplete="off" [value]="url()" (input)="setUrl(value($event))" [attr.aria-invalid]="errors().url ? 'true' : null" [attr.aria-describedby]="uid + '-url-hint' + (errors().url ? ' ' + uid + '-url-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-url-hint'">HTTPS, or <span class="gw-mono">http://127.0.0.1</span> for a server on this computer. A server that asks for a sign-in gets one in your browser after the review.</p>
                @if (errors().url) {<p class="gw-field__error" [id]="uid + '-url-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().url }}</p>}
              </div>
              <section class="gw-card" [hidden]="transport() !== 'http'" [attr.aria-labelledby]="uid + '-signin-title'">
                <header class="gw-card__header">
                  <div class="gw-card__heading">
                    <h3 class="gw-card__title" [id]="uid + '-signin-title'"><svg lucideIcon="key-round" class="gw-icon"></svg> Sign-in options</h3>
                    <p class="gw-card__description">Choose how GateRook authenticates with this server.</p>
                  </div>
                </header>
                <div class="gw-card__body gw-stack gw-stack--sm">
                  <fieldset class="gw-field" [attr.aria-describedby]="uid + '-with-hint'">
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
                      <fieldset class="gw-field">
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
                      <fieldset class="gw-field">
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
                    }
                    @default {
                  <div><button class="gw-btn gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-signin'" [(expanded)]="signInOpen"><svg lucideIcon="sliders-horizontal" class="gw-icon gw-icon--sm"></svg> OAuth app options <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button></div>
                  <div class="gw-stack gw-stack--sm" [id]="uid + '-signin'" [hidden]="!signInOpen()">
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client'">OAuth client ID <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client'" type="text" spellcheck="false" autocomplete="off" [disabled]="transport() !== 'http'" [value]="clientId()" (input)="clientId.set(value($event)); clear('clientId')" [attr.aria-invalid]="errors().clientId ? 'true' : null" [attr.aria-describedby]="uid + '-client-hint' + (errors().clientId ? ' ' + uid + '-client-err' : '')">
                    <p class="gw-field__hint" [id]="uid + '-client-hint'">The ID of the OAuth app you created for GateRook at the server’s provider. The review asks for its secret, if it has one.</p>
                    @if (errors().clientId) {<p class="gw-field__error" [id]="uid + '-client-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().clientId }}</p>}
                  </div>
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-scopes'">Scopes <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-scopes'" type="text" spellcheck="false" autocomplete="off" placeholder="read write" [disabled]="transport() !== 'http'" [value]="scopes()" (input)="scopes.set(value($event)); clear('scopes')" [attr.aria-invalid]="errors().scopes ? 'true' : null" [attr.aria-describedby]="uid + '-scopes-hint' + (errors().scopes ? ' ' + uid + '-scopes-err' : '')">
                    <p class="gw-field__hint" [id]="uid + '-scopes-hint'">Default: what the server asks for.</p>
                    @if (errors().scopes) {<p class="gw-field__error" [id]="uid + '-scopes-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().scopes }}</p>}
                  </div>
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-port'">Callback port <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-port'" type="text" inputmode="numeric" spellcheck="false" autocomplete="off" placeholder="7471" [disabled]="transport() !== 'http'" [value]="callbackPort()" (input)="callbackPort.set(value($event)); clear('callbackPort')" [attr.aria-invalid]="errors().callbackPort ? 'true' : null" [attr.aria-describedby]="uid + '-port-hint' + (errors().callbackPort ? ' ' + uid + '-port-err' : '')">
                    <p class="gw-field__hint" [id]="uid + '-port-hint'">Where the browser comes back to: <span class="gw-mono">http://localhost:&lt;port&gt;/callback</span>, or <span class="gw-mono">http://127.0.0.1:7471/callback</span> for an OAuth app. Default: any free port.</p>
                    @if (errors().callbackPort) {<p class="gw-field__error" [id]="uid + '-port-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().callbackPort }}</p>}
                  </div>
                  </div>
                    }
                  }
                </div>
              </section>
              <fieldset class="gw-field">
                <legend class="gw-field__label">{{ transport() === 'http' && signInWith() === 'bearer' ? 'Additional headers' : 'Variables or headers' }}</legend>
                @for (row of variables(); track $index; let i = $index) {
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-var-name-' + i">{{ transport() === 'http' ? 'Header' : 'Variable' }} name</label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-var-name-' + i" type="text" [placeholder]="transport() === 'http' ? (signInWith() === 'bearer' ? 'X-API-Key' : 'Authorization') : 'SENTRY_AUTH_TOKEN'" spellcheck="false" autocomplete="off" [value]="row.name" (input)="setVariable(i, value($event))" [attr.aria-describedby]="uid + '-env-hint'">
                  </div>
                }
                <!-- The core takes no existing secret by name (C10: a server's
                     values are its own secrets), so the kit's "Value from secret" select is gone; each value is typed in the
                     review, right before the start, and stored encrypted as a secret of the server. -->
                <p class="gw-field__hint" [id]="uid + '-env-hint'">The review asks for their values before the start. Each is stored encrypted as a secret of this server and never shown again. Only these reach the process, and the agent never sees them.</p>
                @if (errors().variables) {<p class="gw-field__error" [id]="uid + '-var-err'" role="alert"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().variables }}</p>}
                <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" (click)="addVariable()"><svg lucideIcon="plus" class="gw-icon gw-icon--sm"></svg>{{ transport() === 'http' ? 'Add header' : 'Add variable' }}</button></div>
              </fieldset>
              @if (error()) {
                <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                  <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                  <div class="gw-alert__content"><p class="gw-alert__title">The server wasn't reviewed</p><p class="gw-alert__body">{{ error() }}</p></div>
                </div>
              }
            </div>
          </div>
          <div class="gw-tabs__panel" role="tabpanel" [id]="uid + '-registry'" [attr.aria-labelledby]="uid + '-tab-registry'" tabindex="0" hidden>
            <div class="gw-stack gw-stack--sm">
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-find-q'">Server name</label>
                <div class="gw-search">
                  <svg lucideIcon="search" class="gw-icon gw-search__icon"></svg>
                  <input class="gw-search__input" type="search" [id]="uid + '-find-q'" [attr.autofocus]="tab() === 'registry' ? '' : null" placeholder="linear, sentry, postgres" spellcheck="false" autocapitalize="off" autocomplete="off" [attr.aria-describedby]="uid + '-find-hint'" (input)="find(value($event))" (keydown.enter)="findNow($event)">
                  <button class="gw-search__clear" type="button" aria-label="Clear search"><svg lucideIcon="x" class="gw-icon gw-icon--sm"></svg></button>
                </div>
                <p class="gw-field__hint" [id]="uid + '-find-hint'">Searches server names in the registry. Use fills in Manual entry; nothing runs or connects before the review.</p>
              </div>
              <p class="gw-text-small gw-fg-muted gw-tabular" role="status">{{ findStatus() }}</p>
              @if (findError(); as e) {
                <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                  <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                  <div class="gw-alert__content"><p class="gw-alert__title">The registry search didn't work</p><p class="gw-alert__body">{{ e }}</p></div>
                  <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="retryFind()">Try again</button></div>
                </div>
              }
              @if (found().length) {
                <ul class="gw-stack gw-stack--sm" aria-label="Servers in the MCP registry">
                  @for (r of found(); track r.server.name; let i = $index) {
                    <li>
                      <article class="gw-card" [attr.aria-labelledby]="uid + '-r' + i">
                        <header class="gw-card__header">
                          <div class="gw-card__heading">
                            <h3 class="gw-card__title" [id]="uid + '-r' + i">{{ r.title }}</h3>
                            <p class="gw-card__description gw-mono">{{ r.server.name }}@if (r.server.version) { · {{ r.server.version }}}</p>
                          </div>
                          <div class="gw-card__actions">
                            <button class="gw-btn gw-btn--sm" type="button" [id]="uid + '-use-' + i" (click)="use(r)" [attr.aria-disabled]="r.use.kind === 'none' ? 'true' : null" [attr.aria-describedby]="r.use.kind === 'none' ? uid + '-rn' + i : null">Use<span class="gw-sr-only"> {{ r.title }}</span></button>
                          </div>
                        </header>
                        <div class="gw-card__body">
                          <div class="gw-stack gw-stack--sm">
                            @if (r.server.description) {<p class="gw-text-small gw-fg-secondary">{{ r.server.description }}</p>}
                            <p class="gw-text-small">
                              @switch (r.use.kind) {
                                @case ('remote') {<span class="gw-badge gw-badge--outline"><svg lucideIcon="globe" class="gw-icon"></svg>HTTP</span> <span class="gw-mono">{{ r.target }}</span>}
                                @case ('package') {<span class="gw-badge gw-badge--outline"><svg lucideIcon="terminal" class="gw-icon"></svg>stdio</span> <span class="gw-mono">{{ r.target }}</span>}
                                @default {<span class="gw-badge gw-badge--neutral">Can’t be added here</span>}
                              }
                              @switch (r.server.listing?.verification) {
                                @case ('vendor') { <span class="gw-badge gw-badge--success"><svg lucideIcon="shield-check" class="gw-icon"></svg>Vendor verified</span>}
                                @case ('publisher') { <span class="gw-badge gw-badge--info"><svg lucideIcon="shield-check" class="gw-icon"></svg>Publisher verified</span>}
                              }
                              @if (r.server.listing?.featured) { <span class="gw-badge gw-badge--brand">Featured</span>}
                              @if (r.server.status === 'deprecated') { <span class="gw-badge gw-badge--warning"><svg lucideIcon="triangle-alert" class="gw-icon"></svg>Deprecated</span>}
                            </p>
                            @if (r.use.kind === 'none') {<p class="gw-text-caption gw-fg-muted" [id]="uid + '-rn' + i">{{ r.use.reason }}</p>}
                          </div>
                        </div>
                      </article>
                    </li>
                  }
                </ul>
                @if (nextCursor()) {
                  <div><button class="gw-btn gw-btn--sm" type="button" (click)="more()" [attr.aria-busy]="moreBusy() ? 'true' : null" [attr.aria-disabled]="moreBusy() ? 'true' : null">@if (moreBusy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} Show more</button></div>
                }
                <p class="gw-text-caption gw-fg-muted">{{ source() }} The registry checks who published a name, not what the server does: the review shows what will run.</p>
              } @else if (searched(); as q) {
                <div class="gw-empty gw-empty--compact">
                  <span class="gw-empty__icon"><svg lucideIcon="search-x" class="gw-icon"></svg></span>
                  <p class="gw-empty__title">No server name contains “{{ q }}”</p>
                  <p class="gw-empty__text">Try a shorter part of its name, or paste the configuration from the server’s docs in Manual entry.</p>
                </div>
              }
            </div>
          </div>
        </div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose>Cancel</button>
        <!-- A result's Use is the registry's action: the review starts from Manual entry, where the fields are. -->
        @if (tab() === 'manual') {
          <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="shield-check" class="gw-icon"></svg>} {{ transport() === 'http' ? 'Review and connect' : 'Review and start' }}</button>
        }
      </footer>
    </form>
  `,
})
export class GwMcpAddDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  /** The core reviewed the server: the review dialog takes over. */
  readonly reviewed = output<McpReviewed>();

  protected readonly uid = `ma-${++nextId}`;
  protected readonly name = signal('');
  protected readonly transport = signal<'stdio' | 'http'>('stdio');
  protected readonly command = signal('');
  protected readonly url = signal('');
  protected readonly variables = signal<readonly McpVariableDraft[]>([{ name: '' }]);
  protected readonly clientId = signal('');
  protected readonly scopes = signal('');
  protected readonly callbackPort = signal('');
  protected readonly signInOpen = signal(false);
  /** OAuth in the browser, a Bearer token, or the server's Google Cloud or AWS credentials (§7.6). */
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
  /** What the URL's host names to sign for, shown as the defaults of Service and Region. */
  protected readonly target = computed(() => signingTarget(null, this.url().trim()));
  protected readonly errors = signal<McpFormErrors>({});
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** The name GateRook suggested from the URL; the person's own name is never replaced. */
  protected readonly suggested = signal<string | null>(null);
  protected readonly pasted = signal<string | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  /** Manual entry (the fields, and a pasted configuration) or the Official MCP Registry tab. */
  protected readonly tab = signal<'manual' | 'registry'>('manual');
  private readonly tabs = viewChild.required(GwTabs);
  /** The registry tab: the servers whose names contain the query, a page at a time (§7.5). */
  protected readonly found = signal<readonly RegistryResult[]>([]);
  /** The query the shown results answer; null before a search, or after the query became too short. */
  protected readonly searched = signal<string | null>(null);
  protected readonly searching = signal(false);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly registry = signal<string | null>(null);
  protected readonly moreBusy = signal(false);
  protected readonly findError = signal<string | null>(null);
  /** What the chosen result filled in, said under Find a server. */
  protected readonly chosen = signal<string | null>(null);
  private readonly query = signal('');
  private findTimer: ReturnType<typeof setTimeout> | null = null;
  /** Each search counts up; an answer to an older one is dropped. */
  private findSeq = 0;

  protected readonly findStatus = computed(() => {
    const q = this.query().trim();
    if (this.searching()) return 'Searching the MCP registry…';
    if (q && q.length < 3) return 'Type at least 3 characters of the server’s name.';
    if (this.searched() === null) return '';
    const n = this.found().length;
    return `${n === 1 ? '1 server' : `${n.toLocaleString('en-US')} servers`}${this.nextCursor() ? ' so far' : ''}`;
  });

  protected readonly source = computed(() => {
    const host = this.registry();
    return host === OFFICIAL_REGISTRY ? `From the Official MCP Registry (${host}).` : `From the MCP registry at ${host}.`;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.findTimer && clearTimeout(this.findTimer));
  }

  /** Opens an empty draft for the explicitly selected source; no server starts until its review is confirmed. */
  open(opener: HTMLElement | null, source: 'manual' | 'registry' | 'url' = 'manual'): void {
    this.name.set('');
    this.transport.set(source === 'url' ? 'http' : 'stdio');
    this.command.set('');
    this.url.set('');
    this.variables.set([{ name: '' }]);
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
    const paste = this.host.querySelector<HTMLTextAreaElement>(`#${this.uid}-paste-text`);
    if (paste) paste.value = '';
    this.tabs().selectById(`${this.uid}-tab-${source === 'registry' ? 'registry' : 'manual'}`);
    this.resetFind();
    this.chosen.set(null);
    const search = this.host.querySelector<HTMLInputElement>(`#${this.uid}-find-q`);
    if (search) search.value = '';
    this.query.set('');
    this.errors.set({});
    this.error.set(null);
    this.busy.set(false);
    this.dialog.openAfterRender(opener);
  }

  /** Returns from the review with the existing draft and the original focus-return target. */
  resume(opener: HTMLElement | null): void {
    this.dialog.openAfterRender(opener);
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

  /**
   * A configuration pasted from the server's docs fills the fields. The text leaves the field once it is read: it may
   * hold a key, and keys are typed in the review, where they become secrets of the server.
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
    this.chosen.set(null);
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
    this.variables.set(r.variables.length ? r.variables.map((name) => ({ name })) : [{ name: '' }]);
    if (r.auth) this.applySignIn(r.auth);
    else if (this.signInWith() === 'bearer') this.setSignInWith('oauth');
    this.errors.set({});
    const kind = r.transport === 'http' ? 'header' : 'variable';
    return joinAnd([
      ...(r.name ? ['the name'] : []),
      r.transport === 'http' ? 'the URL' : 'the command',
      ...(r.variables.length ? [r.variables.length === 1 ? `one ${kind}` : `${r.variables.length} ${kind}s`] : []),
    ]);
  }

  protected onTab(id: string): void {
    this.tab.set(id === `${this.uid}-tab-registry` ? 'registry' : 'manual');
  }

  /** The query as typed: a search 350 ms after the last key, from 3 characters (the registry's minimum). */
  protected find(text: string): void {
    this.query.set(text);
    if (this.findTimer) clearTimeout(this.findTimer);
    this.findTimer = null;
    const q = text.trim();
    if (q.length < 3) {
      this.resetFind();
      return;
    }
    this.findTimer = setTimeout(() => void this.runFind(q), 350);
  }

  /** Enter searches at once, and never submits the form around the field. */
  protected findNow(e: Event): void {
    e.preventDefault();
    if (this.findTimer) clearTimeout(this.findTimer);
    this.findTimer = null;
    const q = this.query().trim();
    if (q.length >= 3) void this.runFind(q);
  }

  protected retryFind(): void {
    const q = this.query().trim();
    if (q.length >= 3) void this.runFind(q);
  }

  private resetFind(): void {
    this.findSeq++;
    this.found.set([]);
    this.searched.set(null);
    this.nextCursor.set(null);
    this.findError.set(null);
    this.searching.set(false);
    this.moreBusy.set(false);
  }

  private async runFind(q: string): Promise<void> {
    const seq = ++this.findSeq;
    this.searching.set(true);
    this.moreBusy.set(false);
    this.findError.set(null);
    try {
      const page = await this.data.searchRegistry(q);
      if (seq !== this.findSeq) return;
      this.found.set(page.servers.map(registryResult));
      this.nextCursor.set(page.nextCursor ?? null);
      this.registry.set(page.registry);
      this.searched.set(q);
    } catch (err) {
      if (seq !== this.findSeq) return;
      this.found.set([]);
      this.nextCursor.set(null);
      this.searched.set(null);
      this.findError.set(failureText(err, 'This build of the gateway core can’t search the MCP registry.'));
    } finally {
      if (seq === this.findSeq) this.searching.set(false);
    }
  }

  /** The next page of the same search; the keyboard moves to the first new result. */
  protected async more(): Promise<void> {
    const cursor = this.nextCursor();
    const q = this.searched();
    if (!cursor || q === null || this.moreBusy()) return;
    const seq = this.findSeq;
    this.moreBusy.set(true);
    try {
      const page = await this.data.searchRegistry(q, cursor);
      if (seq !== this.findSeq) return;
      const first = this.found().length;
      const shown = new Set(this.found().map((r) => r.server.name));
      this.found.update((rows) => [...rows, ...page.servers.filter((s) => !shown.has(s.name)).map(registryResult)]);
      this.nextCursor.set(page.nextCursor ?? null);
      setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-use-${first}`)?.focus());
    } catch (err) {
      if (seq === this.findSeq) this.findError.set(failureText(err, 'This build of the gateway core can’t search the MCP registry.'));
    } finally {
      if (seq === this.findSeq) this.moreBusy.set(false);
    }
  }

  /**
   * A result fills Manual entry as a pasted configuration would, opens that tab, and the keyboard moves to what the
   * person must finish: a URL or command with parts to replace, else the name. Nothing runs before the review.
   */
  protected use(r: RegistryResult): void {
    if (r.use.kind === 'none') return;
    const filled = fromRegistry(r.server);
    if ('error' in filled) {
      this.findError.set(filled.error);
      return;
    }
    // A sign-in chosen for the previous server isn't this one's: without one of its own, the review finds it.
    if (!filled.auth && this.signInWith() !== 'oauth') this.setSignInWith('oauth');
    this.pasted.set(null);
    const what = this.fill(filled);
    this.chosen.set(`${what} from ${r.title} in the MCP registry.${filled.notes.length ? ` ${filled.notes.join(' ')}` : ''}`);
    const unfinished = filled.transport === 'http' ? templateParts(filled.url).length > 0 : /<[A-Za-z_]|\{[A-Za-z_]/.test(filled.command);
    this.tabs().selectById(`${this.uid}-tab-manual`);
    setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-${unfinished ? (filled.transport === 'http' ? 'url' : 'command') : 'name'}`)?.focus());
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

  protected addVariable(): void {
    this.variables.update((rows) => [...rows, { name: '' }]);
    const i = this.variables().length - 1;
    // The new row's Name takes the focus, so the keyboard continues where the row appeared.
    setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-var-name-${i}`)?.focus());
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const way = this.signInWith();
    const r = mcpRequest(this.name(), this.transport(), this.command(), this.url(), this.variables(), {
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
    });
    if (r.errors) {
      this.errors.set(r.errors);
      // The first refused field (the always-visible ones first); a sign-in option opens its section.
      const order: readonly [keyof McpFormErrors, string][] = [['name', 'name'], ['command', 'command'], ['url', 'url'],
        ['variables', 'var-name-0'], ['clientId', 'client'], ['impersonate', 'impersonate'], ['quotaProject', 'quota'],
        ['scopes', 'scopes'], ['callbackPort', 'port'], ['profile', 'profile'], ['accessKeyId', 'key-id'],
        ['roleArn', 'role'], ['externalId', 'external'], ['service', 'service'], ['region', 'region']];
      const first = order.find(([key]) => r.errors[key]);
      if (first && !['name', 'command', 'url', 'variables'].includes(first[0])) this.signInOpen.set(true);
      setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-${first?.[1] ?? 'name'}`)?.focus());
      return;
    }
    this.busy.set(true);
    try {
      const review = await this.data.reviewServer(r.source);
      const opener = this.dialog.returnTo;
      this.dialog.returnTo = null;
      this.dialog.close('next');
      this.reviewed.emit({
        review,
        source: r.source,
        name: this.name().trim(),
        transport: this.transport(),
        target: this.transport() === 'http' ? this.url().trim() : this.command().trim(),
        variables: 'config' in r.source ? (r.source.config.headers ?? r.source.config.env ?? []) : [],
        bearer: 'config' in r.source && r.source.config.transport === 'http' && way === 'bearer',
        opener,
      });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
    } finally {
      this.busy.set(false);
    }
  }
}
