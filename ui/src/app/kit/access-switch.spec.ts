import { TestBed } from '@angular/core/testing';
import { ConnectionsApi } from '../core/api/connections.api';
import { EndpointsApi } from '../core/api/endpoints.api';
import { GwAccessSwitch } from './access-switch';
import { ToastService } from './toast.service';

function setup() {
  let resolve!: () => void;
  let reject!: (reason: Error) => void;
  const pending = new Promise<void>((yes, no) => { resolve = yes; reject = no; });
  const setEnabled = vi.fn(() => pending);
  const enable = vi.fn(() => pending);
  const disable = vi.fn(() => pending);
  const show = vi.fn();
  TestBed.configureTestingModule({ providers: [
    { provide: ConnectionsApi, useValue: { setEnabled } },
    { provide: EndpointsApi, useValue: { enable, disable } },
    { provide: ToastService, useValue: { show } },
  ] });
  function render(kind: 'endpoint' | 'connection', enabled = true) {
    const fixture = TestBed.createComponent(GwAccessSwitch);
    document.body.appendChild(fixture.nativeElement);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('name', 'shared-prod');
    fixture.componentRef.setInput('enabled', enabled);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    const control = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    return { fixture, control, changed };
  }
  return { render, setEnabled, enable, disable, show, resolve, reject };
}

afterEach(() => { document.body.innerHTML = ''; });

describe('access switch', () => {
  it('saves a global connection pause once and shares busy state between repeated tiles', async () => {
    const s = setup();
    const first = s.render('connection');
    const second = s.render('connection');
    document.body.appendChild(first.fixture.nativeElement);
    expect(first.control.checked).toBe(true);
    expect(first.control.getAttribute('aria-label')).toContain('across all endpoints');
    first.control.click();
    first.fixture.detectChanges();
    second.fixture.detectChanges();
    expect(first.control.checked).toBe(true);
    expect(second.control.disabled).toBe(true);
    second.control.click();
    expect(s.setEnabled).toHaveBeenCalledExactlyOnceWith('shared-prod', false);
    s.resolve();
    await first.fixture.whenStable();
    first.fixture.detectChanges();
    expect(first.control.checked).toBe(false);
    expect(first.changed).toHaveBeenCalledExactlyOnceWith(false);
    expect(s.disable).not.toHaveBeenCalled();
  });

  it('keeps the previous state and reports a failed save', async () => {
    const s = setup();
    const row = s.render('connection', false);
    row.control.click();
    s.reject(new Error('offline'));
    await row.fixture.whenStable();
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(false);
    expect(row.control.disabled).toBe(false);
    expect(row.changed).not.toHaveBeenCalled();
    expect(s.show).toHaveBeenCalledWith(expect.objectContaining({ tone: 'danger' }));
  });

  it('does not apply a completed toggle to a different connection after navigation', async () => {
    const s = setup();
    const row = s.render('connection');
    row.control.click();
    row.fixture.componentRef.setInput('name', 'another-connection');
    row.fixture.detectChanges();
    s.resolve();
    await row.fixture.whenStable();
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(true);
    expect(row.changed).not.toHaveBeenCalled();
    expect(s.show).not.toHaveBeenCalled();
  });

  it('re-enables only the named endpoint and follows refreshed input', async () => {
    const s = setup();
    const row = s.render('endpoint', false);
    row.control.click();
    expect(s.enable).toHaveBeenCalledExactlyOnceWith('shared-prod');
    expect(s.setEnabled).not.toHaveBeenCalled();
    s.resolve();
    await row.fixture.whenStable();
    row.fixture.componentRef.setInput('enabled', true);
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(true);
    row.fixture.componentRef.setInput('enabled', false);
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(false);
  });

  it('refuses to toggle an object awaiting integrity review', () => {
    const s = setup();
    const row = s.render('connection');
    row.fixture.componentRef.setInput('blocked', 'Review the change outside GateRook first.');
    row.fixture.detectChanges();
    row.control.click();
    expect(row.control.checked).toBe(true);
    expect(s.setEnabled).not.toHaveBeenCalled();
    expect(row.control.getAttribute('aria-disabled')).toBe('true');
  });

  it('shows effective Off under a paused endpoint and restores the saved global setting', () => {
    const s = setup();
    const row = s.render('connection');
    row.fixture.componentRef.setInput('pausedByEndpoint', 'incident-prod');
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(false);
    expect(row.control.getAttribute('aria-disabled')).toBe('true');
    expect(row.control.getAttribute('aria-label')).toContain('through endpoint incident-prod');
    expect(row.fixture.nativeElement.textContent).toContain('saved connection setting is On');
    row.control.click();
    expect(row.control.checked).toBe(false);
    expect(s.setEnabled).not.toHaveBeenCalled();
    row.fixture.componentRef.setInput('pausedByEndpoint', null);
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(true);
    expect(row.control.getAttribute('aria-disabled')).toBeNull();
    row.fixture.componentRef.setInput('enabled', false);
    row.fixture.componentRef.setInput('pausedByEndpoint', 'incident-prod');
    row.fixture.detectChanges();
    row.fixture.componentRef.setInput('pausedByEndpoint', null);
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(false);
    expect(s.setEnabled).not.toHaveBeenCalled();
  });

  it('keeps blocked access visibly Off without losing the saved setting or accessible name in a compact row', () => {
    const s = setup();
    const row = s.render('connection');
    row.fixture.componentRef.setInput('compact', true);
    row.fixture.componentRef.setInput('forceOff', true);
    row.fixture.componentRef.setInput('blocked', 'Finish setting up this connection first.');
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(false);
    expect(row.fixture.nativeElement.querySelector('label').classList).toContain('gw-sr-only');
    expect(row.control.getAttribute('aria-label')).toContain('shared-prod across all endpoints');
    row.control.click();
    expect(s.setEnabled).not.toHaveBeenCalled();
    row.fixture.componentRef.setInput('blocked', null);
    row.fixture.componentRef.setInput('forceOff', false);
    row.fixture.detectChanges();
    expect(row.control.checked).toBe(true);
  });
});
