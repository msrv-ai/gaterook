import assert from 'node:assert/strict';
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import { writeFileAtomic } from '../src/main/atomic-file.ts';
import { DEFAULTS, portProblem, portRequest, publicPortProblem, readDesktopConfig, writeDesktopConfig }
  from '../src/main/desktop-config.ts';
import { findFreePort, canBind } from '../src/main/ports.ts';
import { createServer, type Server } from 'node:net';
import { tempDir } from './helpers.ts';

const t = tempDir('config');
after(t.remove);

describe('desktop.json', () => {
  it('has the defaults when it is missing', () => {
    assert.deepEqual(readDesktopConfig(join(t.dir, 'nothing-here')), { config: DEFAULTS, problem: null });
  });

  it('is written owner-only and read back', () => {
    const home = join(t.dir, 'home-a');
    writeDesktopConfig(home, { port: 7475, publicPort: 7466, startHidden: false });
    assert.deepEqual(readDesktopConfig(home).config, { port: 7475, publicPort: 7466, startHidden: false });
    if (process.platform !== 'win32') {
      assert.equal(statSync(join(home, 'desktop.json')).mode & 0o777, 0o600);
      assert.equal(statSync(home).mode & 0o777, 0o700);
    }
    assert.deepEqual(JSON.parse(readFileSync(join(home, 'desktop.json'), 'utf8')),
      { port: 7475, publicPort: 7466, startHidden: false });
  });

  it('refuses to write a port the core would refuse', () => {
    assert.throws(() => writeDesktopConfig(join(t.dir, 'home-b'), { port: 80, publicPort: 7466 }), /1024/);
    assert.throws(() => writeDesktopConfig(join(t.dir, 'home-b'), { port: 7466, publicPort: 7466 }), /reserved by GateRook/);
  });

  it('falls back to the defaults on a broken file and says why', () => {
    const home = join(t.dir, 'home-c');
    writeDesktopConfig(home, DEFAULTS);
    writeFileSync(join(home, 'desktop.json'), '{not json');
    assert.deepEqual(readDesktopConfig(home), { config: DEFAULTS, problem: 'desktop.json is not JSON' });
    writeFileSync(join(home, 'desktop.json'), JSON.stringify({ port: 7466, publicPort: 7466 }));
    const r = readDesktopConfig(home);
    assert.deepEqual(r.config, { port: 7465, publicPort: 7466 });
    assert.match(r.problem ?? '', /invalid port/);
  });

  it('checks ports like the core', () => {
    assert.equal(portProblem(7475, 7466), null);
    assert.match(portProblem(7466, 7466) ?? '', /reserved by GateRook/);
    assert.match(portProblem(1023, 7466) ?? '', /1024 to 65535/);
    assert.match(portProblem(65536, 7466) ?? '', /1024 to 65535/);
    assert.match(portProblem('7475', 7466) ?? '', /port number/);
    assert.equal(publicPortProblem(7476, 7465), null);
    assert.match(publicPortProblem(7465, 7465) ?? '', /local API's port/);
    assert.match(publicPortProblem(443, 7465) ?? '', /1024 to 65535/);
  });

  it('keeps main\'s copy of "Check for updates automatically"', () => {
    const home = join(t.dir, 'home-d');
    writeDesktopConfig(home, { ...DEFAULTS, autoUpdate: false });
    assert.equal(readDesktopConfig(home).config.autoUpdate, false);
    writeFileSync(join(home, 'desktop.json'), JSON.stringify({ ...DEFAULTS, autoUpdate: 'no' }));
    assert.equal(readDesktopConfig(home).config.autoUpdate, undefined, 'only a boolean counts');
  });

  it('refuses to write a public port the core would refuse', () => {
    assert.throws(() => writeDesktopConfig(join(t.dir, 'home-e'), { port: 7465, publicPort: 80 }), /1024/);
  });
});

describe('Use another port (usePort)', () => {
  const current = { port: 7465, publicPort: 7466 };

  it('a number moves the local API, as before', () => {
    assert.deepEqual(portRequest(7475, current), { port: 7475, publicPort: 7466, named: ['port'] });
    assert.match((portRequest(7466, current) as { problem: string }).problem, /reserved by GateRook/);
    assert.match((portRequest(80, current) as { problem: string }).problem, /1024/);
  });

  it('moves the tunnel\'s public listener, or both', () => {
    assert.deepEqual(portRequest({ publicPort: 7476 }, current), { port: 7465, publicPort: 7476, named: ['publicPort'] });
    assert.deepEqual(portRequest({ port: 7485, publicPort: 7486 }, current),
      { port: 7485, publicPort: 7486, named: ['port', 'publicPort'] });
  });

  it('refuses what the core would refuse, and nothing at all', () => {
    const problem = (raw: unknown): string => (portRequest(raw, current) as { problem?: string }).problem ?? '';
    assert.match(problem({ publicPort: 7465 }), /local API's port/);
    assert.match(problem({ port: 7500, publicPort: 7500 }), /reserved by GateRook/);
    assert.match(problem({ publicPort: 1023 }), /1024/);
    assert.match(problem({ publicPort: '7476' }), /port number/);
    assert.match(problem({}), /port number/);
    assert.match(problem(null), /port number/);
    assert.match(problem([7475]), /port number/);
    assert.match(problem('7475'), /port number/);
  });
});

describe('main\'s files', () => {
  it('are replaced whole, owner-only, without a temporary file left behind', () => {
    const dir = join(t.dir, 'atomic');
    mkdirSync(dir);
    const file = join(dir, 'x.json');
    writeFileAtomic(file, 'one');
    writeFileAtomic(file, Buffer.from('two'));
    assert.equal(readFileSync(file, 'utf8'), 'two');
    if (process.platform !== 'win32') assert.equal(statSync(file).mode & 0o777, 0o600);
    assert.deepEqual(readdirSync(dir), ['x.json']);
  });

  it('fail without a trace when the folder is missing', () => {
    const dir = join(t.dir, 'atomic-missing');
    assert.throws(() => writeFileAtomic(join(dir, 'x.json'), 'one'), /ENOENT/);
  });
});

describe('ports', () => {
  it('finds the first free port from where it starts, never the excluded one', async () => {
    const taken = new Set([7475, 7476]);
    assert.equal(await findFreePort(7475, [7477], async (p) => !taken.has(p)), 7478);
    assert.equal(await findFreePort(7474, [], async () => true), 7474);
    assert.equal(await findFreePort(100, [], async () => true), 1024);
    assert.equal(await findFreePort(7475, [], async () => false), null);
    assert.equal(await findFreePort(Number.NaN, [], async () => true), null);
  });

  it('sees a port another listener holds', async () => {
    const server: Server = createServer();
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    const port = (server.address() as { port: number }).port;
    try {
      assert.equal(await canBind(port), false);
      const free = await findFreePort(port, []);
      assert.ok(free !== null && free > port);
    } finally {
      await new Promise((r) => server.close(r));
    }
    assert.equal(await canBind(port), true);
  });
});
