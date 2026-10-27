import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** The kit's client marks (gw-client-mark--<kind>, two letters). Anything else is a generic first-letter mark. */
const MARKS: Record<string, { readonly kind: string; readonly text: string; readonly name: string }> = {
  'claude-desktop': { kind: 'claude', text: 'CD', name: 'Claude Desktop' },
  codex: { kind: 'codex', text: 'Cx', name: 'Codex' },
  cursor: { kind: 'cursor', text: 'Cu', name: 'Cursor' },
  antigravity: { kind: 'antigravity', text: 'Ag', name: 'Antigravity' },
  'claude-ai': { kind: 'claudeai', text: 'ai', name: 'claude.ai' },
  inspector: { kind: 'inspector', text: 'In', name: 'MCP Inspector' },
};

/** The display name of a client id ("claude-desktop" → "Claude Desktop"); unknown ids are shown as they are. */
export function clientName(id: string): string {
  return MARKS[id]?.name ?? id;
}

/**
 * `<span gwClientMark="codex" size="sm">`: the decorative two-letter mark of an MCP client (gw-client-mark); the name
 * is always in the text next to it.
 */
@Component({
  selector: 'span[gwClientMark]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': 'classes()', 'aria-hidden': 'true' },
  template: `{{ text() }}`,
})
export class GwClientMark {
  readonly gwClientMark = input.required<string>();
  readonly size = input<'sm' | 'md' | 'lg'>('md');
  /** A generic mark's letter, when the client has no kit mark ("S" for Slack). */
  readonly letter = input<string | null>(null);

  private readonly mark = computed(() => MARKS[this.gwClientMark()] ?? null);
  protected readonly text = computed(() => this.mark()?.text ?? this.letter() ?? (this.gwClientMark()[0] ?? '').toUpperCase());
  protected readonly classes = computed(() => {
    const kind = this.mark()?.kind ?? 'generic';
    const size = this.size() === 'md' ? '' : ` gw-client-mark--${this.size()}`;
    return `gw-client-mark gw-client-mark--${kind}${size}`;
  });
}
