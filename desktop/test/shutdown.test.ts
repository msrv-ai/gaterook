import assert from 'node:assert/strict';
import { setImmediate } from 'node:timers/promises';
import { describe, it } from 'node:test';
import { ShutdownSequence } from '../src/main/shutdown.ts';

describe('application shutdown', () => {
  it('shares one operation and waits for the core and broker before allowing quit', async () => {
    const core = Promise.withResolvers<void>();
    const broker = Promise.withResolvers<void>();
    const order: string[] = [];
    const shutdown = new ShutdownSequence([
      { name: 'events', run: () => { order.push('events'); } },
      { name: 'core', run: async () => { order.push('core'); await core.promise; } },
      { name: 'broker', run: async () => { order.push('broker'); await broker.promise; } },
      { name: 'tray', run: () => { order.push('tray'); } },
    ], () => assert.fail('cleanup should succeed'));

    const first = shutdown.run();
    assert.equal(shutdown.run(), first);
    await setImmediate();
    assert.deepEqual(order, ['events', 'core']);
    assert.equal(shutdown.completed, false);
    assert.equal(shutdown.run(), first, 'another quit must wait for the running core cleanup');

    core.resolve();
    await setImmediate();
    assert.deepEqual(order, ['events', 'core', 'broker']);
    assert.equal(shutdown.completed, false);
    assert.equal(shutdown.run(), first, 'another quit must wait for the running broker cleanup');

    broker.resolve();
    await first;
    assert.deepEqual(order, ['events', 'core', 'broker', 'tray']);
    assert.equal(shutdown.completed, true);
    assert.equal(shutdown.run(), first);
    assert.deepEqual(order, ['events', 'core', 'broker', 'tray'], 'completed cleanup does not run again');
  });

  it('attempts every cleanup and rejects installation if any step failed', async () => {
    const firstError = new Error('Could not stop event delivery');
    const errors: string[] = [];
    const order: string[] = [];
    const shutdown = new ShutdownSequence([
      { name: 'events', run: () => { order.push('events'); throw firstError; } },
      { name: 'unlock', run: () => { order.push('unlock'); } },
      { name: 'core', run: async () => { order.push('core'); throw new Error('Core shutdown failed'); } },
      { name: 'broker', run: async () => { order.push('broker'); } },
      { name: 'tray', run: () => { order.push('tray'); } },
    ], (step, error) => { errors.push(`${step}: ${error.message}`); });
    let installed = false;
    const operation = shutdown.run().then(() => { installed = true; });
    await assert.rejects(operation, (error) => error === firstError);
    assert.deepEqual(order, ['events', 'unlock', 'core', 'broker', 'tray']);
    assert.deepEqual(errors, ['events: Could not stop event delivery', 'core: Core shutdown failed']);
    assert.equal(installed, false);
    assert.equal(shutdown.completed, true, 'a recovery quit is now allowed');
  });

  it('lets an ordinary quit finish after failed cleanup, but only after later resources close', async () => {
    const broker = Promise.withResolvers<void>();
    const shutdown = new ShutdownSequence([
      { name: 'core', run: () => { throw new Error('Core shutdown failed'); } },
      { name: 'broker', run: () => broker.promise },
      { name: 'tray', run: () => { throw new Error('Tray was already destroyed'); } },
    ], () => undefined);
    let quit = false;
    const finishQuit = (): void => { assert.equal(shutdown.completed, true); quit = true; };
    const first = shutdown.run();
    const quitting = first.then(finishQuit, finishQuit);
    await setImmediate();
    assert.equal(quit, false);
    assert.equal(shutdown.completed, false);
    assert.equal(shutdown.run(), first);
    broker.resolve();
    await quitting;
    assert.equal(quit, true);
    assert.equal(shutdown.completed, true);
    await assert.rejects(shutdown.run(), /Core shutdown failed/);
  });

  it('does not start another sequence if a cleanup callback requests quit synchronously', async () => {
    let repeated: Promise<void> | null = null;
    let calls = 0;
    const shutdown = new ShutdownSequence([
      { name: 'events', run: () => { calls++; repeated = shutdown.run(); } },
      { name: 'core', run: async () => { calls++; } },
    ], () => undefined);
    const first = shutdown.run();
    await first;
    assert.equal(repeated, first);
    assert.equal(calls, 2);
    assert.equal(shutdown.completed, true);
  });

  it('marks the core stopping before a cancelled unlock can resume startup', async () => {
    const unlock = Promise.withResolvers<void>();
    let stopping = false;
    const startup = unlock.promise.then(() => {
      assert.equal(stopping, true, 'cancelling unlock must not let startup spawn an unprotected core');
    });
    const shutdown = new ShutdownSequence([
      { name: 'unlock', run: () => unlock.resolve() },
      { name: 'native unlock', run: () => undefined },
      { name: 'core', run: () => { stopping = true; return startup; } },
    ], () => assert.fail('shutdown should succeed'));
    await shutdown.run();
    assert.equal(shutdown.completed, true);
  });

  it('continues closing resources even if reporting a cleanup failure also throws', async () => {
    const order: string[] = [];
    const shutdown = new ShutdownSequence([
      { name: 'events', run: () => { throw new Error('Event cleanup failed'); } },
      { name: 'core', run: async () => { order.push('core'); } },
      { name: 'broker', run: async () => { order.push('broker'); } },
    ], () => { throw new Error('Log unavailable'); });
    await assert.rejects(shutdown.run(), /Event cleanup failed/);
    assert.deepEqual(order, ['core', 'broker']);
    assert.equal(shutdown.completed, true);
  });
});
