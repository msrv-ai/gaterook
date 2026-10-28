import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';

import {
  brokerInfoPath, createCoreClient, ping, readBrokerInfo, signRequest, startBroker,
} from '../index.mjs';
import { exchange, fakeCore, issued, SECRET, tempHome } from './helpers.mjs';

const unix = process.platform !== 'win32';
const req = (o) => JSON.stringify({ v: 1, op: 'token', client: 'codex', endpoint: 'dev', grant: 'gr_abcdefghijkl',
  pid: 4242, bridge: '0.4.0', ...o }) + '\n';

describe('broker', () => {
  let core;
  let home;
  let broker;
  let answers;

  before(async () => {
    answers = [];
    core = await fakeCore({ tokens: async (r) => answers.shift()?.(r) ?? issued(core.port, r.endpoint) });
    home = tempHome('broker');
    broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET,
      instanceNonce: 'instance-nonce-of-the-test-core' }) });
  });

  after(async () => {
    await broker?.close();
    await core?.close();
    fs.rmSync(home, { recursive: true, force: true });
  });

  it('listens on a random name named in run/broker.json, owner-only', { skip: !unix }, () => {
    const info = readBrokerInfo(home);
    assert.equal(info.path, broker.path);
    assert.equal(path.dirname(broker.path), path.join(home, 'run'));
    assert.match(path.basename(broker.path), /^[A-Za-z0-9_-]{22}\.sock$/);
    assert.equal(fs.statSync(path.join(home, 'run')).mode & 0o777, 0o700);
    assert.equal(fs.statSync(broker.path).mode & 0o777, 0o600);
    assert.equal(fs.statSync(brokerInfoPath(home)).mode & 0o777, 0o600);
  });

  it('answers a ping without the core', async () => {
    const seen = core.seen.length;
    assert.deepEqual(JSON.parse(await exchange(broker.path, '{"v":1,"op":"ping"}\n')),
      { v: 1, ok: true, broker: '0.4.1' });
    assert.equal(core.seen.length, seen);
    assert.equal(await ping(broker.path), true);
  });

  it('forwards a token request to the core, signed, and the answer back as it is', async () => {
    const answer = JSON.parse(await exchange(broker.path, req({})));
    assert.equal(answer.ok, true);
    assert.equal(answer.token, 'gwb_test_token_1');
    assert.equal(answer.url, `http://127.0.0.1:${core.port}/mcp/dev`);
    const forwarded = core.seen.at(-1);
    assert.equal(forwarded.url, '/internal/bridge/tokens');
    assert.deepEqual(forwarded.body, { v: 1, op: 'token', client: 'codex', endpoint: 'dev', grant: 'gr_abcdefghijkl',
      pid: 4242, bridge: '0.4.0' });
    assert.match(forwarded.headers['x-gateway-auth'], /^\d+\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}$/);
  });

  it('passes the core\'s refusals on: revoked, mismatch', async () => {
    answers.push(() => ({ v: 1, ok: false, error: 'grant_revoked', message: 'Access for Codex to dev was revoked.' }));
    assert.deepEqual(JSON.parse(await exchange(broker.path, req({}))),
      { v: 1, ok: false, error: 'grant_revoked', message: 'Access for Codex to dev was revoked.' });
    answers.push(() => ({ v: 1, ok: false, error: 'grant_mismatch', message: 'Not for codex on prod.' }));
    assert.equal(JSON.parse(await exchange(broker.path, req({ endpoint: 'prod' }))).error, 'grant_mismatch');
  });

  it('refuses what isn\'t protocol v1 without asking the core', async () => {
    const seen = core.seen.length;
    for (const [line, why] of [['not json\n', 'isn\'t JSON'], ['{"v":2,"op":"token"}\n', 'protocol v1'],
      ['{"v":1,"op":"steal"}\n', 'Unknown operation'], [req({ grant: 42 }), 'malformed'],
      [req({ pid: 'x' }), 'malformed']]) {
      const a = JSON.parse(await exchange(broker.path, line));
      assert.equal(a.ok, false);
      assert.equal(a.error, 'bad_request');
      assert.match(a.message, new RegExp(why));
    }
    assert.equal(core.seen.length, seen);
  });

  it('cuts a request longer than 4 KiB and a connection that sends no line', async () => {
    assert.equal(await exchange(broker.path, 'x'.repeat(5000)), '');
    const started = Date.now();
    await new Promise((resolve) => {
      const s = net.connect(broker.path);
      s.on('close', resolve);
      s.on('error', resolve);
    });
    assert.ok(Date.now() - started >= 2500, 'the idle connection lived about 3 s');
  });

  it('says GateRook isn\'t running when the core doesn\'t answer', async () => {
    const deadHome = tempHome('broker-dead');
    let dead;
    try {
      dead = await startBroker({ home: deadHome, core: createCoreClient({
        origin: 'http://127.0.0.1:9', launchSecret: SECRET, timeoutMs: 500 }) });
      const a = JSON.parse(await exchange(dead.path, req({})));
      assert.equal(a.error, 'core_unavailable');
      assert.match(a.message, /GateRook is not running/);
    } finally {
      await dead?.close();
      fs.rmSync(deadHome, { recursive: true, force: true });
    }
  });

  it('refuses a core whose answer lacks the instance nonce', async () => {
    const otherHome = tempHome('broker-other');
    let other;
    try {
      other = await startBroker({ home: otherHome, core: createCoreClient({
        origin: core.origin, launchSecret: SECRET, instanceNonce: 'another-instance' }) });
      assert.equal(JSON.parse(await exchange(other.path, req({}))).error, 'core_unavailable');
    } finally {
      await other?.close();
      fs.rmSync(otherHome, { recursive: true, force: true });
    }
  });

  it('keeps a running broker\'s socket and replaces a stale one', { skip: !unix }, async () => {
    await assert.rejects(startBroker({ home, core: { request: async () => ({}) } }), /Another broker answers/);
    const staleHome = tempHome('broker-stale');
    let second;
    try {
      const first = await startBroker({ home: staleHome, core: { request: async () => ({}) } });
      const stalePath = first.path;
      // A crash leaves the socket file and broker.json behind.
      await new Promise((resolve) => first.close().then(resolve));
      fs.mkdirSync(path.join(staleHome, 'run'), { recursive: true });
      fs.writeFileSync(stalePath, '');
      fs.writeFileSync(brokerInfoPath(staleHome), JSON.stringify({ v: 1, path: stalePath }));
      second = await startBroker({ home: staleHome, core: { request: async () => ({}) } });
      assert.equal(fs.existsSync(stalePath), false);
      assert.notEqual(second.path, stalePath);
      await second.close();
      assert.equal(fs.existsSync(brokerInfoPath(staleHome)), false, 'close removes broker.json');
      assert.equal(fs.existsSync(second.path), false, 'and the socket');
      second = null;
    } finally {
      await second?.close();
      fs.rmSync(staleHome, { recursive: true, force: true });
    }
  });

  it('says why when the socket\'s name would be too long for this system', { skip: !unix }, async () => {
    // A Unix socket's name has at most 103 bytes on macOS and 107 on Linux; a deep gateway home can't hold one.
    const deep = path.join(tempHome('broker-deep'), 'x'.repeat(90));
    try {
      await assert.rejects(startBroker({ home: deep, core: { request: async () => ({}) } }),
        (e) => e.code === 'ENAMETOOLONG' && /too long for a Unix socket/.test(e.message));
    } finally {
      fs.rmSync(path.dirname(deep), { recursive: true, force: true });
    }
  });

  it('turns away another user: EACCES', { skip: !unix || process.getuid?.() !== 0 || !hasSetpriv() }, () => {
    // Run as nobody: the run folder is 0700 and the socket 0600, so connect() is refused by the kernel.
    const run = spawnSync('setpriv', ['--reuid=65534', '--regid=65534', '--clear-groups', process.execPath, '-e',
      `const s=require('net').connect(${JSON.stringify(broker.path)});s.on('connect',()=>{console.log('CONNECTED');`
        + `process.exit(0)});s.on('error',e=>{console.log(e.code);process.exit(0)})`], { encoding: 'utf8' });
    assert.equal(run.stdout.trim(), 'EACCES', run.stderr);
  });
});

describe('broker confirmation of prod grants', () => {
  it('asks the person once for parallel bridges, then forwards the decision and asks the core again', async () => {
    const decisions = [];
    let confirmed = false;
    const core = await fakeCore({
      tokens: async (r) => (confirmed ? issued(core.port, r.endpoint)
        : { v: 1, ok: false, error: 'confirmation_required', message: 'Allow Codex to use incident-prod…' }),
      confirm: (b) => { decisions.push(b); confirmed = b.decision === 'allow'; },
    });
    const home = tempHome('broker-confirm');
    let asked = 0;
    let broker;
    try {
      broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
        confirm: async (ask) => { asked++; assert.equal(ask.endpoint, 'incident-prod'); await sleep(200); return true; } });
      const [a, b] = await Promise.all([exchange(broker.path, req({ endpoint: 'incident-prod' })),
        exchange(broker.path, req({ endpoint: 'incident-prod' }))]);
      assert.equal(JSON.parse(a).ok, true);
      assert.equal(JSON.parse(b).ok, true);
      assert.equal(asked, 1, 'one dialog for both bridges');
      assert.deepEqual(decisions.map((d) => d.decision), ['allow']);
      assert.equal(decisions[0].grant, 'gr_abcdefghijkl');
    } finally {
      await broker?.close();
      await core.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  });

  for (const allowed of [true, false]) {
    it(`records a late ${allowed ? 'Allow' : 'Deny'} once after bridge timeouts and keeps one dialog`, async () => {
      const decisions = [];
      let choose;
      const choice = new Promise((resolve) => { choose = resolve; });
      let recorded;
      const recording = new Promise((resolve) => { recorded = resolve; });
      const grant = 'gr_abcdefghijkl';
      const core = await fakeCore({
        tokens: async (r) => {
          const decision = decisions.find((d) => d.grant === r.grant);
          if (decision?.decision === 'allow') return issued(core.port, r.endpoint);
          return { v: 1, ok: false, error: decision ? 'confirmation_denied' : 'confirmation_required',
            message: 'Permission for Cursor to use incident-prod.' };
        },
        confirm: (body) => { decisions.push(body); recorded(); },
      });
      const home = tempHome('broker-late');
      let asked = 0;
      let broker;
      try {
        broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
          confirmTimeoutMs: 25,
          confirm: async (ask) => {
            asked++;
            assert.equal(ask.client, 'cursor');
            assert.equal(ask.endpoint, 'incident-prod');
            assert.equal(ask.grant, grant);
            return choice;
          } });
        const request = req({ client: 'cursor', endpoint: 'incident-prod', grant });
        assert.equal(JSON.parse(await exchange(broker.path, request)).error, 'confirmation_required');
        assert.equal(JSON.parse(await exchange(broker.path, request)).error, 'confirmation_required');
        assert.equal(asked, 1, 'retries share the dialog after the previous bridge stopped waiting');
        assert.deepEqual(decisions, [], 'a timeout is not a decision');
        choose(allowed);
        await recording;
        const answer = JSON.parse(await exchange(broker.path, request));
        assert.equal(answer.ok, allowed);
        if (!allowed) assert.equal(answer.error, 'confirmation_denied');
        assert.equal(asked, 1, 'the recorded decision needs no further dialog');
        assert.deepEqual(decisions, [{ grant, decision: allowed ? 'allow' : 'deny' }]);
      } finally {
        await broker?.close();
        await core.close();
        fs.rmSync(home, { recursive: true, force: true });
      }
    });
  }

  it('keeps a late permission scoped to its grant while another grant is still waiting', async () => {
    const decisions = [];
    const firstGrant = 'gr_abcdefghijkl';
    const secondGrant = 'gr_mnopqrstuvwx';
    const asked = [];
    let choose;
    const choice = new Promise((resolve) => { choose = resolve; });
    let recorded;
    const recording = new Promise((resolve) => { recorded = resolve; });
    const core = await fakeCore({
      tokens: async (r) => decisions.some((d) => d.grant === r.grant && d.decision === 'allow')
        ? issued(core.port, r.endpoint)
        : { v: 1, ok: false, error: 'confirmation_required', message: 'Allow Cursor…' },
      confirm: (body) => { decisions.push(body); recorded(); },
    });
    const home = tempHome('broker-scoped');
    let broker;
    try {
      broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
        confirmTimeoutMs: 25,
        confirm: (ask) => {
          asked.push(ask.grant);
          return ask.grant === firstGrant ? choice : new Promise(() => {});
        } });
      const request = (grant) => req({ client: 'cursor', endpoint: 'incident-prod', grant });
      const answers = await Promise.all([exchange(broker.path, request(firstGrant)),
        exchange(broker.path, request(secondGrant))]);
      assert.ok(answers.every((answer) => JSON.parse(answer).error === 'confirmation_required'));
      choose(true);
      await recording;
      assert.equal(JSON.parse(await exchange(broker.path, request(firstGrant))).ok, true);
      assert.equal(JSON.parse(await exchange(broker.path, request(secondGrant))).error, 'confirmation_required');
      assert.deepEqual(asked.sort(), [firstGrant, secondGrant]);
      assert.deepEqual(decisions, [{ grant: firstGrant, decision: 'allow' }]);
    } finally {
      await broker?.close();
      await core.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  });

  it('discards a late dialog answer after the broker closes', async () => {
    const decisions = [];
    let choose;
    const choice = new Promise((resolve) => { choose = resolve; });
    const core = await fakeCore({
      tokens: async () => ({ v: 1, ok: false, error: 'confirmation_required', message: 'Allow Cursor…' }),
      confirm: (body) => decisions.push(body),
    });
    const home = tempHome('broker-closed');
    let broker;
    try {
      broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
        confirmTimeoutMs: 25, confirm: async () => choice });
      assert.equal(JSON.parse(await exchange(broker.path, req({ client: 'cursor' }))).error, 'confirmation_required');
      await broker.close();
      broker = null;
      choose(true);
      await sleep(25);
      assert.deepEqual(decisions, []);
    } finally {
      await broker?.close();
      await core.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  });

  it('leaves confirmation required when the dialog returns no decision', async () => {
    const decisions = [];
    const core = await fakeCore({
      tokens: async () => ({ v: 1, ok: false, error: 'confirmation_required', message: 'Allow Cursor…' }),
      confirm: (body) => decisions.push(body),
    });
    const home = tempHome('broker-undecided');
    let broker;
    try {
      broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
        confirm: async () => null });
      assert.equal(JSON.parse(await exchange(broker.path, req({ client: 'cursor' }))).error, 'confirmation_required');
      assert.deepEqual(decisions, []);
    } finally {
      await broker?.close();
      await core.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  });

  it('forwards a denial, and passes the core\'s answer on when nobody can be asked', async () => {
    const decisions = [];
    const core = await fakeCore({
      tokens: async () => (decisions.at(-1)?.decision === 'deny'
        ? { v: 1, ok: false, error: 'confirmation_denied', message: 'You didn\'t allow Codex…' }
        : { v: 1, ok: false, error: 'confirmation_required', message: 'Allow Codex…' }),
      confirm: (b) => decisions.push(b),
    });
    const homeA = tempHome('broker-deny');
    const homeB = tempHome('broker-noask');
    let deny;
    let noAsk;
    try {
      deny = await startBroker({ home: homeA, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }),
        confirm: async () => false });
      noAsk = await startBroker({ home: homeB, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }) });
      assert.equal(JSON.parse(await exchange(noAsk.path, req({}))).error, 'confirmation_required');
      assert.equal(decisions.length, 0, 'nobody was asked, nothing forwarded');
      assert.equal(JSON.parse(await exchange(deny.path, req({}))).error, 'confirmation_denied');
      assert.equal(decisions.at(-1).decision, 'deny');
    } finally {
      await deny?.close();
      await noAsk?.close();
      await core.close();
      fs.rmSync(homeA, { recursive: true, force: true });
      fs.rmSync(homeB, { recursive: true, force: true });
    }
  });
});

describe('createCoreClient', () => {
  it('goes straight to the core even when Node is told to use a proxy', async () => {
    // NODE_USE_ENV_PROXY makes Node's global agent follow HTTP_PROXY, for 127.0.0.1 too; the answers hold tokens.
    const proxied = [];
    const proxy = http.createServer((r, res) => {
      proxied.push(`${r.method} ${r.url}`);
      res.writeHead(502, { 'Content-Length': 0 });
      res.end();
    });
    await new Promise((resolve) => proxy.listen(0, '127.0.0.1', resolve));
    const viaProxy = `http://127.0.0.1:${proxy.address().port}`;
    const core = await fakeCore({ tokens: async () => ({ v: 1, ok: true, token: 'gwb_from_the_core' }) });
    try {
      const client = new URL('../src/core-client.mjs', import.meta.url).href;
      const script = `import { createCoreClient } from ${JSON.stringify(client)};
        createCoreClient({ origin: ${JSON.stringify(core.origin)}, launchSecret: ${JSON.stringify(SECRET)} })
          .request('/internal/bridge/tokens', { v: 1 })
          .then((a) => console.log(JSON.stringify(a)), (e) => console.log('failed: ' + e.message));`;
      const out = await node(['--input-type=module', '-e', script],
        { NODE_USE_ENV_PROXY: '1', HTTP_PROXY: viaProxy, http_proxy: viaProxy });
      assert.match(out, /gwb_from_the_core/);
      assert.deepEqual(proxied, []);
    } finally {
      await core.close();
      await new Promise((resolve) => { proxy.closeAllConnections(); proxy.close(resolve); });
    }
  });
});

describe('signRequest', () => {
  it('matches the core\'s LaunchSecret.sign for a fixed vector', () => {
    // The same vector is checked against platform.LaunchSecret in the core's ClientsUnitTest.
    const secret = Buffer.from('0123456789abcdef0123456789abcdef').toString('base64url');
    assert.equal(signRequest(secret, 'POST', '/internal/bridge/tokens', 1792900000000, 'AAAAAAAAAAAAAAAAAAAAAA'),
      '1792900000000.AAAAAAAAAAAAAAAAAAAAAA.pW2mL07tz9Ju6YR3gKetHyKTmOuLxaf-sj4y2G1o828');
  });
});

function hasSetpriv() {
  return spawnSync('setpriv', ['--version']).status === 0;
}

/** Runs this Node with `args` and only `env` (and PATH); resolves with its stdout. */
function node(args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, { env: { PATH: process.env.PATH, ...env } });
    let out = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (d) => { out += d; });
    child.on('error', reject);
    child.on('exit', () => resolve(out));
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
