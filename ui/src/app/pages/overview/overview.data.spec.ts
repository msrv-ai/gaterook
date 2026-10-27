import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivityApi } from '../../core/api/activity.api';
import { ApprovalItem, ApprovalList, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi } from '../../core/api/clients.api';
import { GettingStarted, Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { approval, histogram, overview } from './overview.fixtures';
import { OverviewData } from './overview.data';

/** OverviewData: what it loads, how it coalesces refreshes, and how Dismiss / Undo survive a refresh in flight. */

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let gets: ReturnType<typeof deferred<Overview>>[];
let ops: string[];
let historyPages: number;

function make(): OverviewData {
  gets = [];
  ops = [];
  historyPages = 0;
  const overviewApi: Partial<OverviewApi> = {
    get: () => {
      const d = deferred<Overview>();
      gets.push(d);
      return d.promise;
    },
    dismissGettingStarted: () => {
      ops.push('dismiss');
      return Promise.resolve<GettingStarted>({ ...overview().gettingStarted, dismissed: true });
    },
    restoreGettingStarted: () => {
      ops.push('restore');
      return Promise.resolve<GettingStarted>({ ...overview().gettingStarted, dismissed: false });
    },
  };
  // History newest first, a request every 6 minutes back 60 hours, pages of `limit`: the loader stops past 24 hours.
  const history: ApprovalItem[] = Array.from({ length: 600 }, (_, i) =>
    approval(`ap_h${i}`, { status: 'completed', requestedAt: new Date(Date.now() - i * 360_000).toISOString() }),
  );
  const approvalsApi: Partial<ApprovalsApi> = {
    list: (status, cursor, limit = 50) => {
      if (status === 'pending') return Promise.resolve<ApprovalList>({ status, items: [], nextCursor: null, pending: 0, counts: {} });
      historyPages++;
      const from = cursor ? Number(cursor) : 0;
      return Promise.resolve<ApprovalList>({
        status,
        items: history.slice(from, from + limit),
        nextCursor: from + limit < history.length ? String(from + limit) : null,
        pending: 0,
        counts: {},
      });
    },
    get: () => Promise.reject(new Error('no detail')),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OverviewApi, useValue: overviewApi },
      { provide: ActivityApi, useValue: { histogram: () => Promise.resolve(histogram()), events: () => Promise.reject(new Error('down')) } },
      { provide: ApprovalsApi, useValue: approvalsApi },
      { provide: ClientsApi, useValue: { list: () => Promise.reject(new Error('404')) } },
      { provide: EventStream, useValue: { on: () => () => undefined } },
    ],
  });
  return runInInjectionContext(TestBed.inject(Injector), () => new OverviewData());
}

describe('OverviewData', () => {
  it('runs one refresh at a time and one more after it for whatever came meanwhile', async () => {
    const data = make();
    const first = data.refresh();
    void data.refresh();
    void data.refresh();
    expect(gets.length).toBe(1);
    gets[0].resolve(overview());
    await first;
    await sleep(0);
    expect(gets.length).toBe(2);
    gets[1].resolve(overview());
    await sleep(0);
    expect(gets.length).toBe(2);
  });

  it('reads the approval history back 24 hours and no further, and leaves out what did not answer', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(overview());
    await done;
    // 200 a page, one request every 6 minutes: the second page reaches past a day, so there is no third.
    expect(historyPages).toBe(2);
    expect(data.approvalsDay()?.history.length).toBe(400);
    expect(data.recent()).toBeNull();
    expect(data.detected()).toEqual([]);
    expect(data.actions().size).toBe(0);
  });

  it('keeps the checklist as the person left it when a refresh started before Dismiss answers after it', async () => {
    const data = make();
    const first = data.refresh();
    gets[0].resolve(overview());
    await first;

    const stale = data.refresh();
    const dismissed = data.setChecklistDismissed(true);
    expect(data.overview()?.gettingStarted.dismissed).toBe(true);
    await dismissed;
    // The refresh began before Dismiss: its answer still says "not dismissed".
    gets[1].resolve(overview());
    await stale;
    expect(data.overview()?.gettingStarted.dismissed).toBe(true);

    await data.setChecklistDismissed(false);
    expect(ops).toEqual(['dismiss', 'restore']);
    expect(data.overview()?.gettingStarted.dismissed).toBe(false);
  });

  it('puts a refused Dismiss back as it was and says so to the caller', async () => {
    const data = make();
    const first = data.refresh();
    gets[0].resolve(overview());
    await first;
    TestBed.inject(OverviewApi).dismissGettingStarted = () => Promise.reject(new Error('refused'));
    await expect(data.setChecklistDismissed(true)).rejects.toThrow('refused');
    expect(data.overview()?.gettingStarted.dismissed).toBe(false);
  });

  it('a failed first load is an error; a failed refresh keeps the data and marks it stale', async () => {
    const data = make();
    const first = data.refresh();
    gets[0].promise.catch(() => undefined);
    (gets[0] as unknown as { resolve: (v: unknown) => void }).resolve(Promise.reject(new Error('500')));
    await first;
    expect(data.error()).toBeTruthy();
    expect(data.overview()).toBeNull();

    const second = data.refresh();
    gets[1].resolve(overview());
    await second;
    expect(data.error()).toBeNull();

    const third = data.refresh();
    (gets[2] as unknown as { resolve: (v: unknown) => void }).resolve(Promise.reject(new Error('500')));
    await third;
    expect(data.overview()).not.toBeNull();
    expect(data.stale()).toBeTruthy();
  });
});
