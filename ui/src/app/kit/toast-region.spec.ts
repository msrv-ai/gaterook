import { Component, inject } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GwToastTimer } from './toast-region';
import { ToastService } from './toast.service';

@Component({
  imports: [GwToastTimer],
  template: `
    @for (toast of toasts.toasts(); track toast.id) {
      <div [gwToastTimer]="toast" class="gw-toast">
        {{ toast.title }}
        <button type="button" (click)="toasts.dismiss(toast.id)">Dismiss</button>
      </div>
    }
  `,
})
class ToastHost {
  readonly toasts = inject(ToastService);
}

describe('toast display duration', () => {
  let fixture: ComponentFixture<ToastHost>;
  let toasts: ToastService;

  beforeEach(() => {
    vi.useFakeTimers();
    fixture = TestBed.createComponent(ToastHost);
    toasts = TestBed.inject(ToastService);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  const advance = (ms: number) => {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  };

  it('removes a notification after 15 seconds by default', () => {
    toasts.show({ title: 'Endpoint dev enabled' });
    fixture.detectChanges();
    advance(14999);
    expect(fixture.nativeElement.textContent).toContain('Endpoint dev enabled');
    advance(1);
    expect(fixture.nativeElement.querySelector('.gw-toast')).toBeNull();
    expect(toasts.toasts()).toEqual([]);
  });

  it('uses the saved duration for new notifications without changing a visible one', () => {
    toasts.show({ title: 'First' });
    fixture.detectChanges();
    advance(5000);
    toasts.setDuration(30);
    toasts.show({ title: 'Second' });
    fixture.detectChanges();
    advance(10000);
    expect(toasts.toasts().map((toast) => toast.title)).toEqual(['Second']);
    advance(19999);
    expect(toasts.toasts()).toHaveLength(1);
    advance(1);
    expect(toasts.toasts()).toEqual([]);
  });

  it('keeps actionable and explicitly persistent notifications until dismissed', () => {
    toasts.setDuration(5);
    toasts.show({ title: 'Review needed', actions: [{ label: 'Review' }] });
    toasts.show({ title: 'Keep this notice', timeout: 0 });
    toasts.show({ title: 'Brief notice', timeout: 1000 });
    fixture.detectChanges();
    advance(120000);
    expect(toasts.toasts().map((toast) => toast.title)).toEqual(['Review needed', 'Keep this notice']);
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    expect(toasts.toasts().map((toast) => toast.title)).toEqual(['Keep this notice']);
  });

  it.each(['pointer', 'keyboard'])('resumes only when both pointer and keyboard leave, with %s leaving first', (first) => {
    toasts.show({ title: 'Saved' });
    fixture.detectChanges();
    advance(5000);
    const toast: HTMLElement = fixture.nativeElement.querySelector('.gw-toast');
    const pointer = (name: string) => toast.dispatchEvent(new MouseEvent(name));
    const keyboard = (name: string) => toast.dispatchEvent(new FocusEvent(name, { bubbles: true, relatedTarget: null }));
    pointer('mouseenter');
    advance(10000);
    keyboard('focusin');
    // Repeated focus events must not consume reading time twice.
    keyboard('focusin');
    if (first === 'pointer') pointer('mouseleave');
    else keyboard('focusout');
    advance(60000);
    expect(toasts.toasts()).toHaveLength(1);
    if (first === 'pointer') keyboard('focusout');
    else pointer('mouseleave');
    advance(9999);
    expect(toasts.toasts()).toHaveLength(1);
    advance(1);
    expect(toasts.toasts()).toEqual([]);
  });

  it('cancels a timer when its notification is dismissed', () => {
    const toast = toasts.show({ title: 'Saved' });
    fixture.detectChanges();
    toasts.dismiss(toast.id);
    fixture.detectChanges();
    const dismiss = vi.spyOn(toasts, 'dismiss');
    advance(15000);
    expect(dismiss).not.toHaveBeenCalled();
  });

  it.each([undefined, null, 0, -1, 16, '30', NaN, Infinity])('falls back to 15 seconds for an invalid saved duration: %s', (value) => {
    toasts.setDuration(value);
    expect(toasts.show({ title: 'Saved' }).timeout).toBe(15000);
  });
});
