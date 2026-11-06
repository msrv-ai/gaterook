import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';

const source = await readFile(new URL('../design/assets/kit.js', import.meta.url), 'utf8');

function harness(t) {
  const dom = new JSDOM(`<!doctype html><html><body>
    <button id="open-first" data-gw-dialog-open="first">Delete connection</button>
    <button id="open-second" data-gw-dialog-open="second">Another decision</button>
    <dialog id="first"><button autofocus>Cancel</button><button>Delete</button></dialog>
    <dialog id="second"><button autofocus>Cancel</button></dialog>
  </body></html>`, { url: 'https://example.test', runScripts: 'outside-only' });
  const win = dom.window;
  const doc = win.document;
  // jsdom has no native modal top layer; the production script still owns opening order.
  const query = doc.querySelector.bind(doc);
  doc.querySelector = (selector) => query(selector === 'dialog[open]:modal' ? 'dialog[open]' : selector);
  win.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  win.HTMLDialogElement.prototype.close = function () {
    this.open = false;
    this.dispatchEvent(new win.Event('close'));
  };
  let now = 0, serial = 0;
  const timers = new Map();
  win.Date.now = () => now;
  win.setTimeout = (callback, delay = 0) => { const id = ++serial; timers.set(id, { callback, at: now + delay }); return id; };
  win.clearTimeout = (id) => timers.delete(id);
  win.eval(source);
  t.after(() => win.close());
  return {
    win, doc,
    open: (id) => doc.getElementById(`open-${id}`).click(),
    close: (id) => doc.getElementById(id).close(),
    visible: () => [...doc.querySelectorAll('.gw-toast:not([hidden]) .gw-toast__title')].map((el) => el.textContent),
    advance(ms) {
      const target = now + ms;
      while (true) {
        const next = [...timers].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at;
        timers.delete(next[0]);
        next[1].callback();
      }
      now = target;
    },
  };
}

test('previous deletion notices never cover a fresh confirmation, including the same dialog reopened', (t) => {
  const h = harness(t);
  h.win.gwToast({ title: 'Previous connection deleted', tone: 'success' });
  h.open('first');
  assert.deepEqual(h.visible(), []);
  h.win.gwToast({ title: 'Delete failed', tone: 'danger' });
  assert.deepEqual(h.visible(), ['Delete failed']);
  assert.equal(h.doc.querySelector('.gw-toast:not([hidden])').getAttribute('role'), 'alert');
  h.close('first');
  h.win.gwShowDialog(h.doc.getElementById('first'));
  assert.deepEqual(h.visible(), []);
  h.win.gwToast({ title: 'Current delete failed', tone: 'danger' });
  assert.deepEqual(h.visible(), ['Current delete failed']);
});

test('nested dialogs preserve their own feedback and persistent actions return after all dialogs close', (t) => {
  const h = harness(t);
  let undone = 0;
  h.win.gwToast({ title: 'Changed', actions: [{ label: 'Undo', onClick: () => undone++ }] });
  h.open('first');
  h.win.gwToast({ title: 'First dialog feedback' });
  h.open('second');
  h.win.gwToast({ title: 'Second dialog feedback' });
  assert.deepEqual(h.visible(), ['Second dialog feedback']);
  h.close('second');
  assert.deepEqual(h.visible(), ['First dialog feedback']);
  h.advance(120000);
  assert.deepEqual(h.visible(), []);
  h.close('first');
  assert.deepEqual(h.visible(), ['Changed']);
  assert.equal(h.doc.querySelector('.gw-toast-region').parentNode, h.doc.body);
  h.doc.querySelector('.gw-toast__actions button').click();
  assert.equal(undone, 1);
  assert.deepEqual(h.visible(), []);
});

test('hidden notifications finish their remaining lifetime even if hover or focus leave events were suppressed', (t) => {
  const h = harness(t);
  const toast = h.win.gwToast({ title: 'Deleted', tone: 'success' });
  h.advance(5000);
  toast.dispatchEvent(new h.win.MouseEvent('mouseenter'));
  toast.dispatchEvent(new h.win.FocusEvent('focusin', { bubbles: true }));
  h.advance(20000);
  assert.equal(toast.isConnected, true);
  h.open('first');
  h.advance(9999);
  assert.equal(toast.isConnected, true);
  h.advance(1);
  assert.equal(toast.isConnected, false);
  h.close('first');
  assert.deepEqual(h.visible(), []);
});
