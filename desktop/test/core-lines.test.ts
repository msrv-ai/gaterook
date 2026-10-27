import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { failedState, parseCoreLine, readyState, StartProgress } from '../src/main/core-lines.ts';

describe('core stdout lines', () => {
  it('reads progress lines', () => {
    assert.deepEqual(parseCoreLine('{"v":1,"step":"db-migrations","state":"running"}'),
      { kind: 'progress', step: 'db-migrations', state: 'running' });
    assert.equal(parseCoreLine('{"v":1,"step":"db-migrations","state":"sleeping"}'), null);
    assert.equal(parseCoreLine('{"v":2,"step":"db-migrations","state":"running"}'), null);
  });

  it('reads the ready line, the pid stays with main', () => {
    const line = parseCoreLine('{"v":1,"ready":true,"firstRun":true,"locked":null,"version":"0.4.0",'
      + '"ports":{"local":7465,"public":7466},"runtime":"native","pid":4321}');
    assert.deepEqual(line, { kind: 'ready', firstRun: true, locked: null, version: '0.4.0',
      ports: { local: 7465, public: 7466 }, runtime: 'native', pid: 4321 });
    assert.ok(line && line.kind === 'ready');
    assert.deepEqual(readyState(line), { kind: 'ready', firstRun: true, locked: null, version: '0.4.0',
      ports: { local: 7465, public: 7466 }, runtime: 'native' });
  });

  it('reads the locked modes and nothing else as a lock', () => {
    const base = '"ports":{"local":7465,"public":7466},"runtime":"jvm","version":"0.4.0"';
    const ready = (locked: string) => parseCoreLine(`{"v":1,"ready":true,"locked":${locked},${base}}`);
    assert.equal((ready('"MASTER_KEY_MISSING"') as { locked: unknown }).locked, 'MASTER_KEY_MISSING');
    assert.equal((ready('"MASTER_KEY_INVALID"') as { locked: unknown }).locked, 'MASTER_KEY_INVALID');
    assert.equal((ready('"SOMETHING"') as { locked: unknown }).locked, null);
  });

  it('reads a failure with its owner of the port', () => {
    const line = parseCoreLine('{"v":1,"ready":false,"reason":"PORT_IN_USE","detail":"Address already in use: 127.0.0.1:7465",'
      + '"portOwner":{"pid":99,"user":"alice"},"log":"/h/logs/core.log"}');
    assert.deepEqual(line, { kind: 'failed', reason: 'PORT_IN_USE', detail: 'Address already in use: 127.0.0.1:7465',
      portOwner: { pid: 99, user: 'alice' }, log: '/h/logs/core.log' });
    assert.ok(line && line.kind === 'failed');
    assert.deepEqual(failedState(line, { port: 7465, publicPort: 7466, version: '0.4.0' }, { exitCode: 2, logTail: 'x' }), {
      kind: 'failed', reason: 'PORT_IN_USE', detail: 'Address already in use: 127.0.0.1:7465',
      portOwner: { pid: 99, user: 'alice' }, log: '/h/logs/core.log', logTail: 'x', exitCode: 2, port: 7465,
      publicPort: 7466, version: '0.4.0' });
  });

  it('ignores whatever is not the protocol', () => {
    for (const text of ['', 'Started GatewayApplication in 1.2 s', '[1,2]', '{"v":1}', '{"v":1,"ready":true}', 'null']) {
      assert.equal(parseCoreLine(text), null, text);
    }
  });
});

describe('start progress', () => {
  it('adds the runtime step and times every step on main\'s clock', () => {
    const p = new StartProgress({ port: 7475, publicPort: 7466, version: '0.4.0' }, 1000);
    assert.deepEqual(p.state(), { kind: 'starting', steps: [{ step: 'runtime', state: 'running' }], port: 7475,
      publicPort: 7466, version: '0.4.0' });
    p.progress({ step: 'master-key', state: 'running' }, 1120);
    const s = p.progress({ step: 'master-key', state: 'done' }, 1150);
    assert.deepEqual(s.kind === 'starting' ? s.steps : null, [
      { step: 'runtime', state: 'done', ms: 120 },
      { step: 'master-key', state: 'done', ms: 30 },
    ]);
    p.progress({ step: 'db-migrations', state: 'running' }, 1200);
    const f = p.progress({ step: 'db-migrations', state: 'failed' }, 1300);
    assert.deepEqual(f.kind === 'starting' ? f.steps.at(-1) : null, { step: 'db-migrations', state: 'failed' });
  });

  it('a done line without its running line has no time', () => {
    const p = new StartProgress({ port: 1, publicPort: 2, version: 'v' }, 0);
    const s = p.progress({ step: 'local-api', state: 'done' }, 10);
    assert.deepEqual(s.kind === 'starting' ? s.steps.at(-1) : null, { step: 'local-api', state: 'done', ms: null });
  });
});
