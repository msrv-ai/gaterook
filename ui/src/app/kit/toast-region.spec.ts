import { Component, inject, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GwDialog } from './dialog';
import { provideKitIcons } from './icons';
import { GwToastRegion, GwToastTimer } from './toast-region';
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

@Component({
  imports: [GwDialog, GwToastRegion],
  template: `
    <gw-toast-region />
    <dialog gwDialog #first="gwDialog" aria-label="Delete connection">
      <p>Delete this connection?</p>
      <button type="button" autofocus>Cancel</button><button type="button">Delete</button>
    </dialog>
    <dialog gwDialog #second="gwDialog" aria-label="Another decision">
      <button type="button" autofocus>Cancel</button>
    </dialog>
  `,
})
class ModalToastHost {
  readonly first = viewChild.required<GwDialog>('first');
  readonly second = viewChild.required<GwDialog>('second');
}

describe('toast feedback during modal decisions', () => {
  let fixture: ComponentFixture<ModalToastHost>;
  let toasts: ToastService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideKitIcons()] });
    fixture = TestBed.createComponent(ModalToastHost);
    document.body.appendChild(fixture.nativeElement);
    toasts = TestBed.inject(ToastService);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  const visible = () => Array.from(fixture.nativeElement.querySelectorAll('.gw-toast:not([hidden])') as NodeListOf<HTMLElement>)
    .map((el) => el.querySelector('.gw-toast__title')?.textContent);
  const advance = (ms: number) => {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  };

  it('keeps previous deletion successes out of the next confirmation while showing its failure feedback', () => {
    toasts.show({ title: 'First connection deleted', tone: 'success' });
    toasts.show({ title: 'Second connection deleted', tone: 'success' });
    fixture.detectChanges();
    fixture.componentInstance.first().open();
    fixture.detectChanges();
    expect(visible()).toEqual([]);
    expect(fixture.componentInstance.first().element.textContent).toContain('Delete this connection?');

    toasts.show({ title: 'Connection could not be deleted', tone: 'danger' });
    fixture.detectChanges();
    expect(visible()).toEqual(['Connection could not be deleted']);
    expect(fixture.componentInstance.first().element.querySelector('.gw-toast:not([hidden])')?.getAttribute('role')).toBe('alert');
  });

  it('gives the same dialog a fresh feedback scope every time it opens', () => {
    const dialog = fixture.componentInstance.first();
    dialog.open();
    // Feedback can arrive before Angular renders the opening.
    toasts.show({ title: 'First attempt failed', tone: 'danger' });
    fixture.detectChanges();
    expect(visible()).toEqual(['First attempt failed']);
    dialog.close();
    dialog.open();
    fixture.detectChanges();
    expect(visible()).toEqual([]);
    toasts.show({ title: 'Current attempt failed', tone: 'danger' });
    fixture.detectChanges();
    expect(visible()).toEqual(['Current attempt failed']);
  });

  it('restores feedback for the underlying dialog after a nested decision closes', () => {
    toasts.show({ title: 'Page notice' });
    fixture.componentInstance.first().open();
    toasts.show({ title: 'First dialog notice' });
    fixture.detectChanges();
    fixture.componentInstance.second().open();
    toasts.show({ title: 'Second dialog notice' });
    fixture.detectChanges();
    expect(visible()).toEqual(['Second dialog notice']);

    fixture.componentInstance.second().close();
    fixture.detectChanges();
    expect(visible()).toEqual(['First dialog notice']);
    fixture.componentInstance.first().close();
    fixture.detectChanges();
    expect(visible()).toEqual(['Page notice', 'First dialog notice', 'Second dialog notice']);
    expect(fixture.nativeElement.querySelector('gw-toast-region').parentElement).toBe(fixture.nativeElement);
  });

  it('lets hidden success notices expire without restarting their duration after the dialog closes', () => {
    toasts.show({ title: 'Deleted', tone: 'success' });
    fixture.detectChanges();
    advance(5000);
    fixture.componentInstance.first().open();
    fixture.detectChanges();
    advance(9999);
    expect(toasts.toasts()).toHaveLength(1);
    advance(1);
    expect(toasts.toasts()).toEqual([]);
    fixture.componentInstance.first().close();
    fixture.detectChanges();
    expect(visible()).toEqual([]);
  });

  it('releases stale pointer and keyboard timer pauses when a notice is hidden by a dialog', () => {
    toasts.show({ title: 'Saved' });
    fixture.detectChanges();
    advance(5000);
    const toast: HTMLElement = fixture.nativeElement.querySelector('.gw-toast');
    toast.dispatchEvent(new MouseEvent('mouseenter'));
    toast.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    advance(20000);
    fixture.componentInstance.first().open();
    fixture.detectChanges();
    // Browsers need not dispatch pointer/focus leave events when the element is hidden.
    advance(9999);
    expect(toasts.toasts()).toHaveLength(1);
    advance(1);
    expect(toasts.toasts()).toEqual([]);
  });

  it('keeps persistent actions available after a modal closes', () => {
    const undo = vi.fn();
    toasts.show({ title: 'Settings changed', actions: [{ label: 'Undo', onClick: undo }] });
    fixture.detectChanges();
    fixture.componentInstance.first().open();
    fixture.detectChanges();
    advance(120000);
    expect(visible()).toEqual([]);
    expect(toasts.toasts()).toHaveLength(1);
    fixture.componentInstance.first().close();
    fixture.detectChanges();
    expect(visible()).toEqual(['Settings changed']);
    fixture.nativeElement.querySelector('.gw-toast__actions button').click();
    fixture.detectChanges();
    expect(undo).toHaveBeenCalledOnce();
    expect(toasts.toasts()).toEqual([]);
  });
});
