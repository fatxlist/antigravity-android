#!/usr/bin/env node
/**
 * web/tests/test_web_patches.mjs
 * Unit & behavioral verification for web/generated bundles:
 *  - patches/mobile_bridge.js (Share Sheet, Extra-Keys, Quick Controls)
 *  - bridge.core.js (https ?port= resolution, random CSRF fallback)
 *  - patches/touch_model_menu.js (MutationObserver submenu reaction)
 *  - patch_main.py (graceful degradation on upstream signature drift)
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const GEN_DIR = path.join(WEB_DIR, 'generated');

function createMockDom() {
  const listeners = new Map();
  let activeElement = null;
  const elementsById = new Map();
  const allElements = [];

  class MockEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.bubbles = !!init.bubbles;
      this.cancelable = !!init.cancelable;
      this.composed = !!init.composed;
      this.defaultPrevented = false;
      this.target = null;
      this.currentTarget = null;
      Object.assign(this, init);
    }
    preventDefault() {
      if (this.cancelable) this.defaultPrevented = true;
    }
    stopPropagation() {}
    stopImmediatePropagation() {}
  }

  class MockKeyboardEvent extends MockEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.key = init.key || '';
      this.code = init.code || '';
      this.keyCode = init.keyCode || 0;
      this.which = init.which || 0;
      this.ctrlKey = !!init.ctrlKey;
      this.shiftKey = !!init.shiftKey;
      this.altKey = !!init.altKey;
      this.metaKey = !!init.metaKey;
    }
  }

  class MockInputEvent extends MockEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.inputType = init.inputType || '';
      this.data = init.data ?? null;
    }
  }

  class MockElement {
    constructor(tagName) {
      this.nodeType = 1;
      this.tagName = tagName.toUpperCase();
      this.attributes = new Map();
      this.children = [];
      this.parentNode = null;
      this.style = {
        _props: new Map(),
        setProperty(k, v) { this._props.set(k, String(v)); },
        getPropertyValue(k) { return this._props.get(k) || ''; }
      };
      this._id = '';
      this._className = '';
      this._value = '';
      this._textContent = '';
      this._listeners = new Map();
      this.isConnected = false;
      this.disabled = false;
      this.readOnly = false;
      this.type = tagName.toUpperCase() === 'INPUT' ? 'text' : '';
      this.selectionStart = 0;
      this.selectionEnd = 0;
      this.dispatched = [];
      this.classList = {
        _set: new Set(),
        add: (...cls) => {
          cls.forEach((c) => this.classList._set.add(c));
          this._className = Array.from(this.classList._set).join(' ');
        },
        remove: (...cls) => {
          cls.forEach((c) => this.classList._set.delete(c));
          this._className = Array.from(this.classList._set).join(' ');
        },
        toggle: (c, force) => {
          const has = this.classList._set.has(c);
          const next = force === undefined ? !has : !!force;
          if (next) this.classList._set.add(c);
          else this.classList._set.delete(c);
          this._className = Array.from(this.classList._set).join(' ');
          return next;
        },
        contains: (c) => this.classList._set.has(c)
      };
    }
    get id() { return this._id; }
    set id(v) {
      if (this._id) elementsById.delete(this._id);
      this._id = String(v);
      if (this._id) elementsById.set(this._id, this);
    }
    get className() { return this._className; }
    set className(v) {
      this._className = String(v);
      this.classList._set = new Set(this._className.split(/\s+/).filter(Boolean));
    }
    get value() { return this._value; }
    set value(v) {
      this._value = String(v);
      this.selectionStart = this._value.length;
      this.selectionEnd = this._value.length;
    }
    get textContent() {
      if (this._textContent) return this._textContent;
      return this.children.map((c) => c.textContent || '').join(' ');
    }
    set textContent(v) { this._textContent = String(v); }
    get isContentEditable() { return this.getAttribute('contenteditable') === 'true'; }
    setAttribute(k, v) { this.attributes.set(k, String(v)); }
    getAttribute(k) { return this.attributes.has(k) ? this.attributes.get(k) : null; }
    hasAttribute(k) { return this.attributes.has(k); }
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      markConnected(child, true);
      if (!allElements.includes(child)) allElements.push(child);
      return child;
    }
    removeChild(child) {
      const idx = this.children.indexOf(child);
      if (idx >= 0) this.children.splice(idx, 1);
      child.parentNode = null;
      markConnected(child, false);
      if (child._id) elementsById.delete(child._id);
      return child;
    }
    contains(other) {
      let cur = other;
      while (cur) {
        if (cur === this) return true;
        cur = cur.parentNode;
      }
      return false;
    }
    closest(selector) {
      const parts = selector.split(',').map((s) => s.trim());
      let cur = this;
      while (cur) {
        for (const p of parts) {
          if (matchesSimple(cur, p)) return cur;
        }
        cur = cur.parentNode;
      }
      return null;
    }
    querySelector(selector) {
      const list = this.querySelectorAll(selector);
      return list[0] || null;
    }
    querySelectorAll(selector) {
      const parts = selector.split(',').map((s) => s.trim());
      const out = [];
      const walk = (node) => {
        for (const ch of node.children) {
          if (parts.some((p) => matchesSimple(ch, p))) out.push(ch);
          walk(ch);
        }
      };
      walk(this);
      return out;
    }
    focus() {
      activeElement = this;
      const ev = new MockEvent('focusin', { bubbles: true });
      ev.target = this;
      document.dispatchEvent(ev);
    }
    setSelectionRange(s, e) {
      this.selectionStart = s;
      this.selectionEnd = e;
    }
    getBoundingClientRect() {
      return { top: 400, left: 10, right: 350, bottom: 520, width: 340, height: 120 };
    }
    addEventListener(type, fn) {
      if (!this._listeners.has(type)) this._listeners.set(type, []);
      this._listeners.get(type).push(fn);
    }
    removeEventListener(type, fn) {
      const arr = this._listeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    }
    dispatchEvent(ev) {
      ev.target = ev.target || this;
      ev.currentTarget = this;
      this.dispatched.push(ev);
      const arr = this._listeners.get(ev.type) || [];
      for (const fn of [...arr]) fn(ev);
      return !ev.defaultPrevented;
    }
  }

  function markConnected(node, connected) {
    node.isConnected = connected;
    if (node._id) {
      if (connected) elementsById.set(node._id, node);
      else elementsById.delete(node._id);
    }
    for (const ch of node.children || []) markConnected(ch, connected);
  }

  function matchesSimple(el, sel) {
    if (!el || el.nodeType !== 1) return false;
    if (sel.startsWith('.')) return el.classList.contains(sel.slice(1));
    if (sel.startsWith('#')) return el.id === sel.slice(1);
    if (sel.startsWith('[')) {
      const m = sel.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
      if (!m) return false;
      return m[2] === undefined ? el.hasAttribute(m[1]) : el.getAttribute(m[1]) === m[2];
    }
    if (sel === 'textarea') return el.tagName === 'TEXTAREA';
    if (sel === 'input[type="text"]' || sel === 'input:not([type])') return el.tagName === 'INPUT';
    return el.tagName === sel.toUpperCase();
  }

  const head = new MockElement('head');
  const body = new MockElement('body');
  const documentElement = new MockElement('html');
  documentElement.isConnected = true;
  documentElement.appendChild(head);
  documentElement.appendChild(body);

  const document = {
    readyState: 'complete',
    head,
    body,
    documentElement,
    cookie: '',
    get activeElement() { return activeElement; },
    createElement(tag) { return new MockElement(tag); },
    createTextNode(txt) { return { nodeType: 3, textContent: txt }; },
    getElementById(id) { return elementsById.get(id) || null; },
    querySelector(sel) { return documentElement.querySelector(sel); },
    querySelectorAll(sel) { return documentElement.querySelectorAll(sel); },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const arr = listeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    },
    dispatchEvent(ev) {
      const arr = listeners.get(ev.type) || [];
      for (const fn of [...arr]) fn(ev);
      return !ev.defaultPrevented;
    }
  };

  const storage = new Map();
  const localStorage = {
    getItem: (k) => (storage.has(k) ? storage.get(k) : null),
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: (k) => storage.delete(k)
  };

  const windowObj = {
    document,
    localStorage,
    innerHeight: 800,
    innerWidth: 400,
    location: { search: '', href: 'https://127.0.0.1:45157/' },
    URL,
    URLSearchParams,
    Event: MockEvent,
    KeyboardEvent: MockKeyboardEvent,
    InputEvent: MockInputEvent,
    ClipboardEvent: MockEvent,
    DragEvent: MockEvent,
    File: globalThis.File,
    atob: globalThis.atob,
    Uint8Array: globalThis.Uint8Array,
    HTMLTextAreaElement: MockElement,
    HTMLInputElement: MockElement,
    navigator: { maxTouchPoints: 5 },
    console,
    setTimeout,
    clearTimeout,
    crypto: globalThis.crypto,
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' })
  };
  windowObj.window = windowObj;
  windowObj.self = windowObj;

  return { windowObj, document, body, MockElement, MockEvent, MockKeyboardEvent };
}

// ---------------------------------------------------------------------------
// 1. Test patches/mobile_bridge.js
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const calls = {
    desktop: [],
    extraKeys: [],
    preview: [],
    remotePortal: [],
    voiceSvcStart: [],
    voiceSvcStop: [],
    voiceMute: [],
    dictateStart: [],
    dictateStop: [],
    clipboardReads: []
  };
  let desktopState = false;
  let extraKeysState = false;
  let voiceSvcActive = false;
  let voiceMuted = false;
  let voiceDictating = false;
  let micPermState = 'prompt';
  let mockClipboardImg = null;
  let nextGumError = null;
  const pushedPaths = [];
  const winListeners = new Map();
  const dispatchedConvEvents = [];

  windowObj.location.pathname = '/';
  windowObj.CustomEvent = class MockCustomEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.detail = init.detail;
    }
  };
  windowObj.addEventListener = (type, fn) => {
    if (!winListeners.has(type)) winListeners.set(type, []);
    winListeners.get(type).push(fn);
  };
  windowObj.dispatchEvent = (ev) => {
    for (const fn of (winListeners.get(ev.type) || [])) fn(ev);
    return true;
  };
  windowObj.addEventListener('ag-open-conversation', (ev) => dispatchedConvEvents.push(ev.detail));
  windowObj.history = {
    pushState(_state, _title, url) {
      pushedPaths.push(String(url));
      windowObj.location.pathname = String(url);
    }
  };
  windowObj.navigator.mediaDevices = {
    async getUserMedia(constraints) {
      if (nextGumError) {
        const err = nextGumError;
        nextGumError = null;
        throw err;
      }
      return { getAudioTracks: () => [] };
    }
  };

  windowObj.navigator.permissions = {
    query: async (desc) => ({ name: desc?.name || '', state: 'denied' })
  };

  // Lightweight mock WebSocket so we can verify /voice/ws auto-service hook
  class MockWebSocket {
    constructor(url, protocols) {
      this.url = url;
      this.protocols = protocols;
      this.readyState = 0;
      this.closed = false;
      this._listeners = new Map();
    }
    addEventListener(type, fn) {
      if (!this._listeners.has(type)) this._listeners.set(type, []);
      this._listeners.get(type).push(fn);
    }
    emit(type, extra = {}) {
      if (type === 'open') this.readyState = 1;
      if (type === 'close') this.readyState = 3;
      for (const fn of (this._listeners.get(type) || [])) fn({ type, target: this, ...extra });
    }
    close() {
      this.closed = true;
      this.emit('close');
    }
  }
  MockWebSocket.CONNECTING = 0;
  MockWebSocket.OPEN = 1;
  MockWebSocket.CLOSING = 2;
  MockWebSocket.CLOSED = 3;
  windowObj.WebSocket = MockWebSocket;

  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'tok-secret-123';
  windowObj.Android = {
    isDesktopMode: () => desktopState,
    setDesktopMode: (v) => { desktopState = !!v; calls.desktop.push(!!v); },
    isExtraKeysVisible: () => extraKeysState,
    setExtraKeysVisible: (v) => { extraKeysState = !!v; calls.extraKeys.push(!!v); },
    openLocalPreview: (tok, url) => { calls.preview.push({ tok, url }); return true; },
    openRemotePortal: (tok) => { calls.remotePortal.push(tok); return true; },
    startVoiceService: (tok) => { voiceSvcActive = true; calls.voiceSvcStart.push(tok); return true; },
    stopVoiceService: (tok) => { voiceSvcActive = false; voiceMuted = false; calls.voiceSvcStop.push(tok); return true; },
    setVoiceMuted: (tok, m) => { voiceMuted = !!m; calls.voiceMute.push({ tok, muted: !!m }); return true; },
    isVoiceServiceActive: () => voiceSvcActive,
    isVoiceMuted: () => voiceMuted,
    startVoiceDictation: (tok) => { voiceDictating = true; calls.dictateStart.push(tok); return true; },
    stopVoiceDictation: (tok) => { voiceDictating = false; calls.dictateStop.push(tok); return true; },
    isVoiceDictating: () => voiceDictating,
    getMicrophonePermissionState: () => micPermState,
    hasClipboardImage: () => !!mockClipboardImg,
    readClipboardImage: (tok) => {
      calls.clipboardReads.push(tok);
      return JSON.stringify(mockClipboardImg || { ok: false, error: 'no_image' });
    }
  };

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'mobile_bridge.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.equal(typeof windowObj.__agDeliverSharedPayload, 'function');
  assert.equal(typeof windowObj.__agExtraKey, 'function');
  assert.equal(document.getElementById('ag-mb-pill'), null, 'No floating Tools pill should clutter the chat/Swarm canvas');

  // Mount a native-like Settings -> Appearance screen and verify "Android Tools" card injects cleanly
  const settingsScreen = document.createElement('div');
  const appearanceSec = document.createElement('div');
  appearanceSec.className = 'space-y-2';
  const appearanceCard = document.createElement('div');
  appearanceCard.className = 'agy-card';
  appearanceCard.textContent = 'Appearance Theme Contrast Font';
  appearanceSec.appendChild(appearanceCard);
  settingsScreen.appendChild(appearanceSec);
  body.appendChild(settingsScreen);
  assert.equal(windowObj.__agMobileBridge.injectSettingsControls(), true, 'Android Tools should inject into Settings');
  assert.ok(document.getElementById('ag-mb-settings-section'), 'Android Tools section should exist inside Settings');

  // 1a. Share Sheet before composer exists -> buffers + shows banner
  const bufferedRes = windowObj.__agDeliverSharedPayload({
    subject: 'Bug report',
    text: 'Check this log',
    files: [{ name: 'crash.txt', path: '/data/user/0/com.agy/cache/shared/crash.txt', mime: 'text/plain' }]
  });
  assert.equal(bufferedRes.ok, true);
  assert.equal(bufferedRes.buffered, true);
  assert.ok(document.getElementById('ag-mb-share-banner'), 'Share banner should appear when composer missing');

  // Mount composer and tap banner action button -> inserts text and removes banner
  const composer = document.createElement('textarea');
  composer.setAttribute('placeholder', 'Ask anything...');
  body.appendChild(composer);
  const bannerBtn = document.getElementById('ag-mb-share-banner').children[0];
  bannerBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.ok(composer.value.includes('Bug report'));
  assert.ok(composer.value.includes('Check this log'));
  assert.ok(composer.value.includes('/data/user/0/com.agy/cache/shared/crash.txt'));
  assert.equal(document.getElementById('ag-mb-share-banner'), null);

  // 1b. Extra-Keys in chat composer: '/' and '@' insert cleanly
  composer.value = '';
  composer.focus();
  windowObj.__agExtraKey('/', false);
  windowObj.__agExtraKey('@', false);
  assert.equal(composer.value, '/@');
  assert.ok(composer.dispatched.some((e) => e.type === 'input'), 'input event must be dispatched for React');

  // 1c. Extra-Keys in xterm terminal
  const xtermWrap = document.createElement('div');
  xtermWrap.className = 'xterm';
  const xtermArea = document.createElement('textarea');
  xtermArea.className = 'xterm-helper-textarea';
  xtermWrap.appendChild(xtermArea);
  body.appendChild(xtermWrap);
  xtermArea.focus();

  const receivedInput = [];
  xtermArea.addEventListener('input', (e) => receivedInput.push(e.data));

  const ctrlC = windowObj.__agExtraKey('c', true);
  assert.equal(ctrlC.target, 'terminal');
  assert.equal(ctrlC.ansi, '\x03');
  const ctrlD = windowObj.__agExtraKey('d', true);
  assert.equal(ctrlD.ansi, '\x04');
  const ctrlZ = windowObj.__agExtraKey('z', true);
  assert.equal(ctrlZ.ansi, '\x1a');
  const ctrlL = windowObj.__agExtraKey('l', true);
  assert.equal(ctrlL.ansi, '\x0c');
  const esc = windowObj.__agExtraKey('ESC', false);
  assert.equal(esc.ansi, '\x1b');
  const tab = windowObj.__agExtraKey('TAB', false);
  assert.equal(tab.ansi, '\t');
  const up = windowObj.__agExtraKey('ArrowUp', false);
  assert.equal(up.ansi, '\x1b[A');
  assert.deepEqual(receivedInput, ['\x03', '\x04', '\x1a', '\x0c', '\x1b', '\t', '\x1b[A']);

  // Verify no duplicate input when xterm consumes keydown via preventDefault()
  receivedInput.length = 0;
  xtermArea.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') e.preventDefault(); });
  windowObj.__agExtraKey('ArrowDown', false);
  assert.equal(receivedInput.length, 0, 'Should not double-inject when xterm handles keydown');

  // 1d. Settings "Android Tools" toggles & Localhost Preview
  document.getElementById('ag-mb-toggle-desktop').dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  document.getElementById('ag-mb-toggle-extrakeys').dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.desktop, [true]);
  assert.deepEqual(calls.extraKeys, [true]);

  const goodPrev = windowObj.__agMobileBridge.openLocalPreview('5173');
  assert.equal(goodPrev.ok, true);
  assert.deepEqual(calls.preview, [{ tok: 'tok-secret-123', url: 'http://localhost:5173' }]);

  const badPrev = windowObj.__agMobileBridge.openLocalPreview('https://evil.example.com/');
  assert.equal(badPrev.ok, false);
  assert.equal(calls.preview.length, 1);

  // 1e. Native hide callback syncs drawer switch state
  assert.equal(typeof windowObj.__agExtraKeysVisibilityChanged, 'function');
  extraKeysState = false;
  windowObj.__agExtraKeysVisibilityChanged(false);
  assert.equal(document.getElementById('ag-mb-toggle-extrakeys').getAttribute('aria-checked'), 'false');

  // 1f. PC Remote Control button invokes Android.openRemotePortal(token)
  const remoteBtn = document.getElementById('ag-mb-remote-portal');
  assert.ok(remoteBtn, 'PC Remote Control button should be rendered in Quick Controls drawer');
  remoteBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.remotePortal, ['tok-secret-123']);

  // 1g. Native Voice Dictation button + __agDeliverVoiceText (interim + final)
  assert.equal(typeof windowObj.__agDeliverVoiceText, 'function');
  assert.equal(typeof windowObj.__agVoiceDictationStateChanged, 'function');
  const dictateBtn = document.getElementById('ag-mb-voice-dictate');
  assert.ok(dictateBtn, 'Voice Dictation button should be rendered in Quick Controls drawer');
  dictateBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.dictateStart, ['tok-secret-123']);

  composer.value = 'Hello';
  composer.focus();
  const interimRes = windowObj.__agDeliverVoiceText('world', false);
  assert.equal(interimRes.interim, true);
  assert.equal(composer.value, 'Hello', 'Interim transcript should not spam composer');

  const finalRes = windowObj.__agDeliverVoiceText('from Android voice', true);
  assert.equal(finalRes.inserted, true);
  assert.equal(composer.value, 'Hello from Android voice');

  // 1h. Non-invasive WebSocket hook for /voice/ws starts/stops VoiceForegroundService
  const otherWs = new windowObj.WebSocket('wss://127.0.0.1:45157/connect-websocket');
  otherWs.emit('open');
  assert.equal(calls.voiceSvcStart.length, 0, 'Non-voice WebSocket must not trigger VoiceForegroundService');

  const voiceWs = new windowObj.WebSocket('wss://127.0.0.1:45157/voice/ws', ['jetski-voice-proto']);
  voiceWs.emit('open');
  assert.deepEqual(calls.voiceSvcStart, ['tok-secret-123']);
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Active'));

  // Notification shade Mute & Stop actions update state and close /voice/ws cleanly
  voiceMuted = true;
  windowObj.__agVoiceServiceStateChanged({ active: true, muted: true });
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Muted'));
  voiceSvcActive = false;
  voiceMuted = false;
  windowObj.__agVoiceServiceStateChanged({ active: false, muted: false });
  assert.equal(voiceWs.closed, true, 'Stop Voice from notification should close active /voice/ws');
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Idle'));

  // 1h-bis. Microphone permission shim overrides false WebView 'denied' using native state
  micPermState = 'prompt';
  const micPerm1 = await windowObj.navigator.permissions.query({ name: 'microphone' });
  assert.equal(micPerm1.state, 'prompt', 'Should map false WebView denied to prompt');
  micPermState = 'granted';
  const micPerm2 = await windowObj.navigator.permissions.query({ name: 'microphone' });
  assert.equal(micPerm2.state, 'granted', 'Should reflect native granted state');
  const otherPerm = await windowObj.navigator.permissions.query({ name: 'clipboard-read' });
  assert.equal(otherPerm.state, 'denied', 'Non-microphone permissions must pass through untouched');

  // 1h-ter. Notification deep-link (__agOpenConversation) + /voice/ws & getUserMedia diagnostics
  const clone = (o) => JSON.parse(JSON.stringify(o));
  assert.deepEqual(clone(windowObj.__agOpenConversation('')), { ok: false, reason: 'invalid_cascade_id' });
  assert.deepEqual(clone(windowObj.__agOpenConversation('../evil')), { ok: false, reason: 'invalid_cascade_id' });
  assert.deepEqual(clone(windowObj.__agOpenConversation('a/b')), { ok: false, reason: 'invalid_cascade_id' });
  assert.equal(pushedPaths.length, 0, 'Invalid cascade IDs must not mutate history');

  const convOpenRes = clone(windowObj.__agOpenConversation('conv-42_test'));
  assert.deepEqual(convOpenRes, { ok: true, cascadeId: 'conv-42_test', path: '/c/conv-42_test' });
  assert.deepEqual(pushedPaths, ['/c/conv-42_test']);
  assert.equal(dispatchedConvEvents.length, 1);
  assert.deepEqual(clone(dispatchedConvEvents[0]), { cascadeId: 'conv-42_test', path: '/c/conv-42_test' });
  assert.deepEqual(clone(windowObj.__agOpenConversation('conv-42_test')), {
    ok: true,
    cascadeId: 'conv-42_test',
    path: '/c/conv-42_test',
    unchanged: true
  });

  const voiceWsErr = new windowObj.WebSocket('wss://127.0.0.1:45157/voice/ws');
  voiceWsErr.emit('open');
  voiceWsErr.emit('close', { code: 1006, reason: 'network_lost' });
  const diagEl = document.getElementById('ag-mb-voice-diag');
  assert.equal(windowObj.__agMobileBridge.probe().voiceWsState, 'error');
  assert.ok(windowObj.__agMobileBridge.probe().voiceLastError.includes('1006'));
  assert.ok(windowObj.__agMobileBridge.probe().voiceLastError.includes('network_lost'));
  assert.equal(diagEl.style.display, 'block');
  assert.ok(diagEl.textContent.includes('network_lost'));
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Error'));

  const gumErr = new Error('Permission denied');
  gumErr.name = 'NotAllowedError';
  nextGumError = gumErr;
  await assert.rejects(() => windowObj.navigator.mediaDevices.getUserMedia({ audio: true }));
  await Promise.resolve();
  assert.equal(windowObj.__agMobileBridge.probe().voiceWsState, 'error');
  assert.ok(windowObj.__agMobileBridge.probe().voiceLastError.includes('Microphone permission denied'));
  assert.ok(diagEl.textContent.includes('Microphone permission denied'));
  assert.ok(document.getElementById('ag-mb-toast').textContent.includes('Microphone permission denied'));

  // 1i. Clipboard image paste (__agDeliverPastedImage, paste interceptor, no-double-paste, fallbacks)
  assert.equal(typeof windowObj.__agDeliverPastedImage, 'function');
  const tinyPngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
  const pastedFiles = [];
  const droppedFiles = [];
  let acceptPaste = true;
  composer.addEventListener('paste', (ev) => {
    if (!acceptPaste) return;
    const items = Array.from(ev.clipboardData?.items || []);
    const fileItem = items.find((it) => it.kind === 'file');
    if (fileItem) {
      ev.preventDefault();
      pastedFiles.push(fileItem.getAsFile());
    }
  });
  composer.addEventListener('drop', (ev) => {
    const files = Array.from(ev.dataTransfer?.files || []);
    if (files.length > 0) {
      ev.preventDefault();
      droppedFiles.push(files[0]);
    }
  });

  // Direct delivery (IME commitContent / OnReceiveContentListener)
  composer.focus();
  const imgRes = windowObj.__agDeliverPastedImage(tinyPngB64, 'image/png', 'screenshot.png');
  assert.equal(imgRes.ok, true);
  assert.equal(imgRes.method, 'paste');
  assert.equal(pastedFiles.length, 1);
  assert.equal(pastedFiles[0].name, 'screenshot.png');
  assert.equal(pastedFiles[0].type, 'image/png');
  assert.equal(droppedFiles.length, 0, 'Must not double-attach via drop when paste was consumed');

  // Empty WebView paste event falls back to Android.readClipboardImage(token) without re-entry loop
  mockClipboardImg = { ok: true, base64: tinyPngB64, mime: 'image/jpeg', name: 'clip.jpg' };
  const emptyPaste = new windowObj.Event('paste', { bubbles: true, cancelable: true });
  emptyPaste.clipboardData = { files: [], items: [], getData: () => '' };
  emptyPaste.target = composer;
  document.dispatchEvent(emptyPaste);
  assert.equal(emptyPaste.defaultPrevented, true, 'Empty paste should be intercepted when Android clipboard has image');
  assert.deepEqual(calls.clipboardReads, ['tok-secret-123']);
  assert.equal(pastedFiles.length, 2);
  assert.equal(pastedFiles[1].name, 'clip.jpg');

  // Native WebView paste that already carries an image must NOT trigger duplicate Android read
  const nativeImgPaste = new windowObj.Event('paste', { bubbles: true, cancelable: true });
  nativeImgPaste.clipboardData = { files: [{ type: 'image/png' }], items: [], getData: () => '' };
  nativeImgPaste.target = composer;
  document.dispatchEvent(nativeImgPaste);
  assert.equal(calls.clipboardReads.length, 1, 'Should not read Android clipboard if WebView already supplied image');

  // When composer ignores paste, fallback attaches once via drop
  acceptPaste = false;
  const dropRes = windowObj.__agDeliverPastedImage(tinyPngB64, 'image/webp', 'fallback.webp');
  assert.equal(dropRes.ok, true);
  assert.equal(dropRes.method, 'drop');
  assert.equal(droppedFiles.length, 1);
  assert.equal(droppedFiles[0].name, 'fallback.webp');

  // Quick Controls "Paste Image" button reads native clipboard and attaches
  acceptPaste = true;
  const pasteImgBtn = document.getElementById('ag-mb-paste-image');
  assert.ok(pasteImgBtn, 'Paste Image button should be rendered in Quick Controls');
  pasteImgBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.equal(calls.clipboardReads.length, 2);
  assert.equal(pastedFiles.length, 3);

  // 1j. Confirm programmatic drawer fallback also works cleanly when Settings is not mounted
  settingsScreen.parentNode.removeChild(settingsScreen);
  assert.equal(document.getElementById('ag-mb-settings-section'), null);
  windowObj.__agMobileBridge.openDrawer();
  assert.ok(document.getElementById('ag-mb-drawer').classList.contains('ag-mb-open'));
  windowObj.__agMobileBridge.closeDrawer();
  assert.equal(document.getElementById('ag-mb-drawer').classList.contains('ag-mb-open'), false);
  assert.equal(document.getElementById('ag-mb-pill'), null, 'Still zero floating pill after programmatic drawer');

  console.log('test mobile_bridge.js: OK');
}

// ---------------------------------------------------------------------------
// 2. Test bridge.core.js (https ?port=, random CSRF fallback, /voice/health retry)
// ---------------------------------------------------------------------------
{
  const { windowObj } = createMockDom();
  windowObj.location = { search: '?port=45123', href: 'https://127.0.0.1:45123/?port=45123' };
  const nativeNotifications = [];
  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'tok-core-notify';
  windowObj.Android = {
    showAgentNotification: (tok, title, body, tag, silent) => {
      nativeNotifications.push({ tok, title, body, tag, silent });
      return true;
    }
  };
  let vhAttempts = 0;
  windowObj.fetch = async (url) => {
    if (String(url).includes('/voice/health')) {
      vhAttempts++;
      if (vhAttempts === 1) throw new Error('ECONNREFUSED');
      if (vhAttempts === 2) return { status: 503, ok: false };
      return { status: 200, ok: true };
    }
    return { status: 200, ok: true };
  };
  const code = fs.readFileSync(path.join(GEN_DIR, 'bridge.core.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.equal(windowObj.__AG_ENDPOINTS__.core_http, 'https://127.0.0.1:45123');
  assert.equal(windowObj.__agBridgeCore.baseOrigin(), 'https://127.0.0.1:45123');

  const csrf1 = windowObj.__APP_CONFIG__.csrfToken;
  assert.ok(csrf1 && csrf1.length >= 16, 'CSRF fallback should be non-empty random token');
  assert.notEqual(csrf1, 'agy-standalone-token', 'Hardcoded CSRF fallback must be removed');
  assert.equal(windowObj.__agBridgeCore.syncCsrf(), csrf1, 'Fallback CSRF should remain stable within session');

  const vhResp = await windowObj.fetch('/voice/health');
  assert.equal(vhResp.status, 200, '/voice/health should retry transient cold-boot failures');
  assert.equal(vhAttempts, 3);

  const webNote = new windowObj.Notification('Build Ready', {
    body: 'Completed successfully',
    tag: 'turn_99',
    silent: false
  });
  assert.equal(webNote.title, 'Build Ready');
  assert.deepEqual(nativeNotifications, [{
    tok: 'tok-core-notify',
    title: 'Build Ready',
    body: 'Completed successfully',
    tag: 'turn_99',
    silent: false
  }], 'Notification polyfill should forward web notifications to Android.showAgentNotification');

  console.log('test bridge.core.js: OK');
}

// ---------------------------------------------------------------------------
// 3. Test touch_model_menu.js (MutationObserver reaction & cleanup)
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const observers = [];
  windowObj.MutationObserver = class MockMutationObserver {
    constructor(cb) {
      this.cb = cb;
      this.disconnected = false;
      observers.push(this);
    }
    observe() {}
    disconnect() { this.disconnected = true; }
  };
  windowObj.PointerEvent = windowObj.Event;
  windowObj.MouseEvent = windowObj.Event;

  const row = document.createElement('div');
  row.setAttribute('role', 'menuitem');
  const group = document.createElement('div');
  group.setAttribute('data-testid', 'model-selector-effort-group');
  row.appendChild(group);
  body.appendChild(row);

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'touch_model_menu.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  // Tap row -> starts openSubmenu observer
  row.dispatchEvent(new windowObj.Event('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
  assert.ok(observers.length >= 2, 'openSubmenu should attach a MutationObserver');
  const openObs = observers[observers.length - 1];

  // Base UI highlights the row immediately -> observer triggers ArrowRight immediately
  row.setAttribute('data-highlighted', '');
  openObs.cb([]);
  assert.ok(row.dispatched.some((e) => e.type === 'keydown' && e.key === 'ArrowRight'), 'MutationObserver should send ArrowRight immediately on highlight');

  // Base UI mounts effort options -> observer cleans up and disconnects
  const opt = document.createElement('div');
  opt.setAttribute('data-testid', 'model-selector-effort-option');
  body.appendChild(opt);
  openObs.cb([]);
  assert.equal(openObs.disconnected, true, 'MutationObserver should disconnect once options open');

  console.log('test touch_model_menu.js: OK');
}

// ---------------------------------------------------------------------------
// 4. Test patch_main.py graceful fallback on changed upstream signatures
// ---------------------------------------------------------------------------
{
  const tmpFile = path.join(os.tmpdir(), `agy-drift-main-${Date.now()}.js`);
  const dummySrc = 'const completelyChangedUpstream = () => "hello";\n';
  fs.writeFileSync(tmpFile, dummySrc, 'utf8');
  try {
    const py = process.platform === 'win32' ? 'python' : 'python3';
    execFileSync(py, [path.join(WEB_DIR, 'patch_main.py'), tmpFile], { stdio: 'pipe' });
    assert.equal(fs.readFileSync(tmpFile, 'utf8'), dummySrc, 'Unmatched main.js must remain untouched');
  } finally {
    try { fs.unlinkSync(tmpFile); } catch (_) {}
  }
  console.log('test patch_main.py graceful fallback: OK');
}

// ---------------------------------------------------------------------------
// 5. Test patches/web_optimizations.js (IME resize/RO deferral + safe-area CSS)
// ---------------------------------------------------------------------------
{
  const { windowObj, document } = createMockDom();

  class MockEventTarget {
    constructor() {
      this._etListeners = new Map();
    }
    addEventListener(type, fn) {
      if (!this._etListeners.has(type)) this._etListeners.set(type, []);
      this._etListeners.get(type).push(fn);
    }
    removeEventListener(type, fn) {
      const arr = this._etListeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    }
    dispatchEvent(ev) {
      const arr = this._etListeners.get(ev.type) || [];
      for (const fn of [...arr]) fn.call(this, ev);
      return true;
    }
  }

  class MockResizeObserver {
    constructor(cb) {
      this.cb = cb;
      this.disconnected = false;
      MockResizeObserver.instances.push(this);
    }
    observe() {}
    disconnect() { this.disconnected = true; }
    trigger(entries) {
      if (!this.disconnected) this.cb(entries, this);
    }
  }
  MockResizeObserver.instances = [];

  const vv = new MockEventTarget();
  vv.height = 800;
  windowObj.EventTarget = MockEventTarget;
  windowObj.HTMLElement = function () {};
  windowObj.HTMLElement.prototype = { focus() {} };
  windowObj.ResizeObserver = MockResizeObserver;
  windowObj.visualViewport = vv;
  windowObj._etListeners = new Map();
  Object.setPrototypeOf(windowObj, MockEventTarget.prototype);

  const addedWorklets = [];
  class MockAudioContext {
    constructor(opts) {
      this.sampleRate = opts?.sampleRate || 48000;
      this.state = 'suspended';
      this._loadedModules = new Set();
      this.audioWorklet = {
        addModule: async (url) => {
          addedWorklets.push(url);
          this._loadedModules.add(url);
        }
      };
    }
    async resume() { this.state = 'running'; }
    createMediaStreamSource() { return { connect() {}, disconnect() {} }; }
  }
  class MockAudioWorkletNode {
    constructor(ctx, name) {
      if (!ctx._loadedModules.size) {
        throw new Error(`AudioWorkletNode ${name} constructed before addModule finished`);
      }
      this.name = name;
    }
  }
  windowObj.AudioContext = MockAudioContext;
  windowObj.AudioWorkletNode = MockAudioWorkletNode;

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'web_optimizations.js'), 'utf8');
  const ctx = vm.createContext(windowObj);
  vm.runInContext(code, ctx);
  const winProxy = vm.runInContext('window', ctx);

  const injectedStyle = document.head.querySelector('[data-agy="web-optimizations"]');
  assert.ok(injectedStyle, 'web-optimizations style tag should be injected');
  assert.ok(injectedStyle.textContent.includes('--agy-safe-top'), 'Safe-area top CSS variable must be defined');
  assert.ok(injectedStyle.textContent.includes('--agy-safe-bottom'), 'Safe-area bottom CSS variable must be defined');
  assert.ok(injectedStyle.textContent.includes('html.agy-extra-keys'), 'Extra-keys bottom safe-area override must be present');
  assert.ok(injectedStyle.textContent.includes('content-visibility: auto'), 'Chat articles must use content-visibility: auto for long-thread scroll performance');
  assert.ok(injectedStyle.textContent.includes('contain-intrinsic-block-size: auto 180px'), 'Chat articles must reserve intrinsic height with contain-intrinsic-block-size');

  let resizeCalls = 0;
  let vvResizeCalls = 0;
  let removedResizeCalls = 0;
  const onWinResize = () => { resizeCalls++; };
  const onRemovedResize = () => { removedResizeCalls++; };
  winProxy.addEventListener('resize', onWinResize);
  winProxy.addEventListener('resize', onRemovedResize);
  winProxy.removeEventListener('resize', onRemovedResize);
  vv.addEventListener('resize', () => { vvResizeCalls++; });

  let roActiveCalls = 0;
  let roDisconnectedCalls = 0;
  const roActive = new winProxy.ResizeObserver(() => { roActiveCalls++; });
  const roStale = new winProxy.ResizeObserver(() => { roDisconnectedCalls++; });
  const origActiveInst = MockResizeObserver.instances[0];
  const origStaleInst = MockResizeObserver.instances[1];

  winProxy.__agSetImeAnimating(true);
  assert.equal(winProxy.__agIsImeAnimating(), true);

  // Fire multiple resize & ResizeObserver frames during IME transition
  vv.height = 600;
  winProxy.dispatchEvent(new winProxy.Event('resize'));
  winProxy.dispatchEvent(new winProxy.Event('resize'));
  vv.dispatchEvent(new winProxy.Event('resize'));
  origActiveInst.trigger([{ target: 'box1' }]);
  origActiveInst.trigger([{ target: 'box2' }]);
  origStaleInst.trigger([{ target: 'stale' }]);
  roStale.disconnect();

  assert.equal(resizeCalls, 0, 'window.resize must be deferred during IME animation');
  assert.equal(vvResizeCalls, 0, 'visualViewport.resize must be deferred during IME animation');
  assert.equal(roActiveCalls, 0, 'ResizeObserver must be deferred during IME animation');

  winProxy.__agSetImeAnimating(false);
  assert.equal(winProxy.__agIsImeAnimating(), false);
  assert.equal(resizeCalls, 1, 'Deferred window.resize must flush once on IME animation end');
  assert.equal(vvResizeCalls, 1, 'Deferred visualViewport.resize must flush once on IME animation end');
  assert.equal(removedResizeCalls, 0, 'Removed resize listener must not fire');
  assert.equal(roActiveCalls, 1, 'Deferred ResizeObserver must flush once with latest entries');
  assert.equal(roDisconnectedCalls, 0, 'Disconnected ResizeObserver must not flush after disconnect()');

  // Verify 48kHz Live Voice AudioContext loads audioWorklet synchronously when awaited
  const voiceCtx = new winProxy.AudioContext({ sampleRate: 48000 });
  await voiceCtx.resume();
  await voiceCtx.audioWorklet.addModule('data:application/javascript;base64,test');
  const node = new winProxy.AudioWorkletNode(voiceCtx, 'audio-streaming-processor');
  assert.equal(node.name, 'audio-streaming-processor');
  assert.equal(addedWorklets.length, 1, '48kHz audioWorklet.addModule must execute eagerly, not be stubbed');

  console.log('test web_optimizations.js: OK');
}

// ---------------------------------------------------------------------------
// 6. Test patches/theme_sync.js (surface color prefers mobile-layout over body)
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const syncedColors = [];
  windowObj.__AG_HOST_THEME__ = 'light';
  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'tok-theme';
  windowObj.Android = {
    getTheme: () => 'light',
    setThemeByUser: () => true,
    onVisualThemeChanged: () => {},
    setSurfaceColor: (tok, hex) => {
      if (tok === 'tok-theme') syncedColors.push(hex);
      return true;
    }
  };
  const mobileLayout = document.createElement('div');
  mobileLayout.setAttribute('data-testid', 'mobile-layout');
  body.appendChild(mobileLayout);
  body.style.backgroundColor = 'rgb(0, 0, 0)';
  mobileLayout.style.backgroundColor = 'rgb(255, 255, 255)';
  windowObj.getComputedStyle = (el) => ({
    backgroundColor: el?.style?.backgroundColor || 'transparent'
  });

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'theme_sync.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.ok(syncedColors.includes('#ffffff'), 'Surface sync should read [data-testid="mobile-layout"] background before body');
  console.log('test theme_sync.js: OK');
}

// ---------------------------------------------------------------------------
// 7. Test mobile_bridge.js Settings MutationObserver streaming leaf filter
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const rootEl = document.createElement('div');
  rootEl.id = 'root';
  body.appendChild(rootEl);

  const scheduled = [];
  let timerSeq = 1;
  windowObj.setTimeout = (_fn, delay) => {
    scheduled.push(delay);
    return timerSeq++;
  };
  windowObj.clearTimeout = () => {};

  const observers = [];
  windowObj.MutationObserver = class MockMutationObserver {
    constructor(cb) {
      this.cb = cb;
      observers.push(this);
    }
    observe() {}
    disconnect() {}
  };

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'mobile_bridge.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.equal(observers.length, 1, 'mobile_bridge should install one Settings MutationObserver');
  const obs = observers[0];

  // Streaming leaf elements (SPAN, P, CODE) must NOT schedule Settings DOM scans
  const spanNode = document.createElement('span');
  const codeNode = document.createElement('code');
  obs.cb([{ addedNodes: [spanNode, codeNode] }]);
  assert.equal(scheduled.length, 0, 'Streaming leaf nodes should be skipped without scheduling Settings scan');

  // Container carrying .agy-card SHOULD schedule a 200ms debounced Settings scan
  const cardNode = document.createElement('div');
  cardNode.className = 'agy-card';
  obs.cb([{ addedNodes: [cardNode] }]);
  assert.deepEqual(scheduled, [200], 'Adding .agy-card should schedule Settings scan with 200ms debounce');

  // Once #ag-mb-settings-section is mounted and connected, observer should early-return
  const mountedSec = document.createElement('div');
  mountedSec.id = 'ag-mb-settings-section';
  body.appendChild(mountedSec);
  obs.cb([{ addedNodes: [cardNode] }]);
  assert.equal(scheduled.length, 1, 'Mounted #ag-mb-settings-section should skip re-scanning mutations');

  console.log('test mobile_bridge.js observer filter: OK');
}

// ---------------------------------------------------------------------------
// 8. Test bridge.core.js wrapStreamingFetch per-stream recovery & multi-chunk
// ---------------------------------------------------------------------------
{
  const { windowObj } = createMockDom();
  const winListeners = new Map();
  windowObj.location = { search: '?port=45157', href: 'https://127.0.0.1:45157/' };
  windowObj.ReadableStream = globalThis.ReadableStream;
  windowObj.Response = globalThis.Response;
  windowObj.AbortController = globalThis.AbortController;
  windowObj.addEventListener = (type, fn) => {
    if (!winListeners.has(type)) winListeners.set(type, []);
    winListeners.get(type).push(fn);
  };
  windowObj.dispatchEvent = (ev) => {
    for (const fn of (winListeners.get(ev.type) || [])) fn(ev);
    return true;
  };

  let controllerRef;
  windowObj.fetch = async () => {
    const body = new globalThis.ReadableStream({
      start(controller) {
        controllerRef = controller;
        controller.enqueue(new Uint8Array([1, 2]));
        controller.enqueue(new Uint8Array([3, 4]));
      }
    });
    return new globalThis.Response(body, { status: 200, statusText: 'OK' });
  };

  const code = fs.readFileSync(path.join(GEN_DIR, 'bridge.core.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  const resp = await windowObj.fetch(
    'https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates',
    { method: 'POST', body: '{}' }
  );
  assert.equal(windowObj.__agBridgeCore.getActiveStreamCount(), 1, 'Streaming RPC should register active stream closer');

  const reader = resp.body.getReader();
  const c1 = await reader.read();
  assert.equal(c1.done, false);
  assert.deepEqual(Array.from(c1.value), [1, 2]);
  const c2 = await reader.read();
  assert.equal(c2.done, false);
  assert.deepEqual(Array.from(c2.value), [3, 4]);

  // Next read awaits server chunk or host resume; dispatching agy:host-resume must close cleanly
  const pendingRead = reader.read();
  windowObj.dispatchEvent(new windowObj.Event('agy:host-resume'));
  const c3 = await pendingRead;
  assert.equal(c3.done, true, 'agy:host-resume must resolve pending wrapped read cleanly with done: true');
  assert.equal(windowObj.__agBridgeCore.getActiveStreamCount(), 0, 'Active stream closer must be removed after resume');

  console.log('test bridge.core.js streaming recovery: OK');
}

// ---------------------------------------------------------------------------
// 9. Test patches/account_switcher.js (vault sync + Settings UI section/screen)
// ---------------------------------------------------------------------------
{
  const { windowObj } = createMockDom();
  const dispatchedEvents = [];
  let activeId = 'alice@example.com';
  const accountsDb = [
    { id: 'alice@example.com', email: 'alice@example.com', name: 'Alice', tier: 'Pro', active: true },
    { id: 'bob@example.com', email: 'bob@example.com', name: 'Bob', tier: 'Free', active: false }
  ];
  const calls = { sync: [], switched: [], removed: [], restarted: 0, authChecks: 0, refreshes: 0 };

  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'acct-tok';
  windowObj.CustomEvent = class MockCustomEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.detail = init.detail;
    }
  };
  windowObj.addEventListener = () => {};
  windowObj.removeEventListener = () => {};
  windowObj.dispatchEvent = (ev) => {
    dispatchedEvents.push(ev.type);
    return true;
  };
  windowObj.Android = {
    listSavedAccounts(tok) {
      assert.equal(tok, 'acct-tok');
      return JSON.stringify(accountsDb.map((a) => ({ ...a, active: a.id === activeId })));
    },
    syncCurrentAccount(tok, profileJson) {
      assert.equal(tok, 'acct-tok');
      calls.sync.push(JSON.parse(profileJson));
      return this.listSavedAccounts(tok);
    },
    switchSavedAccount(tok, id) {
      assert.equal(tok, 'acct-tok');
      calls.switched.push(id);
      activeId = id;
      return JSON.stringify({ ok: true, accountId: id, email: id, accounts: JSON.parse(this.listSavedAccounts(tok)) });
    },
    removeSavedAccount(tok, id) {
      assert.equal(tok, 'acct-tok');
      calls.removed.push(id);
      const idx = accountsDb.findIndex((a) => a.id === id);
      if (idx >= 0) accountsDb.splice(idx, 1);
      return this.listSavedAccounts(tok);
    },
    restartCoreForAccountSwitch(tok) {
      assert.equal(tok, 'acct-tok');
      calls.restarted += 1;
    }
  };

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'account_switcher.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.ok(windowObj.__agAccountSwitcher, '__agAccountSwitcher should be installed');
  const firstSync = windowObj.__agAccountSwitcher.syncProfile(
    { email: 'alice@example.com', name: 'Alice', userTier: { description: 'Pro' } },
    { project: 'proj-1', location: 'us-central1', isGcpTos: true }
  );
  assert.equal(firstSync.length, 2);
  assert.equal(calls.sync.length, 1);
  assert.equal(calls.sync[0].projectId, 'proj-1');
  assert.ok(dispatchedEvents.includes('ag:accounts-updated'));

  // Repeating same payload must dedup without invoking Android.syncCurrentAccount again
  windowObj.__agAccountSwitcher.syncProfile(
    { email: 'alice@example.com', name: 'Alice', userTier: { description: 'Pro' } },
    { project: 'proj-1', location: 'us-central1', isGcpTos: true }
  );
  assert.equal(calls.sync.length, 1, 'syncProfile should deduplicate identical payloads');

  // Minimal synchronous React harness for renderScreen / renderSection
  const stateSlots = [];
  let hookIdx = 0;
  const mockReact = {
    useState(init) {
      const idx = hookIdx++;
      if (!(idx in stateSlots)) stateSlots[idx] = typeof init === 'function' ? init() : init;
      return [stateSlots[idx], (v) => { stateSlots[idx] = v; }];
    },
    useEffect() {},
    createElement(type, props, ...children) {
      if (typeof type === 'function') {
        hookIdx = 0;
        return type({ ...(props || {}), children });
      }
      return { type, props: props || {}, children };
    }
  };
  const screenEl = windowObj.__agAccountSwitcher.renderScreen({
    React: mockReact,
    LU: 'Screen',
    PU: 'Section',
    NU: 'Card',
    OU: 'Row',
    BI: 'Button',
    Dgb: null,
    eE: null,
    userStatus: { email: 'alice@example.com', name: 'Alice' },
    authService: {
      showLoginFlow() {},
      async checkCurrentAuthStatus() { calls.authChecks += 1; }
    },
    async refreshUserStatus() { calls.refreshes += 1; },
    getStoreState: () => ({ userStatus: { email: activeId }, auth: { context: {} } }),
    isRemoteClient: false
  });
  assert.equal(screenEl.props.title, 'Accounts');

  // Find Switch & Remove buttons inside virtual tree
  const buttons = [];
  (function walk(node) {
    if (!node) return;
    if (Array.isArray(node)) return node.forEach(walk);
    if (typeof node === 'object') {
      if (node.type === 'Button') buttons.push(node);
      if (node.props && node.props.rightElement) walk(node.props.rightElement);
      if (node.children) walk(node.children);
    }
  })(screenEl);

  const switchBtn = buttons.find((b) => b.children.includes('Switch'));
  assert.ok(switchBtn, 'Switch button should render for inactive Bob account');
  switchBtn.props.onClick();
  await new Promise((r) => setTimeout(r, 10));
  assert.deepEqual(calls.switched, ['bob@example.com']);
  assert.equal(calls.authChecks, 1);
  assert.equal(calls.refreshes, 1);
  assert.equal(calls.restarted, 0, 'In-place auth refresh succeeded without core restart');

  const removeBtns = buttons.filter((b) => b.children.includes('Remove'));
  assert.equal(removeBtns.length, 2);
  removeBtns[0].props.onClick();
  assert.deepEqual(calls.removed, ['alice@example.com']);

  console.log('test patches/account_switcher.js: OK');
}

// ---------------------------------------------------------------------------
// 10. Test patches/mobile_bridge.js clickable file paths & APK install card
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body, MockEvent } = createMockDom();
  const pathCalls = { apk: [], open: [] };
  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'path-tok';
  windowObj.location.pathname = '/';
  windowObj.CustomEvent = class MockCustomEvent { constructor(type, init = {}) { this.type = type; this.detail = init.detail; } };
  windowObj.addEventListener = () => {};
  windowObj.dispatchEvent = () => true;
  windowObj.Android = {
    isDesktopMode: () => false,
    setDesktopMode: () => {},
    isExtraKeysVisible: () => false,
    setExtraKeysVisible: () => {},
    getMicrophonePermissionState: () => 'granted',
    installLocalApk(tok, p) { pathCalls.apk.push([tok, p]); return true; },
    openLocalPath(tok, p, folder) { pathCalls.open.push([tok, p, folder]); return true; }
  };

  const apkLink = document.createElement('a');
  apkLink.setAttribute('href', 'file:///storage/emulated/0/Download/app-release.apk');
  apkLink.textContent = 'app-release.apk';
  body.appendChild(apkLink);

  const codePath = document.createElement('code');
  codePath.textContent = '/storage/emulated/0/Documents/notes.txt';
  body.appendChild(codePath);

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'mobile_bridge.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  document.dispatchEvent(Object.assign(new MockEvent('click', { cancelable: true }), { target: apkLink }));
  const installBtn = document.getElementById('ag-mb-path-install-apk');
  assert.ok(installBtn, 'Clicking .apk file:// link should mount #ag-mb-path-card with #ag-mb-path-install-apk');
  installBtn.dispatchEvent(new MockEvent('click'));
  assert.deepEqual(pathCalls.apk, [['path-tok', '/storage/emulated/0/Download/app-release.apk']]);

  document.dispatchEvent(Object.assign(new MockEvent('click', { cancelable: true }), { target: codePath }));
  assert.equal(document.getElementById('ag-mb-path-install-apk'), null, 'Non-APK path should not show APK install button');
  const folderBtn = document.getElementById('ag-mb-path-open-folder');
  assert.ok(folderBtn, 'Clicking local path <code> should offer Open Folder');
  folderBtn.dispatchEvent(new MockEvent('click'));
  assert.deepEqual(pathCalls.open, [['path-tok', '/storage/emulated/0/Documents/notes.txt', true]]);

  console.log('test patches/mobile_bridge.js clickable paths: OK');
}

// ---------------------------------------------------------------------------
// 11. Test patches/web_optimizations.js broken data:video image badge fallback
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body, MockEvent } = createMockDom();
  windowObj.HTMLElement = windowObj.HTMLInputElement;
  windowObj.addEventListener = () => {};
  windowObj.requestAnimationFrame = () => 1;
  windowObj.cancelAnimationFrame = () => {};

  const holder = document.createElement('div');
  const vidImg = document.createElement('img');
  vidImg.setAttribute('src', 'data:video/mp4;base64,AAAA');
  vidImg.src = 'data:video/mp4;base64,AAAA';
  vidImg.setAttribute('alt', 'clip.mp4');
  holder.appendChild(vidImg);
  body.appendChild(holder);

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'web_optimizations.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  document.dispatchEvent(Object.assign(new MockEvent('error'), { target: vidImg }));
  assert.equal(vidImg.getAttribute('data-agy-broken-video'), 'true');
  assert.equal(vidImg.style.display, 'none');
  const badge = holder.querySelector('[data-agy-video-badge="true"]');
  assert.ok(badge, 'Broken video img should render .agy-video-badge');
  assert.ok(badge.textContent.includes('MP4'), `Expected MP4 label, got ${badge.textContent}`);

  console.log('test patches/web_optimizations.js video badge fallback: OK');
}

// ---------------------------------------------------------------------------
// 12. Test bridge.core.js network reconnect banner
// ---------------------------------------------------------------------------
{
  const { windowObj, document } = createMockDom();
  windowObj.location = { search: '?port=45157', href: 'https://127.0.0.1:45157/' };
  windowObj.addEventListener = () => {};
  windowObj.dispatchEvent = () => true;
  windowObj.fetch = async () => ({ ok: true, status: 200 });

  const code = fs.readFileSync(path.join(GEN_DIR, 'bridge.core.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  windowObj.__agBridgeCore.notifyNetworkFailure();
  const warnBanner = document.getElementById('ag-net-banner');
  assert.ok(warnBanner, 'notifyNetworkFailure should mount #ag-net-banner');
  assert.ok(warnBanner.textContent.includes('reconnecting'), 'Warn banner should mention reconnecting');

  windowObj.__agBridgeCore.notifyNetworkSuccess();
  const okBanner = document.getElementById('ag-net-banner');
  assert.ok(okBanner, 'notifyNetworkSuccess after failure should update #ag-net-banner');
  assert.equal(okBanner.textContent, 'Reconnected');

  console.log('test bridge.core.js reconnect banner: OK');
}

// ---------------------------------------------------------------------------
// 13. Test patch_main.py applies all 4 v2.35.2 hooks idempotently on web/main.js
// ---------------------------------------------------------------------------
{
  const mainJs = path.join(WEB_DIR, 'main.js');
  if (fs.existsSync(mainJs)) {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ag-patch-real-'));
    const tmpTarget = path.join(tmpDir, 'main.js');
    fs.copyFileSync(mainJs, tmpTarget);
    const out1 = execFileSync('python3', ['-B', path.join(WEB_DIR, 'patch_main.py'), tmpTarget], { encoding: 'utf8' });
    assert.ok(out1.includes('Patched Desktop Mode toggle'), `Desktop Mode missing in output: ${out1}`);
    assert.ok(out1.includes('Patched Account Switcher'), `Account Switcher missing in output: ${out1}`);
    assert.ok(out1.includes('Patched composer any-file attachments'), `Attachments missing in output: ${out1}`);
    assert.ok(out1.includes('Disabled desktop sandboxMode'), `Sandbox patch missing in output: ${out1}`);
    assert.ok(!fs.readFileSync(tmpTarget, 'utf8').includes('sandboxMode:!0'), 'Patched main.js must not contain sandboxMode:!0');
    // Second run must be an idempotent no-op
    const patchedSize = fs.statSync(tmpTarget).size;
    execFileSync('python3', ['-B', path.join(WEB_DIR, 'patch_main.py'), tmpTarget], { encoding: 'utf8' });
    assert.equal(fs.statSync(tmpTarget).size, patchedSize, 'patch_main.py must be idempotent on second run');
    fs.rmSync(tmpDir, { recursive: true, force: true });
    console.log('test patch_main.py v2.35.2 hooks & idempotency: OK');
  }
}

// ---------------------------------------------------------------------------
// 14. Test bridge.core.js non-idempotent POST handoff grace period
// ---------------------------------------------------------------------------
{
  const makeFetchEnv = (fetchImpl) => {
    const { windowObj } = createMockDom();
    windowObj.location = { search: '?port=45123', href: 'https://127.0.0.1:45123/?port=45123' };
    windowObj.Headers = globalThis.Headers;
    windowObj.setTimeout = (fn) => { fn(); return 1; };
    windowObj.fetch = fetchImpl;
    return windowObj;
  };

  // 14a. Non-idempotent POST recovers within 4 network-error retries
  let postAttempts = 0;
  const windowObj = makeFetchEnv(async (url) => {
    if (String(url).includes('/SendUserCascadeMessage')) {
      postAttempts++;
      if (postAttempts <= 3) throw new Error('ERR_NETWORK_CHANGED');
      return { status: 200, ok: true };
    }
    return { status: 200, ok: true };
  });
  const code = fs.readFileSync(path.join(GEN_DIR, 'bridge.core.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  const okResp = await windowObj.fetch('exa.language_server_pb.LanguageServerService/SendUserCascadeMessage', {
    method: 'POST',
    body: '{}',
  });
  assert.equal(okResp.status, 200);
  assert.equal(postAttempts, 4, 'Non-idempotent POST should recover across 3 transient handoff errors');

  // 14b. Persistently failing non-idempotent POST caps at 4 retries (5 total calls)
  let failAttempts = 0;
  const env2 = makeFetchEnv(async () => {
    failAttempts++;
    throw new Error('ERR_CONNECTION_RESET');
  });
  vm.runInNewContext(code, env2);
  let threw = false;
  try {
    await env2.fetch('exa.language_server_pb.LanguageServerService/SendUserCascadeMessage', {
      method: 'POST',
      body: '{}',
    });
  } catch (_) {
    threw = true;
  }
  assert.equal(threw, true);
  assert.equal(failAttempts, 5, 'Persistent non-idempotent POST must stop after 4 retries (5 attempts total)');

  // 14c. Aborted POST fails immediately with 0 retries
  let abortAttempts = 0;
  const env3 = makeFetchEnv(async () => {
    abortAttempts++;
    const err = new Error('The operation was aborted');
    err.name = 'AbortError';
    throw err;
  });
  vm.runInNewContext(code, env3);
  let aborted = false;
  try {
    await env3.fetch('exa.language_server_pb.LanguageServerService/SendUserCascadeMessage', {
      method: 'POST',
      body: '{}',
      signal: { aborted: true },
    });
  } catch (_) {
    aborted = true;
  }
  assert.equal(aborted, true);
  assert.equal(abortAttempts, 1, 'Aborted POST must fail immediately without retrying');

  console.log('test bridge.core.js handoff grace period: OK');
}
