import { Directive, ElementRef, inject, model } from '@angular/core';
import { Announcer } from './announcer';

/**
 * `<button gwPressed [(pressed)]>` (kit.js "pressed"): flips `aria-pressed` (Live, view toggles) and announces
 * "Live on" / "Live off" from the button's label.
 */
@Directive({
  selector: 'button[gwPressed]',
  host: { '[attr.aria-pressed]': 'String(pressed())', '(click)': 'toggle()' },
})
export class GwPressed {
  private readonly el: HTMLButtonElement = inject(ElementRef<HTMLButtonElement>).nativeElement;
  private readonly announcer = inject(Announcer);
  readonly pressed = model(false);
  protected readonly String = String;

  toggle(): void {
    const on = !this.pressed();
    this.pressed.set(on);
    const label = this.el.getAttribute('aria-label') ?? this.el.textContent?.trim() ?? '';
    this.announcer.announce(`${label}${on ? ' on' : ' off'}`);
  }
}
