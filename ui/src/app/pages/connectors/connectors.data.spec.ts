import { ApiError } from '../../core/api/api-error';
import { Source } from './connectors.data';

/** The page's reloadable answers: what shows while loading, after a failure, and how reloads coalesce. */

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('connectors data: Source', () => {
  it('keeps one load in flight and runs one more after it for changes that came meanwhile', async () => {
    const answers = [deferred<number>(), deferred<number>()];
    let loads = 0;
    const after: number[] = [];
    const source = new Source<number>(
      () => answers[loads++].promise,
      (v) => after.push(v),
    );
    const first = source.refresh();
    const second = source.refresh();
    void source.refresh();
    expect(loads).toBe(1);
    expect(source.loading()).toBe(true);
    expect(second).toBe(first);

    answers[0].resolve(1);
    await first;
    expect(source.value()).toBe(1);
    // The calls made during the first load are served by one more load, not two.
    await Promise.resolve();
    expect(loads).toBe(2);
    answers[1].resolve(2);
    await new Promise((r) => setTimeout(r, 0));
    expect(source.value()).toBe(2);
    expect(after).toEqual([1, 2]);
    expect(source.loading()).toBe(false);
  });

  it('says why the first load failed, and keeps the last answer when a reload fails', async () => {
    let fail: unknown = new ApiError('server', 500, { detail: 'busy' });
    const source = new Source<string>(() => (fail ? Promise.reject(fail) : Promise.resolve('ok')));
    await source.refresh();
    expect(source.value()).toBeNull();
    expect(source.error()).toBe(fail);
    expect(source.notAvailable()).toBe(false);

    fail = null;
    await source.refresh();
    expect(source.value()).toBe('ok');
    expect(source.error()).toBeNull();

    fail = new Error('offline');
    await source.refresh();
    expect(source.value()).toBe('ok');
    expect(source.error()).toBeNull();
    expect((source.stale() as Error).message).toBe('offline');
  });

  it('tells a route this core doesn’t have yet apart from a failure', async () => {
    const source = new Source<string>(() => Promise.reject(new ApiError('not-available', 404, {})));
    await source.refresh();
    expect(source.notAvailable()).toBe(true);
    source.set('later');
    expect(source.notAvailable()).toBe(false);
    expect(source.value()).toBe('later');
  });
});
