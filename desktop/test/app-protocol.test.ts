import assert from 'node:assert/strict';
import { mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { contentSecurityPolicy, injectNonce, isAppPage, newNonce, routeAppUrl, safeUrl, serveUi, wellFormedApiPath } from '../src/main/app-protocol.ts';
import { desktopExecArg } from '../src/main/autostart.ts';
import { tempDir } from './helpers.ts';

const t = tempDir('ui');
const root = join(t.dir, 'ui');
const outside = join(t.dir, 'secret.txt');
after(t.remove);
before(() => {
  mkdirSync(join(root, 'assets'), { recursive: true });
  writeFileSync(join(root, 'index.html'), '<!doctype html><html><head><title>x</title></head><body><gw-root></gw-root></body></html>');
  writeFileSync(join(root, 'main-ABC.js'), 'console.log(1)');
  writeFileSync(join(root, 'assets', 'logo-mark.svg'), '<svg/>');
  writeFileSync(join(root, 'assets', 'with space.css'), 'a{}');
  writeFileSync(outside, 'top secret');
  if (process.platform !== 'win32') {
    symlinkSync(outside, join(root, 'leak.txt'));
    symlinkSync(t.dir, join(root, 'up'));
  }
});

const route = (url: string) => routeAppUrl(new URL(url));
const get = (url: string) => serveUi(root, route(url), () => 'NONCE123');

describe('app:// routing', () => {
  it('answers only the gw host', () => {
    assert.deepEqual(route('app://evil/index.html'), { kind: 'not-found' });
    assert.deepEqual(route('app://gw/'), { kind: 'index' });
  });

  it('sends /api to the core with the raw path and query, and only well-formed ones', () => {
    assert.deepEqual(route('app://gw/api/health'), { kind: 'api', target: '/api/health' });
    assert.deepEqual(route('app://gw/api/activity?limit=50&q=a%20b'), { kind: 'api', target: '/api/activity?limit=50&q=a%20b' });
    assert.deepEqual(route('app://gw/api/x?'), { kind: 'api', target: '/api/x' });
    for (const bad of ['app://gw/api/%2e%2e/internal/files/grant', 'app://gw/api/a%2fb', 'app://gw/api/a%5cb',
      'app://gw/api/a;b', 'app://gw/api/a/', 'app://gw/api//a', 'app://gw/api/a%00', 'app://gw/api/%252e']) {
      assert.deepEqual(route(bad), { kind: 'not-found' }, bad);
    }
  });

  it('never lets the renderer reach /internal', () => {
    assert.deepEqual(route('app://gw/internal/files/grant'), { kind: 'not-found' });
    assert.deepEqual(route('app://gw/internal'), { kind: 'not-found' });
    // An escaped letter is not a way in either: it is a page route of the UI, not a request to the core.
    assert.equal(route('app://gw/%69nternal/files/grant').kind, 'index');
  });

  it('refuses encoded traversal: app://gw/..%2f is 404', async () => {
    for (const bad of ['app://gw/..%2f', 'app://gw/..%2fpackage.json', 'app://gw/%2e%2e/%2e%2e/secret.txt',
      'app://gw/assets/..%2f..%2fsecret.txt', 'app://gw/assets%2f..%2f..%2fsecret.txt', 'app://gw/a%5c..%5csecret.txt',
      'app://gw/%00.js', 'app://gw/%E0%A4%A.js']) {
      const r = await get(bad);
      assert.equal(r.status, 404, bad);
    }
  });

  it('dot segments, literal or escaped, are resolved by the URL parser inside the root', () => {
    assert.equal(new URL('app://gw/assets/../../secret.txt').pathname, '/secret.txt');
    assert.equal(new URL('app://gw/%2e%2e/%2e%2e/etc/passwd').pathname, '/etc/passwd');
    // …which is then a route of the SPA, never a file outside it.
    assert.deepEqual(route('app://gw/%2e%2e/%2e%2e/etc/passwd'), { kind: 'index' });
  });

  it('serves files inside the root and routes of the SPA', async () => {
    const js = await get('app://gw/main-ABC.js');
    assert.equal(js.status, 200);
    assert.equal(js.headers.get('content-type'), 'text/javascript; charset=utf-8');
    assert.equal(await js.text(), 'console.log(1)');
    assert.equal((await get('app://gw/assets/with%20space.css')).status, 200);
    assert.equal((await get('app://gw/assets/logo-mark.svg')).headers.get('content-type'), 'image/svg+xml');
    assert.equal((await get('app://gw/missing.js')).status, 404);
    const spa = await get('app://gw/approvals/ap_0123456789ab');
    assert.equal(spa.status, 200);
    assert.match(await spa.text(), /<gw-root ngCspNonce="NONCE123">/);
  });

  it('does not follow symlinks, inside or out', { skip: process.platform === 'win32' }, async () => {
    assert.equal((await get('app://gw/leak.txt')).status, 404);
    assert.equal((await get('app://gw/up/secret.txt')).status, 404);
  });

  it('puts a fresh nonce into index.html and its CSP', async () => {
    const r = await serveUi(root, { kind: 'index' });
    const csp = r.headers.get('content-security-policy') ?? '';
    const nonce = /'nonce-([^']+)'/.exec(csp)?.[1];
    assert.ok(nonce);
    assert.match(await r.text(), new RegExp(`<gw-root ngCspNonce="${nonce.replace(/[+/]/g, '\\$&')}">`));
    assert.equal(r.headers.get('cache-control'), 'no-store');
    const again = await serveUi(root, { kind: 'index' });
    assert.notEqual(/'nonce-([^']+)'/.exec(again.headers.get('content-security-policy') ?? '')?.[1], nonce);
    assert.notEqual(newNonce(), newNonce());
  });

  it('has the policy of ARCHITECTURE.md §7.1', () => {
    const csp = contentSecurityPolicy('N');
    for (const part of ["default-src 'self'", "script-src 'self'", "style-src 'self' 'nonce-N'", "connect-src 'self'",
      "object-src 'none'", "frame-ancestors 'none'", "form-action 'none'", "require-trusted-types-for 'script'",
      'trusted-types angular angular#bundler']) {
      assert.ok(csp.includes(part), part);
    }
    assert.ok(!csp.includes('unsafe-inline') && !csp.includes('unsafe-eval'));
    assert.equal(contentSecurityPolicy(null).includes('nonce'), false);
  });

  it('nonce goes on the first root element only', () => {
    assert.equal(injectNonce('<gw-root></gw-root><gw-root-x>', 'n'), '<gw-root ngCspNonce="n"></gw-root><gw-root-x>');
    assert.equal(injectNonce('<gw-root class="a">', 'n'), '<gw-root ngCspNonce="n" class="a">');
    assert.equal(injectNonce('<body>', 'n'), '<body>');
  });

  it('lets the window show the app\'s pages only, never an answer of the core as a document', () => {
    assert.ok(isAppPage('app://gw/'));
    assert.ok(isAppPage('app://gw/approvals/ap_0123456789ab'));
    for (const bad of ['app://gw/api/health', 'app://gw/API/health', 'app://gw/api', 'app://gw/internal/files/grant',
      'https://evil.example/', 'app://gwx/', 'file:///etc/passwd']) {
      assert.ok(!isAppPage(bad), bad);
    }
    assert.equal(safeUrl('app://gw/approvals/ap_0123456789ab?token=secret#x'), 'app://gw/approvals/ap_0123456789ab');
  });

  it('writes a .desktop Exec argument by the spec', () => {
    assert.equal(desktopExecArg('/opt/MCP Gateway/mcp-gateway'), '"/opt/MCP Gateway/mcp-gateway"');
    assert.equal(desktopExecArg('/a "b" $c `d` \\e 100%'), '"/a \\\\"b\\\\" \\\\$c \\\\`d\\\\` \\\\\\\\e 100%%"');
  });

  it('applies the core\'s rule to raw admin paths', () => {
    assert.ok(wellFormedApiPath('/api/endpoints/dev/tools'));
    assert.ok(!wellFormedApiPath('/api/endpoints/./tools'));
    assert.ok(!wellFormedApiPath('/api/endpoints/../tools'));
    assert.ok(!wellFormedApiPath('/api/%25'));
  });
});
