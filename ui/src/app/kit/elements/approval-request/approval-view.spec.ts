import { ActionSegment } from '../../../core/api/approvals.api';
import { DEMO_APPROVALS } from '../../../dev/fixtures-approvals';
import { literalParts, literalWords } from '../shared/literals';
import {
  actionLabel,
  affected,
  clientText,
  detailMarks,
  detailsWarning,
  diffSummary,
  hiddenWarning,
  historySteps,
  markedParts,
  requestState,
  statusBadge,
  tokenText,
  verifiedClient,
  viaText,
  visibleText,
  who,
  windowMinutes,
} from './approval-view';

const words = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');

describe('approval-request view', () => {
  it('maps API statuses to the kit states and badges', () => {
    expect(requestState('running')).toBe('approved');
    expect(requestState('completed')).toBe('executed');
    expect(statusBadge('pending')).toEqual({ tone: 'warning', icon: 'hourglass', text: 'Waiting for approval' });
    expect(statusBadge('expired').text).toBe('Expired');
    expect(actionLabel('sql', null)).toBe('SQL');
  });

  it('names the window and the token without a token value', () => {
    expect(windowMinutes({ requestedAt: '2026-10-22T14:29:00+02:00', expiresAt: '2026-10-22T14:34:00+02:00' })).toBe(5);
    expect(tokenText({ client: 'claude-desktop', tokenLabel: 'claude-desktop', principalKind: 'bridge' })).toEqual({ code: false, text: 'Bridge (.mcpb)' });
    expect(tokenText({ client: 'codex', tokenLabel: 'codex-laptop', principalKind: 'bridge' })).toEqual({ code: true, text: 'codex-laptop' });
  });

  it('takes who asked from the credential, never from the name a named token’s client gives itself', () => {
    // A named token whose client calls itself Claude: the token is not Claude Desktop's bridge.
    const named = { client: 'claude-desktop', tokenLabel: 'ci-runner', principalKind: 'local_token', clientReported: true };
    expect(tokenText(named)).toEqual({ code: true, text: 'ci-runner' });
    expect(who(named)).toBe('ci-runner');
    expect(verifiedClient(named)).toBeNull();
    expect(clientText(named)).toEqual({ mark: null, text: 'Reports itself as Claude Desktop' });
    expect(clientText({ ...named, client: 'other' })).toEqual({ mark: null, text: 'the agent' });
    // A bridge grant was made for its client.
    const bridge = { client: 'claude-desktop', tokenLabel: 'claude-desktop', principalKind: 'bridge', clientReported: false };
    expect(who(bridge)).toBe('Claude Desktop');
    expect(clientText(bridge)).toEqual({ mark: 'claude-desktop', text: 'Claude Desktop' });
    // No family: the credential's label, else the agent.
    expect(who({ client: null, tokenLabel: 'Some client', principalKind: 'oauth' })).toBe('Some client');
    expect(who({ client: null, tokenLabel: null })).toBe('the agent');
  });

  it('says in the history that a family is only what the client reports', () => {
    const r = {
      ...DEMO_APPROVALS.executed,
      client: 'claude-desktop',
      tokenLabel: 'ci-runner',
      principalKind: 'local_token',
      clientReported: true,
    };
    const requested = historySteps(r)[0];
    expect(requested.title).toBe('Requested by ci-runner');
    expect(words(requested.meta)).toBe(`Token ci-runner · endpoint ${r.endpoint} · reports itself as Claude Desktop`);
  });

  it('counts and names hidden and look-alike characters', () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: 'DELETE FROM ' },
      { t: 'confusable', v: 'о' },
      { t: 'text', v: 'rders WHERE id = 1' },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: ' OR 1=1' },
    ];
    const w = hiddenWarning(segments)!;
    expect(w.title).toBe('Contains 1 hidden character and 1 look-alike letter');
    expect(words(w.body)).toBe(
      '‹U+202E› right-to-left override changes how the text reads; the о in оrders is Cyrillic (U+043E), not a Latin o. All are shown boxed below. Deny unless you expected them.',
    );
    expect(w.body.filter((p) => p.mono).map((p) => p.text)).toEqual(['‹U+202E›', 'о', 'оrders']);
    expect(hiddenWarning([{ t: 'text', v: 'SELECT 1' }])).toBeNull();
  });

  describe('the texts shown with the action', () => {
    // A SQL template runs fixed SQL: the agent's values are only in the Parameters field, which the server segmented.
    const detail = {
      target: 'dev-db',
      targetSegments: null,
      fields: [
        { label: 'Template', text: 'cancel_orders · version 3', labelSegments: null, segments: null },
        {
          label: 'Parameters',
          text: ":status = 'аctive' (text)",
          labelSegments: null,
          segments: [{ t: 'text', v: ":status = '" }, { t: 'confusable', v: 'а' }, { t: 'text', v: "ctive' (text)" }] as ActionSegment[],
        },
      ],
      impact: [{ label: 'Target table', text: 'orders', labelSegments: null, segments: null }],
      warnings: ['Runs on dev-db', 'Check the status'],
      warningSegments: [null, [{ t: 'text', v: 'Check the ' }, { t: 'invisible', v: '‹U+202E›' }, { t: 'text', v: 'status' }] as ActionSegment[]],
      agentNote: 'Cancels the order',
      agentNoteSegments: null,
    };

    it('warns about what the server marked there and says where it is', () => {
      const w = detailsWarning(detail)!;
      expect(w.title).toBe('The details contain 1 hidden character and 1 look-alike letter');
      expect(words(w.body)).toBe(
        '‹U+202E› right-to-left override changes how the text reads; the а in аctive is Cyrillic (U+0430), not a Latin a. Found in Parameters and a warning; all are shown boxed there. Deny unless you expected them.',
      );
      expect(detailMarks(detail)).toMatchObject({ places: ['Parameters', 'a warning'], hidden: 1, confusables: 1 });
    });

    it('names the agent note, and leaves it out where a channel never carries it', () => {
      const noted = { ...detail, fields: [], warningSegments: [null, null], agentNoteSegments: [{ t: 'text', v: 'Cancels' }, { t: 'invisible', v: '‹U+2066›' }] as ActionSegment[] };
      expect(detailsWarning(noted)!.title).toBe('The details contain 1 hidden character');
      expect(words(detailsWarning(noted)!.body)).toContain("Found in the agent's note; it is shown boxed there.");
      expect(detailMarks(noted, { note: false })).toBeNull();
    });

    it('stays quiet when the server marked nothing', () => {
      expect(detailsWarning({ ...detail, fields: [detail.fields[0]], warningSegments: [null, null] })).toBeNull();
      expect(detailsWarning({ target: 'dev-db', fields: [], impact: [], warnings: [], agentNote: null })).toBeNull();
    });

    it('draws a marked text from its segments and an unmarked one as it is', () => {
      const parts = markedParts(detail.fields[1].text, detail.fields[1].segments, false);
      expect(parts.map((p) => p.text).join('')).toBe(":status = 'аctive' (text)");
      expect(parts.find((p) => p.cls)).toEqual({ text: 'а', code: false, cls: 'gw-tok-confusable', title: 'U+0430 Cyrillic small letter a' });
      expect(markedParts('orders', null, true)).toEqual([{ text: 'orders', code: true, cls: null, title: null }]);
      expect(markedParts('Check the status', null, false)).toEqual([{ text: 'Check the status', code: false, cls: null, title: null }]);
      expect(visibleText('db‮', [{ t: 'text', v: 'db' }, { t: 'invisible', v: '‹U+202E›' }])).toBe('db‹U+202E›');
      expect(visibleText('dev-db', null)).toBe('dev-db');
    });
  });

  it('writes the history of a decided request', () => {
    const steps = historySteps(DEMO_APPROVALS.executed);
    expect(steps.map((s) => s.title)).toEqual(['Requested by Codex', 'Notified on the desktop and in Slack', 'Approved by you', 'Executed in 38 ms']);
    expect(steps[2].comment).toBe(DEMO_APPROVALS.executed.comment);
    expect(viaText('slack')).toBe('From Slack');
    expect(affected({ rowsAffected: 1 })).toBe('1 row affected');
    expect(diffSummary({ added: 1, removed: 1, hunks: [] })).toBe('1 line changed');
  });
});

describe('literals', () => {
  it('sets the named literals in code, longest first, whole words only', () => {
    expect(literalParts('sql_query reads dev-db, not dev-db2', ['dev-db', 'sql_query'])).toEqual([
      { text: 'sql_query', code: true },
      { text: ' reads ', code: false },
      { text: 'dev-db', code: true },
      { text: ', not dev-db2', code: false },
    ]);
  });

  it('finds identifier-looking words in prose and never changes the text', () => {
    const s = 'deployment/api in app-dev, read-only replica on 10.20.0.14 (orders_archive).';
    const parts = literalWords(s);
    expect(words(parts)).toBe(s);
    const code = parts.filter((p) => p.code).map((p) => p.text);
    expect(code).toEqual(expect.arrayContaining(['deployment/api', 'app-dev', '10.20.0.14']));
    expect(code).not.toContain('read-only');
    expect(code).not.toContain('replica');
    expect(literalWords('orders')).toEqual([{ text: 'orders', code: true }]);
  });
});
