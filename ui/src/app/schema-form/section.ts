import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, TemplateRef, computed, input } from '@angular/core';
import { LucideDynamicIcon } from '@lucide/angular';
import { GwToggle } from '../kit/disclosure';
import { SchemaNode } from './compile';
import { GwSchemaFields } from './fields';
import { SchemaForm } from './schema-form';

/**
 * One top-level object of the schema as a form section (ARCHITECTURE.md §8.3, gw-form contract): a `fieldset` with
 * its legend, or — when it holds a nested fieldset (choice cards, a sub-object, the page's own `lead` such as the
 * secret source picker) — a `section` with an h3 legend. `x-collapsed` puts the fields behind a disclosure button
 * ("Advanced settings", kit.js data-gw-toggle). `lead` renders first in the section's rows (the connection's Name
 * in Connection, the source cards in Credentials).
 */
@Component({
  selector: 'gw-schema-section',
  imports: [NgTemplateOutlet, LucideDynamicIcon, GwToggle, GwSchemaFields],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let n = node();
    @let f = form();
    @let id = f.id(n);
    <ng-template #rows>
      <ng-container [ngTemplateOutlet]="lead()" />
      @if (n.collapsed) {
        <button
          class="gw-btn gw-btn--ghost gw-btn--sm gw-connection-form__toggle"
          type="button"
          gwToggle
          [expanded]="f.isExpanded(n)"
          (expandedChange)="f.setExpanded(n, $event)"
          [attr.aria-controls]="id + '-body'"
        >
          <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-connection-form__when-closed"></svg
          ><svg lucideIcon="chevron-up" class="gw-icon gw-icon--sm gw-connection-form__when-open"></svg>{{ toggleLabel() }}
        </button>
        <div class="gw-form__rows" [id]="id + '-body'" [hidden]="!f.isExpanded(n)">
          <gw-schema-fields [form]="f" [node]="n" />
        </div>
      } @else {
        <gw-schema-fields [form]="f" [node]="n" />
      }
      <ng-container [ngTemplateOutlet]="trail()" />
    </ng-template>

    @if (asSection()) {
      <section class="gw-form__section" [class]="sectionClass()" [attr.aria-labelledby]="id + '-t'">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" [id]="id + '-t'">{{ n.title }}</h3>
          @if (descriptionTemplate()) {
            <p class="gw-form__description"><ng-container [ngTemplateOutlet]="descriptionTemplate()" /></p>
          } @else if (text()) {
            <p class="gw-form__description">{{ text() }}</p>
          }
        </header>
        <div class="gw-form__rows"><ng-container [ngTemplateOutlet]="rows" /></div>
      </section>
    } @else {
      <fieldset class="gw-form__section" [class]="sectionClass()">
        <legend class="gw-form__legend">{{ n.title }}</legend>
        @if (descriptionTemplate()) {
          <p class="gw-form__description"><ng-container [ngTemplateOutlet]="descriptionTemplate()" /></p>
        } @else if (text()) {
          <p class="gw-form__description">{{ text() }}</p>
        }
        <div class="gw-form__rows"><ng-container [ngTemplateOutlet]="rows" /></div>
      </fieldset>
    }
  `,
})
export class GwSchemaSection {
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  /** Rendered first in the section's rows. */
  readonly lead = input<TemplateRef<unknown> | null>(null);
  /** Rendered last in the section's rows. */
  readonly trail = input<TemplateRef<unknown> | null>(null);
  /** Extra classes of the section (`gw-connection-form__credentials`). */
  readonly sectionClass = input<string>('');

  /** The page's own description of the section (the schema's otherwise). */
  readonly description = input<string | null>(null);
  /** The description with markup (a mono name, an env marker), in place of `description`. */
  readonly descriptionTemplate = input<TemplateRef<unknown> | null>(null);
  /** Whether the section holds a nested fieldset; by default: a `lead`, choice cards or a sub-object do. */
  readonly nested = input<boolean | null>(null);

  protected readonly text = computed(() => this.description() ?? this.node().description);

  /** Sections holding a nested fieldset are a `section` + h3 (gw-form contract: one legend heads one fieldset). */
  protected readonly asSection = computed(
    () => this.nested() ?? (!!this.lead() || this.node().children.some((c) => c.kind === 'choice-cards' || c.kind === 'object')),
  );

  protected readonly toggleLabel = computed(() => {
    const title = this.node().title;
    return /settings$/i.test(title) ? title : `${title} settings`;
  });
}
