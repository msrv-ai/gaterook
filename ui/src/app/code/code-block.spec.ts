import { TestBed } from '@angular/core/testing';
import { ClipboardWriter } from '../kit/clipboard';
import { provideKitIcons } from '../kit/icons';
import { GwCodeBlock } from './code-block';

describe('code block display format', () => {
  const original = '{"id":9007199254740993,"updates":[{"$set":{"active":true}}]}';

  beforeEach(() => TestBed.configureTestingModule({ providers: [provideKitIcons()] }));
  afterEach(() => { document.body.innerHTML = ''; });

  function render(text = original, pretty = true, language = 'json') {
    const fixture = TestBed.createComponent(GwCodeBlock);
    fixture.componentRef.setInput('text', text);
    fixture.componentRef.setInput('pretty', pretty);
    fixture.componentRef.setInput('language', language);
    fixture.componentRef.setInput('regionLabel', 'Request');
    fixture.componentRef.setInput('copyLabel', 'request');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const code = () => element.querySelector('code')!.textContent!;
    const select = (value: string) => {
      const radio = element.querySelector<HTMLInputElement>(`input[value="${value}"]`)!;
      radio.checked = true;
      radio.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    };
    return { fixture, element, code, select };
  }

  it('starts with readable JSON, and restores the original without changing its input', () => {
    const view = render();
    expect(view.code()).toContain('\n  "id": 9007199254740993,\n');
    expect(view.element.querySelector('[role="region"]')!.getAttribute('aria-label')).toBe('Request (pretty JSON)');
    view.select('original');
    expect(view.code()).toBe(original);
    expect(view.element.querySelector('[role="region"]')!.getAttribute('aria-label')).toBe('Request');
    expect(view.fixture.componentInstance.text()).toBe(original);
    view.select('pretty');
    expect(view.code()).toContain('\n    {\n      "$set": {');
  });

  it('copies exactly the active view, including all number digits', async () => {
    const write = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({ providers: [{ provide: ClipboardWriter, useValue: { write } }] });
    const view = render();
    const copy = view.element.querySelector<HTMLButtonElement>('button')!;
    const formatted = view.code();
    copy.click();
    await view.fixture.whenStable();
    expect(write).toHaveBeenLastCalledWith(formatted);
    view.select('original');
    copy.click();
    await view.fixture.whenStable();
    expect(write).toHaveBeenLastCalledWith(original);
  });

  it('keeps Original selected through a same-content refresh and resets for another request', () => {
    const view = render();
    view.select('original');
    view.fixture.componentRef.setInput('segments', [{ t: 'text', v: original }]);
    view.fixture.detectChanges();
    expect(view.code()).toBe(original);
    view.fixture.componentRef.setInput('segments', [{ t: 'text', v: '{"next":[1,2]}' }]);
    view.fixture.detectChanges();
    expect(view.code()).toBe('{\n  "next": [\n    1,\n    2\n  ]\n}');
    expect(view.element.querySelector<HTMLInputElement>('input[value="pretty"]')!.checked).toBe(true);
  });

  it('offers no formatting switch for unsupported, invalid, unchanged or non-opted-in text', () => {
    for (const [text, pretty, language] of [
      ['SELECT id FROM orders', true, 'sql'],
      ['{"unfinished":', true, 'json'],
      ['{\n  "id": 1\n}', true, 'json'],
      [original, false, 'json'],
    ] as const) {
      const view = render(text, pretty, language);
      expect(view.code()).toBe(text);
      expect(view.element.querySelector('fieldset')).toBeNull();
      view.fixture.destroy();
    }
  });

  it('offers the format controls when the copy button and header labels are absent', () => {
    const view = render();
    view.fixture.componentRef.setInput('copy', false);
    view.fixture.detectChanges();
    expect(view.element.querySelector('legend')!.textContent).toBe('Display format for request');
    expect(view.element.querySelector('button')).toBeNull();
    view.select('original');
    expect(view.code()).toBe(original);
  });

  it('gives each block an independent native radio group controlling its own code', () => {
    const first = render();
    const second = render();
    const radio = (element: HTMLElement) => element.querySelector<HTMLInputElement>('input')!;
    expect(radio(first.element).name).not.toBe(radio(second.element).name);
    expect(radio(first.element).getAttribute('aria-controls')).toBe(first.element.querySelector('pre')!.id);
    first.select('original');
    expect(second.code()).toContain('\n  "id":');
  });
});
