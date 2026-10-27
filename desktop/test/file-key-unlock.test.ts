import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { FileKeyUnlock, type UnlockState } from '../src/main/file-key-unlock.ts';
import { FileKeyAccessError, type FileKeyPlan } from '../src/main/file-key.ts';
import { CoreSupervisor } from '../src/main/core-supervisor.ts';

const keyPlan = (): FileKeyPlan => ({ fileKey: Buffer.alloc(32, 7), protection: 'os', note: null });

function setup(load: () => Promise<FileKeyPlan>) {
  const states: (UnlockState | null)[] = [];
  let denied = 0;
  const gate = new FileKeyUnlock({ load, changed: (state) => states.push(state), log: () => undefined,
    denied: () => { denied++; },
    keychainService: 'GateRook Dev Safe Storage' });
  return { gate, states, denied: () => denied };
}

describe('explicit OS-key-store unlock', () => {
  it('waits before any OS probe, replays its state, and authorizes subsequent starts in the same session', async () => {
    let calls = 0;
    const { gate, states } = setup(async () => { calls++; return keyPlan(); });
    const pending = gate.prepare();
    assert.equal(calls, 0);
    assert.deepEqual(gate.state(), { kind: 'unlock-required', unlocking: false, error: null,
      keychainService: 'GateRook Dev Safe Storage' });
    assert.equal(gate.prepare(), pending);
    await gate.unlock();
    const first = (await pending).fileKey!;
    assert.equal(first.length, 32);
    first.fill(0); // the bootstrap writer zeroes its copy
    assert.equal(calls, 1);
    assert.equal(gate.state(), null);
    assert.deepEqual(states.map((state) => state?.unlocking ?? null), [false, true, null]);
    const restarted = await gate.prepare();
    assert.deepEqual(restarted.fileKey, Buffer.alloc(32, 7));
    restarted.fileKey!.fill(0);
    assert.deepEqual((await gate.prepare()).fileKey, Buffer.alloc(32, 7));
    assert.equal(calls, 1, 'restarting the core reuses a separate session copy without another OS request');
    gate.close();
    assert.equal((await gate.prepare()).fileKey, null);
  });

  it('deduplicates clicks and closes exactly once after denial without another OS request', async () => {
    let resolve!: (plan: FileKeyPlan) => void;
    let calls = 0;
    const { gate, denied } = setup(() => { calls++; return new Promise((r) => { resolve = r; }); });
    const pending = gate.prepare();
    const first = gate.unlock();
    assert.equal(gate.unlock(), first);
    assert.equal(calls, 1);
    assert.equal(gate.state()?.unlocking, true);
    resolve({ fileKey: null, protection: 'unavailable', note: null, accessFailed: true });
    await first;
    assert.equal((await pending).fileKey, null);
    assert.equal(gate.state(), null);
    assert.equal(denied(), 1);
    await gate.unlock();
    assert.equal(calls, 1);
    assert.equal(denied(), 1);
  });

  it('an encryption API failure on first launch closes the application', async () => {
    const { gate, denied } = setup(async () => { throw new FileKeyAccessError('Error while encrypting the text provided to safeStorage.encryptStringAsync.'); });
    const pending = gate.prepare();
    await gate.unlock();
    assert.equal((await pending).fileKey, null);
    assert.equal(gate.state(), null);
    assert.equal(denied(), 1);
  });

  it('a local persistence failure can retry without restarting the OS provider', async () => {
    const { gate, denied } = setup(async () => { throw new Error('EACCES: permission denied'); });
    const pending = gate.prepare();
    await gate.unlock();
    assert.equal(denied(), 0);
    assert.match(gate.state()?.error ?? '', /data folder/);
    gate.close();
    await pending;
  });

  it('native denial shuts down the supervisor before it can spawn an unprotected core', async () => {
    let commands = 0;
    let shutdown: Promise<void> | null = null;
    const gate = new FileKeyUnlock({
      load: async () => ({ fileKey: null, protection: 'unavailable', note: null, accessFailed: true }),
      changed: () => undefined,
      log: () => undefined,
      denied: () => { shutdown = supervisor.stop(); },
    });
    const supervisor = new CoreSupervisor({
      version: '0.4.0', home: '/unused', ports: () => ({ port: 7474, publicPort: 7475 }),
      command: () => { commands++; throw new Error('The core must not be spawned'); },
      fileKey: () => gate.prepare(), log: () => undefined,
    });
    const starting = supervisor.start();
    assert.equal(supervisor.pid(), null);
    await gate.unlock();
    await starting;
    await shutdown;
    assert.equal(commands, 0);
    assert.equal(supervisor.pid(), null);
    await gate.unlock();
    await supervisor.start();
    assert.equal(commands, 0);
  });

  it('missing or corrupt key material still reaches the existing recovery flow', async () => {
    const recovery = { fileKey: null, protection: 'off', note: 'the protected key is missing' };
    const { gate } = setup(async () => recovery);
    const pending = gate.prepare();
    await gate.unlock();
    assert.equal(await pending, recovery);
    assert.equal(gate.state(), null);
  });

  it('quit releases the waiting startup without touching the key store', async () => {
    let calls = 0;
    const { gate } = setup(async () => { calls++; return keyPlan(); });
    const pending = gate.prepare();
    gate.close();
    assert.equal((await pending).fileKey, null);
    await gate.unlock();
    assert.equal(calls, 0);
    assert.equal((await gate.prepare()).fileKey, null);
  });

  it('quit does not wait for a system dialog and zeroes a key that arrives afterwards', async () => {
    let resolve!: (plan: FileKeyPlan) => void;
    const { gate } = setup(() => new Promise((r) => { resolve = r; }));
    const pending = gate.prepare();
    const unlock = gate.unlock();
    gate.close();
    assert.equal((await pending).fileKey, null);
    const plan = keyPlan();
    resolve(plan);
    await unlock;
    assert.deepEqual(plan.fileKey, Buffer.alloc(32));
    assert.equal(gate.state(), null);
  });
});
