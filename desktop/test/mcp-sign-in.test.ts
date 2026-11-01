import assert from 'node:assert/strict';
import { test } from 'node:test';
import { signInEnded, signInId, signInPage } from '../src/main/mcp-sign-in.ts';

test('only the core’s sign-in ids are accepted', () => {
  assert.equal(signInId('msi_abc123def456'), 'msi_abc123def456');
  for (const id of ['msi_ABC123DEF456', 'msi_abc', 'tok_abc123def456', '../msi_abc123def456', null, 7]) {
    assert.equal(signInId(id), null);
  }
});

test('the sign-in page is HTTPS, or HTTP only to this computer, without credentials or a fragment', () => {
  assert.equal(signInPage('https://mcp.linear.app/authorize?client_id=a&state=b'), 'https://mcp.linear.app/authorize?client_id=a&state=b');
  assert.equal(signInPage('http://127.0.0.1:8080/authorize?x=1'), 'http://127.0.0.1:8080/authorize?x=1');
  assert.equal(signInPage('http://localhost:3000/oauth'), 'http://localhost:3000/oauth');
  for (const raw of ['http://auth.example.com/authorize', 'file:///etc/passwd', 'javascript:alert(1)', 'https://u:p@as.example/a',
    'https://as.example/a#frag', 'cursor://anysphere/oauth', 'https://as.example/' + 'a'.repeat(9000), 42, null]) {
    assert.equal(signInPage(raw), null, String(raw).slice(0, 60));
  }
});

test('the window comes forward when a sign-in in the browser ends, not for a stored server’s state', () => {
  assert.equal(signInEnded('mcp-server.sign-in', { id: 'msi_abc123def456', state: 'signed-in' }), true);
  assert.equal(signInEnded('mcp-server.sign-in', { id: 'msi_abc123def456', state: 'failed' }), true);
  assert.equal(signInEnded('mcp-server.sign-in', { id: 'msi_abc123def456', state: 'waiting' }), false);
  assert.equal(signInEnded('mcp-server.sign-in', { server: 'linear', state: 'needs-sign-in' }), false);
  assert.equal(signInEnded('mcp-server.state', { id: 'msi_abc123def456', state: 'signed-in' }), false);
});
