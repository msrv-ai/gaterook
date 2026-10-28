/*!
 * GateRook Design Kit · reference behaviours (kit.js)
 * ------------------------------------------------------------------
 * Vanilla, dependency-free implementations of every interactive pattern
 * the kit's demo pages use. It is the executable spec for the Angular
 * components: same keyboard model, same ARIA, same states. Load once,
 * after lucide, at the end of <body>:
 *
 *   <script src="../vendor/lucide/lucide.min.js"></script>
 *   <script src="../assets/kit.js"></script>
 *
 * Guard (runs before everything else, capture phase):
 *   aria-disabled  [aria-disabled="true"] (or a <label> of such an input) never acts: click,
 *                Enter / Space (and ↑/↓ on a menu trigger) and a submit by it are cancelled
 *                before any hook or page script sees them; a radio / checkbox with it can't
 *                be checked (a change that slips through, e.g. arrow keys in a radio group,
 *                is reverted). The control stays focusable and explains itself through
 *                aria-describedby: the kit's "unavailable, with a reason" state.
 *
 * Declarative hooks (no page script needed):
 *   icons        <i class="gw-icon" data-lucide="name">            → Lucide SVG, aria-hidden
 *   menu         .gw-menu-host > button[aria-haspopup][aria-controls] + .gw-menu / .gw-popover
 *                click / Enter / Space opens, Esc closes and refocuses, arrows move,
 *                outside click closes, one open at a time. Never on hover or focus.
 *                The open panel goes to the top layer (Popover API, popover="manual"),
 *                placed under its trigger from the trigger's rect, so a gw-table-wrap or a
 *                dialog body never clips it; it flips above (gw-menu--up / gw-popover--up)
 *                when there is more room there, and follows scrolling and resizing.
 *                A popover (aria-haspopup="dialog") focuses its checked radio, else its
 *                first field.
 *   tabs         [role=tablist] > [role=tab][aria-controls]        arrows, Home/End, automatic activation;
 *                a URL #hash pointing at a tab, a panel or anything inside one selects that tab
 *   dialog       button[data-gw-dialog-open="id"] → <dialog id> .gw-dialog / .gw-drawer (showModal)
 *                [data-gw-dialog-close] inside closes; backdrop click closes unless data-gw-static.
 *                The dialog's initial focus is in the MARKUP: autofocus on its first field, or on
 *                Cancel in a confirmation. On close, focus returns to the opener (the menu's
 *                trigger when the opener was a menu item; the first opener of a dialog chain).
 *   disclosure   button[data-gw-toggle][aria-controls][aria-expanded]  toggles `hidden` on the target
 *   expand       button[data-gw-expand="#id"] opens the disclosure #id (and syncs the aria-expanded of its
 *                data-gw-toggle button), then moves focus into it: its first tabindex="0" region, else
 *                #id itself. "View raw" in an event detail → the raw JSON, expanded and focused.
 *   copy         button[data-gw-copy="text"] or [data-gw-copy-from="#id"] → clipboard + "Copied" state;
 *                announces "Copied to clipboard" unless the button also has data-gw-toast
 *                (the toast is the more specific message: one announcement per action)
 *   toast        button[data-gw-toast="message"] [data-gw-toast-tone] [data-gw-toast-title]; or gwToast({...})
 *   countdown    [data-gw-countdown="seconds"] with [data-gw-countdown-text] inside; sets --gw-countdown (1→0).
 *                At 60 s or less: [data-urgent], gw-progress--warning → --danger inside it, and its
 *                [role=timer] line turns gw-fg-danger (the icon drops gw-fg-warning).
 *                At 0: [data-expired], the bar is hidden, the timer line reads
 *                data-gw-countdown-expired (default "Expired"; "Expired, denied automatically" in
 *                an approval request) with a timer-off icon, and `gw-countdown-end` bubbles from it.
 *                Inside a pending (or partially approved) .gw-approval-request the request expires too: data-state="expired",
 *                the badge becomes neutral "Expired", the decision caption says nothing ran, Deny and
 *                Approve and run become aria-disabled (described by that caption), the comment is
 *                read-only, and "<tool> on <connection> expired" is announced once.
 *   sort         th[aria-sort] > button.gw-table__sort  cycles none → ascending → descending
 *   theme        input[name=gw-theme] / input[name=gw-contrast] radios bound to window.gwTheme
 *   sidebar      button[data-gw-sidebar-toggle] toggles data-collapsed on .gw-shell (persisted)
 *   select rows  input[data-gw-select-all] in a thead checks every input[data-gw-select-row] in its table
 *   menu close   [data-gw-menu-close] inside a menu / popover closes it and refocuses the trigger
 *   tooltip      Esc hides the bubble of the focused / hovered .gw-tooltip (WCAG 1.4.13 dismissible)
 *   search       .gw-search__clear empties its .gw-search__input, fires `input`, refocuses
 *   reveal       button[data-gw-reveal][aria-controls] toggles a NEW secret input password ↔ text + aria-pressed
 *   file drop    .gw-file-drop gets data-state dragover / uploaded; [data-gw-file-name] shows the file name
 *   chart bars   .gw-chart__bar[aria-pressed] buttons: one pressed per chart (the selected range)
 *   indeterminate input[data-gw-indeterminate] starts indeterminate
 *   pressed      button[data-gw-pressed][aria-pressed] flips aria-pressed (Live, view toggles) and announces it
 *   ext. clear   button[data-gw-search-clear="#input"] empties that search field (e.g. from an empty state)
 *   dialog steps form[data-gw-dialog-next="id"] (or a button with it) closes this dialog when valid and opens the next
 *   dialog guard <dialog data-gw-dialog-guard>: Esc / backdrop can't close it while its form is invalid
 *                (a one-time secret stays until "I've stored this token" is checked)
 *   enable when  button[data-gw-enable-when="#checkbox"] is aria-disabled until that checkbox is checked,
 *                or, pointed at a text field (textarea / input), until it holds non-blank text: the
 *                prod "comment required" Approve and run (a native `disabled` in the markup is the
 *                no-script fallback and is lifted); point the button's aria-describedby at the hint
 *                that says what to check or type
 *   persist      button[data-gw-toggle][data-gw-toggle-persist="key"] remembers open / closed
 *   palette      .gw-command-palette with an input[role=combobox][aria-controls → role=listbox]
 *                (elements/command-palette.html): typing filters the [role=option] rows by their
 *                text + data-keywords and hides empty groups; Up / Down (wrapping), Home / End
 *                move the active option (aria-activedescendant on the input, aria-selected +
 *                gw-menu__item--active on the option, scrolled into view), and so does the
 *                pointer when it really moves; Enter clicks the active option (follows its link,
 *                or runs its data-gw-copy / data-gw-dialog-close / data-cmdk-theme action);
 *                [data-cmdk-status] gets "4 results" / "No results", [data-cmdk-empty] shows with
 *                the query in [data-cmdk-query]. In the modal, Esc always closes (the first Esc of a
 *                type=search field would only clear it) and focus returns to where it was; closing
 *                resets the query and the active option. DOM focus never leaves the input.
 *   ⌘K / Ctrl+K  toggles <dialog id="cmdk"> (the shell's palette) from anywhere, unless another
 *                modal dialog is open
 *   "/" key      focuses the visible field with aria-keyshortcuts="/" (the page's search / filter),
 *                only when focus is NOT in an editable field (input, textarea, select,
 *                contenteditable) and no menu is open, and never while <html> has
 *                data-gw-single-keys="off" (WCAG 2.1.4: single-character shortcuts can be
 *                turned off). Inside an open modal dialog only its own field counts.
 *   single keys  input[type=checkbox][data-gw-single-keys-toggle] (Settings › General, a gw-switch,
 *                default on) turns single-key shortcuts on / off: data-gw-single-keys="off" on
 *                <html>, remembered in localStorage "gw-single-keys"
 * Toasts and announcements go into the open modal <dialog> when there is one (body is inert then).
 * Helpers: window.gwAnnounce(text, { assertive }), window.gwToast({ title, body, tone, icon, timeout, actions: [{ label, onClick }] }),
 *          window.gwIcons(root). data-gw-countdown-announce="…" is read out once (assertive) when the last minute starts.
 */
(function () {
  "use strict";
  var doc = document;

  // ───────────────────────── live region ─────────────────────────
  var live = doc.createElement("div");
  live.className = "gw-sr-only";
  live.setAttribute("role", "status");
  live.setAttribute("aria-live", "polite");
  var liveAlert = doc.createElement("div");
  liveAlert.className = "gw-sr-only";
  liveAlert.setAttribute("role", "alert");
  function topLayer() { return doc.querySelector("dialog[open]:modal") || doc.body; }
  function announce(text, opts) {
    var region = opts && opts.assertive ? liveAlert : live;
    var host = topLayer();
    if (region.parentNode !== host) host.appendChild(region);
    region.textContent = "";
    setTimeout(function () { region.textContent = text; }, 50);
  }
  window.gwAnnounce = announce;
  var uidCount = 0;
  function uid(prefix) { uidCount += 1; return prefix + "-" + uidCount; }
  function describeBy(el, id) {
    var ids = (el.getAttribute("aria-describedby") || "").split(/\s+/).filter(Boolean);
    if (ids.indexOf(id) < 0) ids.push(id);
    el.setAttribute("aria-describedby", ids.join(" "));
  }

  // ───────────────────────── aria-disabled guard ─────────────────────────
  // "Unavailable, with a reason" = aria-disabled="true" + aria-describedby → the reason:
  // the control stays in the Tab order and explains itself, but it never acts.
  // Capture phase on document, registered before every other listener, so no kit
  // hook (dialog, toast, copy, toggle, pressed, dialog steps, menus) and no page
  // script ever sees the activation.
  function offTarget(node) {
    if (!node || !node.closest) return null;
    var off = node.closest('[aria-disabled="true"]');
    if (off) return off;
    var label = node.closest("label");
    var control = label && label.control;
    return control && control.getAttribute("aria-disabled") === "true" ? control : null;
  }
  doc.addEventListener("click", function (e) {
    if (!offTarget(e.target)) return;
    e.preventDefault(); // also keeps a radio / checkbox from toggling
    e.stopImmediatePropagation();
    // still an outside click for an open menu
    if (openMenu && !openMenu.panel.contains(e.target) && !openMenu.trigger.contains(e.target)) closeMenu(false);
  }, true);
  // The radio that was checked before a pointer or key acts on a group, so a change
  // to an aria-disabled option (arrow keys move AND check) can be reverted.
  var radioBefore = null;
  function snapshotRadio(e) {
    var t = e.target;
    if (!t || !t.closest) return;
    var r = t.closest('input[type="radio"]') || (t.closest("label") && t.closest("label").control);
    if (!r || r.type !== "radio" || !r.name) return;
    radioBefore = Array.prototype.filter.call(doc.getElementsByName(r.name), function (x) {
      return x.type === "radio" && x.form === r.form && x.checked;
    })[0] || null;
  }
  doc.addEventListener("pointerdown", snapshotRadio, true);
  doc.addEventListener("keydown", function (e) {
    snapshotRadio(e);
    var off = offTarget(e.target);
    if (!off) return;
    var activates = e.key === "Enter" || e.key === " " ||
      ((e.key === "ArrowDown" || e.key === "ArrowUp") && off.hasAttribute("aria-haspopup"));
    if (activates) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
  // Some engines activate a button or a checkbox when Space is RELEASED: cancel that too.
  doc.addEventListener("keyup", function (e) {
    if (e.key !== " " || !offTarget(e.target)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
  }, true);
  doc.addEventListener("change", function (e) {
    var input = e.target;
    if (!input || !input.getAttribute || input.getAttribute("aria-disabled") !== "true") return;
    if (input.type === "checkbox") input.checked = !input.checked;
    else if (input.type === "radio") {
      if (radioBefore && radioBefore !== input) radioBefore.checked = true; else input.checked = false;
    } else return;
    e.stopImmediatePropagation();
  }, true);
  doc.addEventListener("submit", function (e) {
    var by = e.submitter;
    if (by && by.getAttribute("aria-disabled") === "true") { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);

  // ───────────────────────── icons ─────────────────────────
  function icons(rootNode) {
    if (!window.lucide) return;
    window.lucide.createIcons({ attrs: { "aria-hidden": "true", focusable: "false" }, root: rootNode || doc });
  }
  window.gwIcons = icons;

  // ───────────────────────── menus & popovers ─────────────────────────
  var openMenu = null;
  function menuItems(panel) {
    return Array.prototype.slice.call(panel.querySelectorAll('[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"],[role="option"],.gw-menu__item'))
      .filter(function (el) { return !el.disabled && el.offsetParent !== null; }); // aria-disabled items stay reachable (APG): their reason is readable
  }
  // Top layer: a panel inside a scroller (gw-table-wrap, a dialog body) is never
  // clipped. Without the Popover API it stays absolute under its trigger.
  var canPopover = typeof HTMLElement !== "undefined" && typeof HTMLElement.prototype.showPopover === "function";
  var MENU_GAP = 4; // px between trigger and panel, = --gw-space-1 (the CSS gap)
  function placeMenu(m) {
    var panel = m.panel;
    var upClass = panel.classList.contains("gw-popover") ? "gw-popover--up" : "gw-menu--up";
    var r = m.trigger.getBoundingClientRect();
    var vw = doc.documentElement.clientWidth, vh = doc.documentElement.clientHeight;
    panel.classList.remove(upClass);
    panel.style.maxHeight = "";
    var h = panel.offsetHeight, w = panel.offsetWidth;
    var below = vh - r.bottom - 2 * MENU_GAP, above = r.top - 2 * MENU_GAP;
    var up = h > below && above > below;
    panel.classList.toggle(upClass, up);
    if (!panel.hasAttribute("popover")) return; // absolute fallback: the --up class flips it
    var room = up ? above : below;
    if (h > room) { panel.style.maxHeight = Math.max(room, 0) + "px"; h = panel.offsetHeight; }
    var rtl = getComputedStyle(panel).direction === "rtl";
    var atEnd = panel.classList.contains("gw-menu--right") || panel.classList.contains("gw-popover--right");
    var left = atEnd !== rtl ? r.right - w : r.left;
    left = Math.max(MENU_GAP, Math.min(left, vw - w - MENU_GAP));
    panel.style.left = left + "px";
    panel.style.top = (up ? r.top - MENU_GAP - h : r.bottom + MENU_GAP) + "px";
  }
  function closeMenu(returnFocus) {
    if (!openMenu) return;
    var m = openMenu; openMenu = null;
    if (m.panel.hasAttribute("popover")) {
      try { m.panel.hidePopover(); } catch (err) { /* already hidden */ }
      m.panel.removeAttribute("popover");
    }
    m.panel.style.top = m.panel.style.left = m.panel.style.maxHeight = "";
    m.panel.classList.remove("gw-menu--up", "gw-popover--up");
    m.panel.hidden = true;
    m.trigger.setAttribute("aria-expanded", "false");
    if (returnFocus) m.trigger.focus();
  }
  function openMenuFor(trigger, focusLast) {
    var panel = doc.getElementById(trigger.getAttribute("aria-controls"));
    if (!panel) return;
    closeMenu(false);
    panel.hidden = false;
    if (canPopover) {
      panel.setAttribute("popover", "manual");
      try { panel.showPopover(); } catch (err) { panel.removeAttribute("popover"); }
    }
    trigger.setAttribute("aria-expanded", "true");
    openMenu = { trigger: trigger, panel: panel };
    placeMenu(openMenu);
    var items = menuItems(panel);
    var selected = items.filter(function (i) { return i.getAttribute("aria-selected") === "true" || i.getAttribute("aria-checked") === "true"; })[0];
    // A popover starts at its CHECKED radio (Appearance: the current theme), else its first field.
    var target = trigger.getAttribute("aria-haspopup") === "dialog"
      ? panel.querySelector('input[type="radio"]:checked, input:not([type="radio"]):not([type="hidden"]), select, textarea, button, [tabindex]:not([tabindex="-1"])') ||
        panel.querySelector("input,select,textarea,button,[tabindex]:not([tabindex='-1'])")
      : selected || (focusLast ? items[items.length - 1] : items[0]);
    if (target) target.focus();
  }
  function followTrigger(e) {
    if (!openMenu) return;
    if (e && e.target && e.target.nodeType === 1 && openMenu.panel.contains(e.target)) return; // the panel's own scroll
    placeMenu(openMenu);
  }
  window.addEventListener("scroll", followTrigger, true);
  window.addEventListener("resize", followTrigger);
  doc.addEventListener("click", function (e) {
    var trigger = e.target.closest(".gw-menu-host > [aria-haspopup][aria-controls]");
    if (trigger) {
      e.preventDefault();
      if (openMenu && openMenu.trigger === trigger) closeMenu(true); else openMenuFor(trigger);
      return;
    }
    if (openMenu && !openMenu.panel.contains(e.target)) closeMenu(false);
    if (e.target.closest('[aria-disabled="true"]') && openMenu && openMenu.panel.contains(e.target)) { e.preventDefault(); return; }
    if (e.target.closest("[data-gw-menu-close]") && openMenu) { closeMenu(true); return; }
    var checkItem = e.target.closest('.gw-menu [role="menuitemcheckbox"]');
    if (checkItem && openMenu) { checkItem.setAttribute("aria-checked", String(checkItem.getAttribute("aria-checked") !== "true")); return; }
    var item = e.target.closest('.gw-menu [role="option"], .gw-menu [role="menuitemradio"]');
    if (item && openMenu) {
      var group = item.closest('[role="listbox"],[role="group"],.gw-menu');
      var attrName = item.getAttribute("role") === "option" ? "aria-selected" : "aria-checked";
      group.querySelectorAll('[' + attrName + ']').forEach(function (i) { i.setAttribute(attrName, "false"); });
      item.setAttribute(attrName, "true");
      var valueSlot = openMenu.trigger.querySelector("[data-gw-menu-value]");
      if (valueSlot) valueSlot.textContent = (item.querySelector(".gw-menu__label") || item).textContent.trim();
      closeMenu(true);
    } else if (e.target.closest('.gw-menu [role="menuitem"]') && openMenu) {
      closeMenu(true);
    }
  });
  doc.addEventListener("keydown", function (e) {
    var trigger = e.target.closest && e.target.closest(".gw-menu-host > [aria-haspopup][aria-controls]");
    if (trigger && (e.key === "ArrowDown" || e.key === "ArrowUp") && trigger.getAttribute("aria-haspopup") !== "dialog") {
      e.preventDefault(); openMenuFor(trigger, e.key === "ArrowUp"); return;
    }
    if (!openMenu) return;
    if (e.key === "Escape") { e.preventDefault(); closeMenu(true); return; }
    if (e.key === "Tab" && openMenu.trigger.getAttribute("aria-haspopup") !== "dialog") { closeMenu(false); return; }
    if (!openMenu.panel.contains(e.target)) return;
    var items = menuItems(openMenu.panel);
    var i = items.indexOf(doc.activeElement);
    var next = null;
    if (e.key === "ArrowDown") next = items[(i + 1) % items.length];
    if (e.key === "ArrowUp") next = items[(i - 1 + items.length) % items.length];
    if (e.key === "Home") next = items[0];
    if (e.key === "End") next = items[items.length - 1];
    if (next) { e.preventDefault(); next.focus(); }
  });
  doc.addEventListener("focusout", function (e) {
    if (!openMenu || openMenu.trigger.getAttribute("aria-haspopup") !== "dialog") return;
    setTimeout(function () {
      if (openMenu && !openMenu.panel.contains(doc.activeElement) && doc.activeElement !== openMenu.trigger) closeMenu(false);
    }, 0);
  });

  // ───────────────────────── tabs ─────────────────────────
  function selectTab(tab, focus) {
    var list = tab.closest('[role="tablist"]');
    list.querySelectorAll('[role="tab"]').forEach(function (t) {
      var on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      var panel = doc.getElementById(t.getAttribute("aria-controls"));
      if (panel) panel.hidden = !on;
    });
    if (focus) tab.focus();
  }
  doc.addEventListener("click", function (e) {
    var tab = e.target.closest('[role="tab"]');
    if (tab && tab.getAttribute("aria-controls") && tab.getAttribute("aria-disabled") !== "true") selectTab(tab, false);
  });
  doc.addEventListener("keydown", function (e) {
    var tab = e.target.closest && e.target.closest('[role="tab"]');
    if (!tab) return;
    var tabs = Array.prototype.slice.call(tab.closest('[role="tablist"]').querySelectorAll('[role="tab"]:not([disabled]):not([aria-disabled="true"])'));
    var i = tabs.indexOf(tab);
    var vertical = tab.closest('[role="tablist"]').getAttribute("aria-orientation") === "vertical";
    var nextKey = vertical ? "ArrowDown" : "ArrowRight";
    var prevKey = vertical ? "ArrowUp" : "ArrowLeft";
    var next = null;
    if (e.key === nextKey) next = tabs[(i + 1) % tabs.length];
    if (e.key === prevKey) next = tabs[(i - 1 + tabs.length) % tabs.length];
    if (e.key === "Home") next = tabs[0];
    if (e.key === "End") next = tabs[tabs.length - 1];
    if (next) { e.preventDefault(); selectTab(next, true); }
  });
  // Deep links: #<id> of a tab, a tabpanel or anything inside a hidden tabpanel
  // selects that tab first, then scrolls the target into view.
  function tabFromHash() {
    var id = decodeURIComponent(location.hash.slice(1));
    if (!id || !/^[A-Za-z][\w:.-]*$/.test(id)) return;
    var target = doc.getElementById(id);
    if (!target) return;
    // every tabpanel around the target, innermost first, gets its tab selected
    for (var panel = target.closest('[role="tabpanel"]'); panel; panel = panel.parentElement && panel.parentElement.closest('[role="tabpanel"]')) {
      var owner = doc.querySelector('[role="tab"][aria-controls="' + panel.id + '"]');
      if (owner) selectTab(owner, false);
    }
    if (target.getAttribute("role") === "tab") selectTab(target, false);
    target.scrollIntoView({ block: "start" });
  }
  window.addEventListener("hashchange", tabFromHash);

  function initTabs() {
    doc.querySelectorAll('[role="tablist"]').forEach(function (list) {
      var sel = list.querySelector('[role="tab"][aria-selected="true"]') || list.querySelector('[role="tab"]');
      if (sel && sel.getAttribute("aria-controls")) selectTab(sel, false);
    });
  }

  // ───────────────────────── dialogs & drawers ─────────────────────────
  // Where focus goes back to when a dialog closes: the opener, or the trigger of
  // the menu the opener sat in (that item is hidden once the menu closes).
  function returnTarget(opener) {
    if (openMenu && openMenu.panel.contains(opener)) return openMenu.trigger;
    return opener;
  }
  doc.addEventListener("close", function (e) {
    var d = e.target;
    if (!d || d.tagName !== "DIALOG") return;
    var back = d._gwReturn;
    d._gwReturn = null;
    if (!back || !back.isConnected || typeof back.focus !== "function") return;
    var now = doc.activeElement;
    if (!now || now === doc.body || d.contains(now)) back.focus();
  }, true);
  doc.addEventListener("click", function (e) {
    var opener = e.target.closest("[data-gw-dialog-open]");
    if (opener) {
      var dlg = doc.getElementById(opener.getAttribute("data-gw-dialog-open"));
      if (dlg && typeof dlg.showModal === "function" && !dlg.open) {
        dlg._gwReturn = returnTarget(opener);
        closeMenu(false);
        dlg.showModal();
        icons(dlg);
      }
      return;
    }
    var closer = e.target.closest("[data-gw-dialog-close]");
    if (closer) { var d = closer.closest("dialog"); if (d) d.close(closer.value || ""); return; }
    // backdrop click: the event target is the <dialog> itself
    if (e.target.tagName === "DIALOG" && e.target.open && !e.target.hasAttribute("data-gw-static")) {
      var r = e.target.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) e.target.close("");
    }
  });

  // ───────────────────────── disclosure ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("button[data-gw-toggle][aria-controls]");
    if (!btn) return;
    var target = doc.getElementById(btn.getAttribute("aria-controls"));
    if (!target) return;
    var open = btn.getAttribute("aria-expanded") !== "true";
    btn.setAttribute("aria-expanded", String(open));
    target.hidden = !open;
    var key = btn.getAttribute("data-gw-toggle-persist");
    if (key) { try { localStorage.setItem("gw-toggle-" + key, open ? "open" : "closed"); } catch (err) { /* ignore */ } }
  });
  // "View raw": open a disclosure from elsewhere and put the keyboard inside it.
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-expand]");
    if (!btn) return;
    var target = doc.querySelector(btn.getAttribute("data-gw-expand"));
    if (!target) return;
    e.preventDefault();
    if (target.id) {
      doc.querySelectorAll('[data-gw-toggle][aria-controls="' + target.id + '"]').forEach(function (t) { t.setAttribute("aria-expanded", "true"); });
    }
    target.hidden = false;
    var into = target.querySelector('[tabindex="0"]');
    if (!into) {
      into = target;
      if (!target.hasAttribute("tabindex")) target.setAttribute("tabindex", "-1");
    }
    into.focus();
  });
  function initToggles() {
    doc.querySelectorAll("button[data-gw-toggle][data-gw-toggle-persist][aria-controls]").forEach(function (btn) {
      var v = null;
      try { v = localStorage.getItem("gw-toggle-" + btn.getAttribute("data-gw-toggle-persist")); } catch (err) { /* ignore */ }
      var target = doc.getElementById(btn.getAttribute("aria-controls"));
      if (!v || !target) return;
      btn.setAttribute("aria-expanded", String(v === "open"));
      target.hidden = v !== "open";
    });
  }

  // ───────────────────────── copy ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-copy],[data-gw-copy-from]");
    if (!btn) return;
    var text = btn.getAttribute("data-gw-copy");
    if (text === null) {
      var src = doc.querySelector(btn.getAttribute("data-gw-copy-from"));
      text = src ? (src.value !== undefined && src.tagName !== "DIV" && src.tagName !== "PRE" && src.tagName !== "CODE" ? src.value : src.textContent) : "";
    }
    var done = function () {
      btn.setAttribute("data-copied", "");
      if (!btn.hasAttribute("data-gw-toast")) announce("Copied to clipboard"); // the toast says it, more precisely
      clearTimeout(btn._gwCopyTimer);
      btn._gwCopyTimer = setTimeout(function () { btn.removeAttribute("data-copied"); }, 1600);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, done);
    else done();
  });

  // ───────────────────────── toasts ─────────────────────────
  function toastRegion() {
    var host = topLayer();
    var region = host.querySelector(":scope > .gw-toast-region");
    if (!region) {
      region = doc.createElement("section");
      region.className = "gw-toast-region";
      region.setAttribute("aria-label", "Notifications");
      region.setAttribute("aria-live", "polite");
      region.setAttribute("aria-relevant", "additions");
      host.appendChild(region);
    }
    return region;
  }
  function toast(opts) {
    opts = opts || {};
    var tone = opts.tone || "neutral";
    var iconName = opts.icon || { success: "circle-check", warning: "triangle-alert", danger: "circle-x", info: "info", neutral: "bell", brand: "bell" }[tone] || "bell";
    var el = doc.createElement("div");
    el.className = "gw-toast gw-toast--" + tone;
    if (tone === "danger") el.setAttribute("role", "alert"); // the region is polite; failures interrupt once
    el.innerHTML =
      '<i class="gw-icon gw-toast__icon" data-lucide="' + iconName + '"></i>' +
      '<div class="gw-toast__content">' +
      (opts.title ? '<p class="gw-toast__title"></p>' : "") +
      (opts.body ? '<p class="gw-toast__body"></p>' : "") +
      (opts.actions && opts.actions.length ? '<div class="gw-toast__actions"></div>' : "") +
      "</div>" +
      '<button class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-toast__close" type="button" aria-label="Dismiss notification"><i class="gw-icon gw-icon--sm" data-lucide="x"></i></button>';
    if (opts.title) el.querySelector(".gw-toast__title").textContent = opts.title;
    if (opts.body) el.querySelector(".gw-toast__body").textContent = opts.body;
    (opts.actions || []).forEach(function (a) {
      var b = doc.createElement("button");
      b.type = "button";
      b.className = "gw-btn gw-btn--sm";
      b.textContent = a.label;
      b.addEventListener("click", function () { if (a.onClick) a.onClick(); el.remove(); });
      el.querySelector(".gw-toast__actions").appendChild(b);
    });
    el.querySelector(".gw-toast__close").addEventListener("click", function () { el.remove(); });
    var region = toastRegion();
    while (region.children.length >= 3) region.firstElementChild.remove(); // cap the stack
    region.appendChild(el);
    icons(el);
    // Auto-dismiss pauses while the toast is hovered or focused (WCAG 2.2.1); toasts with actions stay.
    var timeout = opts.timeout === undefined ? (opts.actions && opts.actions.length ? 0 : 6000) : opts.timeout;
    if (timeout) {
      var remaining = timeout, started = Date.now(), timer = null;
      var start = function () { started = Date.now(); timer = setTimeout(function () { el.remove(); }, remaining); };
      var pause = function () { clearTimeout(timer); remaining -= Date.now() - started; };
      el.addEventListener("mouseenter", pause);
      el.addEventListener("mouseleave", start);
      el.addEventListener("focusin", pause);
      el.addEventListener("focusout", function (ev) { if (!el.contains(ev.relatedTarget)) start(); });
      start();
    }
    return el;
  }
  window.gwToast = toast;
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-toast]");
    if (!btn) return;
    toast({ title: btn.getAttribute("data-gw-toast-title"), body: btn.getAttribute("data-gw-toast"), tone: btn.getAttribute("data-gw-toast-tone") || "neutral", icon: btn.getAttribute("data-gw-toast-icon") || undefined });
  });

  // ───────────────────────── countdown ─────────────────────────
  // Urgent (≤ 60 s): the tone moves from warning to danger, by markup classes only.
  function urgentTone(el) {
    el.querySelectorAll(".gw-progress--warning").forEach(function (p) {
      p.classList.remove("gw-progress--warning");
      p.classList.add("gw-progress--danger");
    });
    el.querySelectorAll('[role="timer"]').forEach(function (line) {
      line.classList.add("gw-fg-danger");
      line.querySelectorAll(".gw-fg-warning").forEach(function (i) { i.classList.remove("gw-fg-warning"); });
    });
  }
  // An approval request whose countdown ran out was denied by the gateway: it can
  // no longer be approved, so the decision turns into its explained, inert form.
  var expiredAnnounced = {};
  function expireRequest(card, countdown) {
    card.setAttribute("data-state", "expired");
    var badge = card.querySelector(".gw-approval-request__status > .gw-badge");
    if (badge) {
      badge.className = "gw-badge gw-badge--neutral";
      badge.innerHTML = '<i class="gw-icon gw-badge__icon" data-lucide="timer-off"></i>Expired';
      icons(badge);
    }
    var caption = card.querySelector(".gw-approval-request__decision-row > p");
    if (caption) {
      caption.textContent = "Expired: denied automatically. Nothing ran, and the agent was told the request expired.";
      if (!caption.id) caption.id = uid("gw-expired");
      card.querySelectorAll(".gw-approval-request__buttons .gw-btn").forEach(function (b) {
        b.setAttribute("aria-disabled", "true");
        b.removeAttribute("data-gw-enable-when"); // a comment typed now must not bring Approve back
        b.setAttribute("aria-describedby", caption.id); // the old reason ("Add a comment…") is gone
      });
    }
    card.querySelectorAll(".gw-approval-request__decision textarea").forEach(function (t) { t.readOnly = true; });
    var title = card.querySelector(".gw-approval-request__title");
    var codes = title ? Array.prototype.map.call(title.querySelectorAll("code"), function (c) { return c.textContent.trim(); }) : [];
    var what = codes.slice(0, 2).join(" on ");
    // the queue row and the detail of ONE request (same tool, connection and timer) announce once
    var key = what + "|" + countdown.getAttribute("data-gw-countdown") + "|" + countdown.getAttribute("data-gw-countdown-total");
    if (what && !expiredAnnounced[key]) {
      expiredAnnounced[key] = true;
      announce(what + " expired, denied automatically");
    }
  }
  function expireCountdown(el) {
    el.setAttribute("data-expired", "");
    var card = el.closest(".gw-approval-request");
    var line = el.querySelector('[role="timer"]');
    if (line) {
      var icon = doc.createElement("i");
      icon.className = "gw-icon gw-icon--sm";
      icon.setAttribute("data-lucide", "timer-off");
      line.textContent = "";
      line.appendChild(icon);
      line.appendChild(doc.createTextNode(el.getAttribute("data-gw-countdown-expired") || (card ? "Expired, denied automatically" : "Expired")));
      line.classList.remove("gw-fg-danger");
      line.removeAttribute("role"); // no longer a running timer
      icons(line);
    }
    el.querySelectorAll(".gw-progress").forEach(function (p) { p.hidden = true; });
    var state = card && (card.getAttribute("data-state") || "pending");
    if (state === "pending" || state === "partial") expireRequest(card, el);
    el.dispatchEvent(new CustomEvent("gw-countdown-end", { bubbles: true }));
  }
  function initCountdowns() {
    doc.querySelectorAll("[data-gw-countdown]").forEach(function (el) {
      var total = Number(el.getAttribute("data-gw-countdown-total") || el.getAttribute("data-gw-countdown"));
      var left = Number(el.getAttribute("data-gw-countdown"));
      var text = el.querySelector("[data-gw-countdown-text]") || el;
      function render() {
        var m = Math.floor(left / 60), s = left % 60;
        text.textContent = m + ":" + (s < 10 ? "0" : "") + s;
        el.style.setProperty("--gw-countdown", String(total ? left / total : 0));
        if (left <= 60 && !el.hasAttribute("data-urgent")) {
          el.setAttribute("data-urgent", "");
          urgentTone(el);
          if (left > 0 && el.hasAttribute("data-gw-countdown-announce")) announce(el.getAttribute("data-gw-countdown-announce"), { assertive: true });
        }
      }
      render();
      if (left <= 0) { expireCountdown(el); return; }
      var t = setInterval(function () {
        left = Math.max(0, left - 1);
        render();
        if (left === 0) { clearInterval(t); expireCountdown(el); }
      }, 1000);
    });
  }

  // ───────────────────────── table sort & selection ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("th[aria-sort] .gw-table__sort");
    if (btn) {
      var th = btn.closest("th");
      var next = th.getAttribute("aria-sort") === "ascending" ? "descending" : "ascending";
      th.closest("tr").querySelectorAll("th[aria-sort]").forEach(function (h) { h.setAttribute("aria-sort", "none"); });
      th.setAttribute("aria-sort", next);
      announce("Sorted by " + btn.textContent.trim() + ", " + next);
      return;
    }
  });
  doc.addEventListener("change", function (e) {
    var all = e.target.closest("input[data-gw-select-all]");
    var table = e.target.closest("table");
    if (all && table) {
      table.querySelectorAll("input[data-gw-select-row]").forEach(function (c) { c.checked = all.checked; c.closest("tr").setAttribute("aria-selected", String(all.checked)); });
      all.indeterminate = false;
      return;
    }
    var row = e.target.closest("input[data-gw-select-row]");
    if (row && table) {
      row.closest("tr").setAttribute("aria-selected", String(row.checked));
      var boxes = table.querySelectorAll("input[data-gw-select-row]");
      var checked = table.querySelectorAll("input[data-gw-select-row]:checked").length;
      var head = table.querySelector("input[data-gw-select-all]");
      if (head) { head.checked = checked === boxes.length; head.indeterminate = checked > 0 && checked < boxes.length; }
    }
  });

  // ───────────────────────── theme controls ─────────────────────────
  function initTheme() {
    if (!window.gwTheme) return;
    var s = window.gwTheme.get();
    doc.querySelectorAll('input[name="gw-theme"]').forEach(function (r) { r.checked = r.value === s.theme; });
    doc.querySelectorAll('input[name="gw-contrast"]').forEach(function (r) { r.checked = r.value === s.contrast; });
  }
  window.addEventListener("gw-theme-change", initTheme); // every appearance control on the page stays in sync
  doc.addEventListener("change", function (e) {
    if (!window.gwTheme) return;
    if (e.target.name === "gw-theme") window.gwTheme.set({ theme: e.target.value });
    if (e.target.name === "gw-contrast") window.gwTheme.set({ contrast: e.target.value });
  });

  // ───────────────────────── sidebar ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-sidebar-toggle]");
    if (!btn) return;
    var shell = btn.closest(".gw-shell");
    if (!shell) return;
    setSidebar(shell, btn, !shell.hasAttribute("data-collapsed"));
    // Only the real app shell (the one holding nav[data-gw-nav]) remembers the choice; demos don't.
    if (shell.querySelector("[data-gw-nav]")) {
      try { localStorage.setItem("gw-sidebar-collapsed", shell.hasAttribute("data-collapsed") ? "1" : ""); } catch (err) { /* ignore */ }
    }
  });
  function setSidebar(shell, btn, collapsed) {
    shell.toggleAttribute("data-collapsed", collapsed);
    btn.setAttribute("aria-expanded", String(!collapsed));
    btn.setAttribute("aria-label", collapsed ? "Expand sidebar" : "Collapse sidebar");
  }
  function initSidebar() {
    var stored = false;
    try { stored = localStorage.getItem("gw-sidebar-collapsed") === "1"; } catch (err) { /* ignore */ }
    doc.querySelectorAll(".gw-shell").forEach(function (shell) {
      var btn = shell.querySelector("[data-gw-sidebar-toggle]");
      if (!btn) return;
      var persisted = Boolean(shell.querySelector("[data-gw-nav]"));
      setSidebar(shell, btn, persisted ? stored : shell.hasAttribute("data-collapsed"));
    });
  }

  // ───────────────────────── tooltip: Esc dismisses ─────────────────────────
  doc.addEventListener("keydown", function (e) {
    if (e.key !== "Escape" || openMenu) return;
    var tips = doc.querySelectorAll(".gw-tooltip:hover, .gw-tooltip:has(:focus-visible)");
    tips.forEach(function (t) { t.setAttribute("data-dismissed", ""); });
  });
  function clearTip(e) { var t = e.target.closest && e.target.closest(".gw-tooltip[data-dismissed]"); if (t) t.removeAttribute("data-dismissed"); }
  doc.addEventListener("mouseleave", clearTip, true);
  doc.addEventListener("focusout", clearTip);

  // ───────────────────────── search clear ─────────────────────────
  doc.addEventListener("click", function (e) {
    var clear = e.target.closest(".gw-search__clear");
    if (!clear) return;
    var input = clear.closest(".gw-search").querySelector(".gw-search__input");
    if (!input) return;
    input.value = "";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  });

  // ───────────────────────── reveal a NEW secret while typing ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-reveal][aria-controls]");
    if (!btn) return;
    var input = doc.getElementById(btn.getAttribute("aria-controls"));
    if (!input) return;
    var show = btn.getAttribute("aria-pressed") !== "true";
    btn.setAttribute("aria-pressed", String(show));
    input.type = show ? "text" : "password";
  });

  // ───────────────────────── file drop ─────────────────────────
  ["dragenter", "dragover"].forEach(function (type) {
    doc.addEventListener(type, function (e) {
      var zone = e.target.closest && e.target.closest(".gw-file-drop");
      if (!zone) return;
      e.preventDefault();
      zone.setAttribute("data-state", "dragover");
    });
  });
  doc.addEventListener("dragleave", function (e) {
    var zone = e.target.closest && e.target.closest(".gw-file-drop");
    if (zone && !zone.contains(e.relatedTarget)) zone.setAttribute("data-state", "idle");
  });
  doc.addEventListener("drop", function (e) {
    var zone = e.target.closest && e.target.closest(".gw-file-drop");
    if (!zone) return;
    e.preventDefault();
    var input = zone.querySelector("input[type=file]");
    if (input && e.dataTransfer && e.dataTransfer.files.length) { input.files = e.dataTransfer.files; input.dispatchEvent(new Event("change", { bubbles: true })); }
    else zone.setAttribute("data-state", "idle");
  });
  doc.addEventListener("change", function (e) {
    var input = e.target.closest && e.target.closest(".gw-file-drop input[type=file]");
    if (!input) return;
    var zone = input.closest(".gw-file-drop");
    var file = input.files && input.files[0];
    zone.setAttribute("data-state", file ? "uploaded" : "idle");
    var name = zone.querySelector("[data-gw-file-name]");
    if (name && file) name.textContent = file.name;
    if (file) announce(file.name + " selected");
  });

  // ───────────────────────── chart bars: one selected range ─────────────────────────
  doc.addEventListener("click", function (e) {
    var bar = e.target.closest(".gw-chart__bar[aria-pressed]");
    if (!bar) return;
    var chart = bar.closest(".gw-chart") || bar.parentElement;
    var on = bar.getAttribute("aria-pressed") !== "true";
    chart.querySelectorAll(".gw-chart__bar[aria-pressed]").forEach(function (b) { b.setAttribute("aria-pressed", "false"); });
    bar.setAttribute("aria-pressed", String(on));
    announce(on ? "Range selected: " + (bar.getAttribute("aria-label") || "") : "Range cleared");
  });

  // ───────────────────────── chart bars: roving focus ─────────────────────────
  // One tab stop per chart; arrows / Home / End move between bars.
  function initChartFocus() {
    doc.querySelectorAll(".gw-chart").forEach(function (chart) {
      var bars = Array.prototype.slice.call(chart.querySelectorAll(".gw-chart__bar[aria-pressed]"));
      if (!bars.length) return;
      var current = bars.filter(function (b) { return b.getAttribute("aria-pressed") === "true"; })[0] || bars[bars.length - 1];
      bars.forEach(function (b) { b.tabIndex = b === current ? 0 : -1; });
    });
  }
  doc.addEventListener("keydown", function (e) {
    var bar = e.target.closest && e.target.closest(".gw-chart__bar[aria-pressed]");
    if (!bar) return;
    var bars = Array.prototype.slice.call((bar.closest(".gw-chart") || bar.parentElement).querySelectorAll(".gw-chart__bar[aria-pressed]"));
    var i = bars.indexOf(bar), next = null;
    if (e.key === "ArrowRight" || e.key === "ArrowDown") next = bars[Math.min(i + 1, bars.length - 1)];
    if (e.key === "ArrowLeft" || e.key === "ArrowUp") next = bars[Math.max(i - 1, 0)];
    if (e.key === "Home") next = bars[0];
    if (e.key === "End") next = bars[bars.length - 1];
    if (next) { e.preventDefault(); bars.forEach(function (b) { b.tabIndex = b === next ? 0 : -1; }); next.focus(); }
  });

  // ───────────────────────── generic pressed toggle ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("button[data-gw-pressed][aria-pressed]");
    if (!btn) return;
    var on = btn.getAttribute("aria-pressed") !== "true";
    btn.setAttribute("aria-pressed", String(on));
    announce((btn.getAttribute("aria-label") || btn.textContent.trim()) + (on ? " on" : " off"));
  });

  // ───────────────────────── external search clear ─────────────────────────
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-gw-search-clear]");
    if (!btn) return;
    var input = doc.querySelector(btn.getAttribute("data-gw-search-clear"));
    if (!input) return;
    input.value = "";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  });

  // ───────────────────────── dialog steps and guard ─────────────────────────
  function openNext(from, nextId) {
    var next = doc.getElementById(nextId);
    var current = from.closest("dialog");
    if (!next || typeof next.showModal !== "function") return;
    // the chain hands its first opener on, so the last step returns focus there
    next._gwReturn = current ? current._gwReturn : returnTarget(from);
    if (current) { current._gwReturn = null; current.close("next"); }
    next.showModal();
    icons(next);
  }
  doc.addEventListener("submit", function (e) {
    var form = e.target;
    var nextId = form.getAttribute && form.getAttribute("data-gw-dialog-next");
    if (!nextId) return;
    e.preventDefault(); // only reached when the form is valid
    openNext(form, nextId);
  });
  doc.addEventListener("click", function (e) {
    var btn = e.target.closest("button[data-gw-dialog-next]");
    if (!btn || btn.type === "submit") return;
    var form = btn.closest("form");
    if (form && !form.reportValidity()) return;
    openNext(btn, btn.getAttribute("data-gw-dialog-next"));
  });
  doc.addEventListener("cancel", function (e) {
    var dlg = e.target;
    if (!dlg.hasAttribute || !dlg.hasAttribute("data-gw-dialog-guard")) return;
    var form = dlg.querySelector("form");
    if (form && !form.checkValidity()) { e.preventDefault(); form.reportValidity(); }
  }, true);
  doc.addEventListener("click", function (e) {
    // the backdrop close above must respect the guard too
    if (e.target.tagName === "DIALOG" && e.target.hasAttribute("data-gw-dialog-guard") && !e.target.hasAttribute("data-gw-static")) e.target.setAttribute("data-gw-static", "");
  }, true);

  // ───────────────────────── enable when checked ─────────────────────────
  // aria-disabled, not disabled: the button stays reachable and its aria-describedby
  // says what to check ("Required to close…"); the guard above keeps it inert.
  // A checkbox counts when checked; a text field (a required comment) when it
  // holds something other than white space.
  function conditionMet(src) {
    if (src.type === "checkbox" || src.type === "radio") return src.checked;
    return String(src.value || "").trim() !== "";
  }
  function syncEnableWhen() {
    doc.querySelectorAll("[data-gw-enable-when]").forEach(function (btn) {
      var src = doc.querySelector(btn.getAttribute("data-gw-enable-when"));
      if (!src) return;
      if (btn.disabled) btn.disabled = false; // the markup's no-script fallback
      if (conditionMet(src)) btn.removeAttribute("aria-disabled"); else btn.setAttribute("aria-disabled", "true");
    });
  }
  doc.addEventListener("change", function (e) { if (e.target.type === "checkbox") syncEnableWhen(); });
  doc.addEventListener("input", function (e) {
    var t = e.target;
    if (t && (t.tagName === "TEXTAREA" || t.tagName === "INPUT") && t.type !== "checkbox") syncEnableWhen();
  });

  // ───────────────────────── command palette (.gw-command-palette) ─────────────────────────
  // APG combobox + listbox: DOM focus stays in the field; the active option is
  // aria-activedescendant on the input, aria-selected + gw-menu__item--active on
  // the option. Previews (--preview) get the same behaviour and keep their
  // rendered state until someone types.
  function initPalette(root) {
    if (root._gwPalette) return;
    var input = root.querySelector('[role="combobox"]');
    var list = input && doc.getElementById(input.getAttribute("aria-controls"));
    if (!list) return;
    root._gwPalette = true;
    var results = root.querySelector(".gw-command-palette__results");
    var empty = root.querySelector("[data-cmdk-empty]");
    var query = root.querySelector("[data-cmdk-query]");
    var status = root.querySelector("[data-cmdk-status]");
    var options = Array.prototype.slice.call(list.querySelectorAll('[role="option"]'));
    var lastPointer = "";

    function shown() { return options.filter(function (o) { return !o.hidden; }); }
    function active() { return options.filter(function (o) { return o.getAttribute("aria-selected") === "true"; })[0] || null; }
    function setActive(option, scroll) {
      options.forEach(function (o) {
        var on = o === option;
        o.setAttribute("aria-selected", String(on));
        o.classList.toggle("gw-menu__item--active", on);
      });
      if (!option) { input.removeAttribute("aria-activedescendant"); return; }
      input.setAttribute("aria-activedescendant", option.id);
      if (scroll && results) {
        // keep it in view inside the results scroller only (never scroll the page)
        var r = results.getBoundingClientRect(), o = option.getBoundingClientRect();
        if (o.top < r.top) results.scrollTop -= r.top - o.top;
        else if (o.bottom > r.bottom) results.scrollTop += o.bottom - r.bottom;
      }
    }
    function filter() {
      var q = input.value.trim().toLowerCase();
      options.forEach(function (o) {
        var text = (o.textContent + " " + (o.getAttribute("data-keywords") || "")).replace(/\s+/g, " ").toLowerCase();
        o.hidden = q !== "" && text.indexOf(q) === -1;
      });
      list.querySelectorAll('[role="group"]').forEach(function (g) {
        g.hidden = !g.querySelector('[role="option"]:not([hidden])');
      });
      var vis = shown();
      list.hidden = vis.length === 0;
      input.setAttribute("aria-expanded", String(vis.length > 0));
      if (empty) empty.hidden = vis.length > 0;
      if (query) query.textContent = input.value.trim();
      setActive(vis[0] || null, false);
      if (results) results.scrollTop = 0;
      if (status) status.textContent = q === "" ? "" : vis.length === 0 ? "No results" : vis.length + (vis.length === 1 ? " result" : " results");
    }

    // the rendered state: the option the input points at is the active one
    setActive(doc.getElementById(input.getAttribute("aria-activedescendant") || "") || active(), false);

    input.addEventListener("input", filter);
    input.addEventListener("keydown", function (e) {
      if (e.isComposing) return;
      // Esc always closes the modal: a type="search" field would spend the first Esc on clearing.
      if (e.key === "Escape" && root.tagName === "DIALOG") { e.preventDefault(); root.close(""); return; }
      var vis = shown();
      if (!vis.length) return;
      var i = vis.indexOf(active());
      var next = null;
      if (e.key === "ArrowDown") next = vis[(i + 1) % vis.length];
      else if (e.key === "ArrowUp") next = vis[i <= 0 ? vis.length - 1 : i - 1];
      else if (e.key === "Home") next = vis[0];
      else if (e.key === "End") next = vis[vis.length - 1];
      else if (e.key === "Enter") {
        e.preventDefault();
        if (i > -1) vis[i].click(); // a link navigates; a button runs its kit.js action
        return;
      }
      if (next) { e.preventDefault(); setActive(next, true); }
    });
    // The pointer moves the active option too, but only when it really moves:
    // scrolling the list under a still pointer must not steal it from the keyboard.
    list.addEventListener("mousemove", function (e) {
      var where = e.clientX + "," + e.clientY;
      if (where === lastPointer) return;
      lastPointer = where;
      var option = e.target.closest('[role="option"]');
      if (option && option.getAttribute("aria-selected") !== "true") setActive(option, false);
    });
    root.addEventListener("click", function (e) {
      if (!e.target.closest("[data-cmdk-theme]") || !window.gwTheme) return;
      var now = window.gwTheme.get().resolved.theme;
      window.gwTheme.set({ theme: now === "dark" ? "light" : "dark" });
    });
    if (root.tagName === "DIALOG") root.addEventListener("close", function () { input.value = ""; filter(); });
  }

  // ⌘K / Ctrl+K: the shell's palette, from anywhere.
  doc.addEventListener("keydown", function (e) {
    if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey || e.repeat || !e.key || e.key.toLowerCase() !== "k") return;
    var palette = doc.getElementById("cmdk");
    if (!palette || typeof palette.showModal !== "function") return;
    if (palette.open) { e.preventDefault(); palette.close(""); return; }
    var other = doc.querySelector("dialog[open]");
    if (other) return; // never on top of another dialog's decision
    e.preventDefault();
    palette._gwReturn = returnTarget(doc.activeElement);
    closeMenu(false);
    palette.showModal();
    icons(palette);
  });

  // ───────────────────────── "/" focuses the page's search ─────────────────────────
  // WCAG 2.1.4: a single-character shortcut only acts when focus is not in a text
  // field, and the user can turn single-key shortcuts off (data-gw-single-keys="off").
  var NOT_TEXT = /^(checkbox|radio|button|submit|reset|range|color|file|image|hidden)$/;
  function isEditable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    var tag = el.tagName;
    if (tag === "TEXTAREA" || tag === "SELECT") return true;
    if (tag === "INPUT") return !NOT_TEXT.test(el.type);
    var role = el.getAttribute("role");
    return role === "textbox" || role === "searchbox" || role === "combobox";
  }
  function singleKeysOn() { return doc.documentElement.getAttribute("data-gw-single-keys") !== "off"; }
  doc.addEventListener("keydown", function (e) {
    if (e.key !== "/" || e.ctrlKey || e.metaKey || e.altKey || e.isComposing || e.defaultPrevented) return;
    if (!singleKeysOn() || openMenu || isEditable(e.target)) return;
    var scope = doc.querySelector("dialog[open]") || doc;
    var field = Array.prototype.filter.call(scope.querySelectorAll('[aria-keyshortcuts~="/"]'), function (el) {
      return !el.disabled && el.getAttribute("aria-disabled") !== "true" && el.getClientRects().length > 0 &&
        (scope !== doc || !el.closest("dialog:not([open])"));
    })[0];
    if (!field) return;
    e.preventDefault(); // the "/" is not typed into the field
    field.focus();
  });
  var SINGLE_KEYS = "gw-single-keys";
  function setSingleKeys(on) {
    if (on) doc.documentElement.removeAttribute("data-gw-single-keys");
    else doc.documentElement.setAttribute("data-gw-single-keys", "off");
    try { localStorage.setItem(SINGLE_KEYS, on ? "on" : "off"); } catch (err) { /* ignore */ }
  }
  doc.addEventListener("change", function (e) {
    var t = e.target;
    if (t && t.matches && t.matches("input[data-gw-single-keys-toggle]")) setSingleKeys(t.checked);
  });
  function initSingleKeys() {
    var v = null;
    try { v = localStorage.getItem(SINGLE_KEYS); } catch (err) { /* ignore */ }
    if (v === "off") doc.documentElement.setAttribute("data-gw-single-keys", "off");
    if (v === "on") doc.documentElement.removeAttribute("data-gw-single-keys");
    var on = singleKeysOn();
    doc.querySelectorAll("input[data-gw-single-keys-toggle]").forEach(function (t) { t.checked = on; });
  }

  // ───────────────────────── initial checkbox states ─────────────────────────
  function initCheckboxes() {
    doc.querySelectorAll("input[data-gw-indeterminate]").forEach(function (c) { c.indeterminate = true; });
    doc.querySelectorAll("table").forEach(function (table) {
      var head = table.querySelector("input[data-gw-select-all]");
      if (!head) return;
      var boxes = table.querySelectorAll("input[data-gw-select-row]");
      var checked = table.querySelectorAll("input[data-gw-select-row]:checked").length;
      head.checked = boxes.length > 0 && checked === boxes.length;
      head.indeterminate = checked > 0 && checked < boxes.length;
      table.querySelectorAll("input[data-gw-select-row]").forEach(function (c) { c.closest("tr").setAttribute("aria-selected", String(c.checked)); });
    });
  }

  // ───────────────────────── Vault reference demo ─────────────────────────
  // Fixtures contain complete reference names only. The application discovers accessible KV keys in the core.
  function initVaultPickers() {
    var closeActive = null;
    doc.querySelectorAll('[data-source="vault"]').forEach(function (root) {
      var ref = root.querySelector("[data-vault-reference]");
      if (!ref) return;
      var panel = root.querySelector("[data-vault-suggestions]");
      var status = root.querySelector("[data-vault-status]");
      var refresh = root.querySelector("[data-vault-refresh]");
      var spinner = root.querySelector("[data-vault-spinner]");
      var names = [], visible = [], active = -1, open = false, loaded = false, busy = false, request = 0;
      var source = root.closest("form");
      var fixture = ["kv/dev/db#password", "kv/dev/db#username", "kv/stage/db#password", "kv/stage/db#username", "secret/dev/api#token"];
      function isVisible() { return ref.getClientRects().length > 0; }
      function query() { return ref.value.trim().replace(/^vault:\/\//, "").replace(/\?version=[1-9][0-9]*/g, "").toLowerCase(); }
      function position() { if (open) placeMenu({ trigger: ref.closest(".gw-input-group"), panel: panel }); }
      function close() {
        open = false;
        active = -1;
        ref.setAttribute("aria-expanded", "false");
        ref.removeAttribute("aria-activedescendant");
        if (panel.hasAttribute("popover")) {
          try { panel.hidePopover(); } catch (err) { /* already hidden */ }
          panel.removeAttribute("popover");
        }
        panel.style.top = panel.style.left = panel.style.maxHeight = "";
        panel.classList.remove("gw-menu--up");
        panel.hidden = true;
        if (closeActive === close) closeActive = null;
      }
      function render() {
        var text = query();
        visible = names.filter(function (name) { return !text || name.toLowerCase().includes(text); });
        active = Math.min(active, visible.length - 1);
        panel.replaceChildren();
        visible.forEach(function (name, i) {
          var option = doc.createElement("button");
          option.type = "button";
          option.className = "gw-menu__item";
          option.id = panel.id + "-" + i;
          option.setAttribute("role", "option");
          option.setAttribute("aria-selected", String(i === active));
          option.classList.toggle("gw-menu__item--active", i === active);
          option.tabIndex = -1;
          var label = doc.createElement("span");
          label.className = "gw-menu__label gw-mono";
          label.textContent = name;
          option.appendChild(label);
          option.addEventListener("mousedown", function (event) { event.preventDefault(); });
          option.addEventListener("click", function () {
            var currentPath = ref.value.replace(/^vault:\/\//, "").split(/[?#]/)[0];
            var version = ref.value.match(/\?version=([1-9][0-9]*)/);
            ref.value = version && currentPath === name.split("#")[0] ? name.replace("#", "?version=" + version[1] + "#") : name;
            ref.dispatchEvent(new Event("input", { bubbles: true }));
            ref.dispatchEvent(new Event("change", { bubbles: true }));
            close();
            ref.focus();
          });
          panel.appendChild(option);
        });
        if (!visible.length) {
          var empty = doc.createElement("p");
          empty.className = "gw-menu__note";
          empty.textContent = busy ? "Loading Vault paths and keys…" : names.length ? "No matching references. Keep typing or enter a reference manually." : "No references available. You can enter a reference manually.";
          panel.appendChild(empty);
        }
        if (active >= 0) ref.setAttribute("aria-activedescendant", panel.id + "-" + active);
        else ref.removeAttribute("aria-activedescendant");
        position();
      }
      function show() {
        if (!isVisible()) return;
        if (closeActive && closeActive !== close) closeActive();
        open = true;
        closeActive = close;
        panel.hidden = false;
        ref.setAttribute("aria-expanded", "true");
        if (canPopover && !panel.hasAttribute("popover")) {
          panel.setAttribute("popover", "manual");
          try { panel.showPopover(); } catch (err) { panel.removeAttribute("popover"); }
        }
        render();
      }
      function load() {
        if (busy || !isVisible()) return;
        var current = ++request;
        busy = true;
        names = [];
        loaded = false;
        spinner.hidden = false;
        refresh.setAttribute("aria-busy", "true");
        refresh.setAttribute("aria-disabled", "true");
        status.textContent = "Loading Vault paths and keys…";
        if (open) render();
        window.setTimeout(function () {
          if (request !== current || !isVisible()) return;
          busy = false;
          loaded = true;
          spinner.hidden = true;
          refresh.removeAttribute("aria-busy");
          refresh.removeAttribute("aria-disabled");
          var state = root.getAttribute("data-vault-demo-state");
          names = state === "denied" || state === "empty" ? [] : fixture.slice();
          status.textContent = state === "denied" ? "Vault denied listing paths. Grant list access to the KV metadata path, then retry. Manual references still work."
            : state === "empty" ? "No accessible Vault keys were found. You can enter a reference manually."
            : state === "partial" ? "Some paths could not be loaded. Available references are listed; check Vault list and read permissions."
            : names.length + " references available. Type to filter paths and keys; secret values are never shown.";
          refresh.lastChild.textContent = state === "denied" ? "Retry" : "Refresh list";
          if (open) render();
        }, 250);
      }
      function selectedSource() {
        if (isVisible()) { if (!loaded && !busy) load(); }
        else {
          close();
          if (busy) { request++; busy = false; spinner.hidden = true; refresh.removeAttribute("aria-busy"); refresh.removeAttribute("aria-disabled"); }
        }
      }
      ref.addEventListener("input", function () {
        ref.value = ref.value.replace(/^vault:\/\//, "");
        active = -1;
        if (!loaded && !busy) load();
        show();
      });
      ref.addEventListener("click", function () { if (!loaded && !busy) load(); show(); });
      ref.addEventListener("keydown", function (event) {
        if (event.isComposing) return;
        if (event.key === "Escape") { if (open) { event.preventDefault(); event.stopPropagation(); close(); } return; }
        if (event.key === "Tab") { close(); return; }
        if (event.key === "Enter" && open) {
          event.preventDefault(); event.stopPropagation();
          if (active >= 0) panel.children[active].click();
          return;
        }
        if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
        event.preventDefault(); event.stopPropagation();
        if (!open) { if (!loaded && !busy) load(); show(); }
        if (!visible.length) return;
        active = event.key === "ArrowDown" ? (active + 1) % visible.length : active <= 0 ? visible.length - 1 : active - 1;
        render();
        panel.children[active].scrollIntoView({ block: "nearest" });
      });
      ref.addEventListener("blur", function () { window.setTimeout(function () { if (!panel.contains(doc.activeElement) && doc.activeElement !== ref) close(); }, 0); });
      refresh.addEventListener("click", load);
      doc.addEventListener("click", function (event) { if (open && event.target !== ref && !panel.contains(event.target)) close(); });
      if (source) source.addEventListener("change", selectedSource);
      window.addEventListener("resize", position);
      window.addEventListener("scroll", function (event) { if (!panel.contains(event.target)) position(); }, true);
      selectedSource();
    });
  }

  // ───────────────────────── boot ─────────────────────────
  function boot() {
    doc.body.appendChild(live);
    icons();
    initTabs();
    initCountdowns();
    initTheme();
    initSidebar();
    initCheckboxes();
    initToggles();
    initChartFocus();
    syncEnableWhen();
    doc.querySelectorAll(".gw-command-palette").forEach(initPalette);
    initSingleKeys();
    initVaultPickers();
    tabFromHash();
  }
  if (doc.readyState === "loading") doc.addEventListener("DOMContentLoaded", boot); else boot();
})();
