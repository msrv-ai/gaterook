import assert from 'node:assert/strict';
import { ChildProcess, type SpawnOptions } from 'node:child_process';
import { describe, it } from 'node:test';
import { GoogleSignIn, gcloudPaths, googleSignInCommand, googleSignInEnv } from '../src/main/google-sign-in.ts';

const home = '/home/example';
const file = '/opt/google-cloud-sdk/bin/gcloud';
const env = { PATH: '/opt/google-cloud-sdk/bin:/usr/bin', HOME: home };
const flush = () => new Promise<void>((resolve) => setImmediate(resolve));

function harness(timeoutMs = 5_000) {
  const calls: { file: string; args: string[]; options: SpawnOptions; child: ChildProcess }[] = [];
  const killed: ChildProcess[] = [];
  const login = new GoogleSignIn({ platform: 'linux', home, env, timeoutMs,
    executable: async (path) => path === file,
    spawn: (file, args, options) => {
      const child = new ChildProcess();
      calls.push({ file, args, options, child });
      return child;
    },
    terminate: (child) => { killed.push(child); },
  });
  return { login, calls, killed };
}

describe('Google sign-in executable and environment', () => {
  it('ignores working-directory PATH entries and finds common GUI SDK installs', () => {
    const paths = gcloudPaths('darwin', '/Users/example', { PATH: '.:relative:/usr/bin::/usr/bin' });
    assert.equal(paths.filter((path) => path === '/usr/bin').length, 1);
    assert.ok(paths.every((path) => path.startsWith('/')));
    assert.ok(paths.includes('/opt/homebrew/share/google-cloud-sdk/bin'));
    assert.ok(paths.includes('/Users/example/google-cloud-sdk/bin'));
    assert.ok(paths.includes('/Users/example/Downloads/google-cloud-sdk/bin'));
    const windows = gcloudPaths('win32', 'C:\\Users\\Example', { Path: 'C:\\tools;.;relative',
      LOCALAPPDATA: 'C:\\Users\\Example\\AppData\\Local' });
    assert.deepEqual(windows, ['C:\\tools', 'C:\\Users\\Example\\AppData\\Local\\Google\\Cloud SDK\\google-cloud-sdk\\bin']);
  });

  it('writes ADC where the core reads it and strips credential and runtime overrides', () => {
    const source = { ...env, CLOUDSDK_CONFIG: '/other/config', GOOGLE_APPLICATION_CREDENTIALS: '/other/key.json',
      CLOUDSDK_AUTH_ACCESS_TOKEN: 'private-token', NODE_OPTIONS: '--inspect', ELECTRON_RUN_AS_NODE: '1',
      DISPLAY: ':0', DBUS_SESSION_BUS_ADDRESS: 'unix:path=/run/user/1000/bus', HTTPS_PROXY: 'https://proxy.example.test' };
    const actual = googleSignInEnv('linux', home, source);
    assert.equal(actual['CLOUDSDK_CONFIG'], '/home/example/.config/gcloud');
    for (const key of ['GOOGLE_APPLICATION_CREDENTIALS', 'CLOUDSDK_AUTH_ACCESS_TOKEN', 'NODE_OPTIONS', 'ELECTRON_RUN_AS_NODE']) {
      assert.equal(actual[key], undefined, key);
    }
    assert.equal(actual['DISPLAY'], ':0');
    assert.equal(actual['HTTPS_PROXY'], source.HTTPS_PROXY);
    assert.equal(googleSignInEnv('win32', 'C:\\Users\\Example', { APPDATA: 'C:\\Users\\Example\\AppData\\Roaming' })['CLOUDSDK_CONFIG'],
      'C:\\Users\\Example\\AppData\\Roaming\\gcloud');
  });

  it('quotes the Windows SDK launcher with only fixed arguments and rejects shell expansion in paths', () => {
    const sdk = 'C:\\Program Files (x86)\\Google\\Cloud SDK\\google-cloud-sdk\\bin\\gcloud.cmd';
    assert.deepEqual(googleSignInCommand(sdk, 'win32', { SystemRoot: 'C:\\Windows' }), {
      file: 'C:\\Windows\\System32\\cmd.exe',
      args: ['/d', '/s', '/v:off', '/c', `""${sdk}" auth application-default login --launch-browser --quiet"`],
      windowsVerbatimArguments: true,
    });
    for (const value of ['gcloud.cmd', 'C:\\%TOKEN%\\gcloud.cmd', 'C:\\!TOKEN!\\gcloud.cmd',
      'C:\\tools&whoami\\gcloud.cmd', 'C:\\tools"\\gcloud.cmd', 'C:\\tools\n\\gcloud.cmd']) {
      assert.throws(() => googleSignInCommand(value, 'win32', { SystemRoot: 'C:\\Windows' }));
    }
  });
});

describe('Google sign-in lifecycle', () => {
  it('runs one fixed command with no shell or output pipes and shares duplicate requests', async () => {
    const { login, calls, killed } = harness();
    const pending = login.signIn();
    assert.equal(login.signIn(), pending);
    await flush();
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.file, file);
    assert.deepEqual(calls[0]!.args, ['auth', 'application-default', 'login', '--launch-browser', '--quiet']);
    assert.equal(calls[0]!.options.shell, false);
    assert.equal(calls[0]!.options.stdio, 'ignore');
    assert.equal(calls[0]!.options.cwd, home);
    assert.equal(calls[0]!.options.detached, true);
    calls[0]!.child.emit('exit', 0, null);
    await pending;
    assert.equal(killed.length, 0);
  });

  it('reports a missing CLI without launching another command', async () => {
    const login = new GoogleSignIn({ platform: 'linux', home, env, executable: async () => false,
      spawn: () => { throw new Error('must not spawn'); } });
    await assert.rejects(login.signIn(), /Google Cloud CLI was not found/);
  });

  it('keeps process errors private and allows a retry after failure', async () => {
    const { login, calls } = harness();
    const first = login.signIn();
    const rejected = assert.rejects(first, (error: Error) => {
      assert.match(error.message, /Google sign-in did not finish/);
      assert.ok(!error.message.includes('private-token'));
      return true;
    });
    await flush();
    calls[0]!.child.emit('error', new Error('private-token'));
    await rejected;
    const retry = login.signIn();
    await flush();
    assert.equal(calls.length, 2);
    calls[1]!.child.emit('exit', 0, null);
    await retry;
  });

  it('rejects a nonzero exit without treating it as completed authorization', async () => {
    const { login, calls } = harness();
    const rejected = assert.rejects(login.signIn(), /Google sign-in did not finish/);
    await flush();
    calls[0]!.child.emit('exit', 1, null);
    await rejected;
  });

  it('terminates a sign-in that exceeds its deadline', async () => {
    const { login, calls, killed } = harness(20);
    await assert.rejects(login.signIn(), /Google sign-in timed out/);
    assert.deepEqual(killed, [calls[0]!.child]);
  });

  it('cancels on app shutdown and never starts another sign-in', async () => {
    const { login, calls, killed } = harness();
    const rejected = assert.rejects(login.signIn(), /Google sign-in was cancelled/);
    await flush();
    login.close();
    await rejected;
    assert.deepEqual(killed, [calls[0]!.child]);
    await assert.rejects(login.signIn(), /Google sign-in was cancelled/);
    assert.equal(calls.length, 1);
  });

  it('does not launch after app shutdown while the executable is being found', async () => {
    let release: (exists: boolean) => void = () => undefined;
    const login = new GoogleSignIn({ platform: 'linux', home, env,
      executable: () => new Promise<boolean>((resolve) => { release = resolve; }),
      spawn: () => { throw new Error('must not spawn'); } });
    const rejected = assert.rejects(login.signIn(), /Google sign-in was cancelled/);
    login.close();
    release(true);
    await rejected;
  });
});
