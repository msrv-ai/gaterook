import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { reportStartupFailure, StartupProblem, startupFailureText } from '../src/main/startup-failure.ts';

function host() {
  const calls: string[] = [];
  return {
    calls,
    logFile: '/home/person/.gaterook/logs/desktop.log',
    log: (line: string) => calls.push(`log ${line.split('\n')[0]}`),
    showErrorBox: (title: string, content: string) => calls.push(`box ${title} | ${content}`),
    exit: (code: number) => calls.push(`exit ${code}`),
  };
}

describe('a start that fails is shown to the person before the app closes', () => {
  it('shows a problem written for the person in its own words, then where the details are', () => {
    const h = host();
    reportStartupFailure(new StartupProblem("GateRook can't update its bridge files in C:\\bin while another app uses "
      + 'them. Quit the AI apps that connect through GateRook, then start GateRook again.'), h);
    assert.equal(h.calls.length, 3);
    assert.match(h.calls[0]!, /^log could not start: StartupProblem: GateRook can't update its bridge files/);
    assert.equal(h.calls[1], "box GateRook couldn't start | GateRook can't update its bridge files in C:\\bin while "
      + 'another app uses them. Quit the AI apps that connect through GateRook, then start GateRook again.\n\n'
      + 'The details are in /home/person/.gaterook/logs/desktop.log.');
    assert.equal(h.calls[2], 'exit 1');
  });

  it('a failure not written for the person says where the log is, never its technical text', () => {
    const text = startupFailureText(new Error('EACCES: permission denied, open /x/y'), '/logs/desktop.log');
    assert.equal(text.message, 'Something went wrong while GateRook started. The details are in /logs/desktop.log.');
    assert.doesNotMatch(text.message, /EACCES/);
  });

  it('still closes the app when the box itself fails', () => {
    const h = host();
    h.showErrorBox = () => { throw new Error('no display'); };
    reportStartupFailure('not even an Error', h);
    assert.deepEqual(h.calls, ['log could not start: Error: not even an Error', 'log could not show why: no display',
      'exit 1']);
  });
});
