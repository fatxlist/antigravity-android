/**
 * patches/web_optimizations/bidi.js - Automatic BiDi/RTL text alignment (PR #22)
 * while keeping code blocks, inline code, and terminals strictly LTR-isolated.
 */
function installBidi(docRef) {
  try {
    if (!docRef.querySelector('style[data-agy="bidi-fix"]')) {
      var bidiStyle = docRef.createElement('style');
      bidiStyle.setAttribute('data-agy', 'bidi-fix');
      bidiStyle.textContent =
        '[role="listitem"] p, [role="listitem"] li, [role="article"] p, [role="article"] li, ' +
        '[data-testid="conversation-view"] p, [data-testid="conversation-view"] li, ' +
        '.message-row p, .message-row li, .markdown p, .markdown li, article p, article li {' +
        '  unicode-bidi: plaintext !important;' +
        '  text-align: start !important;' +
        '}' +
        '[role="listitem"] pre, [role="article"] pre, .message-row pre, .markdown pre, code, .xterm, .xterm * {' +
        '  unicode-bidi: isolate !important;' +
        '  direction: ltr !important;' +
        '  text-align: left !important;' +
        '}';
      (docRef.head || docRef.documentElement).appendChild(bidiStyle);
    }
  } catch (e) {}
}

installBidi(doc);
