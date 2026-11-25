/**
 * patches/touch_model_menu/scan.js - debounced DOM scan for effort groups,
 * pausing root observer when the model selector panel is closed.
 */
var SCAN_DEBOUNCE_MS = 80;

function isPanelOpen() {
  try {
    return !!(document.querySelector(CONFIG.panel) || document.querySelector(CONFIG.effortGroup));
  } catch (_) {
    return false;
  }
}

function disconnectObserver() {
  if (state.observer && state.observingRoot) {
    try { state.observer.disconnect(); } catch (_) {}
    state.observingRoot = false;
  }
}

function scan() {
  var groups = [];
  try {
    groups = document.querySelectorAll(CONFIG.effortGroup);
  } catch (_) {
    groups = [];
  }
  state.groups = groups.length;
  for (var i = 0; i < groups.length; i++) {
    var group = groups[i];
    var row = (group.closest && group.closest(CONFIG.menuItemRole)) || group.parentElement || group;
    registerRow(row);
  }
  if (!isPanelOpen()) {
    disconnectObserver();
  }
}

function scheduleScan() {
  if (state.scanTimer) clearTimeout(state.scanTimer);
  state.scanTimer = setTimeout(function () {
    state.scanTimer = 0;
    scan();
  }, SCAN_DEBOUNCE_MS);
}

function armPanelObserver() {
  var root = document.querySelector(CONFIG.root) || document.body || document.documentElement;
  if (window.MutationObserver && root) {
    if (!state.observer) {
      state.observer = new MutationObserver(scheduleScan);
    }
    if (!state.observingRoot) {
      try {
        state.observer.observe(root, { childList: true, subtree: true });
        state.observingRoot = true;
      } catch (_) {}
    }
  }
  setTimeout(scan, 16);
  setTimeout(scan, 80);
  setTimeout(scan, 200);
}
