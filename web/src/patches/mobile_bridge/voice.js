/**
 * patches/mobile_bridge/voice.js - native Android Voice Service & Dictation bridge.
 *
 * - Exposes window.__agDeliverVoiceText(text, isFinal) for Android SpeechRecognizer.
 * - Exposes window.__agVoiceServiceStateChanged({active, muted}) for VoiceForegroundService.
 * - Non-invasively observes window.WebSocket for /voice/ws sessions so Live Voice
 *   Call automatically starts/stops VoiceForegroundService (AudioFocus + notification).
 */
function isVoiceServiceActiveNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceServiceActive === 'function') {
      return !!android.isVoiceServiceActive();
    }
  } catch (_) {}
  return !!state.voiceServiceActive;
}

function isVoiceMutedNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceMuted === 'function') {
      return !!android.isVoiceMuted();
    }
  } catch (_) {}
  return !!state.voiceServiceMuted;
}

function isVoiceDictatingNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceDictating === 'function') {
      return !!android.isVoiceDictating();
    }
  } catch (_) {}
  return !!state.dictating;
}

function startNativeVoiceService() {
  var android = getAndroid();
  try {
    if (android && typeof android.startVoiceService === 'function') {
      var res = android.startVoiceService(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceActive = true;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] startVoiceService failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function stopNativeVoiceService() {
  var android = getAndroid();
  try {
    if (android && typeof android.stopVoiceService === 'function') {
      var res = android.stopVoiceService(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceActive = false;
      state.voiceServiceMuted = false;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] stopVoiceService failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function setNativeVoiceMuted(muted) {
  var next = !!muted;
  applyMicTracksMute(next);
  var android = getAndroid();
  try {
    if (android && typeof android.setVoiceMuted === 'function') {
      var res = android.setVoiceMuted(getNativeToken(), next);
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceMuted = next;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true, muted: next };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] setVoiceMuted failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  state.voiceServiceMuted = next;
  if (typeof syncDrawerState === 'function') syncDrawerState();
  return { ok: false, reason: 'bridge_unavailable' };
}

function startNativeVoiceDictation() {
  var android = getAndroid();
  try {
    if (android && typeof android.startVoiceDictation === 'function') {
      var res = android.startVoiceDictation(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.dictating = true;
      state.voiceInterim = '';
      if (typeof syncDrawerState === 'function') syncDrawerState();
      if (typeof showBridgeToast === 'function') showBridgeToast('Listening\u2026');
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] startVoiceDictation failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function stopNativeVoiceDictation() {
  var android = getAndroid();
  state.dictating = false;
  state.voiceInterim = '';
  try {
    if (android && typeof android.stopVoiceDictation === 'function') {
      android.stopVoiceDictation(getNativeToken());
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] stopVoiceDictation failed:', e); } catch (_) {}
    if (typeof syncDrawerState === 'function') syncDrawerState();
    return { ok: false, reason: 'bridge_error' };
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
  return { ok: false, reason: 'bridge_unavailable' };
}

function applyMicTracksMute(muted) {
  var alive = [];
  for (var i = 0; i < state.activeMicTracks.length; i++) {
    var tr = state.activeMicTracks[i];
    if (!tr || tr.readyState === 'ended') continue;
    try { tr.enabled = !muted; } catch (_) {}
    alive.push(tr);
  }
  state.activeMicTracks = alive;
}

function deliverVoiceText(text, isFinal) {
  var str = typeof text === 'string' ? text.trim() : '';
  var finalFlag = isFinal !== false;

  if (!finalFlag) {
    state.dictating = true;
    state.voiceInterim = str;
    if (typeof syncDrawerState === 'function') syncDrawerState();
    if (str && typeof showBridgeToast === 'function') {
      showBridgeToast('\ud83c\udfa4 ' + (str.length > 64 ? str.slice(0, 61) + '\u2026' : str));
    }
    return { ok: true, interim: true, text: str };
  }

  state.dictating = false;
  state.voiceInterim = '';
  if (typeof syncDrawerState === 'function') syncDrawerState();

  if (!str) {
    return { ok: false, reason: 'empty_transcript' };
  }

  state.voiceDeliveries++;
  var composer = findComposer();
  if (composer && insertTextIntoEditable(composer, str, ' ')) {
    if (typeof showBridgeToast === 'function') {
      showBridgeToast('Voice text inserted');
    }
    return { ok: true, inserted: true, text: str };
  }

  // No composer mounted yet: reuse Share Sheet buffer banner so text is never lost.
  return deliverSharedPayload({ text: str });
}

function onVoiceDictationStateChanged(payload) {
  var info = payload;
  if (typeof payload === 'string') {
    try { info = JSON.parse(payload); } catch (_) { info = null; }
  }
  if (!info || typeof info !== 'object') return;
  state.dictating = !!info.listening;
  if (!state.dictating) {
    state.voiceInterim = '';
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
  if (info.error && typeof showBridgeToast === 'function') {
    showBridgeToast('Voice dictation: ' + String(info.error));
  }
}

function onVoiceServiceStateChanged(payload) {
  var info = payload;
  if (typeof payload === 'string') {
    try { info = JSON.parse(payload); } catch (_) { info = null; }
  }
  if (!info || typeof info !== 'object') return;

  var active = !!info.active;
  var muted = !!info.muted;
  var wasActive = state.voiceServiceActive;
  state.voiceServiceActive = active;
  state.voiceServiceMuted = active ? muted : false;
  if (!active) {
    stopMicLevelMeter();
    if (state.activeVoiceSockets.length === 0 && state.voiceWsState !== 'error') {
      state.voiceWsState = 'idle';
    }
  }

  applyMicTracksMute(state.voiceServiceMuted);

  // If the user tapped "Stop Voice" in the Android notification shade, close active /voice/ws sockets.
  if (!active && wasActive && state.activeVoiceSockets.length > 0) {
    var sockets = state.activeVoiceSockets.slice();
    state.activeVoiceSockets = [];
    for (var i = 0; i < sockets.length; i++) {
      try {
        if (sockets[i] && sockets[i].readyState <= 1) {
          sockets[i].close(1000, 'stopped_from_notification');
        }
      } catch (_) {}
    }
  }

  if (typeof syncDrawerState === 'function') syncDrawerState();
  try {
    if (typeof global.CustomEvent === 'function') {
      global.dispatchEvent(new CustomEvent('ag-voice-service-state', {
        detail: { active: state.voiceServiceActive, muted: state.voiceServiceMuted }
      }));
    }
  } catch (_) {}
}

function isVoiceWebSocketUrl(url) {
  if (!url) return false;
  return /\/voice\/ws(?:[?#]|$)/i.test(String(url));
}

function formatVoiceMicError(err) {
  var name = err && err.name ? String(err.name) : '';
  var msg = err && err.message ? String(err.message) : '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || /permission|denied/i.test(msg)) {
    return 'Microphone permission denied — allow Microphone in Android Settings';
  }
  if (name === 'NotFoundError' || /no.*device|not found/i.test(msg)) {
    return 'No microphone input device found';
  }
  if (name === 'NotReadableError' || /in use|busy|could not start audio source/i.test(msg)) {
    return 'Microphone is busy in another app';
  }
  return msg ? ('Microphone error: ' + msg) : 'Microphone initialization failed';
}

function stopMicLevelMeter() {
  if (typeof state.voiceMeterCleanup === 'function') {
    try { state.voiceMeterCleanup(); } catch (_) {}
    state.voiceMeterCleanup = null;
  }
  state.voiceMicLevel = 0;
  try {
    var fill = doc && doc.getElementById('ag-mb-voice-level-fill');
    if (fill) fill.style.width = '0%';
  } catch (_) {}
}

function attachMicLevelMeter(stream) {
  stopMicLevelMeter();
  try {
    var Ctx = global.AudioContext || global.webkitAudioContext;
    if (!Ctx || !stream || typeof stream.getAudioTracks !== 'function' || !stream.getAudioTracks().length) {
      return;
    }
    var ctx = new Ctx();
    if (typeof ctx.createMediaStreamSource !== 'function' || typeof ctx.createAnalyser !== 'function') {
      if (typeof ctx.close === 'function') ctx.close().catch(function () {});
      return;
    }
    var source = ctx.createMediaStreamSource(stream);
    var analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);
    var bins = new Uint8Array(analyser.frequencyBinCount);
    var timer = setInterval(function () {
      try {
        analyser.getByteTimeDomainData(bins);
        var sumSq = 0;
        for (var i = 0; i < bins.length; i++) {
          var v = (bins[i] - 128) / 128;
          sumSq += v * v;
        }
        var rms = Math.sqrt(sumSq / (bins.length || 1));
        var pct = Math.max(0, Math.min(100, Math.round(rms * 260)));
        state.voiceMicLevel = state.voiceServiceMuted ? 0 : pct;
        var fill = doc && doc.getElementById('ag-mb-voice-level-fill');
        if (fill) fill.style.width = state.voiceMicLevel + '%';
      } catch (_) {}
    }, 180);
    state.voiceMeterCleanup = function () {
      clearInterval(timer);
      try { source.disconnect(); } catch (_) {}
      try { analyser.disconnect(); } catch (_) {}
      try { if (typeof ctx.close === 'function') ctx.close().catch(function () {}); } catch (_) {}
    };
  } catch (_) {}
}

function trackVoiceSocket(ws) {
  if (!ws) return;
  var cleaned = false;
  state.voiceWsState = 'connecting';
  state.voiceLastError = '';
  if (typeof syncDrawerState === 'function') syncDrawerState();

  function onOpen() {
    if (cleaned) return;
    state.voiceWsState = 'open';
    state.voiceLastError = '';
    if (state.activeVoiceSockets.indexOf(ws) === -1) {
      state.activeVoiceSockets.push(ws);
    }
    if (state.activeVoiceSockets.length === 1) {
      startNativeVoiceService();
    }
    if (typeof syncDrawerState === 'function') syncDrawerState();
  }
  function onError() {
    state.voiceWsState = 'error';
    state.voiceLastError = 'Live Voice WebSocket (/voice/ws) connection error';
    if (typeof showBridgeToast === 'function') showBridgeToast(state.voiceLastError);
    onDone();
  }
  function onClose(ev) {
    if (ev && typeof ev.code === 'number' && ev.code !== 1000 && ev.code !== 1005) {
      state.voiceWsState = 'error';
      state.voiceLastError = 'Live Voice closed (code ' + ev.code + (ev.reason ? ': ' + ev.reason : '') + ')';
      if (typeof showBridgeToast === 'function') showBridgeToast(state.voiceLastError);
    }
    onDone();
  }
  function onDone() {
    if (cleaned) return;
    cleaned = true;
    var idx = state.activeVoiceSockets.indexOf(ws);
    if (idx >= 0) state.activeVoiceSockets.splice(idx, 1);
    if (state.activeVoiceSockets.length === 0) {
      stopMicLevelMeter();
      if (state.voiceWsState !== 'error') {
        state.voiceWsState = 'idle';
      }
      if (state.voiceServiceActive) {
        stopNativeVoiceService();
      }
    }
    if (typeof syncDrawerState === 'function') syncDrawerState();
  }
  try {
    ws.addEventListener('open', onOpen);
    ws.addEventListener('close', onClose);
    ws.addEventListener('error', onError);
  } catch (_) {}
}

function installVoiceWebSocketHook() {
  var NativeWS = global.WebSocket;
  if (typeof NativeWS !== 'function' || NativeWS.__agVoiceWrapped) return;

  function WrappedWebSocket(url, protocols) {
    var ws = arguments.length > 1 ? new NativeWS(url, protocols) : new NativeWS(url);
    if (isVoiceWebSocketUrl(url)) {
      trackVoiceSocket(ws);
    }
    return ws;
  }

  WrappedWebSocket.prototype = NativeWS.prototype;
  WrappedWebSocket.CONNECTING = NativeWS.CONNECTING;
  WrappedWebSocket.OPEN = NativeWS.OPEN;
  WrappedWebSocket.CLOSING = NativeWS.CLOSING;
  WrappedWebSocket.CLOSED = NativeWS.CLOSED;
  WrappedWebSocket.__agVoiceWrapped = true;

  try {
    global.WebSocket = WrappedWebSocket;
  } catch (_) {}
}

function installMicTrackObserver() {
  try {
    var md = global.navigator && global.navigator.mediaDevices;
    if (!md || typeof md.getUserMedia !== 'function' || md.getUserMedia.__agVoiceWrapped) return;
    var origGum = md.getUserMedia.bind(md);
    var wrappedGum = function (constraints) {
      var promise = origGum(constraints);
      if (promise && typeof promise.then === 'function' && constraints && constraints.audio) {
        promise.then(function (stream) {
          state.voiceLastError = '';
          if (!stream || typeof stream.getAudioTracks !== 'function') return;
          var tracks = stream.getAudioTracks();
          for (var i = 0; i < tracks.length; i++) {
            var tr = tracks[i];
            if (!tr) continue;
            if (state.voiceServiceMuted) {
              try { tr.enabled = false; } catch (_) {}
            }
            if (state.activeMicTracks.indexOf(tr) === -1) {
              state.activeMicTracks.push(tr);
            }
            try {
              tr.addEventListener('ended', function (ev) {
                var idx = state.activeMicTracks.indexOf(ev.target);
                if (idx >= 0) state.activeMicTracks.splice(idx, 1);
                if (state.activeMicTracks.length === 0) stopMicLevelMeter();
              });
            } catch (_) {}
          }
          attachMicLevelMeter(stream);
        }, function (err) {
          stopMicLevelMeter();
          state.voiceWsState = 'error';
          state.voiceLastError = formatVoiceMicError(err);
          if (typeof showBridgeToast === 'function') showBridgeToast(state.voiceLastError);
          if (typeof syncDrawerState === 'function') syncDrawerState();
        });
      }
      return promise;
    };
    wrappedGum.__agVoiceWrapped = true;
    md.getUserMedia = wrappedGum;
  } catch (_) {}
}

function installMicrophonePermissionShim() {
  try {
    var perms = global.navigator && global.navigator.permissions;
    if (!perms || typeof perms.query !== 'function' || perms.query.__agMicWrapped) return;
    var origQuery = perms.query.bind(perms);

    var makeMicStatus = function (nativeState) {
      return {
        name: 'microphone',
        state: nativeState,
        onchange: null,
        addEventListener: function () {},
        removeEventListener: function () {}
      };
    };

    var readNativeMicState = function () {
      try {
        var android = getAndroid();
        if (android && typeof android.getMicrophonePermissionState === 'function') {
          var s = String(android.getMicrophonePermissionState() || '');
          if (s === 'granted' || s === 'prompt') return s;
        }
      } catch (_) {}
      return '';
    };

    var wrappedQuery = function (desc) {
      if (!desc || desc.name !== 'microphone') {
        return origQuery(desc);
      }
      return Promise.resolve().then(function () {
        return origQuery(desc);
      }).then(function (status) {
        if (status && status.state === 'denied') {
          var nativeState = readNativeMicState();
          if (nativeState) return makeMicStatus(nativeState);
        }
        return status;
      }, function (err) {
        var nativeState = readNativeMicState();
        if (nativeState) return makeMicStatus(nativeState);
        throw err;
      });
    };

    wrappedQuery.__agMicWrapped = true;
    try {
      perms.query = wrappedQuery;
    } catch (_) {
      Object.defineProperty(perms, 'query', {
        value: wrappedQuery,
        configurable: true,
        writable: true
      });
    }
  } catch (_) {}
}
