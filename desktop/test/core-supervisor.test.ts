import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer, type Server } from 'node:net';
import { join } from 'node:path';
import { after, afterEach, describe, it } from 'node:test';
import type { DesktopCoreState } from '../src/shared/contract.ts';
import { CoreSupervisor, logTail, type SupervisorOptions } from '../src/main/core-supervisor.ts';
import { callCoreOk } from '../src/main/core-client.ts';
import { FAKE_CORE, tempDir, waitFor } from './helpers.ts';
import { NativeUnlockError } from '../src/main/native-unlock.ts';

const t = tempDir('supervisor');
after(t.remove);
// A failed assertion must not leave a core running (it would keep the test process alive).
const started: CoreSupervisor[] = [];
afterEach(async () => {
  await Promise.all(started.splice(0).map((s) => s.stop()));
});

function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => resolve(port));
    });
  });
}

let n = 0;
async function setup(control: Record<string, unknown> = {}, over: Partial<SupervisorOptions> = {}) {
  const home = join(t.dir, `home-${++n}`);
  mkdirSync(home, { recursive: true });
  writeFileSync(join(home, 'fake-core.json'), JSON.stringify(control));
  const port = await freePort();
  const states: DesktopCoreState[] = [];
  const logs: string[] = [];
  const supervisor = new CoreSupervisor({
    version: '0.4.0',
    home,
    ports: () => ({ port, publicPort: port === 65535 ? 65534 : port + 1 }),
    command: () => ({ file: process.execPath, args: [FAKE_CORE, `-Dgateway.home=${home}`], env: { PATH: process.env['PATH'] ?? '' } }),
    fileKey: async () => ({ fileKey: Buffer.alloc(32, 9), protection: 'os' }),
    log: (l) => logs.push(l),
    restartDelaysMs: [30, 30, 30],
    ...over,
  });
  supervisor.onState((s) => states.push(s));
  started.push(supervisor);
  const runs = () => readFileSync(join(home, 'fake-core-runs.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  return { home, port, supervisor, states, logs, runs };
}

const kinds = (states: DesktopCoreState[]) => states.map((s) => s.kind);
const alive = (pid: number): boolean => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

describe('the core process', () => {
  for (const error of [new NativeUnlockError('unavailable'), new Error('provider preparation failed')]) {
    it(`never starts an unprotected core when key preparation rejects: ${error.name}`, async () => {
      let commands = 0;
      const { supervisor, runs } = await setup({}, {
        fileKey: async () => { throw error; },
        command: () => { commands++; throw new Error('The core must not start'); },
      });
      await supervisor.start();
      assert.equal(supervisor.state().kind, 'failed');
      assert.equal(supervisor.pid(), null);
      assert.equal(commands, 0);
      assert.throws(runs, { code: 'ENOENT' });
      await supervisor.restart();
      assert.equal(supervisor.state().kind, 'failed');
      assert.equal(commands, 0, 'retry does not downgrade protection');
    });
  }

  it('starts with the bootstrap line, reports the steps, says ready, stops on stdin EOF', async () => {
    const { supervisor, states, runs, port } = await setup();
    let ready = 0;
    supervisor.onReady(() => ready++);
    await supervisor.start();
    await waitFor('ready', () => supervisor.state().kind === 'ready');
    const starting = states.filter((s) => s.kind === 'starting');
    assert.deepEqual(starting[0], { kind: 'starting', steps: [{ step: 'runtime', state: 'running' }], port, publicPort: port + 1,
      version: '0.4.0' });
    const last = starting.at(-1);
    assert.ok(last && last.kind === 'starting');
    assert.deepEqual(last.steps.map((s) => `${s.step}:${s.state}`), ['runtime:done', 'master-key:done', 'db-migrations:done',
      'local-api:done']);
    assert.ok(last.steps.every((s) => typeof s.ms === 'number'), 'every finished step has its time');
    assert.deepEqual(supervisor.state(), { kind: 'ready', firstRun: false, locked: null, version: '0.4.0',
      ports: { local: port, public: port + 1 }, runtime: 'jvm' });
    assert.equal(ready, 1);
    // What the core got: the ports, the protection, a file key (the fake records only that one came).
    assert.deepEqual(runs().map(({ port: p, publicPort, protection, fileKey }) => ({ p, publicPort, protection, fileKey })),
      [{ p: port, publicPort: port + 1, protection: 'os', fileKey: true }]);
    const endpoint = supervisor.endpoint();
    assert.ok(endpoint);
    assert.equal(Buffer.from(endpoint.launchSecret, 'base64url').length, 32);
    // The admin API answers main, signed with this launch's secret.
    const health = await callCoreOk<{ status: string }>(endpoint, 'GET', '/api/health');
    assert.equal(health.status, 'ok');
    // A renderer that subscribes now sees the steps, then ready.
    assert.deepEqual(kinds(supervisor.replay()), ['starting', 'ready']);
    const pid = supervisor.pid()!;
    await supervisor.stop();
    assert.equal(alive(pid), false);
    assert.equal(supervisor.endpoint(), null);
  });

  it('a taken port is PORT_IN_USE with the exit code and the log', async () => {
    const { supervisor, port } = await setup();
    const squatter: Server = createServer();
    await new Promise<void>((r) => squatter.listen(port, '127.0.0.1', r));
    try {
      await supervisor.start();
      const failed = await waitFor('failed', () => {
        const s = supervisor.state();
        return s.kind === 'failed' ? s : null;
      });
      assert.equal(failed.reason, 'PORT_IN_USE');
      assert.deepEqual(failed.portOwner, { pid: 4242, user: 'someone' });
      assert.equal(failed.exitCode, 2);
      assert.equal(failed.port, port);
      assert.match(failed.log ?? '', /core\.log$/);
      assert.match(failed.logTail ?? '', /ERROR can't bind/);
      assert.equal(supervisor.endpoint(), null);
    } finally {
      await new Promise((r) => squatter.close(r));
    }
  });

  it('a core that never says ready is stopped: READY_TIMEOUT', async () => {
    const { supervisor } = await setup({ hang: true }, { readyTimeoutMs: 400 });
    await supervisor.start();
    const pid = supervisor.pid()!;
    const failed = await waitFor('failed', () => {
      const s = supervisor.state();
      return s.kind === 'failed' ? s : null;
    });
    assert.equal(failed.reason, 'READY_TIMEOUT');
    assert.equal(alive(pid), false);
  });

  it('a crash after ready restarts the core three times, then the screen says stopped', async () => {
    const { supervisor, states, runs } = await setup({ crashAfterReadyMs: 50 });
    await supervisor.start();
    const stopped = await waitFor('stopped', () => {
      const s = supervisor.state();
      return s.kind === 'stopped' ? s : null;
    }, 20_000);
    assert.equal(runs().length, 4, 'the first start and three restarts');
    assert.equal(kinds(states).filter((k) => k === 'ready').length, 4);
    assert.equal(stopped.exitCode, 137);
    assert.match(stopped.reason ?? '', /OutOfMemoryError/);
    assert.ok((stopped.uptimeMs ?? -1) >= 0);
    assert.ok(stopped.at && !Number.isNaN(Date.parse(stopped.at)));
    assert.match(stopped.logTail ?? '', /fake crash/);
  });

  it('Restart starts a new launch with new secrets; restart resets the crash count', async () => {
    const { supervisor, runs, states } = await setup();
    await supervisor.start();
    await waitFor('ready', () => supervisor.endpoint());
    const first = supervisor.endpoint()!;
    const before = states.length;
    await supervisor.restart();
    // The screen leaves `ready` at once, before the old core has even stopped.
    assert.deepEqual(states[before], { kind: 'starting', steps: [], port: first.port, publicPort: first.publicPort, version: '0.4.0' });
    await waitFor('ready again', () => supervisor.endpoint());
    const second = supervisor.endpoint()!;
    assert.notEqual(first.launchSecret, second.launchSecret);
    assert.notEqual(first.instanceNonce, second.instanceNonce);
    assert.equal(runs().length, 2);
    assert.equal(alive(first.pid!), false);
    // The old launch's secret no longer opens anything.
    await assert.rejects(callCoreOk(first, 'GET', '/api/health'));
    await supervisor.stop();
  });

  it('the locked core is ready, and says so', async () => {
    const { supervisor } = await setup({ locked: 'MASTER_KEY_MISSING' });
    await supervisor.start();
    await waitFor('ready', () => supervisor.endpoint());
    assert.deepEqual(supervisor.state(), { kind: 'ready', firstRun: false, locked: 'MASTER_KEY_MISSING', version: '0.4.0',
      ports: supervisor.endpoint() ? { local: supervisor.endpoint()!.port, public: supervisor.endpoint()!.publicPort } : null,
      runtime: 'jvm' });
    await supervisor.stop();
  });

  it('a core unlocked by Restore or Create is ready without the lock, for the screen and a later subscriber', async () => {
    const { supervisor, states } = await setup({ locked: 'MASTER_KEY_MISSING' });
    const unlockedFor: string[] = [];
    supervisor.onReady((c) => unlockedFor.push(c.instanceNonce));
    await supervisor.start();
    const locked = await waitFor('ready', () => supervisor.endpoint());
    assert.equal(locked.locked, 'MASTER_KEY_MISSING');
    assert.equal(supervisor.unlocked('another-launch-0000'), null, 'only the launch that is running');
    const now = supervisor.unlocked(locked.instanceNonce);
    assert.ok(now);
    assert.equal(now.locked, null);
    assert.equal(now.instanceNonce, locked.instanceNonce, 'the same launch: no restart');
    assert.equal(supervisor.endpoint()?.locked, null);
    assert.deepEqual(states.at(-1), { kind: 'ready', firstRun: false, locked: null, version: '0.4.0',
      ports: { local: locked.port, public: locked.publicPort }, runtime: 'jvm' });
    assert.equal((supervisor.replay().at(-1) as { locked?: unknown }).locked, null, 'a reloaded page sees it unlocked');
    assert.equal(supervisor.unlocked(locked.instanceNonce), null, 'nothing more to unlock');
    assert.deepEqual(unlockedFor, [locked.instanceNonce], 'not a new start: the ready listeners don\'t run again');
    await supervisor.stop();
    assert.equal(supervisor.unlocked(locked.instanceNonce), null);
  });

  it('a start the core refuses keeps its reason (DB_LOCKED)', async () => {
    const { supervisor } = await setup({ failReason: 'DB_LOCKED' });
    await supervisor.start();
    const failed = await waitFor('failed', () => {
      const s = supervisor.state();
      return s.kind === 'failed' ? s : null;
    });
    assert.equal(failed.reason, 'DB_LOCKED');
    assert.equal(failed.exitCode, 2);
  });

  it('a core that can\'t be run at all is STARTUP_FAILED with the reason', async () => {
    const { supervisor } = await setup({}, { command: () => ({ file: join(t.dir, 'no-such-core'), args: [], env: {} }) });
    await supervisor.start();
    const failed = await waitFor('failed', () => {
      const s = supervisor.state();
      return s.kind === 'failed' ? s : null;
    });
    assert.equal(failed.reason, 'STARTUP_FAILED');
    assert.match(failed.detail ?? '', /Can't run the core: .*ENOENT/);
  });
});

describe('log tail', () => {
  it('reads the end of the log, else stderr', () => {
    const file = join(t.dir, 'core.log');
    writeFileSync(file, Array.from({ length: 20 }, (_, i) => `line ${i}`).join('\n') + '\n\n');
    assert.equal(logTail(file, [], 3), 'line 17\nline 18\nline 19');
    assert.equal(logTail(join(t.dir, 'missing.log'), ['GateRook core: bad', ''], 3), 'GateRook core: bad');
    assert.equal(logTail(null, [], 3), null);
  });
});
