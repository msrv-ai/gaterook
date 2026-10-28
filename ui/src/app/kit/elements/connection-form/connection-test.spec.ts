import { TestBed } from '@angular/core/testing';
import { TestOutcome } from '../../../core/api/connections.api';
import { DesktopBridge } from '../../../core/desktop/desktop-bridge';
import { provideKitIcons } from '../../icons';
import { GwConnectionTest, usesGoogleAdc } from './connection-test';

const FAILED: TestOutcome = {
  ok: false, latencyMs: 1, serverVersion: null, identity: null, permissions: null,
  hints: [], checks: [], error: { code: 'unavailable', message: 'No Google sign-in on this computer.', technical: null },
  needsConfirmation: null, confirmationId: null, suggestedPolicyChange: null, details: null,
};

function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function render(available = true) {
  const pending = deferred();
  const signInToGoogle = vi.fn(() => pending.promise);
  TestBed.configureTestingModule({ providers: [
    provideKitIcons(),
    { provide: DesktopBridge, useValue: { googleSignInAvailable: available, signInToGoogle } },
  ] });
  const fixture = TestBed.createComponent(GwConnectionTest);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('state', 'done');
  fixture.componentRef.setInput('outcome', FAILED);
  fixture.componentRef.setInput('googleSignIn', true);
  fixture.componentRef.setInput('googleSignInContext', 'projects/acme-stage');
  const run = vi.fn();
  fixture.componentInstance.run.subscribe(run);
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    await Promise.resolve();
    fixture.detectChanges();
    await fixture.whenStable();
  };
  const button = (label: string) => [...el.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.trim() === label);
  await settle();
  return { fixture, el, pending, signInToGoogle, run, settle, button };
}

afterEach(() => { document.body.innerHTML = ''; });

describe('connection test: Google sign-in', () => {
  it('offers Google sign-in for GCP ADC and GCP Logging ADC only', () => {
    expect(usesGoogleAdc('gcp', { credentials: { source: 'adc' } })).toBe(true);
    expect(usesGoogleAdc('gcp', { credentials: { source: 'serviceAccountKey' } })).toBe(false);
    expect(usesGoogleAdc('gcp-logging', { credentials: { source: 'adc' } })).toBe(true);
    expect(usesGoogleAdc('gcp-logging', { credentials: { source: 'serviceAccountKey' } })).toBe(false);
    expect(usesGoogleAdc('logs', { backend: 'gcp-logging', credentials: { source: 'adc' } })).toBe(true);
    expect(usesGoogleAdc('logs', { backend: 'gcp-logging', credentials: { source: 'serviceAccountKey' } })).toBe(false);
    expect(usesGoogleAdc('logs', { backend: 'cloudwatch', credentials: { source: 'adc' } })).toBe(false);
    expect(usesGoogleAdc('aws', { credentials: { source: 'adc' } })).toBe(false);
    for (const type of ['loki', 'cloudwatch', 'opensearch']) {
      expect(usesGoogleAdc(type, { credentials: { source: 'adc' } })).toBe(false);
    }
    expect(usesGoogleAdc('gcp', {})).toBe(false);
  });

  it('offers sign-in only for an eligible failed connection', async () => {
    const v = await render();
    expect(v.button('Sign in with Google')).toBeDefined();
    v.fixture.componentRef.setInput('googleSignIn', false);
    await v.settle();
    expect(v.button('Sign in with Google')).toBeUndefined();
    v.fixture.componentRef.setInput('googleSignIn', true);
    v.fixture.componentRef.setInput('outcome', { ...FAILED, ok: true, error: null });
    await v.settle();
    expect(v.button('Sign in with Google')).toBeUndefined();
    v.fixture.componentRef.setInput('state', 'idle');
    v.fixture.componentRef.setInput('outcome', null);
    await v.settle();
    expect(v.button('Sign in with Google')).toBeUndefined();
    expect(v.signInToGoogle).not.toHaveBeenCalled();
  });

  it('keeps sign-in and testing unavailable while Google login is pending, then tests once', async () => {
    const v = await render();
    v.button('Sign in with Google')!.click();
    await v.settle();
    const signIn = v.button('Sign in with Google')!;
    expect(signIn.getAttribute('aria-busy')).toBe('true');
    expect(signIn.getAttribute('aria-disabled')).toBe('true');
    expect(signIn.querySelector('.gw-spinner')).not.toBeNull();
    expect(v.el.textContent).toContain('Complete Google sign-in in your browser. The connection will be tested again when you finish.');
    const test = v.button('Test again')!;
    expect(test.getAttribute('aria-disabled')).toBe('true');
    signIn.click();
    test.click();
    await v.settle();
    expect(v.signInToGoogle).toHaveBeenCalledTimes(1);
    expect(v.run).not.toHaveBeenCalled();
    v.pending.resolve();
    await v.settle();
    expect(v.run).toHaveBeenCalledTimes(1);
    expect(v.button('Sign in with Google')!.getAttribute('aria-busy')).not.toBe('true');
  });

  it('shows the desktop failure without its IPC wrapper and does not test', async () => {
    const v = await render();
    v.button('Sign in with Google')!.click();
    await v.settle();
    v.pending.reject(new Error("Error invoking remote method 'google-sign-in': Error: Google sign-in was cancelled."));
    await v.settle();
    expect(v.el.textContent).toContain('Google sign-in was cancelled.');
    expect(v.el.textContent).not.toContain('Error invoking remote method');
    expect(v.run).not.toHaveBeenCalled();
    expect(v.button('Sign in with Google')!.getAttribute('aria-disabled')).not.toBe('true');
  });

  it('explains the terminal fallback when the desktop shell cannot sign in', async () => {
    const v = await render(false);
    const signIn = v.button('Sign in with Google')!;
    expect(signIn.getAttribute('aria-disabled')).toBe('true');
    expect(v.el.textContent).toContain('gcloud auth application-default login');
    expect(signIn.getAttribute('aria-describedby')).toBeTruthy();
    signIn.click();
    await v.settle();
    expect(v.signInToGoogle).not.toHaveBeenCalled();
    expect(v.run).not.toHaveBeenCalled();
  });

  for (const change of ['context', 'outcome', 'eligibility', 'state'] as const) {
    it(`does not test a stale connection when ${change} changes during sign-in`, async () => {
      const v = await render();
      v.button('Sign in with Google')!.click();
      await v.settle();
      if (change === 'context') v.fixture.componentRef.setInput('googleSignInContext', 'projects/acme-prod');
      if (change === 'outcome') v.fixture.componentRef.setInput('outcome', { ...FAILED });
      if (change === 'eligibility') v.fixture.componentRef.setInput('googleSignIn', false);
      if (change === 'state') v.fixture.componentRef.setInput('state', 'testing');
      await v.settle();
      v.pending.resolve();
      await v.settle();
      expect(v.run).not.toHaveBeenCalled();
    });
  }

  it('does not display a stale sign-in error after the connection changes', async () => {
    const v = await render();
    v.button('Sign in with Google')!.click();
    await v.settle();
    v.fixture.componentRef.setInput('googleSignInContext', 'projects/acme-prod');
    await v.settle();
    v.pending.reject(new Error('Previous Google sign-in failed.'));
    await v.settle();
    expect(v.el.textContent).not.toContain('Previous Google sign-in failed.');
    expect(v.run).not.toHaveBeenCalled();
  });

  it('ignores sign-in completion after the panel is destroyed', async () => {
    const v = await render();
    v.button('Sign in with Google')!.click();
    await v.settle();
    v.fixture.destroy();
    v.pending.resolve();
    await v.pending.promise;
    await Promise.resolve();
    expect(v.run).not.toHaveBeenCalled();
  });
});
