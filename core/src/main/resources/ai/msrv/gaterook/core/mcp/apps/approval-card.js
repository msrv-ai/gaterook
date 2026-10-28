const PROTOCOL_VERSION = '2026-01-26';
const REQUEST_TIMEOUT_MS = 10_000;
const POLL_INTERVAL_MS = 5_000;
const STATES = Object.freeze({
  pending: ['Waiting for approval', 'Not executed. Review this request in GateRook.', 'warning'],
  running: ['Running', 'Approved. Execution is in progress.', 'info'],
  completed: ['Completed', 'Execution completed. Open GateRook for details.', 'success'],
  rejected: ['Denied', 'The request was denied. Nothing ran.', 'danger'],
  expired: ['Expired', 'The approval deadline passed. Nothing ran.', 'neutral'],
  failed: ['Failed', 'Execution failed. Open GateRook for details.', 'danger'],
  cancelled: ['Cancelled', 'The request was cancelled. Nothing ran.', 'neutral'],
});
const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const isString = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const isLive = status => status === 'pending' || status === 'running';

/** Accept only the caller-safe, versioned projection; HTML and host-provided CSS are never evaluated. */
export function readApprovalCard(result) {
  const value = result?.structuredContent;
  if (!isRecord(result) || result.isError === true || !isRecord(value)
      || value.kind !== 'gaterook.approval-card' || value.version !== 1
      || !/^ap_[a-z0-9]{12}$/.test(value.handle ?? '')
      || !isString(value.tool, 512) || !isString(value.connection, 512)
      || !['dev', 'stage', 'prod'].includes(value.environment)
      || !Object.hasOwn(STATES, value.status)
      || !isString(value.expiresAt, 64) || !Number.isFinite(Date.parse(value.expiresAt))
      || !isString(value.serverTime, 64) || !Number.isFinite(Date.parse(value.serverTime))
      || !isRecord(value.request) || !['json', 'arguments', 'sql', 'http', 'sh'].includes(value.request.language)
      || typeof value.request.text !== 'string' || value.request.text.length > 12_000
      || (value.request.truncated !== undefined && typeof value.request.truncated !== 'boolean')
      || typeof value.commentRequired !== 'boolean' || typeof value.canDeny !== 'boolean'
      || typeof value.canOpen !== 'boolean' || value.approvalInApp !== true) {
    throw new Error('This approval card is unavailable. Open GateRook to check the request.');
  }
  return value;
}

/** A small MCP Apps transport: source/origin checks identify the host, not a human approver. */
export function mountApprovalCard(win) {
  const doc = win.document;
  const byId = id => doc.getElementById(id);
  const buttons = ['refresh', 'details', 'deny', 'approve'].map(name => byId(`${name}-button`));
  const pending = new Map();
  const parent = win.parent;
  let sequence = 0;
  let origin;
  let ready = false;
  let destroyed = false;
  let serverTools = false;
  let card;
  let invocationHandle;
  let receivedAt = 0;
  let generation = 0;
  let operation;
  let pollTimer;
  let clockTimer;
  let resizeTimer;
  let lastSize = '';
  let observer;
  let expiredRefresh = false;

  const send = message => {
    if (!destroyed) parent.postMessage(message, origin && origin !== 'null' ? origin : '*');
  };
  const notify = (method, params = {}) => send({ jsonrpc: '2.0', method, params });
  const request = (method, params, initializing = false) => new Promise((resolve, reject) => {
    if (destroyed || (!ready && !initializing)) {
      reject(new Error('The chat connection is unavailable. Reopen the card or use GateRook.'));
      return;
    }
    const id = ++sequence;
    const timeout = win.setTimeout(() => {
      pending.delete(id);
      reject(new Error('The chat did not respond. Refresh the request or open GateRook.'));
    }, REQUEST_TIMEOUT_MS);
    pending.set(id, { resolve, reject, timeout, initializing });
    send({ jsonrpc: '2.0', id, method, params });
  });
  const error = message => {
    byId('error-message').textContent = message;
    byId('card-error').hidden = !message;
  };
  const remaining = () => card ? Date.parse(card.expiresAt) - Date.parse(card.serverTime)
    - (win.performance.now() - receivedAt) : 0;
  const expiredLocally = () => card?.status === 'pending' && remaining() <= 0;
  const updateButtons = () => {
    const enabled = ready && serverTools && Boolean(card) && !operation && !destroyed;
    byId('refresh-button').disabled = !enabled;
    byId('details-button').disabled = !enabled || !card?.canOpen;
    byId('deny-button').disabled = !enabled || card?.status !== 'pending' || !card?.canDeny || expiredLocally();
    byId('approve-button').disabled = !enabled || card?.status !== 'pending' || !card?.canOpen || expiredLocally();
    byId('deny-button').hidden = Boolean(card && card.status !== 'pending');
    byId('approve-button').hidden = Boolean(card && card.status !== 'pending');
    for (const button of buttons) {
      button.setAttribute('aria-busy', String(Boolean(operation && operation.button === button.id)));
    }
  };
  const clearTimers = () => {
    win.clearTimeout(pollTimer);
    win.clearInterval(clockTimer);
    pollTimer = undefined;
    clockTimer = undefined;
  };
  const schedulePoll = () => {
    win.clearTimeout(pollTimer);
    pollTimer = undefined;
    if (ready && serverTools && !destroyed && !operation && card && isLive(card.status) && !doc.hidden) {
      pollTimer = win.setTimeout(() => interact('status'), POLL_INTERVAL_MS);
    }
  };
  const updateClock = () => {
    if (!card) return;
    byId('expiry-row').hidden = card.status !== 'pending';
    const milliseconds = remaining();
    if (milliseconds > 0) {
      const seconds = Math.ceil(milliseconds / 1000);
      byId('request-expiry').textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    } else {
      byId('request-expiry').textContent = 'Checking the deadline with GateRook…';
    }
    updateButtons();
    if (expiredLocally() && !expiredRefresh && !operation && !doc.hidden && serverTools) {
      expiredRefresh = true;
      void interact('status');
    }
  };
  const applyContext = context => {
    if (!isRecord(context)) return;
    if (context.theme === 'light' || context.theme === 'dark') doc.documentElement.dataset.theme = context.theme;
  };
  const accept = (result, expectedHandle) => {
    const next = readApprovalCard(result);
    if (expectedHandle && next.handle !== expectedHandle) throw new Error('The chat returned a different request. Open GateRook to continue.');
    if (card?.handle === next.handle) {
      if (Date.parse(next.serverTime) < Date.parse(card.serverTime)) return;
      if (!isLive(card.status) && isLive(next.status)) return;
      if (card.status === 'running' && next.status === 'pending') return;
    } else {
      generation++;
      operation = undefined;
      expiredRefresh = false;
    }
    card = next;
    receivedAt = win.performance.now();
    const [label, description, tone] = STATES[card.status];
    byId('status-description').textContent = description;
    byId('status-badge').textContent = label;
    byId('status-badge').className = `gw-badge gw-badge--${tone}`;
    byId('request-tool').textContent = card.tool;
    byId('request-connection').textContent = card.connection;
    byId('request-handle').textContent = card.handle;
    byId('request-environment').textContent = card.environment.toUpperCase();
    byId('request-environment').className = `gw-env gw-env--${card.environment}`;
    byId('request-language').textContent = card.request.language === 'arguments' ? 'JSON' : card.request.language.toUpperCase();
    byId('request-text').textContent = card.request.text;
    byId('request-truncated').hidden = !card.request.truncated;
    byId('comment-required').hidden = !card.commentRequired;
    byId('request-content').hidden = false;
    error(serverTools ? '' : 'This chat cannot use card actions. Open GateRook to review the request.');
    clearTimers();
    if (isLive(card.status) && !doc.hidden) clockTimer = win.setInterval(updateClock, 1000);
    updateClock();
    schedulePoll();
  };
  async function interact(action, button) {
    if (!ready || !serverTools || !card || operation || destroyed) return;
    if (action === 'deny' && (!card.canDeny || card.status !== 'pending' || expiredLocally())) return;
    if (action === 'open' && !card.canOpen) return;
    const own = { generation, handle: card.handle, button };
    operation = own;
    win.clearTimeout(pollTimer);
    updateButtons();
    error('');
    try {
      const result = await request('tools/call', { name: 'approval_card_action', arguments: { handle: own.handle, action } });
      if (destroyed || generation !== own.generation || operation !== own) return;
      if (result?.isError === true) throw new Error('GateRook could not complete this action. Refresh the request or open GateRook.');
      accept(result, own.handle);
    } catch (failure) {
      if (!destroyed && generation === own.generation && operation === own) error(failure.message);
    } finally {
      if (!destroyed && operation === own) {
        operation = undefined;
        updateButtons();
        schedulePoll();
      }
    }
  }
  const onVisibility = () => {
    clearTimers();
    if (!doc.hidden && card && isLive(card.status)) {
      clockTimer = win.setInterval(updateClock, 1000);
      updateClock();
      if (!operation) void interact('status');
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
      if (message.error) call.reject(new Error('The chat could not complete this action. Refresh the request or open GateRook.'));
      else call.resolve(message.result);
      return;
    }
    if (!ready || typeof message.method !== 'string') return;
    if (Object.hasOwn(message, 'id')) {
      if (typeof message.id !== 'string' && !Number.isSafeInteger(message.id)) return;
      if (message.method === 'ping' || message.method === 'ui/resource-teardown') {
        send({ jsonrpc: '2.0', id: message.id, result: {} });
        if (message.method === 'ui/resource-teardown') destroy();
      } else {
        send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } });
      }
      return;
    }
    if (message.method === 'ui/notifications/host-context-changed') {
      applyContext(message.params);
    } else if (message.method === 'ui/notifications/tool-result') {
      try { accept(message.params, invocationHandle); } catch (failure) {
        generation++;
        operation = undefined;
        card = undefined;
        clearTimers();
        byId('request-content').hidden = true;
        byId('status-description').textContent = 'Open GateRook to check the request.';
        byId('status-badge').textContent = 'Unavailable';
        byId('status-badge').className = 'gw-badge gw-badge--neutral';
        error(failure.message);
        updateButtons();
      }
    } else if (message.method === 'ui/notifications/tool-input') {
      // A new invocation invalidates outstanding responses before its result arrives.
      generation++;
      operation = undefined;
      card = undefined;
      invocationHandle = typeof message.params?.arguments?.handle === 'string' ? message.params.arguments.handle : undefined;
      clearTimers();
      byId('request-content').hidden = true;
      byId('status-description').textContent = 'Loading the request…';
      byId('status-badge').textContent = 'Loading';
      byId('status-badge').className = 'gw-badge gw-badge--neutral';
      error('');
      updateButtons();
    } else if (message.method === 'ui/notifications/tool-cancelled') {
      generation++;
      operation = undefined;
      card = undefined;
      clearTimers();
      byId('request-content').hidden = true;
      byId('status-description').textContent = 'The card request was cancelled. Open GateRook to check its status.';
      byId('status-badge').textContent = 'Unavailable';
      byId('status-badge').className = 'gw-badge gw-badge--neutral';
      error('');
      updateButtons();
    }
  };
  const handlers = buttons.map(button => {
    const action = button.id === 'deny-button' ? 'deny' : button.id === 'refresh-button' ? 'status' : 'open';
    const handler = () => { if (!button.disabled) void interact(action, button.id); };
    button.addEventListener('click', handler);
    return [button, handler];
  });
  function destroy() {
    if (destroyed) return;
    destroyed = true;
    generation++;
    clearTimers();
    win.clearTimeout(resizeTimer);
    observer?.disconnect();
    for (const call of pending.values()) {
      win.clearTimeout(call.timeout);
      call.reject(new Error('The approval card was closed.'));
    }
    pending.clear();
    win.removeEventListener('message', onMessage);
    win.removeEventListener('pagehide', destroy);
    doc.removeEventListener('visibilitychange', onVisibility);
    for (const [button, handler] of handlers) button.removeEventListener('click', handler);
    updateButtons();
  }
  win.addEventListener('message', onMessage);
  win.addEventListener('pagehide', destroy);
  doc.addEventListener('visibilitychange', onVisibility);
  void request('ui/initialize', {
    appInfo: { name: 'GateRook approval', version: '1.0.0' },
    appCapabilities: { availableDisplayModes: ['inline'] },
    protocolVersion: PROTOCOL_VERSION,
  }, true).then(result => {
    if (destroyed) return;
    ready = true;
    serverTools = isRecord(result.hostCapabilities.serverTools);
    applyContext(result.hostContext);
    notify('ui/notifications/initialized');
    byId('status-description').textContent = 'Waiting for the request…';
    byId('status-badge').textContent = 'Loading';
    if (!serverTools) error('This chat cannot use card actions. Open GateRook to review the request.');
    if (typeof win.ResizeObserver === 'function') {
      observer = new win.ResizeObserver(() => {
        win.clearTimeout(resizeTimer);
        resizeTimer = win.setTimeout(() => {
          const { width, height } = byId('approval-card').getBoundingClientRect();
          const size = `${Math.ceil(width)}:${Math.ceil(height)}`;
          if (size !== lastSize) {
            lastSize = size;
            notify('ui/notifications/size-changed', { width: Math.ceil(width), height: Math.ceil(height) });
          }
        }, 50);
      });
      observer.observe(byId('approval-card'));
    }
    updateButtons();
  }).catch(failure => {
    if (destroyed) return;
    byId('status-description').textContent = 'The chat connection is unavailable.';
    byId('status-badge').textContent = 'Unavailable';
    error(failure.message);
    updateButtons();
  });
  return { destroy };
}

if (typeof window !== 'undefined' && window.document.getElementById('approval-card')) mountApprovalCard(window);
