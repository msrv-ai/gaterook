import { rebase } from './live';

describe('gallery fixtures on the core clock', () => {
  it('moves every ISO date-time by the delta and leaves the rest alone', () => {
    const fixture = {
      requestedAt: '2026-10-22T14:29:12+02:00',
      expiresAt: '2026-10-22T12:34:12Z',
      nested: [{ at: '2026-10-22T14:00:00.250+02:00', id: '2026-10-22', text: 'Since 14:29' }],
      count: 3,
      none: null,
    };
    const moved = rebase(fixture, 60_000);
    expect(moved.requestedAt).toBe('2026-10-22T12:30:12.000Z');
    expect(moved.expiresAt).toBe('2026-10-22T12:35:12.000Z');
    expect(moved.nested[0]).toEqual({ at: '2026-10-22T12:01:00.250Z', id: '2026-10-22', text: 'Since 14:29' });
    expect(moved.count).toBe(3);
    expect(moved.none).toBeNull();
    // A deep copy: the module's fixture stays as written.
    expect(fixture.requestedAt).toBe('2026-10-22T14:29:12+02:00');
  });
});
