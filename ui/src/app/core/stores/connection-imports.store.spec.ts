import { Component, inject, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ConnectionImportsApi, ConnectionImportTask } from '../api/connection-imports.api';
import { TimeService } from '../time/time.service';
import { ConnectionImportsStore } from './connection-imports.store';
import { CoreStore } from './core.store';

const startedAt = '2026-11-07T12:00:00Z';
const task: ConnectionImportTask = {
  id: 'ci_abcdefgh1234', repository: '/workspace/example', endpoint: 'default',
  status: 'awaiting', expiresAt: '2026-11-07T14:00:00Z',
  progress: { startedAt, updatedAt: startedAt, message: 'Checking repository configuration' },
};
type TaskList = { items: readonly ConnectionImportTask[] };

@Component({ template: '' })
class ImportConsumer {
  readonly imports = inject(ConnectionImportsStore);
}

describe('ConnectionImportsStore', () => {
  let running: ReturnType<typeof signal<boolean>>;
  let instanceChanges: ReturnType<typeof signal<number>>;
  let now: ReturnType<typeof signal<number>>;
  let list: ReturnType<typeof vi.fn<() => Promise<TaskList>>>;
  let store: ConnectionImportsStore;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    running = signal(false);
    instanceChanges = signal(0);
    now = signal(Date.parse(startedAt));
    list = vi.fn<() => Promise<TaskList>>().mockResolvedValue({ items: [] });
    TestBed.configureTestingModule({ providers: [
      { provide: ConnectionImportsApi, useValue: { list } },
      { provide: CoreStore, useValue: { running, instanceChanges } },
      { provide: TimeService, useValue: { now } },
    ] });
    store = TestBed.inject(ConnectionImportsStore);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  async function settle(): Promise<void> {
    TestBed.tick();
    await Promise.resolve();
    await Promise.resolve();
  }

  it('starts when the core runs and keeps polling after a page using it is destroyed', async () => {
    await settle();
    expect(list).not.toHaveBeenCalled();
    list.mockResolvedValue({ items: [task] });
    running.set(true);
    await settle();
    expect(list).toHaveBeenCalledTimes(1);
    expect(store.tasks()).toEqual([task]);
    const page = TestBed.createComponent(ImportConsumer);
    page.detectChanges();
    expect(page.componentInstance.imports).toBe(store);
    page.destroy();
    await vi.advanceTimersByTimeAsync(4999);
    expect(list).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(list).toHaveBeenCalledTimes(2);
    expect(store.tasks()).toEqual([task]);
  });

  it('shares one in-flight request and removes tasks absent from the next server response', async () => {
    let resolve!: (value: TaskList) => void;
    list.mockReturnValueOnce(new Promise<TaskList>(done => { resolve = done; }));
    running.set(true);
    await settle();
    const pending = store.refresh();
    expect(store.refresh()).toBe(pending);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(list).toHaveBeenCalledTimes(1);
    resolve({ items: [task] });
    await pending;
    expect(store.tasks()).toEqual([task]);
    await vi.advanceTimersByTimeAsync(5000);
    expect(list).toHaveBeenCalledTimes(2);
    expect(store.tasks()).toEqual([]);
  });

  it('retains reports during a transient failure and filters expiry using the core clock', async () => {
    const review: ConnectionImportTask = { ...task, id: 'ci_review123456', status: 'review', progress: null };
    const notStarted: ConnectionImportTask = { ...task, id: 'ci_waiting12345', progress: null };
    list.mockResolvedValueOnce({ items: [task, review, notStarted] });
    running.set(true);
    await settle();
    expect(store.tasks()).toEqual([task, review]);
    list.mockRejectedValue(new Error('Temporary network failure'));
    await vi.advanceTimersByTimeAsync(5000);
    now.set(Date.parse(startedAt) + 16 * 60_000);
    expect(store.tasks()).toEqual([task, review]);
    now.set(Date.parse(task.expiresAt));
    expect(store.tasks()).toEqual([]);
  });

  it('clears reports on a core restart and ignores the old instance response', async () => {
    list.mockResolvedValueOnce({ items: [task] });
    running.set(true);
    await settle();
    let resolveOld!: (value: TaskList) => void;
    list.mockReturnValueOnce(new Promise<TaskList>(done => { resolveOld = done; }));
    const old = store.refresh();
    let resolveNew!: (value: TaskList) => void;
    list.mockReturnValueOnce(new Promise<TaskList>(done => { resolveNew = done; }));
    instanceChanges.update(value => value + 1);
    expect(store.tasks()).toEqual([]);
    await settle();
    expect(list).toHaveBeenCalledTimes(3);
    const current = store.refresh();
    resolveOld({ items: [task] });
    await old;
    expect(store.tasks()).toEqual([]);
    expect(store.refresh()).toBe(current);
    const next: ConnectionImportTask = { ...task, id: 'ci_newtask12345', status: 'review' };
    resolveNew({ items: [next] });
    await current;
    expect(store.tasks()).toEqual([next]);
  });

  it('stops polling while the core is down and discards responses after stop or destruction', async () => {
    let resolveOld!: (value: TaskList) => void;
    list.mockReturnValueOnce(new Promise<TaskList>(done => { resolveOld = done; }));
    running.set(true);
    await settle();
    const old = store.refresh();
    running.set(false);
    await settle();
    resolveOld({ items: [task] });
    await old;
    expect(store.tasks()).toEqual([]);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(list).toHaveBeenCalledTimes(1);
    list.mockResolvedValueOnce({ items: [task] });
    running.set(true);
    await settle();
    expect(store.tasks()).toEqual([task]);
    let resolveLate!: (value: TaskList) => void;
    list.mockReturnValueOnce(new Promise<TaskList>(done => { resolveLate = done; }));
    const late = store.refresh();
    TestBed.resetTestingModule();
    resolveLate({ items: [task] });
    await late;
    expect(store.tasks()).toEqual([]);
    await vi.advanceTimersByTimeAsync(10_000);
    await store.refresh();
    expect(list).toHaveBeenCalledTimes(3);
  });
});
