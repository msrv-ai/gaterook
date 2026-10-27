import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { SchemaNode, rootSection } from './compile';
import { SchemaForm } from './schema-form';
import { GwSchemaSection } from './section';

/**
 * `<gw-schema-form [form]="state">` inside a `<form class="gw-form">`: every section of a schema form in order — the
 * root's own fields first, as one section titled by the schema — for the forms that need nothing around them
 * (an MCP server's settings, a secret store, a template's parameters). The connection form composes the same
 * sections with the gateway's own parts in between (kit/elements/connection-form).
 */
@Component({
  selector: 'gw-schema-form',
  imports: [GwSchemaSection],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loose(); as root) {
      <gw-schema-section [form]="form()" [node]="root" />
    }
    @for (section of form().compiled.sections; track section) {
      <gw-schema-section [form]="form()" [node]="section" />
    }
  `,
})
export class GwSchemaForm {
  readonly form = input.required<SchemaForm>();
  /** The title of the section of root-level fields ("Settings"). */
  readonly title = input('Settings');

  /** The root's scalar fields as one pseudo-section, so they get a legend and rows like the others. */
  protected readonly loose = computed<SchemaNode | null>(() => {
    const fields = this.form().compiled.loose;
    return fields.length ? rootSection(fields, this.title(), fields.map((c) => [c])) : null;
  });
}
