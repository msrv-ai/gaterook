import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { NativeUnlockClient, NativeUnlockError, nativeReply } from '../src/main/native-unlock.ts';

describe('native unlock protocol', () => {
  it('accepts only typed replies and never exposes unexpected helper output', () => {
    assert.equal(nativeReply(Buffer.from('{"ok":true,"available":true,"touchId":false}')).available, true);
    for (const text of ['private native stderr', '{"ok":true,"available":"secret"}', 'null', '[]', 'x'.repeat(4097),
      '{"ok":false,"error":"secret"}']) {
      assert.throws(() => nativeReply(Buffer.from(text)), (error: unknown) => {
        assert.ok(error instanceof NativeUnlockError);
        assert.equal(error.message, 'Native unlock failed');
        return true;
      });
    }
    assert.throws(() => nativeReply(Buffer.from('{"ok":false,"error":"cancelled"}')), { message: 'Native unlock cancelled' });
  });

  it('fails closed if the bundled helper cannot be started or the client has closed', async () => {
    const client = new NativeUnlockClient('/nonexistent/gaterook-native-unlock');
    await assert.rejects(client.call({ operation: 'check' }), NativeUnlockError);
    client.close();
    await assert.rejects(client.call({ operation: 'verify' }), { message: 'Native unlock cancelled' });
  });
});
