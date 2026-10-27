import { EnvironmentProviders, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import { AriaDisabledGuard, GwAriaDisabled } from './aria-disabled';
import { GwChart } from './chart';
import { GwCopy } from './copy';
import { GwCountdown } from './countdown';
import { GwDialog, GwDialogClose, GwDialogNext, GwDialogOpen } from './dialog';
import { GwExpand, GwToggle } from './disclosure';
import { GwEnableWhen } from './enable-when';
import { GwFileDrop } from './file-drop';
import { GwIcon, provideKitIcons } from './icons';
import { GwMenu, GwMenuTrigger } from './menu';
import { GwPressed } from './pressed';
import { GwReveal } from './reveal';
import { GwSearchClear, GwSearchClearButton } from './search';
import { GwSecret } from './secret';
import { GwSingleKeysToggle, ShortcutService } from './shortcuts';
import { GwSidebarToggle } from './sidebar';
import { GwIndeterminate, GwSelectable, GwSortRow } from './table';
import { GwFragmentTabs, GwTabs } from './tabs';
import { GwThemeControl } from './theme';
import { GwToast, GwToastRegion } from './toast-region';
import { TooltipDismiss } from './tooltip';

/**
 * Every kit.js behaviour as an Angular directive or component (ARCHITECTURE.md §8.2), for a page's `imports`.
 * The document-wide behaviours (aria-disabled guard, "/" and ⌘K, tooltip Esc) are services installed once by
 * {@link provideKit}.
 */
export const KIT = [
  GwIcon,
  GwAriaDisabled,
  GwMenu,
  GwMenuTrigger,
  GwTabs,
  GwFragmentTabs,
  GwDialog,
  GwDialogOpen,
  GwDialogClose,
  GwDialogNext,
  GwToggle,
  GwExpand,
  GwCopy,
  GwToast,
  GwToastRegion,
  GwCountdown,
  GwSortRow,
  GwSelectable,
  GwIndeterminate,
  GwThemeControl,
  GwSidebarToggle,
  GwSearchClearButton,
  GwSearchClear,
  GwReveal,
  GwFileDrop,
  GwChart,
  GwPressed,
  GwEnableWhen,
  GwSingleKeysToggle,
  GwSecret,
] as const;

/**
 * Installs the kit: icons, and — before any page renders — the capture-phase aria-disabled guard (it must see
 * activations before every other listener), the keyboard shortcuts and tooltip dismissal.
 */
export function provideKit(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideKitIcons(),
    provideAppInitializer(() => {
      inject(AriaDisabledGuard).install();
      inject(ShortcutService).install();
      inject(TooltipDismiss).install();
    }),
  ]);
}
