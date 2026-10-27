import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../../../core/api/api-error';
import { ClientCard, ClientLink, configEditProblem, toKitDiff } from '../../../core/api/clients.api';
import { DEMO_CLIENTS, DEMO_CLIENT_ENDPOINTS, DEMO_DISCONNECT_CODEX } from '../../../dev/fixtures-clients';
import {
  andList,
  approvalMode,
  authNote,
  authParts,
  bridgeFiles,
  changeSummary,
  choiceMeta,
  clientFailure,
  disconnectFacts,
  disconnectWords,
  endpointOption,
  lastCallAt,
  methodsFor,
  notInstalledText,
  restartAlert,
  tableLayout,
  toolsCell,
  viaText,
} from './client-view';
import { answerWithin, consentTools, consentWants, tunnelHistory, tunnelLook, whilePublic } from './tunnel-view';
import { DEMO_GRANTS, DEMO_TUNNEL_OFF, DEMO_TUNNEL_ON, DEMO_CONSENT } from '../../../dev/fixtures-clients';

const words = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');
const [dev, stage, prod] = DEMO_CLIENT_ENDPOINTS;

describe('client-card view (design/elements/client-card.html)', () => {
  it('names endpoints in the Connect select as the kit does', () => {
    expect(endpointOption(dev)).toBe('dev · 8 connections, 22 tools');
    expect(endpointOption(stage)).toBe('stage-readonly · 3 connections, read only');
    expect(endpointOption(prod)).toBe('incident-prod · 3 prod connections');
    expect(toolsCell(dev)).toEqual({ total: '22', modes: '14 read only, 3 approval, 5 off' });
  });

  it('says how a client connects (__via)', () => {
    expect(viaText(DEMO_CLIENTS.claudeDesktop)).toEqual({ text: 'Bridge extension (.mcpb) · 2 endpoints, one of them prod', mono: false });
    expect(viaText(DEMO_CLIENTS.codex)).toEqual({ text: '~/.codex/config.toml', mono: true });
    expect(tableLayout(DEMO_CLIENTS.claudeDesktop)).toBe(true);
    expect(tableLayout(DEMO_CLIENTS.codex)).toBe(false);
  });

  it('describes auth without ever naming a token value', () => {
    const codex = authParts(DEMO_CLIENTS.codex, DEMO_CLIENTS.codex.links[0]);
    expect(words(codex)).toBe('Access codex-laptop: a 15-minute token handed to http_headers_helper on the fly, never written to the config');
    expect(codex.filter((p) => p.code).map((p) => p.text)).toEqual(['codex-laptop', 'http_headers_helper']);
    expect(words(authParts(DEMO_CLIENTS.cursor, null))).toBe('You choose on Connect: a token in the file (plain text), or sign-in through the browser');
    expect(words(authParts(DEMO_CLIENTS.antigravity, null))).toBe('Token in headers, written to its config in plain text');
  });

  it('takes the newest call of all entries', () => {
    expect(lastCallAt(DEMO_CLIENTS.claudeDesktop.links)).toBe('2026-10-22T14:16:00+02:00');
    expect(lastCallAt([])).toBeNull();
  });

  it('offers only what prod allows, with the reason', () => {
    const { offered, refused } = methodsFor(DEMO_CLIENTS.cursor, 'prod');
    expect(offered.map((m) => m.id)).toEqual(['config-bridge']);
    expect(refused.map((m) => m.id)).toEqual(['token', 'oauth']);
    expect(methodsFor(DEMO_CLIENTS.cursor, 'dev').offered).toHaveLength(3);
  });

  it('warns that a token in the file is plain text', () => {
    const note = authNote(DEMO_CLIENTS.cursor, 'token', 'dev', 'cursor-laptop', null);
    expect(note.tone).toBe('warning');
    expect(note.title).toBe('The token is written in plain text');
    expect(words(note.body)).toContain('anyone who can read the file can call endpoint dev from this computer until it expires');
    expect(authNote(DEMO_CLIENTS.codex, 'helper', 'dev', null, 'codex-laptop').title).toBe('Token issued on the fly, not written to the config');
  });

  it('turns the clients module diff into the kit diff', () => {
    const d = toKitDiff({ added: 1, removed: 1, hunks: [{ header: '@@ -1 +1 @@', lines: [{ type: 'removed', oldNo: 1, newNo: null, text: 'a' }, { type: 'added', oldNo: null, newNo: 1, text: 'b' }] }] });
    expect(d.hunks[0].lines).toEqual([{ t: 'del', v: 'a' }, { t: 'add', v: 'b' }]);
    expect(changeSummary({ added: 7, removed: 1 })).toBe('7 additions, 1 deletion');
    expect(changeSummary({ added: 7, removed: 0 })).toBe('7 additions');
  });

  it('lists what Disconnect removes, revokes and keeps (pages/clients.html, pages/endpoint-detail.html)', () => {
    const link = DEMO_CLIENTS.codex.links[0];
    const f = disconnectFacts(DEMO_CLIENTS.codex, link, DEMO_DISCONNECT_CODEX);
    expect(words(f.removes!)).toBe('[mcp_servers.gateway-dev] from ~/.codex/config.toml');
    expect(words(f.revokes!)).toBe("The helper's grant codex-laptop; no new tokens are issued to it");
    expect(f.backup).toBe('~/.codex/config.toml.bak-2026-10-22');
    const token: ClientLink = { ...link, grantLabel: null, tokenName: 'cursor-laptop', configDisplay: '~/.cursor/mcp.json', entryName: 'gateway-dev' };
    expect(words(disconnectFacts(DEMO_CLIENTS.cursor, token, null).removes!)).toBe('"gateway-dev" from ~/.cursor/mcp.json');
    expect(andList(['dev', 'stage-readonly', 'incident-prod'])).toBe('dev, stage-readonly and incident-prod');
    // The bridge extension: the endpoint leaves the bridge, nothing to back up.
    const [cdDev, cdProd] = DEMO_CLIENTS.claudeDesktop.links;
    const bridge = disconnectFacts(DEMO_CLIENTS.claudeDesktop, cdDev, null);
    expect(words(bridge.removes!)).toBe('The bridge extension of dev, gaterook-dev.mcpb');
    expect(words(bridge.revokes!)).toBe("The bridge's short-lived token for dev; no new ones are issued for it");
    expect(bridge.backup).toBeNull();
    // The confirm's words: one row of the bridge keeps the others; a single entry names the client.
    const row = disconnectWords(DEMO_CLIENTS.claudeDesktop, cdDev, true);
    expect(row.title).toBe('Disconnect Claude Desktop from dev?');
    expect(words(row.body)).toBe('Claude Desktop loses access to endpoint dev at once. The bridge stays installed for incident-prod.');
    expect(row.button).toBe('Disconnect Claude Desktop from dev');
    expect(words(disconnectWords(DEMO_CLIENTS.claudeDesktop, cdProd, true).body)).toBe(
      'Claude Desktop loses access to endpoint incident-prod and its prod connections at once. The bridge stays installed for dev.',
    );
    const single = disconnectWords(DEMO_CLIENTS.codex, link, false);
    expect(single.title).toBe('Disconnect Codex?');
    expect(words(single.body)).toBe('Codex loses access to endpoint dev at once. Connect again any time; that creates a new access.');
    expect(single.button).toBe('Disconnect Codex');
  });

  it('words the Clients screen as pages/clients.html does', () => {
    expect(words(authParts(DEMO_CLIENTS.claudeDesktop, DEMO_CLIENTS.claudeDesktop.links[0], false, true))).toBe(
      'A short-lived token per endpoint, issued on the fly and never written to the config',
    );
    const codex = authParts(DEMO_CLIENTS.codex, DEMO_CLIENTS.codex.links[0], false, true);
    expect(words(codex)).toBe(
      'Short-lived token from gw-bridge headers (http_headers_helper), issued on the fly, not written to the config · access codex-laptop',
    );
    expect(codex.filter((p) => p.code).map((p) => p.text)).toEqual(['gw-bridge headers', 'http_headers_helper', 'codex-laptop']);
    expect(words(authParts(DEMO_CLIENTS.antigravity, null, false, true))).toBe('Token in headers');
    expect(notInstalledText(DEMO_CLIENTS.antigravity, true)).toBe(
      'Not found on this computer. Install it, then check again. One config file serves the Antigravity app, its IDE and its CLI.',
    );
    expect(notInstalledText(DEMO_CLIENTS.antigravity)).toBe('Not found on this machine. Install it, then check again.');
    const entry = { file: '~/.codex/config.toml', language: 'toml' as const, text: '[mcp_servers.gateway-dev]\ndefault_tools_approval_mode = "writes"' };
    expect(approvalMode(entry)).toBe('writes');
    expect(approvalMode(null)).toBeNull();
    const [token, oauth, bridge] = DEMO_CLIENTS.cursor.methods;
    expect([token, oauth, bridge].map(choiceMeta)).toEqual(['Not on prod endpoints', 'Turns on OAuth sign-in for the endpoint', 'Works on every endpoint']);
    expect(bridgeFiles({ ...DEMO_CLIENTS.claudeDesktop, links: DEMO_CLIENTS.claudeDesktop.links.map((l) => ({ ...l, mcpbPath: `/h/.gaterook/clients/claude-desktop/gaterook-${l.endpoint}.mcpb` })) })).toEqual([
      'gaterook-dev.mcpb',
      'gaterook-incident-prod.mcpb',
    ]);
  });

  it('names the restart a client needs', () => {
    expect(restartAlert(DEMO_CLIENTS.codexRestart)).toEqual({ title: 'Restart Codex to load the server', body: 'The config is written. Codex reads it only when it starts.' });
  });

  it('explains a failed write and gives the fix', () => {
    const card: ClientCard = DEMO_CLIENTS.cursor;
    const err = ApiError.fromHttp(new HttpErrorResponse({ status: 409, error: { title: 'Connect failed', detail: "Can't write ~/.cursor/mcp.json: java.nio.file.AccessDeniedException: /Users/maria/.cursor/mcp.json" } }));
    const f = clientFailure(err, card);
    expect(words(f.title)).toBe("Can't write ~/.cursor/mcp.json");
    expect(f.fix).toEqual({ title: 'Fix the owner', label: 'command', language: 'shell', text: 'sudo chown "$USER" ~/.cursor/mcp.json' });
  });

  it("reads K1's 422 edit problem from the response: the core's sentence and the entry to add by hand", () => {
    const card: ClientCard = DEMO_CLIENTS.cursor;
    const detail = 'The file has comments or trailing commas (line 4, column 3). GateRook writes only strict JSON, so it changes nothing; add the entry by hand.';
    // As ClientsController.EditProblem serialises it.
    const body = { type: 'about:blank', title: "GateRook won't edit this file", detail, field: null, errors: [], reason: 'jsonc', line: 4, column: 3, fragment: '"gateway-dev": {"url": "…"}' };
    const err = ApiError.fromHttp(new HttpErrorResponse({ status: 422, error: body }));
    expect(configEditProblem(err)).toEqual({ reason: 'jsonc', detail, line: 4, column: 3, fragment: '"gateway-dev": {"url": "…"}' });
    const jsonc = clientFailure(err, card);
    expect(words(jsonc.title)).toBe("Can't edit ~/.cursor/mcp.json safely");
    expect(words(jsonc.body)).toBe(detail);
    expect(jsonc.fix).toEqual({ title: 'Add by hand', label: 'entry', language: 'json', text: '"gateway-dev": {"url": "…"}' });

    // No fragment (an invalid file): the sentence, no code block.
    const invalid = ApiError.fromHttp(new HttpErrorResponse({ status: 422, error: { ...body, reason: 'invalid', fragment: null } }));
    expect(clientFailure(invalid, card).fix).toBeNull();
    // Another 422 (a policy refused it) is not an edit problem.
    const other = ApiError.fromHttp(new HttpErrorResponse({ status: 422, error: { title: "Can't do that", detail: 'No.' } }));
    expect(configEditProblem(other)).toBeNull();
  });
});

describe('claude.ai card view (tunnel and OAuth)', () => {
  const NOW = Date.parse('2026-10-22T14:30:00+02:00');
  it('says the tunnel state in words', () => {
    expect(tunnelLook(DEMO_TUNNEL_OFF)).toEqual({ tone: 'neutral', text: 'Tunnel off' });
    expect(tunnelLook(DEMO_TUNNEL_ON)).toEqual({ tone: 'success', text: 'Tunnel on' });
    expect(tunnelLook(null).text).toBe('Not available yet');
  });

  it('tells when it was public and what claude.ai did meanwhile', () => {
    const history = tunnelHistory(DEMO_TUNNEL_OFF, NOW, () => 'just now');
    expect(history).toMatch(/^Off since \d\d:\d\d · public today \d\d:\d\d to \d\d:\d\d$/);
    expect(tunnelHistory({ ...DEMO_TUNNEL_OFF, history: [] }, NOW, () => '')).toMatch(/never published$/);
    expect(tunnelHistory(DEMO_TUNNEL_ON, NOW, () => 'just now')).toBe('Public since just now');
    expect(whilePublic(DEMO_TUNNEL_OFF, DEMO_GRANTS)).toEqual({ calls: 19, lastAt: '2026-10-22T12:08:14+02:00' });
  });

  it('a consent says what it wants and how long it waits', () => {
    expect(consentWants(DEMO_CONSENT)).toBe('Read access to');
    expect(consentTools(DEMO_CONSENT)).toBeNull();
    expect(consentTools({ ...DEMO_CONSENT, tools: { read: 14, approval: 3, off: 5 } })).toBe('14 read only, 3 with approval');
    expect(answerWithin(DEMO_CONSENT.expiresAt, NOW)).toBe('5 min');
    expect(answerWithin(DEMO_CONSENT.expiresAt, NOW + 4 * 60_000 + 12_000)).toBe('48 s');
  });
});
