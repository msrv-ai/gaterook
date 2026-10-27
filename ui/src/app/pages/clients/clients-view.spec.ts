import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../../core/api/api-error';
import { ClientCard } from '../../core/api/clients.api';
import { clockTime } from '../../core/time/time-format';
import { DEMO_CLIENTS, DEMO_GRANTS, DEMO_PREVIEW_CURSOR, DEMO_PUBLISH, DEMO_TUNNEL_OFF, DEMO_TUNNEL_ON } from '../../dev/fixtures-clients';
import { tableLayout } from '../../kit/elements/client-card/client-view';
import {
  allowedAt,
  bridgeRemovedToast,
  checkSummary,
  clientsLayout,
  connectFailure,
  connectToast,
  connectionEnvs,
  disconnectToast,
  endpointAddedToast,
  entryFromPreview,
  errorSentence,
  expiresIn,
  handTokens,
  headerCounts,
  lastCallRow,
  manualEndpoint,
  noClients,
  otherPaths,
  providerName,
  publishedEndpoint,
  scanSummary,
  snippetLang,
  snippetTabs,
  tunnelRow,
} from './clients-view';
import { NOW, codexDisconnect, connected, kitClients, kitEndpoints, kitManual, kitTokens, noneFound } from './clients.fixtures';

/** What the Clients screen says, against design/pages/clients.html's words and the core's answers. */

const words = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');
const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));
const { claudeDesktop, codex, cursor, antigravity } = DEMO_CLIENTS;

describe('Clients view: header and layout', () => {
  it('counts the clients as the kit’s header does', () => {
    expect(headerCounts(kitClients().clients)).toEqual([
      { tone: 'success', text: '2 connected' },
      { tone: 'info', text: '1 detected, not connected' },
      { tone: 'neutral', text: '1 not installed' },
    ]);
    // A restart or an error is counted on its own, so the header says what needs the person.
    const restart = headerCounts([DEMO_CLIENTS.codexRestart, { ...cursor, status: 'error', statusText: 'Config changed outside the app' }]);
    expect(restart.map((c) => c.text)).toEqual(['1 needs restart', '1 needs you']);
  });

  it('puts the first detected client first and wide, the bridge wide, everyone else two by two', () => {
    const layout = clientsLayout(kitClients().clients, tableLayout);
    expect(layout.inline?.client).toBe('cursor');
    expect(layout.wide.map((c) => c.client)).toEqual(['claude-desktop']);
    expect(layout.grid.map((c) => c.client)).toEqual(['codex', 'antigravity']);
    // Once connected, Cursor is a card like the others.
    const done = clientsLayout([{ ...cursor, status: 'connected', links: codex.links }], tableLayout);
    expect(done.inline).toBeNull();
  });

  it('shows "No MCP clients found" only when the scan found nothing at all', () => {
    expect(noClients(kitClients().clients)).toBe(false);
    expect(noClients(noneFound().clients)).toBe(true);
    expect(noClients([{ ...antigravity, detected: true, status: 'detected' }])).toBe(false);
  });

  it('announces what Scan again and Check again found', () => {
    const before = kitClients().clients;
    expect(scanSummary(before, before)).toBe('Scan finished. Nothing changed: 2 connected, 1 detected, not connected, 1 not installed.');
    const after = before.map((c) => (c.client === 'antigravity' ? { ...c, status: 'detected' as const, statusText: 'Detected, not connected', detected: true } : c));
    expect(scanSummary(before, after)).toBe('Scan finished: 2 connected, 2 detected, not connected.');
    expect(scanSummary(before, noneFound().clients)).toBe('Scan finished. No MCP clients found on this computer.');
    expect(checkSummary(before, 'antigravity', 'Antigravity')).toBe('Antigravity is still not installed.');
    expect(checkSummary(after, 'antigravity', 'Antigravity')).toBe('Antigravity found on this computer: detected, not connected.');
  });
});

describe('Clients view: Connect and Disconnect toasts', () => {
  it('says what Connect wrote, in the kit’s words', () => {
    const request = { endpoint: 'dev', method: 'token' as const, token: { name: 'cursor-laptop', ttlDays: 7 } };
    expect(connectToast(cursor, request, DEMO_PREVIEW_CURSOR, connected('cursor', request), false)).toEqual({
      title: 'Cursor connected to dev',
      body: 'One server added to ~/.cursor/mcp.json with token cursor-laptop, which expires in 7 days. Backup: ~/.cursor/mcp.json.bak-2026-10-22.',
    });
    const oauth = { endpoint: 'dev', method: 'oauth' as const };
    expect(connectToast(cursor, oauth, DEMO_PREVIEW_CURSOR, connected('cursor', oauth), false).body).toBe(
      'One server added to ~/.cursor/mcp.json, with no token in it. Cursor opens your browser to sign in; allow it here.',
    );
    const mcpb = { endpoint: 'stage-readonly', method: 'mcpb' as const };
    expect(connectToast(claudeDesktop, mcpb, null, connected('claude-desktop', mcpb), true).body).toBe(
      'Claude Desktop opens its install dialog for the extension: click Install. It lists the tools in the next new chat.',
    );
    // Without the desktop app, the person opens the extension file.
    expect(connectToast(claudeDesktop, mcpb, null, connected('claude-desktop', mcpb), false).body).toContain('Open /Users/maria/.mcp-gateway/clients/claude-desktop/gateway-stage-readonly.mcpb in Claude Desktop');
    expect(endpointAddedToast(claudeDesktop, 'stage-readonly', connected('claude-desktop', mcpb), true).title).toBe('stage-readonly added to Claude Desktop');
  });

  it('says what Disconnect and Remove bridge took back', () => {
    const [dev] = claudeDesktop.links;
    expect(disconnectToast(claudeDesktop, dev, { linkId: dev.id, entryRemoved: false, rejectedApprovals: 0, notes: [] })).toEqual({
      title: 'Claude Desktop disconnected from dev',
      body: 'incident-prod still works through the bridge.',
    });
    expect(disconnectToast(codex, codex.links[0], { linkId: 'x', entryRemoved: true, rejectedApprovals: 0, notes: [] })).toEqual({
      title: 'Codex disconnected',
      body: 'The entry is removed and access codex-laptop is revoked.',
    });
    expect(bridgeRemovedToast(claudeDesktop, { revoked: [], removedFiles: [], rejectedApprovals: 0, notes: [] })).toEqual({
      title: 'Bridge removed from Claude Desktop',
      body: 'The extension is removed and its tokens for dev and incident-prod are revoked.',
    });
  });
});

describe('Clients view: Connect failed', () => {
  it('a config that is not valid JSON: the kit’s title and sentence, the parser’s words in Details', () => {
    const e = problem(422, {
      title: 'Config file not editable',
      status: 422,
      detail: "The file isn't valid JSON (line 7, column 5): Unexpected character ('}'). MCP Gateway changes nothing.",
      reason: 'invalid',
      line: 7,
      column: 5,
      fragment: null,
    });
    const f = connectFailure(e, cursor, NOW);
    expect(words(f.title)).toBe("Can't connect Cursor: ~/.cursor/mcp.json is not valid JSON");
    expect(f.title.filter((p) => p.code).map((p) => p.text)).toEqual(['~/.cursor/mcp.json']);
    expect(words(f.body)).toBe('The file is unchanged. Fix line 7 in Cursor or an editor, then connect again.');
    expect(f.details?.title).toBe(`core answer · ${clockTime(NOW)}`);
    expect(f.details?.text).toBe(
      "client-config: invalid, nothing written\n  file   ~/.cursor/mcp.json\n  at     line 7, column 5\n  error  The file isn't valid JSON (line 7, column 5): Unexpected character ('}'). MCP Gateway changes nothing.",
    );
    expect(f.fix).toBeNull();
  });

  it('a config it won’t touch: what it is, and the entry to add by hand when the core gives one', () => {
    const e = problem(422, { title: 'Config file not editable', status: 422, detail: 'Codex keeps its servers in an inline table. Add the entry by hand.', reason: 'inline-table', line: 3, column: 1, fragment: '[mcp_servers.gateway-dev]\nurl = "http://127.0.0.1:7465/mcp/dev"' });
    const f = connectFailure(e, codex, NOW);
    expect(words(f.title)).toBe("Can't connect Codex: ~/.codex/config.toml keeps its servers in an inline table");
    expect(words(f.body)).toBe('Codex keeps its servers in an inline table. Add the entry by hand.');
    expect(f.fix).toEqual({ title: 'Add by hand', label: 'entry', language: 'toml', text: '[mcp_servers.gateway-dev]\nurl = "http://127.0.0.1:7465/mcp/dev"' });
  });

  it('the file changed since the preview (409), and no answer at all', () => {
    const changed = connectFailure(problem(409, { title: 'The file changed', status: 409, detail: '~/.cursor/mcp.json changed after the preview.' }), cursor, NOW);
    expect(words(changed.title)).toBe("Can't connect Cursor: ~/.cursor/mcp.json changed since you checked it");
    expect(words(changed.body)).toBe('~/.cursor/mcp.json changed after the preview. Nothing was written: connect again to see the new change.');
    const away = connectFailure(new TypeError('Failed to fetch'), cursor, NOW);
    expect(words(away.title)).toBe("Can't connect Cursor");
    expect(words(away.body)).toBe("The core didn't answer. Nothing was written; try again.");
    expect(away.details?.text).toBe('TypeError: Failed to fetch');
  });
});

describe('Clients view: Codex’s entry', () => {
  it('is the disconnect preview’s removed lines, exactly, blank lines around it trimmed', () => {
    const entry = entryFromPreview(codexDisconnect())!;
    expect(entry.file).toBe('~/.codex/config.toml');
    expect(entry.language).toBe('toml');
    const lines = entry.text.split('\n');
    expect(lines[0]).toBe('# >>> MCP Gateway · gateway-dev · managed entry: Disconnect in MCP Gateway removes it');
    expect(lines).toContain('default_tools_approval_mode = "writes"');
    expect(lines.at(-1)).toBe('# <<< MCP Gateway · gateway-dev');
    // The bridge extension has no entry in a file.
    expect(entryFromPreview({ ...codexDisconnect(), files: [{ ...codexDisconnect().files[0], kind: 'mcpb', diff: null }] })).toBeNull();
  });
});

describe('Clients view: manual setup', () => {
  it('is about the first running endpoint that isn’t prod', () => {
    const eps = kitEndpoints();
    expect(manualEndpoint(eps)).toBe('dev');
    expect(manualEndpoint(eps.filter((e) => e.slug === 'incident-prod'))).toBe('incident-prod');
    expect(manualEndpoint([])).toBeNull();
  });

  it('sorts the core’s snippets into the kit’s tabs, never with a token on a command line', () => {
    const tabs = snippetTabs(kitManual());
    expect(tabs.map((t) => t.label)).toEqual(['MCP Inspector', 'JSON', 'TOML']);
    expect(tabs[0].snippets.map((s) => s.id)).toEqual(['inspector-ui', 'inspector-cli']);
    expect(words(tabs[0].caption)).toBe('Inspector reads the token from gw-inspector.json on the JSON tab: paste your token there, never into the command line.');
    expect(tabs[0].snippets.every((s) => !/Bearer|‹token›/.test(s.text))).toBe(true);
    expect(words(tabs[1].caption)).toBe('For apps that read an mcpServers file. Add the gateway-dev entry next to the servers you already have.');
    expect(tabs[2].snippets[0].text).toContain('Bearer ‹token›');
    expect(snippetLang('sh')).toBe('Shell');
    expect(otherPaths(kitManual())).toEqual(['/mcp/stage-readonly', '/mcp/incident-prod']);
    // Without the JSON snippet, Inspector's own way to take the token.
    const only = snippetTabs({ ...kitManual(), snippets: kitManual().snippets.filter((s) => s.language === 'sh') });
    expect(words(only[0].caption)).toBe('In Inspector, open Authentication, choose Bearer token and paste your token.');
  });

  it('lists tokens set up by hand: named, active, not written by a Connect', () => {
    expect(handTokens(kitTokens(), kitClients().clients).map((t) => t.name)).toEqual(['inspector-debug']);
    const cursorWithToken: ClientCard = { ...cursor, links: [{ ...codex.links[0], client: 'cursor', method: 'token', tokenName: 'inspector-debug' }] };
    expect(handTokens(kitTokens(), [cursorWithToken])).toEqual([]);
    expect(expiresIn('2026-10-25T14:30:00+02:00', NOW)).toBe('expires in 3 days');
    expect(expiresIn(null, NOW)).toBe('never expires');
    expect(expiresIn('2026-10-22T13:00:00+02:00', NOW)).toBe('expires today');
  });
});

describe('Clients view: claude.ai through the tunnel', () => {
  it('is about the tunnel’s endpoint, else the one marked published', () => {
    expect(publishedEndpoint(DEMO_TUNNEL_OFF, kitEndpoints())).toBe('stage-readonly');
    expect(publishedEndpoint({ ...DEMO_TUNNEL_OFF, endpoint: null }, kitEndpoints())).toBe('stage-readonly');
    expect(publishedEndpoint(null, kitEndpoints().map((e) => ({ ...e, published: false })))).toBeNull();
    expect(providerName('cloudflare-named')).toBe('Cloudflare Tunnel');
    expect(providerName(null)).toBeNull();
  });

  it('words the Tunnel and Last call rows as the kit does', () => {
    const relative = () => 'just now';
    expect(tunnelRow(DEMO_TUNNEL_OFF, NOW, relative)).toBe(
      `Off since ${clockTime('2026-10-22T12:10:00+02:00', false)} · public today ${clockTime('2026-10-22T08:40:00+02:00', false)} to ${clockTime('2026-10-22T12:10:00+02:00', false)}`,
    );
    expect(tunnelRow(DEMO_TUNNEL_ON, NOW, relative)).toBe('Public since just now');
    expect(tunnelRow({ ...DEMO_TUNNEL_OFF, history: [] }, NOW, relative)).toContain('never published');
    const last = lastCallRow(DEMO_TUNNEL_OFF, DEMO_GRANTS, NOW);
    expect(last).toEqual({ at: '2026-10-22T12:08:14+02:00', note: ` (tunnel off since ${clockTime('2026-10-22T12:10:00+02:00', false)})`, calls: '19 calls today' });
    expect(lastCallRow(DEMO_TUNNEL_ON, DEMO_GRANTS, NOW).note).toBe('');
    expect(connectionEnvs(DEMO_PUBLISH)).toEqual(['stage']);
    expect(allowedAt('2026-10-22T08:52:00+02:00', NOW)).toBe(`Today ${clockTime('2026-10-22T08:52:00+02:00', false)}`);
  });

  it('turns any failure into one sentence for a toast', () => {
    expect(errorSentence(problem(409, { title: 'Conflict', status: 409, detail: 'The tunnel is starting.' }))).toBe('The tunnel is starting.');
    expect(errorSentence(new Error('x'))).toBe("The core didn't answer. Try again.");
  });
});
