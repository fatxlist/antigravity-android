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

function onMutations(mutations) {
  if (state.scanTimer) return;
  if (!mutations || !mutations.length) {
    scheduleScan();
    return;
  }
  for (var i = 0; i < mutations.length; i++) {
    var m = mutations[i];
    var added = m && m.addedNodes;
    if (!added || added.length === 0) continue;
    var target = m.target;
    if (target && target.closest && target.closest('.prose, pre, code')) {
      continue;
    }
    for (var j = 0; j < added.length; j++) {
      var node = added[j];
      if (!node || node.nodeType !== 1) continue;
      if (node.closest && (node.closest(CONFIG.panel) || node.closest('[role="menu"]'))) {
        scheduleScan();
        return;
      }
      if (node.querySelector && (node.querySelector(CONFIG.effortGroup) || node.querySelector(CONFIG.panel))) {
        scheduleScan();
        return;
      }
    }
  }
}

function armPanelObserver() {
  var root = document.querySelector(CONFIG.root) || document.body || document.documentElement;
  if (window.MutationObserver && root) {
    if (!state.observer) {
      state.observer = new MutationObserver(onMutations);
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
