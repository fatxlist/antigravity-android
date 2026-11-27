/**
 * patches/touch_model_menu/install.js - initial scan + lazy observer activation when model menu opens.
 */
function install() {
  if (state.installed) return;
  state.hasTouch = detectTouch();
  state.installed = true;

  if (!state.hasTouch) {
    console.log('[agTouchModelMenu] no touch input detected; patch is a no-op.');
    return;
  }

  if (window.MutationObserver && !state.observer) {
    state.observer = new MutationObserver(onMutations);
  }

  scan();

  var onInteract = function (ev) {
    var t = ev && ev.target;
    if (!t || !t.closest) return;
    if (t.closest(CONFIG.trigger) || t.closest(CONFIG.panel) || isPanelOpen()) {
      armPanelObserver();
    }
  };

  if (document && document.addEventListener) {
    document.addEventListener('pointerdown', onInteract, { capture: true, passive: true });
    document.addEventListener('click', onInteract, { capture: true, passive: true });
  }

  if (isPanelOpen()) {
    armPanelObserver();
  }
  console.log('[agTouchModelMenu] active (rows=' + state.rows + ').');
}
