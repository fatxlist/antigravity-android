/**
 * patches/mobile_bridge/paths.js - clickable local file paths and APK install card.
 *
 * Detects taps on file:// links, local paths inside chat <code> elements, or
 * attachment pills carrying data-uri / data-file-uri, and presents an inline
 * action card to install APKs, open the file, open the folder, or copy path.
 */
var pathCardListenerInstalled = false;

function extractLocalPath(raw) {
  if (!raw || typeof raw !== 'string') return null;
  var s = raw.trim().replace(/^["'`(]+|["'`.,;:!?)]+$/g, '');
  if (!s) return null;
  if (/^file:\/\//i.test(s)) {
    try {
      s = decodeURIComponent(s.replace(/^file:\/\//i, ''));
    } catch (_) {
      s = s.replace(/^file:\/\//i, '');
    }
  }
  // Ignore URL hashes/line anchors if appended with #L...
  var hashAt = s.indexOf('#');
  if (hashAt > 0) s = s.slice(0, hashAt);
  if (s.indexOf('://') !== -1) return null;
  if (/^\/(?:storage\/emulated\/|sdcard\/|data\/(?:user\/[0-9]+\/|data\/))/.test(s)) {
    return s;
  }
  return null;
}

function findClickedPathInfo(target) {
  var cur = target;
  var depth = 0;
  while (cur && depth < 6) {
    if (isOwnBridgeElement(cur)) return null;
    if (isTerminalElement(cur)) return null;
    var tag = cur.tagName ? String(cur.tagName).toUpperCase() : '';
    if (tag === 'TEXTAREA' || tag === 'INPUT') return null;

    if (cur.getAttribute) {
      var dataUri = cur.getAttribute('data-file-uri') || cur.getAttribute('data-uri') || cur.getAttribute('data-path');
      var extractedAttr = extractLocalPath(dataUri || '');
      if (extractedAttr) {
        return { path: extractedAttr, element: cur };
      }
      if (tag === 'A') {
        var href = cur.getAttribute('href') || '';
        var extractedHref = extractLocalPath(href);
        if (extractedHref) {
          return { path: extractedHref, element: cur };
        }
      }
    }
    if (tag === 'CODE') {
      var txt = (cur.textContent || '').trim();
      if (txt.length <= 512 && txt.indexOf('\n') === -1) {
        var extractedCode = extractLocalPath(txt);
        if (extractedCode) {
          return { path: extractedCode, element: cur };
        }
      }
    }
    cur = cur.parentNode;
    depth++;
  }
  return null;
}

function dismissPathCard() {
  if (!doc) return;
  var existing = doc.getElementById('ag-mb-path-card');
  if (existing && existing.parentNode) {
    existing.parentNode.removeChild(existing);
  }
}

function showPathActionCard(pathStr) {
  if (!doc || !doc.body || !pathStr) return false;
  dismissPathCard();
  ensureBridgeStyles();

  var android = getAndroid();
  var card = doc.createElement('div');
  card.id = 'ag-mb-path-card';
  card.setAttribute('data-ag-mobile-bridge', 'true');
  card.setAttribute('role', 'dialog');
  card.setAttribute('aria-label', 'File Actions');

  var hdr = doc.createElement('div');
  hdr.className = 'ag-mb-hdr';
  var title = doc.createElement('span');
  title.className = 'ag-mb-title';
  var baseName = pathStr.split('/').pop() || pathStr;
  title.textContent = baseName;
  var closeBtn = doc.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'ag-mb-close';
  closeBtn.setAttribute('aria-label', 'Close file actions');
  closeBtn.textContent = '\u00d7';
  closeBtn.addEventListener('click', dismissPathCard);
  hdr.appendChild(title);
  hdr.appendChild(closeBtn);
  card.appendChild(hdr);

  var pathBox = doc.createElement('div');
  pathBox.className = 'ag-mb-path-text';
  pathBox.textContent = pathStr;
  card.appendChild(pathBox);

  var actions = doc.createElement('div');
  actions.className = 'ag-mb-voice-row';

  if (/\.apk$/i.test(pathStr)) {
    var apkBtn = doc.createElement('button');
    apkBtn.type = 'button';
    apkBtn.id = 'ag-mb-path-install-apk';
    apkBtn.className = 'ag-mb-btn';
    apkBtn.textContent = 'Install APK';
    apkBtn.addEventListener('click', function () {
      dismissPathCard();
      if (android && typeof android.installLocalApk === 'function') {
        if (!android.installLocalApk(getNativeToken(), pathStr)) {
          showBridgeToast('Cannot install APK at this path');
        }
      } else {
        showBridgeToast('Native APK installer unavailable');
      }
    });
    actions.appendChild(apkBtn);
  }

  var openBtn = doc.createElement('button');
  openBtn.type = 'button';
  openBtn.id = 'ag-mb-path-open-file';
  openBtn.className = 'ag-mb-btn-secondary';
  openBtn.textContent = 'Open';
  openBtn.addEventListener('click', function () {
    dismissPathCard();
    if (android && typeof android.openLocalPath === 'function') {
      if (!android.openLocalPath(getNativeToken(), pathStr, false)) {
        showBridgeToast('File not found or restricted');
      }
    }
  });
  actions.appendChild(openBtn);

  var folderBtn = doc.createElement('button');
  folderBtn.type = 'button';
  folderBtn.id = 'ag-mb-path-open-folder';
  folderBtn.className = 'ag-mb-btn-secondary';
  folderBtn.textContent = 'Open Folder';
  folderBtn.addEventListener('click', function () {
    dismissPathCard();
    if (android && typeof android.openLocalPath === 'function') {
      if (!android.openLocalPath(getNativeToken(), pathStr, true)) {
        showBridgeToast('Folder not found or restricted');
      }
    }
  });
  actions.appendChild(folderBtn);

  var copyBtn = doc.createElement('button');
  copyBtn.type = 'button';
  copyBtn.id = 'ag-mb-path-copy';
  copyBtn.className = 'ag-mb-btn-secondary';
  copyBtn.textContent = 'Copy';
  copyBtn.addEventListener('click', function () {
    dismissPathCard();
    if (global.navigator && global.navigator.clipboard && typeof global.navigator.clipboard.writeText === 'function') {
      try {
        global.navigator.clipboard.writeText(pathStr);
      } catch (_) {}
    }
    showBridgeToast('Path copied');
  });
  actions.appendChild(copyBtn);

  card.appendChild(actions);
  doc.body.appendChild(card);
  return true;
}

function installPathClickInterceptor() {
  if (pathCardListenerInstalled || !doc || typeof doc.addEventListener !== 'function') return;
  pathCardListenerInstalled = true;

  doc.addEventListener('click', function (ev) {
    var info = findClickedPathInfo(ev && ev.target);
    if (!info) return;
    if (typeof ev.preventDefault === 'function') ev.preventDefault();
    if (typeof ev.stopPropagation === 'function') ev.stopPropagation();
    showPathActionCard(info.path);
  }, true);

  doc.addEventListener('pointerdown', function (ev) {
    var card = doc.getElementById('ag-mb-path-card');
    if (!card) return;
    if (ev && ev.target && isOwnBridgeElement(ev.target)) return;
    dismissPathCard();
  }, true);
}
