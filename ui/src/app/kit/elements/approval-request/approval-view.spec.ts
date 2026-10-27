import { ActionSegment } from '../../../core/api/approvals.api';
import { DEMO_APPROVALS } from '../../../dev/fixtures-approvals';
import { literalParts, literalWords } from '../shared/literals';
import {
  actionLabel,
  affected,
  diffSummary,
  hiddenWarning,
  historySteps,
  requestState,
  statusBadge,
  tokenText,
  viaText,
  windowMinutes,
} from './approval-view';

const words = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');

describe('approval-request view (design/elements/approval-request.html)', () => {
  it('maps API statuses to the kit states and badges', () => {
    expect(requestState('running')).toBe('approved');
    expect(requestState('completed')).toBe('executed');
    expect(statusBadge('pending')).toEqual({ tone: 'warning', icon: 'hourglass', text: 'Waiting for approval' });
    expect(statusBadge('expired').text).toBe('Expired');
    expect(actionLabel('sql', null)).toBe('SQL');
  });

  it('names the window and the token without a token value', () => {
    expect(windowMinutes({ requestedAt: '2026-10-22T14:29:00+02:00', expiresAt: '2026-10-22T14:34:00+02:00' })).toBe(5);
    expect(tokenText('claude-desktop', 'claude-desktop')).toEqual({ code: false, text: 'Bridge (.mcpb)' });
    expect(tokenText('codex', 'codex-laptop')).toEqual({ code: true, text: 'codex-laptop' });
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
