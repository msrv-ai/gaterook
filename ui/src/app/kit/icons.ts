import { Provider } from '@angular/core';
import { LucideDynamicIcon, provideLucideIcons } from '@lucide/angular';
import { KIT_ICONS } from './icons.generated';

/**
 * Registered Lucide icons (@lucide/angular 1.49.0), including compatibility aliases such as `trash-2`, `filter`,
 * and `history`. Icon data is bundled in `icons.generated.ts`. Icons are decorative (`aria-hidden`);
 * meaning is in the adjacent text.
 */
export function provideKitIcons(): Provider {
  return provideLucideIcons(...KIT_ICONS);
}

/** The icon component, under the kit's name. */
export { LucideDynamicIcon as GwIcon };
