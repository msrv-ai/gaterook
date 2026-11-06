const PROTOCOL_VERSION = '2026-01-26';
const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const sessionId = value => typeof value === 'string' && /^ci_[a-z0-9]{12}$/.test(value);

/** Only a bounded count and opaque handle are rendered; connection details stay in GateRook. */
export function readConnectionImportCard(result) {
  const data = result?.structuredContent;
  if (!isRecord(result) || result.isError === true || !isRecord(data)
      || data.kind !== 'gaterook.connection-import-card' || data.version !== 1 || !sessionId(data.sessionId)
      || !['review', 'imported'].includes(data.status) || !Number.isInteger(data.count)
      || data.count < 0 || data.count > 50 || data.canOpen !== (data.status === 'review')
      || data.reviewUrl !== `gaterook://connection-import/${data.sessionId}`) {
    throw new Error('This import card is unavailable. Open GateRook to check the import.');
  }
  return { sessionId: data.sessionId, status: data.status, count: data.count, canOpen: data.canOpen };
}

/** Parent-origin checks protect the transport; the server separately enforces endpoint and caller ownership. */
export function mountConnectionImportCard(win) {
  const doc = win.document;
  const byId = id => doc.getElementById(id);
  const button = byId('open-import');
  const parent = win.parent;
  const pending = new Map();
  let sequence = 0;
  let origin;
  let ready = false;
  let serverTools = false;
  let destroyed = false;
  let card;
  let invocationId;
  let generation = 0;
  let operation;
  let observer;
  let resizeTimer;
  let lastSize;
  const send = message => {
    if (!destroyed) parent.postMessage(message, origin && origin !== 'null' ? origin : '*');
  };
  const notify = (method, params = {}) => send({ jsonrpc: '2.0', method, params });
  const error = message => {
    byId('error-message').textContent = message;
    byId('card-error').hidden = !message;
  };
  const updateButton = () => {
    button.disabled = !ready || !serverTools || !card?.canOpen || Boolean(operation) || destroyed;
    button.setAttribute('aria-busy', String(Boolean(operation)));
  };
  const request = (method, params, initializing = false) => new Promise((resolve, reject) => {
    if (destroyed || (!ready && !initializing)) return reject(new Error('The chat connection is unavailable. Open GateRook to continue.'));
    const id = ++sequence;
    const timeout = win.setTimeout(() => {
      pending.delete(id);
      reject(new Error('The chat did not respond. Open GateRook to review the import.'));
    }, 10_000);
    pending.set(id, { resolve, reject, timeout, initializing });
    send({ jsonrpc: '2.0', id, method, params });
  });
  const context = value => {
    if (isRecord(value) && ['light', 'dark'].includes(value.theme)) doc.documentElement.dataset.theme = value.theme;
  };
  const reset = message => {
    generation++;
    operation = undefined;
    card = undefined;
    byId('card-status').textContent = message;
    updateButton();
  };
  const accept = (result, expectedId) => {
    const next = readConnectionImportCard(result);
    if (expectedId && next.sessionId !== expectedId) throw new Error('The chat returned a different import. Open GateRook to continue.');
    card = next;
    byId('card-status').textContent = next.status === 'review'
      ? `${next.count} connection candidate${next.count === 1 ? '' : 's'} ready for review.`
      : 'The selected connections have been imported.';
    byId('card-explanation').textContent = next.status === 'review'
      ? 'Nothing has been imported. Choose connections and their destination endpoint in GateRook.'
      : 'Review the connections and their access in GateRook.';
    error(serverTools ? '' : 'This chat cannot open the import card. Use the GateRook link in the tool result.');
    updateButton();
  };
  const open = async () => {
    if (button.disabled || !card || operation || destroyed) return;
    const own = { generation, sessionId: card.sessionId };
    operation = own;
    updateButton();
    error('');
    try {
      const result = await request('tools/call', { name: 'open_connection_import', arguments: { sessionId: own.sessionId } });
      if (destroyed || generation !== own.generation || operation !== own) return;
      accept(result, own.sessionId);
      if (card.canOpen) byId('card-status').textContent = 'Opened this import in GateRook. Review and select connections there.';
    } catch (failure) {
      if (!destroyed && generation === own.generation && operation === own) error(failure.message);
    } finally {
      if (!destroyed && operation === own) {
        operation = undefined;
        updateButton();
      }
    }
  };
  const onMessage = event => {
    if (destroyed || event.source !== parent || (origin !== undefined && event.origin !== origin)) return;
    const message = event.data;
    if (!isRecord(message) || message.jsonrpc !== '2.0') return;
    if (Object.hasOwn(message, 'id') && !Object.hasOwn(message, 'method')) {
      const call = pending.get(message.id);
      if (!call || (!Object.hasOwn(message, 'result') && !Object.hasOwn(message, 'error'))) return;
      if (call.initializing) {
        if (message.result?.protocolVersion !== PROTOCOL_VERSION || !isRecord(message.result?.hostCapabilities)) return;
        origin = event.origin;
      }
      pending.delete(message.id);
      win.clearTimeout(call.timeout);
      if (message.error) call.reject(new Error('GateRook could not open this import. It may have expired or belong to another client.'));
      else call.resolve(message.result);
      return;
    }
    if (!ready || typeof message.method !== 'string') return;
    if (Object.hasOwn(message, 'id')) {
      if (typeof message.id !== 'string' && !Number.isSafeInteger(message.id)) return;
      if (message.method === 'ping' || message.method === 'ui/resource-teardown') {
        send({ jsonrpc: '2.0', id: message.id, result: {} });
        if (message.method === 'ui/resource-teardown') destroy();
      } else send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } });
    } else if (message.method === 'ui/notifications/tool-input') {
      invocationId = sessionId(message.params?.arguments?.sessionId) ? message.params.arguments.sessionId : undefined;
      reset('Loading this import…');
      error('');
    } else if (message.method === 'ui/notifications/tool-result') {
      try { accept(message.params, invocationId); }
      catch (failure) { reset('Import unavailable.'); error(failure.message); }
    } else if (message.method === 'ui/notifications/tool-cancelled') {
      reset('The request was cancelled. Open GateRook to check the import.');
      error('');
    } else if (message.method === 'ui/notifications/host-context-changed') context(message.params);
  };
  function destroy() {
    if (destroyed) return;
    destroyed = true;
    generation++;
    observer?.disconnect();
    win.clearTimeout(resizeTimer);
    for (const call of pending.values()) {
      win.clearTimeout(call.timeout);
      call.reject(new Error('The import card was closed.'));
    }
    pending.clear();
    win.removeEventListener('message', onMessage);
    win.removeEventListener('pagehide', destroy);
    button.removeEventListener('click', open);
    updateButton();
  }
  button.addEventListener('click', open);
  win.addEventListener('message', onMessage);
  win.addEventListener('pagehide', destroy);
  void request('ui/initialize', {
    appInfo: { name: 'GateRook connection import', version: '1.0.0' },
    appCapabilities: { availableDisplayModes: ['inline'] }, protocolVersion: PROTOCOL_VERSION,
  }, true).then(result => {
    if (destroyed) return;
    ready = true;
    serverTools = isRecord(result.hostCapabilities.serverTools);
    context(result.hostContext);
    notify('ui/notifications/initialized');
    byId('card-status').textContent = 'Waiting for this import…';
    if (!serverTools) error('This chat cannot open the import card. Use the GateRook link in the tool result.');
    if (typeof win.ResizeObserver === 'function') {
      observer = new win.ResizeObserver(() => {
        win.clearTimeout(resizeTimer);
        resizeTimer = win.setTimeout(() => {
          const { width, height } = byId('connection-import-card').getBoundingClientRect();
          const size = `${Math.ceil(width)}:${Math.ceil(height)}`;
          if (size !== lastSize) {
            lastSize = size;
            notify('ui/notifications/size-changed', { width: Math.ceil(width), height: Math.ceil(height) });
          }
        }, 50);
      });
      observer.observe(byId('connection-import-card'));
    }
    updateButton();
  }).catch(failure => {
    if (!destroyed) { reset('The chat connection is unavailable.'); error(failure.message); }
  });
  return { destroy };
}

if (typeof window !== 'undefined' && window.document.getElementById('connection-import-card')) mountConnectionImportCard(window);
