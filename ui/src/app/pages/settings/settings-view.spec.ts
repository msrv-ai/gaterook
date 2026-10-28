import { NotificationChannel, NotificationsSent } from '../../core/api/notifications.api';
import { AuditExportDestination } from '../../core/api/settings.api';
import { TunnelState } from '../../core/api/tunnel.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { ClientsView } from '../../core/api/clients.api';
import { ApprovalItem } from '../../core/api/approvals.api';
import { clockTime } from '../../core/time/time-format';
import {
  announceParts,
  channelStatus,
  dailyHint,
  dbBackedUpToast,
  endpointUrlsAt,
  envRuleRows,
  expiringTokens,
  exportCard,
  keyBackedUpToast,
  keyBackupFileName,
  listText,
  parseOrigin,
  parsePort,
  policiesAfterSlack,
  portClients,
  previewRequest,
  protectsText,
  providerCard,
  providerOf,
  retentionHint,
  roughly,
  ruleNoteOf,
  runtimeRows,
  savedToast,
  slackWorkspace,
  stillNotifiedBy,
  telegramBot,
  testSentToast,
  todayText,
  tunnelCalls,
  tunnelLook,
  updateToast,
  updateView,
} from './settings-view';

/** The words of Settings against the kit (design/pages/settings.html and its page script). */

const NOW = Date.parse('2026-10-22T12:30:00Z');
const DAY = 24 * 60 * 60 * 1000;
const text = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');

describe('settings-view: applies at once and Save forms', () => {
  it('a setting that applies at once names itself and its value, like the kit’s page script', () => {
    expect(savedToast('Default token lifetime', '30 days')).toEqual({ tone: 'success', title: 'Saved', body: 'Default token lifetime: 30 days.' });
    expect(listText(['SIEM', 'Amazon S3', 'BigQuery'])).toBe('SIEM, Amazon S3 and BigQuery');
    expect(listText(['Codex'])).toBe('Codex');
    expect(listText([])).toBe('');
  });
});

describe('settings-view: General › Core', () => {
  it('runtime rows: native core or a development JVM, and the plugin runtime (§8.6.1)', () => {
    const info = { version: '0.4.0', runtime: 'native', vm: '', port: 7465, logFile: '/x/core.log', pluginRuntime: { bundledJre: true, home: '/app/jre', version: '25.0.1+8', present: true } };
    expect(runtimeRows(info).map((r) => [r.key, text(r.value)])).toEqual([
      ['Core', 'Native core'],
      ['Plugin runtime', 'Bundled JRE 25, started only for Java plugins'],
    ]);
    const dev = { ...info, runtime: 'jvm', vm: 'OpenJDK 25', pluginRuntime: { bundledJre: false, home: '/opt/jdk', version: '25.0.5', present: true } };
    expect(runtimeRows(dev).map((r) => text(r.value))).toEqual(['Java VM, a development build · OpenJDK 25', 'JRE 25 at /opt/jdk']);
    expect(text(runtimeRows({ ...dev, pluginRuntime: { ...dev.pluginRuntime, present: false } })[1].value)).toBe('Not found: Java plugins can’t start');
    expect(runtimeRows(null)).toEqual([]);
  });

  it('the new port: 1024–65535, not the current one, not the tunnel’s', () => {
    expect(parsePort('', 7465, 43433).error).toBe('Enter a port from 1024 to 65535.');
    expect(parsePort('80', 7465, 43433).error).toBe('Use a port from 1024 to 65535.');
    expect(parsePort('70000', 7465, 43433).error).toBe('Use a port from 1024 to 65535.');
    expect(parsePort('7465', 7465, 43433).error).toBe('The core already listens on 7465. Enter another port.');
    expect(parsePort('43433', 7465, 43433).error).toBe("43433 is reserved by GateRook. Choose another one.");
    expect(parsePort(' 7475 ', 7465, 43433)).toEqual({ value: 7475, error: null });
  });

  it('endpoint URLs at the new port: the first in full, the rest as paths', () => {
    expect(text(endpointUrlsAt(7475, ['dev', 'stage-readonly', 'incident-prod']))).toBe('http://127.0.0.1:7475/mcp/dev, /mcp/stage-readonly and /mcp/incident-prod');
    expect(text(endpointUrlsAt(null, ['dev']))).toBe('http://127.0.0.1:<port>/mcp/dev');
    expect(text(endpointUrlsAt(7475, []))).toContain('None yet');
  });

  it('who connects again: entries that keep the URL; the bridges ask the app for it', () => {
    const view = {
      scannedAt: null,
      clients: [
        { client: 'claude-desktop', name: 'Claude Desktop', links: [{ method: 'mcpb' }] },
        { client: 'codex', name: 'Codex', links: [{ method: 'headers-helper' }] },
        { client: 'cursor', name: 'Cursor', links: [{ method: 'config-bridge' }, { method: 'token' }] },
      ],
    } as unknown as ClientsView;
    expect(portClients(view)).toEqual({ again: ['Codex', 'Cursor'], keep: ['Claude Desktop'] });
    expect(portClients(null)).toEqual({ again: [], keep: [] });
  });

  it('updates: the status line and the toast of Check now', () => {
    expect(updateView(null, '0.4.0', false)).toMatchObject({ tone: 'neutral', label: 'Version', version: '0.4.0' });
    expect(updateView({ state: 'up-to-date', version: '0.4.0', lastCheckedAt: null } as never, null, true)).toMatchObject({ tone: 'success', label: 'Up to date · version', version: '0.4.0' });
    expect(updateView({ state: 'checking' } as never, '0.4.0', true)).toMatchObject({ tone: 'checking', busy: true });
    expect(updateView({ state: 'available', version: '0.4.1' }, '0.4.0', true)).toMatchObject({ label: 'Update available · version', busy: false });
    expect(updateView({ state: 'downloading', version: '0.4.1', percent: 42.9 }, '0.4.0', true)).toMatchObject({ label: 'Downloading update · 42%', busy: true });
    expect(updateView({ state: 'installing', version: '0.4.1' }, '0.4.0', true)).toMatchObject({ label: 'Restarting to install update', busy: true });
    expect(updateView({ state: 'error', version: '0.4.1', message: 'Offline' }, '0.4.0', true).label).toBe('Couldn’t install update');
    expect(updateToast({ state: 'available', version: '0.4.1' }, null)).toEqual({ tone: 'info', title: 'Version 0.4.1 is available', body: 'Choose Update and restart in the sidebar to download and install it.' });
    expect(updateToast({ state: 'up-to-date', version: '0.4.0' } as never, null)).toEqual({ tone: 'success', title: 'You’re on the latest version', body: 'GateRook 0.4.0' });
  });
});

describe('settings-view: Authorization', () => {
  it('named tokens that run out within 7 days, soonest first; grants and expired ones are not warned about', () => {
    const tok = (name: string, inDays: number, over: Partial<TokenRow> = {}): TokenRow =>
      ({ id: name, kind: 'NAMED', name, prefix: 'gwt_1', client: null, createdAt: '2026-10-01T00:00:00Z', expiresAt: new Date(NOW + inDays * DAY).toISOString(), lastUsedAt: null, revokedAt: null, state: 'active', ...over }) as TokenRow;
    const map = new Map([
      ['dev', [tok('inspector-debug', 3), tok('ci', 30), tok('old', 2, { state: 'revoked' }), tok('grant', 1, { kind: 'GRANT' as never })]],
      ['stage-readonly', [tok('cursor', 1)]],
    ]);
    expect(expiringTokens(map, NOW).map((t) => `${t.endpoint}/${t.name}`)).toEqual(['stage-readonly/cursor', 'dev/inspector-debug']);
  });
});

describe('settings-view: Notifications', () => {
  const ch = (kind: string, over: Partial<NotificationChannel> = {}): NotificationChannel =>
    ({ kind, enabled: true, config: {}, state: 'connected', stateDetail: null, lastDeliveryAt: null, version: 1, ...over }) as NotificationChannel;

  it('a chat channel: connected, failing in the core’s words, or not set up', () => {
    expect(channelStatus(null)).toEqual({ tone: 'neutral', label: 'Not set up' });
    expect(channelStatus(ch('telegram', { state: 'not-set-up' }))).toEqual({ tone: 'neutral', label: 'Not set up' });
    expect(channelStatus(ch('slack'))).toEqual({ tone: 'success', label: 'Connected' });
    expect(channelStatus(ch('slack', { state: 'error', stateDetail: 'The bot token was revoked' }))).toEqual({ tone: 'danger', label: 'The bot token was revoked' });
    expect(channelStatus(ch('slack', { state: 'reconnecting' }))).toEqual({ tone: 'warning', label: 'Reconnecting' });
  });

  it('Send test notification says what the core did with each channel (kit toast), a failure in its words', () => {
    const slack = ch('slack', { config: { deliverTo: 'channel', channelName: '#deploy-approvals' } });
    const channels = [ch('desktop'), slack, ch('telegram', { state: 'off', enabled: false })];
    const sent = (desktop: string, s: string, t: string, detail: string | null = null): NotificationsSent =>
      ({
        channels: [
          { channel: 'desktop', state: desktop, detail: null },
          { channel: 'slack', state: s, detail },
          { channel: 'telegram', state: t, detail: null },
        ],
      }) as NotificationsSent;
    expect(testSentToast(sent('sent', 'sent', 'off'), channels)).toEqual({
      tone: 'success',
      title: 'Test notification sent',
      body: 'Sent to the desktop and #deploy-approvals in Slack. Telegram is not set up.',
    });
    expect(testSentToast(sent('sent', 'failed', 'off', 'Slack answered not_in_channel.'), channels)).toEqual({
      tone: 'danger',
      title: 'The test notification didn’t reach every channel',
      body: 'Sent to the desktop. Slack failed: Slack answered not_in_channel. Telegram is not set up.',
    });
    // Slack is set up (its status says Connected) but the core didn't send to it: not "not set up".
    expect(testSentToast(sent('off', 'off', 'off'), channels)).toEqual({
      tone: 'warning',
      title: 'No test notification sent',
      body: 'Nothing was sent. Desktop notifications are off. Slack isn’t connected right now. Telegram is not set up.',
    });
  });

  it('the Slack workspace and the Telegram bot as the core stores them', () => {
    expect(slackWorkspace(ch('slack', { config: { workspace: { team: 'acme', teamId: 'T1' } } }))).toBe('acme');
    expect(slackWorkspace(ch('slack', { config: {} }))).toBeNull();
    expect(telegramBot(ch('telegram', { config: { bot: { id: '7', username: 'acme_gateway_bot' } } }))).toBe('@acme_gateway_bot');
    expect(telegramBot(ch('telegram'))).toBeNull();
  });

  it('Disconnect Slack: who still notifies and what the policies announce in afterwards', () => {
    const rows = envRuleRows({
      dev: { timeoutMinutes: 5, comment: 'optional', announceIn: ['desktop', 'slack'] },
      stage: { timeoutMinutes: 10, comment: 'optional', announceIn: ['desktop', 'slack'] },
      prod: { timeoutMinutes: 10, comment: 'required', announceIn: ['desktop', 'slack'] },
    });
    expect(policiesAfterSlack(rows)).toBe('dev, stage and prod announce in desktop notifications only');
    expect(policiesAfterSlack(envRuleRows({ dev: { announceIn: ['slack'] }, stage: { announceIn: ['desktop'] }, prod: { announceIn: ['desktop', 'telegram'] } }))).toBe(
      'dev announces in this window only; stage announces in desktop notifications only; prod announces in desktop notifications and Telegram',
    );
    expect(stillNotifiedBy(true, false)).toBe('Desktop notifications');
    expect(stillNotifiedBy(true, true)).toBe('Desktop notifications and Telegram');
    expect(stillNotifiedBy(false, false)).toBe('Nothing outside this window: requests wait in Approvals');
  });

  it('Preview messages shows a waiting request that won’t expire while it is open, with its first impact fact', () => {
    const item = (id: string, inMs: number, status = 'pending') => ({ id, status, expiresAt: new Date(NOW + inMs).toISOString() }) as ApprovalItem;
    expect(previewRequest([item('a', 30_000), item('b', 240_000)], NOW)?.id).toBe('b');
    expect(previewRequest([item('a', 30_000)], NOW)?.id).toBe('a');
    expect(previewRequest([item('c', 240_000, 'running')], NOW)).toBeNull();
    expect(ruleNoteOf([{ label: 'Estimated rows', text: '1' }])).toBe('estimated rows 1');
    expect(ruleNoteOf([])).toBeNull();
  });
});

describe('settings-view: Approval rules', () => {
  it('the per-environment table and the note under it (kit wording)', () => {
    const rows = envRuleRows({
      dev: { timeoutMinutes: 5, comment: 'optional', announceIn: ['desktop', 'slack'] },
      stage: { timeoutMinutes: 10, comment: 'optional', announceIn: ['desktop', 'slack'] },
      prod: { timeoutMinutes: 10, comment: 'required', announceIn: ['desktop', 'slack'] },
    });
    expect(rows.map((r) => [r.env, r.timeout, r.comment])).toEqual([
      ['dev', '5 min', 'Optional'],
      ['stage', '10 min', 'Optional'],
      ['prod', '10 min', 'Required'],
    ]);
    expect(text(announceParts(rows, '#deploy-approvals'))).toBe(
      'All three announce requests in desktop notifications and Slack #deploy-approvals. One approval is enough everywhere: on this desktop, yours. Named approvers and two-person approval are server mode only.',
    );
    expect(envRuleRows(undefined).map((r) => r.timeout)).toEqual(['Not set', 'Not set', 'Not set']);
  });
});

describe('settings-view: Tunnel', () => {
  const tunnel = (over: Partial<TunnelState> = {}): TunnelState => ({
    provider: 'cloudflare-quick',
    state: 'off',
    host: null,
    publicUrl: null,
    endpoint: null,
    since: '2026-10-22T10:10:00Z',
    callers: { anthropicOnly: true },
    cloudflared: null,
    error: null,
    history: [],
    ...over,
  });

  it('the status line in each state', () => {
    expect(tunnelLook(tunnel({ state: 'on', endpoint: 'stage-readonly' }))).toEqual({ tone: 'warning', text: 'Public · stage-readonly · Cloudflare Tunnel' });
    expect(tunnelLook(tunnel({ state: 'starting' })).tone).toBe('checking');
    expect(tunnelLook(tunnel({ state: 'error' })).text).toBe('The tunnel didn’t start');
    expect(tunnelLook(tunnel()).text).toBe(`Off since ${clockTime('2026-10-22T10:10:00Z', false)}`);
  });

  it('today’s public periods and their calls', () => {
    const t = tunnel({ history: [{ from: '2026-10-22T06:40:00Z', to: '2026-10-22T10:10:00Z', endpoint: 'stage-readonly', calls: 19 }] });
    expect(todayText(t, NOW)).toBe(`Public ${clockTime('2026-10-22T06:40:00Z', false)} to ${clockTime('2026-10-22T10:10:00Z', false)}, turned on and off by you`);
    expect(tunnelCalls(t, NOW)).toBe(19);
    expect(todayText(tunnel(), NOW)).toBeNull();
    expect(tunnelCalls(tunnel(), NOW)).toBeNull();
  });

  it('the provider cards: Cloudflare keeps a named tunnel that is set up', () => {
    expect(providerCard('cloudflare-named')).toBe('cloudflare');
    expect(providerCard('ngrok')).toBe('ngrok');
    expect(providerOf('cloudflare', 'cloudflare-named')).toBe('cloudflare-named');
    expect(providerOf('cloudflare', 'ngrok')).toBe('cloudflare-quick');
    expect(providerOf('ngrok', 'cloudflare-quick')).toBe('ngrok');
  });
});

describe('settings-view: Local API', () => {
  it('an origin is a scheme, host and port, compared without case or a trailing slash; at most 20', () => {
    const help = 'Enter a scheme, host and port, for example http://localhost:3000. Paths aren’t allowed.';
    expect(parseOrigin('http://localhost:3000/', [])).toEqual({ value: 'http://localhost:3000', error: null });
    expect(parseOrigin('HTTP://LocalHost:6274', [])).toEqual({ value: 'http://localhost:6274', error: null });
    expect(parseOrigin('http://localhost:3000/app', []).error).toBe(help);
    expect(parseOrigin('localhost:3000', []).error).toBe(help);
    expect(parseOrigin('http://localhost:99999', []).error).toBe(help);
    expect(parseOrigin('http://localhost:6274', ['http://localhost:6274']).error).toBe('http://localhost:6274 is already allowed.');
    const twenty = Array.from({ length: 20 }, (_, i) => `http://localhost:${3000 + i}`);
    expect(parseOrigin('http://localhost:4000', twenty).error).toBe('At most 20 origins can be allowed. Remove one first.');
  });
});

describe('settings-view: Audit & retention', () => {
  it('the retention hint at today’s rate (kit: 1,284 calls, about 470,000 records a year)', () => {
    expect(roughly(468_660)).toBe('470,000');
    expect(retentionHint(1284, 1)).toBe(
      "A year at least, so the audit always covers the last twelve months. At today's rate, 1,284 calls in the last 24 hours, a year holds about 470,000 records.",
    );
    expect(retentionHint(1284, 3)).toContain('3 years hold about 1,400,000 records.');
    expect(retentionHint(null, 1)).toBe('A year at least, so the audit always covers the last twelve months.');
  });

  it('an export card: schedule, last run, failing since', () => {
    const d = (over: Partial<AuditExportDestination>): AuditExportDestination => ({
      id: 'x',
      kind: 'siem',
      name: 'SIEM',
      enabled: true,
      config: { host: 'siem.acme.internal', port: 6514 },
      schedule: 'stream',
      state: 'ok',
      failingSince: null,
      lastAttemptAt: '2026-10-22T12:29:30Z',
      lastError: null,
      ...over,
    });
    expect(exportCard(d({}))).toMatchObject({ title: 'SIEM · syslog', target: 'siem.acme.internal:6514', tone: 'success', label: 'Streaming · last event', failing: false });
    expect(exportCard(d({ kind: 's3', config: { bucket: 'acme-audit', prefix: 'gaterook/' }, schedule: 'daily@02:00' }))).toMatchObject({ title: 'Amazon S3', target: 's3://acme-audit/gaterook/', label: 'Daily at 02:00 · last run' });
    const bq = exportCard(d({ kind: 'bigquery', config: { table: 'acme-prod.audit.mcp_calls' }, schedule: 'hourly', failingSince: '2026-10-22T10:00:00Z', lastError: '403 Forbidden' }));
    expect(bq).toMatchObject({ title: 'BigQuery', target: 'acme-prod.audit.mcp_calls', tone: 'danger', failing: true, error: '403 Forbidden' });
    expect(bq.label).toBe(`Hourly · failing since ${clockTime('2026-10-22T10:00:00Z', false)} · last try`);
    expect(exportCard(d({ enabled: false }))).toMatchObject({ tone: 'neutral', label: 'Streaming · paused', at: null });
  });

  it('a card says why the core won’t run it, and names the place as the core does (K5 `target`)', () => {
    const d = (over: Partial<AuditExportDestination>): AuditExportDestination => ({
      id: 'x',
      kind: 'siem',
      name: 'SIEM',
      enabled: true,
      config: { host: 'siem.acme.internal' },
      schedule: 'stream',
      state: 'ok',
      failingSince: null,
      lastAttemptAt: null,
      lastError: null,
      ...over,
    });
    expect(exportCard(d({ target: 'siem.acme.internal:6514' })).target).toBe('siem.acme.internal:6514');
    expect(exportCard(d({})).blocked).toBeNull();
    expect(exportCard(d({ demo: true })).blocked).toBe('A destination of the demo world: shown, never called.');
    expect(exportCard(d({ verified: false, state: 'failing' })).blocked).toBe('Changed outside GateRook: it sends nothing until it is saved again.');
  });
});

describe('settings-view: Backup', () => {
  it('the daily hint, the copy’s toast, what the key protects', () => {
    expect(dailyHint('03:00', 7)).toBe('At 03:00, or at the next start if the computer was off. The last 7 copies are kept.');
    expect(dbBackedUpToast({ path: '/Users/you/.gaterook/backups/gateway-2026-10-22-1430.db', file: 'gateway-2026-10-22-1430.db', at: '2026-10-22T12:30:00Z', sizeBytes: 1 })).toEqual({
      tone: 'success',
      title: 'Database backed up',
      body: 'Saved as gateway-2026-10-22-1430.db in /Users/you/.gaterook/backups/.',
    });
    expect(protectsText(8, 3)).toBe('8 secrets stored here · 3 secret references don’t need it');
    expect(protectsText(1, 1)).toBe('1 secret stored here · 1 secret reference doesn’t need it');
    expect(protectsText(0, null)).toBe('No secrets stored here yet');
  });

  it('the master key copy: its suggested name and the toast (a synced folder warns)', () => {
    // The name Electron main's save dialog proposes (desktop/src/main/file-purposes.ts: UTC date).
    expect(keyBackupFileName(Date.parse('2026-10-22T12:00:00Z'))).toBe('gaterook-master-key-2026-10-22.json');
    expect(keyBackupFileName(Date.parse('2026-10-22T23:30:00-02:00'))).toBe('gaterook-master-key-2026-10-23.json');
    expect(keyBackedUpToast({ path: '/Volumes/Vault/k.json', passphrase: true, warnings: [] })).toMatchObject({
      tone: 'success',
      title: 'Master key backed up, encrypted with your passphrase',
      timeout: 0,
    });
    expect(keyBackedUpToast({ path: '/x/k.json', passphrase: false, warnings: ['synced-folder'] }).tone).toBe('warning');
  });
});
