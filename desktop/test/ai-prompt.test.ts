import assert from 'node:assert/strict';
import { test } from 'node:test';
import { aiPromptUrl } from '../src/main/ai-prompt.ts';

test('AI links prefill exactly one prompt without accepting extra URL parameters', () => {
  const prompt = 'Inspect https://example.com/a?x=1&folder=/tmp # instructions';
  for (const [client, key] of [['claude', 'q'], ['codex', 'prompt']]) {
    const url = new URL(aiPromptUrl(client, prompt));
    assert.deepEqual([...url.searchParams], [[key, prompt]]);
    assert.equal(url.hash, '');
  }
});

test('Cursor preserves prompt text through URI decoding and the extension query parser', () => {
  const prompt = 'C++ &mode=agent &workspace=/tmp/unrequested\n100% literal %2B %26text%3Dother # ? = "quotes" / API клиентов';
  const url = new URL(aiPromptUrl('cursor', prompt));
  assert.equal(url.protocol, 'cursor:');
  assert.equal(url.hostname, 'anysphere.cursor-deeplink');
  assert.equal(url.pathname, '/prompt');
  // The URI layer decodes first. The extension splits query parameters, converts + to spaces, and decodes again.
  const query = decodeURIComponent(url.search.slice(1));
  assert.equal(query.split(/&[a-zA-Z][a-zA-Z0-9_-]*=/).length, 1);
  assert.equal(decodeURIComponent(query.slice('text='.length).replace(/\+/g, ' ')), prompt);
});

test('AI links reject unknown destinations, invalid input and oversized encoded URLs', () => {
  for (const client of ['file:///tmp/a', '__proto__', 'constructor', null]) assert.throws(() => aiPromptUrl(client, 'Hello'));
  for (const prompt of ['', {}, 'x\0y', 'x'.repeat(8001), '界'.repeat(1200)]) assert.throws(() => aiPromptUrl('codex', prompt));
  assert.throws(() => aiPromptUrl('cursor', '+'.repeat(2000)), /too long/);
});
