import { Directive, ElementRef, inject, input, output } from '@angular/core';
import { Announcer } from './announcer';

export type FileDropState = 'idle' | 'dragover' | 'uploaded' | 'error';

/**
 * The kit's `label.gw-file-drop` (kit.js "file drop"): `data-state` follows dragenter / dragover (`dragover`),
 * dragleave (`idle`), a drop (the files go into its `input[type=file]`, which fires `change`) and the choice
 * (`uploaded`, the name in `[data-gw-file-name]`, announced). `error` is the page's verdict (`gwFileDropError`):
 * too large, wrong type. A plugin JAR is ≤ 50 MB, an OpenAPI file ≤ 20 MB (`gwFileDropMaxBytes`).
 */
@Directive({
  selector: 'label.gw-file-drop',
  host: {
    '(dragenter)': 'onDragOver($event)',
    '(dragover)': 'onDragOver($event)',
    '(dragleave)': 'onDragLeave($event)',
    '(drop)': 'onDrop($event)',
    '(change)': 'onChange($event)',
  },
})
export class GwFileDrop {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly announcer = inject(Announcer);
  protected state: FileDropState = 'idle';

  readonly gwFileDropMaxBytes = input<number | null>(null);
  readonly fileChange = output<File | null>();
  /** A file over the size limit: the page shows its error text. */
  readonly tooLarge = output<File>();

  constructor() {
    if (!this.el.hasAttribute('data-state')) this.el.setAttribute('data-state', 'idle');
  }

  setState(state: FileDropState): void {
    this.state = state;
    this.el.setAttribute('data-state', state);
  }

  protected onDragOver(e: DragEvent): void {
    e.preventDefault();
    this.setState('dragover');
  }

  protected onDragLeave(e: DragEvent): void {
    if (!this.el.contains(e.relatedTarget as Node | null)) this.setState('idle');
  }

  protected onDrop(e: DragEvent): void {
    e.preventDefault();
    const input = this.el.querySelector<HTMLInputElement>('input[type=file]');
    if (input && e.dataTransfer && e.dataTransfer.files.length) {
      input.files = e.dataTransfer.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      this.setState('idle');
    }
  }

  protected onChange(e: Event): void {
    const input = e.target instanceof HTMLInputElement && e.target.type === 'file' ? e.target : null;
    if (!input) return;
    const file = input.files?.[0] ?? null;
    const max = this.gwFileDropMaxBytes();
    if (file && max !== null && file.size > max) {
      this.setState('error');
      input.setAttribute('aria-invalid', 'true');
      this.tooLarge.emit(file);
      return;
    }
    input.removeAttribute('aria-invalid');
    this.setState(file ? 'uploaded' : 'idle');
    const name = this.el.querySelector('[data-gw-file-name]');
    if (name && file) name.textContent = file.name;
    if (file) this.announcer.announce(`${file.name} selected`);
    this.fileChange.emit(file);
  }
}
