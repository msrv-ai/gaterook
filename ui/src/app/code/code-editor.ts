import {
  CSP_NONCE,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  booleanAttribute,
  effect,
  inject,
  input,
  model,
  output,
} from '@angular/core';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { PostgreSQL, sql } from '@codemirror/lang-sql';
import { yaml } from '@codemirror/lang-yaml';
import { Compartment, EditorState, Extension } from '@codemirror/state';
import { EditorView, keymap, placeholder as placeholderExt } from '@codemirror/view';
import { FormValueControl } from '@angular/forms/signals';

export type EditorLanguage = 'sql' | 'yaml' | 'json' | 'text';

/**
 * The editor's look in kit tokens only: it sits where a `gw-textarea--code` would and must read as one (no own
 * colours, sizes or fonts: design/AGENTS.md §4).
 */
const KIT_THEME = EditorView.theme({
  '&': {
    color: 'var(--gw-color-code-text)',
    backgroundColor: 'var(--gw-color-bg-field)',
    border: 'var(--gw-border-width-default) solid var(--gw-color-border-input)',
    borderRadius: 'var(--gw-radius-sm)',
    fontFamily: 'var(--gw-font-family-mono)',
    fontSize: 'var(--gw-font-size-sm)',
  },
  '&.cm-focused': { outline: 'var(--gw-focus-width) solid var(--gw-color-border-focus)' },
  '.cm-content': { fontFamily: 'var(--gw-font-family-mono)', fontVariantLigatures: 'none', padding: 'var(--gw-space-2) 0' },
  '.cm-line': { padding: '0 var(--gw-space-3)' },
  '.cm-cursor': { borderLeftColor: 'var(--gw-color-text-primary)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--gw-color-bg-selected)' },
  '.cm-placeholder': { color: 'var(--gw-color-text-placeholder)' },
});

function languageOf(lang: EditorLanguage): Extension {
  switch (lang) {
    case 'sql':
      return sql({ dialect: PostgreSQL });
    case 'yaml':
      return yaml();
    case 'json':
      return json();
    default:
      return [];
  }
}

/**
 * `<gw-code-editor>`: CodeMirror 6 for typing SQL, YAML or JSON (templates, Playground arguments, policy snippets) —
 * never for showing an action to approve (that is `gw-code-block` / `gw-diff`, ARCHITECTURE.md A19, §8.5). Load it
 * with `@defer`: CodeMirror (~320 KB) stays out of the initial bundle. The strict CSP needs the nonce for the styles
 * CodeMirror injects (`EditorView.cspNonce`). It is a `FormValueControl<string>`, so Signal Forms bind it with
 * `[formField]`.
 */
@Component({
  selector: 'gw-code-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  host: { '[attr.aria-invalid]': 'invalid() ? "true" : null' },
})
export class GwCodeEditor implements FormValueControl<string> {
  readonly value = model('');
  readonly language = input<EditorLanguage>('text');
  readonly label = input.required<string>();
  readonly describedBy = input<string | null>(null);
  readonly placeholder = input<string | null>(null);
  readonly readonly = input(false, { transform: booleanAttribute });
  readonly invalid = input(false, { transform: booleanAttribute });
  readonly touch = output<void>();

  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly nonce = inject(CSP_NONCE, { optional: true });
  private view: EditorView | null = null;
  private readonly lang = new Compartment();
  private readonly ro = new Compartment();

  constructor() {
    afterNextRender(() => this.create());
    effect(() => {
      const v = this.value();
      const view = this.view;
      if (view && view.state.doc.toString() !== v) {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: v } });
      }
    });
    effect(() => {
      const lang = this.language();
      this.view?.dispatch({ effects: this.lang.reconfigure(languageOf(lang)) });
    });
    effect(() => {
      const ro = this.readonly();
      this.view?.dispatch({ effects: this.ro.reconfigure(EditorState.readOnly.of(ro)) });
    });
    inject(DestroyRef).onDestroy(() => this.view?.destroy());
  }

  focus(): void {
    this.view?.focus();
  }

  private create(): void {
    const attrs: Record<string, string> = { 'aria-label': this.label(), 'aria-multiline': 'true', spellcheck: 'false' };
    const described = this.describedBy();
    if (described) attrs['aria-describedby'] = described;
    this.view = new EditorView({
      parent: this.host,
      state: EditorState.create({
        doc: this.value(),
        extensions: [
          ...(this.nonce ? [EditorView.cspNonce.of(this.nonce)] : []),
          KIT_THEME,
          EditorView.lineWrapping,
          EditorView.contentAttributes.of(attrs),
          history(),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          this.lang.of(languageOf(this.language())),
          this.ro.of(EditorState.readOnly.of(this.readonly())),
          ...(this.placeholder() ? [placeholderExt(this.placeholder()!)] : []),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) this.value.set(u.state.doc.toString());
            if (u.focusChanged && !u.view.hasFocus) this.touch.emit();
          }),
        ],
      }),
    });
  }
}
