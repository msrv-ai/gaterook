import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { findDangerousEnv, findDangerousSwitches, refusalMessage, sanitizeEnvironment, switchName } from '../src/main/switches.ts';

const none = (): boolean => false;
const chromium = (argv: string[]) => (name: string) => argv.some((a) => switchName(a) === name);

describe('dangerous Chromium and Node switches', () => {
  it('finds remote debugging in every spelling Chromium accepts', () => {
    for (const arg of ['--remote-debugging-port=9222', '-remote-debugging-port=9222', '--remote-debugging-pipe',
      '--remote-debugging-address=0.0.0.0', '--REMOTE-DEBUGGING-PORT=1']) {
      assert.ok(findDangerousSwitches(['/app/MCP Gateway', arg], none).length > 0, arg);
    }
  });

  it('finds every inspect variant, including ones not listed by name', () => {
    for (const arg of ['--inspect', '--inspect=9229', '--inspect-brk', '--inspect-port=0', '--inspect-brk-node',
      '--inspect-something-new']) {
      assert.deepEqual(findDangerousSwitches([arg], none), [switchName(arg)], arg);
    }
  });

  it('finds proxy, logging, sandbox and code-injection switches', () => {
    const argv = ['--proxy-server=http://evil:8080', '--proxy-pac-url=x', '--host-resolver-rules=MAP * evil',
      '--host-rules=x', '--ignore-certificate-errors', '--log-net-log=/tmp/x', '--net-log-capture-mode=Everything',
      '--enable-logging', '--v=1', '--vmodule=*=3', '--js-flags=--expose-gc', '--disable-web-security',
      '--no-sandbox', '--load-extension=/x', '--user-data-dir=/tmp/x', '--renderer-cmd-prefix=gdb'];
    assert.equal(findDangerousSwitches(argv, none).length, argv.length);
  });

  it('trusts Chromium\'s own parse for exact names', () => {
    assert.deepEqual(findDangerousSwitches([], (n) => n === 'proxy-server'), ['proxy-server']);
  });

  it('leaves ordinary arguments alone', () => {
    const argv = ['/Applications/MCP Gateway.app/Contents/MacOS/MCP Gateway', '--hidden', 'mcp-gateway://approvals/ap_0123456789ab',
      '/Users/x/project', '-psn_0_12345', '--allow-file-access-from-files-not-really'];
    assert.deepEqual(findDangerousSwitches(argv, chromium(argv)), []);
  });

  it('stops at --, like Chromium', () => {
    assert.deepEqual(findDangerousSwitches(['app', '--', '--remote-debugging-port=1'], none), []);
  });

  it('tolerates --no-sandbox only when the policy allows it', () => {
    assert.deepEqual(findDangerousSwitches(['--no-sandbox'], none, { allowNoSandbox: true }), []);
    assert.deepEqual(findDangerousSwitches(['--no-sandbox', '--inspect'], none, { allowNoSandbox: true }), ['inspect']);
  });

  it('finds sandbox, Blink-feature and key-log switches, and switches with stray whitespace', () => {
    for (const arg of ['--zygote-cmd-prefix=gdb', '--disable-seccomp-filter-sandbox', '--disable-setuid-sandbox',
      '--disable-blink-features=TrustedDOMTypes', '--ssl-key-log-file=/tmp/keys', ' --inspect-foo', '--remote-debugging-port=1 ']) {
      assert.ok(findDangerousSwitches([arg], none).length === 1, arg);
    }
    // Wayland users start Electron apps with these: not refused.
    assert.deepEqual(findDangerousSwitches(['--enable-features=UseOzonePlatform', '--ozone-platform=wayland'], none), []);
  });

  it('refuses the logging variables and drops SSLKEYLOGFILE', () => {
    assert.deepEqual(findDangerousEnv({ ELECTRON_ENABLE_LOGGING: '1', PATH: '/bin' }), ['ELECTRON_ENABLE_LOGGING']);
    assert.deepEqual(findDangerousEnv({ ELECTRON_LOG_FILE: '/tmp/log' }), ['ELECTRON_LOG_FILE']);
    assert.deepEqual(findDangerousEnv({ ELECTRON_ENABLE_LOGGING: '' }), []);
    const env: NodeJS.ProcessEnv = { SSLKEYLOGFILE: '/tmp/keys', HOME: '/h' };
    assert.deepEqual(sanitizeEnvironment(env), ['SSLKEYLOGFILE']);
    assert.deepEqual(env, { HOME: '/h' });
    assert.match(refusalMessage(['ELECTRON_ENABLE_LOGGING']), /with ELECTRON_ENABLE_LOGGING:/);
  });

  it('says what was refused', () => {
    assert.match(refusalMessage(['remote-debugging-port']), /--remote-debugging-port: this switch lets other programs/);
  });
});
